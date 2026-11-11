package com.rag.kb;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档口径冲突实体，对应 kb_conflict 表。
 * 一条记录代表"同一话题的两段内容给出了不一致的数值/期限"，
 * 由 ConflictDetectionService 自动检出，交人工确认口径。
 */
@Data
public class KbConflict {
    /** 主键 */
    private Long id;
    /** 知识库编号 */
    private Long kbId;
    /** 冲突片段 A 的切片编号 */
    private Long chunkAId;
    /** 冲突片段 B 的切片编号 */
    private Long chunkBId;
    /** 片段 A 所属文件 */
    private Long fileAId;
    private String fileAName;
    private Integer chunkAIndex;
    private String contentA;
    /** 片段 B 所属文件 */
    private Long fileBId;
    private String fileBName;
    private Integer chunkBIndex;
    private String contentB;
    /** 两段文本的 Jaccard 相似度，越高越可能是同一话题 */
    private Float similarity;
    /** 产生冲突的计量单位（天/月/%/元…） */
    private String conflictUnit;
    /** 两边在该单位上的取值 */
    private String valueA;
    private String valueB;
    /** 自动生成的冲突说明 */
    private String reason;
    /** PENDING=待确认，CONFIRMED=确认冲突，DISMISSED=已忽略 */
    private String conflictStatus;
    /** 触发检测的用户 */
    private Long createUser;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
