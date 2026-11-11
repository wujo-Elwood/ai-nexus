package com.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.ai.ChatService;
import com.rag.common.BusinessException;
import com.rag.rag.KnowledgeCitation;
import com.rag.service.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评测服务
 * 用「评测问题 + 期望来源」跑一遍真实检索链路，输出命中率 / MRR / 拒答率，
 * 让每次检索改动的效果都能被数字验证，而不是靠感觉。
 */
@Slf4j
@Service
public class EvalService {

    @Autowired private EvalMapper evalMapper;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 命中期望来源 */
    private static final String STATUS_HIT = "HIT";
    /** 有召回但没命中期望来源（排错了或被过滤了） */
    private static final String STATUS_MISS = "MISS";
    /** 一条都没召回，等同于线上会拒答 */
    private static final String STATUS_NO_EVIDENCE = "NO_EVIDENCE";
    /** 检索过程抛异常 */
    private static final String STATUS_ERROR = "ERROR";

    /** 期望来源的分隔符：逗号 / 分号 / 顿号 / 换行 */
    private static final String SOURCE_SPLIT_REGEX = "[,;，；、\\n\\r]+";

    /** 查询评测问题 */
    public List<EvalCase> listCases(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        return evalMapper.listCases(kbId, userId);
    }

    /** 新增评测问题 */
    public EvalCase addCase(EvalCase item, Long userId) {
        knowledgeBaseService.checkManageAccess(item.getKbId(), userId);
        if (item.getQuestion() == null || item.getQuestion().isBlank()) {
            throw new BusinessException(400, "评测问题不能为空");
        }
        item.setCreatedBy(userId);
        if (item.getEnabled() == null) {
            item.setEnabled(1);
        }
        evalMapper.insertCase(item);
        return item;
    }

    /** 删除评测问题 */
    public void deleteCase(Long id, Long userId) {
        if (evalMapper.deleteCase(id, userId) == 0) {
            throw new BusinessException(404, "评测问题不存在");
        }
    }

    /** 查询评测历史 */
    public List<EvalRun> listRuns(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        return evalMapper.listRuns(kbId, userId);
    }

    /**
     * 执行评测：逐条跑真实检索链路并落库明细
     * 口径说明：调用 ChatService.retrieveCitations，与线上问答走同一套知识库策略与检索规则，
     * 只跳过「调用大模型生成答案」这一步，因此测的是召回与排序质量。
     */
    public Map<String, Object> run(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        List<EvalCase> cases = evalMapper.listCases(kbId, userId);
        if (cases.isEmpty()) {
            throw new BusinessException(400, "请先添加至少一条评测问题");
        }

        EvalRun run = new EvalRun();
        run.setKbId(kbId);
        run.setCreatedBy(userId);
        run.setTotalCount(cases.size());

        int hits = 0;
        int groundedCount = 0;
        double reciprocalSum = 0;
        long totalLatency = 0;
        List<EvalRunItem> items = new ArrayList<>();

        for (EvalCase item : cases) {
            EvalRunItem detail = new EvalRunItem();
            detail.setCaseId(item.getId());
            detail.setQuestion(item.getQuestion());
            detail.setTokenCount(0);
            long start = System.currentTimeMillis();
            try {
                // 第1步：跑一次真实检索（不调大模型）
                List<KnowledgeCitation> citations = chatService.retrieveCitations(item.getQuestion(), kbId);
                long cost = System.currentTimeMillis() - start;
                detail.setLatencyMs(cost);
                totalLatency += cost;

                // 第2步：是否检出了可用证据（对应线上「不会被拒答」）
                boolean grounded = citations != null && !citations.isEmpty();
                detail.setGrounded(grounded ? 1 : 0);
                if (grounded) {
                    groundedCount++;
                }

                // 第3步：计算期望来源排在第几位；用例没写期望来源时，只要召回就算命中
                String expected = item.getExpectedSources();
                boolean expectedBlank = expected == null || expected.isBlank();
                int rank = expectedBlank ? (grounded ? 1 : 0) : matchRank(citations, expected);
                detail.setMatchedRank(rank);

                boolean hit = rank > 0;
                detail.setHit(hit ? 1 : 0);
                detail.setCitationHit(hit ? 1 : 0);
                detail.setResultStatus(hit ? STATUS_HIT : (grounded ? STATUS_MISS : STATUS_NO_EVIDENCE));
                if (hit) {
                    hits++;
                    reciprocalSum += 1.0 / rank;
                }
                detail.setSources(toSourcesJson(citations));
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - start;
                detail.setLatencyMs(cost);
                totalLatency += cost;
                detail.setResultStatus(STATUS_ERROR);
                detail.setHit(0);
                detail.setCitationHit(0);
                detail.setGrounded(0);
                detail.setMatchedRank(0);
                detail.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 500));
                log.warn("评测用例执行失败：question={}, error={}", item.getQuestion(), e.getMessage());
            }
            items.add(detail);
        }

        run.setHitCount(hits);
        run.setCitationHitCount(hits);
        run.setGroundedCount(groundedCount);
        // MRR 用总用例数做分母，未命中计 0，便于横向比较不同版本的检索质量
        run.setMrr(round4(reciprocalSum / cases.size()));
        run.setAvgLatencyMs(totalLatency / cases.size());
        run.setTotalTokens(0);
        run.setRunStatus(hits == cases.size() ? "SUCCESS" : "PARTIAL");
        run.setFinishedAt(LocalDateTime.now());
        run.setParameterSnapshot(buildSnapshot(cases.size()));
        evalMapper.insertRun(run);

        for (EvalRunItem detail : items) {
            detail.setRunId(run.getId());
            evalMapper.insertItem(detail);
        }

        return buildSummary(run, items);
    }

    /** 查询某次运行的明细 */
    public Map<String, Object> runDetail(Long runId, Long userId) {
        EvalRun run = evalMapper.findRunById(runId);
        if (run == null) {
            throw new BusinessException(404, "评测记录不存在");
        }
        knowledgeBaseService.checkManageAccess(run.getKbId(), userId);
        List<EvalRunItem> items = evalMapper.listItemsByRun(runId);
        return buildSummary(run, items);
    }

    /** 组装「摘要 + 明细 + 派生指标」的返回结构 */
    private Map<String, Object> buildSummary(EvalRun run, List<EvalRunItem> items) {
        int total = run.getTotalCount() == null ? 0 : run.getTotalCount();
        int hits = run.getHitCount() == null ? 0 : run.getHitCount();
        int grounded = run.getGroundedCount() == null ? 0 : run.getGroundedCount();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("items", items);
        // 命中率 / 拒答率按百分比返回，前端直接展示
        result.put("hitRate", total == 0 ? 0d : round2(hits * 100.0 / total));
        result.put("refusalRate", total == 0 ? 0d : round2((total - grounded) * 100.0 / total));
        result.put("mrr", run.getMrr() == null ? 0d : run.getMrr());
        return result;
    }

    /**
     * 计算首个命中期望来源的排名（1 起）
     * 期望来源支持多个关键词（逗号/分号/顿号/换行分隔），任一命中即算命中
     */
    private int matchRank(List<KnowledgeCitation> citations, String expectedSources) {
        if (citations == null || citations.isEmpty() || expectedSources == null || expectedSources.isBlank()) {
            return 0;
        }
        List<String> tokens = new ArrayList<>();
        for (String token : expectedSources.split(SOURCE_SPLIT_REGEX)) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed.toLowerCase());
            }
        }
        if (tokens.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < citations.size(); i++) {
            KnowledgeCitation citation = citations.get(i);
            String fileName = citation.getFileName() == null ? "" : citation.getFileName().toLowerCase();
            String source = citation.getSource() == null ? "" : citation.getSource().toLowerCase();
            for (String token : tokens) {
                if (fileName.contains(token) || source.contains(token)) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    /** 把本次召回结果压成 JSON 快照，便于事后复盘是哪几段被召回 */
    private String toSourcesJson(List<KnowledgeCitation> citations) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        if (citations != null) {
            int limit = Math.min(citations.size(), 10);
            for (int i = 0; i < limit; i++) {
                KnowledgeCitation citation = citations.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", i + 1);
                row.put("fileName", citation.getFileName());
                row.put("chunkIndex", citation.getChunkIndex());
                row.put("matchType", citation.getMatchType());
                row.put("vectorScore", citation.getVectorScore());
                row.put("keywordScore", citation.getKeywordScore());
                row.put("finalScore", citation.getFinalScore());
                snapshot.add(row);
            }
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 记录本次评测用的检索口径，方便对比不同版本的结果 */
    private String buildSnapshot(int caseCount) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", "retrieval");
        snapshot.put("retrievalPath", "ChatService.buildContextWithSources");
        snapshot.put("caseCount", caseCount);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    private double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
