package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.mapper.ModelProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型供应商视觉能力单元测试
 * 视觉供应商的激活权限、激活联动和视觉优先回落规则
 */
class ModelProviderVisionTest {

    private ModelProviderMapper modelProviderMapper;
    private ModelProviderService modelProviderService;

    @BeforeEach
    void setUp() {
        modelProviderMapper = mock(ModelProviderMapper.class);
        modelProviderService = new ModelProviderService();
        ReflectionTestUtils.setField(modelProviderService, "modelProviderMapper", modelProviderMapper);
    }

    @Test
    void activateVision_deactivatesOthersThenActivates() {
        when(modelProviderMapper.findById(5L)).thenReturn(provider(5L, 1L));

        modelProviderService.activateVision(5L, 1L, false);

        verify(modelProviderMapper).deactivateAllVisions();
        verify(modelProviderMapper).activateVision(5L);
    }

    @Test
    void activateVision_rejectsNonOwner() {
        when(modelProviderMapper.findById(5L)).thenReturn(provider(5L, 2L));

        assertThrows(BusinessException.class, () -> modelProviderService.activateVision(5L, 1L, false));
        verify(modelProviderMapper, never()).deactivateAllVisions();
        verify(modelProviderMapper, never()).activateVision(any());
    }

    @Test
    void getActiveForVision_prefersVisionActiveProvider() {
        ModelProvider visionProvider = provider(7L, 1L);
        when(modelProviderMapper.findVisionActive()).thenReturn(visionProvider);

        assertEquals(visionProvider, modelProviderService.getActiveForVision());
        verify(modelProviderMapper, never()).findActive();
    }

    @Test
    void getActiveForVision_fallsBackToChatActiveProvider() {
        ModelProvider chatProvider = provider(3L, 1L);
        when(modelProviderMapper.findVisionActive()).thenReturn(null);
        when(modelProviderMapper.findActive()).thenReturn(chatProvider);

        assertEquals(chatProvider, modelProviderService.getActiveForVision());
    }

    @Test
    void getActiveForVision_throwsWhenNothingConfigured() {
        when(modelProviderMapper.findVisionActive()).thenReturn(null);
        when(modelProviderMapper.findActive()).thenReturn(null);

        assertThrows(BusinessException.class, () -> modelProviderService.getActiveForVision());
    }

    private ModelProvider provider(Long id, Long createdBy) {
        ModelProvider provider = new ModelProvider();
        provider.setId(id);
        provider.setName("供应商" + id);
        provider.setBaseUrl("https://api.example.com");
        provider.setApiKey("sk-test");
        provider.setModel("test-model");
        provider.setCreatedBy(createdBy);
        return provider;
    }
}
