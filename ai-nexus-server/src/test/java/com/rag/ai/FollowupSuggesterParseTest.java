package com.rag.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追问建议解析测试
 * 模型输出格式不稳定（JSON 数组、带编号行、截断、包裹代码块），解析必须全部兼容
 */
class FollowupSuggesterParseTest {

    private final FollowupSuggester suggester = new FollowupSuggester(null);

    /** 标准 JSON 数组输出 */
    @Test
    void shouldParseJsonArray() {
        List<String> result = suggester.parseSuggestions("[\"问题一\",\"问题二\",\"问题三\"]");
        assertEquals(3, result.size());
        assertEquals("问题一", result.get(0));
        assertEquals("问题三", result.get(2));
    }

    /** 模型把 JSON 包在 markdown 代码块里 */
    @Test
    void shouldParseFencedJson() {
        List<String> result = suggester.parseSuggestions("```json\n[\"A 问题\",\"B 问题\"]\n```");
        assertEquals(2, result.size());
        assertEquals("A 问题", result.get(0));
    }

    /** 模型输出编号行（提示词禁止但仍常见） */
    @Test
    void shouldParseNumberedLines() {
        List<String> result = suggester.parseSuggestions("1. 追问甲\n2. 追问乙\n3. 追问丙");
        assertEquals(3, result.size());
        assertEquals("追问甲", result.get(0));
        assertEquals("追问丙", result.get(2));
    }

    /** JSON 截断时逐行提取，行尾残留的引号逗号括号要清理 */
    @Test
    void shouldCleanTruncatedJsonLines() {
        List<String> result = suggester.parseSuggestions("[\"完整的问题\",\n\"被截断的问题\",");
        assertEquals(2, result.size());
        assertEquals("完整的问题", result.get(0));
        assertEquals("被截断的问题", result.get(1));
    }

    /** 去掉首尾的方括号与引号包裹 */
    @Test
    void shouldStripBracketsAndQuotes() {
        List<String> result = suggester.parseSuggestions("[\"带引号的问题\"]");
        assertEquals("带引号的问题", result.get(0));
    }

    /** 空内容与不可解析内容返回空列表 */
    @Test
    void shouldReturnEmptyForUnparseable() {
        assertTrue(suggester.parseSuggestions(null).isEmpty());
        assertTrue(suggester.parseSuggestions("").isEmpty());
        assertTrue(suggester.parseSuggestions("模型拒绝回答了这个问题").isEmpty());
    }

    /** 超长行与重复行被过滤 */
    @Test
    void shouldFilterLongAndDuplicate() {
        String longQuestion = "这是一个故意写得非常非常长的追问".repeat(6);
        List<String> result = suggester.parseSuggestions(longQuestion + "\n重复问题\n重复问题");
        assertEquals(1, result.size());
        assertEquals("重复问题", result.get(0));
    }
}
