package com.rag.mapper;

import com.rag.entity.AnswerQuality;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 回答质量事实 Mapper，负责记录和查询知识缺口分析样本。 */
@Mapper
public interface AnswerQualityMapper {
    /** 保存一条回答质量事实。 */
    int insert(AnswerQuality quality);

    /** 查询窗口内需要关注的回答质量样本。 */
    List<AnswerQuality> findSamples(@Param("from") LocalDateTime from);
}
