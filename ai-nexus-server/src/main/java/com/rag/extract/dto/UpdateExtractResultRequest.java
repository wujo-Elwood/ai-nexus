package com.rag.extract.dto;

import lombok.Data;

/**
 * 更新抽取结果请求
 */
@Data
public class UpdateExtractResultRequest {

    /**
     * 新值
     */
    private String newValue;

    /**
     * 备注
     */
    private String remark;
}
