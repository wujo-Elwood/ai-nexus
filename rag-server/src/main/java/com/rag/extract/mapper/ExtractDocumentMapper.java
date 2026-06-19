package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 抽取文档 Mapper 接口
 */
@Mapper
public interface ExtractDocumentMapper {

    /**
     * 新增抽取文档
     */
    int insert(ExtractDocument document);

    /**
     * 根据文档主键查询文档
     */
    ExtractDocument findById(@Param("id") Long id);

    /**
     * 根据文档主键和上传用户查询文档
     */
    ExtractDocument findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 更新文档解析结果
     */
    int updateParsed(@Param("id") Long id, @Param("fullText") String fullText, @Param("pageCount") Integer pageCount);

    /**
     * 更新文档解析状态
     */
    int updateStatus(@Param("id") Long id, @Param("parseStatus") String parseStatus);
}
