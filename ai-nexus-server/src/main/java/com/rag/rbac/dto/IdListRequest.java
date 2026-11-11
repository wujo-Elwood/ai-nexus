package com.rag.rbac.dto;

import lombok.Data;

import java.util.List;

/**
 * 编号列表请求
 * 用于保存角色菜单授权和用户角色授权
 */
@Data
public class IdListRequest {
    /** 编号列表 */
    private List<Long> ids;
}
