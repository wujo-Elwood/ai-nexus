package com.rag.security;

import com.rag.common.BusinessException;

/**
 * 密码强度策略
 *
 * 背景：注册与改密此前只校验非空，用户可以用 123456 这样的弱口令，
 * 一旦数据库泄露即可被彩虹表直接命中。这里统一最低要求：
 * 长度 8-64，且同时包含字母和数字。
 */
public final class PasswordPolicy {

    /** 最短长度 */
    private static final int MIN_LENGTH = 8;

    /** 最长长度，避免 BCrypt 处理超长输入浪费算力 */
    private static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
    }

    /**
     * 校验密码强度，不满足时抛出 400
     *
     * @param password 明文密码
     */
    public static void validate(String password) {
        // 第1步：校验长度
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BusinessException(400, "密码至少 " + MIN_LENGTH + " 位");
        }
        if (password.length() > MAX_LENGTH) {
            throw new BusinessException(400, "密码最长 " + MAX_LENGTH + " 位");
        }
        // 第2步：要求字母与数字混合，挡住纯数字和纯字母弱口令
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(400, "密码需要同时包含字母和数字");
        }
    }
}
