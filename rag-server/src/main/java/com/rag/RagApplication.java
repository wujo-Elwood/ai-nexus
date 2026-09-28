package com.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RAG 知识库系统启动类
 * 配置 MyBatis Mapper 扫描路径和异步任务线程池
 */
@SpringBootApplication
@EnableScheduling
    @MapperScan({"com.rag.mapper", "com.rag.extract.mapper", "com.rag.agent.mapper", "com.rag.image.mapper", "com.rag.rbac.mapper", "com.rag.catalog", "com.rag.task", "com.rag.eval"})
public class RagApplication {

    /**
     * 文件处理线程池
     * 用于后台异步执行文件解析、分块、向量化等耗时操作
     * 固定 2 个线程，避免文件上传请求被阻塞
     */
    @Bean("fileProcessExecutor")
    public ThreadPoolExecutor fileProcessExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    /**
     * 聊天流式输出线程池
     * 用于后台执行大模型流式调用和 SSE 推送
     * 固定 4 个线程，支持多个用户同时进行流式对话
     */
    @Bean("chatStreamExecutor")
    public ThreadPoolExecutor chatStreamExecutor() {
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    /** 知识缺口分析线程池，用于隔离定时和手动报告生成任务。 */
    @Bean("knowledgeGapAnalysisExecutor")
    public ThreadPoolExecutor knowledgeGapAnalysisExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    /**
     * AI 生图任务线程池
     * 用于后台执行图片生成任务，让页面切换后仍然可以继续查询任务状态
     */
    @Bean("imageGenerateExecutor")
    public ThreadPoolExecutor imageGenerateExecutor() {
        return new ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    /**
     * 应用入口
     */
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
