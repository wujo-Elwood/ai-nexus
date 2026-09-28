package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 大模型供应商实体类，对应数据库 ai_model_provider 表
 * 存储不同 AI 供应商的配置信息，支持动态切换当前使用的模型
 */
@Data
public class ModelProvider {
    /** 供应商ID，主键 */
    private Long id;
    /** 供应商名称，如 DeepSeek、GPT、Mimo */
    private String name;
    /** API 基础地址 */
    private String baseUrl;
    /** API 密钥 */
    private String apiKey;
    /** 模型名称 */
    private String model;
    /** 生图 API 地址，为空时沿用普通 API 基础地址 */
    private String imageBaseUrl;
    /** 生图 API 密钥，为空时沿用普通 API 密钥 */
    private String imageApiKey;
    /** 生图模型名称，为空时沿用普通模型名称 */
    private String imageModel;
    /** 是否激活：1=激活，0=未激活 */
    private Integer isActive;
    /** 创建人用户ID，历史数据为空 */
    private Long createdBy;
    /** 创建人用户名，查询时联表带出，仅用于展示 */
    private String createdByName;
    /** 创建时间 */
    private LocalDateTime createTime;
}
