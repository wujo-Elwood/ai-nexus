package com.rag.mapper;

import com.rag.entity.KbFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 文件 Mapper 接口
 * 提供文件的增删改查操作
 */
@Mapper
public interface FileMapper {

    /** 根据文件ID查询文件信息 */
    KbFile findById(@Param("id") Long id);

    /** 查询知识库下的所有文件（按创建时间倒序） */
    List<KbFile> findByKbId(@Param("kbId") Long kbId);

    /** 查询知识库当前版本文件 */
    List<KbFile> findCurrentByKbId(@Param("kbId") Long kbId);

    /** 按哈希查询同知识库重复文件 */
    KbFile findBySha256(@Param("kbId") Long kbId, @Param("fileSha256") String fileSha256);

    /** 查询同名文件的当前版本 */
    KbFile findCurrentByName(@Param("kbId") Long kbId, @Param("fileName") String fileName);

    /** 查询同名文件的全部历史版本 */
    List<KbFile> findVersions(@Param("versionGroupId") Long versionGroupId);

    /** 新增文件记录 */
    int insert(KbFile file);

    /** 首次插入后回填版本组ID */
    int updateVersionGroup(@Param("id") Long id, @Param("versionGroupId") Long versionGroupId);

    /** 记录文件版本快照。 */
    int insertVersion(@Param("fileId") Long fileId, @Param("versionNo") Integer versionNo,
                      @Param("fileSha256") String fileSha256, @Param("filePath") String filePath,
                      @Param("isCurrent") Integer isCurrent, @Param("createUser") Long createUser);

    /** 设置当前版本 */
    int setCurrentVersion(@Param("id") Long id, @Param("versionGroupId") Long versionGroupId);

    /** 将同名旧版本设置为历史版本 */
    int clearCurrentVersion(@Param("versionGroupId") Long versionGroupId);

    /** 更新文件目录、分类和质量状态 */
    int updateCatalog(@Param("id") Long id, @Param("folderId") Long folderId, @Param("category") String category);

    /** 更新文件质量统计结果 */
    int updateQuality(@Param("id") Long id, @Param("qualityStatus") String qualityStatus,
                      @Param("qualityScore") java.math.BigDecimal qualityScore,
                      @Param("vectorStatus") String vectorStatus);

    /** 更新文件处理状态、阶段、进度和失败原因 */
    int updateProcessInfo(@Param("id") Long id,
                          @Param("status") String status,
                          @Param("processStage") String processStage,
                          @Param("progress") Integer progress,
                          @Param("errorMessage") String errorMessage);

    /** 记录处理失败信息和下一次重试时间 */
    int updateProcessFailure(@Param("id") Long id,
                             @Param("processStage") String processStage,
                             @Param("progress") Integer progress,
                             @Param("errorMessage") String errorMessage,
                             @Param("processAttempts") Integer processAttempts,
                             @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /** 查询已经到达重试时间的失败文件 */
    List<KbFile> findRetryableFiles(@Param("now") LocalDateTime now,
                                    @Param("maxAttempts") Integer maxAttempts,
                                    @Param("limit") Integer limit);

    /** 抢占一个失败文件的重试执行权 */
    int claimRetry(@Param("id") Long id);

    /** 清空人工重新处理时的重试状态 */
    int resetProcessRetry(@Param("id") Long id);

    /** 删除文件记录 */
    int deleteById(@Param("id") Long id);
}
