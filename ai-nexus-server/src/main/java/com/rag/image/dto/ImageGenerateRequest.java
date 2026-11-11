package com.rag.image.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 生图请求参数
 * 接收前端提交的提示词、图片尺寸、生成数量和参考图
 */
@Data
public class ImageGenerateRequest {

    /** 生图提示词 */
    @NotBlank(message = "请输入生图提示词")
    private String prompt;

    /** 图片尺寸，例如 1024x1024，auto 表示交给模型自动决定 */
    private String size = "1024x1024";

    /** 生成数量，最多支持同时生成 6 张 */
    @Min(value = 1, message = "至少生成 1 张图片")
    @Max(value = 6, message = "一次最多生成 6 张图片")
    private Integer n = 1;

    /** 参考图 data URL 列表，最多允许 6 张 */
    private List<String> referenceImages = List.of();
}
