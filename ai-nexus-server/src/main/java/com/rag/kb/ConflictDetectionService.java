package com.rag.kb;

import com.rag.entity.KbChunk;
import com.rag.mapper.ChunkMapper;
import com.rag.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库口径冲突检测。
 *
 * <p>解决的问题：同一知识库里两份文件对同一件事给出了不同的数值（例如"年假 5 天"与"年假 10 天"），
 * 检索时两段会被同时召回，模型各引一半，答案看起来有出处却自相矛盾。这类错误比"答不上来"更危险。</p>
 *
 * <p>判定思路（确定性规则，不依赖大模型，向量库不可用也能跑）：</p>
 * <ol>
 *   <li>把每个切片抽成「二字组集合」+「单位 → 取值集合」（天/月/%/元/次…）；</li>
 *   <li>按单位分桶，只在同一单位桶内两两配对，避免 O(n²) 全量比较；</li>
 *   <li>两条要求同时满足才算候选：文本相似度够高（说明在说同一件事），
 *       且该单位上的取值互不重叠（说明数字对不上）；</li>
 *   <li>结果按相似度降序截断，落库供人工确认口径。</li>
 * </ol>
 */
@Service
public class ConflictDetectionService {
    private static final Logger log = LoggerFactory.getLogger(ConflictDetectionService.class);

    /** 单次检测最多处理的切片数，避免超大知识库把接口拖死 */
    private static final int MAX_CHUNKS = 600;
    /** 单次检测最多返回的冲突数 */
    private static final int MAX_CONFLICTS = 50;
    /** 两两比较的次数上限，超过即停止（保护长尾知识库） */
    private static final int MAX_PAIRS = 200_000;
    /** 单个单位桶最多参与的切片数 */
    private static final int MAX_PER_UNIT = 300;
    /** 判定"在说同一件事"的最低 Jaccard 相似度 */
    private static final double MIN_SIMILARITY = 0.35d;
    /** 至少要有这么多个二字组重合，才值得算相似度 */
    private static final int MIN_SHARED_GRAMS = 8;
    /** 参与比对的正文前缀长度，控制计算量 */
    private static final int MAX_CONTENT_CHARS = 800;
    /** 落库的内容快照长度 */
    private static final int MAX_SNAPSHOT_CHARS = 600;

    /** 抽取"数字 + 计量单位"，单位按长度从长到短排列，避免"个工作日"被"日"截断 */
    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(个工作日|工作日|个月|小时|分钟|天|日|月|年|周|%|％|万元|元|次|倍|人|份)");
    /** 清洗正文中的空白与标点，只保留用于切二字组的字符 */
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "[\\s\\p{Punct}，。、；：！？''\"\"（）《》〈〉【】…·—－]+");

    private final ChunkMapper chunkMapper;
    private final KbConflictMapper conflictMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    public ConflictDetectionService(ChunkMapper chunkMapper,
                                    KbConflictMapper conflictMapper,
                                    KnowledgeBaseService knowledgeBaseService) {
        this.chunkMapper = chunkMapper;
        this.conflictMapper = conflictMapper;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 对一个知识库执行冲突检测，结果全量替换旧记录。
     *
     * @return chunkCount=参与检测的切片数，pairCount=实际比较的片段对数，conflictCount=检出的冲突数
     */
    public Map<String, Object> detect(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);

        List<KbChunk> chunks = chunkMapper.findCompletedCurrentByKbId(kbId);
        List<Profile> profiles = new ArrayList<>();
        if (chunks != null) {
            for (KbChunk chunk : chunks) {
                if (profiles.size() >= MAX_CHUNKS) {
                    break;
                }
                if (chunk.getContent() == null || chunk.getContent().trim().length() < 10) {
                    continue;
                }
                profiles.add(buildProfile(chunk));
            }
        }

        Map<String, KbConflict> best = new LinkedHashMap<>();
        int pairsExamined = 0;
        if (profiles.size() > 1) {
            Map<String, List<Integer>> buckets = groupByUnit(profiles);
            outer:
            for (Map.Entry<String, List<Integer>> bucket : buckets.entrySet()) {
                String unit = bucket.getKey();
                List<Integer> indexes = bucket.getValue();
                if (indexes.size() < 2) {
                    continue;
                }
                if (indexes.size() > MAX_PER_UNIT) {
                    indexes = indexes.subList(0, MAX_PER_UNIT);
                }
                for (int i = 0; i < indexes.size(); i++) {
                    for (int j = i + 1; j < indexes.size(); j++) {
                        if (++pairsExamined > MAX_PAIRS) {
                            log.warn("冲突检测提前结束：比较次数达到上限 {}（知识库 {}）", MAX_PAIRS, kbId);
                            break outer;
                        }
                        Profile a = profiles.get(indexes.get(i));
                        Profile b = profiles.get(indexes.get(j));
                        if (isSameFileAdjacent(a, b)) {
                            continue;
                        }
                        List<String> valuesA = a.claims.get(unit);
                        List<String> valuesB = b.claims.get(unit);
                        if (valuesA == null || valuesB == null || valuesA.isEmpty() || valuesB.isEmpty()) {
                            continue;
                        }
                        // 取值集合完全一致说明口径相同；只要存在差异就可能是冲突。
                        // 这里不能要求两侧完全不相交：同一份制度里「年假 5 天」和「报销 30 天」共用一个单位，
                        // 交集不会被判为冲突，但真正的差异值是 5 与 10，必须按差集看才能抓到。
                        List<String> diffA = differingValues(valuesA, valuesB);
                        List<String> diffB = differingValues(valuesB, valuesA);
                        if (diffA.isEmpty() && diffB.isEmpty()) {
                            continue;
                        }
                        int shared = countShared(a.grams, b.grams, MIN_SHARED_GRAMS);
                        if (shared < MIN_SHARED_GRAMS) {
                            continue;
                        }
                        double similarity = jaccard(a.grams, b.grams);
                        if (similarity < MIN_SIMILARITY) {
                            continue;
                        }
                        // 展示差异值；若某一侧是另一侧的子集（没有独有值），退回展示它全部取值
                        List<String> displayA = diffA.isEmpty() ? valuesA : diffA;
                        List<String> displayB = diffB.isEmpty() ? valuesB : diffB;
                        KbConflict conflict = buildConflict(kbId, userId, a, b, unit, displayA, displayB, similarity);
                        String key = conflict.getChunkAId() + "-" + conflict.getChunkBId();
                        KbConflict existing = best.get(key);
                        if (existing == null || existing.getSimilarity() < conflict.getSimilarity()) {
                            best.put(key, conflict);
                        }
                    }
                }
            }
        }

        List<KbConflict> detected = new ArrayList<>(best.values());
        detected.sort((left, right) -> Float.compare(right.getSimilarity(), left.getSimilarity()));
        if (detected.size() > MAX_CONFLICTS) {
            detected = new ArrayList<>(detected.subList(0, MAX_CONFLICTS));
        }

        conflictMapper.deleteByKb(kbId);
        if (!detected.isEmpty()) {
            conflictMapper.insertBatch(detected);
        }
        log.info("知识库 {} 冲突检测完成：切片 {} 个，比较 {} 对，检出 {} 条", kbId, profiles.size(), pairsExamined, detected.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chunkCount", profiles.size());
        summary.put("pairCount", pairsExamined);
        summary.put("conflictCount", detected.size());
        return summary;
    }

    /** 查询冲突列表，status 为空时返回全部 */
    public Map<String, Object> list(Long kbId, Long userId, String status) {
        knowledgeBaseService.checkAccess(kbId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", conflictMapper.findByKb(kbId, status));
        result.put("pendingCount", conflictMapper.countByStatus(kbId, "PENDING"));
        result.put("confirmedCount", conflictMapper.countByStatus(kbId, "CONFIRMED"));
        result.put("dismissedCount", conflictMapper.countByStatus(kbId, "DISMISSED"));
        return result;
    }

    /** 确认或忽略一条冲突 */
    public KbConflict updateStatus(Long kbId, Long userId, Long conflictId, String status) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!"PENDING".equals(normalized) && !"CONFIRMED".equals(normalized) && !"DISMISSED".equals(normalized)) {
            throw new com.rag.common.BusinessException(400, "状态只能是 PENDING、CONFIRMED 或 DISMISSED");
        }
        KbConflict conflict = conflictMapper.findById(conflictId);
        if (conflict == null || !kbId.equals(conflict.getKbId())) {
            throw new com.rag.common.BusinessException(404, "冲突记录不存在");
        }
        conflictMapper.updateStatus(conflictId, normalized);
        conflict.setConflictStatus(normalized);
        return conflict;
    }

    /** 把切片预处理成"二字组 + 单位取值"，避免在配对循环里重复计算 */
    private Profile buildProfile(KbChunk chunk) {
        String content = chunk.getContent();
        String head = content.length() > MAX_CONTENT_CHARS ? content.substring(0, MAX_CONTENT_CHARS) : content;
        Profile profile = new Profile(chunk);
        profile.grams = toGrams(head);
        profile.claims = extractClaims(head);
        return profile;
    }

    /** 切二字组：中文没有空格，二字滑窗是最省事又够用的相似度粒度 */
    private Set<String> toGrams(String text) {
        String cleaned = NOISE_PATTERN.matcher(text).replaceAll("");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            grams.add(cleaned.substring(i, i + 2));
        }
        return grams;
    }

    /** 抽取"数值 + 单位"，同一单位下的多个取值按出现顺序去重保存 */
    private Map<String, List<String>> extractClaims(String text) {
        Map<String, List<String>> claims = new HashMap<>();
        Matcher matcher = QUANTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = normalizeNumber(matcher.group(1));
            String unit = normalizeUnit(matcher.group(2));
            List<String> values = claims.computeIfAbsent(unit, key -> new ArrayList<>());
            if (!values.contains(value) && values.size() < 5) {
                values.add(value);
            }
        }
        return claims;
    }

    /** 12.0 → 12，保留小数但去掉无意义的 .0 */
    private String normalizeNumber(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.endsWith(".0")) {
            return value.substring(0, value.length() - 2);
        }
        return value;
    }

    /** 同义单位归一，避免"日/天"、"％/%"、"个月/月"被当成两个单位 */
    private String normalizeUnit(String raw) {
        String unit = raw == null ? "" : raw.trim();
        switch (unit) {
            case "日":
                return "天";
            case "％":
                return "%";
            case "个月":
                return "月";
            case "个工作日":
                return "工作日";
            default:
                return unit;
        }
    }

    /** 按单位建倒排桶：只在同一单位内配对 */
    private Map<String, List<Integer>> groupByUnit(List<Profile> profiles) {
        Map<String, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < profiles.size(); i++) {
            for (String unit : profiles.get(i).claims.keySet()) {
                buckets.computeIfAbsent(unit, key -> new ArrayList<>()).add(i);
            }
        }
        return buckets;
    }

    /** 同一文件里相邻的两段通常是同一条款的上下半句，跳过以免误报 */
    private boolean isSameFileAdjacent(Profile a, Profile b) {
        if (a.chunk.getFileId() == null || !a.chunk.getFileId().equals(b.chunk.getFileId())) {
            return false;
        }
        if (a.chunk.getChunkIndex() == null || b.chunk.getChunkIndex() == null) {
            return false;
        }
        return Math.abs(a.chunk.getChunkIndex() - b.chunk.getChunkIndex()) <= 1;
    }

    /** 数一数两个二字组集合有多少重合，达到阈值立即返回（省掉完整交集） */
    private int countShared(Set<String> left, Set<String> right, int threshold) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        int shared = 0;
        for (String gram : smaller) {
            if (larger.contains(gram)) {
                shared++;
                if (shared >= threshold) {
                    return shared;
                }
            }
        }
        return shared;
    }

    /** Jaccard 相似度 = 交集 / 并集 */
    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0d;
        }
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        int shared = 0;
        for (String gram : smaller) {
            if (larger.contains(gram)) {
                shared++;
            }
        }
        int union = left.size() + right.size() - shared;
        return union == 0 ? 0d : (double) shared / union;
    }

    /** 组装一条冲突记录，chunkAId 恒小于 chunkBId 以便唯一键稳定 */
    private KbConflict buildConflict(Long kbId, Long userId, Profile a, Profile b, String unit,
                                     List<String> valuesA, List<String> valuesB, double similarity) {
        Profile left = a.chunk.getId() <= b.chunk.getId() ? a : b;
        Profile right = left == a ? b : a;
        List<String> leftValues = left == a ? valuesA : valuesB;
        List<String> rightValues = left == a ? valuesB : valuesA;

        KbConflict conflict = new KbConflict();
        conflict.setKbId(kbId);
        conflict.setChunkAId(left.chunk.getId());
        conflict.setChunkBId(right.chunk.getId());
        conflict.setFileAId(left.chunk.getFileId());
        conflict.setFileAName(left.chunk.getFileName());
        conflict.setChunkAIndex(left.chunk.getChunkIndex());
        conflict.setContentA(snapshot(left.chunk.getContent()));
        conflict.setFileBId(right.chunk.getFileId());
        conflict.setFileBName(right.chunk.getFileName());
        conflict.setChunkBIndex(right.chunk.getChunkIndex());
        conflict.setContentB(snapshot(right.chunk.getContent()));
        conflict.setSimilarity((float) similarity);
        conflict.setConflictUnit(unit);
        conflict.setValueA(joinValues(leftValues));
        conflict.setValueB(joinValues(rightValues));
        conflict.setReason(buildReason(unit, leftValues, rightValues, similarity));
        conflict.setConflictStatus("PENDING");
        conflict.setCreateUser(userId);
        return conflict;
    }

    /** 生成人话版的冲突说明，例如「「天」相关表述不一致：A 为 5，B 为 10」 */
    private String buildReason(String unit, List<String> valuesA, List<String> valuesB, double similarity) {
        String reason = String.format("「%s」相关表述数值不一致：左侧为 %s，右侧为 %s（文本相似度 %.0f%%）",
                unit, joinValues(valuesA), joinValues(valuesB), similarity * 100);
        return reason.length() > 480 ? reason.substring(0, 480) : reason;
    }

    /** 取值列表拼接，最多展示 3 个 */
    private String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        int limit = Math.min(values.size(), 3);
        return String.join("/", values.subList(0, limit));
    }

    /**
     * 取左侧独有的取值（右侧没有的），用于定位真正不一致的数值。
     *
     * @param side  左侧取值
     * @param other 右侧取值
     * @return 左侧独有的取值，按原顺序返回
     */
    private List<String> differingValues(List<String> side, List<String> other) {
        List<String> diff = new ArrayList<>();
        for (String value : side) {
            if (other == null || !other.contains(value)) {
                diff.add(value);
            }
        }
        return diff;
    }

    /** 内容快照：压掉换行与多余空白，便于列表里直接读 */
    private String snapshot(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replaceAll("\\s+", " ").trim();
        return text.length() > MAX_SNAPSHOT_CHARS ? text.substring(0, MAX_SNAPSHOT_CHARS) + "…" : text;
    }

    /** 切片的预处理结果 */
    private static final class Profile {
        private final KbChunk chunk;
        private Set<String> grams = Collections.emptySet();
        private Map<String, List<String>> claims = Collections.emptyMap();

        private Profile(KbChunk chunk) {
            this.chunk = chunk;
        }
    }
}
