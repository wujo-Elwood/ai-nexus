package com.rag.mapper;

import com.rag.entity.KbGrant;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库共享授权 Mapper
 */
public interface KbGrantMapper {

    /** 查询单条授权 */
    KbGrant findById(@Param("id") Long id);

    /** 查询知识库的全部授权（带授权对象名称，供共享设置弹窗展示） */
    List<KbGrant> findByKbId(@Param("kbId") Long kbId);

    /** 判断用户是否持有指定知识库的授权（USER 授权直接命中，ROLE 授权按用户角色命中；READ/MANAGE 均视为可读） */
    int countReadable(@Param("kbId") Long kbId, @Param("userId") Long userId);

    /** 判断用户是否持有指定知识库的 MANAGE 授权 */
    int countManageable(@Param("kbId") Long kbId, @Param("userId") Long userId);

    /** 用户被授权（可读）的知识库编号集合，供知识库列表查询使用 */
    List<Long> findReadableKbIds(@Param("userId") Long userId);

    /** 新增授权；同一知识库同一对象重复授权时忽略 */
    int insert(KbGrant grant);

    /** 删除单条授权 */
    int deleteById(@Param("id") Long id);

    /** 知识库删除时清理全部授权 */
    int deleteByKbId(@Param("kbId") Long kbId);
}
