package com.rag.analysis.controller;

import com.rag.analysis.entity.AnalysisFile;
import com.rag.analysis.service.SpreadsheetService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据分析控制器
 * 提供 Excel 上传、列表、表结构详情和删除接口；行级分析通过技能在对话中完成
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final SpreadsheetService spreadsheetService;

    public AnalysisController(SpreadsheetService spreadsheetService) {
        this.spreadsheetService = spreadsheetService;
    }

    /**
     * 上传并解析表格（目前支持 .xlsx）
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(spreadsheetService.upload(file, userId));
    }

    /**
     * 当前用户的表格列表
     */
    @GetMapping
    public Result<List<AnalysisFile>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(spreadsheetService.listByUser(userId));
    }

    /**
     * 表格详情（含表结构）
     */
    @GetMapping("/{id}")
    public Result<AnalysisFile> detail(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(spreadsheetService.loadForUser(id, userId));
    }

    /**
     * 删除表格（登记与文件一并清理）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        spreadsheetService.delete(id, userId);
        return Result.success();
    }

    /**
     * 导出分析结果：后端按相同参数重算聚合，输出 xlsx 或 csv（带 BOM）
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam String operation,
                                         @RequestParam(required = false) String column,
                                         @RequestParam(required = false) String sheet,
                                         @RequestParam(required = false) String groupBy,
                                         @RequestParam(required = false) String filterColumn,
                                         @RequestParam(required = false) String filterValue,
                                         HttpServletRequest request) throws Exception {
        // 第1步：按格式生成文件字节
        Long userId = (Long) request.getAttribute("userId");
        boolean excel = !"csv".equalsIgnoreCase(format);
        String fileName = "分析结果." + (excel ? "xlsx" : "csv");
        byte[] body = excel
                ? spreadsheetService.exportExcel(id, userId, sheet, operation, column, groupBy, filterColumn, filterValue)
                : spreadsheetService.exportCsv(id, userId, sheet, operation, column, groupBy, filterColumn, filterValue)
                        .getBytes(StandardCharsets.UTF_8);
        // 第2步：按下载响应输出
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(excel
                        ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }
}
