package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.KnowledgeBaseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库服务
 * 提供知识库的增删改查和权限校验
 */
@Service
public class KnowledgeBaseService {

    /** 正在删除的知识库编号，供异步文件处理任务快速终止 */
    private static final Set<Long> DELETING_KB_IDS = ConcurrentHashMap.newKeySet();

    /** 判断知识库是否正在删除 */
    public static boolean isDeleting(Long kbId) {
        return kbId != null && DELETING_KB_IDS.contains(kbId);
    }

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private FileService fileService;

    /** 创建知识库，默认私有 */
    public KnowledgeBase create(String name, String description, Long userId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setCreateUser(userId);
        kb.setVisibility("PRIVATE");
        // 使用空值表示沿用系统默认策略，避免新建知识库改变旧行为
        kb.setChunkSize(null);
        kb.setChunkOverlap(null);
        kb.setTopK(null);
        kb.setSimilarityThreshold(null);
        kb.setVectorWeight(null);
        kb.setKeywordWeight(null);
        kb.setHeadingSplitEnabled(null);
        kb.setTableKeepStrategy(null);
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    /** 根据 ID 查询知识库 */
    public KnowledgeBase getById(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.findById(id);
        if (kb == null) {
            throw new BusinessException(404, "Knowledge base not found");
        }
        return kb;
    }

    /**
     * 查询用户可见的知识库
     * 自己的全部可见 + 他人的公开的可见
     */
    public List<KnowledgeBase> getVisible(Long userId) {
        return knowledgeBaseMapper.findVisible(userId);
    }

    /**
     * 修改知识库名称、描述和可见范围
     * 只有知识库创建者可以修改
     */
    public KnowledgeBase update(Long id, String name, String description, String visibility, Long userId) {
        // 第1步：查询知识库并校验当前用户是否为创建者
        KnowledgeBase kb = getById(id);
        if (!kb.getCreateUser().equals(userId)) {
            throw new BusinessException(403, "No permission to update this knowledge base");
        }
        // 第2步：校验名称，避免保存空知识库名称
        String safeName = name == null ? "" : name.trim();
        if (safeName.isEmpty()) {
            throw new BusinessException(400, "Knowledge base name cannot be empty");
        }
        // 第3步：只接受公开和私有两种可见范围
        String safeVisibility = "PUBLIC".equals(visibility) ? "PUBLIC" : "PRIVATE";
        kb.setName(safeName);
        kb.setDescription(description == null ? "" : description.trim());
        kb.setVisibility(safeVisibility);
        // 第4步：保存修改并返回最新知识库信息
        knowledgeBaseMapper.update(kb);
        return kb;
    }

    /** 删除知识库及其全部文件、切片、向量等关联数据（只有创建者可以删除） */
    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id, Long userId) {
        KnowledgeBase kb = knowledgeBaseMapper.findById(id);
        if (kb == null) {
            throw new BusinessException(404, "Knowledge base not found");
        }
        if (!kb.getCreateUser().equals(userId)) {
            throw new BusinessException(403, "No permission to delete this knowledge base");
        }
        DELETING_KB_IDS.add(id);
        try {
            //数据库无外键约束，必须先清理关联数据，否则文件、切片和向量全部残留
            fileService.deleteKbData(id);
            knowledgeBaseMapper.deleteById(id);
        } finally {
            DELETING_KB_IDS.remove(id);
        }
    }

    /**
     * 校验用户是否有权访问指定知识库
     * 创建者可以访问自己的，其他人只能访问公开的
     */
    public void checkAccess(Long kbId, Long userId) {
        KnowledgeBase kb = getById(kbId);
        if (!kb.getCreateUser().equals(userId) && !"PUBLIC".equals(kb.getVisibility())) {
            throw new BusinessException(403, "No permission to access this knowledge base");
        }
    }

    /**
     * 校验用户是否有权管理指定知识库
     * 公开知识库允许他人读取，但上传、删除、重处理等管理操作只允许创建者执行
     */
    public void checkManageAccess(Long kbId, Long userId) {
        // 第1步：查询知识库
        KnowledgeBase kb = getById(kbId);
        // 第2步：非创建者不能执行管理操作
        if (!kb.getCreateUser().equals(userId)) {
            throw new BusinessException(403, "No permission to manage this knowledge base");
        }
    }

    /** 查询知识库处理和检索策略 */
    public KnowledgeBase getStrategy(Long kbId, Long userId) {
        checkAccess(kbId, userId);
        return getById(kbId);
    }

    /** 保存知识库处理和检索策略 */
    public KnowledgeBase updateStrategy(Long kbId, KnowledgeBase request, Long userId) {
        checkManageAccess(kbId, userId);
        KnowledgeBase kb = getById(kbId);
        kb.setChunkSize(validateInteger(request.getChunkSize(), 100, 5000, "切片大小"));
        kb.setChunkOverlap(validateInteger(request.getChunkOverlap(), 0, 2000, "切片重叠长度"));
        if (kb.getChunkOverlap() != null && kb.getChunkSize() != null && kb.getChunkOverlap() >= kb.getChunkSize()) {
            throw new BusinessException(400, "切片重叠长度必须小于切片大小");
        }
        kb.setTopK(validateInteger(request.getTopK(), 1, 50, "Top-K"));
        kb.setSimilarityThreshold(validateDecimal(request.getSimilarityThreshold(), 0, 1, "相似度阈值"));
        kb.setVectorWeight(validateDecimal(request.getVectorWeight(), 0, 1, "向量权重"));
        kb.setKeywordWeight(validateDecimal(request.getKeywordWeight(), 0, 1, "关键词权重"));
        if (kb.getVectorWeight() != null && kb.getKeywordWeight() != null
                && kb.getVectorWeight().add(kb.getKeywordWeight()).compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "向量权重和关键词权重不能同时为零");
        }
        kb.setHeadingSplitEnabled(request.getHeadingSplitEnabled() == null ? 0 : (request.getHeadingSplitEnabled() == 1 ? 1 : 0));
        String tableStrategy = request.getTableKeepStrategy();
        kb.setTableKeepStrategy("SUMMARY".equals(tableStrategy) ? "SUMMARY" : "FULL");
        knowledgeBaseMapper.update(kb);
        return getById(kbId);
    }

    /** 校验整数策略参数 */
    private Integer validateInteger(Integer value, int min, int max, String label) {
        if (value == null) return null;
        if (value < min || value > max) throw new BusinessException(400, label + "超出允许范围");
        return value;
    }

    /** 校验小数策略参数 */
    private java.math.BigDecimal validateDecimal(java.math.BigDecimal value, double min, double max, String label) {
        if (value == null) return null;
        if (value.compareTo(java.math.BigDecimal.valueOf(min)) < 0 || value.compareTo(java.math.BigDecimal.valueOf(max)) > 0) {
            throw new BusinessException(400, label + "超出允许范围");
        }
        return value;
    }
}
