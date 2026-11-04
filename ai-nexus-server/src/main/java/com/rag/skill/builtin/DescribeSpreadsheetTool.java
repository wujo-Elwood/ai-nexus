package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.analysis.service.SpreadsheetService;
import com.rag.skill.core.AgentTool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表格结构描述技能：返回 Excel 的 Sheet/列/类型/行数与前几行样例，
 * 让模型先理解数据再决定怎么做统计；仅能访问当前用户上传的表格
 */
@Component
public class DescribeSpreadsheetTool implements AgentTool {

    private final SpreadsheetService spreadsheetService;

    public DescribeSpreadsheetTool(SpreadsheetService spreadsheetService) {
        this.spreadsheetService = spreadsheetService;
    }

    @Override
    public String name() {
        return "describe_spreadsheet";
    }

    @Override
    public String description() {
        return "查看用户上传的 Excel 表格结构：Sheet 列表、列名与类型、行数和前几行样例。"
                + "做任何数据统计前必须先调用本技能了解列结构。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(
                new ToolParam("fileId", "number", "分析文件编号，来自用户消息中的【当前分析文件 ID: N】", true),
                new ToolParam("sheet", "string", "Sheet 名称，留空查看全部 Sheet", false));
    }

    @Override
    public String execute(JsonNode arguments) {
        // 第1步：读取用户身份与参数
        long userId = arguments.path("userId").asLong(0);
        if (userId <= 0) {
            return "未获取到用户身份，无法读取表格";
        }
        long fileId = arguments.path("fileId").asLong(0);
        if (fileId <= 0) {
            return "缺少文件编号 fileId，请从用户消息的【当前分析文件 ID: N】中获取";
        }
        // 第2步：业务错误作为文本返回，模型可据此调整
        try {
            return spreadsheetService.describe(fileId, userId);
        } catch (Exception e) {
            return "读取表格失败：" + (e.getMessage() == null ? "未知原因" : e.getMessage());
        }
    }
}
