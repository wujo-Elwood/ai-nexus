package com.rag.health;

import lombok.Data;

/**
 * 可观测线程池指标
 */
@Data
public class ThreadPoolHealth {
    /** 线程池 Bean 名称 */
    private String name;
    /** 线程池状态 */
    private HealthStatus status;
    /** 核心线程数 */
    private int corePoolSize;
    /** 最大线程数 */
    private int maximumPoolSize;
    /** 当前线程数 */
    private int poolSize;
    /** 活动线程数 */
    private int activeCount;
    /** 等待队列长度 */
    private int queueSize;
    /** 已完成任务数 */
    private long completedTaskCount;
}
