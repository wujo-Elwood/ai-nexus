package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.skill.core.AgentTool;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 发送邮件技能：把智能体整理好的内容（如 AI 资讯简报）以 HTML 邮件发出。
 * 安全闸门：收件人必须在管理员配置的白名单内（精确地址或 *@域名 通配），
 * 白名单为空时禁止一切发送；SMTP 未配置时向模型返回可行动的提示
 */
@Slf4j
@Component
public class SendEmailTool implements AgentTool {

    private static final int MAX_BODY_LENGTH = 60_000;
    private static final int MAX_SUBJECT_LENGTH = 200;

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final List<String> allowedRecipients;

    public SendEmailTool(ObjectProvider<JavaMailSender> mailSenderProvider,
                         @Value("${spring.mail.username:}") String mailFrom,
                         @Value("${skill.email.allowed-recipients:}") String allowedRecipients) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.allowedRecipients = parseRecipients(allowedRecipients);
    }

    @Override
    public String name() {
        return "send_email";
    }

    @Override
    public String description() {
        return "把整理好的内容以邮件发送到指定邮箱（如把 AI 资讯简报发给用户）。"
                + "收件人必须在平台白名单内；用户要求发送邮件时调用，把最终整理好的完整内容作为 body 传入。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("to", "string", "收件人邮箱地址", true),
                new ToolParam("subject", "string", "邮件主题，如「AI 资讯早报 2026-10-01」", true),
                new ToolParam("body", "string", "邮件正文（完整的整理后内容，保留分段）", true));
    }

    @Override
    public String execute(JsonNode arguments) {
        // 第1步：检查 SMTP 配置与白名单闸门
        if (mailSender == null || mailFrom.isEmpty()) {
            return "邮件功能未配置：需要在平台配置 SMTP 服务器、发件账号和授权码后才能发送，请告知用户联系管理员在配置文件中设置 MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD";
        }
        String to = arguments.path("to").asText("").trim();
        String subject = arguments.path("subject").asText("").trim();
        String body = arguments.path("body").asText("");
        if (to.isEmpty() || subject.isEmpty() || body.isBlank()) {
            return "缺少必填参数：to（收件人）、subject（主题）、body（正文）都不能为空";
        }
        if (!isAllowed(to)) {
            return "收件人 " + to + " 不在邮件白名单内，已拒绝发送；请告知用户只有管理员配置的白名单邮箱才能接收，可在配置项 skill.email.allowed-recipients 中添加";
        }
        // 第2步：组装并投递；失败原因透传给模型
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(mailFrom);
            helper.setTo(to);
            helper.setSubject(subject.length() > MAX_SUBJECT_LENGTH ? subject.substring(0, MAX_SUBJECT_LENGTH) : subject);
            helper.setText(toHtml(body), true);
            mailSender.send(message);
            log.info("邮件已发送: to={}, subject长度={}, 正文长度={}", to, subject.length(), body.length());
            return "邮件已成功发送到 " + to + "（主题：" + subject + "）。请告知用户查收（注意垃圾箱）。";
        } catch (Exception e) {
            log.warn("邮件发送失败: to={}, 原因={}", to, e.getMessage());
            return "邮件发送失败：" + (e.getMessage() == null ? "未知原因" : e.getMessage())
                    + "，请告知用户稍后重试或联系管理员检查 SMTP 配置";
        }
    }

    /** 白名单匹配：精确地址（忽略大小写）、* 全放行、*@域名 通配 */
    boolean isAllowed(String recipient) {
        if (allowedRecipients.isEmpty()) {
            return false;
        }
        String target = recipient.trim().toLowerCase(Locale.ROOT);
        for (String pattern : allowedRecipients) {
            String p = pattern.toLowerCase(Locale.ROOT);
            if (p.equals("*") || p.equals(target)
                    || (p.startsWith("*@") && target.endsWith("@" + p.substring(2)))) {
                return true;
            }
        }
        return false;
    }

    /** 正文转简单 HTML：转义后按换行分段，保留简报的排版结构 */
    private String toHtml(String body) {
        String safe = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        StringBuilder builder = new StringBuilder();
        builder.append("<div style=\"font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;")
                .append("font-size:14px;line-height:1.8;color:#24292f;max-width:720px\">");
        for (String paragraph : safe.split("\n")) {
            String line = paragraph.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                builder.append("<h2 style=\"font-size:17px;margin:18px 0 8px\">").append(
                        line.replaceFirst("^#+\\s*", "")).append("</h2>");
            } else if (line.startsWith("- ") || line.matches("^\\d+\\.\\s.*")) {
                builder.append("<p style=\"margin:4px 0\">• ").append(
                        line.replaceFirst("^-\\s*", "").replaceFirst("^\\d+\\.\\s*", "")).append("</p>");
            } else {
                builder.append("<p style=\"margin:8px 0\">").append(line).append("</p>");
            }
        }
        builder.append("</div>");
        return builder.toString();
    }

    /** 解析白名单配置：逗号分隔、去空白 */
    private List<String> parseRecipients(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.stream(config.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }
}
