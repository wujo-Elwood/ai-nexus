package com.rag.knowledgegap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.BusinessException;
import com.rag.entity.AnswerQuality;
import com.rag.entity.KnowledgeGapReport;
import com.rag.entity.ModelProvider;
import com.rag.mapper.AnswerQualityMapper;
import com.rag.mapper.KnowledgeGapReportMapper;
import com.rag.service.ModelProviderService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/** 知识缺口分析服务，聚类拒答和低质量问题并持久化报告。 */
@Slf4j
@Service
public class KnowledgeGapAnalysisService {

    /** 只允许报告这两个固定时间窗口。 */
    private static final Set<Integer> SUPPORTED_WINDOWS = Set.of(7, 30);

    private final AnswerQualityMapper answerQualityMapper;
    private final KnowledgeGapReportMapper reportMapper;
    private final ModelProviderService modelProviderService;
    private final Executor analysisExecutor;
    private final BiFunction<String, ModelProvider, String> modelCaller;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 标记当前是否存在正在执行的分析任务。 */
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);

    /** 创建使用真实激活模型和受管线程池的分析服务。 */
    @Autowired
    public KnowledgeGapAnalysisService(AnswerQualityMapper answerQualityMapper,
                                       KnowledgeGapReportMapper reportMapper,
                                       ModelProviderService modelProviderService,
                                       @Qualifier("knowledgeGapAnalysisExecutor") Executor analysisExecutor) {
        this(answerQualityMapper, reportMapper, modelProviderService, analysisExecutor, null);
    }

    /** 创建可注入模型调用器的分析服务，供测试隔离外部模型。 */
    public KnowledgeGapAnalysisService(AnswerQualityMapper answerQualityMapper,
                                       KnowledgeGapReportMapper reportMapper,
                                       ModelProviderService modelProviderService,
                                       Executor analysisExecutor,
                                       BiFunction<String, ModelProvider, String> modelCaller) {
        this.answerQualityMapper = answerQualityMapper;
        this.reportMapper = reportMapper;
        this.modelProviderService = modelProviderService;
        this.analysisExecutor = analysisExecutor;
        this.modelCaller = modelCaller;
    }

    /** 查询窗口报告；尚未生成时返回可直接渲染的空报告。 */
    public Map<String, Object> getReport(int days) {
        validateWindow(days);
        KnowledgeGapReport saved = reportMapper.findByWindowDays(days);
        if (saved == null) {
            return emptyReport(days, "NOT_GENERATED");
        }
        try {
            Map<String, Object> report = saved.getReportJson() == null || saved.getReportJson().isBlank()
                    ? emptyReport(days, saved.getStatus())
                    : objectMapper.readValue(saved.getReportJson(), new TypeReference<>() {});
            report.put("status", saved.getStatus());
            report.put("sampleCount", saved.getSampleCount());
            report.put("refusalCount", saved.getRefusalCount());
            report.put("lowConfidenceCount", saved.getLowConfidenceCount());
            report.put("negativeFeedbackCount", saved.getNegativeFeedbackCount());
            report.put("generatedAt", saved.getGeneratedAt());
            if (saved.getErrorMessage() != null) {
                report.put("error", saved.getErrorMessage());
            }
            return report;
        } catch (Exception e) {
            log.warn("Knowledge gap report JSON parse failed, window={}", days, e);
            return emptyReport(days, saved.getStatus());
        }
    }

    /** 提交异步分析任务，重复提交时返回冲突。 */
    public Map<String, Object> triggerAnalysis(int days, Long userId) {
        validateWindow(days);
        if (!analysisRunning.compareAndSet(false, true)) {
            throw new BusinessException(409, "知识缺口分析正在进行，请稍后查看报告");
        }
        try {
            // 先持久化运行状态，让报告查询和前端轮询立即看到任务已启动
            reportMapper.markRunning(days, LocalDateTime.now());
            analysisExecutor.execute(() -> {
                try {
                    analyzeWindowInternal(days);
                } catch (Exception e) {
                    // 异步线程出现未预期异常时也要结束运行状态，避免报告永久显示 RUNNING
                    log.error("Knowledge gap analysis failed unexpectedly, window={}", days, e);
                    markAnalysisFailure(days, e);
                } finally {
                    analysisRunning.set(false);
                }
            });
        } catch (Exception e) {
            analysisRunning.set(false);
            // 线程池拒绝或状态落库失败时清理 RUNNING 状态，避免留下不可恢复任务
            markAnalysisFailure(days, e);
            throw new BusinessException(503, "无法提交知识缺口分析任务，请稍后重试");
        }
        return Map.of("days", days, "status", "RUNNING", "submittedBy", userId);
    }

    /** 记录异步分析失败状态，保留最近一次成功报告 JSON。 */
    private void markAnalysisFailure(int days, Exception exception) {
        try {
            String message = exception.getMessage() == null ? "知识缺口分析失败" : exception.getMessage();
            reportMapper.updateFailure(days, "FAILED", message, LocalDateTime.now());
        } catch (Exception saveError) {
            log.error("Failed to save knowledge gap failure status, window={}", days, saveError);
        }
    }

    /** 分析单个窗口并写入报告快照，供定时任务和测试调用。 */
    public Map<String, Object> analyzeWindow(int days) {
        validateWindow(days);
        if (!analysisRunning.compareAndSet(false, true)) {
            throw new BusinessException(409, "知识缺口分析正在进行，请稍后查看报告");
        }
        try {
            return analyzeWindowInternal(days);
        } finally {
            analysisRunning.set(false);
        }
    }

    /** 执行单窗口分析主体，调用方负责控制并发状态。 */
    private Map<String, Object> analyzeWindowInternal(int days) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeGapReport existing = reportMapper.findByWindowDays(days);
        List<AnswerQuality> samples = answerQualityMapper.findSamples(now.minusDays(days));
        List<AnswerQuality> uniqueSamples = deduplicate(samples);
        KnowledgeGapReportData data = buildBaseData(days, samples, uniqueSamples);
        try {
            if (uniqueSamples.isEmpty()) {
                data.setTopics(List.of());
                data.setAnalysisMethod("NONE");
            } else {
                data.setTopics(analyzeTopics(uniqueSamples));
                data.setAnalysisMethod("LLM");
            }
            Map<String, Object> report = toReportMap(data, now, "COMPLETED");
            String json = objectMapper.writeValueAsString(report);
            KnowledgeGapReport saved = toEntity(data, json, "COMPLETED", null, now);
            if (existing == null) {
                reportMapper.insert(saved);
            } else {
                reportMapper.updateSuccess(saved);
            }
            return report;
        } catch (Exception e) {
            String message = e.getMessage() == null ? "知识缺口分析失败" : e.getMessage();
            data.setTopics(ruleTopics(uniqueSamples));
            data.setAnalysisMethod("RULE_FALLBACK");
            data.setError(message);
            Map<String, Object> fallback = toReportMap(data, now, "COMPLETED");
            try {
                String json = objectMapper.writeValueAsString(fallback);
                KnowledgeGapReport saved = toEntity(data, json, "COMPLETED", message, now);
                if (existing == null) {
                    reportMapper.insert(saved);
                } else {
                    reportMapper.updateSuccess(saved);
                }
            } catch (Exception saveError) {
                log.error("Failed to save knowledge gap fallback report", saveError);
            }
            return fallback;
        }
    }

    /** 根据事实样本统计报告基础指标。 */
    private KnowledgeGapReportData buildBaseData(int days, List<AnswerQuality> samples,
                                                 List<AnswerQuality> uniqueSamples) {
        KnowledgeGapReportData data = new KnowledgeGapReportData();
        data.setWindowDays(days);
        data.setSampleCount(samples.size());
        data.setRefusalCount((int) samples.stream().filter(item -> Boolean.TRUE.equals(item.getRefusal())).count());
        data.setLowConfidenceCount((int) samples.stream().filter(item -> item.getConfidence() != null && item.getConfidence() < 50).count());
        data.setNegativeFeedbackCount((int) samples.stream().filter(item -> Integer.valueOf(0).equals(item.getHelpful())).count());
        return data;
    }

    /** 对问题做空白、大小写和标点归一化后去重。 */
    private List<AnswerQuality> deduplicate(List<AnswerQuality> samples) {
        Map<String, AnswerQuality> unique = new LinkedHashMap<>();
        for (AnswerQuality sample : samples == null ? List.<AnswerQuality>of() : samples) {
            String key = normalizeQuestion(sample.getQuestion());
            if (!key.isBlank()) {
                unique.putIfAbsent(key, sample);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** 调用激活模型并解析主题 JSON。 */
    private List<Map<String, Object>> analyzeTopics(List<AnswerQuality> samples) throws Exception {
        ModelProvider provider = modelProviderService.getActive();
        List<Map<String, Object>> input = new ArrayList<>();
        for (AnswerQuality sample : samples) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", sample.getQuestion());
            item.put("confidence", sample.getConfidence());
            item.put("evidenceCoverage", sample.getEvidenceCoverage());
            item.put("refusal", sample.getRefusal());
            item.put("reason", sample.getReason());
            item.put("helpful", sample.getHelpful());
            input.add(item);
        }
        String response = callModel(buildPrompt(input), provider);
        JsonNode root = objectMapper.readTree(response == null ? "" : response.trim());
        JsonNode topicsNode = root.isArray() ? root : root.path("topics");
        if (!topicsNode.isArray()) {
            throw new IllegalArgumentException("LLM 返回不是主题 JSON 数组");
        }
        List<Map<String, Object>> topics = objectMapper.convertValue(topicsNode, new TypeReference<>() {});
        if (topics.isEmpty()) {
            throw new IllegalArgumentException("LLM 未返回有效主题");
        }
        return topics;
    }

    /** 构建约束模型只输出 JSON 的提示词。 */
    private String buildPrompt(List<Map<String, Object>> samples) throws Exception {
        return "你是知识库运营分析助手。请将以下反复拒答或低质量问题聚类成知识缺口主题。"
                + "只返回 JSON，不要 Markdown，不要解释。格式为："
                + "{\"topics\":[{\"topic\":\"主题\",\"count\":1,\"representativeQuestions\":[\"问题\"],"
                + "\"gap\":\"缺少的知识\",\"suggestedDocuments\":[\"建议文档\"],\"priority\":\"HIGH|MEDIUM|LOW\"}]}。"
                + "问题样本：\n" + objectMapper.writeValueAsString(samples);
    }

    /** LLM 失败时按问题前缀做确定性规则聚类。 */
    private List<Map<String, Object>> ruleTopics(List<AnswerQuality> samples) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (AnswerQuality sample : samples) {
            String question = sample.getQuestion() == null ? "未分类问题" : sample.getQuestion().trim();
            String topic = question.length() <= 12 ? question : question.substring(0, 12);
            grouped.computeIfAbsent(topic, key -> new ArrayList<>()).add(question);
        }
        List<Map<String, Object>> topics = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("topic", entry.getKey());
            topic.put("count", entry.getValue().size());
            topic.put("representativeQuestions", entry.getValue().stream().limit(3).toList());
            topic.put("gap", "该主题缺少可直接支撑回答的知识库资料");
            topic.put("suggestedDocuments", List.of(entry.getKey() + " FAQ 或流程说明"));
            topic.put("priority", entry.getValue().size() >= 3 ? "HIGH" : "MEDIUM");
            topics.add(topic);
        }
        topics.sort(Comparator.comparingInt(item -> -((Number) item.get("count")).intValue()));
        return topics;
    }

    /** 把报告数据转换成接口和 JSON 共用的 Map。 */
    private Map<String, Object> toReportMap(KnowledgeGapReportData data, LocalDateTime generatedAt, String status) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("windowDays", data.getWindowDays());
        report.put("sampleCount", data.getSampleCount());
        report.put("refusalCount", data.getRefusalCount());
        report.put("lowConfidenceCount", data.getLowConfidenceCount());
        report.put("negativeFeedbackCount", data.getNegativeFeedbackCount());
        report.put("topics", data.getTopics());
        report.put("analysisMethod", data.getAnalysisMethod());
        report.put("status", status);
        report.put("generatedAt", generatedAt == null ? null : generatedAt.toString());
        if (data.getError() != null) {
            report.put("error", data.getError());
        }
        return report;
    }

    /** 把分析数据映射为报告实体。 */
    private KnowledgeGapReport toEntity(KnowledgeGapReportData data, String json, String status,
                                        String error, LocalDateTime generatedAt) {
        KnowledgeGapReport report = new KnowledgeGapReport();
        report.setWindowDays(data.getWindowDays());
        report.setSampleCount(data.getSampleCount());
        report.setRefusalCount(data.getRefusalCount());
        report.setLowConfidenceCount(data.getLowConfidenceCount());
        report.setNegativeFeedbackCount(data.getNegativeFeedbackCount());
        report.setReportJson(json);
        report.setStatus(status);
        report.setErrorMessage(error);
        report.setGeneratedAt(generatedAt);
        return report;
    }

    /** 返回未生成窗口的统一空结构。 */
    private Map<String, Object> emptyReport(int days, String status) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("windowDays", days);
        report.put("sampleCount", 0);
        report.put("refusalCount", 0);
        report.put("lowConfidenceCount", 0);
        report.put("negativeFeedbackCount", 0);
        report.put("topics", List.of());
        report.put("analysisMethod", "NONE");
        report.put("status", status);
        return report;
    }

    /** 校验报告窗口参数。 */
    private void validateWindow(int days) {
        if (!SUPPORTED_WINDOWS.contains(days)) {
            throw new BusinessException(400, "分析窗口只支持最近 7 天或最近 30 天");
        }
    }

    /** 规范化问题文本用于去重。 */
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：‘’“”《》【】（）()]+", "");
    }

    /** 调用当前激活的 OpenAI 兼容模型。 */
    protected String callModel(String prompt, ModelProvider provider) {
        if (modelCaller != null) {
            return modelCaller.apply(prompt, provider);
        }
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl(provider.getBaseUrl()))
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .temperature(0.1)
                .build();
        Response<AiMessage> response = model.generate(List.of(
                SystemMessage.from("你只能返回合法 JSON。"), UserMessage.from(prompt)));
        return response.content().text();
    }

    /** 归一化模型供应商地址。 */
    private String normalizeBaseUrl(String rawBaseUrl) {
        String value = rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        return value.endsWith("/v1") ? value : value + "/v1";
    }
}
