package com.rag.kb;

import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.KnowledgeBase;
import com.rag.entity.ModelProvider;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.service.KnowledgeBaseService;
import com.rag.service.ModelProviderService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** 知识库摘要服务，按当前版本已完成文档生成并保存知识摘要。 */
@Slf4j
@Service
public class KnowledgeSummaryService {

    /** 单次摘要提示词的最大文本长度，避免超出模型上下文。 */
    private static final int GROUP_LIMIT = 8000;

    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ModelProviderService modelProviderService;
    private final BiFunction<String, ModelProvider, String> modelCaller;

    /** 创建使用真实 OpenAI 兼容模型的摘要服务。 */
    @Autowired
    public KnowledgeSummaryService(ChunkMapper chunkMapper, KnowledgeBaseMapper knowledgeBaseMapper,
                                   KnowledgeBaseService knowledgeBaseService, ModelProviderService modelProviderService) {
        this(chunkMapper, knowledgeBaseMapper, knowledgeBaseService, modelProviderService, null);
    }

    /** 创建可注入模型调用器的摘要服务，供单元测试隔离外部模型。 */
    public KnowledgeSummaryService(ChunkMapper chunkMapper, KnowledgeBaseMapper knowledgeBaseMapper,
                                   KnowledgeBaseService knowledgeBaseService, ModelProviderService modelProviderService,
                                   BiFunction<String, ModelProvider, String> modelCaller) {
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelProviderService = modelProviderService;
        this.modelCaller = modelCaller;
    }

    /** 查询已保存的知识库摘要，读取权限与知识库详情保持一致。 */
    public KnowledgeBase getSummary(Long kbId, Long userId) {
        knowledgeBaseService.checkAccess(kbId, userId);
        return knowledgeBaseMapper.findById(kbId);
    }

    /** 生成并保存知识库摘要，生成失败时不覆盖旧摘要。 */
    public KnowledgeBase generateSummary(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        List<KbChunk> chunks = chunkMapper.findCompletedCurrentByKbId(kbId);
        if (chunks == null || chunks.isEmpty()) {
            throw new BusinessException(400, "知识库中没有已完成处理的文档，暂时无法生成摘要");
        }

        ModelProvider provider = modelProviderService.getActive();
        List<String> partialSummaries = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        for (KbChunk chunk : chunks) {
            String content = chunk.getContent() == null ? "" : chunk.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (group.length() > 0 && group.length() + content.length() + 2 > GROUP_LIMIT) {
                partialSummaries.add(callModel(buildGroupPrompt(group.toString()), provider));
                group.setLength(0);
            }
            group.append(content).append("\n\n");
        }
        if (group.length() > 0) {
            partialSummaries.add(callModel(buildGroupPrompt(group.toString()), provider));
        }
        partialSummaries.removeIf(item -> item == null || item.isBlank());
        if (partialSummaries.isEmpty()) {
            throw new BusinessException(502, "大模型未返回有效知识摘要");
        }

        String summary = partialSummaries.size() == 1
                ? partialSummaries.get(0).trim()
                : callModel(buildMergePrompt(partialSummaries), provider);
        if (summary == null || summary.isBlank()) {
            throw new BusinessException(502, "大模型未返回有效知识摘要");
        }
        knowledgeBaseMapper.updateSummary(kbId, summary.trim());
        return knowledgeBaseMapper.findById(kbId);
    }

    /** 构建文档分组摘要提示词。 */
    private String buildGroupPrompt(String content) {
        return "请基于以下知识库文档片段，提炼事实、主题、关键规则和重要数字。"
                + "只输出简洁的中文摘要，不要补充片段之外的信息。\n\n" + content;
    }

    /** 构建多个分组摘要的合并提示词。 */
    private String buildMergePrompt(List<String> partialSummaries) {
        return "请合并以下分组摘要，生成一份结构清晰、去除重复、可供用户快速阅读的中文知识库摘要。"
                + "只输出摘要正文，不要解释生成过程。\n\n" + String.join("\n\n", partialSummaries);
    }

    /** 调用摘要模型，单元测试可通过注入调用器替换外部请求。 */
    protected String callModel(String prompt, ModelProvider provider) {
        if (modelCaller != null) {
            return modelCaller.apply(prompt, provider);
        }
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl(normalizeBaseUrl(provider.getBaseUrl()))
                    .apiKey(provider.getApiKey())
                    .modelName(provider.getModel())
                    .temperature(0.2)
                    .build();
            Response<AiMessage> response = model.generate(List.of(
                    SystemMessage.from("你是企业知识库摘要助手。"),
                    UserMessage.from(prompt)));
            return response.content().text();
        } catch (Exception e) {
            log.error("Knowledge summary model call failed", e);
            throw new BusinessException(502, "知识库摘要生成失败，请检查模型服务");
        }
    }

    /** 将供应商地址归一化为 LangChain4j 所需的 base URL。 */
    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            value = value.substring(0, value.length() - "/chat/completions".length());
        }
        if (!value.endsWith("/v1")) {
            value += "/v1";
        }
        return value;
    }
}
