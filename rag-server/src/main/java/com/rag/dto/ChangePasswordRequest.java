package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求
 * 包含原密码、新密码和确认密码
 */
@Data
public class ChangePasswordRequest {

    /** 当前正在使用的密码 */
    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    /** 新密码 */
    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 50, message = "新密码长度需要在 6 到 50 个字符之间")
    private String newPassword;

    /** 确认新密码 */
    @NotBlank(message = "请再次输入新密码")
    private String confirmPassword;
}
