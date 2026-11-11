package com.rag.kb;

import com.rag.entity.KbChunk;
import com.rag.mapper.ChunkMapper;
import com.rag.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冲突检测规则测试：验证"同一话题 + 数值不一致"能被检出，
 * 并确保"数值一致 / 话题不同 / 同文件相邻段落"这些噪声不会被误报。
 */
class ConflictDetectionServiceTest {

    /** 员工年假 5 天（2023 版手册） */
    private static final String LEAVE_5_DAYS =
            "第三条 员工年假为每年 5 天，入职满一年后享受年假待遇，未满一年按比例折算。年假需提前申请，经直属上级批准后生效。";
    /** 员工年假 10 天（2024 版手册） */
    private static final String LEAVE_10_DAYS =
            "第三条 员工年假为每年 10 天，入职满一年后享受年假待遇，未满一年按比例折算。年假需提前申请，经人事部门批准后生效。";
    /** 同样话题、数值一致 */
    private static final String LEAVE_5_DAYS_OTHER =
            "第三条 员工年假为每年 5 天，入职满一年后享受年假待遇，未满一年按比例折算。年假申请需部门负责人审批。";

    @Test
    void shouldDetectNumericConflictAcrossFiles() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KbConflictMapper conflictMapper = mock(KbConflictMapper.class);
        ConflictDetectionService service = newService(chunkMapper, conflictMapper);
        when(chunkMapper.findCompletedCurrentByKbId(1L)).thenReturn(Arrays.asList(
                chunk(11L, 21L, "员工手册-2023.txt", 0, LEAVE_5_DAYS),
                chunk(12L, 22L, "员工手册-2024.txt", 0, LEAVE_10_DAYS)));

        Map<String, Object> summary = service.detect(1L, 99L);

        assertEquals(1, summary.get("conflictCount"));
        ArgumentCaptor<List<KbConflict>> captor = ArgumentCaptor.forClass(List.class);
        verify(conflictMapper).insertBatch(captor.capture());
        KbConflict conflict = captor.getValue().get(0);
        assertEquals(11L, conflict.getChunkAId());
        assertEquals(12L, conflict.getChunkBId());
        assertEquals("天", conflict.getConflictUnit());
        assertEquals("5", conflict.getValueA());
        assertEquals("10", conflict.getValueB());
        assertEquals("PENDING", conflict.getConflictStatus());
        assertEquals("员工手册-2023.txt", conflict.getFileAName());
        assertNotNull(conflict.getReason());
        assertTrue(conflict.getReason().contains("5") && conflict.getReason().contains("10"));
        assertTrue(conflict.getSimilarity() > 0.35f);
    }

    /**
     * 两侧取值集合有交集但存在差异时仍然要报冲突：
     * 同一份制度里「年假 5 天」与「报销 30 天」共用一个单位，交集不能掩盖 5 与 10 的差异。
     */
    @Test
    void shouldDetectConflictWhenValueSetsPartiallyOverlap() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KbConflictMapper conflictMapper = mock(KbConflictMapper.class);
        ConflictDetectionService service = newService(chunkMapper, conflictMapper);
        String version2023 = LEAVE_5_DAYS + "差旅报销需在费用发生后 30 天内提交，超期需部门负责人书面说明。";
        String version2024 = LEAVE_10_DAYS + "差旅报销需在费用发生后 30 天内提交，超期需部门负责人书面说明。";
        when(chunkMapper.findCompletedCurrentByKbId(1L)).thenReturn(Arrays.asList(
                chunk(11L, 21L, "员工手册-2023.txt", 0, version2023),
                chunk(12L, 22L, "员工手册-2024.txt", 0, version2024)));

        Map<String, Object> summary = service.detect(1L, 99L);

        assertEquals(1, summary.get("conflictCount"));
        ArgumentCaptor<List<KbConflict>> captor = ArgumentCaptor.forClass(List.class);
        verify(conflictMapper).insertBatch(captor.capture());
        KbConflict conflict = captor.getValue().get(0);
        assertEquals("天", conflict.getConflictUnit());
        // 只展示各自独有的取值，共有的 30 天不算差异
        assertEquals("5", conflict.getValueA());
        assertEquals("10", conflict.getValueB());
    }

    @Test
    void shouldIgnoreIdenticalValues() {        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KbConflictMapper conflictMapper = mock(KbConflictMapper.class);
        ConflictDetectionService service = newService(chunkMapper, conflictMapper);
        when(chunkMapper.findCompletedCurrentByKbId(1L)).thenReturn(Arrays.asList(
                chunk(11L, 21L, "a.txt", 0, LEAVE_5_DAYS),
                chunk(12L, 22L, "b.txt", 0, LEAVE_5_DAYS_OTHER)));

        Map<String, Object> summary = service.detect(1L, 99L);

        assertEquals(0, summary.get("conflictCount"));
        verify(conflictMapper, never()).insertBatch(anyList());
    }

    @Test
    void shouldIgnoreUnrelatedContentWithSameUnit() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KbConflictMapper conflictMapper = mock(KbConflictMapper.class);
        ConflictDetectionService service = newService(chunkMapper, conflictMapper);
        when(chunkMapper.findCompletedCurrentByKbId(1L)).thenReturn(Arrays.asList(
                chunk(11L, 21L, "a.txt", 0, "第三条 员工年假为每年 5 天，入职满一年后享受年假待遇，未满一年按比例折算。"),
                chunk(12L, 22L, "b.txt", 0, "机房温度应保持在 18 到 25 度，湿度控制在 40 到 60 之间，每日巡检两次并记录存档。")));

        Map<String, Object> summary = service.detect(1L, 99L);

        assertEquals(0, summary.get("conflictCount"));
        verify(conflictMapper, never()).insertBatch(anyList());
    }

    @Test
    void shouldSkipAdjacentChunksInSameFile() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KbConflictMapper conflictMapper = mock(KbConflictMapper.class);
        ConflictDetectionService service = newService(chunkMapper, conflictMapper);
        when(chunkMapper.findCompletedCurrentByKbId(1L)).thenReturn(Arrays.asList(
                chunk(11L, 21L, "员工手册.txt", 0, LEAVE_5_DAYS),
                chunk(12L, 21L, "员工手册.txt", 1, LEAVE_10_DAYS)));

        Map<String, Object> summary = service.detect(1L, 99L);

        assertEquals(0, summary.get("conflictCount"));
        verify(conflictMapper, never()).insertBatch(anyList());
    }

    @Test
    void shouldReturnEmptySummaryWhenKbHasNoChunks() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KbConflictMapper conflictMapper = mock(KbConflictMapper.class);
        ConflictDetectionService service = newService(chunkMapper, conflictMapper);
        when(chunkMapper.findCompletedCurrentByKbId(1L)).thenReturn(Collections.emptyList());

        Map<String, Object> summary = service.detect(1L, 99L);

        assertEquals(0, summary.get("chunkCount"));
        assertEquals(0, summary.get("conflictCount"));
        verify(conflictMapper).deleteByKb(1L);
    }

    /** 构造被测服务，权限校验用空实现（鉴权由 KnowledgeBaseService 自身测试覆盖） */
    private ConflictDetectionService newService(ChunkMapper chunkMapper, KbConflictMapper conflictMapper) {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        return new ConflictDetectionService(chunkMapper, conflictMapper, knowledgeBaseService);
    }

    /** 构造一条已完成入库的切片 */
    private KbChunk chunk(Long id, Long fileId, String fileName, int chunkIndex, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setFileId(fileId);
        chunk.setFileName(fileName);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        return chunk;
    }
}
