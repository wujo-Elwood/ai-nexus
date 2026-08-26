package com.rag.task;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 统一任务中心接口。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskCenterController {
    @Autowired private TaskCenterService taskCenterService;

    /** 查询当前用户任务 */
    @GetMapping
    public Result<List<TaskCenterItem>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String taskType,
                                             @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                             HttpServletRequest request) {
        // 调用任务中心服务按用户、状态和类型聚合任务
        return Result.success(taskCenterService.list((Long) request.getAttribute("userId"), status, taskType, page, size));
    }

    /** 重试任务 */
    @PostMapping("/{taskType}/{taskId}/retry")
    public Result<Void> retry(@PathVariable String taskType, @PathVariable String taskId, HttpServletRequest request) {
        // 调用任务中心服务执行受支持的重试动作
        taskCenterService.retry(taskType, taskId, (Long) request.getAttribute("userId"));
        return Result.success();
    }

    /** 取消任务 */
    @PostMapping("/{taskType}/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable String taskType, @PathVariable String taskId, HttpServletRequest request) {
        // 调用任务中心服务执行受支持的取消动作
        taskCenterService.cancel(taskType, taskId, (Long) request.getAttribute("userId"));
        return Result.success();
    }
}
