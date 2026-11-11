package com.rag.vo;

import lombok.Data;

/**
 * 统一 API 响应包装类
 * 封装所有接口的返回结果，包含状态码、提示信息和数据
 *
 * @param <T> 响应数据类型
 */
@Data
public class Result<T> {
    /** 状态码，200 表示成功，500 表示失败 */
    private int code;
    /** 提示信息 */
    private String message;
    /** 响应数据 */
    private T data;

    /**
     * 返回成功的响应（带数据）
     *
     * @param data 响应数据
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    /**
     * 返回成功的响应（无数据）
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 返回失败的响应（自定义状态码和错误信息）
     *
     * @param code    错误状态码
     * @param message 错误提示信息
     */
    public static <T> Result<T> error(int code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    /**
     * 返回失败的响应（默认状态码 500）
     *
     * @param message 错误提示信息
     */
    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}
