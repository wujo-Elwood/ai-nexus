package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractField;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽取字段 Mapper 接口
 */
@Mapper
public interface ExtractFieldMapper {

    /**
     * 根据模板主键查询字段列表
     */
    List<ExtractField> findByTemplateId(@Param("templateId") Long templateId);
}
