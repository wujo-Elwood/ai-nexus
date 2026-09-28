package com.rag.mapper;

import com.rag.entity.KnowledgeGapReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 知识缺口报告 Mapper，负责报告快照读写。 */
@Mapper
public interface KnowledgeGapReportMapper {
    /** 按分析窗口查询最新报告。 */
    KnowledgeGapReport findByWindowDays(@Param("windowDays") int windowDays);

    /** 插入一份首次生成的报告。 */
    int insert(KnowledgeGapReport report);

    /** 标记窗口进入分析中状态，并保留最近一次成功报告内容。 */
    int markRunning(@Param("windowDays") int windowDays, @Param("generatedAt") LocalDateTime generatedAt);

    /** 更新成功报告及其统计信息。 */
    int updateSuccess(@Param("report") KnowledgeGapReport report);

    /** 更新失败状态但保留最近一次成功的 JSON。 */
    int updateFailure(@Param("windowDays") int windowDays, @Param("status") String status,
                      @Param("errorMessage") String errorMessage, @Param("generatedAt") LocalDateTime generatedAt);
}
