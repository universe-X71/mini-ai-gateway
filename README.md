# Mini AI Gateway

一个基于 Spring Boot 3 + WebFlux 的轻量级 AI 网关，兼容 OpenAI 协议，支持多模型路由、SSE 流式转发、Token 计量、限流、熔断与自动 Fallback。

## 特性

- ✅ **OpenAI 协议兼容**：`POST /v1/chat/completions`，流式与非流式单一路径
- ✅ **多模型适配**：OpenAI、DeepSeek、Ollama 无缝切换
- ✅ **SSE 流式转发**：基于 WebFlux 的 `Flux<ServerSentEvent>`
- ✅ **Token 计量**：JTokkit 本地预估 Prompt Token
- ✅ **三级限流**：RPM（Redis Lua 滑动窗口）、TPM、每日配额（MySQL 原子扣减）
- ✅ **动态自适应路由**：成本优先 / 速度优先 / 轮询
- ✅ **自动 Fallback**：主 Provider 失败自动切换（流式仅首字节前）
- ✅ **熔断 + 重试**：Resilience4j，每个 Provider 独立熔断器
- ✅ **动态定价**：从 OpenRouter 同步实时单价，持久化到 MySQL
- ✅ **动态延迟统计**：Redis ZSET 滑动窗口计算 P95 TTFT

## 架构

### 分层架构

```
                    ┌──────────────────────────────┐
                    │   客户端 (OpenAI SDK)          │
                    └──────────────┬───────────────┘
                                   │
                    ┌──────────────▼───────────────┐
                    │  ChatCompletionController    │
                    │  (WebFlux + SSE)             │
                    └──────────────┬───────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
    ┌─────────▼─────────┐ ┌────────▼────────┐ ┌────────▼────────┐
    │ RateLimitAspect   │ │  RouterService  │ │  QuotaService   │
    │ (AOP)             │ │                 │ │                 │
    └─────────┬─────────┘ └────────┬────────┘ └────────┬────────┘
              │                    │                    │
    ┌─────────▼─────────┐ ┌────────▼────────┐ ┌────────▼────────┐
    │ TokenRateLimiter  │ │ Pricing / Latency│ │ MySQL (R2DBC)   │
    │ (Redis + Lua)     │ │ (MySQL + Redis) │ │                 │
    └───────────────────┘ └────────┬────────┘ └─────────────────┘
                                   │
                    ┌──────────────▼───────────────┐
                    │     ModelAdapterFactory      │
                    └──────────────┬───────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
┌───────▼────────┐        ┌────────▼───────┐        ┌─────────▼────────┐
│ OpenAiAdapter  │        │ DeepSeekAdapter│        │  OllamaAdapter   │
│ (ChatAnywhere) │        │ (DeepSeek API) │        │ (localhost)      │
└────────────────┘        └────────────────┘        └──────────────────┘
```

### 请求流程

```
请求 → 限流(Redis) → 配额(MySQL) → 路由决策 → Fallback 链
                                                     │
              ┌──────────────────────────────────────┘
              │
    ┌─────────▼─────────┐
    │  主 Provider      │
    │  成功？            │
    └─────┬────────┬────┘
          │        │
        成功      失败
          │        │
          │   ┌────▼─────────────────┐
          │   │ 熔断器打开？          │
          │   │ 是 → 直接尝试下一个    │
          │   │ 否 → 重试(指数退避)    │
          │   └────┬─────────────────┘
          │        │
          │   ┌────▼────────────────┐
          │   │ 流式？              │
          │   │ 有 token 了？       │
          │   └────┬────────────────┘
          │        │
          │   无token  → fallback 到下一个
          │   有token  → 直接断开
          │
    ┌─────▼─────────┐
    │ 返回响应        │
    └───────────────┘
```

## 快速开始

### 依赖

- JDK 21
- Maven 3.9+
- Docker（Nacos、Redis、MySQL）
- Ollama（可选，本地模型）

### 启动依赖

```bash
docker compose up -d    # 启动 Nacos、Redis、MySQL
ollama run qwen3.5:4b   # 本地模型
```

### 配置

在 Nacos 中创建 Data ID 为 `mini-ai-gateway.yml` 的配置，参考 `docs/nacos-config-example.yml`。

### 启动网关

```bash
mvn spring-boot:run
```

### 测试

```bash
# 非流式（身份由 API Key 决定，租户/限流/配额都挂在它对应的 tenant 上）
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-xxx" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}'

# 流式
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-xxx" \
  -d '{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"hi"}]}'
```

## API

| 端点 | 方法 | 说明 |
|---|---|---|
| `/v1/chat/completions` | POST | OpenAI 兼容的对话接口，支持 `stream` 字段 |
| `/actuator/health` | GET | 健康检查 |
| `/actuator/gateway/routes` | GET | 查看网关路由 |
| `/admin/latency` | GET | 查看各 Provider 的 P95/平均延迟/失败率 |
| `/admin/pricing/{model}` | GET | 查看指定模型的定价 |
| `/admin/pricing/reload` | POST | 手动刷新定价缓存 |

## 技术栈

| 模块 | 技术 |
|---|---|
| 框架 | Spring Boot 3.2.5 + WebFlux |
| 网关 | Spring Cloud Gateway |
| 服务发现 | Nacos |
| 缓存/限流 | Redis + Lua |
| 数据库 | MySQL + R2DBC |
| Token 计量 | JTokkit |
| 熔断/重试 | Resilience4j |
| 服务发现 | Nacos |
| 可观测 | Micrometer + Prometheus (Day 11) |

## Roadmap

### 已完成
- [x] OpenAI 兼容接口 + SSE 流式转发
- [x] 多 Provider 适配器
- [x] Token 计量 + RPM/TPM 限流 + 每日配额
- [x] 动态自适应路由（成本 / 速度 / 轮询）
- [x] 自动 Fallback（非流式 + 流式首字节前）
- [x] Resilience4j 熔断 + 重试

### 后续迭代
- [ ] 复杂度感知路由（Complexity-Aware Routing）
- [ ] 流式 Fallback 断点续传
- [ ] 按租户差异化路由
- [ ] 成本统计看板
- [ ] MCP 工具调用集成（Day 10）
- [ ] PII 脱敏 + Prompt 注入防御（Day 8）
- [ ] JWT 鉴权 + 多租户隔离（Day 9）
- [ ] Micrometer + Prometheus 可观测性（Day 11）

## License

MIT