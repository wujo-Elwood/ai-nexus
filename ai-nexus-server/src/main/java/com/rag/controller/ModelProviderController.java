package com.rag.controller;

import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.rbac.annotation.RequirePermission;
import com.rag.rbac.service.RbacService;
import com.rag.service.ModelProviderService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型供应商控制器
 * 提供大模型供应商的增删改查和激活切换接口
 * 供应商按创建人隔离：普通用户只能看到和操作自己创建的供应商，管理员可看到全部
 * 列表和激活接口供聊天页模型切换使用，登录即可访问，内容由服务层按归属过滤
 *
 * 密钥安全：任何角色的响应都只回传脱敏后的 Key（前 4 位 + **** + 后 4 位），
 * 明文 Key 只保留在服务端用于调用模型；前端提交时若该字段含 **** 表示未修改，服务层保留原值。
 */
@RestController
@RequestMapping("/api/model-provider")
public class ModelProviderController {

    /** 管理员角色编码 */
    private static final String ADMIN_ROLE = "admin";

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private RbacService rbacService;

    /** 查询当前用户可见的供应商列表（密钥脱敏） */
    @GetMapping("/list")
    public Result<List<ModelProvider>> list(HttpServletRequest request) {
        Long userId = currentUserId(request);
        List<ModelProvider> providers = modelProviderService.listVisible(userId, isAdmin(userId));
        return Result.success(providers.stream().map(this::maskSecrets).toList());
    }

    /** 获取当前激活的供应商（密钥脱敏） */
    @GetMapping("/active")
    public Result<ModelProvider> getActive(HttpServletRequest request) {
        Long userId = currentUserId(request);
        return Result.success(maskSecrets(modelProviderService.getActiveForViewer(userId, isAdmin(userId))));
    }

    /** 新增供应商，创建人记为当前登录用户 */
    @RequirePermission("settings:view")
    @PostMapping
    public Result<ModelProvider> create(@RequestBody ModelProvider provider, HttpServletRequest request) {
        return Result.success(maskSecrets(modelProviderService.create(provider, currentUserId(request))));
    }

    /** 修改自己创建的供应商 */
    @RequirePermission("settings:view")
    @PutMapping("/{id}")
    public Result<ModelProvider> update(@PathVariable Long id, @RequestBody ModelProvider provider,
                                       HttpServletRequest request) {
        Long userId = currentUserId(request);
        return Result.success(maskSecrets(modelProviderService.update(id, provider, userId, isAdmin(userId))));
    }

    /** 删除自己创建的供应商 */
    @RequirePermission("settings:view")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        modelProviderService.delete(id, userId, isAdmin(userId));
        return Result.success();
    }

    /** 激活自己创建的供应商（取消其他供应商的激活状态） */
    @PutMapping("/{id}/activate")
    public Result<Void> activate(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        modelProviderService.activate(id, userId, isAdmin(userId));
        return Result.success();
    }

    /** 设置生图供应商（与聊天激活解耦，生图固定使用该供应商） */
    @PutMapping("/{id}/activate-image")
    public Result<Void> activateImage(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        modelProviderService.activateImage(id, userId, isAdmin(userId));
        return Result.success();
    }

    /** 设置视觉供应商（与聊天激活解耦，文档图片内容入库解析固定使用该供应商） */
    @PutMapping("/{id}/activate-vision")
    public Result<Void> activateVision(@PathVariable Long id, HttpServletRequest request) {
        Long userId = currentUserId(request);
        modelProviderService.activateVision(id, userId, isAdmin(userId));
        return Result.success();
    }

    /** 从认证拦截器写入的请求属性中取当前登录用户。 */
    private Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }

    /** 判断当前登录用户是否为管理员。 */
    private boolean isAdmin(Long userId) {
        return rbacService.hasRole(userId, ADMIN_ROLE);
    }

    /** 响应视图统一脱敏，避免明文密钥出现在浏览器网络面板、日志与截图中。 */
    private ModelProvider maskSecrets(ModelProvider provider) {
        // 第1步：复制实体，避免污染服务层持有的对象（内部模型调用仍需明文 Key）
        if (provider == null) {
            return null;
        }
        ModelProvider view = new ModelProvider();
        BeanUtils.copyProperties(provider, view);
        // 第2步：三套密钥全部脱敏
        view.setApiKey(mask(provider.getApiKey()));
        view.setImageApiKey(mask(provider.getImageApiKey()));
        view.setVisionApiKey(mask(provider.getVisionApiKey()));
        return view;
    }

    /** 密钥脱敏：保留前 4 位与后 4 位，长度不足时整体打码。 */
    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
