package com.rag.agent.service;

import com.rag.agent.dto.RunKnowledgeQualityRequest;
import com.rag.ai.ChatService;
import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.KbFile;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.FileMapper;
import com.rag.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库质检智能体服务
 * 负责生成知识库质量评分、问题清单、召回结果和优化建议
 */
@Service
public class KnowledgeQualityAgentService {

    private static final int shortChunkLimit = 80;
    private static final int longChunkLimit = 1500;

    private final KnowledgeBaseService knowledgeBaseService;
    private final FileMapper fileMapper;
    private final ChunkMapper chunkMapper;
    private final ChatService chatService;

    /**
     * 创建知识库质检智能体服务
     */
    public KnowledgeQualityAgentService(KnowledgeBaseService knowledgeBaseService,
                                        FileMapper fileMapper,
                                        ChunkMapper chunkMapper,
                                        ChatService chatService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper;
        this.chatService = chatService;
    }

    /**
     * 运行知识库质检
     */
    public Map<String, Object> runQualityCheck(RunKnowledgeQualityRequest request, Long userId) {
        // 第1步：校验知识库编号
        if (request == null || request.getKbId() == null) {
            throw new BusinessException(400, "请选择要质检的知识库");
        }
        // 第2步：规范检查模式
        String checkMode = normalizeCheckMode(request.getCheckMode());
        // 第3步：校验当前用户是否可以访问知识库
        knowledgeBaseService.checkAccess(request.getKbId(), userId);
        // 第4步：读取知识库基础信息
        KnowledgeBase knowledgeBase = knowledgeBaseService.getById(request.getKbId());
        // 第5步：读取知识库文件和分片
        List<KbFile> files = fileMapper.findByKbId(request.getKbId());
        List<KbChunk> chunks = chunkMapper.findByKbId(request.getKbId());
        // 第6步：计算基础指标
        Map<String, Object> metrics = buildMetrics(files, chunks);
        // 第7步：生成基础问题清单
        List<Map<String, Object>> issues = buildIssues(metrics);
        // 第8步：准备召回测试问题
        List<String> testQuestions = buildTestQuestions(request.getTestQuestions(), checkMode, knowledgeBase, files);
        // 第9步：执行召回测试
        List<Map<String, Object>> recallResults = runRecallChecks(request.getKbId(), checkMode, testQuestions);
        // 第10步：把召回结果写回指标和问题清单
        fillRecallMetrics(metrics, recallResults);
        addRecallIssues(issues, metrics);
        // 第11步：计算质检评分和风险等级
        int score = calculateScore(metrics);
        String riskLevel = calculateRiskLevel(score);
        // 第12步：生成优化建议和覆盖分析
        List<Map<String, Object>> suggestions = buildSuggestions(issues, metrics);
        Map<String, Object> coverage = buildCoverageAnalysis(knowledgeBase, files, testQuestions);
        // 第13步：组装完整报告
        return buildReport(knowledgeBase, checkMode, score, riskLevel, metrics, issues, recallResults, suggestions, coverage);
    }

    /**
     * 规范检查模式
     */
    private String normalizeCheckMode(String checkMode) {
        // 第1步：空模式默认使用标准模式
        if (checkMode == null || checkMode.isBlank()) {
            return "STANDARD";
        }
        // 第2步：只允许三种固定模式
        String upperMode = checkMode.trim().toUpperCase();
        if ("QUICK".equals(upperMode) || "STANDARD".equals(upperMode) || "DEEP".equals(upperMode)) {
            return upperMode;
        }
        // 第3步：非法模式回退到标准模式
        return "STANDARD";
    }

    /**
     * 构建知识库质检指标
     */
    private Map<String, Object> buildMetrics(List<KbFile> files, List<KbChunk> chunks) {
        // 第1步：创建指标容器
        Map<String, Object> metrics = new LinkedHashMap<>();
        // 第2步：统计文件状态
        int fileTotal = files.size();
        int completedFileCount = countFilesByStatus(files, "COMPLETED");
        int failedFileCount = countFilesByStatus(files, "FAILED");
        int processingFileCount = countProcessingFiles(files);
        // 第3步：统计分片质量
        int emptyChunkCount = 0;
        int shortChunkCount = 0;
        int longChunkCount = 0;
        int missingSourceCount = 0;
        int noiseChunkCount = 0;
        int minChunkLength = chunks.isEmpty() ? 0 : Integer.MAX_VALUE;
        int maxChunkLength = 0;
        long totalChunkLength = 0;
        Map<String, Integer> normalizedChunkCount = new LinkedHashMap<>();
        for (KbChunk chunk : chunks) {
            String content = safeText(chunk.getContent());
            int length = content.trim().length();
            totalChunkLength += length;
            minChunkLength = Math.min(minChunkLength, length);
            maxChunkLength = Math.max(maxChunkLength, length);
            if (length == 0) {
                emptyChunkCount++;
            }
            if (length > 0 && length < shortChunkLimit) {
                shortChunkCount++;
            }
            if (length > longChunkLimit) {
                longChunkCount++;
            }
            if (chunk.getSourceInfo() == null || chunk.getSourceInfo().isBlank()) {
                missingSourceCount++;
            }
            if (isNoiseChunk(content)) {
                noiseChunkCount++;
            }
            String normalizedContent = normalizeContent(content);
            if (!normalizedContent.isBlank()) {
                normalizedChunkCount.put(normalizedContent, normalizedChunkCount.getOrDefault(normalizedContent, 0) + 1);
            }
        }
        // 第4步：统计重复文件和重复分片
        int duplicateFileNameCount = countDuplicateFileNames(files);
        int duplicateChunkCount = countDuplicateItems(normalizedChunkCount);
        // 第5步：写入文件指标
        metrics.put("fileTotal", fileTotal);
        metrics.put("completedFileCount", completedFileCount);
        metrics.put("failedFileCount", failedFileCount);
        metrics.put("processingFileCount", processingFileCount);
        // 第6步：写入分片指标
        metrics.put("chunkTotal", chunks.size());
        metrics.put("emptyChunkCount", emptyChunkCount);
        metrics.put("shortChunkCount", shortChunkCount);
        metrics.put("longChunkCount", longChunkCount);
        metrics.put("missingSourceCount", missingSourceCount);
        metrics.put("duplicateFileNameCount", duplicateFileNameCount);
        metrics.put("duplicateChunkCount", duplicateChunkCount);
        metrics.put("noiseChunkCount", noiseChunkCount);
        metrics.put("averageChunkLength", chunks.isEmpty() ? 0 : Math.round((double) totalChunkLength / chunks.size()));
        metrics.put("minChunkLength", minChunkLength == Integer.MAX_VALUE ? 0 : minChunkLength);
        metrics.put("maxChunkLength", maxChunkLength);
        // 第7步：预留召回指标
        metrics.put("recallQuestionCount", 0);
        metrics.put("recallEmptyCount", 0);
        metrics.put("recallWeakCount", 0);
        return metrics;
    }

    /**
     * 按状态统计文件数量
     */
    private int countFilesByStatus(List<KbFile> files, String expectedStatus) {
        // 第1步：遍历文件并匹配状态
        int count = 0;
        for (KbFile file : files) {
            if (expectedStatus.equalsIgnoreCase(safeText(file.getStatus()))) {
                count++;
            }
        }
        // 第2步：返回匹配数量
        return count;
    }

    /**
     * 统计处理中或待处理文件数量
     */
    private int countProcessingFiles(List<KbFile> files) {
        // 第1步：遍历文件状态
        int count = 0;
        for (KbFile file : files) {
            String status = safeText(file.getStatus()).toUpperCase();
            if ("UPLOADED".equals(status) || "PROCESSING".equals(status) || "PENDING".equals(status)) {
                count++;
            }
        }
        // 第2步：返回处理中数量
        return count;
    }

    /**
     * 统计重复文件名数量
     */
    private int countDuplicateFileNames(List<KbFile> files) {
        // 第1步：按规范化文件名计数
        Map<String, Integer> fileNameCount = new LinkedHashMap<>();
        for (KbFile file : files) {
            String fileName = safeText(file.getFileName()).trim().toLowerCase();
            if (!fileName.isBlank()) {
                fileNameCount.put(fileName, fileNameCount.getOrDefault(fileName, 0) + 1);
            }
        }
        // 第2步：统计重复项
        return countDuplicateItems(fileNameCount);
    }

    /**
     * 统计重复项数量
     */
    private int countDuplicateItems(Map<String, Integer> itemCount) {
        // 第1步：累计超过一次的重复数量
        int duplicateCount = 0;
        for (Integer count : itemCount.values()) {
            if (count != null && count > 1) {
                duplicateCount += count - 1;
            }
        }
        // 第2步：返回重复数量
        return duplicateCount;
    }

    /**
     * 判断分片是否疑似噪声
     */
    private boolean isNoiseChunk(String content) {
        // 第1步：空内容交给空分片指标处理
        String text = safeText(content).replaceAll("\\s+", "");
        if (text.isBlank()) {
            return false;
        }
        // 第2步：短文本中只有目录或页码时视为噪声
        if (text.length() <= 40 && (text.contains("目录") || text.matches(".*第\\d+页.*"))) {
            return true;
        }
        // 第3步：统计字母、数字和中文字符比例
        int readableCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char currentChar = text.charAt(i);
            if (Character.isLetterOrDigit(currentChar) || isChineseChar(currentChar)) {
                readableCount++;
            }
        }
        // 第4步：可读字符太少时视为噪声
        return text.length() >= 20 && readableCount * 1.0 / text.length() < 0.35;
    }

    /**
     * 判断字符是否为常见中文字符
     */
    private boolean isChineseChar(char currentChar) {
        // 第1步：使用 Unicode 范围判断中文字符
        return currentChar >= '\u4e00' && currentChar <= '\u9fa5';
    }

    /**
     * 构建基础问题清单
     */
    private List<Map<String, Object>> buildIssues(Map<String, Object> metrics) {
        // 第1步：创建问题容器
        List<Map<String, Object>> issues = new ArrayList<>();
        // 第2步：读取核心指标
        int fileTotal = getInt(metrics, "fileTotal");
        int completedFileCount = getInt(metrics, "completedFileCount");
        int failedFileCount = getInt(metrics, "failedFileCount");
        int processingFileCount = getInt(metrics, "processingFileCount");
        int chunkTotal = getInt(metrics, "chunkTotal");
        // 第3步：检查文件问题
        if (fileTotal == 0 || completedFileCount == 0) {
            addIssue(issues, "HIGH", "EMPTY_KB", "知识库没有可用文件", "当前知识库还没有完成入库的文件，问答时无法稳定召回业务资料。", "已完成文件数：" + completedFileCount, "先上传并等待文件处理完成，再运行质检。");
        }
        if (failedFileCount > 0) {
            addIssue(issues, "HIGH", "FAILED_FILE", "存在处理失败文件", "有文件解析或向量化失败，这些资料不会进入稳定问答链路。", "失败文件数：" + failedFileCount, "进入文件管理页面，重试处理失败文件或重新上传原文件。");
        }
        if (processingFileCount > 0) {
            addIssue(issues, "MEDIUM", "PROCESSING_FILE", "存在未完成处理文件", "部分文件仍在上传后处理阶段，当前质检结果可能不是最终质量。", "处理中或待处理文件数：" + processingFileCount, "等待文件处理完成后再次运行质检。");
        }
        // 第4步：检查分片问题
        if (chunkTotal == 0) {
            addIssue(issues, "HIGH", "NO_CHUNK", "知识库没有文本分片", "没有文本分片会导致召回结果为空，问答只能依赖模型自身知识。", "分片数：0", "检查文件解析任务和分片任务是否执行成功。");
        }
        addMetricIssue(issues, metrics, "emptyChunkCount", "HIGH", "EMPTY_CHUNK", "存在空分片", "空分片会浪费召回位置，并降低知识库整体质量。", "删除异常分片或重新处理对应文件。");
        addMetricIssue(issues, metrics, "shortChunkCount", "MEDIUM", "SHORT_CHUNK", "存在过短分片", "过短分片容易缺少上下文，模型回答时证据不足。", "适当调大分片长度或优化分片策略。");
        addMetricIssue(issues, metrics, "longChunkCount", "MEDIUM", "LONG_CHUNK", "存在过长分片", "过长分片会降低召回精度，并增加上下文噪声。", "适当调小分片长度，让每个分片主题更集中。");
        addMetricIssue(issues, metrics, "missingSourceCount", "MEDIUM", "MISSING_SOURCE", "部分分片缺少来源", "缺少来源会影响回答引用和人工追溯。", "重新生成分片来源信息，至少保留文件名和段落序号。");
        addMetricIssue(issues, metrics, "duplicateFileNameCount", "LOW", "DUPLICATE_FILE", "存在重复文件名", "重复文件容易造成知识冗余，影响召回结果多样性。", "确认是否重复上传，必要时删除多余文件。");
        addMetricIssue(issues, metrics, "duplicateChunkCount", "LOW", "DUPLICATE_CHUNK", "存在重复分片", "重复分片会让召回集中在相同内容上，降低有效证据覆盖面。", "清理重复内容或重新处理重复文件。");
        addMetricIssue(issues, metrics, "noiseChunkCount", "MEDIUM", "NOISE_CHUNK", "存在疑似噪声分片", "目录、页眉页脚、乱码或 OCR 噪声会污染问答结果。", "清理原文噪声后重新上传，或在解析阶段过滤无效文本。");
        // 第5步：返回问题清单
        return issues;
    }

    /**
     * 根据指标生成问题
     */
    private void addMetricIssue(List<Map<String, Object>> issues,
                                Map<String, Object> metrics,
                                String metricKey,
                                String severity,
                                String type,
                                String title,
                                String description,
                                String suggestion) {
        // 第1步：读取指标数量
        int count = getInt(metrics, metricKey);
        // 第2步：没有异常时不生成问题
        if (count <= 0) {
            return;
        }
        // 第3步：生成问题项
        addIssue(issues, severity, type, title, description, "异常数量：" + count, suggestion);
    }

    /**
     * 添加问题项
     */
    private void addIssue(List<Map<String, Object>> issues,
                          String severity,
                          String type,
                          String title,
                          String description,
                          String evidence,
                          String suggestion) {
        // 第1步：创建问题对象
        Map<String, Object> issue = new LinkedHashMap<>();
        // 第2步：写入问题字段
        issue.put("severity", severity);
        issue.put("type", type);
        issue.put("title", title);
        issue.put("description", description);
        issue.put("evidence", evidence);
        issue.put("suggestion", suggestion);
        // 第3步：加入问题列表
        issues.add(issue);
    }

    /**
     * 构建召回测试问题
     */
    private List<String> buildTestQuestions(List<String> rawQuestions,
                                            String checkMode,
                                            KnowledgeBase knowledgeBase,
                                            List<KbFile> files) {
        // 第1步：优先使用用户输入的问题
        List<String> questions = new ArrayList<>();
        if (rawQuestions != null) {
            for (String question : rawQuestions) {
                String cleanQuestion = safeText(question).trim();
                if (!cleanQuestion.isBlank() && !questions.contains(cleanQuestion)) {
                    questions.add(cleanQuestion);
                }
            }
        }
        // 第2步：深度模式没有问题时自动生成轻量问题
        if ("DEEP".equals(checkMode) && questions.isEmpty()) {
            String kbName = safeText(knowledgeBase.getName()).trim();
            if (!kbName.isBlank()) {
                questions.add(kbName + "主要包含哪些内容？");
                questions.add(kbName + "有哪些关键流程或规则？");
            }
            for (KbFile file : files) {
                if (questions.size() >= 5) {
                    break;
                }
                String fileName = safeText(file.getFileName()).trim();
                if (!fileName.isBlank()) {
                    questions.add(fileName + "里有哪些重点信息？");
                }
            }
        }
        // 第3步：限制问题数量避免一次质检过慢
        return questions.stream().limit(10).collect(Collectors.toList());
    }

    /**
     * 执行召回测试
     */
    private List<Map<String, Object>> runRecallChecks(Long kbId, String checkMode, List<String> testQuestions) {
        // 第1步：快速模式不执行召回
        List<Map<String, Object>> recallResults = new ArrayList<>();
        if ("QUICK".equals(checkMode) || testQuestions.isEmpty()) {
            return recallResults;
        }
        // 第2步：逐个问题调用现有召回测试能力
        for (String question : testQuestions) {
            List<Map<String, Object>> results = chatService.recallTest(question, kbId);
            Map<String, Object> recallItem = new LinkedHashMap<>();
            recallItem.put("question", question);
            recallItem.put("hit", !results.isEmpty());
            recallItem.put("resultCount", results.size());
            recallItem.put("sources", buildRecallSources(results));
            recallItem.put("topContents", buildRecallSnippets(results));
            recallResults.add(recallItem);
        }
        // 第3步：返回召回测试结果
        return recallResults;
    }

    /**
     * 提取召回来源
     */
    private List<String> buildRecallSources(List<Map<String, Object>> results) {
        // 第1步：使用集合去重并保持顺序
        Set<String> sources = new LinkedHashSet<>();
        // 第2步：读取召回结果中的来源字段
        for (Map<String, Object> result : results) {
            Object source = result.get("source");
            if (source != null && !source.toString().isBlank()) {
                sources.add(source.toString());
            }
        }
        // 第3步：最多返回五个来源
        return sources.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * 提取召回片段摘要
     */
    private List<String> buildRecallSnippets(List<Map<String, Object>> results) {
        // 第1步：创建片段列表
        List<String> snippets = new ArrayList<>();
        // 第2步：截断召回内容，避免报告过长
        for (Map<String, Object> result : results) {
            Object content = result.get("content");
            if (content != null && !content.toString().isBlank()) {
                snippets.add(truncateText(content.toString(), 180));
            }
            if (snippets.size() >= 3) {
                break;
            }
        }
        // 第3步：返回片段列表
        return snippets;
    }

    /**
     * 写入召回指标
     */
    private void fillRecallMetrics(Map<String, Object> metrics, List<Map<String, Object>> recallResults) {
        // 第1步：统计召回为空和召回较弱的问题数
        int recallEmptyCount = 0;
        int recallWeakCount = 0;
        for (Map<String, Object> recallResult : recallResults) {
            int resultCount = getInt(recallResult, "resultCount");
            if (resultCount == 0) {
                recallEmptyCount++;
            } else if (resultCount < 2) {
                recallWeakCount++;
            }
        }
        // 第2步：写入召回指标
        metrics.put("recallQuestionCount", recallResults.size());
        metrics.put("recallEmptyCount", recallEmptyCount);
        metrics.put("recallWeakCount", recallWeakCount);
    }

    /**
     * 添加召回问题项
     */
    private void addRecallIssues(List<Map<String, Object>> issues, Map<String, Object> metrics) {
        // 第1步：读取召回指标
        int recallEmptyCount = getInt(metrics, "recallEmptyCount");
        int recallWeakCount = getInt(metrics, "recallWeakCount");
        // 第2步：召回为空时生成高风险问题
        if (recallEmptyCount > 0) {
            addIssue(issues, "HIGH", "RECALL_EMPTY", "部分测试问题召回为空", "测试问题没有召回到任何知识片段，用户提问时可能直接答不出来。", "召回为空问题数：" + recallEmptyCount, "补充相关资料，或检查 embedding 模型、向量库和分片策略。");
        }
        // 第3步：召回证据少时生成中风险问题
        if (recallWeakCount > 0) {
            addIssue(issues, "MEDIUM", "RECALL_WEAK", "部分测试问题召回证据偏少", "召回结果太少会让回答缺少交叉证据，容易不完整。", "召回较弱问题数：" + recallWeakCount, "增加相关资料，或调整 topK、分片长度和召回策略。");
        }
    }

    /**
     * 计算质检评分
     */
    private int calculateScore(Map<String, Object> metrics) {
        // 第1步：从满分开始扣分
        int score = 100;
        int fileTotal = getInt(metrics, "fileTotal");
        int chunkTotal = getInt(metrics, "chunkTotal");
        int recallQuestionCount = getInt(metrics, "recallQuestionCount");
        // 第2步：根据文件健康度扣分
        if (fileTotal == 0 || getInt(metrics, "completedFileCount") == 0) {
            score -= 45;
        }
        score -= ratioPenalty(getInt(metrics, "failedFileCount"), fileTotal, 20);
        score -= ratioPenalty(getInt(metrics, "processingFileCount"), fileTotal, 8);
        // 第3步：根据分片质量扣分
        if (chunkTotal == 0) {
            score -= 35;
        }
        score -= ratioPenalty(getInt(metrics, "emptyChunkCount"), chunkTotal, 15);
        score -= ratioPenalty(getInt(metrics, "shortChunkCount"), chunkTotal, 10);
        score -= ratioPenalty(getInt(metrics, "longChunkCount"), chunkTotal, 10);
        score -= ratioPenalty(getInt(metrics, "missingSourceCount"), chunkTotal, 10);
        score -= ratioPenalty(getInt(metrics, "duplicateChunkCount"), chunkTotal, 8);
        score -= ratioPenalty(getInt(metrics, "noiseChunkCount"), chunkTotal, 8);
        // 第4步：根据召回质量扣分
        score -= ratioPenalty(getInt(metrics, "recallEmptyCount"), recallQuestionCount, 25);
        score -= ratioPenalty(getInt(metrics, "recallWeakCount"), recallQuestionCount, 8);
        // 第5步：限制分数范围
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 按比例计算扣分
     */
    private int ratioPenalty(int count, int total, int maxPenalty) {
        // 第1步：没有总数时不按比例扣分
        if (count <= 0 || total <= 0) {
            return 0;
        }
        // 第2步：按异常比例计算扣分
        return (int) Math.ceil(Math.min(1.0, count * 1.0 / total) * maxPenalty);
    }

    /**
     * 计算风险等级
     */
    private String calculateRiskLevel(int score) {
        // 第1步：高分为健康
        if (score >= 85) {
            return "健康";
        }
        // 第2步：中间分为需关注
        if (score >= 70) {
            return "需关注";
        }
        // 第3步：低分为高风险
        return "高风险";
    }

    /**
     * 生成优化建议
     */
    private List<Map<String, Object>> buildSuggestions(List<Map<String, Object>> issues, Map<String, Object> metrics) {
        // 第1步：根据问题类型生成建议
        List<Map<String, Object>> suggestions = new ArrayList<>();
        Set<String> addedTypes = new LinkedHashSet<>();
        for (Map<String, Object> issue : issues) {
            String type = safeText(issue.get("type"));
            if (addedTypes.add(type)) {
                suggestions.add(buildSuggestion(issue, suggestions.size() + 1));
            }
        }
        // 第2步：没有问题时给出保持建议
        if (suggestions.isEmpty()) {
            Map<String, Object> suggestion = new LinkedHashMap<>();
            suggestion.put("priority", 1);
            suggestion.put("action", "当前知识库基础质量良好，可以保留现有入库和分片策略。");
            suggestion.put("reason", "本次质检没有发现明显的文件、分片或召回风险。");
            suggestions.add(suggestion);
        }
        // 第3步：补充复检建议
        Map<String, Object> finalSuggestion = new LinkedHashMap<>();
        finalSuggestion.put("priority", suggestions.size() + 1);
        finalSuggestion.put("action", "处理问题后再次运行知识库质检。");
        finalSuggestion.put("reason", "复检可以确认修复动作是否真的提升了评分和召回效果。");
        suggestions.add(finalSuggestion);
        return suggestions;
    }

    /**
     * 根据问题生成单条建议
     */
    private Map<String, Object> buildSuggestion(Map<String, Object> issue, int priority) {
        // 第1步：创建建议对象
        Map<String, Object> suggestion = new LinkedHashMap<>();
        // 第2步：写入建议内容
        suggestion.put("priority", priority);
        suggestion.put("action", issue.get("suggestion"));
        suggestion.put("reason", issue.get("description"));
        suggestion.put("sourceIssue", issue.get("type"));
        return suggestion;
    }

    /**
     * 构建知识覆盖分析
     */
    private Map<String, Object> buildCoverageAnalysis(KnowledgeBase knowledgeBase, List<KbFile> files, List<String> testQuestions) {
        // 第1步：创建覆盖分析容器
        Map<String, Object> coverage = new LinkedHashMap<>();
        // 第2步：从文件名提取当前覆盖主题
        List<String> coveredTopics = files.stream()
                .map(KbFile::getFileName)
                .filter(fileName -> fileName != null && !fileName.isBlank())
                .limit(8)
                .collect(Collectors.toList());
        // 第3步：根据描述和问题给出轻量缺口提示
        List<String> missingHints = new ArrayList<>();
        String description = safeText(knowledgeBase.getDescription());
        if (!description.isBlank() && files.isEmpty()) {
            missingHints.add("知识库有描述但没有文件，需要补充与描述匹配的资料。");
        }
        if (!testQuestions.isEmpty() && files.size() < 2) {
            missingHints.add("测试问题已经存在，但知识文件数量较少，建议补充 FAQ、流程说明或案例材料。");
        }
        if (description.contains("合同") && coveredTopics.stream().noneMatch(topic -> topic.contains("合同"))) {
            missingHints.add("知识库描述提到合同，但文件名中没有明显合同资料。");
        }
        if (description.contains("制度") && coveredTopics.stream().noneMatch(topic -> topic.contains("制度"))) {
            missingHints.add("知识库描述提到制度，但文件名中没有明显制度资料。");
        }
        // 第4步：写入覆盖分析结果
        coverage.put("coveredTopics", coveredTopics);
        coverage.put("missingHints", missingHints);
        coverage.put("analysisMethod", "基于知识库描述、文件名和测试问题做轻量规则判断");
        return coverage;
    }

    /**
     * 构建完整报告
     */
    private Map<String, Object> buildReport(KnowledgeBase knowledgeBase,
                                            String checkMode,
                                            int score,
                                            String riskLevel,
                                            Map<String, Object> metrics,
                                            List<Map<String, Object>> issues,
                                            List<Map<String, Object>> recallResults,
                                            List<Map<String, Object>> suggestions,
                                            Map<String, Object> coverage) {
        // 第1步：生成摘要
        String summary = buildSummary(score, riskLevel, issues, metrics);
        // 第2步：组装报告对象
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("agentCode", "knowledge-quality");
        report.put("agentName", "知识库质检智能体");
        report.put("kbId", knowledgeBase.getId());
        report.put("kbName", knowledgeBase.getName());
        report.put("checkMode", checkMode);
        report.put("score", score);
        report.put("riskLevel", riskLevel);
        report.put("summary", summary);
        report.put("metrics", metrics);
        report.put("issues", issues);
        report.put("recallResults", recallResults);
        report.put("coverage", coverage);
        report.put("suggestions", suggestions);
        report.put("generatedAt", LocalDateTime.now().toString());
        return report;
    }

    /**
     * 构建报告摘要
     */
    private String buildSummary(int score, String riskLevel, List<Map<String, Object>> issues, Map<String, Object> metrics) {
        // 第1步：统计高风险和中风险问题数量
        long highIssueCount = issues.stream().filter(issue -> "HIGH".equals(issue.get("severity"))).count();
        long mediumIssueCount = issues.stream().filter(issue -> "MEDIUM".equals(issue.get("severity"))).count();
        // 第2步：拼接摘要文案
        return "本次质检得分 " + score + " 分，风险等级为" + riskLevel
                + "。共发现 " + issues.size() + " 个问题，其中高风险 " + highIssueCount
                + " 个，中风险 " + mediumIssueCount + " 个。当前知识库包含 "
                + getInt(metrics, "fileTotal") + " 个文件、" + getInt(metrics, "chunkTotal")
                + " 个分片，建议优先处理高风险问题后再用于正式问答。";
    }

    /**
     * 获取整数指标
     */
    private int getInt(Map<String, Object> data, String key) {
        // 第1步：读取字段值
        Object value = data.get(key);
        // 第2步：数字类型直接转换
        if (value instanceof Number number) {
            return number.intValue();
        }
        // 第3步：其他类型按 0 处理
        return 0;
    }

    /**
     * 安全文本转换
     */
    private String safeText(Object value) {
        // 第1步：空值转换为空字符串
        if (value == null) {
            return "";
        }
        // 第2步：普通对象转换为字符串
        return value.toString();
    }

    /**
     * 规范化内容用于重复判断
     */
    private String normalizeContent(String content) {
        // 第1步：去掉空白并转成小写
        String text = safeText(content).replaceAll("\\s+", "").toLowerCase();
        // 第2步：过短内容不参与重复判断
        if (text.length() < 30) {
            return "";
        }
        // 第3步：截断超长内容，避免占用过多内存
        return truncateText(text, 500);
    }

    /**
     * 截断文本
     */
    private String truncateText(String text, int maxLength) {
        // 第1步：空文本直接返回空字符串
        String safeValue = safeText(text);
        // 第2步：长度在限制内直接返回
        if (safeValue.length() <= maxLength) {
            return safeValue;
        }
        // 第3步：超长文本截断并追加省略号
        return safeValue.substring(0, maxLength) + "...";
    }
}
