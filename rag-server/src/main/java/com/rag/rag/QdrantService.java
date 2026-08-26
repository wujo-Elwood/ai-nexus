package com.rag.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private volatile boolean collectionReady = false;
    private volatile int collectionVectorSize = 0;

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

    /** 确保向量集合存在，不存在时自动创建 */
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
            if (isCollectionExists()) {
                //如果存在，记录其向量维度，标记为就绪，直接返回
                collectionVectorSize = vectorSize;
                collectionReady = true;
                log.info("Collection {} already exists", collection);
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
            throw new IllegalArgumentException("Vector size mismatch. expected=" + collectionVectorSize + ", actual=" + vectorSize);
        }
    }

    //检查集合是否真实存在于数据库中
    private boolean isCollectionExists() {
        try {
            String url = getBaseUrl() + "/collections/" + collection;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
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
        } catch (Exception e) {
            log.error("Failed to create collection", e);
            throw new RuntimeException("Failed to create Qdrant collection", e);
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
        } catch (Exception e) {
            log.error("Failed to upsert points, size={}", vectorPoints.size(), e);
            throw new RuntimeException("Failed to batch upsert to Qdrant", e);
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
        try {
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
        } catch (Exception e) {
            log.error("Failed to search in Qdrant", e);
            throw new RuntimeException("Failed to search in Qdrant", e);
        }
    }

    /** 按文件编号删除向量点 */
    public void deleteByFileId(Long fileId) {
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
        } catch (Exception e) {
            log.error("Failed to delete points by file_id", e);
            //向量删除失败时必须阻断数据库删除，等待上层重试以避免孤立向量
            throw new RuntimeException("Failed to delete vectors by file_id", e);
        }
    }
}
