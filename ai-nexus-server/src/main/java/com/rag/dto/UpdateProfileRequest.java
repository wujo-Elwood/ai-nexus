package com.rag.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户资料修改请求
 * 用于修改当前登录用户的昵称和头像地址
 */
@Data
public class UpdateProfileRequest {

    /** 昵称，用于页面展示 */
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /** 头像地址，当前页面暂不上传文件，先保留 URL 字段 */
    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;
}
