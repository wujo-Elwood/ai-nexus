package com.rag.security;

import com.rag.common.BusinessException;
import com.rag.mapper.UserMapper;
import com.rag.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全加固测试
 * 覆盖 P0 五项：密码强度、登录限流、Token 版本失效、JWT 密钥强度
 */
class SecurityHardeningTest {

    // ==================== 密码强度 ====================

    @Test
    void passwordShouldRejectTooShort() {
        BusinessException exception = assertThrows(BusinessException.class, () -> PasswordPolicy.validate("abc123"));
        assertEquals("密码至少 8 位", exception.getMessage());
    }

    @Test
    void passwordShouldRequireLetterAndDigit() {
        assertEquals("密码需要同时包含字母和数字",
                assertThrows(BusinessException.class, () -> PasswordPolicy.validate("12345678")).getMessage());
        assertEquals("密码需要同时包含字母和数字",
                assertThrows(BusinessException.class, () -> PasswordPolicy.validate("abcdefgh")).getMessage());
    }

    @Test
    void passwordShouldAcceptStrongPassword() {
        PasswordPolicy.validate("Rag2026kb");
    }

    // ==================== 登录限流 ====================

    @Test
    void loginShouldBeLockedAfterFiveFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        String key = LoginAttemptGuard.key("Alice", "10.0.0.1");
        // 第1步：连续失败 5 次
        for (int i = 0; i < 5; i++) {
            guard.recordFailure(key);
        }
        // 第2步：再次登录被拒绝，返回 429
        BusinessException exception = assertThrows(BusinessException.class, () -> guard.checkAllowed(key));
        assertEquals(429, exception.getCode());
        assertTrue(exception.getMessage().startsWith("登录失败次数过多"));
    }

    @Test
    void loginShouldResetAfterSuccess() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        String key = LoginAttemptGuard.key("bob", "10.0.0.2");
        guard.recordFailure(key);
        guard.recordFailure(key);
        guard.recordSuccess(key);
        // 失败计数已清空，不会触发锁定
        guard.checkAllowed(key);
    }

    @Test
    void loginKeyShouldSeparateUsersAndIps() {
        // 同一用户名不同 IP 各自计数，避免一个人把所有 IP 一起锁死
        assertEquals("alice@1.1.1.1", LoginAttemptGuard.key(" Alice ", "1.1.1.1"));
        assertFalse(LoginAttemptGuard.key("alice", "1.1.1.1")
                .equals(LoginAttemptGuard.key("alice", "2.2.2.2")));
    }

    // ==================== Token 版本失效 ====================

    @Test
    void tokenShouldBeValidWhenVersionMatches() {
        TokenRevocationService service = new TokenRevocationService(fixedVersionMapper(3));
        ReflectionTestUtils.setField(service, "enabled", true);
        assertTrue(service.isTokenStillValid(7L, 3));
    }

    @Test
    void tokenShouldBeInvalidAfterPasswordChange() {
        // 数据库版本号已递增，Token 里还是旧版本 → 判定失效
        TokenRevocationService service = new TokenRevocationService(fixedVersionMapper(4));
        ReflectionTestUtils.setField(service, "enabled", true);
        assertFalse(service.isTokenStillValid(7L, 3));
    }

    @Test
    void tokenShouldBeInvalidWhenVersionMissing() {
        TokenRevocationService service = new TokenRevocationService(fixedVersionMapper(0));
        ReflectionTestUtils.setField(service, "enabled", true);
        assertFalse(service.isTokenStillValid(7L, null));
    }

    @Test
    void tokenShouldPassWhenDatabaseFails() {
        // 数据库异常时放行（签名已校验），避免整站不可用
        TokenRevocationService service = new TokenRevocationService(new ThrowingUserMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        assertTrue(service.isTokenStillValid(7L, 1));
    }

    // ==================== JWT 密钥强度 ====================

    @Test
    void jwtShouldRejectLegacyDefaultSecret() {
        JwtUtils jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", "rag-knowledge-base-system-jwt-secret-key-2024");
        ReflectionTestUtils.setField(jwtUtils, "expiration", 3600_000L);
        IllegalStateException exception = assertThrows(IllegalStateException.class, jwtUtils::validateSecret);
        assertTrue(exception.getMessage().contains("示例密钥"));
    }

    @Test
    void jwtShouldRejectShortSecret() {
        JwtUtils jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", "too-short");
        ReflectionTestUtils.setField(jwtUtils, "expiration", 3600_000L);
        assertThrows(IllegalStateException.class, jwtUtils::validateSecret);
    }

    @Test
    void jwtShouldGenerateRandomSecretWhenBlankAndRoundTripToken() {
        JwtUtils jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", "");
        ReflectionTestUtils.setField(jwtUtils, "expiration", 3600_000L);
        // 未配置时不抛异常，改为一次性随机密钥
        jwtUtils.validateSecret();
        String token = jwtUtils.generateToken(7L, "alice", 2);
        assertNotNull(token);
        assertEquals(7L, jwtUtils.getUserId(token));
        assertEquals("alice", jwtUtils.getUsername(token));
        assertEquals(2, jwtUtils.getTokenVersion(token));
        assertTrue(jwtUtils.validateToken(token));
    }

    @Test
    void jwtShouldTreatMissingVersionClaimAsZero() {
        JwtUtils jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", "unit-test-secret-key-0123456789abcdef");
        ReflectionTestUtils.setField(jwtUtils, "expiration", 3600_000L);
        // 旧版本签发的 Token 没有 ver claim，按 0 处理，避免升级后所有人被强制退出
        String legacyToken = jwtUtils.generateToken(7L, "alice");
        assertEquals(0, jwtUtils.getTokenVersion(legacyToken));
    }

    // ==================== 测试替身 ====================

    /** 固定版本号的 Mapper */
    private UserMapper fixedVersionMapper(int version) {
        return new StubUserMapper() {
            @Override
            public Integer findTokenVersionById(Long id) {
                return version;
            }
        };
    }

    /** 查询即抛异常的 Mapper，模拟数据库故障 */
    private static final class ThrowingUserMapper extends StubUserMapper {
        @Override
        public Integer findTokenVersionById(Long id) {
            throw new IllegalStateException("数据库连接失败");
        }
    }

    /** 只实现必要方法的 Mapper 基类 */
    private static class StubUserMapper implements UserMapper {
        @Override
        public com.rag.entity.SysUser findById(Long id) {
            return null;
        }

        @Override
        public com.rag.entity.SysUser findByUsername(String username) {
            return null;
        }

        @Override
        public java.util.List<com.rag.entity.SysUser> findAll() {
            return java.util.List.of();
        }

        @Override
        public int insert(com.rag.entity.SysUser user) {
            return 1;
        }

        @Override
        public int updateProfile(com.rag.entity.SysUser user) {
            return 1;
        }

        @Override
        public int updatePassword(Long id, String password) {
            return 1;
        }

        @Override
        public Integer findTokenVersionById(Long id) {
            return 0;
        }

        @Override
        public int bumpTokenVersion(Long id) {
            return 1;
        }

        @Override
        public int updateMascotPrefs(Long id, String mascotPrefs) {
            return 1;
        }

        @Override
        public int deleteUserRoles(Long id) {
            return 1;
        }

        @Override
        public int deleteById(Long id) {
            return 1;
        }
    }
}
