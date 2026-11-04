package com.rag.skill.controller;

import com.rag.common.BusinessException;
import com.rag.rbac.annotation.RequirePermission;
import com.rag.rbac.service.RbacService;
import com.rag.skill.builtin.WebSearchTool;
import com.rag.skill.entity.WebSearchConfig;
import com.rag.skill.mapper.WebSearchConfigMapper;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web_search 技能搜索源配置控制器
 * 配置按用户维度存储：普通保存写入当前用户专属配置，scope = platform 写入平台默认（user_id = 0）；
 * 读取时返回当前生效配置（用户专属优先）和平台默认，API Key 一律脱敏下发
 */
@RestController
@RequestMapping("/api/skills/web-search")
public class WebSearchConfigController {

    /** 管理员角色编码：平台默认搜索源涉及全站共用 Key，只允许管理员修改 */
    private static final String ADMIN_ROLE = "admin";

    private final WebSearchConfigMapper configMapper;

    private final RbacService rbacService;

    public WebSearchConfigController(WebSearchConfigMapper configMapper, RbacService rbacService) {
        this.configMapper = configMapper;
        this.rbacService = rbacService;
    }

    /**
     * 读取当前生效的搜索源配置（用户专属优先，其次平台默认）
     */
    @GetMapping("/config")
    @RequirePermission("skills:view")
    public Result<Map<String, Object>> getConfig(HttpServletRequest request) {
        long userId = currentUserId(request);
        // 第1步：解析生效配置与来源
        WebSearchConfig own = safeFind(userId);
        WebSearchConfig platform = safeFind(WebSearchConfig.PLATFORM_USER_ID);
        WebSearchConfig effective = valid(own) ? own : (valid(platform) ? platform : null);
        String source = effective == null ? "none" : (effective == own ? "user" : "platform");
        // 第2步：脱敏组装，平台默认单独下发供弹窗展示
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("effective", effective == null ? null : toMaskedView(effective));
        result.put("platform", platform == null ? null : toMaskedView(platform));
        return Result.success(result);
    }

    /**
     * 保存搜索源配置；scope = platform 保存为平台默认，否则保存为当前用户专属
     */
    @PutMapping("/config")
    @RequirePermission("skills:view")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        // 第1步：解析并校验参数
        String provider = body.get("provider") == null ? "" : String.valueOf(body.get("provider")).trim().toLowerCase();
        if (!WebSearchTool.PROVIDERS.contains(provider)) {
            throw new BusinessException(400, "不支持的搜索源：" + provider);
        }
        String apiKey = trimToNull(body.get("apiKey"));
        // 脱敏回显值（含 ****）视为未修改，交由下方保留原 Key 的逻辑处理
        if (apiKey != null && apiKey.contains("****")) {
            apiKey = null;
        }
        String baseUrl = trimToNull(body.get("baseUrl"));
        int maxResults = parseIntOrDefault(body.get("maxResults"), 5);
        if (maxResults < 1 || maxResults > 10) {
            throw new BusinessException(400, "结果条数仅支持 1-10");
        }
        // 第2步：Key 传空或为脱敏回显时保留原值，避免重复输入
        long targetUser = resolveTargetUser(body.get("scope"), request);
        WebSearchConfig existing = configMapper.findByUserId(targetUser);
        String apiKeyToStore = apiKey != null ? apiKey : (existing == null ? null : existing.getApiKey());
        if ("searxng".equals(provider)) {
            if (baseUrl == null || !baseUrl.startsWith("http")) {
                throw new BusinessException(400, "SearXNG 需要填写以 http(s):// 开头的实例地址");
            }
        } else if (apiKeyToStore == null || apiKeyToStore.isBlank()) {
            throw new BusinessException(400, "该搜索源需要填写 API Key");
        }
        // 第3步：落库
        WebSearchConfig config = new WebSearchConfig();
        config.setUserId(targetUser);
        config.setProvider(provider);
        config.setApiKey(apiKeyToStore);
        config.setBaseUrl(baseUrl);
        config.setMaxResults(maxResults);
        configMapper.upsert(config);
        return Result.success();
    }

    /**
     * 清除搜索源配置；scope = platform 清除平台默认（仅管理员），否则清除当前用户专属
     */
    @DeleteMapping("/config")
    @RequirePermission("skills:view")
    public Result<Void> deleteConfig(@RequestParam(required = false) String scope, HttpServletRequest request) {
        long targetUser = resolveTargetUser(scope, request);
        configMapper.deleteByUserId(targetUser);
        return Result.success();
    }

    /**
     * scope = platform 写平台默认配置（user_id = 0），其余写当前用户专属
     * 平台默认配置是全站共用 Key，非管理员不得读写，避免越权覆盖他人可用的搜索源
     */
    private long resolveTargetUser(Object scope, HttpServletRequest request) {
        if ("platform".equalsIgnoreCase(trimToNull(scope))) {
            long userId = currentUserId(request);
            if (!rbacService.hasRole(userId, ADMIN_ROLE)) {
                throw new BusinessException(403, "只有管理员可以修改平台默认搜索配置");
            }
            return WebSearchConfig.PLATFORM_USER_ID;
        }
        return currentUserId(request);
    }

    /** 当前登录用户编号，JWT 拦截器注入 */
    private long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "未登录或登录状态已失效");
        }
        return userId;
    }

    /** 表不存在等异常按无配置处理，不阻塞页面 */
    private WebSearchConfig safeFind(long userId) {
        try {
            return configMapper.findByUserId(userId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 配置是否完整可用（与工具执行侧校验保持一致） */
    private boolean valid(WebSearchConfig config) {
        if (config == null || config.getProvider() == null || !WebSearchTool.PROVIDERS.contains(config.getProvider())) {
            return false;
        }
        if ("searxng".equals(config.getProvider())) {
            return config.getBaseUrl() != null && config.getBaseUrl().trim().startsWith("http");
        }
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    /** 脱敏视图：Key 只保留前 4 位与后 4 位 */
    private Map<String, Object> toMaskedView(WebSearchConfig config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("provider", config.getProvider());
        view.put("apiKey", mask(config.getApiKey()));
        view.put("baseUrl", config.getBaseUrl());
        view.put("maxResults", config.getMaxResults());
        return view;
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private int parseIntOrDefault(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
