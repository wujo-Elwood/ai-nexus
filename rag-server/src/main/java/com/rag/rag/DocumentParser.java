package com.rag.rag;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析器
 * 使用 Apache Tika 和 Apache POI 从各种文件格式中提取文本内容
 *
 * 支持的格式和解析策略：
 * - PDF：使用 Tika 提取文本（含表格文字内容）
 * - DOC/DOCX：使用 Tika 提取段落文本
 * - TXT/Markdown：直接读取文件内容
 * - XLSX：使用 Apache POI 逐行逐列读取，转换为 Markdown 表格格式（保留结构）
 *
 * 表格处理说明：
 * - XLSX 文件：使用 POI 精确读取每个单元格，输出为 Markdown 表格
 *   表头用 | 分隔，第二行用 --- 分隔，数据行用 | 分隔
 *   示例：
 *   | 姓名 | 年龄 | 部门 |
 *   | --- | --- | --- |
 *   | 张三 | 28 | 技术部 |
 *   | 李四 | 32 | 产品部 |
 *
 * - PDF 文件：Tika 会提取表格中的文字，但不保留表格结构
 *   如果 PDF 中有表格，提取出来的文字会按阅读顺序排列
 */
@Component
public class DocumentParser {

    /** Tika 实例，用于自动识别文件格式并提取文本 */
    private final Tika tika = new Tika();

    /**
     * 解析文件，提取文本内容
     * 根据文件扩展名自动选择解析策略：
     * - .xlsx 文件使用 Apache POI 精确解析（保留表格结构）
     * - 其他文件使用 Tika 通用解析
     *
     * @param file 待解析的文件对象
     * @return 提取的文本内容（XLSX 文件返回 Markdown 表格格式）
     * @throws IOException 解析失败时抛出
     */
    public String parse(File file) throws IOException {
        String fileName = file.getName().toLowerCase();

        //图片不做 OCR 或视觉解析，只保留文件名作为可检索上下文
        if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                || fileName.endsWith(".gif") || fileName.endsWith(".webp")) {
            return "[图片引用: " + file.getName() + "]";
        }

        // XLSX 文件使用 POI 精确解析，保留表格结构
        if (fileName.endsWith(".xlsx")) {
            return parseExcel(file);
        }

        // 其他文件使用 Tika 通用解析
        return parseWithTika(file);
    }

    /**
     * 使用 Tika 解析文件（PDF、DOC、DOCX、TXT、Markdown 等）
     *
     * 解析流程：
     * 1. 创建 AutoDetectParser，自动识别文件格式
     * 2. 创建 BodyContentHandler，提取 body 标签中的文本内容
     * 3. 设置提取阈值为 -1（不限制提取长度）
     * 4. 调用 parser.parse() 解析文件
     * 5. 返回提取的纯文本
     *
     * @param file 待解析的文件
     * @return 提取的纯文本内容
     */
    private String parseWithTika(File file) throws IOException {
        try {
            // 使用 Tika 的通用解析方法
            // Tika 内部会根据文件类型选择合适的解析器：
            // PDF → PDFParser（Apache PDFBox）
            // DOC/DOCX → OfficeParser（Apache POI）
            // TXT → 读取纯文本
            return tika.parseToString(file);
        } catch (Exception e) {
            throw new IOException("Failed to parse document: " + file.getName(), e);
        }
    }

    /**
     * 使用 Apache POI 解析 Excel 文件（.xlsx）
     * 将每个 Sheet 转换为 Markdown 表格格式，保留完整的表格结构
     *
     * 解析流程：
     * 1. 使用 XSSFWorkbook 打开 Excel 文件
     * 2. 遍历每个 Sheet
     * 3. 遍历每个 Row（行）
     * 4. 遍历每个 Cell（单元格），读取单元格值
     * 5. 第一行作为表头，用 Markdown 表格格式输出
     * 6. 后续行作为数据行
     * 7. 多个 Sheet 之间用分隔线隔开
     *
     * 输出格式示例：
     * # Sheet1
     *
     * | 姓名 | 年龄 | 部门 |
     * | --- | --- | --- |
     * | 张三 | 28 | 技术部 |
     * | 李四 | 32 | 产品部 |
     *
     * @param file Excel 文件
     * @return Markdown 表格格式的文本内容
     */
    private String parseExcel(File file) throws IOException {
        StringBuilder result = new StringBuilder();

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            // 遍历每个 Sheet
            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                // 跳过空 Sheet
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }

                // Sheet 标题
                if (sheetIdx > 0) {
                    result.append("\n\n");
                }
                result.append("# Sheet: ").append(sheetName).append("\n");

                // 读取所有行数据
                List<List<String>> allRows = new ArrayList<>();
                int maxCols = 0;

                for (int rowIdx = 0; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                    Row row = sheet.getRow(rowIdx);
                    if (row == null) {
                        continue;
                    }

                    List<String> rowData = new ArrayList<>();
                    int lastCol = row.getLastCellNum();
                    if (lastCol > maxCols) {
                        maxCols = lastCol;
                    }

                    for (int colIdx = 0; colIdx < lastCol; colIdx++) {
                        Cell cell = row.getCell(colIdx);
                        rowData.add(getCellValue(cell));
                    }

                    // 跳过全空的行
                    if (rowData.stream().anyMatch(s -> !s.isEmpty())) {
                        allRows.add(rowData);
                    }
                }

                if (allRows.isEmpty()) {
                    continue;
                }

                // 生成 Markdown 表格
                // 第一行作为表头
                List<String> header = allRows.get(0);
                result.append("表格范围: A1:").append(columnName(Math.max(1, maxCols))).append(allRows.size()).append("\n");
                result.append("| ");
                for (int i = 0; i < maxCols; i++) {
                    String val = i < header.size() ? header.get(i) : "";
                    result.append(escapeTableCell(val)).append(" | ");
                }
                result.append("\n");

                // 分隔行
                result.append("| ");
                for (int i = 0; i < maxCols; i++) {
                    result.append("--- | ");
                }
                result.append("\n");

                // 数据行
                for (int rowIdx = 1; rowIdx < allRows.size(); rowIdx++) {
                    List<String> row = allRows.get(rowIdx);
                    result.append("| ");
                    for (int i = 0; i < maxCols; i++) {
                        String val = i < row.size() ? row.get(i) : "";
                        result.append(escapeTableCell(val)).append(" | ");
                    }
                    result.append("\n");
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse Excel file: " + file.getName(), e);
        }

        return result.toString();
    }

    /**
     * 读取 Excel 单元格的值，转换为字符串
     * 支持所有单元格类型：字符串、数字、布尔、公式、日期、空值
     *
     * @param cell 单元格对象
     * @return 单元格的字符串值，空单元格返回空字符串
     */
    private String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case STRING:
                // 字符串类型：直接返回，去掉换行符避免破坏表格格式
                return cell.getStringCellValue().replace("\n", " ").trim();

            case NUMERIC:
                // 数字类型：整数不带小数点，浮点数保留合理精度
                double numVal = cell.getNumericCellValue();
                if (numVal == Math.floor(numVal) && !Double.isInfinite(numVal)) {
                    return String.valueOf((long) numVal);
                }
                return String.valueOf(numVal);

            case BOOLEAN:
                // 布尔类型
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:
                // 公式类型：尝试获取计算结果，失败则返回公式文本
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        double formulaVal = cell.getNumericCellValue();
                        if (formulaVal == Math.floor(formulaVal) && !Double.isInfinite(formulaVal)) {
                            return String.valueOf((long) formulaVal);
                        }
                        return String.valueOf(formulaVal);
                    } catch (Exception e2) {
                        return cell.getCellFormula();
                    }
                }

            case BLANK:
                // 空单元格
                return "";

            default:
                return "";
        }
    }

    /** 转义 Markdown 表格中的分隔符，避免单元格破坏结构。 */
    private String escapeTableCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** 将列号转换为 Excel 字母。 */
    private String columnName(int column) {
        StringBuilder result = new StringBuilder();
        int value = column;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.append((char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.reverse().toString();
    }
}
