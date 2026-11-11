package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.dto.ChangePasswordRequest;
import com.rag.dto.LoginRequest;
import com.rag.dto.RegisterRequest;
import com.rag.dto.UpdateMascotPrefsRequest;
import com.rag.dto.UpdateProfileRequest;
import com.rag.entity.SysUser;
import com.rag.mapper.UserMapper;
import com.rag.security.LoginAttemptGuard;
import com.rag.security.PasswordPolicy;
import com.rag.utils.JwtUtils;
import com.rag.vo.LoginResponse;
import com.rag.vo.UserProfileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

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

    /** 登录失败限流器：连续失败 5 次锁定 10 分钟，挡住脚本撞库 */
    @Autowired
    private LoginAttemptGuard loginAttemptGuard;

    /** 吉祥物外观偏好 JSON 序列化 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 吉祥物花色白名单 */
    private static final Set<String> MASCOT_SKINS = Set.of("classic", "gray", "pink", "cow");

    /** 吉祥物换装白名单 */
    private static final Set<String> MASCOT_OUTFITS = Set.of("none", "hat", "glasses", "shades", "crown");

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

        // 校验密码强度，挡住 123456 这类弱口令
        PasswordPolicy.validate(request.getPassword());

        // 创建用户，密码使用 BCrypt 加密
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        userMapper.insert(user);

        // 生成 JWT Token 并返回
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getTokenVersion());
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
    }

    /**
     * 用户登录
     * 校验登录限流 → 验证用户名存在 → 验证密码 → 生成 JWT Token
     *
     * @param request  登录请求（用户名、密码）
     * @param clientIp 客户端 IP，与用户名一起构成限流键
     * @return 登录响应（含 Token 和用户信息）
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 第1步：检查该用户名+IP 是否因连续失败被锁定
        String attemptKey = LoginAttemptGuard.key(request.getUsername(), clientIp);
        loginAttemptGuard.checkAllowed(attemptKey);

        // 第2步：查找用户
        SysUser user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            loginAttemptGuard.recordFailure(attemptKey);
            throw new BusinessException(401, "Invalid username or password");
        }

        // 第3步：验证密码，失败计入限流
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptGuard.recordFailure(attemptKey);
            throw new BusinessException(401, "Invalid username or password");
        }

        // 第4步：登录成功，清空失败计数
        loginAttemptGuard.recordSuccess(attemptKey);

        // 第5步：生成 JWT Token 并返回（携带令牌版本号，改密后旧 Token 失效）
        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getTokenVersion());
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
     * 更新吉祥物外观偏好（花色/换装/名字）
     * 服务端做白名单校验并组装 JSON 存储，跟随用户账号跨浏览器与设备保留
     *
     * @return 组装后的偏好 JSON（前端据此同步本地状态）
     */
    public String updateMascotPrefs(Long userId, UpdateMascotPrefsRequest request) {
        // 第1步：确认用户存在并读取现有偏好，作为未传字段的兜底
        SysUser user = getRequiredUser(userId);
        String current = user.getMascotPrefs();
        String skin = null;
        String outfit = null;
        String name = null;
        if (current != null && !current.isBlank()) {
            try {
                var tree = objectMapper.readTree(current);
                skin = tree.path("skin").asText(null);
                outfit = tree.path("outfit").asText(null);
                name = tree.path("name").asText(null);
            } catch (Exception ignored) { /* 旧数据损坏时按空处理 */ }
        }
        // 第2步：校验并合并新值（未传字段保持原值）
        if (request.getSkin() != null && !request.getSkin().isBlank()) {
            if (!MASCOT_SKINS.contains(request.getSkin())) {
                throw new BusinessException(400, "不支持的花色");
            }
            skin = request.getSkin();
        }
        if (request.getOutfit() != null && !request.getOutfit().isBlank()) {
            if (!MASCOT_OUTFITS.contains(request.getOutfit())) {
                throw new BusinessException(400, "不支持的换装");
            }
            outfit = request.getOutfit();
        }
        if (request.getName() != null) {
            String clean = request.getName().trim();
            if (clean.length() > 8) {
                throw new BusinessException(400, "名字最长 8 个字");
            }
            name = clean.isEmpty() ? null : clean;
        }
        // 第3步：组装 JSON 并落库（三项都空时清空偏好）
        if (skin == null && outfit == null && name == null) {
            userMapper.updateMascotPrefs(userId, null);
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(java.util.Map.of(
                    "skin", skin == null ? "" : skin,
                    "outfit", outfit == null ? "" : outfit,
                    "name", name == null ? "" : name));
            userMapper.updateMascotPrefs(userId, json);
            return json;
        } catch (Exception e) {
            throw new BusinessException(500, "偏好保存失败");
        }
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
        // 第5步：校验新密码强度
        PasswordPolicy.validate(request.getNewPassword());
        // 第6步：加密新密码并写入数据库
        userMapper.updatePassword(userId, passwordEncoder.encode(request.getNewPassword()));
        // 第7步：递增令牌版本号，让改密前签发的所有 Token（含其他设备的登录态）立即失效
        userMapper.bumpTokenVersion(userId);
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
                .mascotPrefs(user.getMascotPrefs())
                .createTime(user.getCreateTime())
                .build();
    }
}
