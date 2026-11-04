package com.rag.agent.mapper;

import com.rag.agent.entity.AgentInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体注册表 Mapper
 */
public interface AgentInfoMapper {

    /** 查询全部智能体（管理列表，含停用） */
    List<AgentInfo> findAll();

    /** 查询启用的智能体（对外卡片列表） */
    List<AgentInfo> findAllEnabled();

    /** 按编码查询智能体 */
    AgentInfo findByCode(@Param("agentCode") String agentCode);

    /** 按编码查询系统提示词 */
    String findPromptByCode(@Param("agentCode") String agentCode);

    /** 新增智能体 */
    int insert(AgentInfo agent);

    /** 更新智能体基础信息（不含编码与启用状态） */
    int update(AgentInfo agent);

    /** 更新启用状态 */
    int updateEnabled(@Param("agentCode") String agentCode, @Param("enabled") Integer enabled);

    /** 按编码删除智能体（仅自定义智能体） */
    int deleteByCode(@Param("agentCode") String agentCode);
}
