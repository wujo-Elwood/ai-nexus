package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽取模板 Mapper 接口
 */
@Mapper
public interface ExtractTemplateMapper {

    /**
     * 查询启用的抽取模板
     */
    List<ExtractTemplate> findEnabled();

    /**
     * 根据模板主键查询模板
     */
    ExtractTemplate findById(@Param("id") Long id);
}
