package com.rag.rag;

import com.rag.entity.KbChunk;
import com.rag.mapper.ChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词检索服务
 * 在 MySQL 中用 LIKE 模糊匹配检索包含关键词的文本块
 * 作为向量检索的补充，提升精确关键词场景的召回率
 */
@Slf4j
@Component
public class KeywordSearchService {

    @Autowired
    private ChunkMapper chunkMapper;

    /**
     * 在指定知识库中检索包含关键词的文本块
     *
     * @param query 用户查询文本
     * @param kbId  知识库ID
     * @param limit 最大返回数量
     * @return 命中的 chunk 列表
     */
    public List<KbChunk> search(String query, Long kbId, int limit) {
        if (query == null || query.isBlank() || kbId == null) {
            return Collections.emptyList();
        }
        // 第1步：提取查询中的关键词
        Set<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return Collections.emptyList();
        }
        // 第2步：分别搜索多个关键词，再按 chunk 编号合并去重
        try {
            Map<Long, KbChunk> uniqueChunks = new LinkedHashMap<>();
            for (String keyword : keywords) {
                List<KbChunk> matches = chunkMapper.searchByKeyword(kbId, keyword, limit);
                for (KbChunk chunk : matches) {
                    uniqueChunks.putIfAbsent(chunk.getId(), chunk);
                    if (uniqueChunks.size() >= limit) {
                        return List.copyOf(uniqueChunks.values());
                    }
                }
            }
            return List.copyOf(uniqueChunks.values());
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从查询文本中提取关键词
     * 过滤掉常见停用词，保留有意义的词
     */
    private Set<String> extractKeywords(String query) {
        // 第1步：按分隔符拆分
        String[] tokens = query.split("[\\s,;.!?，。；！？、\\-]+");
        Set<String> keywords = new HashSet<>();
        // 第2步：常见停用词列表
        Set<String> stopWords = Set.of(
                "的", "了", "是", "在", "我", "有", "和", "就", "不", "人",
                "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
                "你", "会", "着", "没有", "看", "好", "自己", "这", "他", "吗",
                "什么", "那", "请", "帮", "可以", "怎么", "如何", "哪些", "哪个",
                "the", "a", "an", "is", "are", "was", "were", "be", "been",
                "what", "how", "which", "where", "when", "who"
        );
        for (String token : tokens) {
            // 第3步：过滤停用词和过短的词
            if (token.length() >= 2 && !stopWords.contains(token.toLowerCase())) {
                keywords.add(token);
            }
        }
        // 第4步：如果全部被过滤掉，返回原始查询的前4个字
        if (keywords.isEmpty() && query.length() >= 2) {
            keywords.add(query.substring(0, Math.min(4, query.length())));
        }
        return keywords;
    }
}
