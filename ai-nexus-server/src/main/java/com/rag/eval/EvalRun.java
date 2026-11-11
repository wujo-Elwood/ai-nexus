package com.rag.eval;

import lombok.Data;
import java.time.LocalDateTime;

/** RAG 评测运行摘要。 */
@Data
public class EvalRun {
    private Long id; private Long kbId; private String runStatus; private Integer totalCount;
    private Integer hitCount; private Integer citationHitCount; private Integer groundedCount;
    /** 平均倒数排名（MRR）：命中越靠前越接近 1，未命中记 0 */
    private Double mrr;
    private Long avgLatencyMs; private Integer totalTokens; private String parameterSnapshot;
    private String errorMessage; private Long createdBy; private java.time.LocalDateTime createTime;
    private java.time.LocalDateTime finishedAt;
}
