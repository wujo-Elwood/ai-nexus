package com.rag.image.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.image.dto.ImageGenerateRequest;
import com.rag.image.entity.ImageHistory;
import com.rag.image.mapper.ImageHistoryMapper;
import com.rag.image.vo.GeneratedImageItem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 生图服务测试
 * 验证生图结果解析和图片下载兼容逻辑
 */
class ImageGenerateServiceTest {

    @TempDir
    Path tempDir;

    /**
     * 测试自动尺寸会原样传给模型接口
     */
    @Test
    void buildSafeRequestShouldAcceptAutoSize() throws Exception {
        // 第1步：准备自动尺寸请求
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("测试自动尺寸");
        request.setSize("auto");
        request.setN(1);
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：清洗请求参数
        ImageGenerateRequest safeRequest = buildSafeRequest(service, request);

        // 第3步：确认自动尺寸不会被改成固定像素值
        assertEquals("auto", safeRequest.getSize());
    }

    /**
     * 测试按比例得到的 4K 横图尺寸可以通过后端校验
     */
    @Test
    void buildSafeRequestShouldAcceptGeneratedPixelSize() throws Exception {
        // 第1步：准备 4K 16:9 尺寸请求
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("测试 4K 横图");
        request.setSize("3840x2160");
        request.setN(1);
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：清洗请求参数
        ImageGenerateRequest safeRequest = buildSafeRequest(service, request);

        // 第3步：确认合法像素尺寸可以继续传给模型接口
        assertEquals("3840x2160", safeRequest.getSize());
    }

    /**
     * 测试非法尺寸会被后端拦截
     */
    @Test
    void buildSafeRequestShouldRejectInvalidPixelSize() {
        // 第1步：准备格式不正确的尺寸请求
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("测试非法尺寸");
        request.setSize("abc");
        request.setN(1);
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：确认后端会拒绝非法尺寸
        assertThrows(BusinessException.class, () -> buildSafeRequest(service, request));
    }

    /**
     * 测试参考图最多允许上传 6 张，并且清洗后会保留到请求参数中
     */
    @Test
    void buildSafeRequestShouldAcceptSixReferenceImages() throws Exception {
        // 第1步：准备 6 张 data URL 格式的参考图
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("参考图生成测试");
        request.setSize("auto");
        request.setN(1);
        request.setReferenceImages(List.of(
                buildImageDataUrl("image/png", "1"),
                buildImageDataUrl("image/jpeg", "2"),
                buildImageDataUrl("image/webp", "3"),
                buildImageDataUrl("image/png", "4"),
                buildImageDataUrl("image/jpeg", "5"),
                buildImageDataUrl("image/webp", "6")
        ));
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：清洗请求参数
        ImageGenerateRequest safeRequest = buildSafeRequest(service, request);

        // 第3步：确认 6 张参考图都会被保留
        assertEquals(6, safeRequest.getReferenceImages().size());
        assertEquals(buildImageDataUrl("image/png", "1"), safeRequest.getReferenceImages().get(0));
    }

    /**
     * 测试参考图超过 6 张时会被后端拒绝
     */
    @Test
    void buildSafeRequestShouldRejectMoreThanSixReferenceImages() {
        // 第1步：准备 7 张参考图
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("参考图数量测试");
        request.setSize("auto");
        request.setN(1);
        request.setReferenceImages(List.of(
                buildImageDataUrl("image/png", "1"),
                buildImageDataUrl("image/png", "2"),
                buildImageDataUrl("image/png", "3"),
                buildImageDataUrl("image/png", "4"),
                buildImageDataUrl("image/png", "5"),
                buildImageDataUrl("image/png", "6"),
                buildImageDataUrl("image/png", "7")
        ));
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：确认后端会拒绝过多参考图
        assertThrows(BusinessException.class, () -> buildSafeRequest(service, request));
    }

    /**
     * 测试非图片 data URL 不能作为参考图提交给生图接口
     */
    @Test
    void buildSafeRequestShouldRejectNonImageReference() {
        // 第1步：准备文本类型的 data URL
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("参考图类型测试");
        request.setSize("auto");
        request.setN(1);
        request.setReferenceImages(List.of("data:text/plain;base64,MTIz"));
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：确认后端会拒绝非图片参考图
        assertThrows(BusinessException.class, () -> buildSafeRequest(service, request));
    }

    /**
     * 测试有参考图时 multipart 请求体会把参考图传给模型供应商
     */
    @Test
    void buildMultipartBodyPartsShouldIncludeReferenceImages() throws Exception {
        // 第1步：准备带参考图的安全请求
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("按参考图生成");
        request.setSize("1024x1024");
        request.setN(1);
        request.setReferenceImages(List.of(buildImageDataUrl("image/png", "1")));
        ModelProvider provider = new ModelProvider();
        provider.setModel("gpt-image-2");
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：构建发送给模型供应商的 multipart 请求体
        String requestBody = buildMultipartBodyPartsText(service, provider, request);

        // 第3步：确认请求体里包含参考图字段
        assertTrue(requestBody.contains("name=\"model\""));
        assertTrue(requestBody.contains("name=\"prompt\""));
        assertTrue(requestBody.contains("name=\"image[]\"; filename=\"reference-1.png\""));
        assertTrue(requestBody.contains("Content-Type: image/png"));
    }

    /**
     * 测试有参考图时会切换到图片编辑接口
     */
    @Test
    void normalizeImageApiUrlShouldUseEditsEndpointWhenReferenceImagesExist() throws Exception {
        // 第1步：准备服务实例
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：分别生成无参考图和有参考图的接口地址
        String generationUrl = normalizeImageApiUrl(service, "https://zyyc.mxou.cn/v1", false);
        String editUrl = normalizeImageApiUrl(service, "https://zyyc.mxou.cn/v1", true);

        // 第3步：确认普通生图和参考图生图走不同接口
        assertEquals("https://zyyc.mxou.cn/v1/images/generations", generationUrl);
        assertEquals("https://zyyc.mxou.cn/v1/images/edits", editUrl);
    }

    /**
     * 测试自定义尺寸会规整成模型支持的合法像素值
     */
    @Test
    void buildSafeRequestShouldNormalizeCustomPixelSize() throws Exception {
        // 第1步：准备不是 16 倍数的自定义尺寸
        ImageGenerateRequest request = new ImageGenerateRequest();
        request.setPrompt("测试自定义尺寸");
        request.setSize("1025x1025");
        request.setN(1);
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：清洗请求参数
        ImageGenerateRequest safeRequest = buildSafeRequest(service, request);

        // 第3步：确认尺寸被规整成 16 的倍数
        assertEquals("1024x1024", safeRequest.getSize());
    }

    /**
     * 测试模型只返回图片 URL 时，后端会转成 base64 方便前端稳定下载
     */
    @Test
    void parseImageItemsShouldDownloadUrlImageForFrontendDownload() throws Exception {
        // 第1步：启动一个本地图片服务模拟模型返回的远程图片
        byte[] imageBytes = new byte[]{1, 2, 3, 4, 5};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, imageBytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(imageBytes);
            }
        });
        server.start();

        try {
            // 第2步：解析只包含 URL 的模型响应
            String imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png";
            String responseBody = "{\"data\":[{\"url\":\"" + imageUrl + "\"}]}";
            ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());
            List<GeneratedImageItem> images = parseImageItems(service, responseBody);

            // 第3步：确认 URL 图片已经被转换成可下载的 base64
            assertEquals(1, images.size());
            assertEquals(imageUrl, images.get(0).getUrl());
            assertEquals(Base64.getEncoder().encodeToString(imageBytes), images.get(0).getB64Json());
            assertEquals("image/png", images.get(0).getMimeType());
        } finally {
            // 第4步：关闭本地图片服务
            server.stop(0);
        }
    }

    /**
     * 测试模型返回 data URL 格式的 base64 时，后端会拆出纯 base64 和真实图片类型
     */
    @Test
    void parseImageItemsShouldSupportBase64DataUrl() throws Exception {
        // 第1步：准备 data URL 格式的模型响应
        byte[] imageBytes = new byte[]{6, 7, 8};
        String dataUrl = "data:image/webp;base64," + Base64.getEncoder().encodeToString(imageBytes);
        String responseBody = "{\"data\":[{\"b64_json\":\"" + dataUrl + "\"}]}";
        ImageGenerateService service = new ImageGenerateService(null, null, new ObjectMapper(), tempDir.toString());

        // 第2步：解析图片结果
        List<GeneratedImageItem> images = parseImageItems(service, responseBody);

        // 第3步：确认结果已经变成前端和历史保存都可用的纯 base64
        assertEquals(1, images.size());
        assertEquals(Base64.getEncoder().encodeToString(imageBytes), images.get(0).getB64Json());
        assertEquals("image/webp", images.get(0).getMimeType());
    }

    /**
     * 测试生成后的 base64 图片会保存到磁盘并写入历史记录
     */
    @Test
    void saveGeneratedImagesShouldWriteFileAndReturnHistoryUrl() throws Exception {
        // 第1步：准备一张 base64 图片和历史 Mapper
        byte[] imageBytes = new byte[]{9, 8, 7};
        GeneratedImageItem image = GeneratedImageItem.builder()
                .b64Json(Base64.getEncoder().encodeToString(imageBytes))
                .mimeType("image/png")
                .build();
        MemoryImageHistoryMapper mapper = new MemoryImageHistoryMapper();
        ImageGenerateService service = new ImageGenerateService(null, mapper, new ObjectMapper(), tempDir.toString());

        // 第2步：通过反射调用保存图片方法
        List<GeneratedImageItem> savedImages = saveGeneratedImages(service, List.of(image));

        // 第3步：验证图片文件和历史访问地址都存在
        assertEquals(1, savedImages.size());
        assertEquals(1L, savedImages.get(0).getHistoryId());
        assertEquals("/api/image/history/1/view", savedImages.get(0).getViewUrl());
        assertEquals("/api/image/history/1/download", savedImages.get(0).getDownloadUrl());
        assertTrue(Files.exists(Path.of(mapper.history.getFilePath())));
        assertEquals(imageBytes.length, Files.readAllBytes(Path.of(mapper.history.getFilePath())).length);
    }

    /**
     * 通过反射调用图片结果解析方法
     */
    @SuppressWarnings("unchecked")
    private List<GeneratedImageItem> parseImageItems(ImageGenerateService service, String responseBody) throws Exception {
        // 第1步：读取私有解析方法
        Method method = ImageGenerateService.class.getDeclaredMethod("parseImageItems", String.class);
        method.setAccessible(true);
        // 第2步：返回解析后的图片列表
        return (List<GeneratedImageItem>) method.invoke(service, responseBody);
    }

    /**
     * 通过反射调用请求参数清洗方法
     */
    private ImageGenerateRequest buildSafeRequest(ImageGenerateService service, ImageGenerateRequest request) throws Exception {
        // 第1步：读取私有请求清洗方法
        Method method = ImageGenerateService.class.getDeclaredMethod("buildSafeRequest", ImageGenerateRequest.class);
        method.setAccessible(true);
        // 第2步：返回清洗后的请求参数
        try {
            return (ImageGenerateRequest) method.invoke(service, request);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    /**
     * 通过反射调用 multipart 请求体构建方法并转成文本
     */
    @SuppressWarnings("unchecked")
    private String buildMultipartBodyPartsText(ImageGenerateService service, ModelProvider provider, ImageGenerateRequest request) throws Exception {
        // 第1步：读取私有 multipart 请求体构建方法
        Method method = ImageGenerateService.class.getDeclaredMethod("buildMultipartBodyParts", ModelProvider.class, ImageGenerateRequest.class, String.class);
        method.setAccessible(true);
        // 第2步：把请求体分片拼接成文本，方便断言字段
        List<byte[]> parts = (List<byte[]>) method.invoke(service, provider, request, "test-boundary");
        StringBuilder builder = new StringBuilder();
        for (byte[] part : parts) {
            builder.append(new String(part, StandardCharsets.ISO_8859_1));
        }
        return builder.toString();
    }

    /**
     * 通过反射调用生图接口地址规整方法
     */
    private String normalizeImageApiUrl(ImageGenerateService service, String rawBaseUrl, boolean hasReferenceImages) throws Exception {
        // 第1步：读取私有地址规整方法
        Method method = ImageGenerateService.class.getDeclaredMethod("normalizeImageApiUrl", String.class, boolean.class);
        method.setAccessible(true);
        // 第2步：返回规整后的接口地址
        return (String) method.invoke(service, rawBaseUrl, hasReferenceImages);
    }

    /**
     * 构建测试用图片 data URL
     */
    private String buildImageDataUrl(String mimeType, String content) {
        // 第1步：把短文本编码成最小可用的 base64 图片数据
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(content.getBytes());
    }

    /**
     * 通过反射调用图片保存方法
     */
    @SuppressWarnings("unchecked")
    private List<GeneratedImageItem> saveGeneratedImages(ImageGenerateService service, List<GeneratedImageItem> images) throws Exception {
        // 第1步：准备反射入参
        com.rag.entity.ModelProvider provider = new com.rag.entity.ModelProvider();
        provider.setName("测试供应商");
        provider.setModel("gpt-image-1");
        com.rag.image.dto.ImageGenerateRequest request = new com.rag.image.dto.ImageGenerateRequest();
        request.setPrompt("测试提示词");
        request.setSize("1024x1024");
        request.setN(1);
        // 第2步：调用私有保存方法
        Method method = ImageGenerateService.class.getDeclaredMethod(
                "saveGeneratedImages",
                List.class,
                com.rag.entity.ModelProvider.class,
                com.rag.image.dto.ImageGenerateRequest.class,
                Long.class
        );
        method.setAccessible(true);
        return (List<GeneratedImageItem>) method.invoke(service, images, provider, request, 100L);
    }

    /**
     * 内存版生图历史 Mapper
     */
    private static class MemoryImageHistoryMapper implements ImageHistoryMapper {
        private ImageHistory history;

        /** 新增生图历史记录 */
        @Override
        public int insert(ImageHistory history) {
            this.history = history;
            this.history.setId(1L);
            return 1;
        }

        /** 查询用户自己的生图历史 */
        @Override
        public List<ImageHistory> findByUserId(Long userId, Integer limit) {
            return List.of();
        }

        /** 根据编号和用户查询生图历史 */
        @Override
        public ImageHistory findByIdAndUserId(Long id, Long userId) {
            return history;
        }

        /** 根据编号查询生图历史 */
        @Override
        public ImageHistory findById(Long id) {
            return history;
        }

        /** 删除用户自己的生图历史 */
        @Override
        public int deleteByIdAndUserId(Long id, Long userId) {
            return 1;
        }
    }
}
