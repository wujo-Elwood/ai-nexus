package com.rag.rag;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索结果缓存
 * 对相同或相似问题的检索结果做内存缓存，降低重复查询的延迟和成本
 * 使用 LRU 策略，最多缓存 200 条，TTL 5 分钟
 */
@Component
public class RetrievalCache {

    /** 缓存最大条数 */
    private static final int MAX_SIZE = 200;
    /** 缓存过期时间（毫秒）：5 分钟 */
    private static final long TTL_MS = 5 * 60 * 1000;

    /** LRU 缓存：key = kbId + ":" + 问题文本的 hash，value = CachedEntry */
    private final Map<String, CachedEntry> cache = new LinkedHashMap<>(MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
            return size() > MAX_SIZE;
        }
    };

    /**
     * 查询缓存
     * @return 缓存的 chunk ID 列表，未命中或过期时返回 null
     */
    public synchronized List<Long> get(Long kbId, String query) {
        String key = buildKey(kbId, query);
        CachedEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        // 检查是否过期
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cache.remove(key);
            return null;
        }
        return entry.chunkIds;
    }

    /**
     * 写入缓存
     */
    public synchronized void put(Long kbId, String query, List<Long> chunkIds) {
        String key = buildKey(kbId, query);
        cache.put(key, new CachedEntry(chunkIds, System.currentTimeMillis()));
    }

    /**
     * 知识库文件变更后清除该库全部缓存
     * 文件删除、重新处理或版本回滚后，缓存中保留的 chunk ID 可能已失效，
     * 必须主动清除，避免 5 分钟 TTL 内检索到已删除或非当前版本的内容
     */
    public synchronized void invalidateKb(Long kbId) {
        if (kbId == null) {
            return;
        }
        cache.keySet().removeIf(key -> key.startsWith(kbId + ":"));
    }

    /**
     * 清空全部知识库检索缓存
     */
    public synchronized void invalidateAll() {
        cache.clear();
    }

    private String buildKey(Long kbId, String query) {
        return kbId + ":" + query.hashCode();
    }

    private static class CachedEntry {
        final List<Long> chunkIds;
        final long timestamp;
        CachedEntry(List<Long> chunkIds, long timestamp) {
            this.chunkIds = chunkIds;
            this.timestamp = timestamp;
        }
    }
}
