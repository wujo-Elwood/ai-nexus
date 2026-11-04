package com.rag.skill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * web_search 技能搜索源配置实体
 * 按用户维度存储：user_id = 0 为平台默认，其余为用户专属配置
 */
@Data
public class WebSearchConfig {

    /** 平台默认配置占位用户编号 */
    public static final long PLATFORM_USER_ID = 0L;

    /** 主键 */
    private Long id;

    /** 所属用户：0=平台默认，其余为用户专属配置 */
    private Long userId;

    /** 搜索源：tavily/serper/bocha/searxng */
    private String provider;

    /** 搜索源 API Key；searxng 免 Key 可为空 */
    private String apiKey;

    /** 自建搜索源地址（searxng 必填） */
    private String baseUrl;

    /** 单次搜索返回结果条数上限（1-10） */
    private Integer maxResults;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
