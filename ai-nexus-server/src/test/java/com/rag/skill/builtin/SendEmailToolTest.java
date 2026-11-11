package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发送邮件技能测试：验证白名单闸门、SMTP 未配置降级、发送成功路径和参数校验
 */
class SendEmailToolTest {

    private JavaMailSender mailSender;
    private SendEmailTool tool;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                // 空会话的 MimeMessage：仅用于验证组装与 send 调用，不真正投递
                new MimeMessage((jakarta.mail.Session) null));
    }

    @SuppressWarnings("unchecked")
    private SendEmailTool newTool(String whitelist, boolean withSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(withSender ? mailSender : null);
        return new SendEmailTool(provider, "bot@example.com", whitelist);
    }

    @Test
    void emptyWhitelistRejectsEverything() throws Exception {
        tool = newTool("", true);
        String result = tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"user@qq.com\",\"subject\":\"简报\",\"body\":\"内容\"}"));
        assertTrue(result.contains("不在邮件白名单内"));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void exactAddressAllowedAndSent() throws Exception {
        tool = newTool("user@qq.com,admin@corp.com", true);
        String result = tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"User@QQ.com\",\"subject\":\"AI 早报\",\"body\":\"# 今日简报\\n- 条目一\"}"));
        assertTrue(result.contains("已成功发送"));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void domainWildcardAllowsMatchingDomain() throws Exception {
        tool = newTool("*@corp.com", true);
        assertTrue(tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"anyone@CORP.com\",\"subject\":\"s\",\"body\":\"b\"}")).contains("已成功发送"));
        assertFalse(tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"anyone@qq.com\",\"subject\":\"s\",\"body\":\"b\"}")).contains("已成功发送"));
    }

    @Test
    void missingSenderReturnsConfigHint() throws Exception {
        tool = newTool("user@qq.com", false);
        String result = tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"user@qq.com\",\"subject\":\"简报\",\"body\":\"内容\"}"));
        assertTrue(result.contains("邮件功能未配置"));
        assertTrue(result.contains("MAIL_HOST"));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void missingParamsReturnHint() throws Exception {
        tool = newTool("user@qq.com", true);
        String result = tool.execute(new ObjectMapper().readTree("{\"to\":\"user@qq.com\"}"));
        assertTrue(result.contains("缺少必填参数"));
    }

    @Test
    void sendFailureReturnsReason() throws Exception {
        tool = newTool("user@qq.com", true);
        org.mockito.Mockito.doThrow(new RuntimeException("SMTP 连接超时"))
                .when(mailSender).send(any(MimeMessage.class));
        String result = tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"user@qq.com\",\"subject\":\"s\",\"body\":\"b\"}"));
        assertTrue(result.contains("邮件发送失败"));
        assertTrue(result.contains("SMTP 连接超时"));
    }
}
