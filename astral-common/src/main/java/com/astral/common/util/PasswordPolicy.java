package com.astral.common.util;

/**
 * 密码强度策略（统一校验入口）
 *
 * <p>所有「设置/修改/重置密码」的入口必须走 {@link #validate}：
 * 个人中心改密、管理员重置、管理员新建用户。规则保持简单可解释：</p>
 * <ol>
 *   <li>长度 8-64 位</li>
 *   <li>必须同时包含字母与数字</li>
 *   <li>不得包含空白字符</li>
 *   <li>不得与用户名相同（区分大小写直接比较）</li>
 * </ol>
 *
 * <p>App 端注册（qt 插件）当前未接入——旧客户端不受影响；后续客户端发版
 * 注册接口接入同一策略即可。管理端入口（本批次改动）全部已接入。</p>
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
    }

    /**
     * 校验密码强度
     *
     * @param password 明文密码（调用方负责先解密）
     * @param username 用户名（用于「不得与用户名相同」检查，可为 null）
     * @return null = 通过；否则返回面向用户的失败原因文案
     */
    public static String validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "密码至少 " + MIN_LENGTH + " 位";
        }
        if (password.length() > MAX_LENGTH) {
            return "密码最多 " + MAX_LENGTH + " 位";
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isWhitespace(c)) {
                return "密码不能包含空白字符";
            }
        }
        if (!hasLetter || !hasDigit) {
            return "密码必须同时包含字母和数字";
        }
        if (username != null && !username.isBlank() && username.equals(password)) {
            return "密码不能与用户名相同";
        }
        return null;
    }

    /** 校验失败时抛出 IllegalArgumentException 的便捷变体（文案可直接返回给前端） */
    public static void validateOrThrow(String password, String username) {
        String message = validate(password, username);
        if (message != null) {
            throw new IllegalArgumentException(message);
        }
    }
}
