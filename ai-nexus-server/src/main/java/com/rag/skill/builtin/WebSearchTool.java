package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.skill.core.AgentTool;
import com.rag.skill.entity.WebSearchConfig;
import com.rag.skill.mapper.WebSearchConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 联网搜索技能：调用外部搜索源 API 返回标题、链接和摘要
 * 支持 Tavily、Serper、博查、自建 SearXNG 四类搜索源；
 * 搜索源和 API Key 按用户维度存放在 web_search_config 表（user_id = 0 为平台默认），
 * 执行时优先取当前用户配置，没有则回退平台默认；两者都没有时返回配置指引
 */
@Slf4j
@Component
public class WebSearchTool implements AgentTool {

    /** 支持的搜索源编码 */
    public static final Set<String> PROVIDERS = Set.of("tavily", "serper", "bocha", "searxng");

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 10;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;
    private static final int SNIPPET_MAX_LENGTH = 300;

    private final ObjectMapper objectMapper;
    private final WebSearchConfigMapper configMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WebSearchTool(ObjectMapper objectMapper, WebSearchConfigMapper configMapper) {
        this.objectMapper = objectMapper;
        this.configMapper = configMapper;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索互联网信息，返回标题、链接和摘要。当用户询问实时资讯、时事热点或知识库之外的事实信息时使用；回答时请注明信息来源链接。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("query", "string", "搜索关键词，建议使用简洁的核心词，可用空格组合多个条件", true),
                new ToolParam("max_results", "number", "返回结果条数（1-10），不传时使用配置的默认值", false));
    }

    @Override
    public String execute(JsonNode arguments) throws Exception {
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return "参数错误：需要传入 query（搜索关键词）";
        }

        WebSearchConfig config = resolveConfig(arguments.path("userId").asLong(0));
        if (config == null) {
            return "搜索源尚未配置，无法执行联网搜索。请到「技能管理」页找到 web_search 技能，"
                    + "点击「搜索源配置」填写搜索源和 API Key（支持 Tavily、Serper、博查、自建 SearXNG）。"
                    + "请将以上提示转告用户。";
        }

        int maxResults = clampMaxResults(arguments.path("max_results").asInt(0), config.getMaxResults());
        List<SearchResult> results = search(config, query, maxResults);
        if (results.isEmpty()) {
            return "搜索源（" + config.getProvider() + "）没有返回「" + query + "」的结果，可尝试更换关键词后重试。";
        }
        return formatResults(config.getProvider(), query, results);
    }

    /**
     * 解析生效的搜索源配置：当前用户专属配置优先，没有则回退平台默认；
     * 表不存在等数据库异常按未配置处理，不影响对话继续
     */
    private WebSearchConfig resolveConfig(long userId) {
        try {
            if (userId > 0) {
                WebSearchConfig own = configMapper.findByUserId(userId);
                if (own != null && validProvider(own)) {
                    return own;
                }
            }
            WebSearchConfig platform = configMapper.findByUserId(WebSearchConfig.PLATFORM_USER_ID);
            return validProvider(platform) ? platform : null;
        } catch (Exception e) {
            log.warn("读取搜索源配置失败（web_search_config 表可能未创建）: {}", e.getMessage());
            return null;
        }
    }

    /** 校验配置完整性：搜索源合法，且按搜索源要求具备 Key 或地址 */
    private boolean validProvider(WebSearchConfig config) {
        if (config == null || config.getProvider() == null || !PROVIDERS.contains(config.getProvider())) {
            return false;
        }
        if ("searxng".equals(config.getProvider())) {
            return config.getBaseUrl() != null && config.getBaseUrl().trim().startsWith("http");
        }
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    /** 结果条数：模型传参优先，其次配置值，最后默认值；统一收敛到 1-10 */
    private int clampMaxResults(int requested, Integer configured) {
        int value = requested > 0 ? requested
                : (configured == null || configured <= 0 ? DEFAULT_MAX_RESULTS : configured);
        return Math.max(1, Math.min(value, MAX_RESULTS_LIMIT));
    }

    /** 按搜索源分发请求并归一化结果 */
    private List<SearchResult> search(WebSearchConfig config, String query, int maxResults) throws Exception {
        return switch (config.getProvider()) {
            case "tavily" -> searchTavily(config, query, maxResults);
            case "serper" -> searchSerper(config, query, maxResults);
            case "bocha" -> searchBocha(config, query, maxResults);
            case "searxng" -> searchSearxng(config, query, maxResults);
            default -> List.of();
        };
    }

    /** Tavily：POST JSON，Bearer 鉴权，results[].title/url/content */
    private List<SearchResult> searchTavily(WebSearchConfig config, String query, int maxResults) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("max_results", maxResults);
        body.put("search_depth", "basic");
        JsonNode root = postJson("https://api.tavily.com/search",
                Map.of("Authorization", "Bearer " + config.getApiKey()), body);
        return collect(root.path("results"), "title", "url", "content", maxResults);
    }

    /** Serper：POST JSON，X-API-KEY 头鉴权，返回 Google 索引，organic[].title/link/snippet */
    private List<SearchResult> searchSerper(WebSearchConfig config, String query, int maxResults) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("q", query);
        body.put("num", maxResults);
        JsonNode root = postJson("https://google.serper.dev/search",
                Map.of("X-API-KEY", config.getApiKey()), body);
        return collect(root.path("organic"), "title", "link", "snippet", maxResults);
    }

    /** 博查：POST JSON，Bearer 鉴权，国内直连，data.webPages.value[].name/url/summary */
    private List<SearchResult> searchBocha(WebSearchConfig config, String query, int maxResults) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("count", maxResults);
        body.put("summary", true);
        JsonNode root = postJson("https://api.bochaai.com/v1/web-search",
                Map.of("Authorization", "Bearer " + config.getApiKey()), body);
        JsonNode items = root.path("data").path("webPages").path("value");
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            if (results.size() >= maxResults) {
                break;
            }
            String snippet = firstNonBlank(item.path("summary").asText(""), item.path("snippet").asText(""));
            results.add(new SearchResult(item.path("name").asText(""), item.path("url").asText(""), snippet));
        }
        return results;
    }

    /** SearXNG：自建实例 GET JSON 输出，免 Key，results[].title/url/content */
    private List<SearchResult> searchSearxng(WebSearchConfig config, String query, int maxResults) throws Exception {
        String base = config.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + "/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&language=zh-CN";
        JsonNode root = getJson(url);
        return collect(root.path("results"), "title", "url", "content", maxResults);
    }

    /** 通用 POST JSON 请求，带超时与非 200 校验 */
    private JsonNode postJson(String url, Map<String, String> headers, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        headers.forEach(builder::header);
        return parseResponse(httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()), url);
    }

    /** 通用 GET JSON 请求 */
    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build();
        return parseResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()), url);
    }

    /** 响应解析：非 2xx 抛出带状态码的异常，交由 SkillRegistry 统一以失败步骤记录 */
    private JsonNode parseResponse(HttpResponse<String> response, String url) throws java.io.IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("搜索接口返回 " + response.statusCode() + "：" + url);
        }
        return objectMapper.readTree(response.body());
    }

    /** 从指定字段名的结果数组归一化收集，最多 maxResults 条 */
    private List<SearchResult> collect(JsonNode items, String titleField, String urlField,
                                       String snippetField, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            if (results.size() >= maxResults) {
                break;
            }
            results.add(new SearchResult(item.path(titleField).asText(""),
                    item.path(urlField).asText(""), item.path(snippetField).asText("")));
        }
        return results;
    }

    /** 输出编号列表，供模型引用来源 */
    private String formatResults(String provider, String query, List<SearchResult> results) {
        StringBuilder text = new StringBuilder();
        text.append("「").append(query).append("」的联网搜索结果（来源：").append(provider)
                .append("，共 ").append(results.size()).append(" 条）：");
        int index = 1;
        for (SearchResult result : results) {
            text.append("\n").append(index++).append(". ").append(result.title().isEmpty() ? "（无标题）" : result.title());
            if (!result.url().isEmpty()) {
                text.append("\n   链接: ").append(result.url());
            }
            if (!result.snippet().isEmpty()) {
                text.append("\n   摘要: ").append(truncate(result.snippet()));
            }
        }
        return text.toString();
    }

    /** 摘要截断，避免单条结果占用过多上下文 */
    private String truncate(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_MAX_LENGTH ? normalized
                : normalized.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }

    private String firstNonBlank(String a, String b) {
        return !a.isBlank() ? a : b;
    }

    /** 归一化后的单条搜索结果 */
    private record SearchResult(String title, String url, String snippet) {
    }
}
