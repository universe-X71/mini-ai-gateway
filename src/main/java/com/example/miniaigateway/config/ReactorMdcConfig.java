package com.example.miniaigateway.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jboss.logging.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Slf4j
@Configuration
public class ReactorMdcConfig {

    @PostConstruct
    public void init() {
        // 注册 ThreadLocal 访问器，告诉库怎么读写 MDC
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                "traceId",
                () -> MDC.get("traceId"),           // 读取当前 MDC
                traceId -> MDC.put("traceId", traceId),   // 写入 MDC
                () -> MDC.remove("traceId")          // 清理
        );
        // 自动把 Reactor Context 同步到 MDC
        Hooks.enableAutomaticContextPropagation();
        log.info("MDC context propagation enabled");
    }
}