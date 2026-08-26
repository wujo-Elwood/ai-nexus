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

    /**
     * 新增抽取字段
     */
    int insert(ExtractField field);

    /**
     * 更新抽取字段
     */
    int update(ExtractField field);

    /**
     * 删除模板下的指定字段
     */
    int deleteMissing(@Param("templateId") Long templateId, @Param("ids") List<Long> ids);

    /**
     * 删除模板下的所有字段
     */
    int deleteByTemplateId(@Param("templateId") Long templateId);
}
