package com.rag.rag;

import com.rag.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入防护组件
 * 检测用户输入中是否包含常见的 Prompt 注入特征
 * 包含注入特征时抛出 BusinessException 阻止请求
 */
@Component
public class PromptGuard {

    /** 常见注入模式列表 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)忽略.{0,10}(以上|之前|上面).{0,10}(指令|提示|规则|要求)"),
            Pattern.compile("(?i)(输出|显示|打印|告诉我).{0,10}(系统|初始).{0,10}(提示词|prompt|指令)"),
            Pattern.compile("(?i)ignore.{0,20}(previous|above|system).{0,20}(instructions|prompt)"),
            Pattern.compile("(?i)you are now.{0,20}(DAN|jailbreak|unrestricted)"),
            Pattern.compile("(?i)pretend.{0,20}(you are|you're).{0,20}(no longer|not)"),
            Pattern.compile("(?i)从现在起.{0,10}你.{0,10}(不再|不是|忘记)"),
            Pattern.compile("(?i)进入.{0,5}(开发者|调试|越狱).{0,5}模式")
    );

    /**
     * 检测用户输入是否包含注入特征
     * 包含时抛出异常，不包含时正常返回
     */
    public void check(String userInput) {
        if (userInput == null || userInput.isEmpty()) {
            return;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(userInput).find()) {
                throw new BusinessException(400, "检测到不安全的输入内容，请重新提问");
            }
        }
    }
}
