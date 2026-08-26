package com.rag.eval;

import lombok.Data;
import java.time.LocalDateTime;

/** RAG 评测问题实体。 */
@Data
public class EvalCase { private Long id; private Long kbId; private String question; private String expectedAnswer; private String expectedSources; private Integer enabled; private Long createdBy; private LocalDateTime createTime; }
