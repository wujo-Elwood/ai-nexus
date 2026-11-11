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
     * 查询当前用户可用模板
     */
    List<ExtractTemplate> findVisible(@Param("userId") Long userId);

    /**
     * 根据模板主键查询模板
     */
    ExtractTemplate findById(@Param("id") Long id);

    /**
     * 根据模板编码查询模板
     */
    ExtractTemplate findByCode(@Param("templateCode") String templateCode);

    /**
     * 新增抽取模板
     */
    int insert(ExtractTemplate template);

    /**
     * 更新抽取模板
     */
    int update(ExtractTemplate template);

    /**
     * 删除抽取模板
     */
    int delete(@Param("id") Long id, @Param("userId") Long userId);
}
