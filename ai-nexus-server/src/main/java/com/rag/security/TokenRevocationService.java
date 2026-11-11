package com.rag.security;

import com.rag.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Token 失效校验服务
 *
 * 背景：JWT 一旦签发，在有效期内无法撤回。用户改密、管理员强制下线之后旧 Token 仍然可用，
 * 是本项目此前的安全缺口。这里用 sys_user.token_version 做版本号比对：
 * 签发 Token 时把版本号写进 ver claim，每次请求比对数据库当前值，不一致即视为失效。
 */
@Slf4j
@Service
public class TokenRevocationService {

    private final UserMapper userMapper;

    /** 是否启用版本号校验，可按需关闭以省掉每请求一次查询 */
    @Value("${security.token-revocation-enabled:true}")
    private boolean enabled;

    public TokenRevocationService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 校验 Token 携带的版本号与数据库是否一致
     *
     * @param userId       当前登录用户
     * @param tokenVersion Token 中的版本号；null 表示解析失败，按失效处理
     * @return true=仍有效，false=已失效（用户改密或已被强制下线）
     */
    public boolean isTokenStillValid(Long userId, Integer tokenVersion) {
        // 第1步：未启用或未取到用户时不做拦截
        if (!enabled || userId == null) {
            return true;
        }
        // 第2步：版本号解析失败的 Token 直接判为失效
        if (tokenVersion == null) {
            return false;
        }
        try {
            // 第3步：读取数据库当前版本号并比对
            Integer current = userMapper.findTokenVersionById(userId);
            int expected = current == null ? 0 : current;
            return expected == tokenVersion;
        } catch (Exception e) {
            // 第4步：数据库异常时放行（签名已校验通过），只记录告警避免整站不可用
            log.warn("校验 Token 版本号失败，本次请求按有效处理: {}", e.getMessage());
            return true;
        }
    }
}
