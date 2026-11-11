package com.rag.dto;

import lombok.Data;

/**
 * 吉祥物外观偏好更新请求
 * 花色/换装/名字随用户账号持久化，跨浏览器与设备保留
 */
@Data
public class UpdateMascotPrefsRequest {

    /** 花色：classic/gray/pink/cow，空表示保持不变 */
    private String skin;

    /** 换装：none/hat/glasses/shades/crown，空表示保持不变 */
    private String outfit;

    /** 名字，最长 8 字，空表示保持不变 */
    private String name;
}
