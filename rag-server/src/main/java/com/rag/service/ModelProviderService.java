package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.mapper.ModelProviderMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ModelProviderService {

    @Autowired
    private ModelProviderMapper modelProviderMapper;

    /**
     * 查询当前用户可见的供应商列表
     * 管理员可见全部供应商及其密钥，普通用户仅可见自己创建的供应商
     * 当前激活的供应商即使属于他人也会返回，供聊天页展示正在使用的模型，但密钥字段被清空
     */
    public List<ModelProvider> listVisible(Long userId, boolean admin) {
        // 第1步：管理员直接拿到全量列表
        if (admin) {
            return modelProviderMapper.findAll();
        }
        // 第2步：普通用户只拿到自己创建的供应商
        List<ModelProvider> visible = new ArrayList<>(modelProviderMapper.findByCreatedBy(userId));
        // 第3步：补上当前激活的他人供应商，避免聊天页显示成未选择模型
        ModelProvider active = modelProviderMapper.findActive();
        if (active != null && !isOwned(active, userId)) {
            visible.add(maskSecrets(active));
        }
        return visible;
    }

    /**
     * 查询当前用户视角下的激活供应商，非本人创建的供应商不返回密钥
     */
    public ModelProvider getActiveForViewer(Long userId, boolean admin) {
        ModelProvider provider = getActive();
        return admin || isOwned(provider, userId) ? provider : maskSecrets(provider);
    }

    /** 查询激活供应商，内部模型调用链路使用，始终返回完整配置。 */
    public ModelProvider getActive() {
        ModelProvider provider = modelProviderMapper.findActive();
        if (provider == null) {
            throw new BusinessException(404, "没有激活的模型供应商，请先在模型设置中配置并激活一个供应商");
        }
        return provider;
    }

    public ModelProvider getById(Long id) {
        ModelProvider provider = modelProviderMapper.findById(id);
        if (provider == null) {
            throw new BusinessException(404, "供应商不存在");
        }
        return provider;
    }

    /** 按当前用户归属校验后查询供应商 */
    public ModelProvider getOwnedById(Long id, Long userId, boolean admin) {
        ModelProvider provider = getById(id);
        // 第1步：管理员可以操作任意供应商，归属为空的历史数据也只由管理员处理
        if (admin || isOwned(provider, userId)) {
            return provider;
        }
        // 第2步：其余情况一律拒绝
        throw new BusinessException(403, "只能操作自己创建的模型供应商，如需调整请联系管理员");
    }

    /** 新建供应商，创建人固定记为当前登录用户 */
    public ModelProvider create(ModelProvider provider, Long userId) {
        cleanImageConfig(provider);
        provider.setIsActive(0);
        provider.setCreatedBy(userId);
        modelProviderMapper.insert(provider);
        return provider;
    }

    /** 修改供应商配置 */
    public ModelProvider update(Long id, ModelProvider provider, Long userId, boolean admin) {
        ModelProvider existing = getOwnedById(id, userId, admin);
        existing.setName(provider.getName());
        existing.setBaseUrl(provider.getBaseUrl());
        existing.setApiKey(provider.getApiKey());
        existing.setModel(provider.getModel());
        existing.setImageBaseUrl(cleanBlank(provider.getImageBaseUrl()));
        existing.setImageApiKey(cleanBlank(provider.getImageApiKey()));
        existing.setImageModel(cleanBlank(provider.getImageModel()));
        modelProviderMapper.update(existing);
        return existing;
    }

    /** 删除供应商 */
    public void delete(Long id, Long userId, boolean admin) {
        getOwnedById(id, userId, admin);
        modelProviderMapper.deleteById(id);
    }

    /** 激活指定供应商，会取消其他供应商的激活状态 */
    @Transactional
    public void activate(Long id, Long userId, boolean admin) {
        getOwnedById(id, userId, admin);
        modelProviderMapper.deactivateAll();
        modelProviderMapper.activate(id);
    }

    /** 判断供应商是否属于当前用户，归属为空的历史数据视为管理员资产。 */
    private boolean isOwned(ModelProvider provider, Long userId) {
        return provider.getCreatedBy() != null && provider.getCreatedBy().equals(userId);
    }

    /** 生成只读视图：清空密钥和归属人，保留名称与模型名供界面展示。 */
    private ModelProvider maskSecrets(ModelProvider provider) {
        // 第1步：复制实体，避免污染调用方持有的同一对象
        ModelProvider view = new ModelProvider();
        BeanUtils.copyProperties(provider, view);
        // 第2步：抹掉密钥和归属人后返回
        view.setApiKey(null);
        view.setImageApiKey(null);
        view.setCreatedByName(null);
        return view;
    }

    /**
     * 清理生图配置中的空字符串
     */
    private void cleanImageConfig(ModelProvider provider) {
        // 第1步：空字符串统一转成 null，后续生图时才能正确走默认配置
        provider.setImageBaseUrl(cleanBlank(provider.getImageBaseUrl()));
        provider.setImageApiKey(cleanBlank(provider.getImageApiKey()));
        provider.setImageModel(cleanBlank(provider.getImageModel()));
    }

    /**
     * 清理空白字符串
     */
    private String cleanBlank(String value) {
        // 第1步：为空或全空格时返回 null
        if (value == null || value.isBlank()) {
            return null;
        }
        // 第2步：返回去除首尾空格后的内容
        return value.trim();
    }
}
