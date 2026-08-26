package com.rag.controller;

import com.rag.dto.ChangePasswordRequest;
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
