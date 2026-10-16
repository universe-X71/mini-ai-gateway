package com.example.miniaigateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    /** 连接超时 10 秒 */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** 响应超时 60 秒 */
    private static final int RESPONSE_TIMEOUT_SECONDS = 60;

    /** 读超时 60 秒 */
    private static final int READ_TIMEOUT_SECONDS = 60;

    /** 响应体缓冲上限 16 MB（SSE 流式场景需要放宽默认的 256 KB） */
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    /**
     * 通用 WebClient.Builder，各适配器按需 clone 并设置 baseUrl / apiKey
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES));
    }
}
