package com.rag.image.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 单张生图结果
 * 支持 URL 和 base64 两种 OpenAI 兼容返回格式
 */
@Data
@Builder
public class GeneratedImageItem {

    /** 生图历史编号 */
    private Long historyId;

    /** 图片访问地址 */
    private String url;

    /** base64 图片内容 */
    private String b64Json;

    /** 图片媒体类型，用于前端拼接可下载的 data URL */
    private String mimeType;

    /** 后端图片查看地址 */
    private String viewUrl;

    /** 后端图片下载地址 */
    private String downloadUrl;
}
