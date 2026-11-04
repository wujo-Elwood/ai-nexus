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
 * 优先使用 kb_chunk 的全文索引（MATCH ... AGAINST，中文走 ngram 分词）召回并按相关度排序；
 * 索引不存在或 MySQL 不支持时自动回退到原来的 LIKE 模糊匹配，保证未执行升级脚本的环境也能用
 */
@Slf4j
@Component
public class KeywordSearchService {

    /** ngram 分词器按 2 字切分，长度不足 2 的词检索不到，直接在构造查询时跳过 */
    private static final int MIN_FULLTEXT_WORD_LENGTH = 2;

    /** 布尔模式下的操作符，出现在关键词里会破坏查询语义，统一替换成空格 */
    private static final String BOOLEAN_OPERATORS = "[+\\-><()~*\"@]";

    @Autowired
    private ChunkMapper chunkMapper;

    /** 全文索引是否可用：首次失败后置为 false，避免每条查询都触发一次异常 */
    private volatile boolean fulltextAvailable = true;

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
        // 第2步：优先走全文索引，索引不可用时回退 LIKE
        List<KbChunk> fulltextResult = searchByFulltext(keywords, kbId, limit);
        if (fulltextResult != null) {
            return fulltextResult;
        }
        return searchByLike(keywords, kbId, limit);
    }

    /**
     * 全文索引检索：多关键词按 OR 语义匹配，相关度高的排前面
     *
     * @return null 表示全文索引不可用，调用方需要回退 LIKE
     */
    private List<KbChunk> searchByFulltext(Set<String> keywords, Long kbId, int limit) {
        if (!fulltextAvailable) {
            return null;
        }
        String expression = buildBooleanQuery(keywords);
        if (expression.isBlank()) {
            return null;
        }
        try {
            List<KbChunk> matches = chunkMapper.searchByFulltext(kbId, expression, limit);
            return matches == null ? Collections.emptyList() : matches;
        } catch (Exception e) {
            fulltextAvailable = false;
            log.warn("全文索引检索不可用，已回退 LIKE 关键词检索"
                    + "（请执行 database/kb_fulltext_search_upgrade.sql 建立索引）: {}", e.getMessage());
            return null;
        }
    }

    /** LIKE 兜底检索：逐个关键词查询后按 chunk 去重合并 */
    private List<KbChunk> searchByLike(Set<String> keywords, Long kbId, int limit) {
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

    /** 把关键词拼成 MySQL 布尔模式表达式，去掉会改变语义的操作符 */
    String buildBooleanQuery(Set<String> keywords) {
        StringBuilder expression = new StringBuilder();
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String cleaned = keyword.replaceAll(BOOLEAN_OPERATORS, " ").trim();
            if (cleaned.isEmpty() || cleaned.length() < MIN_FULLTEXT_WORD_LENGTH) {
                continue;
            }
            if (expression.length() > 0) {
                expression.append(' ');
            }
            expression.append(cleaned);
        }
        return expression.toString();
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
