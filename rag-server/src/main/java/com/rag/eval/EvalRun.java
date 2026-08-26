package com.rag.eval;

import lombok.Data;
import java.time.LocalDateTime;

/** RAG 评测运行摘要。 */
@Data
public class EvalRun { private Long id; private Long kbId; private String runStatus; private Integer totalCount; private Integer hitCount; private Integer citationHitCount; private Integer groundedCount; private Long avgLatencyMs; private Integer totalTokens; private String parameterSnapshot; private String errorMessage; private Long createdBy; private LocalDateTime createTime; private LocalDateTime finishedAt; }
