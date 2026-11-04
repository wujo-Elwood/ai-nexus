package com.rag.health;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康面板聚合响应
 */
@Data
public class SystemHealthResponse {
    /** 系统总体状态 */
    private HealthStatus status;
    /** 聚合检查时间 */
    private LocalDateTime checkedAt;
    /** 外部依赖和磁盘组件状态 */
    private Map<String, HealthComponent> components = new LinkedHashMap<>();
    /** 后台线程池状态 */
    private Map<String, ThreadPoolHealth> threadPools = new LinkedHashMap<>();
}
