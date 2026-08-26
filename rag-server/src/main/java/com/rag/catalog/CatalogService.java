package com.rag.catalog;

import com.rag.common.BusinessException;
import com.rag.service.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** 知识库目录、分类和标签业务服务。 */
@Service
public class CatalogService {
    @Autowired private CatalogMapper catalogMapper;
    @Autowired private KnowledgeBaseService knowledgeBaseService;

    /** 查询知识库目录和标签。 */
    public Map<String,Object> list(Long kbId, Long userId) {
        knowledgeBaseService.checkAccess(kbId, userId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("folders", catalogMapper.listFolders(kbId));
        result.put("tags", catalogMapper.listTags(kbId));
        return result;
    }

    /** 创建目录。 */
    public Map<String,Object> createFolder(Long kbId, Long parentId, String name, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        if (name == null || name.trim().isEmpty()) throw new BusinessException(400, "目录名称不能为空");
        Map<String,Object> data = new HashMap<>();
        data.put("kbId", kbId); data.put("parentId", parentId); data.put("name", name.trim()); data.put("userId", userId);
        catalogMapper.insertFolder(data);
        return data;
    }

    /** 删除目录。 */
    public void deleteFolder(Long kbId, Long id, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        catalogMapper.deleteFolder(id, kbId);
    }

    /** 修改目录名称和父目录。 */
    public void updateFolder(Long kbId, Long id, Long parentId, String name, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        if (name == null || name.trim().isEmpty()) throw new BusinessException(400, "目录名称不能为空");
        if (Objects.equals(id, parentId)) throw new BusinessException(400, "目录不能移动到自身下面");
        catalogMapper.updateFolder(id, kbId, parentId, name.trim());
    }

    /** 创建标签。 */
    public Map<String,Object> createTag(Long kbId, String name, String color, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        if (name == null || name.trim().isEmpty()) throw new BusinessException(400, "标签名称不能为空");
        Map<String,Object> data = new HashMap<>();
        data.put("kbId", kbId); data.put("name", name.trim()); data.put("color", color); data.put("userId", userId);
        catalogMapper.insertTag(data);
        return data;
    }

    /** 删除标签。 */
    public void deleteTag(Long kbId, Long id, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        catalogMapper.deleteTag(id, kbId);
    }

    /** 设置文件标签。 */
    public List<Map<String,Object>> setFileTags(Long fileId, List<Long> tagIds, Long userId) {
        //文件权限在控制器中校验，服务只负责替换关联关系
        catalogMapper.clearFileTags(fileId);
        if (tagIds != null) for (Long tagId : tagIds) if (tagId != null) catalogMapper.addFileTag(fileId, tagId);
        return catalogMapper.listFileTags(fileId);
    }

    /** 查询文件标签。 */
    public List<Map<String,Object>> listFileTags(Long fileId) { return catalogMapper.listFileTags(fileId); }
}
