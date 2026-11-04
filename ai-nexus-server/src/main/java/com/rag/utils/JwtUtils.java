package com.rag.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 工具类
 * 负责 JWT Token 的生成、解析、验证
 * 使用 HMAC-SHA256 签名算法
 *
 * 安全约定：
 * 1. 签名密钥必须由环境变量 JWT_SECRET 提供；仓库里不再保留可用的默认密钥
 * 2. 未配置时本次启动生成随机密钥（重启后旧 Token 失效），避免使用可预测的默认值
 * 3. Token 里记录用户令牌版本号，改密或强制下线后旧 Token 立即失效
 */
@Slf4j
@Component
public class JwtUtils {

    /** 历史版本里写在仓库中的示例密钥：公开可查，任何人可据此伪造 Token，禁止继续使用 */
    private static final String LEGACY_INSECURE_SECRET = "rag-knowledge-base-system-jwt-secret-key-2024";

    /** HMAC-SHA256 要求密钥至少 256 位（32 字节） */
    private static final int MIN_SECRET_LENGTH = 32;

    /** Token 中记录用户令牌版本号的 claim 名称 */
    private static final String CLAIM_TOKEN_VERSION = "ver";

    /** JWT 签名密钥，从配置读取；缺省时启动期生成随机值 */
    @Value("${jwt.secret:}")
    private String secret;

    /** Token 有效期（毫秒），从配置文件读取 */
    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 启动期校验签名密钥强度，避免带着公开默认值上线
     */
    @PostConstruct
    public void validateSecret() {
        // 第1步：拒绝继续使用仓库里的示例密钥
        if (secret != null && LEGACY_INSECURE_SECRET.equals(secret.trim())) {
            throw new IllegalStateException("jwt.secret 使用了仓库内置的示例密钥，任何人都能据此伪造登录 Token。"
                    + "请配置环境变量 JWT_SECRET（至少 " + MIN_SECRET_LENGTH + " 个字符，建议 48 个字符以上的随机串）。");
        }
        // 第2步：未配置时生成一次性随机密钥，宁可重启掉线也不使用可预测密钥
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("未配置环境变量 JWT_SECRET，已为本次启动生成临时随机签名密钥：后端重启后所有登录状态会失效。"
                    + "生产环境请配置固定密钥（至少 {} 个字符）。", MIN_SECRET_LENGTH);
            return;
        }
        // 第3步：显式配置的密钥必须够长
        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("jwt.secret 长度不足 " + MIN_SECRET_LENGTH + " 个字符，"
                    + "无法保证 HMAC-SHA256 签名强度，请更换为更长的随机字符串。");
        }
    }

    /**
     * 获取签名密钥对象
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token（令牌版本按 0 处理，用于兼容未读取版本的调用方）
     *
     * @param userId   用户ID
     * @param username 用户名
     * @return JWT Token 字符串
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, 0);
    }

    /**
     * 生成 JWT Token
     * Token 中包含 userId（subject）、username（claim）和令牌版本号（ver）
     *
     * @param userId       用户ID
     * @param username     用户名
     * @param tokenVersion 用户当前的令牌版本号，改密后会递增使旧 Token 失效
     * @return JWT Token 字符串
     */
    public String generateToken(Long userId, String username, Integer tokenVersion) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion == null ? 0 : tokenVersion)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析 JWT Token，返回 Claims 对象
     *
     * @param token JWT Token 字符串
     * @return Claims 对象（包含 subject、claims、expiration 等）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中提取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 从 Token 中提取用户名
     *
     * @param token JWT Token
     * @return 用户名
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims.get("username", String.class);
    }

    /**
     * 从 Token 中提取令牌版本号
     * 旧版本签发的 Token 没有该 claim，按 0 处理；解析失败返回 null 交由调用方判定为无效
     *
     * @param token JWT Token
     * @return 令牌版本号
     */
    public Integer getTokenVersion(String token) {
        try {
            Integer version = parseToken(token).get(CLAIM_TOKEN_VERSION, Integer.class);
            return version == null ? 0 : version;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 Token 是否已过期
     *
     * @param token JWT Token
     * @return true=已过期，false=未过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 验证 Token 是否有效（未过期且签名正确）
     *
     * @param token JWT Token
     * @return true=有效，false=无效
     */
    public boolean validateToken(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }
}
