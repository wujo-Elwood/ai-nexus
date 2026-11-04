package com.rag.rag;

import com.rag.ai.VisionParseService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合文档解析器单元测试：验证视觉开关的路由行为
 * 视觉服务用假模型替代，不发起真实视觉调用；PDF/DOCX 测试文件在临时目录现场生成
 */
class HybridDocumentParserTest {

    @TempDir
    Path tempDir;

    private DocumentParser documentParser;
    private VisionParseService visionParseService;
    private HybridDocumentParser parser;

    @BeforeEach
    void setUp() {
        documentParser = mock(DocumentParser.class);
        visionParseService = mock(VisionParseService.class);
        parser = new HybridDocumentParser();
        ReflectionTestUtils.setField(parser, "documentParser", documentParser);
        ReflectionTestUtils.setField(parser, "visionParseService", visionParseService);
        ReflectionTestUtils.setField(parser, "maxVisionPages", 5);
        ReflectionTestUtils.setField(parser, "minTextChars", 30);
        ReflectionTestUtils.setField(parser, "imageAreaRatio", 0.5);
    }

    @Test
    void visionDisabled_delegatesToTikaAndNeverCallsVision() throws Exception {
        File png = writeBytes("photo.png", tinyPng());
        when(documentParser.parse(any(File.class))).thenReturn("tika 文本");

        HybridDocumentParser.ParseResult result = parser.parse(png, false);

        assertEquals("tika 文本", result.getContent());
        assertNull(result.getVisionTrace());
        verify(visionParseService, never()).parseImage(any(byte[].class), anyString());
    }

    @Test
    void imageFile_parsesThroughVisionWithCorrectMime() throws Exception {
        File webp = writeBytes("diagram.webp", tinyPng());
        when(visionParseService.describeVisionModel()).thenReturn("测试供应商 / mock-vision");
        when(visionParseService.parseImage(any(byte[].class), eq("image/webp"))).thenReturn("# 流程图内容");

        HybridDocumentParser.ParseResult result = parser.parse(webp, true);

        assertEquals("# 流程图内容", result.getContent());
        assertTrue(result.getVisionTrace().contains("图片文件视觉解析"));
        assertTrue(result.getVisionTrace().contains("测试供应商 / mock-vision"));
    }

    @Test
    void imageFile_visionFailureFallsBackToPlaceholder() throws Exception {
        File png = writeBytes("broken.png", tinyPng());
        when(visionParseService.parseImage(any(byte[].class), anyString()))
                .thenThrow(new RuntimeException("视觉服务不可用"));

        HybridDocumentParser.ParseResult result = parser.parse(png, true);

        assertEquals("[图片引用: broken.png]", result.getContent());
        assertNull(result.getVisionTrace());
    }

    @Test
    void imageFile_overSizeLimit_skipsVision() throws Exception {
        byte[] huge = new byte[10 * 1024 * 1024 + 1];
        File png = writeBytes("huge.png", huge);

        HybridDocumentParser.ParseResult result = parser.parse(png, true);

        assertEquals("[图片引用: huge.png]", result.getContent());
        verify(visionParseService, never()).parseImage(any(byte[].class), anyString());
    }

    @Test
    void pdf_textPage_keepsTextLayerWithoutVision() throws Exception {
        File pdf = writeTextPdf("This page has a real text layer for parsing", false);
        when(documentParser.parse(any(File.class))).thenReturn("tika 文本");

        HybridDocumentParser.ParseResult result = parser.parse(pdf, true);

        assertTrue(result.getContent().contains("This page has a real text layer for parsing"));
        assertNull(result.getVisionTrace());
        verify(visionParseService, never()).parseImage(any(byte[].class), anyString());
    }

    @Test
    void pdf_scannedPage_routesToVision() throws Exception {
        File pdf = writeEmptyPdf(1);
        when(visionParseService.renderPageToJpeg(any(PDDocument.class), anyInt())).thenReturn(tinyPng());
        when(visionParseService.parseImage(any(byte[].class), eq("image/jpeg"))).thenReturn("# 扫描页识别结果");

        HybridDocumentParser.ParseResult result = parser.parse(pdf, true);

        verify(visionParseService, times(1)).parseImage(any(byte[].class), eq("image/jpeg"));
        assertTrue(result.getContent().contains("## 第 1 页"));
        assertTrue(result.getContent().contains("# 扫描页识别结果"));
        assertTrue(result.getVisionTrace().contains("第1页"));
    }

    @Test
    void pdf_largeImagePage_routesToVisionEvenWithText() throws Exception {
        File pdf = writeTextPdf("This page has a real text layer for parsing", true);
        when(visionParseService.renderPageToJpeg(any(PDDocument.class), anyInt())).thenReturn(tinyPng());
        when(visionParseService.parseImage(any(byte[].class), eq("image/jpeg"))).thenReturn("# 图表页识别结果");

        HybridDocumentParser.ParseResult result = parser.parse(pdf, true);

        verify(visionParseService, times(1)).parseImage(any(byte[].class), eq("image/jpeg"));
        assertTrue(result.getContent().contains("# 图表页识别结果"));
    }

    @Test
    void pdf_respectsVisionPageBudget() throws Exception {
        File pdf = writeEmptyPdf(2);
        when(visionParseService.renderPageToJpeg(any(PDDocument.class), anyInt())).thenReturn(tinyPng());
        when(visionParseService.parseImage(any(byte[].class), anyString())).thenReturn("# 页面识别");
        ReflectionTestUtils.setField(parser, "maxVisionPages", 1);

        HybridDocumentParser.ParseResult result = parser.parse(pdf, true);

        verify(visionParseService, times(1)).parseImage(any(byte[].class), anyString());
        assertTrue(result.getContent().contains("## 第 1 页"));
        assertFalse(result.getContent().contains("## 第 2 页"));
    }

    @Test
    void pdf_visionReturnsBlank_fallsBackToTika() throws Exception {
        File pdf = writeEmptyPdf(1);
        when(visionParseService.renderPageToJpeg(any(PDDocument.class), anyInt())).thenReturn(tinyPng());
        when(visionParseService.parseImage(any(byte[].class), anyString())).thenReturn("  ");
        when(documentParser.parse(any(File.class))).thenReturn("tika 兜底文本");

        HybridDocumentParser.ParseResult result = parser.parse(pdf, true);

        assertEquals("tika 兜底文本", result.getContent());
        assertNull(result.getVisionTrace());
    }

    @Test
    void docx_embeddedImage_parsesThroughVision() throws Exception {
        File docx = writeDocx("docx 正文内容", true);
        when(visionParseService.parseImage(any(byte[].class), eq("image/png"))).thenReturn("内嵌图表：季度销量柱状图");

        HybridDocumentParser.ParseResult result = parser.parse(docx, true);

        assertTrue(result.getContent().contains("docx 正文内容"));
        assertTrue(result.getContent().contains("### 图 1（视觉解析）"));
        assertTrue(result.getContent().contains("内嵌图表：季度销量柱状图"));
        assertTrue(result.getVisionTrace().contains("DOCX 内嵌图视觉解析 1 张"));
    }

    @Test
    void docx_corruptFile_fallsBackToTika() throws Exception {
        File docx = writeBytes("broken.docx", "不是合法的 DOCX 内容".getBytes());
        when(documentParser.parse(any(File.class))).thenReturn("tika 兜底文本");

        HybridDocumentParser.ParseResult result = parser.parse(docx, true);

        assertEquals("tika 兜底文本", result.getContent());
        assertNull(result.getVisionTrace());
    }

    @Test
    void xlsx_alwaysDelegatesWithoutVision() throws Exception {
        File xlsx = writeBytes("data.xlsx", new byte[]{1, 2, 3});
        when(documentParser.parse(any(File.class))).thenReturn("| 表格 |");

        HybridDocumentParser.ParseResult result = parser.parse(xlsx, true);

        assertEquals("| 表格 |", result.getContent());
        assertNull(result.getVisionTrace());
        verify(visionParseService, never()).parseImage(any(byte[].class), anyString());
    }

    // ===== 测试文件生成 =====

    private File writeBytes(String fileName, byte[] bytes) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.write(path, bytes);
        return path.toFile();
    }

    /** 生成只含文字层的单页 PDF，includeLargeImage 时整页铺一张大图 */
    private File writeTextPdf(String text, boolean includeLargeImage) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
                if (includeLargeImage) {
                    PDImageXObject image = LosslessFactory.createFromImage(document,
                            new BufferedImage(595, 842, BufferedImage.TYPE_INT_RGB));
                    content.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            Path path = tempDir.resolve("text-" + System.nanoTime() + ".pdf");
            document.save(path.toFile());
            return path.toFile();
        }
    }

    /** 生成指定页数的纯扫描 PDF（每页无文字层） */
    private File writeEmptyPdf(int pageCount) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            Path path = tempDir.resolve("scan-" + System.nanoTime() + ".pdf");
            document.save(path.toFile());
            return path.toFile();
        }
    }

    /** 生成 DOCX：正文一段文字，可选内嵌一张 PNG 图 */
    private File writeDocx(String bodyText, boolean includePicture) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(bodyText);
            if (includePicture) {
                run.addPicture(new ByteArrayInputStream(tinyPng()), XWPFDocument.PICTURE_TYPE_PNG,
                        "chart.png", Units.toEMU(64), Units.toEMU(64));
            }
            Path path = tempDir.resolve("doc-" + System.nanoTime() + ".docx");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                document.write(out);
                Files.write(path, out.toByteArray());
            }
            return path.toFile();
        }
    }

    private byte[] tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
