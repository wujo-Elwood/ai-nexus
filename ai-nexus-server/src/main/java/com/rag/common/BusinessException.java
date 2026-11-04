package com.rag.common;

import lombok.Getter;

/**
 * 业务异常类
 * 用于封装业务逻辑中的异常情况（如用户不存在、权限不足等）
 * 会被 GlobalExceptionHandler 捕获并转换为统一的错误响应
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误状态码（如 400、401、403、404、500） */
    private final int code;

    /**
     * 创建业务异常（默认状态码 500）
     *
     * @param message 错误提示信息
     */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    /**
     * 创建业务异常（自定义状态码）
     *
     * @param code    错误状态码
     * @param message 错误提示信息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
