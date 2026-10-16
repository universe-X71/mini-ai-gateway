package com.example.miniaigateway.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ProviderPricingService {

    private static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ModelPricingRepository pricingRepository;

    // modelId -> ModelPricing，启动时从 MySQL 加载，每天同步后刷新
    private final Map<String, ModelPricing> pricingCache = new ConcurrentHashMap<>();

    public ProviderPricingService(WebClient.Builder webClientBuilder,
                                  ObjectMapper objectMapper,
                                  ModelPricingRepository pricingRepository) {
        this.webClient = webClientBuilder.clone().baseUrl(OPENROUTER_MODELS_URL).build();
        this.objectMapper = objectMapper;
        this.pricingRepository = pricingRepository;
    }

    /**
     * 启动时：
     * 1. 从 MySQL 加载已有定价到内存缓存
     * 2. 异步同步一次 OpenRouter 最新定价
     */
    @PostConstruct
    public void init() {
        loadFromDatabase()
                .then(syncFromOpenRouter())
                .subscribe(
                        null,
                        e -> log.error("Failed to init pricing: {}", e.getMessage()),
                        () -> log.info("Pricing initialized, cache size={}", pricingCache.size())
                );
    }

    /**
     * 每天凌晨 3 点同步
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledSync() {
        syncFromOpenRouter().subscribe();
    }

    /**
     * 从 MySQL 加载启用的定价到内存
     */
    public Mono<Void> loadFromDatabase() {
        return pricingRepository.findByEnabledTrue()
                .doOnNext(entity -> {
                    ModelPricing mp = new ModelPricing();
                    mp.setModelId(entity.getModelId());
                    mp.setPromptPrice(entity.getPromptPrice().doubleValue());
                    mp.setCompletionPrice(entity.getCompletionPrice().doubleValue());
                    pricingCache.put(entity.getModelId(), mp);
                })
                .then()
                .doOnSuccess(v -> log.info("Loaded {} pricing records from MySQL", pricingCache.size()));
    }

    /**
     * 从 OpenRouter 拉取最新定价，upsert 到 MySQL，然后刷新缓存
     */
    public Mono<Void> syncFromOpenRouter() {
        return webClient.get()
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .flatMapMany(body -> {
                    try {
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode data = root.get("data");
                        if (data == null || !data.isArray()) return Flux.empty();

                        return Flux.fromIterable(() -> data.elements())
                                .flatMap(this::parseAndUpsert);
                    } catch (Exception e) {
                        log.error("Failed to parse OpenRouter pricing: {}", e.getMessage());
                        return Flux.empty();
                    }
                })
                .then(loadFromDatabase())   // 同步完再刷新缓存
                .onErrorResume(e -> {
                    log.warn("Failed to sync pricing from OpenRouter: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 解析单个模型并 upsert 到 MySQL
     */
    private Mono<Void> parseAndUpsert(JsonNode model) {
        try {
            String id = model.get("id").asText();
            String name = model.has("name") ? model.get("name").asText() : id;
            JsonNode p = model.get("pricing");
            if (p == null) return Mono.empty();

            BigDecimal promptPrice = new BigDecimal(p.get("prompt").asText());
            BigDecimal completionPrice = new BigDecimal(p.get("completion").asText());

            // 先查是否已存在
            return pricingRepository.findByModelId(id)
                    .flatMap(existing -> {
                        // 存在则更新价格，但保留 source=manual 的人工修改（不覆盖）
                        if ("manual".equals(existing.getSource())) {
                            return Mono.empty();
                        }
                        existing.setPromptPrice(promptPrice);
                        existing.setCompletionPrice(completionPrice);
                        existing.setDisplayName(name);
                        return pricingRepository.save(existing);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        // 不存在则新建
                        ModelPricingEntity entity = new ModelPricingEntity();
                        entity.setModelId(id);
                        entity.setDisplayName(name);
                        entity.setPromptPrice(promptPrice);
                        entity.setCompletionPrice(completionPrice);
                        entity.setSource("openrouter");
                        entity.setEnabled(true);
                        return pricingRepository.save(entity);
                    }))
                    .then();
        } catch (Exception e) {
            log.debug("Failed to parse model: {}", e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * 根据模型名查询定价
     * 支持：
     * 1. 精确匹配 "openai/gpt-4o-mini"
     * 2. 带 provider 前缀匹配 "gpt-4o-mini" -> "openai/gpt-4o-mini"
     * 3. 前缀模糊匹配
     */
    public ModelPricing getPricing(String model) {
        ModelPricing mp = pricingCache.get(model);
        if (mp != null) return mp;

        for (String prefix : new String[]{"openai/", "anthropic/", "google/", "deepseek/", "meta/"}) {
            mp = pricingCache.get(prefix + model);
            if (mp != null) return mp;
        }

        String lowerModel = model.toLowerCase();
        return pricingCache.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains(lowerModel))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 估算成本（USD）
     */
    public Double estimateCost(String model, int promptTokens, int completionTokens) {
        ModelPricing mp = getPricing(model);
        if (mp == null) return null;
        double cost = mp.estimateCost(promptTokens, completionTokens);
        log.info("Estimated cost for model {} promptTokens={} completionTokens={} is {} USD",
                model, promptTokens, completionTokens, cost);
        return cost;
    }
}