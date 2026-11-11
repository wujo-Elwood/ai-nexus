package com.rag.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可信回答校验器
 * 只接受本次检索结果中真实存在的文件和文本块引用
 */
@Component
public class TrustedAnswerGuard {

    /** 模型回答中的来源标记格式 */
    private static final Pattern SOURCE_PATTERN = Pattern.compile("\\[来源:\\s*(.+?)\\s*,\\s*第(\\d+)段\\]");
    /** 知识库没有证据时的固定回答 */
    public static final String NO_EVIDENCE_MESSAGE = "知识库中没有找到足够依据，暂时无法可靠回答这个问题。";
    /** 模型没有生成有效引用时的固定回答 */
    public static final String INVALID_CITATION_MESSAGE = "模型未能生成带有效依据的回答，请调整问题后重试。";

    /**
     * 校验模型回答中的知识库引用
     *
     * @param answer 模型回答文本
     * @param context 本次检索上下文
     * @return 可信回答结果
     */
    public TrustedAnswerResult validate(String answer, KnowledgeContext context) {
        if (context == null || !context.hasContext()) {
            return new TrustedAnswerResult(NO_EVIDENCE_MESSAGE, false, "NO_EVIDENCE", List.of());
        }
        String safeAnswer = answer == null ? "" : answer.trim();
        Matcher matcher = SOURCE_PATTERN.matcher(safeAnswer);
        List<KnowledgeCitation> validCitations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean hasCitation = false;
        while (matcher.find()) {
            hasCitation = true;
            String fileName = matcher.group(1).trim();
            int chunkIndex = Integer.parseInt(matcher.group(2)) - 1;
            KnowledgeCitation citation = findCitation(context.getCitations(), fileName, chunkIndex);
            if (citation == null) {
                return new TrustedAnswerResult(INVALID_CITATION_MESSAGE, false, "INVALID_CITATION", List.of());
            }
            String key = fileName + "#" + chunkIndex;
            if (seen.add(key)) {
                validCitations.add(citation);
            }
        }
        if (!hasCitation || validCitations.isEmpty()) {
            return new TrustedAnswerResult(INVALID_CITATION_MESSAGE, false, "INVALID_CITATION", List.of());
        }
        return new TrustedAnswerResult(safeAnswer, true, "GROUNDED", validCitations);
    }

    /**
     * 按文件名和文本块序号匹配本次检索引用
     */
    private KnowledgeCitation findCitation(List<KnowledgeCitation> citations,
                                           String fileName, int chunkIndex) {
        for (KnowledgeCitation citation : citations) {
            if (fileName.equals(citation.getFileName()) && Integer.valueOf(chunkIndex).equals(citation.getChunkIndex())) {
                return citation;
            }
        }
        return null;
    }
}
