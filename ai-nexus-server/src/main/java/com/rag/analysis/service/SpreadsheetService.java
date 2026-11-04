package com.rag.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.analysis.entity.AnalysisFile;
import com.rag.analysis.mapper.AnalysisFileMapper;
import com.rag.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 表格分析服务
 * 上传 xlsx 时用 POI 解析表结构（Sheet/列/类型/行数）入库，行数据按需从文件读取；
 * 聚合分析在服务端做确定性计算（count/sum/avg/min/max/distinct + 分组 + 等值筛选），
 * 模型只拿到汇总结果，避免把大表塞进上下文
 */
@Slf4j
@Service
public class SpreadsheetService {

    private static final int MAX_ROWS = 100_000;
    private static final int MAX_SHEETS = 20;
    private static final int PREVIEW_ROWS = 5;
    private static final int GROUP_LIMIT_DEFAULT = 20;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AnalysisFileMapper analysisFileMapper;
    private final ObjectMapper objectMapper;
    private final String uploadDir;
    private final DataFormatter cellFormatter = new DataFormatter();

    public SpreadsheetService(AnalysisFileMapper analysisFileMapper,
                              ObjectMapper objectMapper,
                              @Value("${file.upload-dir}") String uploadDir) {
        this.analysisFileMapper = analysisFileMapper;
        this.objectMapper = objectMapper;
        this.uploadDir = uploadDir;
    }

    /** 表结构定义 */
    public record SheetSchema(String name, List<ColumnSchema> columns, int rowCount) {
    }

    public record ColumnSchema(String name, String type) {
    }

    /** 上传并解析：仅登记表结构，返回带预览的数据 */
    public Map<String, Object> upload(MultipartFile file, Long userId) {
        // 第1步：校验文件
        String originalName = file.getOriginalFilename() == null ? "未命名.xlsx" : file.getOriginalFilename();
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(400, "目前仅支持 .xlsx 文件，CSV 支持即将上线");
        }
        // 第2步：保存文件并解析表结构
        String storedName = UUID.randomUUID() + "_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = Paths.get(uploadDir, "analysis", storedName);
        List<SheetSchema> sheets;
        int totalRows;
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
            sheets = parseSchema(target);
            totalRows = sheets.stream().mapToInt(SheetSchema::rowCount).sum();
        } catch (BusinessException e) {
            quietDelete(target);
            throw e;
        } catch (Exception e) {
            quietDelete(target);
            log.warn("表格解析失败: {}", e.getMessage());
            throw new BusinessException(400, "表格解析失败，请确认是有效的 .xlsx 文件");
        }
        // 第3步：入库登记
        AnalysisFile entity = new AnalysisFile();
        entity.setUserId(userId);
        entity.setFileName(originalName);
        entity.setFilePath(target.toString());
        entity.setTotalRows(totalRows);
        try {
            entity.setSheetsJson(objectMapper.writeValueAsString(sheets));
        } catch (Exception e) {
            throw new BusinessException(500, "表结构序列化失败");
        }
        analysisFileMapper.insert(entity);
        // 第4步：组装返回（含首表预览）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("fileName", originalName);
        result.put("totalRows", totalRows);
        result.put("sheets", sheets);
        try {
            result.put("preview", previewMarkdown(target, sheets.get(0).name()));
        } catch (Exception e) {
            result.put("preview", "");
        }
        return result;
    }

    /** 查询用户的表格列表 */
    public List<AnalysisFile> listByUser(Long userId) {
        return analysisFileMapper.findByUserId(userId);
    }

    /** 按 ID 加载并校验归属 */
    public AnalysisFile loadForUser(Long id, Long userId) {
        AnalysisFile file = analysisFileMapper.findByIdAndUserId(id, userId);
        if (file == null) {
            throw new BusinessException(404, "表格不存在或无权访问：id=" + id);
        }
        return file;
    }

    /** 删除表格登记与文件 */
    public void delete(Long id, Long userId) {
        AnalysisFile file = loadForUser(id, userId);
        analysisFileMapper.deleteByIdAndUserId(id, userId);
        quietDelete(Paths.get(file.getFilePath()));
    }

    /** 表结构描述 + 每个表的前几行样例，供模型理解数据 */
    public String describe(Long id, Long userId) {
        AnalysisFile file = loadForUser(id, userId);
        StringBuilder builder = new StringBuilder("表格「").append(file.getFileName()).append("」共 ")
                .append(file.getTotalRows()).append(" 行数据。\n");
        try {
            List<SheetSchema> sheets = readSchemas(file);
            try (Workbook workbook = open(file.getFilePath())) {
                for (SheetSchema schema : sheets) {
                    builder.append("\n## Sheet：").append(schema.name())
                            .append("（").append(schema.rowCount()).append(" 行）\n列：")
                            .append(schema.columns().stream()
                                    .map(c -> c.name() + "(" + c.type() + ")")
                                    .reduce((a, b) -> a + ", " + b).orElse("无"))
                            .append('\n');
                    builder.append(previewRows(workbook.getSheet(schema.name()), 5)).append('\n');
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取表格失败: {}", e.getMessage());
            throw new BusinessException(400, "表格文件读取失败，可能已被移动或删除");
        }
        return builder.toString();
    }

    /**
     * 确定性聚合分析
     *
     * @param operation    count/sum/avg/min/max/distinct
     * @param column       数值列（count/distinct 可不传）
     * @param groupBy      分组列（可选）
     * @param filterColumn 筛选列（可选，等值匹配）
     * @param filterValue  筛选值（可选）
     */
    public String analyze(Long id, Long userId, String sheetName, String operation,
                          String column, String groupBy, String filterColumn, String filterValue) {
        AnalysisFile file = loadForUser(id, userId);
        String op = operation == null ? "count" : operation.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = List.of("count", "sum", "avg", "min", "max", "distinct");
        if (!allowed.contains(op)) {
            return "不支持的操作 " + operation + "，仅支持：" + String.join("/", allowed);
        }
        try (Workbook workbook = open(file.getFilePath())) {
            // 第1步：定位 Sheet 与表头
            Sheet sheet = resolveSheet(workbook, sheetName);
            List<String> headers = readHeaders(sheet);
            // 第2步：读数据行（字符串化 + 数值化标记）
            List<Map<String, String>> rows = readRows(sheet, headers);
            // 第3步：解析列名（宽松匹配，找不到时把可用列返回给模型自我纠正）
            String numericColumn = requireColumn(column, headers, "统计列", op.equals("count"));
            String groupColumn = requireColumn(groupBy, headers, "分组列", true);
            String filterCol = requireColumn(filterColumn, headers, "筛选列", true);
            // 第4步：等值筛选
            List<Map<String, String>> filtered = rows;
            if (filterCol != null && filterValue != null && !filterValue.isBlank()) {
                String expected = filterValue.trim();
                filtered = rows.stream()
                        .filter(row -> expected.equalsIgnoreCase(row.getOrDefault(filterCol, "").trim()))
                        .toList();
            }
            // 第5步：执行聚合（结构化结果 → Markdown + 图表指令）
            AggregationResult structured = aggregateStructured(op, numericColumn, groupColumn, filtered, rows.size());
            String chart = chartBlock(structured);
            String markdown = renderMarkdown(structured);
            return chart.isEmpty() ? markdown : markdown + "\n" + chart;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("表格分析失败: {}", e.getMessage());
            throw new BusinessException(400, "表格文件读取失败，可能已被移动或删除");
        }
    }

    /** 从入库 JSON 还原表结构定义 */
    private List<SheetSchema> readSchemas(AnalysisFile file) {
        try {
            return objectMapper.readValue(file.getSheetsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SheetSchema.class));
        } catch (Exception e) {
            throw new BusinessException(400, "表结构数据损坏，请重新上传");
        }
    }

    /** 解析表结构：首个非空行为表头，其余为数据行；列类型按全列是否可数值化推断 */
    private List<SheetSchema> parseSchema(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path); Workbook workbook = new XSSFWorkbook(in)) {
            if (workbook.getNumberOfSheets() > MAX_SHEETS) {
                throw new BusinessException(400, "Sheet 数量超过 " + MAX_SHEETS + " 个，请精简后再上传");
            }
            List<SheetSchema> sheets = new ArrayList<>();
            int totalRows = 0;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                List<String> headers = readHeaders(sheet);
                if (headers.isEmpty()) {
                    continue;
                }
                List<Map<String, String>> rows = readRows(sheet, headers);
                List<ColumnSchema> columns = new ArrayList<>();
                for (String header : headers) {
                    boolean numeric = !rows.isEmpty() && rows.stream()
                            .allMatch(row -> isNumeric(row.get(header)));
                    columns.add(new ColumnSchema(header, numeric ? "number" : "text"));
                }
                sheets.add(new SheetSchema(sheet.getSheetName(), columns, rows.size()));
                totalRows += rows.size();
            }
            if (sheets.isEmpty()) {
                throw new BusinessException(400, "表格中没有读到有效表头，请确认首行是列名");
            }
            if (totalRows > MAX_ROWS) {
                throw new BusinessException(400, "数据行数 " + totalRows + " 超过上限 " + MAX_ROWS + "，请拆分后上传");
            }
            return sheets;
        }
    }

    /** 读表头：首个非空行，空表头单元格补"列N" */
    private List<String> readHeaders(Sheet sheet) {
        for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || row.getPhysicalNumberOfCells() == 0) {
                continue;
            }
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String name = cellFormatter.formatCellValue(row.getCell(c)).trim();
                headers.add(name.isEmpty() ? "列" + (c + 1) : name);
            }
            return headers;
        }
        return List.of();
    }

    /** 读数据行：表头之后的非空行，单元格统一字符串化（日期格式化、公式取缓存值） */
    private List<Map<String, String>> readRows(Sheet sheet, List<String> headers) {
        List<Map<String, String>> rows = new ArrayList<>();
        int headerRowIndex = findHeaderRowIndex(sheet);
        for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isBlankRow(row, headers.size())) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                map.put(headers.get(c), cellFormatter.formatCellValue(row.getCell(c)).trim());
            }
            rows.add(map);
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        return rows;
    }

    private int findHeaderRowIndex(Sheet sheet) {
        for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getPhysicalNumberOfCells() > 0) {
                return i;
            }
        }
        return -1;
    }

    private boolean isBlankRow(Row row, int columnCount) {
        for (int c = 0; c < columnCount; c++) {
            if (!cellFormatter.formatCellValue(row.getCell(c)).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 表头预览：前 N 行转 Markdown 表格 */
    private String previewMarkdown(Path path, String sheetName) throws Exception {
        try (Workbook workbook = open(path.toString())) {
            Sheet sheet = resolveSheet(workbook, sheetName);
            List<String> headers = readHeaders(sheet);
            if (headers.isEmpty()) {
                return "";
            }
            return previewRows(sheet, PREVIEW_ROWS);
        }
    }

    private String previewRows(Sheet sheet, int limit) {
        List<String> headers = readHeaders(sheet);
        if (headers.isEmpty()) {
            return "";
        }
        List<Map<String, String>> rows = readRows(sheet, headers);
        StringBuilder builder = new StringBuilder("前 ").append(Math.min(limit, rows.size())).append(" 行样例：\n\n");
        builder.append("| ").append(String.join(" | ", headers)).append(" |\n");
        builder.append("|").append(headers.stream().map(h -> "---").reduce((a, b) -> a + "|" + b).orElse("")).append("|\n");
        for (int i = 0; i < rows.size() && i < limit; i++) {
            final int rowIndex = i;
            builder.append("| ").append(headers.stream().map(h -> rows.get(rowIndex).getOrDefault(h, ""))
                    .reduce((a, b) -> a + " | " + b).orElse("")).append(" |\n");
        }
        return builder.toString();
    }

    /** 聚合结果结构：Markdown 渲染、图表指令和导出共用 */
    public record AggregationResult(String operation, String numericColumn, String groupColumn,
                                    List<String> headers, List<List<Object>> rows,
                                    int analyzedRows, int totalRows) {
    }

    /** 组装结构化聚合结果：单值结果为一行，分组结果为每组一行（降序） */
    private AggregationResult aggregateStructured(String op, String numericColumn, String groupColumn,
                                                  List<Map<String, String>> filtered, int totalRows) {
        if (groupColumn == null) {
            BigDecimal value = computeValue(op, numericColumn, filtered);
            List<String> headers = List.of(op + (numericColumn == null ? "" : "(" + numericColumn + ")"));
            List<List<Object>> rows = List.of(new ArrayList<>(List.<Object>of(value)));
            return new AggregationResult(op, numericColumn, null, headers, rows, filtered.size(), totalRows);
        }
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (Map<String, String> row : filtered) {
            groups.computeIfAbsent(row.getOrDefault(groupColumn, "").trim(), key -> new ArrayList<>()).add(row);
        }
        List<Map.Entry<String, BigDecimal>> aggregated = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : groups.entrySet()) {
            aggregated.add(Map.entry(entry.getKey(), computeValue(op, numericColumn, entry.getValue())));
        }
        aggregated.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : aggregated) {
            rows.add(new ArrayList<>(List.of(
                    entry.getKey().isEmpty() ? "(空)" : entry.getKey(),
                    entry.getValue())));
        }
        return new AggregationResult(op, numericColumn, groupColumn,
                List.of(groupColumn, op + (numericColumn == null ? "" : "(" + numericColumn + ")")),
                rows, filtered.size(), totalRows);
    }

    /** 渲染 Markdown：与重构前格式保持一致 */
    private String renderMarkdown(AggregationResult result) {
        String op = result.operation();
        StringBuilder builder;
        if (result.groupColumn() != null) {
            int limit = Math.min(GROUP_LIMIT_DEFAULT, result.rows().size());
            builder = new StringBuilder("按「").append(result.groupColumn()).append("」分组执行 ").append(op)
                    .append(result.numericColumn() == null ? "" : "(" + result.numericColumn() + ")")
                    .append("，共 ").append(result.rows().size()).append(" 组，展示前 ").append(limit).append(" 组：\n\n")
                    .append("| ").append(result.headers().get(0)).append(" | ").append(result.headers().get(1))
                    .append(" |\n|---|---|\n");
            for (int i = 0; i < limit; i++) {
                List<Object> row = result.rows().get(i);
                builder.append("| ").append(fmt(row.get(0))).append(" | ").append(fmt(row.get(1))).append(" |\n");
            }
            builder.append("\n参与统计的行数：").append(result.analyzedRows())
                    .append("（全表 ").append(result.totalRows()).append(" 行）");
            return builder.toString();
        }
        if (op.equals("distinct")) {
            return "「" + result.numericColumn() + "」的去重值数量：" + fmt(result.rows().get(0).get(0))
                    + "（参与统计 " + result.analyzedRows() + " 行）";
        }
        Object value = result.rows().get(0).get(0);
        String base = "对「" + (result.numericColumn() == null ? "全部行" : result.numericColumn()) + "」执行 " + op
                + "，结果：" + fmt(value) + "（参与统计 " + result.analyzedRows() + " 行，全表 " + result.totalRows() + " 行）";
        if (op.equals("count")) {
            return base;
        }
        return base + "。请基于该数值回答，不要四舍五入成整数以外的编造精度。";
    }

    /** 分组结果生成图表指令块（前端按 ECharts 渲染）；单值结果不可视化 */
    private String chartBlock(AggregationResult result) {
        if (result.groupColumn() == null || result.rows().isEmpty()) {
            return "";
        }
        try {
            List<String> categories = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (List<Object> row : result.rows()) {
                categories.add(String.valueOf(row.get(0)));
                values.add(new BigDecimal(String.valueOf(row.get(1))));
            }
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("type", "bar");
            chart.put("title", "按" + result.groupColumn() + "分组 " + result.operation()
                    + (result.numericColumn() == null ? "" : "(" + result.numericColumn() + ")"));
            chart.put("categories", categories);
            chart.put("values", values);
            return "```chart\n" + objectMapper.writeValueAsString(chart) + "\n```";
        } catch (Exception e) {
            log.warn("图表指令生成失败: {}", e.getMessage());
            return "";
        }
    }

    /** 导出聚合结果为 Excel 字节流（列名 + 数据行 + 统计口径说明） */
    public byte[] exportExcel(Long id, Long userId, String sheetName, String operation, String column,
                              String groupBy, String filterColumn, String filterValue) throws Exception {
        AggregationResult result = aggregateStructuredFromRequest(id, userId, sheetName, operation, column,
                groupBy, filterColumn, filterValue);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("分析结果");
            Row header = sheet.createRow(0);
            for (int c = 0; c < result.headers().size(); c++) {
                header.createCell(c).setCellValue(result.headers().get(c));
            }
            int rowIndex = 1;
            for (List<Object> row : result.rows()) {
                Row dataRow = sheet.createRow(rowIndex++);
                for (int c = 0; c < row.size(); c++) {
                    Object value = row.get(c);
                    if (value instanceof Number number) {
                        dataRow.createCell(c).setCellValue(number.doubleValue());
                    } else {
                        dataRow.createCell(c).setCellValue(String.valueOf(value));
                    }
                }
            }
            Row meta = sheet.createRow(rowIndex + 1);
            meta.createCell(0).setCellValue("参与统计 " + result.analyzedRows() + " 行 / 全表 " + result.totalRows()
                    + " 行；统计方式：" + result.operation()
                    + (result.numericColumn() == null ? "" : "(" + result.numericColumn() + ")"));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 导出聚合结果为 CSV 文本（UTF-8 BOM，Excel 直接打开不乱码） */
    public String exportCsv(Long id, Long userId, String sheetName, String operation, String column,
                            String groupBy, String filterColumn, String filterValue) {
        AggregationResult result = aggregateStructuredFromRequest(id, userId, sheetName, operation, column,
                groupBy, filterColumn, filterValue);
        StringBuilder builder = new StringBuilder("\uFEFF");
        builder.append(String.join(",", result.headers())).append("\r\n");
        for (List<Object> row : result.rows()) {
            builder.append(row.stream().map(value -> escapeCsv(fmt(value)))
                    .collect(Collectors.joining(","))).append("\r\n");
        }
        builder.append("参与统计 ").append(result.analyzedRows()).append(" 行 / 全表 ")
                .append(result.totalRows()).append(" 行").append("\r\n");
        return builder.toString();
    }

    /** 导出入口：复用对话分析的完整校验与计算链路 */
    private AggregationResult aggregateStructuredFromRequest(Long id, Long userId, String sheetName, String operation,
                                                             String column, String groupBy,
                                                             String filterColumn, String filterValue) {
        AnalysisFile file = loadForUser(id, userId);
        String op = operation == null ? "count" : operation.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = List.of("count", "sum", "avg", "min", "max", "distinct");
        if (!allowed.contains(op)) {
            throw new BusinessException(400, "不支持的操作 " + operation + "，仅支持：" + String.join("/", allowed));
        }
        try (Workbook workbook = open(file.getFilePath())) {
            Sheet sheet = resolveSheet(workbook, sheetName);
            List<String> headers = readHeaders(sheet);
            List<Map<String, String>> rows = readRows(sheet, headers);
            String numericColumn = requireColumn(column, headers, "统计列", op.equals("count"));
            String groupColumn = requireColumn(groupBy, headers, "分组列", true);
            String filterCol = requireColumn(filterColumn, headers, "筛选列", true);
            List<Map<String, String>> filtered = rows;
            if (filterCol != null && filterValue != null && !filterValue.isBlank()) {
                String expected = filterValue.trim();
                filtered = rows.stream()
                        .filter(row -> expected.equalsIgnoreCase(row.getOrDefault(filterCol, "").trim()))
                        .toList();
            }
            return aggregateStructured(op, numericColumn, groupColumn, filtered, rows.size());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("导出聚合失败: {}", e.getMessage());
            throw new BusinessException(400, "表格文件读取失败，可能已被移动或删除");
        }
    }

    private String fmt(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** 计算聚合值：count 直接计数，其余按数值列计算（非数值行跳过） */
    private BigDecimal computeValue(String op, String numericColumn, List<Map<String, String>> rows) {
        if (op.equals("count")) {
            return BigDecimal.valueOf(rows.size());
        }
        List<BigDecimal> numbers = new ArrayList<>();
        for (Map<String, String> row : rows) {
            BigDecimal number = toNumber(row.getOrDefault(numericColumn, ""));
            if (number != null) {
                numbers.add(number);
            }
        }
        if (numbers.isEmpty()) {
            return BigDecimal.ZERO;
        }
        switch (op) {
            case "sum":
                return numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg":
                return numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(numbers.size()), 2, java.math.RoundingMode.HALF_UP);
            case "min":
                return numbers.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            case "max":
                return numbers.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            default:
                return BigDecimal.ZERO;
        }
    }

    /** 解析列名：宽松匹配（忽略大小写和空格），找不到时抛出带可用列清单的业务异常 */
    /** 解析列名：宽松匹配（忽略大小写和空格）；optional 为 true 时空值返回 null，找不到时抛出带可用列清单的异常 */
    private String requireColumn(String column, List<String> headers, String role, boolean optional) {
        if (column == null || column.isBlank()) {
            if (optional) {
                return null;
            }
            throw new BusinessException(400, role + "不能为空，可用列：" + String.join("、", headers));
        }
        String expected = column.trim().toLowerCase(Locale.ROOT);
        for (String header : headers) {
            if (header.toLowerCase(Locale.ROOT).equals(expected)) {
                return header;
            }
        }
        throw new BusinessException(400, "找不到" + role + "「" + column + "」，可用列：" + String.join("、", headers));
    }

    private Sheet resolveSheet(Workbook workbook, String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(sheetName.trim());
        if (sheet == null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            throw new BusinessException(400, "找不到 Sheet「" + sheetName + "」，可用：" + String.join("、", names));
        }
        return sheet;
    }

    private BigDecimal toNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isNumeric(String raw) {
        return toNumber(raw) != null;
    }

    private Workbook open(String path) throws Exception {
        InputStream in = Files.newInputStream(Paths.get(path));
        return new XSSFWorkbook(in);
    }

    private void quietDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            // 清理失败不影响主流程
        }
    }
}
