package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库服务测试
 * 验证知识库编辑和访问权限的核心行为
 */
class KnowledgeBaseServiceTest {

    /**
     * 测试知识库编辑只能由创建者执行
     */
    @Test
    void updateShouldAllowOwnerAndRejectOtherUser() {
        // 第1步：准备一个属于用户1的知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase existing = buildKnowledgeBase(10L, 1L, "旧名称", "PRIVATE");
        when(mapper.findById(10L)).thenReturn(existing);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：创建者修改知识库信息
        KnowledgeBase updated = service.update(10L, "新名称", "新描述", "PUBLIC", 1L);

        // 第3步：确认修改内容已经保存
        assertEquals("新名称", updated.getName());
        assertEquals("新描述", updated.getDescription());
        assertEquals("PUBLIC", updated.getVisibility());
        verify(mapper).update(existing);

        // 第4步：其他用户修改时必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(10L, "越权名称", "", "PRIVATE", 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试公开知识库允许其他用户访问
     */
    @Test
    void checkAccessShouldAllowPublicKnowledgeBase() {
        // 第1步：准备公开知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "公开库", "PUBLIC"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：其他用户访问公开知识库不应抛出异常
        service.checkAccess(10L, 2L);
    }

    /**
     * 测试私有知识库拒绝其他用户访问
     */
    @Test
    void checkAccessShouldRejectPrivateKnowledgeBase() {
        // 第1步：准备私有知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：其他用户访问私有知识库必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试公开知识库也只允许创建者管理文件
     */
    @Test
    void checkManageAccessShouldRejectOtherUserForPublicKnowledgeBase() {
        // 第1步：准备属于用户1的公开知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "公开库", "PUBLIC"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：其他用户尝试管理公开知识库时必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkManageAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 构造测试知识库
     */
    private KnowledgeBase buildKnowledgeBase(Long id, Long ownerId, String name, String visibility) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCreateUser(ownerId);
        kb.setName(name);
        kb.setVisibility(visibility);
        return kb;
    }
}
