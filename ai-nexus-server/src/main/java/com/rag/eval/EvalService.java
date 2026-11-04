package com.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.ai.ChatService;
import com.rag.service.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** RAG 评测服务，按评测问题执行检索并计算命中指标。 */
@Service
public class EvalService {
    @Autowired private EvalMapper evalMapper;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 查询评测问题 */
    public List<EvalCase> listCases(Long kbId, Long userId) { knowledgeBaseService.checkManageAccess(kbId, userId); return evalMapper.listCases(kbId, userId); }
    /** 新增评测问题 */
    public EvalCase addCase(EvalCase item, Long userId) { knowledgeBaseService.checkManageAccess(item.getKbId(), userId); item.setCreatedBy(userId); if (item.getQuestion() == null || item.getQuestion().isBlank()) throw new com.rag.common.BusinessException(400, "评测问题不能为空"); evalMapper.insertCase(item); return item; }
    /** 删除评测问题 */
    public void deleteCase(Long id, Long userId) { if (evalMapper.deleteCase(id, userId) == 0) throw new com.rag.common.BusinessException(404, "评测问题不存在"); }
    /** 查询评测历史 */
    public List<EvalRun> listRuns(Long kbId, Long userId) { knowledgeBaseService.checkManageAccess(kbId, userId); return evalMapper.listRuns(kbId, userId); }
    /** 执行评测，统计召回命中率和引用期望命中率 */
    public EvalRun run(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId); List<EvalCase> cases = evalMapper.listCases(kbId, userId); EvalRun run = new EvalRun(); run.setKbId(kbId); run.setCreatedBy(userId); run.setRunStatus("SUCCESS"); run.setTotalCount(cases.size()); int hits=0, citationHits=0; long totalLatency=0;
        for (EvalCase item : cases) { long start=System.currentTimeMillis(); List<Map<String,Object>> results=chatService.recallTest(item.getQuestion(),kbId); totalLatency += System.currentTimeMillis()-start; if (!results.isEmpty()) hits++; String expected=item.getExpectedSources(); if(expected!=null&&!expected.isBlank()&&results.stream().anyMatch(result->String.valueOf(result.get("fileName")).contains(expected))) citationHits++; }
        run.setHitCount(hits); run.setCitationHitCount(citationHits); run.setGroundedCount(hits); run.setAvgLatencyMs(cases.isEmpty()?0:totalLatency/cases.size()); run.setTotalTokens(0); try { run.setParameterSnapshot(objectMapper.writeValueAsString(Map.of("mode","retrieval","caseCount",cases.size()))); } catch(Exception ignored) {} evalMapper.insertRun(run); return run;
    }
}
