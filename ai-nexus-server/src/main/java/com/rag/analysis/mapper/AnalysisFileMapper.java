package com.rag.analysis.mapper;

import com.rag.analysis.entity.AnalysisFile;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据分析-上传表格 Mapper
 */
public interface AnalysisFileMapper {

    int insert(AnalysisFile file);

    /** 按 ID 与用户查询（用户隔离） */
    AnalysisFile findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 查询用户的表格列表（最近的在前） */
    List<AnalysisFile> findByUserId(@Param("userId") Long userId);

    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
