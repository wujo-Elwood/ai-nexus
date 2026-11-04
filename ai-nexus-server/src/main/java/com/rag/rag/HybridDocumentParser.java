package com.rag.rag;

import com.rag.ai.VisionParseService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

/**
 * 混合文档解析器：服务文件入库管道，按知识库开关路由解析方式
 * 开关关闭时行为与 DocumentParser 完全一致；开启后图片内容送视觉模型转 Markdown，
 * 纯文本内容仍走 Tika，两类内容按页序拼回统一正文再进入切片和向量化
 * 文档抽取等其他模块仍直接使用 DocumentParser，不受本类影响
 */
@Slf4j
@Component
public class HybridDocumentParser {

    /** 视觉可识别的独立图片扩展名，与上传白名单保持一致 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    /** 视觉可识别的图片 MIME 类型，用于过滤 DOCX 内嵌图 */
    private static final Set<String> VISION_IMAGE_MIMES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    /** 单张图片字节上限，超过直接跳过视觉调用，避免长时间等待后超时 */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    @Autowired
    private DocumentParser documentParser;
    @Autowired
    private VisionParseService visionParseService;

    /** 单个文件最多送视觉模型的页面或图片数，控制视觉 token 成本 */
    @Value("${file.vision.max-vision-pages:50}")
    private int maxVisionPages;

    /** 页面文字层少于此字符数视为扫描页 */
    @Value("${file.vision.min-text-chars:30}")
    private int minTextChars;

    /** 页面内图片像素面积与页面点面积之比达到该阈值视为大图页；不同扫描分辨率下约对应页面的三到五成 */
    @Value("${file.vision.image-area-ratio:0.5}")
    private double imageAreaRatio;

    /**
     * 解析结果：content 为入库正文；visionTrace 为视觉解析溯源（哪些页或图走了视觉、用了哪个模型），纯文本解析时为 null
     */
    @Data
    @AllArgsConstructor
    public static class ParseResult {
        private String content;
        private String visionTrace;
    }

    /**
     * 按知识库开关路由解析
     *
     * @param file          待解析文件
     * @param visionEnabled 知识库是否开启图片视觉解析
     * @return 解析结果（正文 + 视觉溯源）
     */
    public ParseResult parse(File file, boolean visionEnabled) throws IOException {
        // 第1步：开关关闭时与原解析行为完全一致
        if (!visionEnabled) {
            return new ParseResult(documentParser.parse(file), null);
        }
        // 第2步：按文件类型路由到对应解析策略
        String fileName = file.getName().toLowerCase();
        if (isImageFile(fileName)) {
            return parseImageFile(file, fileName);
        }
        if (fileName.endsWith(".pdf")) {
            return parsePdf(file);
        }
        if (fileName.endsWith(".docx")) {
            return parseDocx(file);
        }
        if (fileName.endsWith(".pptx")) {
            return parsePptx(file);
        }
        // 第3步：XLSX/TXT/MD/DOC/PPT 等仍走既有解析管道
        return new ParseResult(documentParser.parse(file), null);
    }

    /** 独立图片文件：整张送视觉模型，失败回退原占位符保证文件仍可入库 */
    private ParseResult parseImageFile(File file, String fileName) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > MAX_IMAGE_BYTES) {
                log.warn("图片文件超过 10MB，跳过视觉解析: {}", fileName);
                return new ParseResult("[图片引用: " + file.getName() + "]", null);
            }
            String markdown = visionParseService.parseImage(bytes, resolveImageMime(fileName));
            if (markdown.isBlank()) {
                return new ParseResult("[图片引用: " + file.getName() + "]", null);
            }
            return new ParseResult(markdown, "图片文件视觉解析，模型: " + describeVisionModel());
        } catch (Exception e) {
            // 视觉不可用时回退占位符，与关闭开关时的行为一致
            log.warn("图片文件视觉解析失败: {}", fileName, e);
            return new ParseResult("[图片引用: " + file.getName() + "]", null);
        }
    }

    /**
     * PDF 逐页路由：无文字层的扫描页和含大图的页面送视觉模型，
     * 纯文字页保留 PDFBox 提取的页面文字层，按页序拼回 Markdown
     */
    private ParseResult parsePdf(File file) throws IOException {
        // 大文件用临时文件缓冲，避免整份 PDF 常驻内存
        try (PDDocument document = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder content = new StringBuilder();
            StringBuilder trace = new StringBuilder();
            int visionUsed = 0;
            for (int i = 0; i < pageCount; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document);
                String trimmed = pageText == null ? "" : pageText.strip();
                boolean largeImage = pageHasLargeImage(document.getPage(i));
                if ((trimmed.length() < minTextChars || largeImage) && visionUsed < maxVisionPages) {
                    visionUsed++;
                    try {
                        byte[] pageImage = visionParseService.renderPageToJpeg(document, i);
                        String markdown = visionParseService.parseImage(pageImage, "image/jpeg");
                        if (!markdown.isBlank()) {
                            trimmed = markdown;
                            if (trace.length() > 0) {
                                trace.append('、');
                            }
                            trace.append('第').append(i + 1).append("页");
                        }
                    } catch (Exception e) {
                        // 单页视觉失败降级为页面文字层或占位说明，不中断整个文件
                        log.warn("PDF 第 {} 页视觉解析失败: {}", i + 1, e.getMessage());
                        if (trimmed.isEmpty()) {
                            trimmed = "[第 " + (i + 1) + " 页视觉解析失败]";
                        }
                    }
                }
                if (!trimmed.isBlank()) {
                    content.append("## 第 ").append(i + 1).append(" 页\n\n").append(trimmed).append("\n\n");
                }
            }
            // 没有提取到任何内容时回退整本 Tika，避免空正文入库
            if (content.isEmpty()) {
                return new ParseResult(documentParser.parse(file), null);
            }
            return new ParseResult(content.toString(), trace.isEmpty() ? null :
                    "PDF 视觉解析 " + visionUsed + " 页（" + trace + "），模型: " + describeVisionModel());
        }
    }

    /** DOCX：正文走 POI 提取，内嵌图片逐张送视觉，描述以小节追加到正文后面 */
    private ParseResult parseDocx(File file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file.toPath()));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            StringBuilder content = new StringBuilder(extractor.getText() == null ? "" : extractor.getText().strip());
            StringBuilder trace = new StringBuilder();
            int visionUsed = 0;
            int figureIndex = 0;
            for (XWPFPictureData picture : document.getAllPictures()) {
                String contentType = picture.getPackagePart().getContentType();
                if (!VISION_IMAGE_MIMES.contains(contentType)) {
                    continue;
                }
                figureIndex++;
                if (visionUsed >= maxVisionPages || picture.getData().length > MAX_IMAGE_BYTES) {
                    continue;
                }
                visionUsed++;
                try {
                    String description = visionParseService.parseImage(picture.getData(), contentType);
                    if (!description.isBlank()) {
                        content.append("\n\n### 图 ").append(figureIndex).append("（视觉解析）\n\n").append(description);
                        if (trace.length() > 0) {
                            trace.append('、');
                        }
                        trace.append('图').append(figureIndex);
                    }
                } catch (Exception e) {
                    // 单张图片失败只跳过该图，不影响文档其余内容
                    log.warn("DOCX 内嵌图第 {} 张视觉解析失败: {}", figureIndex, e.getMessage());
                }
            }
            if (content.isEmpty()) {
                return new ParseResult(documentParser.parse(file), null);
            }
            return new ParseResult(content.toString(), trace.isEmpty() ? null :
                    "DOCX 内嵌图视觉解析 " + visionUsed + " 张（" + trace + "），模型: " + describeVisionModel());
        } catch (Exception e) {
            // POI 解析失败时回退 Tika，保持原行为
            log.warn("DOCX 混合解析失败，回退 Tika: {}", file.getName(), e);
            return new ParseResult(documentParser.parse(file), null);
        }
    }

    /**
     * PPTX：正文与表格走 DocumentParser 的逐页结构化解析，
     * 开启视觉时页内嵌入图片逐张送视觉模型，描述以小节追加到正文后面
     */
    private ParseResult parsePptx(File file) throws IOException {
        try {
            String baseText = documentParser.parse(file);
            StringBuilder content = new StringBuilder(baseText == null ? "" : baseText.strip());
            StringBuilder trace = new StringBuilder();
            int visionUsed = 0;
            int figureIndex = 0;
            try (FileInputStream fis = new FileInputStream(file);
                 XMLSlideShow slideshow = new XMLSlideShow(fis)) {
                for (XSLFSheet slide : slideshow.getSlides()) {
                    for (XSLFShape shape : slide.getShapes()) {
                        if (!(shape instanceof XSLFPictureShape)) {
                            continue;
                        }
                        XSLFPictureData data = ((XSLFPictureShape) shape).getPictureData();
                        String contentType = data.getContentType();
                        if (!VISION_IMAGE_MIMES.contains(contentType)) {
                            continue;
                        }
                        figureIndex++;
                        if (visionUsed >= maxVisionPages || data.getData().length > MAX_IMAGE_BYTES) {
                            continue;
                        }
                        visionUsed++;
                        try {
                            String description = visionParseService.parseImage(data.getData(), contentType);
                            if (!description.isBlank()) {
                                content.append("\n\n### 图 ").append(figureIndex).append("（视觉解析）\n\n").append(description);
                                if (trace.length() > 0) {
                                    trace.append('、');
                                }
                                trace.append('图').append(figureIndex);
                            }
                        } catch (Exception e) {
                            // 单张图片失败只跳过该图，不影响文档其余内容
                            log.warn("PPTX 内嵌图第 {} 张视觉解析失败: {}", figureIndex, e.getMessage());
                        }
                    }
                }
            }
            if (content.isEmpty()) {
                return new ParseResult(documentParser.parse(file), null);
            }
            return new ParseResult(content.toString(), trace.isEmpty() ? null :
                    "PPTX 内嵌图视觉解析 " + visionUsed + " 张（" + trace + "），模型: " + describeVisionModel());
        } catch (Exception e) {
            // POI 解析失败时回退 Tika，保持原行为
            log.warn("PPTX 混合解析失败，回退 Tika: {}", file.getName(), e);
            return new ParseResult(documentParser.parse(file), null);
        }
    }

    /** 判断页面是否包含大面积图片，按图片像素面积与页面点面积之比估算 */
    private boolean pageHasLargeImage(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) {
            return false;
        }
        float pageArea = page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
        if (pageArea <= 0) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject) {
                    PDImageXObject image = (PDImageXObject) xObject;
                    double ratio = (double) image.getWidth() * image.getHeight() / pageArea;
                    if (ratio >= imageAreaRatio) {
                        return true;
                    }
                }
            } catch (IOException e) {
                // 单个图片对象损坏时跳过，不影响页面判定
                log.debug("跳过无法读取的页面图片对象: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean isImageFile(String lowerFileName) {
        return IMAGE_EXTENSIONS.stream().anyMatch(lowerFileName::endsWith);
    }

    /** 按扩展名推断图片 MIME 类型 */
    private String resolveImageMime(String lowerFileName) {
        if (lowerFileName.endsWith(".png")) {
            return "image/png";
        }
        if (lowerFileName.endsWith(".gif")) {
            return "image/gif";
        }
        if (lowerFileName.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /** 视觉模型描述，用于解析溯源展示；未配置时返回占位说明 */
    private String describeVisionModel() {
        String model = visionParseService.describeVisionModel();
        return model == null ? "未配置视觉模型" : model;
    }
}
