package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.KbGrant;
import com.rag.entity.KnowledgeBase;
import com.rag.entity.SysUser;
import com.rag.mapper.KbGrantMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.mapper.UserMapper;
import com.rag.rbac.entity.SysRole;
import com.rag.rbac.mapper.SysRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

/**
 * 知识库服务测试
 * 验证知识库编辑和访问权限的核心行为
 */
class KnowledgeBaseServiceTest {

    /**
     * 测试知识库编辑只能由创建者执行
     */
    @Test
    void updateShouldAllowOwnerAndRejectOtherUser() {
        // 第1步：准备一个属于用户1的知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase existing = buildKnowledgeBase(10L, 1L, "旧名称", "PRIVATE");
        when(mapper.findById(10L)).thenReturn(existing);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：创建者修改知识库信息
        KnowledgeBase updated = service.update(10L, "新名称", "新描述", "PUBLIC", 1L);

        // 第3步：确认修改内容已经保存
        assertEquals("新名称", updated.getName());
        assertEquals("新描述", updated.getDescription());
        assertEquals("PUBLIC", updated.getVisibility());
        verify(mapper).update(existing);

        // 第4步：其他用户修改时必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(10L, "越权名称", "", "PRIVATE", 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试公开知识库允许其他用户访问
     */
    @Test
    void checkAccessShouldAllowPublicKnowledgeBase() {
        // 第1步：准备公开知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "公开库", "PUBLIC"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：其他用户访问公开知识库不应抛出异常
        service.checkAccess(10L, 2L);
    }

    /**
     * 测试私有知识库拒绝其他用户访问
     */
    @Test
    void checkAccessShouldRejectPrivateKnowledgeBase() {
        // 第1步：准备私有知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：其他用户访问私有知识库必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试公开知识库也只允许创建者管理文件
     */
    @Test
    void checkManageAccessShouldRejectOtherUserForPublicKnowledgeBase() {
        // 第1步：准备属于用户1的公开知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "公开库", "PUBLIC"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        // 第2步：其他用户尝试管理公开知识库时必须被拒绝
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkManageAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试删除知识库时先清理文件、切片、向量、共享授权等关联数据，再删除知识库
     */
    @Test
    void deleteShouldCleanRelationsBeforeDeletingKnowledgeBase() {
        // 第1步：准备属于用户1的知识库
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        FileService fileService = mock(FileService.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "待删除", "PRIVATE"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "fileService", fileService);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        // 第2步：创建者删除知识库
        service.delete(10L, 1L);

        // 第3步：确认关联数据（含共享授权）先被清理，再删除知识库记录
        verify(fileService).deleteKbData(10L);
        verify(grantMapper).deleteByKbId(10L);
        verify(mapper).deleteById(10L);
    }

    /**
     * 测试非创建者删除知识库被拒绝且不触发清理
     */
    @Test
    void deleteShouldRejectOtherUserWithoutCleanup() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        FileService fileService = mock(FileService.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "fileService", fileService);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.delete(10L, 2L));
        assertEquals(403, exception.getCode());
        verify(fileService, never()).deleteKbData(10L);
        verify(grantMapper, never()).deleteByKbId(10L);
        verify(mapper, never()).deleteById(10L);
    }

    /**
     * 构造测试知识库
     */
    private KnowledgeBase buildKnowledgeBase(Long id, Long ownerId, String name, String visibility) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setCreateUser(ownerId);
        kb.setName(name);
        kb.setVisibility(visibility);
        return kb;
    }

    // ---------- 共享授权 ----------

    /**
     * 测试持有授权（USER 或 ROLE 命中）的用户可以访问私有知识库
     */
    @Test
    void checkAccessShouldAllowGrantedUser() {
        // 第1步：准备私有知识库，授权查询命中
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(grantMapper.countReadable(10L, 2L)).thenReturn(1);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        // 第2步：被授权用户访问不应抛异常
        service.checkAccess(10L, 2L);
    }

    /**
     * 测试无授权用户访问私有知识库仍被拒绝
     */
    @Test
    void checkAccessShouldRejectUngrantedUser() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(grantMapper.countReadable(10L, 2L)).thenReturn(0);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试授权表异常（如未建表）时按无授权处理，回退原有判定
     */
    @Test
    void checkAccessShouldTreatGrantTableFailureAsUngranted() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(grantMapper.countReadable(10L, 2L)).thenThrow(new RuntimeException("Table 'kb_grant' doesn't exist"));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试持有 MANAGE 授权的用户可以管理知识库（上传、删除等）
     */
    @Test
    void checkManageAccessShouldAllowManageGrantee() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(grantMapper.countManageable(10L, 2L)).thenReturn(1);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        // MANAGE 授权者管理时不抛异常
        service.checkManageAccess(10L, 2L);
    }

    /**
     * 测试 READ 授权不能升级为管理权限：只读授权者管理时被拒绝
     */
    @Test
    void checkManageAccessShouldRejectReadGrantee() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        // countReadable 命中（可读）但 countManageable 未命中（非 MANAGE 授权）
        when(grantMapper.countManageable(10L, 2L)).thenReturn(0);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkManageAccess(10L, 2L));
        assertEquals(403, exception.getCode());
    }

    /**
     * 测试新增授权：权限缺省为 READ，参数规范化后落库
     */
    @Test
    void addGrantShouldNormalizeAndInsert() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(userMapper.findById(2L)).thenReturn(new SysUser());
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);

        // 权限传小写且为空时缺省 READ，类型小写规范化
        KbGrant grant = service.addGrant(10L, 1L, "user", 2L, null);

        assertEquals(10L, grant.getKbId());
        assertEquals("USER", grant.getGranteeType());
        assertEquals(2L, grant.getGranteeId());
        assertEquals("READ", grant.getPermission());
        assertEquals(1L, grant.getCreateUser());
        verify(grantMapper).insert(grant);
    }

    /**
     * 测试新增授权的参数校验：非法类型、非法权限、用户不存在、授权给自己均被拒绝
     */
    @Test
    void addGrantShouldRejectInvalidParams() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(userMapper.findById(2L)).thenReturn(new SysUser());
        // 创建者自身在用户表中存在，才能走到"不能授权给自己"的校验
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "roleMapper", roleMapper);

        // 非法授权对象类型
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.addGrant(10L, 1L, "GROUP", 2L, "READ")).getCode());
        // 非法权限级别
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.addGrant(10L, 1L, "USER", 2L, "ADMIN")).getCode());
        // 被授权用户不存在
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.addGrant(10L, 1L, "USER", 99L, "READ")).getCode());
        // 不能授权给创建者自己
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.addGrant(10L, 1L, "USER", 1L, "READ")).getCode());
        // 非创建者不能添加授权
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.addGrant(10L, 2L, "USER", 3L, "READ")).getCode());
    }

    /**
     * 测试新增角色授权时校验角色存在
     */
    @Test
    void addGrantShouldValidateRoleExists() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(roleMapper.findById(5L)).thenReturn(new SysRole());
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);
        ReflectionTestUtils.setField(service, "roleMapper", roleMapper);

        KbGrant grant = service.addGrant(10L, 1L, "ROLE", 5L, "MANAGE");
        assertEquals("ROLE", grant.getGranteeType());
        assertEquals("MANAGE", grant.getPermission());

        // 角色不存在时拒绝
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.addGrant(10L, 1L, "ROLE", 99L, "READ")).getCode());
    }

    /**
     * 测试删除授权：授权记录必须属于该知识库，防止跨库误删
     */
    @Test
    void removeGrantShouldRejectGrantFromOtherKb() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        KbGrant foreignGrant = new KbGrant();
        foreignGrant.setId(7L);
        foreignGrant.setKbId(99L);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(grantMapper.findById(7L)).thenReturn(foreignGrant);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.removeGrant(10L, 1L, 7L)).getCode());
        verify(grantMapper, never()).deleteById(7L);
    }

    /**
     * 测试删除授权：创建者可移除本库授权，权限立即收回
     */
    @Test
    void removeGrantShouldDeleteOwnedGrant() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KbGrantMapper grantMapper = mock(KbGrantMapper.class);
        KbGrant grant = new KbGrant();
        grant.setId(7L);
        grant.setKbId(10L);
        when(mapper.findById(10L)).thenReturn(buildKnowledgeBase(10L, 1L, "私有库", "PRIVATE"));
        when(grantMapper.findById(7L)).thenReturn(grant);
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);
        ReflectionTestUtils.setField(service, "kbGrantMapper", grantMapper);

        service.removeGrant(10L, 1L, 7L);
        verify(grantMapper).deleteById(7L);
    }

    /**
     * 测试授权表未创建时知识库列表回退为不含授权的查询
     */
    @Test
    void getVisibleShouldFallbackWhenGrantTableMissing() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase owned = buildKnowledgeBase(10L, 1L, "自有库", "PRIVATE");
        when(mapper.findVisible(1L)).thenThrow(new RuntimeException("Table 'kb_grant' doesn't exist"));
        when(mapper.findVisibleWithoutGrant(1L)).thenReturn(List.of(owned));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "knowledgeBaseMapper", mapper);

        List<KnowledgeBase> visible = service.getVisible(1L);
        assertEquals(1, visible.size());
        assertEquals("自有库", visible.get(0).getName());
    }

    /**
     * 测试可授权用户列表：昵称为空时回退用户名
     */
    @Test
    void listShareUsersShouldPreferNicknameFallbackToUsername() {
        UserMapper userMapper = mock(UserMapper.class);
        SysUser withNickname = new SysUser();
        withNickname.setId(2L);
        withNickname.setUsername("zhang");
        withNickname.setNickname("张三");
        SysUser withoutNickname = new SysUser();
        withoutNickname.setId(3L);
        withoutNickname.setUsername("li");
        when(userMapper.findAll()).thenReturn(List.of(withNickname, withoutNickname));
        KnowledgeBaseService service = new KnowledgeBaseService();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);

        List<java.util.Map<String, Object>> users = service.listShareUsers();
        assertEquals(2, users.size());
        assertEquals("张三", users.get(0).get("name"));
        assertEquals("li", users.get(1).get("name"));
    }
}
