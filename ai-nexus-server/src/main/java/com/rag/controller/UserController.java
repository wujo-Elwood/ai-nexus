package com.rag.controller;

import com.rag.dto.ChangePasswordRequest;
import com.rag.dto.UpdateMascotPrefsRequest;
import com.rag.dto.UpdateProfileRequest;
import com.rag.service.UserService;
import com.rag.vo.Result;
import com.rag.vo.UserProfileResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 用户中心控制器
 * 提供当前登录用户的资料查看、资料修改和密码修改接口
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 获取当前登录用户资料
     */
    @GetMapping("/profile")
    public Result<UserProfileResponse> getProfile(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(userService.getProfile(userId));
    }

    /**
     * 修改当前登录用户资料
     */
    @PutMapping("/profile")
    public Result<UserProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
                                                     HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(userService.updateProfile(userId, request));
    }

    /**
     * 更新吉祥物外观偏好（花色/换装/名字）
     * 跟随用户账号持久化：换浏览器、换地址、换设备登录都能恢复
     */
    @PutMapping("/mascot-prefs")
    public Result<String> updateMascotPrefs(@RequestBody UpdateMascotPrefsRequest request,
                                            HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(userService.updateMascotPrefs(userId, request));
    }

    /**
     * 修改当前登录用户密码
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        userService.changePassword(userId, request);
        return Result.success();
    }
}
