package com.rag.image.mapper;

import com.rag.image.entity.ImageHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 生图历史 Mapper
 * 提供生图历史的保存、查询和删除操作
 */
@Mapper
public interface ImageHistoryMapper {

    /** 新增生图历史记录 */
    int insert(ImageHistory history);

    /** 查询用户自己的生图历史 */
    List<ImageHistory> findByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    /** 根据编号和用户查询生图历史 */
    ImageHistory findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 根据编号查询生图历史 */
    ImageHistory findById(@Param("id") Long id);

    /** 删除用户自己的生图历史 */
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
