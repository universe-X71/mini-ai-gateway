package com.example.miniaigateway.routing;

/**
 * 路由策略
 */
public enum RouteStrategy {
    /** 成本优先：按预估成本升序 */
    COST_FIRST,
    /** 速度优先：按 P95 TTFT 升序 */
    SPEED_FIRST,
    /** 轮询：均匀分发 */
    ROUND_ROBIN
}
