package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.KbGrant;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.KbGrantMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.mapper.UserMapper;
import com.rag.rbac.mapper.SysRoleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库服务
 * 提供知识库的增删改查和权限校验
 * 访问规则：创建者、持有授权（READ/MANAGE）的用户或角色可访问，PUBLIC 对所有用户可读；
 * 管理规则：创建者或持有 MANAGE 授权者可管理
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
    private KbGrantMapper kbGrantMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private FileService fileService;

    /** 口径冲突清理器：删库时同步清理冲突记录；表未建时为空，不影响删除流程 */
    @Autowired(required = false)
    private com.rag.kb.KbConflictMapper kbConflictMapper;

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
        kb.setVisionParseEnabled(null);
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
     * 自己的全部 + 他人的公开的 + 被授权（USER/ROLE）的；
     * kb_grant 表未创建（升级脚本未执行）时回退为不含授权的查询
     */
    public List<KnowledgeBase> getVisible(Long userId) {
        try {
            return knowledgeBaseMapper.findVisible(userId);
        } catch (Exception e) {
            return knowledgeBaseMapper.findVisibleWithoutGrant(userId);
        }
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
            kbGrantMapper.deleteByKbId(id);
            //冲突记录同属知识库数据，知识库删掉后必须一起清，否则会留下指向已删切片的孤儿行
            if (kbConflictMapper != null) {
                kbConflictMapper.deleteByKb(id);
            }
            knowledgeBaseMapper.deleteById(id);
        } finally {
            DELETING_KB_IDS.remove(id);
        }
    }

    /**
     * 校验用户是否有权访问指定知识库
     * 创建者、持有 READ/MANAGE 授权者（含通过角色授权）可以访问，PUBLIC 对所有用户开放
     */
    public void checkAccess(Long kbId, Long userId) {
        KnowledgeBase kb = getById(kbId);
        if (kb.getCreateUser().equals(userId) || "PUBLIC".equals(kb.getVisibility())) {
            return;
        }
        // 表缺失等异常按无授权处理，回退到原有判定
        boolean granted = false;
        try {
            granted = userId != null && kbGrantMapper.countReadable(kbId, userId) > 0;
        } catch (Exception ignored) {
        }
        if (!granted) {
            throw new BusinessException(403, "No permission to access this knowledge base");
        }
    }

    /**
     * 校验用户是否有权管理指定知识库
     * 创建者或持有 MANAGE 授权者可以管理；PUBLIC 知识库对他人仍然只读
     */
    public void checkManageAccess(Long kbId, Long userId) {
        KnowledgeBase kb = getById(kbId);
        if (kb.getCreateUser().equals(userId)) {
            return;
        }
        boolean manageGranted = false;
        try {
            manageGranted = userId != null && kbGrantMapper.countManageable(kbId, userId) > 0;
        } catch (Exception ignored) {
        }
        if (!manageGranted) {
            throw new BusinessException(403, "No permission to manage this knowledge base");
        }
    }

    /** 查询可授权的用户列表：仅返回编号与展示名，供共享设置弹窗选人使用 */
    public List<Map<String, Object>> listShareUsers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.rag.entity.SysUser user : userMapper.findAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            String nickname = user.getNickname() == null ? "" : user.getNickname().trim();
            item.put("name", nickname.isEmpty() ? user.getUsername() : nickname);
            result.add(item);
        }
        return result;
    }

    /** 查询知识库的共享授权列表（仅创建者或 MANAGE 授权者可查看） */
    public List<KbGrant> listGrants(Long kbId, Long userId) {
        checkManageAccess(kbId, userId);
        try {
            return kbGrantMapper.findByKbId(kbId);
        } catch (Exception e) {
            throw new BusinessException(500, "共享授权表未创建，请先执行 database/kb_grant_upgrade.sql");
        }
    }

    /**
     * 新增共享授权
     * 授权对象必须存在；用户授权不能授给创建者自己；重复授权自动忽略
     */
    public KbGrant addGrant(Long kbId, Long userId, String granteeType, Long granteeId, String permission) {
        // 第1步：校验管理权限与参数
        checkManageAccess(kbId, userId);
        String type = granteeType == null ? "" : granteeType.trim().toUpperCase();
        if (!KbGrant.TYPE_USER.equals(type) && !KbGrant.TYPE_ROLE.equals(type)) {
            throw new BusinessException(400, "授权对象类型仅支持 USER 或 ROLE");
        }
        String perm = permission == null || permission.isBlank()
                ? KbGrant.PERM_READ : permission.trim().toUpperCase();
        if (!KbGrant.PERM_READ.equals(perm) && !KbGrant.PERM_MANAGE.equals(perm)) {
            throw new BusinessException(400, "权限级别仅支持 READ 或 MANAGE");
        }
        if (granteeId == null || granteeId <= 0) {
            throw new BusinessException(400, "授权对象编号不能为空");
        }
        // 第2步：校验授权对象存在；用户授权不允许授给创建者自己
        if (KbGrant.TYPE_USER.equals(type)) {
            if (userMapper.findById(granteeId) == null) {
                throw new BusinessException(404, "被授权用户不存在");
            }
            KnowledgeBase kb = getById(kbId);
            if (kb.getCreateUser().equals(granteeId)) {
                throw new BusinessException(400, "知识库创建者无需授权，本身就是可管理状态");
            }
        } else if (roleMapper.findById(granteeId) == null) {
            throw new BusinessException(404, "被授权角色不存在");
        }
        // 第3步：落库（重复授权由唯一键忽略）并返回授权记录
        KbGrant grant = new KbGrant();
        grant.setKbId(kbId);
        grant.setGranteeType(type);
        grant.setGranteeId(granteeId);
        grant.setPermission(perm);
        grant.setCreateUser(userId);
        try {
            kbGrantMapper.insert(grant);
        } catch (Exception e) {
            throw new BusinessException(500, "共享授权表未创建，请先执行 database/kb_grant_upgrade.sql");
        }
        return grant;
    }

    /** 删除共享授权（仅创建者或 MANAGE 授权者可操作，授权必须属于该知识库） */
    public void removeGrant(Long kbId, Long userId, Long grantId) {
        // 第1步：校验管理权限
        checkManageAccess(kbId, userId);
        // 第2步：校验授权归属，避免跨知识库误删
        KbGrant grant = kbGrantMapper.findById(grantId);
        if (grant == null || !kbId.equals(grant.getKbId())) {
            throw new BusinessException(404, "授权记录不存在");
        }
        // 第3步：删除授权，权限立即收回
        kbGrantMapper.deleteById(grantId);
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
        kb.setVisionParseEnabled(request.getVisionParseEnabled() == null ? 0 : (request.getVisionParseEnabled() == 1 ? 1 : 0));
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
