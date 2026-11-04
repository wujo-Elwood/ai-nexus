package com.rag.security;

import com.rag.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流器
 *
 * 背景：登录接口此前没有任何失败次数限制，攻击者可以无限次撞库。
 * 这里按「用户名 + 客户端 IP」在内存中计数：连续失败 5 次锁定 10 分钟。
 * 单机内存实现，足够挡住脚本撞库；集群部署时应换成 Redis 计数。
 */
@Slf4j
@Component
public class LoginAttemptGuard {

    /** 触发锁定的连续失败次数 */
    private static final int MAX_FAILURES = 5;

    /** 失败计数统计窗口（毫秒），窗口外的失败不计入 */
    private static final long WINDOW_MILLIS = 10 * 60 * 1000L;

    /** 触发锁定后的锁定时长（毫秒） */
    private static final long LOCK_MILLIS = 10 * 60 * 1000L;

    /** 内存条目上限，超出后清理过期条目，避免被大量用户名撑爆内存 */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 构造限流键：同一用户名在不同 IP 上分别计数，避免一个人被全网锁死
     */
    public static String key(String username, String clientIp) {
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        return user + "@" + ip;
    }

    /**
     * 登录前校验：处于锁定期内则拒绝登录
     *
     * @param key 限流键
     * @throws BusinessException 429 表示失败次数过多
     */
    public void checkAllowed(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (attempt.lockedUntil > now) {
            long seconds = Math.max(1, (attempt.lockedUntil - now) / 1000);
            throw new BusinessException(429, "登录失败次数过多，请 " + seconds + " 秒后再试");
        }
        // 窗口已过期且未锁定，直接清空计数，让用户可以重新尝试
        if (attempt.lockedUntil <= now && now - attempt.firstFailureAt > WINDOW_MILLIS) {
            attempts.remove(key);
        }
    }

    /**
     * 记录一次登录失败，累计到阈值后进入锁定期
     */
    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        attempts.compute(key, (k, existing) -> {
            Attempt current = existing;
            if (current == null || now - current.firstFailureAt > WINDOW_MILLIS) {
                current = new Attempt(now);
            }
            current.failures++;
            if (current.failures >= MAX_FAILURES) {
                current.lockedUntil = now + LOCK_MILLIS;
                log.warn("账号 {} 连续登录失败 {} 次，已锁定 {} 分钟", k, current.failures, LOCK_MILLIS / 60000);
            }
            return current;
        });
        pruneIfNeeded();
    }

    /**
     * 登录成功后清空失败计数
     */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    /**
     * 条目过多时清理已过期记录，防止内存无限增长
     */
    private void pruneIfNeeded() {
        if (attempts.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Attempt>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next().getValue();
            if (attempt.lockedUntil <= now && now - attempt.firstFailureAt > WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    /** 单个限流键的失败状态 */
    private static final class Attempt {
        /** 窗口内首次失败时间 */
        private final long firstFailureAt;
        /** 窗口内失败次数 */
        private int failures;
        /** 锁定截止时间，0 表示未锁定 */
        private long lockedUntil;

        private Attempt(long firstFailureAt) {
            this.firstFailureAt = firstFailureAt;
        }
    }
}
