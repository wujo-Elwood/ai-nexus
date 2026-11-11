package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractExportRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 抽取导出记录 Mapper 接口
 */
@Mapper
public interface ExtractExportRecordMapper {

    /**
     * 新增抽取导出记录
     */
    int insert(ExtractExportRecord exportRecord);
}
