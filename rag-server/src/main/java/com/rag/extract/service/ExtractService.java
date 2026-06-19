package com.rag.extract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.extract.entity.ExtractDocument;
import com.rag.extract.entity.ExtractExportRecord;
import com.rag.extract.entity.ExtractField;
import com.rag.extract.entity.ExtractResult;
import com.rag.extract.entity.ExtractReviewRecord;
import com.rag.extract.entity.ExtractTask;
import com.rag.extract.entity.ExtractTemplate;
import com.rag.extract.mapper.ExtractDocumentMapper;
import com.rag.extract.mapper.ExtractExportRecordMapper;
import com.rag.extract.mapper.ExtractFieldMapper;
import com.rag.extract.mapper.ExtractResultMapper;
import com.rag.extract.mapper.ExtractReviewRecordMapper;
import com.rag.extract.mapper.ExtractTaskMapper;
import com.rag.extract.mapper.ExtractTemplateMapper;
import com.rag.rag.DocumentParser;
import com.rag.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文档抽取服务
 */
@Slf4j
@Service
public class ExtractService {

    /**
     * 文档 Mapper
     */
    @Autowired
    private ExtractDocumentMapper extractDocumentMapper;

    /**
     * 模板 Mapper
     */
    @Autowired
    private ExtractTemplateMapper extractTemplateMapper;

    /**
     * 字段 Mapper
     */
    @Autowired
    private ExtractFieldMapper extractFieldMapper;

    /**
     * 任务 Mapper
     */
    @Autowired
    private ExtractTaskMapper extractTaskMapper;

    /**
     * 结果 Mapper
     */
    @Autowired
    private ExtractResultMapper extractResultMapper;

    /**
     * 复核记录 Mapper
     */
    @Autowired
    private ExtractReviewRecordMapper extractReviewRecordMapper;

    /**
     * 导出记录 Mapper
     */
    @Autowired
    private ExtractExportRecordMapper extractExportRecordMapper;

    /**
     * 文档解析器
     */
    @Autowired
    private DocumentParser documentParser;

    /**
     * 模型供应商服务
     */
    @Autowired
    private ModelProviderService modelProviderService;

    /**
     * 文件上传目录
     */
    @Value("${file.upload-dir}")
    private String uploadDir;

    /**
     * JSON 工具
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 允许上传的 MIME 类型
     */
    private static final Set<String> allowedMimeTypes = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    /**
     * 允许上传的文件扩展名
     */
    private static final Set<String> allowedExtensions = Set.of("pdf", "doc", "docx");

    /**
     * 查询启用模板列表
     */
    public List<Map<String, Object>> getTemplates() {
        // 第1步：查询所有启用模板
        List<ExtractTemplate> templates = extractTemplateMapper.findEnabled();
        // 第2步：组装前端友好的 Map 结果
        List<Map<String, Object>> templateMaps = new ArrayList<>();
        for (ExtractTemplate template : templates) {
            Map<String, Object> templateMap = new HashMap<>();
            templateMap.put("id", template.getId());
            templateMap.put("templateName", template.getTemplateName());
            templateMap.put("templateCode", template.getTemplateCode());
            templateMap.put("documentType", template.getDocumentType());
            templateMap.put("description", template.getDescription());
            templateMap.put("enabled", template.getEnabled());
            templateMap.put("createTime", template.getCreateTime());
            templateMaps.add(templateMap);
        }
        return templateMaps;
    }

    /**
     * 上传抽取文档
     */
    @Transactional
    public ExtractDocument uploadDocument(MultipartFile file, Long userId) {
        // 第1步：校验文件是否为空
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        // 第2步：校验文件类型是否允许
        String originalFileName = file.getOriginalFilename();
        String fileExtension = getFileExtension(originalFileName);
        String contentType = normalizeContentType(file.getContentType());
        if (!allowedMimeTypes.contains(contentType) && !allowedExtensions.contains(fileExtension)) {
            throw new BusinessException(400, "仅支持 PDF、DOC、DOCX 文件");
        }
        try {
            // 第3步：创建 extract 上传目录
            Path extractUploadPath = Paths.get(uploadDir).resolve("extract");
            Files.createDirectories(extractUploadPath);
            // 第4步：保存上传文件到磁盘
            String safeFileName = UUID.randomUUID() + "_" + cleanFileName(originalFileName);
            Path filePath = extractUploadPath.resolve(safeFileName);
            file.transferTo(filePath.toFile());
            // 第5步：写入文档记录
            ExtractDocument document = new ExtractDocument();
            document.setFileName(originalFileName);
            document.setFileType(contentType.isBlank() ? fileExtension : contentType);
            document.setFileSize(file.getSize());
            document.setFilePath(filePath.toString());
            document.setParseStatus("UPLOADED");
            document.setPageCount(0);
            document.setFullText("");
            document.setUploadedBy(userId);
            extractDocumentMapper.insert(document);
            return document;
        } catch (Exception e) {
            throw new BusinessException(500, "保存上传文件失败：" + e.getMessage());
        }
    }

    /**
     * 创建并同步执行抽取任务
     */
    @Transactional
    public ExtractTask createTask(Long documentId, Long templateId, Long userId) {
        // 第1步：校验文档归属和模板存在
        ExtractDocument document = requireDocument(documentId, userId);
        ExtractTemplate template = requireTemplate(templateId);
        List<ExtractField> fields = extractFieldMapper.findByTemplateId(template.getId());
        if (fields.isEmpty()) {
            throw new BusinessException(400, "模板字段不能为空");
        }
        // 第2步：创建待处理任务
        ExtractTask task = new ExtractTask();
        task.setDocumentId(document.getId());
        task.setTemplateId(template.getId());
        task.setTaskStatus("PENDING");
        task.setTaskMessage("任务已创建");
        task.setCreatedBy(userId);
        extractTaskMapper.insert(task);
        try {
            // 第3步：解析文档内容
            extractTaskMapper.markStarted(task.getId(), "PARSING");
            File sourceFile = new File(document.getFilePath());
            String fullText = documentParser.parse(sourceFile);
            extractDocumentMapper.updateParsed(document.getId(), fullText, 1);
            document.setFullText(fullText);
            document.setPageCount(1);
            // 第4步：调用模型抽取字段
            extractTaskMapper.updateStatus(task.getId(), "EXTRACTING", "正在抽取字段");
            JsonNode fieldValues = callModelForExtraction(template, fields, fullText);
            List<ExtractResult> results = buildResults(task, document, fields, fieldValues);
            if (results.isEmpty()) {
                throw new BusinessException(500, "抽取结果不能为空");
            }
            extractResultMapper.insertBatch(results);
            // 第5步：标记任务进入复核状态
            extractTaskMapper.markFinished(task.getId(), "REVIEWING", "抽取完成，等待复核");
            task.setTaskStatus("REVIEWING");
            task.setTaskMessage("抽取完成，等待复核");
            return task;
        } catch (Exception e) {
            // 第6步：失败时标记任务和文档状态
            log.error("文档抽取任务失败", e);
            extractTaskMapper.markFinished(task.getId(), "FAILED", e.getMessage());
            extractDocumentMapper.updateStatus(document.getId(), "FAILED");
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(500, "文档抽取失败：" + e.getMessage());
        }
    }

    /**
     * 查询用户任务列表
     */
    public List<Map<String, Object>> listTasks(Long userId) {
        // 第1步：查询当前用户任务
        List<ExtractTask> tasks = extractTaskMapper.findByUser(userId);
        // 第2步：补充文档名和模板名
        List<Map<String, Object>> taskMaps = new ArrayList<>();
        for (ExtractTask task : tasks) {
            Map<String, Object> taskMap = new HashMap<>();
            taskMap.put("id", task.getId());
            taskMap.put("documentId", task.getDocumentId());
            taskMap.put("templateId", task.getTemplateId());
            taskMap.put("taskStatus", task.getTaskStatus());
            taskMap.put("taskMessage", task.getTaskMessage());
            taskMap.put("startedAt", task.getStartedAt());
            taskMap.put("finishedAt", task.getFinishedAt());
            taskMap.put("createdBy", task.getCreatedBy());
            taskMap.put("createTime", task.getCreateTime());
            ExtractDocument document = extractDocumentMapper.findById(task.getDocumentId());
            ExtractTemplate template = extractTemplateMapper.findById(task.getTemplateId());
            taskMap.put("documentName", document == null ? "" : document.getFileName());
            taskMap.put("templateName", template == null ? "" : template.getTemplateName());
            taskMaps.add(taskMap);
        }
        return taskMaps;
    }

    /**
     * 查询用户任务详情
     */
    public ExtractTask getTask(Long taskId, Long userId) {
        // 第1步：按任务和用户查询
        ExtractTask task = extractTaskMapper.findByIdAndUser(taskId, userId);
        // 第2步：不存在时返回业务异常
        if (task == null) {
            throw new BusinessException(404, "抽取任务不存在");
        }
        return task;
    }

    /**
     * 查询任务抽取结果
     */
    public List<ExtractResult> getTaskResults(Long taskId, Long userId) {
        // 第1步：校验任务权限
        getTask(taskId, userId);
        // 第2步：查询任务结果
        return extractResultMapper.findByTaskId(taskId);
    }

    /**
     * 更新人工复核结果
     */
    @Transactional
    public void updateResult(Long resultId, String newValue, String remark, Long userId) {
        // 第1步：校验结果权限
        ExtractResult result = extractResultMapper.findByIdAndUser(resultId, userId);
        if (result == null) {
            throw new BusinessException(404, "抽取结果不存在");
        }
        // 第2步：记录复核日志
        ExtractReviewRecord reviewRecord = new ExtractReviewRecord();
        reviewRecord.setResultId(result.getId());
        reviewRecord.setOldValue(result.getFieldValue());
        reviewRecord.setNewValue(newValue);
        reviewRecord.setReviewBy(userId);
        reviewRecord.setRemark(remark);
        extractReviewRecordMapper.insert(reviewRecord);
        // 第3步：更新人工值
        extractResultMapper.updateManualValue(result.getId(), newValue);
    }

    /**
     * 导出任务抽取结果
     */
    @Transactional
    public ResponseEntity<?> exportTask(Long taskId, String exportType, Long userId) {
        // 第1步：校验任务权限并查询结果
        ExtractTask task = getTask(taskId, userId);
        List<ExtractResult> results = extractResultMapper.findByTaskId(task.getId());
        String normalizedExportType = exportType == null ? "json" : exportType.toLowerCase(Locale.ROOT);
        // 第2步：按导出类型生成文件
        byte[] exportBytes;
        MediaType mediaType;
        String fileName;
        if ("excel".equals(normalizedExportType) || "xlsx".equals(normalizedExportType)) {
            exportBytes = buildExcelBytes(results);
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            fileName = "extract-task-" + task.getId() + ".xlsx";
            normalizedExportType = "excel";
        } else if ("json".equals(normalizedExportType)) {
            exportBytes = buildJsonBytes(results);
            mediaType = MediaType.APPLICATION_JSON;
            fileName = "extract-task-" + task.getId() + ".json";
        } else {
            throw new BusinessException(400, "仅支持 json 或 excel 导出");
        }
        // 第3步：插入导出记录
        ExtractExportRecord exportRecord = new ExtractExportRecord();
        exportRecord.setTaskId(task.getId());
        exportRecord.setExportType(normalizedExportType);
        exportRecord.setExportPath(fileName);
        exportRecord.setExportedBy(userId);
        extractExportRecordMapper.insert(exportRecord);
        // 第4步：返回下载响应
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(exportBytes);
    }

    /**
     * 校验并获取用户文档
     */
    private ExtractDocument requireDocument(Long documentId, Long userId) {
        // 第1步：按文档和用户查询
        ExtractDocument document = extractDocumentMapper.findByIdAndUser(documentId, userId);
        // 第2步：不存在时抛出异常
        if (document == null) {
            throw new BusinessException(404, "抽取文档不存在");
        }
        return document;
    }

    /**
     * 校验并获取模板
     */
    private ExtractTemplate requireTemplate(Long templateId) {
        // 第1步：按模板主键查询
        ExtractTemplate template = extractTemplateMapper.findById(templateId);
        // 第2步：不存在时抛出异常
        if (template == null) {
            throw new BusinessException(404, "抽取模板不存在");
        }
        return template;
    }

    /**
     * 调用模型抽取字段
     */
    private JsonNode callModelForExtraction(ExtractTemplate template, List<ExtractField> fields, String fullText) throws Exception {
        // 第1步：读取当前激活模型供应商
        ModelProvider provider = modelProviderService.getActive();
        // 第2步：构造模型请求并发送
        String requestBody = buildModelRequest(template, fields, fullText, provider);
        String endpoint = buildEndpoint(provider);
        HttpURLConnection connection = openConnection(endpoint, provider);
        connection.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
        if (connection.getResponseCode() >= 400) {
            String errorText = readConnectionText(connection, true);
            connection.disconnect();
            throw new BusinessException(500, "模型抽取失败：" + errorText);
        }
        // 第3步：解析模型响应正文
        String responseText = readConnectionText(connection, false);
        connection.disconnect();
        JsonNode responseNode = objectMapper.readTree(responseText);
        String content = responseNode.path("choices").path(0).path("message").path("content").asText("");
        String cleanJson = cleanModelJson(content);
        JsonNode resultNode = objectMapper.readTree(cleanJson);
        JsonNode fieldsNode = resultNode.path("fields");
        if (!fieldsNode.isArray()) {
            throw new BusinessException(500, "模型返回 JSON 缺少 fields 数组");
        }
        return fieldsNode;
    }

    /**
     * 构造模型请求体
     */
    private String buildModelRequest(ExtractTemplate template, List<ExtractField> fields, String fullText, ModelProvider provider) throws Exception {
        // 第1步：创建请求根节点
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", provider.getModel());
        body.put("temperature", 0.1);
        body.put("max_tokens", 2000);
        // 第2步：写入消息内容
        ArrayNode messages = body.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", "你是文档字段抽取助手，只能返回 JSON，不能返回解释文字。JSON 格式必须为 {\"fields\":[{\"fieldCode\":\"\",\"fieldValue\":\"\",\"rawText\":\"\",\"pageNo\":1,\"confidence\":0.0}]}。");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", buildExtractionPrompt(template, fields, fullText));
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 构造字段抽取提示词
     */
    private String buildExtractionPrompt(ExtractTemplate template, List<ExtractField> fields, String fullText) {
        // 第1步：拼接模板和字段要求
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请从文档中抽取模板字段。\n");
        promptBuilder.append("模板名称：").append(template.getTemplateName()).append("\n");
        promptBuilder.append("字段列表：\n");
        for (ExtractField field : fields) {
            promptBuilder.append("- fieldCode: ").append(field.getFieldCode())
                    .append(", fieldName: ").append(field.getFieldName())
                    .append(", required: ").append(Boolean.TRUE.equals(field.getRequired()))
                    .append(", prompt: ").append(field.getFieldPrompt() == null ? "" : field.getFieldPrompt())
                    .append(", example: ").append(field.getExampleValue() == null ? "" : field.getExampleValue())
                    .append("\n");
        }
        // 第2步：拼接输出要求和文档全文
        promptBuilder.append("只返回 JSON，不要使用 Markdown，不要遗漏 fields 数组。\n");
        promptBuilder.append("文档内容：\n");
        promptBuilder.append(fullText == null ? "" : fullText);
        return promptBuilder.toString();
    }

    /**
     * 构造 OpenAI 兼容接口地址
     */
    private String buildEndpoint(ModelProvider provider) {
        // 第1步：规范化基础地址
        String baseUrl = provider.getBaseUrl().replaceAll("/+$", "");
        // 第2步：补齐 chat completions 路径
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        return baseUrl + "/v1/chat/completions";
    }

    /**
     * 打开模型请求连接
     */
    private HttpURLConnection openConnection(String endpoint, ModelProvider provider) throws Exception {
        // 第1步：创建 HTTP 连接
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        // 第2步：设置请求头和超时时间
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + provider.getApiKey());
        connection.setDoOutput(true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        return connection;
    }

    /**
     * 读取 HTTP 响应文本
     */
    private String readConnectionText(HttpURLConnection connection, boolean errorStream) throws Exception {
        // 第1步：选择正确的响应流
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder textBuilder = new StringBuilder();
            String line;
            // 第2步：逐行读取响应内容
            while ((line = reader.readLine()) != null) {
                textBuilder.append(line);
            }
            return textBuilder.toString();
        }
    }

    /**
     * 清理模型返回 JSON
     */
    private String cleanModelJson(String content) {
        // 第1步：处理 markdown json 代码块
        String trimmedContent = content == null ? "" : content.trim();
        if (trimmedContent.startsWith("```json")) {
            String withoutStart = trimmedContent.substring("```json".length()).trim();
            int fenceEndIndex = withoutStart.lastIndexOf("```");
            return fenceEndIndex >= 0 ? withoutStart.substring(0, fenceEndIndex).trim() : withoutStart;
        }
        if (trimmedContent.startsWith("```")) {
            String withoutStart = trimmedContent.substring("```".length()).trim();
            int fenceEndIndex = withoutStart.lastIndexOf("```");
            return fenceEndIndex >= 0 ? withoutStart.substring(0, fenceEndIndex).trim() : withoutStart;
        }
        // 第2步：截取第一个对象到最后一个对象
        int startIndex = trimmedContent.indexOf('{');
        int endIndex = trimmedContent.lastIndexOf('}');
        if (startIndex >= 0 && endIndex > startIndex) {
            return trimmedContent.substring(startIndex, endIndex + 1);
        }
        return trimmedContent;
    }

    /**
     * 构造抽取结果列表
     */
    private List<ExtractResult> buildResults(ExtractTask task, ExtractDocument document, List<ExtractField> fields, JsonNode fieldsNode) {
        // 第1步：把模型返回按字段编码放入 Map
        Map<String, JsonNode> valueNodeMap = new HashMap<>();
        for (JsonNode fieldNode : fieldsNode) {
            String fieldCode = fieldNode.path("fieldCode").asText("");
            if (!fieldCode.isBlank()) {
                valueNodeMap.put(fieldCode, fieldNode);
            }
        }
        // 第2步：为模板每个字段生成结果
        List<ExtractResult> results = new ArrayList<>();
        for (ExtractField field : fields) {
            JsonNode valueNode = valueNodeMap.get(field.getFieldCode());
            ExtractResult result = new ExtractResult();
            result.setTaskId(task.getId());
            result.setDocumentId(document.getId());
            result.setFieldId(field.getId());
            result.setFieldCode(field.getFieldCode());
            result.setFieldName(field.getFieldName());
            result.setFieldValue(valueNode == null ? "" : valueNode.path("fieldValue").asText(""));
            result.setRawText(valueNode == null ? "" : valueNode.path("rawText").asText(""));
            result.setPageNo(valueNode == null ? 1 : valueNode.path("pageNo").asInt(1));
            result.setConfidence(readConfidence(valueNode));
            result.setResultStatus(valueNode == null ? "MISSING" : calculateResultStatus(field, result));
            result.setIsModified(false);
            results.add(result);
        }
        return results;
    }

    /**
     * 读取置信度
     */
    private BigDecimal readConfidence(JsonNode valueNode) {
        // 第1步：模型缺字段时返回默认值
        if (valueNode == null || valueNode.path("confidence").isMissingNode()) {
            return BigDecimal.ZERO;
        }
        // 第2步：把数字转为 BigDecimal
        return BigDecimal.valueOf(valueNode.path("confidence").asDouble(0));
    }

    /**
     * 计算抽取结果状态
     */
    private String calculateResultStatus(ExtractField field, ExtractResult result) {
        // 第1步：必填字段为空时标记缺失
        String fieldValue = result.getFieldValue();
        if (Boolean.TRUE.equals(field.getRequired()) && (fieldValue == null || fieldValue.isBlank())) {
            return "MISSING";
        }
        // 第2步：正则不匹配时标记错误
        String regexRule = field.getRegexRule();
        if (regexRule != null && !regexRule.isBlank() && fieldValue != null && !fieldValue.isBlank() && !Pattern.matches(regexRule, fieldValue)) {
            return "ERROR";
        }
        // 第3步：置信度低于阈值时标记警告
        BigDecimal threshold = field.getConfidenceThreshold();
        if (threshold != null && result.getConfidence().compareTo(threshold) < 0) {
            return "WARNING";
        }
        // 第4步：其他情况标记成功
        return "SUCCESS";
    }

    /**
     * 构造 JSON 导出字节
     */
    private byte[] buildJsonBytes(List<ExtractResult> results) {
        try {
            // 第1步：序列化结果列表
            return objectMapper.writeValueAsBytes(results);
        } catch (Exception e) {
            // 第2步：序列化失败时抛出业务异常
            throw new BusinessException(500, "生成 JSON 导出失败：" + e.getMessage());
        }
    }

    /**
     * 构造 Excel 导出字节
     */
    private byte[] buildExcelBytes(List<ExtractResult> results) {
        // 第1步：创建工作簿和表头
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("抽取结果");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"字段编码", "字段名称", "字段值", "原文片段", "页码", "置信度", "状态"};
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            // 第2步：写入抽取结果行
            for (int i = 0; i < results.size(); i++) {
                ExtractResult result = results.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(nullToEmpty(result.getFieldCode()));
                row.createCell(1).setCellValue(nullToEmpty(result.getFieldName()));
                row.createCell(2).setCellValue(nullToEmpty(result.getFieldValue()));
                row.createCell(3).setCellValue(nullToEmpty(result.getRawText()));
                row.createCell(4).setCellValue(result.getPageNo() == null ? 1 : result.getPageNo());
                row.createCell(5).setCellValue(result.getConfidence() == null ? "0" : result.getConfidence().toPlainString());
                row.createCell(6).setCellValue(nullToEmpty(result.getResultStatus()));
            }
            // 第3步：自动调整列宽并输出字节
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            // 第4步：生成失败时抛出业务异常
            throw new BusinessException(500, "生成 Excel 导出失败：" + e.getMessage());
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        // 第1步：处理空文件名
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        // 第2步：截取最后一个点后的扩展名
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化内容类型
     */
    private String normalizeContentType(String contentType) {
        // 第1步：处理空内容类型
        if (contentType == null) {
            return "";
        }
        // 第2步：去掉 charset 等附加参数
        return contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 清理文件名
     */
    private String cleanFileName(String fileName) {
        // 第1步：处理空文件名
        String safeName = fileName == null || fileName.isBlank() ? "document" : fileName;
        // 第2步：移除路径分隔符
        return safeName.replace("\\", "_").replace("/", "_");
    }

    /**
     * 空字符串兜底
     */
    private String nullToEmpty(String value) {
        // 第1步：把空值转换为空字符串
        return value == null ? "" : value;
    }
}
