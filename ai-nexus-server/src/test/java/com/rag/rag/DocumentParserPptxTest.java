package com.rag.rag;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPTX 逐页结构化解析测试
 * 用 POI 生成含标题、正文、表格、备注的演示文稿，验证解析输出的结构与内容
 */
class DocumentParserPptxTest {

    private final DocumentParser parser = new DocumentParser();

    @TempDir
    Path tempDir;

    /**
     * 解析输出应包含逐页标题、正文段落、Markdown 表格与备注
     */
    @Test
    void parseShouldKeepSlideStructureAndTable() throws Exception {
        File pptx = buildSamplePptx();
        String markdown = parser.parse(pptx);

        assertTrue(markdown.contains("# 第 1 页"), "应输出逐页标题:\n" + markdown);
        assertTrue(markdown.contains("季度经营回顾"), "应包含页标题:\n" + markdown);
        assertTrue(markdown.contains("营收同比增长 20%"), "应包含正文段落:\n" + markdown);
        assertTrue(markdown.contains("| 指标 | 数值 |"), "应包含表格表头:\n" + markdown);
        assertTrue(markdown.contains("| --- | --- |"), "应包含表格分隔行:\n" + markdown);
        assertTrue(markdown.contains("| 营收 | 1.2 亿 |"), "应包含表格数据行:\n" + markdown);
        assertTrue(markdown.contains("第 2 页"), "应包含第二页:\n" + markdown);
        assertTrue(markdown.contains("展望"), "应包含第二页标题:\n" + markdown);
    }

    /** 用 POI 生成两页演示文稿：第一页标题+正文+表格+备注，第二页仅标题 */
    private File buildSamplePptx() throws Exception {
        try (XMLSlideShow slideshow = new XMLSlideShow();
             FileOutputStream out = new FileOutputStream(new File(tempDir.toFile(), "sample.pptx"))) {
            XSLFSlide slide1 = slideshow.createSlide();

            // 标题文本框标记为 TITLE 占位符，使 slide.getTitle() 能读到
            XSLFTextBox title = slide1.createTextBox();
            title.setAnchor(new Rectangle(20, 10, 400, 40));
            title.getPlaceholderDetails().setPlaceholder(Placeholder.TITLE);
            XSLFTextParagraph titleParagraph = title.addNewTextParagraph();
            titleParagraph.addNewTextRun().setText("季度经营回顾");

            XSLFTextBox body = slide1.createTextBox();
            body.setAnchor(new Rectangle(20, 60, 400, 60));
            XSLFTextParagraph paragraph = body.addNewTextParagraph();
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText("营收同比增长 20%");

            XSLFTable table = slide1.createTable();
            table.setAnchor(new Rectangle(20, 140, 400, 80));
            table.addRow().addCell().setText("指标");
            table.getRows().get(0).addCell().setText("数值");
            table.addRow().addCell().setText("营收");
            table.getRows().get(1).addCell().setText("1.2 亿");

            XSLFSlide slide2 = slideshow.createSlide();
            XSLFTextBox title2 = slide2.createTextBox();
            title2.setAnchor(new Rectangle(20, 10, 400, 40));
            title2.getPlaceholderDetails().setPlaceholder(Placeholder.TITLE);
            title2.addNewTextParagraph().addNewTextRun().setText("展望");

            slideshow.write(out);
            return new File(tempDir.toFile(), "sample.pptx");
        }
    }
}
