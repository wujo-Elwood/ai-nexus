package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 资讯技能测试：打桩 Hacker News 接口与新闻页面，验证双板块渲染、
 * 正文提取、story_text 回退、抓取失败回退和单源失败降级
 */
class AiNewsToolTest {

    private static final String OPENAI_JSON = "{\"hits\":["
            + "{\"created_at\":\"2026-10-01T08:00:00Z\",\"title\":\"OpenAI launches new model\","
            + "\"url\":\"https://example.com/openai\",\"author\":\"alice\",\"points\":382,\"num_comments\":418,\"objectID\":\"111\"}"
            + "]}";

    private static final String ANTHROPIC_JSON = "{\"hits\":["
            + "{\"created_at\":\"2026-10-01T06:00:00Z\",\"title\":\"Anthropic releases Claude update\","
            + "\"url\":\"https://example.com/claude\",\"author\":\"bob\",\"points\":500,\"num_comments\":300,"
            + "\"objectID\":\"222\",\"story_text\":\"Claude update improves coding ability.\"}"
            + "]}";

    private static final String GOOGLE_JSON = "{\"hits\":[]}";

    private static final String HOT_JSON = "{\"hits\":["
            + "{\"created_at\":\"2026-10-01T04:00:00Z\",\"title\":\"Hot: new AI chip\","
            + "\"url\":\"https://example.com/chip\",\"author\":\"dave\",\"points\":800,\"num_comments\":120,\"objectID\":\"333\"},"
            + "{\"created_at\":\"2026-10-01T02:00:00Z\",\"title\":\"AI startup funding\","
            + "\"url\":\"https://example.com/fund\",\"author\":\"frank\",\"points\":70,\"num_comments\":8,\"objectID\":\"444\"}"
            + "]}";

    private static final String OPENAI_ARTICLE = "<html><body><article>"
            + "OpenAI announced GPT-6 with major reasoning improvements and lower pricing. It ships next month."
            + "</article></body></html>";

    private static final String CHIP_ARTICLE = "<html><body><article>"
            + "A new AI chip doubles inference performance while halving power draw."
            + "</article></body></html>";

    private AiNewsTool tool;
    private final List<String> requestedUrls = Collections.synchronizedList(new ArrayList<>());
    private String openAiResponse = OPENAI_JSON;
    private String anthropicResponse = ANTHROPIC_JSON;
    private String googleResponse = GOOGLE_JSON;
    private String hotResponse = HOT_JSON;
    private boolean failGoogle = false;
    private boolean failClaudeArticle = false;
    private boolean failChipArticle = false;

    @BeforeEach
    void setUp() {
        AiNewsTool.JINA_SPACING_MS = 0;
        requestedUrls.clear();
        openAiResponse = OPENAI_JSON;
        anthropicResponse = ANTHROPIC_JSON;
        googleResponse = GOOGLE_JSON;
        hotResponse = HOT_JSON;
        failGoogle = false;
        failClaudeArticle = false;
        failChipArticle = false;
        tool = new AiNewsTool(new ObjectMapper()) {
            @Override
            protected String fetchJson(String url) throws Exception {
                requestedUrls.add(url);
                if (url.contains("query=OpenAI")) {
                    return openAiResponse;
                }
                if (url.contains("query=Anthropic")) {
                    return anthropicResponse;
                }
                if (url.contains("query=Google")) {
                    if (failGoogle) {
                        throw new IllegalStateException("接口返回 503");
                    }
                    return googleResponse;
                }
                if (url.contains("search_by_date")) {
                    return hotResponse;
                }
                return "";
            }

            @Override
            protected String fetchArticle(String url) throws Exception {
                requestedUrls.add(url);
                if (url.contains("example.com/openai")) {
                    return OPENAI_ARTICLE;
                }
                if (url.contains("example.com/chip")) {
                    if (failChipArticle) {
                        throw new IllegalStateException("超时");
                    }
                    return CHIP_ARTICLE;
                }
                if (url.contains("example.com/claude")) {
                    if (failClaudeArticle) {
                        throw new IllegalStateException("抓取被拒绝");
                    }
                    return "";
                }
                return "";
            }

            @Override
            protected String fetchViaJina(String url) {
                // 测试桩：不发起真实 Jina 请求
                return "";
            }
        };
    }

    @Test
    void queriesCompaniesAndHotBoard() throws Exception {
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        // 4 次数据源请求：三家公司 + 一次 AI 热榜；另有 2 次正文抓取
        long apiCalls = requestedUrls.stream().filter(url -> url.contains("hn.algolia.com")).count();
        assertEquals(4, apiCalls);
        assertTrue(result.contains("一、头部厂商动态"));
        assertTrue(result.contains("二、AI 圈最热榜"));
    }

    @Test
    void enrichesArticleContentForItems() throws Exception {
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        // OpenAI 条目应带上抓取到的正文摘录
        assertTrue(result.contains("内容：") );
        assertTrue(result.contains("GPT-6 with major reasoning improvements"));
        // 热榜 chip 条目同样有正文
        assertTrue(result.contains("doubles inference performance"));
    }

    @Test
    void storyTextFallsBackWhenArticleFetchFails() throws Exception {
        failClaudeArticle = true;
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        // 原文抓取失败时回退 HN 文本帖的 story_text
        assertTrue(result.contains("Claude update improves coding ability"));
    }

    @Test
    void chipFetchFailureKeepsTitleOnly() throws Exception {
        failChipArticle = true;
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        // chip 无 story_text 且抓取失败 → 该条无正文，但其他条目不受影响
        assertFalse(result.contains("doubles inference performance"));
        assertTrue(result.contains("GPT-6 with major reasoning improvements"));
        // "内容：" 出现次数 = openai 1 条 + claude 1 条 = 2
        assertEquals(2, result.split("内容：", -1).length - 1);
    }

    @Test
    void singleCompanyFailureDoesNotBlockOthers() throws Exception {
        failGoogle = true;
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        assertTrue(result.contains("Anthropic"));
        assertTrue(result.contains("OpenAI launches new model"));
        assertTrue(result.contains("数据源本次检索失败：Google"));
    }

    @Test
    void jinaReaderFallbackWhenDirectBlocked() throws Exception {
        // 直连 chip 页被拒，但 Jina Reader 能取到内容
        tool = new AiNewsTool(new ObjectMapper()) {
            @Override
            protected String fetchJson(String url) throws Exception {
                if (url.contains("query=OpenAI")) {
                    return OPENAI_JSON;
                }
                if (url.contains("search_by_date")) {
                    return HOT_JSON;
                }
                return "{\"hits\":[]}";
            }

            @Override
            protected String fetchArticle(String url) throws Exception {
                if (url.contains("example.com/chip")) {
                    throw new IllegalStateException("接口返回 403");
                }
                return "";
            }

            @Override
            protected String fetchViaJina(String url) {
                return "Title: New AI chip\n\nThe chip doubles inference performance while halving power draw, according to the report.";
            }
        };
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        assertTrue(result.contains("doubles inference performance"));
    }

    @Test
    void allSourcesFailureReturnsFriendlyMessage() throws Exception {
        tool = new AiNewsTool(new ObjectMapper()) {
            @Override
            protected String fetchJson(String url) throws Exception {
                throw new IllegalStateException("接口返回 503");
            }
        };
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        assertTrue(result.contains("全部数据源不可达"));
    }

    @Test
    void customCompaniesParamIsRespected() throws Exception {
        tool.execute(new ObjectMapper().readTree("{\"companies\":\"Mistral,Meta\"}"));
        assertTrue(requestedUrls.get(0).contains("query=Mistral"));
        assertTrue(requestedUrls.get(1).contains("query=Meta"));
        // 数据源请求 = 2 家公司 + 1 次热榜；另有热榜条目的正文抓取请求，不计入
        long apiCalls = requestedUrls.stream().filter(url -> url.contains("hn.algolia.com")).count();
        assertEquals(3, apiCalls);
    }

    @Test
    void emptyEverythingReturnsHonestHint() throws Exception {
        tool = new AiNewsTool(new ObjectMapper()) {
            @Override
            protected String fetchJson(String url) {
                return "{\"hits\":[]}";
            }
        };
        String result = tool.execute(new ObjectMapper().readTree("{}"));
        assertTrue(result.contains("没有找到"));
        assertFalse(result.contains("翻译"));
    }
}
