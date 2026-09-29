package com.rag.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 计算器工具：对数学表达式做安全求值
 * 自带递归下降解析器，只允许数字、四则运算、取余和括号，不执行任何动态代码
 */
@Component
public class CalculatorTool implements AgentTool {

    @Override
    public String name() {
        return "calculate";
    }

    @Override
    public String description() {
        return "计算数学表达式的值，支持加减速乘除、取余和括号，例如 (1000 * 0.149) + 25。用于汇率换算、百分比等精确计算。";
    }

    @Override
    public List<ToolParam> params() {
        return List.of(new ToolParam("expression", "string", "数学表达式，仅支持数字与 + - * / % ( )", true));
    }

    @Override
    public String execute(JsonNode arguments) {
        String expression = arguments.path("expression").asText("").trim();
        if (expression.isEmpty()) {
            return "参数错误：expression 不能为空";
        }
        try {
            String result = new ExpressionParser(expression).parse();
            return "计算结果：" + expression + " = " + result;
        } catch (ArithmeticException e) {
            return "计算失败：" + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "表达式无法计算：" + e.getMessage();
        }
    }

    /** 递归下降表达式解析器，仅支持数字与四则运算 */
    private static class ExpressionParser {
        private final String source;
        private int pos;

        ExpressionParser(String source) {
            this.source = source;
            this.pos = 0;
        }

        String parse() {
            double value = parseExpression();
            skipWhitespace();
            if (pos != source.length()) {
                throw new IllegalArgumentException("表达式在位置 " + (pos + 1) + " 附近存在无法识别的内容");
            }
            return format(value);
        }

        /** 表达式 = 项 (('+' | '-') 项)* */
        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    value += parseTerm();
                } else if (match('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        /** 项 = 因子 (('*' | '/' | '%') 因子)* */
        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    value *= parseFactor();
                } else if (match('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数不能为零");
                    }
                    value /= divisor;
                } else if (match('%')) {
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数不能为零");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        /** 因子 = 数字 | '(' 表达式 ')' | ('+' | '-') 因子 */
        private double parseFactor() {
            skipWhitespace();
            if (match('+')) {
                return parseFactor();
            }
            if (match('-')) {
                return -parseFactor();
            }
            if (match('(')) {
                double value = parseExpression();
                skipWhitespace();
                if (!match(')')) {
                    throw new IllegalArgumentException("括号不匹配");
                }
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWhitespace();
            int start = pos;
            while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("位置 " + (pos + 1) + " 处缺少数字");
            }
            try {
                return Double.parseDouble(source.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("「" + source.substring(start, pos) + "」不是有效数字");
            }
        }

        private boolean match(char expected) {
            skipWhitespace();
            if (pos < source.length() && source.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }

        /** 去掉多余的末尾 0，避免输出 149.02000000000001 这类浮点噪声 */
        private String format(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new ArithmeticException("计算结果溢出");
            }
            java.math.BigDecimal decimal = java.math.BigDecimal.valueOf(value).stripTrailingZeros();
            return decimal.toPlainString();
        }
    }
}
