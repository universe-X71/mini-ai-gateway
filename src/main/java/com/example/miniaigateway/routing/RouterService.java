package com.example.miniaigateway.routing;

import com.example.miniaigateway.adapter.ModelAdapter;
import com.example.miniaigateway.adapter.impl.ModelAdapterFactory;
import com.example.miniaigateway.config.ProviderProperties;
import com.example.miniaigateway.dto.ChatCompletionRequest;
import com.example.miniaigateway.dto.ChatCompletionResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class RouterService {

    private final ModelAdapterFactory adapterFactory;
    private final ProviderProperties providerProperties;
    private final ProviderPricingService pricingService;
    private final LatencyTracker latencyTracker;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @Value("${gateway.routing.strategy:round-robin}")
    private String strategyName;

    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public RouterService(ModelAdapterFactory adapterFactory,
                         ProviderProperties providerProperties,
                         ProviderPricingService pricingService,
                         LatencyTracker latencyTracker,
                         CircuitBreakerRegistry circuitBreakerRegistry,
                         RetryRegistry retryRegistry) {
        this.adapterFactory = adapterFactory;
        this.providerProperties = providerProperties;
        this.pricingService = pricingService;
        this.latencyTracker = latencyTracker;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    /**
     * 供 Controller 用于打响应头，列出候选 provider 链
     */
    public List<ModelAdapter> resolveCandidatesForLog(String model) {
        return resolveCandidates(model);
    }

    /**
     * 带 Fallback 的流式调用
     * 只在"首字节前"发生失败时才 fallback，一旦开始推送 token 就不再切换
     */
    public Flux<ServerSentEvent<String>> chatCompletionStream(
            ChatCompletionRequest request, int promptTokens) {
        String model = request.getModel();
        List<ModelAdapter> candidates = resolveCandidates(model);

        if (candidates.isEmpty()) {
            return Flux.error(new IllegalArgumentException(
                    "No provider supports model: " + model));
        }

        return applyStrategy(candidates, request, promptTokens)
                .flatMapMany(sorted -> executeStreamChain(sorted, 0, request));
    }

    /**
     * 递归尝试候选链的流式版本
     */
    private Flux<ServerSentEvent<String>> executeStreamChain(
            List<ModelAdapter> chain, int index, ChatCompletionRequest request) {
        if (index >= chain.size()) {
            return Flux.error(new RuntimeException(
                    "All providers failed for model: " + request.getModel()));
        }

        ModelAdapter adapter = chain.get(index);
        String providerName = adapter.providerName();
        boolean isFallback = index > 0;

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(providerName);
        Retry retry = retryRegistry.retry(providerName);

        // 关键：追踪是否已经收到过 token
        AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
        AtomicLong ttftMs = new AtomicLong(0);
        long start = System.currentTimeMillis();

        // 复制请求并替换 model 名（跨 provider 映射）
        ChatCompletionRequest providerRequest = cloneRequest(request);
        providerRequest.setModel(resolveModelForProvider(adapter, request.getModel()));

        return adapter.chatCompletionStream(providerRequest)
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .transformDeferred(RetryOperator.of(retry))
                .doOnNext(event -> {
                    if (firstTokenReceived.compareAndSet(false, true)) {
                        long ttft = System.currentTimeMillis() - start;
                        ttftMs.set(ttft);
                        log.info("[Stream] Provider {} first token received (ttft={}ms, fallback={})",
                                providerName, ttft, isFallback);
                    }
                })
                .doOnComplete(() -> {
                    long totalMs = System.currentTimeMillis() - start;
                    if (firstTokenReceived.get()) {
                        latencyTracker.record(providerName, ttftMs.get(), totalMs).subscribe();
                    }
                    log.info("[Stream] Provider {} completed (total={}ms, fallback={})",
                            providerName, totalMs, isFallback);
                })
                .onErrorResume(e -> {
                    if (firstTokenReceived.get()) {
                        // 已经收到过 token，不再 fallback，直接抛错给客户端
                        log.warn("[Stream] Provider {} failed mid-stream after first token: {}, aborting",
                                providerName, e.getMessage());
                        latencyTracker.recordFailure(providerName).subscribe();
                        return Flux.error(e);
                    }
                    // 还没收到 token，安全 fallback
                    log.warn("[Stream] Provider {} failed before first token: {}, trying next",
                            providerName, e.getMessage());
                    latencyTracker.recordFailure(providerName).subscribe();
                    return executeStreamChain(chain, index + 1, request);
                });
    }

    /**
     * 带 Fallback 的对话调用
     */
    public Mono<ProviderResponse<ChatCompletionResponse>> chatCompletion(
            ChatCompletionRequest request, int promptTokens) {
        String model = request.getModel();

        // 1. 从 provider 池中筛选支持该模型的
        List<ModelAdapter> candidates = resolveCandidates(model);

        if (candidates.isEmpty()) {
            return Mono.error(new IllegalArgumentException(
                    "No provider supports model: " + model));
        }

        // 2. 按策略排序
        return applyStrategy(candidates, request, promptTokens)
                .flatMap(sorted -> executeChain(sorted, 0, request));
    }

    /**
     * 从 provider 池筛选出支持目标模型的 provider
     */
    private List<ModelAdapter> resolveCandidates(String model) {
        return adapterFactory.getAllAdapters().stream()
                .filter(adapter -> {
                    ProviderProperties.ProviderConfig cfg = providerProperties
                            .getProviders().get(adapter.providerName());
                    if (cfg == null || cfg.getSupportedModels() == null) return false;
                    // 检查 provider 是否声明支持该模型
                    return cfg.getSupportedModels().contains(model)
                            || cfg.getModelMapping().containsKey(model);
                })
                .collect(Collectors.toList());
    }

    /**
     * 按策略排序候选 provider
     */
    private Mono<List<ModelAdapter>> applyStrategy(
            List<ModelAdapter> candidates, ChatCompletionRequest request, int promptTokens) {
        RouteStrategyEnum strategy = parseStrategy(strategyName);

        return switch (strategy) {
            case COST_FIRST -> sortByCost(candidates, request, promptTokens);
            case SPEED_FIRST -> sortBySpeed(candidates);
            case ROUND_ROBIN -> {
                // 轮询：用全局计数器偏移
                int size = candidates.size();
                int start = Math.floorMod(roundRobinCounter.getAndIncrement(), size);
                List<ModelAdapter> rotated = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    rotated.add(candidates.get((start + i) % size));
                }
                yield Mono.just(rotated);
            }
        };
    }

    /**
     * 成本优先：按预估成本升序排列
     */
    private Mono<List<ModelAdapter>> sortByCost(
            List<ModelAdapter> candidates, ChatCompletionRequest request, int promptTokens) {
        int estCompletion = request.getMaxTokens() == null ? 1000 : request.getMaxTokens();

        return Mono.fromCallable(() -> candidates.stream()
                .sorted(Comparator.comparingDouble(a -> {
                    String model = resolveModelForProvider(a, request.getModel());
                    Double cost = pricingService.estimateCost(model, promptTokens, estCompletion);
                    return cost == null ? Double.MAX_VALUE : cost;
                }))
                .collect(Collectors.toList()));
    }

    /**
     * 速度优先：按 P95 TTFT 升序排列
     */
    private Mono<List<ModelAdapter>> sortBySpeed(List<ModelAdapter> candidates) {
        return Mono.fromCallable(() -> candidates)
                .flatMap(list -> {
                    // 并发查询所有 provider 的 P95
                    List<Mono<Long>> p95Monos = list.stream()
                            .map(a -> latencyTracker.getP95Ttft(a.providerName()))
                            .collect(Collectors.toList());

                    return Mono.zip(p95Monos, results -> {
                        return java.util.stream.IntStream.range(0, list.size())
                                .boxed()
                                .sorted(Comparator.comparingLong(i -> {
                                    Long p95 = (Long) results[i];
                                    return p95 == -1 ? Long.MAX_VALUE : p95;
                                }))
                                .map(list::get)
                                .collect(Collectors.toList());
                    });
                });
    }

    /**
     * 获取 provider 实际调用的模型名（考虑 model-mapping）
     */
    private String resolveModelForProvider(ModelAdapter adapter, String requestModel) {
        ProviderProperties.ProviderConfig cfg = providerProperties
                .getProviders().get(adapter.providerName());
        if (cfg != null && cfg.getModelMapping() != null) {
            return cfg.getModelMapping().getOrDefault(requestModel, requestModel);
        }
        return requestModel;
    }

    private RouteStrategyEnum parseStrategy(String name) {
        try {
            return RouteStrategyEnum.valueOf(name.toUpperCase().replace("-", "_"));
        } catch (Exception e) {
            return RouteStrategyEnum.ROUND_ROBIN;
        }
    }

    /**
     * 递归尝试候选链：第 index 个失败则自动降级到 index+1
     */
    private Mono<ProviderResponse<ChatCompletionResponse>> executeChain(
            List<ModelAdapter> chain, int index, ChatCompletionRequest request) {
        if (index >= chain.size()) {
            return Mono.error(new RuntimeException(
                    "All providers failed for model: " + request.getModel()));
        }

        ModelAdapter adapter = chain.get(index);
        String providerName = adapter.providerName();
        boolean isFallback = index > 0;

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(providerName);
        Retry retry = retryRegistry.retry(providerName);

        long start = System.currentTimeMillis();

        // 复制请求并替换 model 名（用于跨 provider 映射）
        ChatCompletionRequest providerRequest = cloneRequest(request);
        providerRequest.setModel(resolveModelForProvider(adapter, request.getModel()));

        return adapter.chatCompletion(providerRequest)
                .transformDeferred(CircuitBreakerOperator.of(cb))
                .transformDeferred(RetryOperator.of(retry))
                .doOnSuccess(resp -> {
                    long totalMs = System.currentTimeMillis() - start;
                    latencyTracker.record(providerName, totalMs, totalMs).subscribe();
                    log.info("Provider {} succeeded (fallback={}, {}ms)",
                            providerName, isFallback, totalMs);
                })
                .map(resp -> new ProviderResponse<>(resp, providerName, isFallback))
                .onErrorResume(e -> {
                    latencyTracker.recordFailure(providerName).subscribe();
                    log.warn("Provider {} failed: {}, trying next", providerName, e.getMessage());
                    return executeChain(chain, index + 1, request);
                });
    }

    private ChatCompletionRequest cloneRequest(ChatCompletionRequest request) {
        ChatCompletionRequest clone = new ChatCompletionRequest();
        clone.setModel(request.getModel());
        clone.setMessages(request.getMessages());
        clone.setTemperature(request.getTemperature());
        clone.setMaxTokens(request.getMaxTokens());
        clone.setStream(request.getStream());
        return clone;
    }
}