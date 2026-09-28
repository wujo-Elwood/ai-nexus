package com.rag.ai;

import com.rag.entity.AnswerQuality;
import com.rag.mapper.AnswerQualityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 聊天回答质量事实写入测试。 */
class ChatServiceAnswerQualityTest {

    /** 知识库回答应保存问题、指标、拒答原因和回答消息编号。 */
    @Test
    void shouldPersistAnswerQualityFactForKnowledgeAnswer() throws Exception {
        AnswerQualityMapper mapper = mock(AnswerQualityMapper.class);
        ChatService service = new ChatService(null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(service, "answerQualityMapper", mapper);

        Method method = ChatService.class.getDeclaredMethod("recordAnswerQuality", Long.class, Long.class,
                Long.class, String.class, Integer.class, Integer.class, boolean.class, boolean.class, String.class);
        method.setAccessible(true);
        method.invoke(service, 99L, 100L, 3L, "住房补贴怎么申请", 0, 0, false, true, "NO_EVIDENCE");

        var captor = org.mockito.ArgumentCaptor.forClass(AnswerQuality.class);
        verify(mapper).insert(captor.capture());
        AnswerQuality quality = captor.getValue();
        assertEquals(99L, quality.getAnswerMessageId());
        assertEquals(3L, quality.getKbId());
        assertEquals("NO_EVIDENCE", quality.getReason());
        assertEquals(true, quality.getRefusal());
    }
}
