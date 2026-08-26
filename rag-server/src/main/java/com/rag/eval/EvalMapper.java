package com.rag.eval;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** RAG 评测数据访问接口。 */
@Mapper
public interface EvalMapper {
    /** 查询当前用户评测问题 */
    List<EvalCase> listCases(@Param("kbId") Long kbId, @Param("userId") Long userId);
    /** 新增评测问题 */
    int insertCase(EvalCase item);
    /** 删除评测问题 */
    int deleteCase(@Param("id") Long id, @Param("userId") Long userId);
    /** 新增运行摘要 */
    int insertRun(EvalRun run);
    /** 查询运行历史 */
    List<EvalRun> listRuns(@Param("kbId") Long kbId, @Param("userId") Long userId);
}
