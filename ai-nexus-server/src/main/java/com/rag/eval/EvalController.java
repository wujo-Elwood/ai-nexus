package com.rag.eval;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** RAG 评测中心接口。 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {
    @Autowired private EvalService evalService;
    /** 查询评测问题 */
    @GetMapping("/cases") public Result<List<EvalCase>> cases(@RequestParam Long kbId,HttpServletRequest r){return Result.success(evalService.listCases(kbId,(Long)r.getAttribute("userId")));}
    /** 新增评测问题 */
    @PostMapping("/cases") public Result<EvalCase> add(@RequestBody EvalCase body,HttpServletRequest r){return Result.success(evalService.addCase(body,(Long)r.getAttribute("userId")));}
    /** 删除评测问题 */
    @DeleteMapping("/cases/{id}") public Result<Void> delete(@PathVariable Long id,HttpServletRequest r){evalService.deleteCase(id,(Long)r.getAttribute("userId"));return Result.success();}
    /** 执行评测 */
    @PostMapping("/runs") public Result<EvalRun> run(@RequestParam Long kbId,HttpServletRequest r){return Result.success(evalService.run(kbId,(Long)r.getAttribute("userId")));}
    /** 查询评测历史 */
    @GetMapping("/runs") public Result<List<EvalRun>> runs(@RequestParam Long kbId,HttpServletRequest r){return Result.success(evalService.listRuns(kbId,(Long)r.getAttribute("userId")));}
}
