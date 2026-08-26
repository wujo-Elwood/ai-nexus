package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.dto.ChangePasswordRequest;
import com.rag.dto.LoginRequest;
import com.rag.dto.RegisterRequest;
import com.rag.dto.UpdateProfileRequest;
import com.rag.entity.SysUser;
import com.rag.mapper.UserMapper;
import com.rag.utils.JwtUtils;
import com.rag.vo.LoginResponse;
import com.rag.vo.UserProfileResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户服务
 * 处理用户注册、登录等认证相关业务
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtils jwtUtils;

    /** BCrypt 密码编码器，用于密码加密和验证 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户注册
     * 校验用户名唯一性 → 加密密码 → 写入数据库 → 生成 JWT Token
     *
     * @param request 注册请求（用户名、密码、昵称）
     * @return 登录响应（含 Token 和用户信息）
     */
    public LoginResponse register(RegisterRequest request) {
        // 检查用户名是否已存在
        SysUser existing = userMapper.findByUsername(request.getUsername());
        if (existing != null) {
            throw new BusinessException(400, "Username already exists");
        }

        // 创建用户，密码使用 BCrypt 加密
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        userMapper.insert(user);

        // 生成 JWT Token 并返回
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
    }

    /**
     * 用户登录
     * 验证用户名存在 → 验证密码 → 生成 JWT Token
     *
     * @param request 登录请求（用户名、密码）
     * @return 登录响应（含 Token 和用户信息）
     */
    public LoginResponse login(LoginRequest request) {
        // 查找用户
        SysUser user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(401, "Invalid username or password");
        }

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "Invalid username or password");
        }

        // 生成 JWT Token 并返回
        String token = jwtUtils.generateToken(user.getId(), user.getUsername());
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
    }

    /**
     * 根据用户ID查询用户信息
     *
     * @param id 用户ID
     * @return 用户实体
     */
    public SysUser getUserById(Long id) {
        return userMapper.findById(id);
    }

    /**
     * 查询全部用户列表
     */
    public List<SysUser> listUsers() {
        // 第1步：查询用户安全字段，Mapper 不返回密码
        return userMapper.findAll();
    }

    /**
     * 获取当前登录用户资料
     */
    public UserProfileResponse getProfile(Long userId) {
        // 第1步：查询当前登录用户
        SysUser user = getRequiredUser(userId);
        // 第2步：转换为前端可展示的用户资料
        return buildProfileResponse(user);
    }

    /**
     * 修改当前登录用户资料
     */
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        // 第1步：查询当前登录用户，确保用户仍然存在
        SysUser user = getRequiredUser(userId);
        // 第2步：整理昵称，空昵称时回退到用户名
        String nickname = request.getNickname() == null ? "" : request.getNickname().trim();
        user.setNickname(nickname.isEmpty() ? user.getUsername() : nickname);
        // 第3步：头像地址只保留去掉前后空格后的内容
        String avatar = request.getAvatar() == null ? "" : request.getAvatar().trim();
        user.setAvatar(avatar.isEmpty() ? null : avatar);
        // 第4步：保存用户资料并返回最新资料
        userMapper.updateProfile(user);
        return buildProfileResponse(user);
    }

    /**
     * 修改当前登录用户密码
     */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        // 第1步：查询当前登录用户
        SysUser user = getRequiredUser(userId);
        // 第2步：校验原密码是否正确
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码不正确");
        }
        // 第3步：校验两次新密码是否一致
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(400, "两次输入的新密码不一致");
        }
        // 第4步：避免新密码和原密码完全相同
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException(400, "新密码不能和原密码相同");
        }
        // 第5步：加密新密码并写入数据库
        userMapper.updatePassword(userId, passwordEncoder.encode(request.getNewPassword()));
    }

    /**
     * 删除指定用户
     */
    @Transactional
    public void deleteUser(Long targetUserId, Long currentUserId) {
        // 第1步：确认目标用户存在
        SysUser targetUser = getRequiredUser(targetUserId);
        // 第2步：不允许删除当前登录用户，避免把自己踢出系统
        if (targetUser.getId().equals(currentUserId)) {
            throw new BusinessException(400, "不能删除当前登录用户");
        }
        // 第3步：不允许删除 admin 管理员账号，避免权限管理入口失控
        if ("admin".equalsIgnoreCase(targetUser.getUsername())) {
            throw new BusinessException(400, "不能删除 admin 管理员账号");
        }
        // 第4步：先删除用户角色授权，再删除用户本身
        userMapper.deleteUserRoles(targetUserId);
        userMapper.deleteById(targetUserId);
    }

    /**
     * 查询必须存在的当前用户
     */
    private SysUser getRequiredUser(Long userId) {
        // 第1步：检查登录态是否携带用户编号
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        // 第2步：按用户编号查询数据库
        SysUser user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    /**
     * 构建用户资料响应
     */
    private UserProfileResponse buildProfileResponse(SysUser user) {
        // 第1步：只返回页面需要展示的安全字段
        return UserProfileResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .createTime(user.getCreateTime())
                .build();
    }
}
