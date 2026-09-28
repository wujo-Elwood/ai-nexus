package com.rag.mapper;

import com.rag.entity.ModelProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelProviderMapper {

    List<ModelProvider> findAll();

    List<ModelProvider> findByCreatedBy(@Param("createdBy") Long createdBy);

    ModelProvider findById(@Param("id") Long id);

    ModelProvider findActive();

    int insert(ModelProvider provider);

    int update(ModelProvider provider);

    int deleteById(@Param("id") Long id);

    int deactivateAll();

    int activate(@Param("id") Long id);
}
