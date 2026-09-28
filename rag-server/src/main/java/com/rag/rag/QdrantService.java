package com.rag.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Qdrant 向量数据库服务
 * 负责向量集合的创建、写入、检索和删除
 * 与 Qdrant 的 REST API 交互，使用 Cosine 距离度量
 */
@Slf4j
@Service
public class QdrantService {

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port}")
    private int port;

    @Value("${qdrant.collection}")
    private String collection;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object collectionLock = new Object();
    private final ReentrantReadWriteLock rebuildLock = new ReentrantReadWriteLock(true);
    private volatile boolean collectionReady = false;
    private volatile int collectionVectorSize = 0;
    private volatile boolean rebuilding = false;
    /** 标记当前线程是否为全局重建任务线程，允许其写入新集合 */
    private final ThreadLocal<Boolean> rebuildWriter = ThreadLocal.withInitial(() -> false);

    /** 向量点：包含 ID、向量数组和业务负载 */
    public static class VectorPoint {
        public final Long pointId;
        public final float[] embedding;
        public final Map<String, Object> payload;

        public VectorPoint(Long pointId, float[] embedding, Map<String, Object> payload) {
            this.pointId = pointId;
            this.embedding = embedding;
            this.payload = payload;
        }
    }

    /** 搜索结果：包含 chunk ID 和向量相似度分数 */
    public static class SearchResult {
        public final Long id;
        public final float score;

        public SearchResult(Long id, float score) {
            this.id = id;
            this.score = score;
        }
    }

    private String getBaseUrl() {
        return "http://" + host + ":" + port;
    }

    /** 确保向量集合存在，不存在时自动创建；已存在时以向量库真实维度为准 */
    public void ensureCollection(int vectorSize) {
        if (collectionReady) {
            validateVectorSize(vectorSize);
            return;
        }
        synchronized (collectionLock) {
            if (collectionReady) {
                validateVectorSize(vectorSize);
                return;
            }
            //检查集合是否真实存在于数据库中
            String collectionInfo = fetchCollectionInfo();
            if (collectionInfo != null) {
                //集合已存在：读取真实维度做校验，避免换 Embedding 模型后维度被首个调用方覆盖
                int storedVectorSize = parseVectorSize(collectionInfo);
                if (storedVectorSize <= 0) {
                    throw new BusinessException(500, "无法读取 Qdrant collection 的真实向量维度，请检查集合配置");
                }
                collectionVectorSize = storedVectorSize;
                collectionReady = true;
                validateVectorSize(vectorSize);
                log.info("Collection {} already exists, vector size={}", collection, collectionVectorSize);
                return;
            }
            //创建新集合
            createCollection(vectorSize);
            collectionVectorSize = vectorSize;
            collectionReady = true;
            log.info("Created collection {}", collection);
        }
    }

    private void validateVectorSize(int vectorSize) {
        if (collectionVectorSize == 0) {
            return;
        }
        if (collectionVectorSize != vectorSize) {
            throw new BusinessException(500, "向量维度不匹配：向量库为 " + collectionVectorSize
                    + " 维，当前 Embedding 模型输出 " + vectorSize + " 维。"
                    + "请换回原 Embedding 模型，或更换模型后对知识库执行重建向量");
        }
    }

    /**
     * 查询集合信息
     * 只有 404 表示集合不存在，服务异常必须向上抛出，避免误创建集合
     */
    private String fetchCollectionInfo() {
        try {
            String url = getBaseUrl() + "/collections/" + collection;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            log.error("Failed to query Qdrant collection", e);
            throw new BusinessException(503, "Qdrant 服务不可用，无法读取向量集合信息");
        }
    }

    /** 解析集合信息中的向量维度，结构不符合预期时返回 0 */
    int parseVectorSize(String collectionInfo) {
        try {
            JsonNode vectors = objectMapper.readTree(collectionInfo)
                    .path("result").path("config").path("params").path("vectors");
            if (vectors.isObject() && vectors.has("size") && vectors.get("size").isNumber()) {
                return vectors.get("size").asInt();
            }
        } catch (Exception e) {
            log.warn("Failed to parse Qdrant collection vector size", e);
        }
        return 0;
    }

    private void createCollection(int vectorSize) {
        try {
            String url = getBaseUrl() + "/collections/" + collection;
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode params = body.putObject("vectors");
            params.put("size", vectorSize);
            params.put("distance", "Cosine");
            HttpEntity<String> entity = buildJsonEntity(body);
            restTemplate.put(url, entity);
        } catch (RestClientException e) {
            log.error("Failed to create collection", e);
            throw new BusinessException(503, "Qdrant 服务不可用，无法创建向量集合");
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Qdrant collection request", e);
        }
    }

    /**
     * 查询是否正在执行全局向量重建
     */
    public boolean isRebuilding() {
        return rebuilding;
    }

    /**
     * 标记开始全局向量重建，重复触发时返回冲突错误
     */
    public void startRebuild() {
        rebuildLock.writeLock().lock();
        try {
            synchronized (collectionLock) {
                if (rebuilding) {
                    throw new BusinessException(409, "全局向量正在重建，请勿重复提交");
                }
                rebuilding = true;
            }
        } finally {
            rebuildLock.writeLock().unlock();
        }
    }

    /** 允许当前全局重建线程写入新建集合 */
    public void beginRebuildWrites() {
        rebuildWriter.set(true);
    }

    /** 清除当前全局重建线程写入标记 */
    public void endRebuildWrites() {
        rebuildWriter.remove();
    }

    /**
     * 删除旧 collection 并按当前 Embedding 维度重新创建
     */
    public void recreateCollection(int vectorSize) {
        if (!rebuilding) {
            throw new BusinessException(409, "必须先进入全局向量重建状态");
        }
        rebuildLock.writeLock().lock();
        try {
            collectionReady = false;
            collectionVectorSize = 0;
            String url = getBaseUrl() + "/collections/" + collection;
            try {
                //删除旧 collection，保证新旧维度不会混用
                restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
            } catch (HttpClientErrorException.NotFound ignored) {
                log.info("Collection {} does not exist before rebuild", collection);
            } catch (RestClientException e) {
                log.error("Failed to delete collection before rebuild", e);
                throw new BusinessException(503, "Qdrant 服务不可用，无法删除旧向量集合");
            }
            //按新模型输出维度创建 collection
            createCollection(vectorSize);
            collectionVectorSize = vectorSize;
            collectionReady = true;
        } finally {
            rebuildLock.writeLock().unlock();
        }
    }

    /**
     * 结束全局向量重建并恢复检索
     */
    public void finishRebuild() {
        rebuildLock.writeLock().lock();
        try {
            synchronized (collectionLock) {
                rebuilding = false;
            }
        } finally {
            rebuildLock.writeLock().unlock();
        }
    }

    /** 写入或更新单个向量点 */
    public void upsert(Long pointId, float[] embedding, Map<String, Object> payload) {
        List<VectorPoint> vectorPoints = List.of(new VectorPoint(pointId, embedding, payload));
        upsertBatch(vectorPoints);
    }

    /** 批量写入或更新向量点 */
    public void upsertBatch(List<VectorPoint> vectorPoints) {
        if (vectorPoints == null || vectorPoints.isEmpty()) {
            return;
        }
        rebuildLock.readLock().lock();
        try {
            if (rebuilding && !Boolean.TRUE.equals(rebuildWriter.get())) {
                throw new BusinessException(409, "全局向量正在重建，暂时无法写入向量");
            }
            try {
                //确保向量集合存在，不存在时自动创建
                ensureCollection(vectorPoints.get(0).embedding.length);
                String url = getBaseUrl() + "/collections/" + collection + "/points";
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode points = body.putArray("points");
                for (VectorPoint vectorPoint : vectorPoints) {
                    appendPoint(points, vectorPoint);
                }
                HttpEntity<String> entity = buildJsonEntity(body);
                //保存向量到向量数据库
                restTemplate.put(url, entity);
            } catch (BusinessException e) {
                //维度不匹配等业务异常直接抛出，保留可行动的错误提示
                throw e;
            } catch (Exception e) {
                log.error("Failed to upsert points, size={}", vectorPoints.size(), e);
                throw new RuntimeException("Failed to batch upsert to Qdrant", e);
            }
        } finally {
            rebuildLock.readLock().unlock();
        }
    }

    private void appendPoint(ArrayNode points, VectorPoint vectorPoint) {
        ObjectNode point = points.addObject();
        point.put("id", vectorPoint.pointId);
        ArrayNode vector = point.putArray("vector");
        for (float vectorValue : vectorPoint.embedding) {
            vector.add(vectorValue);
        }
        ObjectNode pointPayload = point.putObject("payload");
        for (Map.Entry<String, Object> entry : vectorPoint.payload.entrySet()) {
            appendPayloadValue(pointPayload, entry.getKey(), entry.getValue());
        }
    }

    private void appendPayloadValue(ObjectNode pointPayload, String key, Object value) {
        if (value instanceof Long longValue) {
            pointPayload.put(key, longValue);
            return;
        }
        if (value instanceof Integer integerValue) {
            pointPayload.put(key, integerValue);
            return;
        }
        pointPayload.put(key, String.valueOf(value));
    }

    private HttpEntity<String> buildJsonEntity(ObjectNode body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
    }

    /** 搜索相似文本切片（仅返回 ID 列表） */
    public List<Long> search(float[] queryVector, int topK) {
        return search(queryVector, topK, null);
    }

    /** 按知识库搜索相似文本切片（仅返回 ID 列表） */
    public List<Long> search(float[] queryVector, int topK, Long kbId) {
        return searchWithScore(queryVector, topK, kbId).stream()
                .map(result -> result.id)
                .collect(Collectors.toList());
    }

    /** 按知识库搜索相似文本切片（返回带分数的结果，用于重排序） */
    public List<SearchResult> searchWithScore(float[] queryVector, int topK, Long kbId) {
        rebuildLock.readLock().lock();
        try {
            if (rebuilding) {
                throw new BusinessException(409, "全局向量正在重建，暂时无法检索");
            }
            //检索前先确保集合存在且维度匹配，换 Embedding 模型后能给出明确错误
            ensureCollection(queryVector.length);
            String url = getBaseUrl() + "/collections/" + collection + "/points/search";
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode vector = body.putArray("vector");
            for (float vectorValue : queryVector) {
                vector.add(vectorValue);
            }
            body.put("limit", topK);
            body.put("with_payload", true);

            if (kbId != null) {
                ObjectNode filter = body.putObject("filter");
                ArrayNode must = filter.putArray("must");
                ObjectNode condition = must.addObject();
                condition.put("key", "kb_id");
                ObjectNode match = condition.putObject("match");
                match.put("value", kbId);
            }

            HttpEntity<String> entity = buildJsonEntity(body);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode result = objectMapper.readTree(response.getBody());

            List<SearchResult> results = new ArrayList<>();
            JsonNode resultArray = result.get("result");
            if (resultArray != null && resultArray.isArray()) {
                for (JsonNode point : resultArray) {
                    long id = point.get("id").asLong();
                    float score = point.has("score") ? point.get("score").floatValue() : 0f;
                    results.add(new SearchResult(id, score));
                }
            }
            return results;
        } catch (BusinessException e) {
            //维度不匹配等业务异常直接抛出，保留可行动的错误提示
            throw e;
        } catch (Exception e) {
            log.error("Failed to search in Qdrant", e);
            throw new RuntimeException("Failed to search in Qdrant", e);
        } finally {
            rebuildLock.readLock().unlock();
        }
    }

    /** 按文件编号删除向量点 */
    public void deleteByFileId(Long fileId) {
        rebuildLock.readLock().lock();
        try {
            if (rebuilding) {
                throw new BusinessException(409, "全局向量正在重建，暂时无法删除向量");
            }
            try {
                String url = getBaseUrl() + "/collections/" + collection + "/points/delete";
                ObjectNode body = objectMapper.createObjectNode();
                ObjectNode filter = body.putObject("filter");
                ArrayNode must = filter.putArray("must");
                ObjectNode condition = must.addObject();
                condition.put("key", "file_id");
                ObjectNode match = condition.putObject("match");
                match.put("value", fileId);
                HttpEntity<String> entity = buildJsonEntity(body);
                restTemplate.postForEntity(url, entity, String.class);
            } catch (HttpClientErrorException.NotFound ignored) {
                log.info("Collection {} does not exist while deleting file vectors", collection);
            } catch (Exception e) {
                log.error("Failed to delete points by file_id", e);
                //向量删除失败时必须阻断数据库删除，等待上层重试以避免孤立向量
                throw new RuntimeException("Failed to delete vectors by file_id", e);
            }
        } finally {
            rebuildLock.readLock().unlock();
        }
    }

    /** 按知识库编号删除向量点，用于删除知识库时一次性清理全部向量 */
    public void deleteByKbId(Long kbId) {
        rebuildLock.readLock().lock();
        try {
            if (rebuilding) {
                throw new BusinessException(409, "全局向量正在重建，暂时无法删除向量");
            }
            try {
                String url = getBaseUrl() + "/collections/" + collection + "/points/delete";
                ObjectNode body = objectMapper.createObjectNode();
                ObjectNode filter = body.putObject("filter");
                ArrayNode must = filter.putArray("must");
                ObjectNode condition = must.addObject();
                condition.put("key", "kb_id");
                ObjectNode match = condition.putObject("match");
                match.put("value", kbId);
                HttpEntity<String> entity = buildJsonEntity(body);
                restTemplate.postForEntity(url, entity, String.class);
            } catch (HttpClientErrorException.NotFound ignored) {
                log.info("Collection {} does not exist while deleting knowledge base vectors", collection);
            } catch (Exception e) {
                log.error("Failed to delete points by kb_id", e);
                //向量删除失败时必须阻断数据库删除，等待上层重试以避免孤立向量
                throw new RuntimeException("Failed to delete vectors by kb_id", e);
            }
        } finally {
            rebuildLock.readLock().unlock();
        }
    }
}
