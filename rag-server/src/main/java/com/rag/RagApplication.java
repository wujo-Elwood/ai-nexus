package com.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * RAG 知识库系统启动类
 * 配置 MyBatis Mapper 扫描路径和异步任务线程池
 */
@SpringBootApplication
@MapperScan({"com.rag.mapper", "com.rag.extract.mapper"})
public class RagApplication {

    /**
     * 文件处理线程池
     * 用于后台异步执行文件解析、分块、向量化等耗时操作
     * 固定 2 个线程，避免文件上传请求被阻塞
     */
    @Bean("fileProcessExecutor")
    public Executor fileProcessExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    /**
     * 聊天流式输出线程池
     * 用于后台执行大模型流式调用和 SSE 推送
     * 固定 4 个线程，支持多个用户同时进行流式对话
     */
    @Bean("chatStreamExecutor")
    public Executor chatStreamExecutor() {
        return Executors.newFixedThreadPool(4);
    }

    /**
     * 应用入口
     */
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
