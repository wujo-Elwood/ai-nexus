package com.rag.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.analysis.mapper.AnalysisFileMapper;
import com.rag.common.BusinessException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 表格分析服务测试：内存构建真实 xlsx，验证上传解析、用户隔离和聚合计算
 */
class SpreadsheetServiceTest {

    private static final long FILE_ID = 1L;
    private static final long USER_ID = 1L;

    private AnalysisFileMapper mapper;
    private SpreadsheetService service;
    private Long fileId;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(AnalysisFileMapper.class);
        service = new SpreadsheetService(mapper, new ObjectMapper(), "target/test-analysis");
        // 模拟入库：回填主键并捕获实体（含真实落盘路径）
        com.rag.analysis.entity.AnalysisFile[] captured = new com.rag.analysis.entity.AnalysisFile[1];
        when(mapper.insert(any())).thenAnswer(invocation -> {
            com.rag.analysis.entity.AnalysisFile entity = invocation.getArgument(0);
            entity.setId(FILE_ID);
            entity.setUserId(USER_ID);
            captured[0] = entity;
            return 1;
        });
        // 上传一次测试表格
        Map<String, Object> uploaded = service.upload(buildTestFile(), USER_ID);
        fileId = ((Number) uploaded.get("id")).longValue();
        // 后续按需读取走捕获到的真实实体
        when(mapper.findByIdAndUserId(FILE_ID, USER_ID)).thenReturn(captured[0]);
        when(mapper.findByUserId(USER_ID)).thenReturn(List.of(captured[0]));
    }

    /** 内存构建测试 xlsx：产品/区域/销售额/数量 四列四行 */
    private MockMultipartFile buildTestFile() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("销售数据");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品");
            header.createCell(1).setCellValue("区域");
            header.createCell(2).setCellValue("销售额");
            header.createCell(3).setCellValue("数量");
            Object[][] data = {
                    {"产品A", "华东", 100.0, 1.0},
                    {"产品B", "华北", 200.0, 2.0},
                    {"产品C", "华东", 300.0, 3.0},
                    {"产品D", "华南", 50.0, 5.0}
            };
            for (int i = 0; i < data.length; i++) {
                var row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue((String) data[i][0]);
                row.createCell(1).setCellValue((String) data[i][1]);
                row.createCell(2).setCellValue((Double) data[i][2]);
                row.createCell(3).setCellValue((Double) data[i][3]);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "测试销售.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    @Test
    void uploadParsesSchemaAndTypes() throws Exception {
        Map<String, Object> uploaded = service.upload(buildTestFile(), 2L);
        assertEquals("测试销售.xlsx", uploaded.get("fileName"));
        assertEquals(4, uploaded.get("totalRows"));
        @SuppressWarnings("unchecked")
        List<SpreadsheetService.SheetSchema> sheets = (List<SpreadsheetService.SheetSchema>) uploaded.get("sheets");
        assertEquals("销售数据", sheets.get(0).name());
        assertEquals("number", sheets.get(0).columns().get(2).type());
        assertEquals("text", sheets.get(0).columns().get(0).type());
    }

    @Test
    void uploadRejectsNonXlsxFile() {
        MockMultipartFile csv = new MockMultipartFile("file", "数据.csv", "text/csv", "a,b,c".getBytes());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(csv, USER_ID));
        assertTrue(exception.getMessage().contains("仅支持 .xlsx"));
    }

    @Test
    void uploadRejectsCorruptFile() {
        MockMultipartFile corrupt = new MockMultipartFile("file", "损坏.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "这不是有效的xlsx内容".getBytes());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(corrupt, USER_ID));
        assertTrue(exception.getMessage().contains("解析失败"));
    }

    @Test
    void describeThrowsForMissingFile() {
        assertThrows(BusinessException.class, () -> service.describe(999L, USER_ID));
    }

    @Test
    void analyzeSumGroupByRegion() {
        String result = service.analyze(fileId, USER_ID, null, "sum", "销售额", "区域", null, null);
        assertTrue(result.contains("华东"));
        assertTrue(result.contains("400"));
        assertTrue(result.contains("华北"));
        assertTrue(result.contains("200"));
        assertTrue(result.contains("华南"));
    }

    @Test
    void analyzeAvgWithFilter() {
        String result = service.analyze(fileId, USER_ID, null, "avg", "销售额", null, "区域", "华东");
        assertTrue(result.contains("200"));
    }

    @Test
    void analyzeMaxWithoutGroup() {
        String result = service.analyze(fileId, USER_ID, null, "max", "销售额", null, null, null);
        assertTrue(result.contains("300"));
    }

    @Test
    void analyzeCountAllRows() {
        String result = service.analyze(fileId, USER_ID, null, "count", null, null, null, null);
        assertTrue(result.contains("4"));
    }

    @Test
    void wrongColumnReturnsAvailableList() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.analyze(fileId, USER_ID, null, "sum", "不存在的列", null, null, null));
        assertTrue(exception.getMessage().contains("可用列"));
        assertTrue(exception.getMessage().contains("销售额"));
    }

    @Test
    void userIsolationBlocksOtherUsers() {
        assertThrows(BusinessException.class,
                () -> service.analyze(fileId, 999L, null, "sum", "销售额", null, null, null));
    }

    @Test
    void describeReturnsSchemaAndPreview() {
        String result = service.describe(fileId, USER_ID);
        assertTrue(result.contains("销售数据"));
        assertTrue(result.contains("销售额(number)"));
        assertTrue(result.contains("样例"));
        assertTrue(result.contains("产品A"));
    }

    @Test
    void exportExcelAndCsvCarryAggregation() throws Exception {
        byte[] bytes = service.exportExcel(fileId, USER_ID, null, "sum", "销售额", "区域", null, null);
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("区域", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("sum(销售额)", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals(400.0, sheet.getRow(1).getCell(1).getNumericCellValue(), 0.001);
        }
        String csv = service.exportCsv(fileId, USER_ID, null, "sum", "销售额", "区域", null, null);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("区域,sum"));
        assertTrue(csv.contains("400"));
    }

    @Test
    void groupAnalyzeAppendsChartBlock() {
        String result = service.analyze(fileId, USER_ID, null, "sum", "销售额", "区域", null, null);
        assertTrue(result.contains("```chart"));
        assertTrue(result.contains("bar"));
        assertTrue(result.contains("400"));
        // 单值结果不可视化，不应带图表指令
        String single = service.analyze(fileId, USER_ID, null, "avg", "销售额", null, "区域", "华东");
        assertFalse(single.contains("```chart"));
    }
}
