package com.rag.ai;

import com.rag.entity.KbChunk;
import com.rag.kb.KbConflict;
import com.rag.rag.KnowledgeContext;
import com.rag.rag.KnowledgeCitation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口径冲突接入问答链路的单元测试：
 * 冲突片段应在引用上带标记、在 prompt 上下文里补一句差异说明，且文案要能说清"跟谁冲突、差在哪"。
 */
class ChatServiceCitationConflictTest {

    /** 冲突说明：当前片段在 A 侧时，应该指向 B 侧的文件与段号 */
    @Test
    void conflictNoteShouldPointToTheOtherSide() {
        KbConflict conflict = buildConflict();

        String note = ChatService.buildConflictNote(conflict, 11L);

        assertNotNull(note);
        assertTrue(note.contains("员工手册-2024.txt"), "应指出对方文件：" + note);
        assertTrue(note.contains("第2段"), "应指出对方段号（0 基转 1 基）：" + note);
        assertTrue(note.contains("「天」"), "应带出冲突口径：" + note);
        assertTrue(note.contains("5 对 10"), "应带出两个取值：" + note);
    }

    /** 站在 B 侧时，指向的应该是 A 侧 */
    @Test
    void conflictNoteShouldSwapSidesForSideB() {
        String note = ChatService.buildConflictNote(buildConflict(), 22L);

        assertNotNull(note);
        assertTrue(note.contains("员工手册-2023.txt"), "应指出对方文件：" + note);
        assertTrue(note.contains("第1段"), "应指出对方段号：" + note);
    }

    @Test
    void conflictNoteShouldBeNullWhenNoConflict() {
        assertNull(ChatService.buildConflictNote(null, 11L));
    }

    /** 命中冲突片段时，引用对象上要带 conflict 标记与说明 */
    @Test
    void citationShouldCarryConflictFlag() throws Exception {
        ChatService service = new ChatService(null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        Method method = ChatService.class.getDeclaredMethod("buildKnowledgeContext",
                List.class, String.class, Map.class, Set.class, Map.class);
        method.setAccessible(true);

        KbChunk chunk = new KbChunk();
        chunk.setId(11L);
        chunk.setFileId(1L);
        chunk.setFileName("员工手册-2023.txt");
        chunk.setChunkIndex(0);
        chunk.setSourceInfo("员工手册-2023.txt, 第1段");
        chunk.setContent("年假为 5 天，需提前 3 个工作日申请。");

        Map<Long, KbConflict> conflicts = new HashMap<>();
        conflicts.put(11L, buildConflict());

        KnowledgeContext context = (KnowledgeContext) method.invoke(service,
                List.of(chunk), "年假多少天", Collections.emptyMap(), Collections.emptySet(), conflicts);

        KnowledgeCitation citation = context.getCitations().get(0);
        assertEquals(Boolean.TRUE, citation.getConflict());
        assertNotNull(citation.getConflictNote());
        // 上下文里必须带上提示句，否则模型仍会把其中一段当作唯一答案
        assertTrue(context.getContextWithSources().contains("[注意: 本段与其他文档存在口径冲突"),
                "上下文应包含冲突提示：" + context.getContextWithSources());
        assertTrue(context.getContextWithSources().contains("不要只给出其中一个数值"));
    }

    /** 没有冲突时引用不应被误标记 */
    @Test
    void citationShouldStayCleanWithoutConflict() throws Exception {
        ChatService service = new ChatService(null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        Method method = ChatService.class.getDeclaredMethod("buildKnowledgeContext",
                List.class, String.class, Map.class, Set.class, Map.class);
        method.setAccessible(true);

        KbChunk chunk = new KbChunk();
        chunk.setId(11L);
        chunk.setFileId(1L);
        chunk.setFileName("员工手册-2023.txt");
        chunk.setChunkIndex(0);
        chunk.setSourceInfo("员工手册-2023.txt, 第1段");
        chunk.setContent("年假为 5 天。");

        KnowledgeContext context = (KnowledgeContext) method.invoke(service,
                List.of(chunk), "年假多少天", Collections.emptyMap(), Collections.emptySet(),
                Collections.emptyMap());

        KnowledgeCitation citation = context.getCitations().get(0);
        assertNull(citation.getConflict());
        assertFalse(context.getContextWithSources().contains("口径冲突"));
    }

    /** 构造一条 A/B 两侧数值不一致的冲突记录 */
    private KbConflict buildConflict() {
        KbConflict conflict = new KbConflict();
        conflict.setId(1L);
        conflict.setKbId(9L);
        conflict.setChunkAId(11L);
        conflict.setFileAId(1L);
        conflict.setFileAName("员工手册-2023.txt");
        conflict.setChunkAIndex(0);
        conflict.setChunkBId(22L);
        conflict.setFileBId(2L);
        conflict.setFileBName("员工手册-2024.txt");
        conflict.setChunkBIndex(1);
        conflict.setConflictUnit("天");
        conflict.setValueA("5");
        conflict.setValueB("10");
        conflict.setSimilarity(0.72f);
        conflict.setConflictStatus("PENDING");
        return conflict;
    }
}
