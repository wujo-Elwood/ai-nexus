package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.analysis.service.SpreadsheetService;
import com.rag.skill.core.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表格聚合分析技能：在服务端做确定性统计（count/sum/avg/min/max/distinct，支持分组与筛选），
 * 模型只拿汇总结果；列名写错时会返回可用列清单，模型可自行纠正后重试
 */
@Component
public class AnalyzeSpreadsheetTool implements AgentTool {

    private final SpreadsheetService spreadsheetService;

    public AnalyzeSpreadsheetTool(SpreadsheetService spreadsheetService) {
        this.spreadsheetService = spreadsheetService;
    }

    @Override
    public String name() {
        return "analyze_spreadsheet";
    }

    @Override
    public String description() {
        return "对用户上传的 Excel 表格执行确定性统计：count/sum/avg/min/max/distinct，"
                + "支持按某列分组、按某列等值筛选。回答求和、平均、最大最小、分组排名等数据问题时调用。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("fileId", "number", "分析文件编号，来自用户消息中的【当前分析文件 ID: N】", true),
                new ToolParam("operation", "string", "统计操作：count/sum/avg/min/max/distinct", true),
                new ToolParam("column", "string", "统计的目标列名；count 可不传", false),
                new ToolParam("sheet", "string", "Sheet 名称，留空用第一个 Sheet", false),
                new ToolParam("groupBy", "string", "分组列名，可选；分组后输出每组统计值并降序排列", false),
                new ToolParam("filterColumn", "string", "筛选列名，可选；与 filterValue 配合做等值筛选", false),
                new ToolParam("filterValue", "string", "筛选值，可选", false));
    }

    @Override
    public String execute(JsonNode arguments) {
        // 第1步：读取用户身份与参数
        long userId = arguments.path("userId").asLong(0);
        if (userId <= 0) {
            return "未获取到用户身份，无法分析表格";
        }
        long fileId = arguments.path("fileId").asLong(0);
        if (fileId <= 0) {
            return "缺少文件编号 fileId，请从用户消息的【当前分析文件 ID: N】中获取";
        }
        String operation = arguments.path("operation").asText("count");
        // 第2步：业务错误作为文本返回，模型可据此修正参数重试
        try {
            return spreadsheetService.analyze(fileId, userId,
                    textOrNull(arguments, "sheet"),
                    operation,
                    textOrNull(arguments, "column"),
                    textOrNull(arguments, "groupBy"),
                    textOrNull(arguments, "filterColumn"),
                    textOrNull(arguments, "filterValue"));
        } catch (Exception e) {
            return "分析失败：" + (e.getMessage() == null ? "未知原因" : e.getMessage());
        }
    }

    private String textOrNull(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("");
        return value.isBlank() ? null : value.trim();
    }
}
