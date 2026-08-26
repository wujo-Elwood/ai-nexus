package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.mapper.ModelProviderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ModelProviderService {

    @Autowired
    private ModelProviderMapper modelProviderMapper;

    public List<ModelProvider> listAll() {
        return modelProviderMapper.findAll();
    }

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

    public ModelProvider create(ModelProvider provider) {
        cleanImageConfig(provider);
        provider.setIsActive(0);
        modelProviderMapper.insert(provider);
        return provider;
    }

    public ModelProvider update(Long id, ModelProvider provider) {
        ModelProvider existing = getById(id);
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

    public void delete(Long id) {
        getById(id);
        modelProviderMapper.deleteById(id);
    }

    @Transactional
    public void activate(Long id) {
        getById(id);
        modelProviderMapper.deactivateAll();
        modelProviderMapper.activate(id);
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
