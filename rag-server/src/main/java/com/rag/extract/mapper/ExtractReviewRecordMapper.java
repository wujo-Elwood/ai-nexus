package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractReviewRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 抽取复核记录 Mapper 接口
 */
@Mapper
public interface ExtractReviewRecordMapper {

    /**
     * 新增抽取复核记录
     */
    int insert(ExtractReviewRecord reviewRecord);
}
