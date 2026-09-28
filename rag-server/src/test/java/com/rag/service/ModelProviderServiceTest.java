package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.mapper.ModelProviderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型供应商归属隔离测试
 * 验证普通用户只能看到和操作自己创建的供应商，管理员可以看到并操作全部
 */
class ModelProviderServiceTest {

    /** 普通用户列表只包含自己创建的供应商。 */
    @Test
    void normalUserShouldOnlySeeOwnProviders() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findByCreatedBy(7L)).thenReturn(List.of(provider(1L, 7L, "sk-mine")));
        when(mapper.findActive()).thenReturn(provider(2L, 8L, "sk-other"));
        ModelProviderService service = createService(mapper);

        List<ModelProvider> visible = service.listVisible(7L, false);

        assertEquals(2, visible.size());
        assertEquals("sk-mine", visible.get(0).getApiKey());
        // 激活的他人供应商必须可见，否则聊天页无法展示当前模型，但密钥不能下发
        assertEquals(2L, visible.get(1).getId().longValue());
        assertNull(visible.get(1).getApiKey());
        assertNull(visible.get(1).getCreatedByName());
        verify(mapper, never()).findAll();
    }

    /** 管理员列表返回全部供应商及密钥。 */
    @Test
    void adminShouldSeeAllProvidersWithKeys() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findAll()).thenReturn(List.of(provider(1L, 7L, "sk-mine"), provider(2L, 9L, "sk-other")));
        ModelProviderService service = createService(mapper);

        List<ModelProvider> visible = service.listVisible(9L, true);

        assertEquals(2, visible.size());
        assertEquals("sk-other", visible.get(1).getApiKey());
        verify(mapper, never()).findByCreatedBy(any());
    }

    /** 激活供应商属于自己时不应重复出现在列表里。 */
    @Test
    void ownActiveProviderShouldNotBeDuplicated() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findByCreatedBy(7L)).thenReturn(List.of(provider(1L, 7L, "sk-mine")));
        when(mapper.findActive()).thenReturn(provider(1L, 7L, "sk-mine"));
        ModelProviderService service = createService(mapper);

        assertEquals(1, service.listVisible(7L, false).size());
    }

    /** 普通用户不能修改他人供应商。 */
    @Test
    void normalUserShouldNotUpdateForeignProvider() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findById(2L)).thenReturn(provider(2L, 8L, "sk-other"));
        ModelProviderService service = createService(mapper);

        assertForbidden(() -> service.update(2L, provider(2L, 7L, "sk-hijack"), 7L, false));
        verify(mapper, never()).update(any());
    }

    /** 普通用户不能删除或激活他人供应商。 */
    @Test
    void normalUserShouldNotDeleteOrActivateForeignProvider() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findById(2L)).thenReturn(provider(2L, 8L, "sk-other"));
        ModelProviderService service = createService(mapper);

        assertForbidden(() -> service.delete(2L, 7L, false));
        assertForbidden(() -> service.activate(2L, 7L, false));
        verify(mapper, never()).deleteById(any());
        verify(mapper, never()).deactivateAll();
    }

    /** 归属为空的历史供应商只允许管理员处理。 */
    @Test
    void legacyProviderWithoutOwnerShouldBeAdminOnly() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findById(3L)).thenReturn(provider(3L, null, "sk-legacy"));
        ModelProviderService service = createService(mapper);

        assertForbidden(() -> service.activate(3L, 7L, false));
        service.activate(3L, 7L, true);
        verify(mapper).activate(3L);
    }

    /** 激活接口对普通用户下发他人供应商时必须脱敏。 */
    @Test
    void activeEndpointShouldMaskForeignProviderKey() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        when(mapper.findActive()).thenReturn(provider(2L, 8L, "sk-other"));
        ModelProviderService service = createService(mapper);

        assertNull(service.getActiveForViewer(7L, false).getApiKey());
        assertEquals("sk-other", service.getActiveForViewer(8L, false).getApiKey());
        assertEquals("sk-other", service.getActiveForViewer(7L, true).getApiKey());
        // 内部模型调用链路始终拿到完整配置，脱敏不得就地修改实体
        assertEquals("sk-other", service.getActive().getApiKey());
    }

    /** 新建供应商时创建人固定为当前登录用户。 */
    @Test
    void createShouldRecordCurrentUserAsOwner() {
        ModelProviderMapper mapper = mock(ModelProviderMapper.class);
        ModelProviderService service = createService(mapper);
        ModelProvider form = provider(null, null, "sk-new");

        ModelProvider saved = service.create(form, 7L);

        assertEquals(7L, saved.getCreatedBy().longValue());
        assertEquals(0, saved.getIsActive().intValue());
        assertTrue(saved.getName().length() > 0);
    }

    /** 断言归属校验返回 403。 */
    private void assertForbidden(Runnable action) {
        BusinessException error = assertThrows(BusinessException.class, action::run);
        assertEquals(403, error.getCode());
    }

    /** 构造被测服务并注入 mock 映射器。 */
    private ModelProviderService createService(ModelProviderMapper mapper) {
        ModelProviderService service = new ModelProviderService();
        ReflectionTestUtils.setField(service, "modelProviderMapper", mapper);
        return service;
    }

    /** 构造一个供应商测试数据。 */
    private ModelProvider provider(Long id, Long createdBy, String apiKey) {
        ModelProvider provider = new ModelProvider();
        provider.setId(id);
        provider.setName("provider-" + id);
        provider.setBaseUrl("https://example.com/v1");
        provider.setApiKey(apiKey);
        provider.setModel("gpt-test");
        provider.setImageApiKey(apiKey);
        provider.setIsActive(0);
        provider.setCreatedBy(createdBy);
        provider.setCreatedByName(createdBy == null ? null : "user-" + createdBy);
        return provider;
    }
}
