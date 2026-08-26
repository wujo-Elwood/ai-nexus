package com.rag.task;

import com.rag.common.BusinessException;
import com.rag.service.FileService;
import com.rag.service.MultipartUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** 统一任务中心服务，统一查询已有任务，不复制业务任务数据。 */
@Service
public class TaskCenterService {
    @Autowired private TaskCenterMapper taskCenterMapper;
    @Autowired private FileService fileService;
    @Autowired private MultipartUploadService multipartUploadService;

    /** 查询任务列表 */
    public List<TaskCenterItem> list(Long userId, String status, String taskType, Integer page, Integer size) {
        int safeSize = size == null || size < 1 || size > 100 ? 30 : size;
        int safePage = page == null || page < 1 ? 1 : page;
        return taskCenterMapper.findTasks(userId, status, taskType, safeSize, (safePage - 1) * safeSize);
    }

    /** 重试可重试的文件或分片上传任务 */
    public void retry(String taskType, String taskId, Long userId) {
        if ("FILE".equals(taskType)) {
            fileService.reprocessOwned(Long.valueOf(taskId), userId);
            return;
        }
        throw new BusinessException(409, "该任务类型暂不支持从任务中心重试");
    }

    /** 取消上传任务 */
    public void cancel(String taskType, String taskId, Long userId) {
        if ("UPLOAD".equals(taskType)) {
            multipartUploadService.cancel(taskId, userId);
            return;
        }
        throw new BusinessException(409, "该任务类型暂不支持从任务中心取消");
    }
}
