package com.rag.image.service;

import com.rag.image.mapper.ImageTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 生图任务恢复服务
 * 服务启动时把上次异常断开遗留的运行中任务标记失败，避免前端一直显示生成中
 */
@Slf4j
@Component
public class ImageTaskRecoveryService implements ApplicationRunner {

    private final ImageTaskMapper imageTaskMapper;

    /**
     * 创建生图任务恢复服务
     */
    public ImageTaskRecoveryService(ImageTaskMapper imageTaskMapper) {
        this.imageTaskMapper = imageTaskMapper;
    }

    /**
     * 启动后恢复异常中断任务
     */
    @Override
    public void run(ApplicationArguments args) {
        int count = imageTaskMapper.markInterruptedTasksFailed(
                "服务重启，任务已中断",
                "服务器连接中断或服务重启，任务未完成，请重新提交生成"
        );
        if (count > 0) {
            log.warn("Marked interrupted image tasks as failed, count={}", count);
        }
    }
}
