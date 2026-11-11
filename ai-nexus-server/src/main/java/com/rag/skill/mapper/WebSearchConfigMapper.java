package com.rag.skill.mapper;

import com.rag.skill.entity.WebSearchConfig;
import org.apache.ibatis.annotations.Param;

/**
 * web_search 技能搜索源配置 Mapper
 */
public interface WebSearchConfigMapper {

    /** 按所属用户查询配置；user_id = 0 表示平台默认 */
    WebSearchConfig findByUserId(@Param("userId") Long userId);

    /** 保存配置：不存在则插入，存在则更新 */
    int upsert(WebSearchConfig config);

    /** 删除指定用户的配置 */
    int deleteByUserId(@Param("userId") Long userId);
}
