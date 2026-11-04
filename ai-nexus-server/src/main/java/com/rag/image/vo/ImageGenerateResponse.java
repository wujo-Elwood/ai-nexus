package com.rag.image.vo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 生图响应结果
 * 返回本次使用的模型信息和图片列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageGenerateResponse {

    /** 供应商名称 */
    private String providerName;

    /** 模型名称 */
    private String modelName;

    /** 图片尺寸 */
    private String size;

    /** 图片列表 */
    private List<GeneratedImageItem> images;
}
