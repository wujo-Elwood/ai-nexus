package com.rag.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 检索结果缓存测试
 * 验证缓存命中和知识库级主动失效行为
 */
class RetrievalCacheTest {

    /**
     * 测试写入后相同问题能够命中缓存
     */
    @Test
    void putThenGetShouldReturnChunkIds() {
        RetrievalCache cache = new RetrievalCache();
        List<Long> chunkIds = Arrays.asList(1L, 2L, 3L);

        cache.put(3L, "什么是退货政策", chunkIds);

        assertEquals(chunkIds, cache.get(3L, "什么是退货政策"));
    }

    /**
     * 测试失效知识库后其缓存条目全部移除，其他知识库不受影响
     */
    @Test
    void invalidateKbShouldRemoveOnlyTargetKbEntries() {
        RetrievalCache cache = new RetrievalCache();
        List<Long> kb1Chunks = Arrays.asList(1L, 2L);
        List<Long> kb2Chunks = Arrays.asList(9L);
        cache.put(1L, "问题A", kb1Chunks);
        cache.put(1L, "问题B", kb1Chunks);
        cache.put(2L, "问题C", kb2Chunks);

        cache.invalidateKb(1L);

        assertNull(cache.get(1L, "问题A"));
        assertNull(cache.get(1L, "问题B"));
        assertEquals(kb2Chunks, cache.get(2L, "问题C"));
    }

    /**
     * 测试失效未缓存的或不存在的知识库时不影响现有条目
     */
    @Test
    void invalidateUnknownKbShouldKeepExistingEntries() {
        RetrievalCache cache = new RetrievalCache();
        List<Long> chunkIds = Arrays.asList(5L);
        cache.put(2L, "问题C", chunkIds);

        cache.invalidateKb(99L);
        cache.invalidateKb(null);

        assertEquals(chunkIds, cache.get(2L, "问题C"));
    }
}
