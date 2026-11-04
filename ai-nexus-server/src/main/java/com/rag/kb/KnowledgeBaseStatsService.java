package com.rag.kb;

import com.rag.common.BusinessException;
import com.rag.mapper.FileMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.service.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库统计服务，聚合文件、切片、向量和处理质量指标。
 */
@Service
public class KnowledgeBaseStatsService {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private FileMapper fileMapper;

    /** 查询指定知识库的统计概览 */
    public Map<String, Object> getStats(Long kbId, Long userId) {
        knowledgeBaseService.checkAccess(kbId, userId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("kbId", kbId);
        stats.put("fileCount", knowledgeBaseMapper.countCurrentFiles(kbId));
        stats.put("totalBytes", knowledgeBaseMapper.sumCurrentFileBytes(kbId));
        stats.put("chunkCount", knowledgeBaseMapper.countChunks(kbId));
        stats.put("vectorReadyCount", knowledgeBaseMapper.countVectorReadyFiles(kbId));
        stats.put("processingFailureCount", knowledgeBaseMapper.countFailedFiles(kbId));
        stats.put("duplicateFileCount", knowledgeBaseMapper.countDuplicateFiles(kbId));
        stats.put("lowQualityChunkCount", knowledgeBaseMapper.countLowQualityChunks(kbId));
        stats.put("recentFiles", knowledgeBaseMapper.findRecentFiles(kbId, 8));
        stats.put("popularFiles", knowledgeBaseMapper.findPopularFiles(kbId, 8));
        stats.put("healthScore", calculateHealthScore(stats));
        return stats;
    }

    /** 计算知识库健康分，所有指标为空时保持满分 */
    private int calculateHealthScore(Map<String, Object> stats) {
        int fileCount = number(stats.get("fileCount"));
        int chunkCount = number(stats.get("chunkCount"));
        int failed = number(stats.get("processingFailureCount"));
        int duplicate = number(stats.get("duplicateFileCount"));
        int lowQuality = number(stats.get("lowQualityChunkCount"));
        int vectorMissing = Math.max(0, fileCount - number(stats.get("vectorReadyCount")));
        double penalty = ratio(failed, fileCount) * 28 + ratio(duplicate, fileCount) * 18
                + ratio(lowQuality, chunkCount) * 20 + ratio(vectorMissing, fileCount) * 34;
        return Math.max(0, Math.min(100, (int) Math.round(100 - penalty)));
    }

    /** 将聚合结果安全转换为整数 */
    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 计算比例并避免零分母 */
    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0 : Math.min(1d, (double) numerator / denominator);
    }
}
