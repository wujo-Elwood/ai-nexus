package com.rag.health;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个系统组件的健康检查结果
 */
@Data
public class HealthComponent {
    /** 组件标识 */
    private String key;
    /** 前端展示名称 */
    private String label;
    /** 当前状态 */
    private HealthStatus status;
    /** 检查耗时，单位毫秒 */
    private Long latencyMs;
    /** 脱敏后的状态说明 */
    private String message;
    /** 检查时间 */
    private LocalDateTime checkedAt;
    /** 可展示的非敏感扩展指标 */
    private Map<String, Object> details = new LinkedHashMap<>();

    /**
     * 判断组件是否可用
     */
    public boolean isUp() {
        return status == HealthStatus.UP;
    }
}
