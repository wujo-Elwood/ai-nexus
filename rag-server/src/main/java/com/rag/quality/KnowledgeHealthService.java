package com.rag.quality;

import com.rag.entity.KbChunk;
import com.rag.entity.KbFile;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.FileMapper;
import com.rag.service.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** 计算知识库质量评分，不调用大模型，只使用文件和切片元数据。 */
@Service
public class KnowledgeHealthService {
    @Autowired private FileMapper fileMapper;
    @Autowired private ChunkMapper chunkMapper;
    @Autowired private KnowledgeBaseService knowledgeBaseService;

    /** 计算并返回知识库健康指标。 */
    public Map<String,Object> calculate(Long kbId, Long userId) {
        knowledgeBaseService.checkAccess(kbId, userId);
        List<KbFile> files = fileMapper.findCurrentByKbId(kbId);
        int emptyFiles = 0, failedFiles = 0, vectorMissing = 0;
        Map<String,Integer> hashCount = new HashMap<>();
        for (KbFile file : files) {
            if (file.getFileSize() == null || file.getFileSize() == 0) emptyFiles++;
            if ("FAILED".equalsIgnoreCase(file.getStatus())) failedFiles++;
            if (!"READY".equalsIgnoreCase(file.getVectorStatus()) && "COMPLETED".equalsIgnoreCase(file.getStatus())) vectorMissing++;
            if (file.getFileSha256() != null) hashCount.merge(file.getFileSha256(), 1, Integer::sum);
        }
        int duplicateFiles = (int) hashCount.values().stream().filter(value -> value > 1).count();
        List<KbChunk> chunks = chunkMapper.findByKbId(kbId);
        int lowQualityChunks = 0, duplicateChunks = 0;
        Set<String> contents = new HashSet<>();
        for (KbChunk chunk : chunks) {
            String content = chunk.getContent() == null ? "" : chunk.getContent().trim();
            if (content.length() < 20) lowQualityChunks++;
            if (!contents.add(content) && !content.isEmpty()) duplicateChunks++;
        }
        int totalFiles = Math.max(files.size(), 1);
        int totalChunks = Math.max(chunks.size(), 1);
        double score = 100D;
        score -= Math.min(25D, emptyFiles * 25D / totalFiles);
        score -= Math.min(20D, failedFiles * 20D / totalFiles);
        score -= Math.min(20D, vectorMissing * 20D / totalFiles);
        score -= Math.min(15D, lowQualityChunks * 15D / totalChunks);
        score -= Math.min(10D, duplicateChunks * 10D / totalChunks);
        score -= Math.min(10D, duplicateFiles * 10D / totalFiles);
        score = Math.max(0D, Math.min(100D, score));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("formulaVersion", "kb-health-v1");
        result.put("score", Math.round(score * 100D) / 100D);
        result.put("level", score >= 85 ? "HEALTHY" : score >= 60 ? "WARNING" : "CRITICAL");
        result.put("fileCount", files.size()); result.put("chunkCount", chunks.size());
        result.put("emptyFiles", emptyFiles); result.put("lowQualityChunks", lowQualityChunks);
        result.put("duplicateFiles", duplicateFiles); result.put("duplicateChunks", duplicateChunks);
        result.put("vectorMissing", vectorMissing); result.put("processingFailed", failedFiles);
        result.put("calculatedAt", java.time.LocalDateTime.now());
        return result;
    }
}
