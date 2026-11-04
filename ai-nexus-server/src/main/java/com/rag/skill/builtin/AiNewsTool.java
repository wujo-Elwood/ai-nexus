package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.rag.skill.core.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AI 资讯技能：监控头部 AI 公司（默认 OpenAI/Anthropic/Google）在 Hacker News 上的近期动态，
 * 同时拉取 AI 圈整体最热门的内容；厂商动态全量保留，热榜与厂商动态去重后按热度排列；
 * 每条内容会并行抓取原文正文（HN 文本帖用 story_text，链接帖抓网页提取正文），
 * 返回英文原文 + 正文摘录，由模型翻译成中文简报；
 * 单个数据源或单条正文抓取失败不影响其他条目
 */
@Slf4j
@Component
public class AiNewsTool implements AgentTool {

    private static final String SEARCH_URL = "https://hn.algolia.com/api/v1/search";
    private static final String SEARCH_BY_DATE_URL = "https://hn.algolia.com/api/v1/search_by_date";
    private static final String DEFAULT_COMPANIES = "OpenAI,Anthropic,Google";
    private static final int COMPANY_HITS_PER_PAGE = 5;
    private static final int HOT_CANDIDATE_COUNT = 100;
    private static final int DEFAULT_HOT_LIMIT = 10;
    private static final int MAX_HOT_LIMIT = 20;
    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 168;
    private static final int CONTENT_MAX_LENGTH = 700;
    private static final int ARTICLE_TIMEOUT_MS = 6000;
    private static final int JINA_TIMEOUT_MS = 20000;
    private static final int ENRICH_WAIT_MS = 75000;
    /** Jina 免费档限流 20 次/分钟：全局节流，相邻请求间隔 3.2 秒；测试可置 0 */
    static long JINA_SPACING_MS = 3200;
    private static final Object JINA_GATE = new Object();
    private static long nextJinaAllowedAt = 0;
    /** 新闻站点普遍拒绝非浏览器请求，抓原文时伪装成 Chrome */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 正文抓取线程池：并行拉取多条原文，守护线程不阻碍关闭 */
    private static final ExecutorService ARTICLE_POOL = Executors.newFixedThreadPool(10, runnable -> {
        Thread thread = new Thread(runnable, "hn-article-fetch");
        thread.setDaemon(true);
        return thread;
    });

    /** Jina Reader 的 API Key（可选）：在 jina.ai 免费注册后通过环境变量 JINA_API_KEY 配置，额度远高于匿名调用 */
    private final String jinaApiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public AiNewsTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jinaApiKey = System.getenv("JINA_API_KEY");
    }

    @Override
    public String name() {
        return "fetch_ai_news";
    }

    @Override
    public String description() {
        return "获取头部 AI 公司（默认 OpenAI、Anthropic、Google，可细化如 Google DeepMind/Gemini）在 Hacker News 上的近期全部动态，"
                + "以及 AI 圈整体最热门的内容（不限厂商），每条附正文摘录，分「头部厂商动态」和「AI 圈最热榜」两个板块"
                + "返回英文原文（标题/链接/热度/讨论数/正文摘录）。了解大模型厂商最新消息、AI 行业热点时调用；"
                + "返回的是英文，需要翻译成中文简报，摘要要覆盖正文要点。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("companies", "string",
                        "要监控的公司/主题，逗号分隔，默认 OpenAI,Anthropic,Google；可细化为 OpenAI,Anthropic,Google DeepMind,Gemini", false),
                new ToolParam("hours", "number", "回看时间范围（小时）1-168，默认 24", false),
                new ToolParam("hotLimit", "number", "「AI 圈最热榜」条数 1-20，默认 10", false));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        // 第1步：解析并钳制参数
        String companiesArg = arguments.path("companies").asText("").isBlank()
                ? DEFAULT_COMPANIES : arguments.path("companies").asText("").trim();
        List<String> companies = splitCompanies(companiesArg);
        int hours = clamp(arguments.path("hours").asInt(DEFAULT_HOURS), 1, MAX_HOURS, DEFAULT_HOURS);
        int hotLimit = clamp(arguments.path("hotLimit").asInt(DEFAULT_HOT_LIMIT), 1, MAX_HOT_LIMIT, DEFAULT_HOT_LIMIT);
        long sinceSeconds = Instant.now().getEpochSecond() - Duration.ofHours(hours).toSeconds();
        String timeFilter = "created_at_i>" + sinceSeconds;

        // 第2步：逐家检索公司动态（每家全量保留，按热度降序整理）
        Map<String, List<JsonNode>> companyHits = new LinkedHashMap<>();
        Set<String> seenIds = new LinkedHashSet<>();
        List<String> failedSources = new ArrayList<>();
        for (String company : companies) {
            try {
                String url = SEARCH_URL
                        + "?query=" + URLEncoder.encode(company, StandardCharsets.UTF_8)
                        + "&tags=story&numericFilters=" + URLEncoder.encode(timeFilter, StandardCharsets.UTF_8)
                        + "&hitsPerPage=" + COMPANY_HITS_PER_PAGE;
                JsonNode hits = objectMapper.readTree(fetchJson(url)).path("hits");
                List<JsonNode> companyList = new ArrayList<>();
                for (JsonNode hit : hits) {
                    String objectId = hit.path("objectID").asText("");
                    if (!objectId.isEmpty() && seenIds.add(objectId)) {
                        companyList.add(hit);
                    }
                }
                companyList.sort(Comparator.comparingInt((JsonNode hit) -> hit.path("points").asInt(0)).reversed());
                companyHits.put(company, companyList);
            } catch (Exception e) {
                failedSources.add(company);
            }
        }

        // 第3步：拉取 AI 圈最热榜（按时间拉候选，客户端按热度排序，排除已进公司动态的）
        List<JsonNode> hotHits = new ArrayList<>();
        try {
            String url = SEARCH_BY_DATE_URL
                    + "?query=" + URLEncoder.encode("AI", StandardCharsets.UTF_8)
                    + "&tags=story&numericFilters=" + URLEncoder.encode(timeFilter, StandardCharsets.UTF_8)
                    + "&hitsPerPage=" + HOT_CANDIDATE_COUNT;
            JsonNode hits = objectMapper.readTree(fetchJson(url)).path("hits");
            for (JsonNode hit : hits) {
                String objectId = hit.path("objectID").asText("");
                if (!objectId.isEmpty() && seenIds.add(objectId)) {
                    hotHits.add(hit);
                }
            }
            hotHits.sort(Comparator.comparingInt((JsonNode hit) -> hit.path("points").asInt(0)).reversed());
            if (hotHits.size() > hotLimit) {
                hotHits = new ArrayList<>(hotHits.subList(0, hotLimit));
            }
        } catch (Exception e) {
            failedSources.add("AI 热榜");
        }

        // 第4步：全部来源失败或全部无结果时如实说明
        boolean allSourcesFailed = failedSources.size() == companies.size() + 1;
        if (companyHits.values().stream().allMatch(List::isEmpty) && hotHits.isEmpty()) {
            if (allSourcesFailed) {
                return "获取 Hacker News 资讯失败（全部数据源不可达），请告知用户稍后重试";
            }
            return "近 " + hours + " 小时内没有找到相关的 Hacker News 内容（"
                    + String.join("、", companies) + " 及 AI 热榜），请如实告知用户，或建议扩大时间范围再试";
        }

        // 第5步：并行抓取每条新闻的原文正文（失败则该条保持仅标题）
        enrichHits(concat(companyHits, hotHits));

        // 第6步：分板块渲染，要求模型翻译成中文简报
        return render(companies, failedSources, hours, companyHits, hotHits);
    }

    /** 并行抓取每条新闻的原文正文，整体限时；失败则该条保持仅标题 */
    private void enrichHits(List<JsonNode> hits) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (JsonNode hit : hits) {
            futures.add(CompletableFuture.runAsync(() -> enrichHit(hit), ARTICLE_POOL));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(ENRICH_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("部分新闻正文抓取超时，跳过未完成的条目: {}", e.getMessage());
        }
    }

    /** 抓取单条正文：链接帖提取网页正文，失败或缺失时回退 HN 文本帖的 story_text */
    private void enrichHit(JsonNode hit) {
        String url = hit.path("url").asText("");
        String content = "";
        if (!url.isBlank()) {
            try {
                content = extractArticleText(fetchArticle(url));
            } catch (Exception e) {
                content = "";
            }
            // 直连被拒或正文过短时，回退 Jina Reader（免费网页转文本服务，可渲染 JS 页面），规避大站反爬
            if (content.length() < 120 && url.startsWith("http")) {
                try {
                    String viaReader = fetchViaJina(url);
                    if (viaReader != null && viaReader.length() > content.length()) {
                        content = viaReader;
                    }
                } catch (Exception e) {
                    // Reader 也不可用则保持仅标题
                }
            }
        }
        if (content.isBlank()) {
            content = hit.path("story_text").asText("");
        }
        content = content.replaceAll("\\s+", " ").trim();
        if (content.length() > CONTENT_MAX_LENGTH) {
            content = content.substring(0, CONTENT_MAX_LENGTH) + "…";
        }
        if (!content.isBlank()) {
            ((ObjectNode) hit).set("content", TextNode.valueOf(content));
        }
    }

    /** 从 HTML 提取正文：清洗脚本/导航等噪音后优先 article 标签，正文过短时回退 meta 描述 */
    private String extractArticleText(String html) {
        Document document = Jsoup.parse(html);
        document.select("script,style,nav,header,footer,aside,form,noscript,iframe,svg,button").remove();
        Element main = document.selectFirst("article");
        if (main == null) {
            main = document.selectFirst("main");
        }
        if (main == null) {
            main = document.selectFirst("[itemprop=articleBody]");
        }
        String text = (main != null ? main : document.body()).text();
        if (text == null || text.length() < 120) {
            String meta = metaContent(document, "meta[property=og:description]");
            if (meta.isBlank()) {
                meta = metaContent(document, "meta[name=description]");
            }
            if (meta.length() > (text == null ? 0 : text.length())) {
                text = meta;
            }
        }
        return text == null ? "" : text.trim();
    }

    /** 读取 meta 标签内容 */
    private String metaContent(Document document, String selector) {
        Element element = document.selectFirst(selector);
        return element == null ? "" : element.attr("content").trim();
    }

    /** 合并公司动态与热榜的全部条目（保持渲染顺序） */
    private List<JsonNode> concat(Map<String, List<JsonNode>> companyHits, List<JsonNode> hotHits) {
        List<JsonNode> all = new ArrayList<>();
        for (List<JsonNode> list : companyHits.values()) {
            all.addAll(list);
        }
        all.addAll(hotHits);
        return all;
    }

    /** 分板块渲染：头部厂商动态 + AI 圈最热榜，并要求模型基于正文翻译成中文简报 */
    private String render(List<String> companies, List<String> failedSources, int hours,
                          Map<String, List<JsonNode>> companyHits, List<JsonNode> hotHits) {
        StringBuilder builder = new StringBuilder("已从 Hacker News 检索到近 ").append(hours)
                .append(" 小时的 AI 圈动态：\n");
        int index = 1;
        boolean hasCompanyNews = companyHits.values().stream().anyMatch(list -> !list.isEmpty());
        if (hasCompanyNews) {
            builder.append("\n## 一、头部厂商动态\n");
            for (Map.Entry<String, List<JsonNode>> entry : companyHits.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                builder.append("\n【").append(entry.getKey()).append("】\n");
                for (JsonNode hit : entry.getValue()) {
                    builder.append(renderHit(index++, hit));
                }
            }
        }
        if (!hotHits.isEmpty()) {
            builder.append("\n## 二、AI 圈最热榜（不限厂商，按热度降序）\n");
            for (JsonNode hit : hotHits) {
                builder.append(renderHit(index++, hit));
            }
        }
        if (!failedSources.isEmpty()) {
            builder.append("\n（以下数据源本次检索失败：").append(String.join("、", failedSources)).append("）");
        }
        builder.append("\n以上为英文原文（含正文摘录）。请把每条内容翻译成中文，整理成一份简报："
                + "分「头部厂商动态」和「AI 圈最热榜」两个板块，每条含中文标题和基于正文的详细摘要"
                + "（覆盖内容要点，不要只复述标题），并保留原文链接，组内按热度排列；"
                + "没有正文摘录的条目仅依据标题简要带过，不要编造内容。");
        return builder.toString();
    }

    /** 单条内容渲染：热度/评论/标题/链接/时间/正文摘录 */
    private String renderHit(int index, JsonNode hit) {
        String title = hit.path("title").asText("(无标题)");
        String url = hit.path("url").asText("").isBlank()
                ? "https://news.ycombinator.com/item?id=" + hit.path("objectID").asText("")
                : hit.path("url").asText();
        String content = hit.path("content").asText("");
        StringBuilder builder = new StringBuilder("\n[" + index + "] " + title
                + "（" + hit.path("points").asInt(0) + " 分 / " + hit.path("num_comments").asInt(0) + " 评论）\n"
                + "    链接：" + url + "\n"
                + "    时间：" + hit.path("created_at").asText("").replace('T', ' ') + " UTC\n");
        if (!content.isBlank()) {
            builder.append("    内容：").append(content).append('\n');
        }
        return builder.toString();
    }

    /** 拆分公司清单：逗号分隔、去空白、去重，最多 5 家 */
    private List<String> splitCompanies(String companiesArg) {
        Set<String> companies = new LinkedHashSet<>();
        for (String company : companiesArg.split("[,，]")) {
            String trimmed = company.trim();
            if (!trimmed.isEmpty()) {
                companies.add(trimmed);
            }
        }
        return new ArrayList<>(companies).subList(0, Math.min(companies.size(), 5));
    }

    /** 抓取新闻原文页面：带浏览器请求头，避免被新闻站反爬拦截；独立成方法便于测试时打桩 */
    protected String fetchArticle(String url) throws Exception {
        return fetchArticle(url, ARTICLE_TIMEOUT_MS);
    }

    /** 带超时的原文抓取 */
    protected String fetchArticle(String url, int timeoutMs) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        return doFetch(request);
    }

    /** 通过 Jina Reader 抓取正文：全局节流避开免费限流，配置了 API Key 时附带鉴权头 */
    protected String fetchViaJina(String url) throws Exception {
        synchronized (JINA_GATE) {
            long now = System.currentTimeMillis();
            if (now < nextJinaAllowedAt) {
                Thread.sleep(nextJinaAllowedAt - now);
            }
            nextJinaAllowedAt = System.currentTimeMillis() + JINA_SPACING_MS;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://r.jina.ai/" + url))
                .timeout(Duration.ofMillis(JINA_TIMEOUT_MS))
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/plain")
                .header("X-Return-Format", "text");
        if (jinaApiKey != null && !jinaApiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + jinaApiKey.trim());
        }
        return doFetch(builder.GET().build());
    }

    /** 请求 JSON 接口（Hacker News API），独立成方法便于测试时打桩 */
    protected String fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(ARTICLE_TIMEOUT_MS))
                .GET()
                .build();
        return doFetch(request);
    }

    /** 统一执行请求并校验状态码 */
    private String doFetch(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("接口返回 " + response.statusCode());
        }
        return response.body();
    }

    private int clamp(int value, int min, int max, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.max(min, Math.min(value, max));
    }
}
