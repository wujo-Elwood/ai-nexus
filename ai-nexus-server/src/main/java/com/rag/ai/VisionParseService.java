package com.rag.ai;

import com.rag.entity.ModelProvider;
import com.rag.service.ModelProviderService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 视觉解析服务：把图片内容（扫描页、图表页、内嵌图、独立图片文件）交给视觉大模型转成 Markdown
 * 模型供应商优先取视觉标记供应商，未设置时回落到聊天激活供应商，与生图供应商的选择模式保持一致
 */
@Slf4j
@Service
public class VisionParseService {

    /** 视觉解析提示词：一次调用同时完成文字提取、表格还原和图表描述 */
    private static final String VISION_PROMPT = """
            你是文档数字化助手。请把这张文档页面图片完整转成 Markdown：
            1. 提取页面上的全部文字，保持原有标题、段落和阅读顺序；
            2. 表格必须还原为 Markdown 表格，保留行列结构；
            3. 图表、流程图、示意图用一段结构化文字描述其内容、结构和数据要点；
            4. 只输出 Markdown 内容本身，不要输出任何解释、开场白或多余说明。
            """;

    @Autowired
    private ModelProviderService modelProviderService;

    /** 页面渲染分辨率，144 DPI 在清晰度和视觉 token 成本之间取平衡 */
    @Value("${file.vision.dpi:144}")
    private int renderDpi;

    /** 渲染后图片的最长边像素，超出时等比缩小，控制视觉 token 消耗 */
    @Value("${file.vision.max-image-edge:2000}")
    private int maxImageEdge;

    /** 单次视觉调用超时时间（秒） */
    @Value("${file.vision.timeout-seconds:180}")
    private int visionTimeoutSeconds;

    /**
     * 调用视觉模型解析一张图片，返回 Markdown 文本
     *
     * @param imageBytes 图片字节内容
     * @param mimeType   图片 MIME 类型
     * @return 模型输出的 Markdown
     */
    public String parseImage(byte[] imageBytes, String mimeType) {
        // 第1步：取视觉供应商并构建 OpenAI 兼容同步模型
        ModelProvider provider = modelProviderService.getActiveForVision();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(normalizeOpenAiBaseUrl(resolveVisionBaseUrl(provider)))
                .apiKey(resolveVisionApiKey(provider))
                .modelName(resolveVisionModel(provider))
                .temperature(0.1)
                .timeout(Duration.ofSeconds(visionTimeoutSeconds))
                .build();
        // 第2步：构造多模态消息，与视觉智能体已验证的调用方式一致
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        UserMessage message = new UserMessage(List.of(
                TextContent.from(VISION_PROMPT),
                ImageContent.from(base64, mimeType)));
        // 第3步：同步调用并提取文本
        Response<AiMessage> response = model.generate(List.of(message));
        String text = response.content().text();
        return text == null ? "" : text.trim();
    }

    /**
     * 把 PDF 指定页渲染成 JPEG 字节（白底、限定最长边），供视觉模型识别
     *
     * @param document  已打开的 PDF 文档
     * @param pageIndex 页码，从 0 开始
     * @return JPEG 图片字节
     */
    public byte[] renderPageToJpeg(PDDocument document, int pageIndex) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);
        BufferedImage resized = limitLongEdge(image, maxImageEdge);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(resized, "jpg", out);
        return out.toByteArray();
    }

    /**
     * 查询当前视觉解析使用的模型名，用于解析溯源展示；未配置供应商时返回 null
     */
    public String describeVisionModel() {
        try {
            ModelProvider provider = modelProviderService.getActiveForVision();
            String modelName = resolveVisionModel(provider);
            return provider.getName() + " / " + modelName;
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染结果最长边超过限制时等比缩小，参数与前端图片压缩保持一致 */
    private BufferedImage limitLongEdge(BufferedImage source, int maxEdge) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= maxEdge) {
            return source;
        }
        double scale = (double) maxEdge / longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * (float) scale));
        int height = Math.max(1, Math.round(source.getHeight() * (float) scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    /** 视觉 API 地址：未单独配置时沿用普通 API 地址 */
    private String resolveVisionBaseUrl(ModelProvider provider) {
        if (provider.getVisionBaseUrl() != null && !provider.getVisionBaseUrl().isBlank()) {
            return provider.getVisionBaseUrl().trim();
        }
        return provider.getBaseUrl();
    }

    /** 视觉 API 密钥：未单独配置时沿用普通 API 密钥 */
    private String resolveVisionApiKey(ModelProvider provider) {
        if (provider.getVisionApiKey() != null && !provider.getVisionApiKey().isBlank()) {
            return provider.getVisionApiKey().trim();
        }
        return provider.getApiKey();
    }

    /** 视觉模型名称：未单独配置时沿用普通模型名称 */
    private String resolveVisionModel(ModelProvider provider) {
        if (provider.getVisionModel() != null && !provider.getVisionModel().isBlank()) {
            return provider.getVisionModel().trim();
        }
        return provider.getModel();
    }

    /** 规范化 OpenAI 兼容接口地址，规则与聊天服务保持一致 */
    private String normalizeOpenAiBaseUrl(String rawBaseUrl) {
        String baseUrl = rawBaseUrl.replaceAll("/+$", "");
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl;
        }
        if (!baseUrl.matches(".*/v\\d+(?:/.*)?$")) {
            return baseUrl + "/v1";
        }
        return baseUrl;
    }
}
