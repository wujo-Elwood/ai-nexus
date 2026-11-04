package com.rag.ai;

import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 视觉解析服务单元测试：只测不依赖外部服务的部分
 * 模型选择回退规则和 PDF 页渲染的尺寸限制；真实视觉调用不在单元测试范围
 */
class VisionParseServiceTest {

    private ModelProviderService modelProviderService;
    private VisionParseService visionParseService;

    @BeforeEach
    void setUp() {
        modelProviderService = mock(ModelProviderService.class);
        visionParseService = new VisionParseService();
        ReflectionTestUtils.setField(visionParseService, "modelProviderService", modelProviderService);
        ReflectionTestUtils.setField(visionParseService, "renderDpi", 72);
        ReflectionTestUtils.setField(visionParseService, "maxImageEdge", 100);
        ReflectionTestUtils.setField(visionParseService, "visionTimeoutSeconds", 30);
    }

    @Test
    void describeVisionModel_prefersVisionModel() {
        when(modelProviderService.getActiveForVision()).thenReturn(provider("OpenAI", "gpt-4o", "qwen-vl-max"));

        assertEquals("OpenAI / qwen-vl-max", visionParseService.describeVisionModel());
    }

    @Test
    void describeVisionModel_fallsBackToChatModel() {
        when(modelProviderService.getActiveForVision()).thenReturn(provider("OpenAI", "gpt-4o", null));

        assertEquals("OpenAI / gpt-4o", visionParseService.describeVisionModel());
    }

    @Test
    void describeVisionModel_noProviderReturnsNull() {
        when(modelProviderService.getActiveForVision())
                .thenThrow(new BusinessException(404, "没有激活的模型供应商"));

        assertNull(visionParseService.describeVisionModel());
    }

    @Test
    void renderPageToJpeg_limitsLongEdge() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            byte[] jpeg = visionParseService.renderPageToJpeg(document, 0);

            BufferedImage rendered = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertNotNull(rendered, "渲染结果应可被 ImageIO 读回");
            assertTrue(Math.max(rendered.getWidth(), rendered.getHeight()) <= 100,
                    "最长边不应超过 maxImageEdge 限制");
        }
    }

    private ModelProvider provider(String name, String model, String visionModel) {
        ModelProvider provider = new ModelProvider();
        provider.setName(name);
        provider.setModel(model);
        provider.setVisionModel(visionModel);
        return provider;
    }
}
