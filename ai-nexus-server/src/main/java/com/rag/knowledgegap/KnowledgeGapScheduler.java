package com.rag.knowledgegap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 知识缺口报告定时生成器，每日凌晨刷新两个固定窗口。 */
@Slf4j
@Component
public class KnowledgeGapScheduler {
    private final KnowledgeGapAnalysisService analysisService;

    /** 创建知识缺口报告定时生成器。 */
    public KnowledgeGapScheduler(KnowledgeGapAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** 每日凌晨三点依次刷新 7 天和 30 天报告。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void generateDailyReports() {
        // 先生成短窗口报告，再生成长窗口报告；单个窗口失败不影响另一个窗口
        generateReport(7);
        generateReport(30);
    }

    /** 生成一个窗口报告并记录异常。 */
    private void generateReport(int days) {
        try {
            analysisService.analyzeWindow(days);
        } catch (Exception e) {
            log.error("Knowledge gap scheduled analysis failed, window={}", days, e);
        }
    }
}
