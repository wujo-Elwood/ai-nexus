package com.rag.image.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.BusinessException;
import com.rag.entity.ModelProvider;
import com.rag.image.dto.ImageGenerateRequest;
import com.rag.image.entity.ImageHistory;
import com.rag.image.entity.ImageTask;
import com.rag.image.mapper.ImageHistoryMapper;
import com.rag.image.mapper.ImageTaskMapper;
import com.rag.image.vo.GeneratedImageItem;
import com.rag.image.vo.ImageGenerateResponse;
import com.rag.image.vo.ImageTaskResponse;
import com.rag.mapper.UserMapper;
import com.rag.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 生图服务
 * 负责创建异步生图任务、调用 OpenAI 兼容图片接口、保存图片历史和查询任务进度
 */
@Slf4j
@Service
public class ImageGenerateService {

    private static final String AUTO_IMAGE_SIZE = "auto";
    private static final int IMAGE_SIZE_UNIT = 16;
    private static final int MAX_IMAGE_EDGE = 3840;
    private static final int MIN_IMAGE_PIXELS = 655360;
    private static final int MAX_IMAGE_PIXELS = 8294400;
    private static final double MAX_IMAGE_RATIO = 3.0;
    private static final int MAX_IMAGE_COUNT = 6;
    private static final int MAX_REFERENCE_IMAGE_COUNT = 6;
    private static final int MAX_REFERENCE_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Pattern PIXEL_SIZE_PATTERN = Pattern.compile("^(\\d{2,5})\\s*x\\s*(\\d{2,5})$");
    private static final Pattern IMAGE_DATA_URL_PATTERN = Pattern.compile("^data:(image/(?:png|jpeg|jpg|webp));base64,(.+)$", Pattern.CASE_INSENSITIVE);

    private final ModelProviderService modelProviderService;
    private final ImageHistoryMapper imageHistoryMapper;
    private final ImageTaskMapper imageTaskMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final Executor imageGenerateExecutor;
    private final HttpClient httpClient;
    private final String uploadDir;

    /**
     * 创建 AI 生图服务
     */
    @Autowired
    public ImageGenerateService(ModelProviderService modelProviderService,
                                ImageHistoryMapper imageHistoryMapper,
                                ImageTaskMapper imageTaskMapper,
                                UserMapper userMapper,
                                ObjectMapper objectMapper,
                                @Qualifier("imageGenerateExecutor") Executor imageGenerateExecutor,
                                @Value("${file.upload-dir}") String uploadDir) {
        this.modelProviderService = modelProviderService;
        this.imageHistoryMapper = imageHistoryMapper;
        this.imageTaskMapper = imageTaskMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.imageGenerateExecutor = imageGenerateExecutor;
        this.uploadDir = uploadDir;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /**
     * 创建测试兼容版 AI 生图服务
     * 旧单元测试只验证同步生图内部逻辑，不需要任务 Mapper 和后台线程池
     */
    public ImageGenerateService(ModelProviderService modelProviderService,
                                ImageHistoryMapper imageHistoryMapper,
                                ObjectMapper objectMapper,
                                String uploadDir) {
        this(modelProviderService, imageHistoryMapper, null, null, objectMapper, Runnable::run, uploadDir);
    }

    /**
     * 创建异步生图任务
     */
    public ImageTaskResponse createTask(ImageGenerateRequest request, Long userId) {
        ImageGenerateRequest safeRequest = buildSafeRequest(request);
        ImageTask task = new ImageTask();
        task.setPrompt(safeRequest.getPrompt());
        task.setImageSize(safeRequest.getSize());
        task.setImageCount(safeRequest.getN());
        task.setTaskStatus("PENDING");
        task.setTaskMessage("任务已提交，等待后台生成");
        task.setProgress(0);
        task.setRequestJson(writeJsonQuietly(safeRequest));
        task.setCreatedBy(userId);

        int inserted = imageTaskMapper.insertIfRunningSlotAvailable(task);
        if (inserted == 0) {
            throw new BusinessException(400, "当前已有 6 个生图任务正在生成，请等待完成后再提交");
        }

        imageGenerateExecutor.execute(() -> runImageTask(task.getId(), safeRequest, userId));
        return toTaskResponse(task);
    }

    /**
     * 同步生成图片
     * 保留给测试和内部复用，页面默认使用异步任务入口
     */
    public ImageGenerateResponse generate(ImageGenerateRequest request, Long userId) {
        ImageGenerateRequest safeRequest = buildSafeRequest(request);
        ModelProvider provider = modelProviderService.getActive();
        return doGenerateWithProvider(safeRequest, provider, userId);
    }

    /**
     * 查询当前用户最近的生图任务
     */
    public List<ImageTaskResponse> listTasks(Long userId, Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        return imageTaskMapper.findByUserId(userId, safeLimit).stream().map(this::toTaskResponse).toList();
    }

    /**
     * 查询当前用户的单个生图任务
     */
    public ImageTaskResponse getTask(Long taskId, Long userId) {
        ImageTask task = imageTaskMapper.findByIdAndUserId(taskId, userId);
        if (task == null) {
            throw new BusinessException(404, "生图任务不存在");
        }
        return toTaskResponse(task);
    }

    /**
     * 查询用户生图历史
     */
    public List<ImageHistory> listHistory(Long userId, Integer limit) {
        int safeLimit = limit == null ? 30 : Math.max(1, Math.min(limit, 100));
        return imageHistoryMapper.findByUserId(userId, safeLimit);
    }

    /**
     * 查询用户自己的单条历史
     */
    public ImageHistory getHistory(Long id, Long userId) {
        ImageHistory history = imageHistoryMapper.findByIdAndUserId(id, userId);
        if (history == null) {
            throw new BusinessException(404, "生图历史不存在");
        }
        return history;
    }

    /**
     * 删除用户自己的生图历史
     */
    public void deleteHistory(Long id, Long userId) {
        ImageHistory history = getHistory(id, userId);
        imageHistoryMapper.deleteByIdAndUserId(id, userId);
        try {
            Files.deleteIfExists(Paths.get(history.getFilePath()));
        } catch (Exception e) {
            log.warn("Delete generated image file failed, id={}, path={}", id, history.getFilePath(), e);
        }
    }

    /**
     * 后台执行生图任务
     */
    private void runImageTask(Long taskId, ImageGenerateRequest safeRequest, Long userId) {
        try {
            ModelProvider provider = modelProviderService.getActive();
            imageTaskMapper.markStarted(taskId, provider.getName(), getImageModel(provider));
            imageTaskMapper.updateProgress(taskId, "模型正在生成图片", 35);
            ImageGenerateResponse response = doGenerateWithProvider(safeRequest, provider, userId);
            imageTaskMapper.markSuccess(taskId, "图片生成完成", response.getProviderName(), response.getModelName(), writeJsonQuietly(response));
        } catch (Exception e) {
            log.error("Image task failed, taskId={}", taskId, e);
            imageTaskMapper.markFailed(taskId, "图片生成失败", buildSafeErrorMessage(e));
        }
    }

    /**
     * 使用指定模型供应商执行真实生图逻辑
     */
    private ImageGenerateResponse doGenerateWithProvider(ImageGenerateRequest safeRequest, ModelProvider provider, Long userId) {
        List<GeneratedImageItem> images = callImageApi(provider, safeRequest);
        List<GeneratedImageItem> savedImages = saveGeneratedImages(images, provider, safeRequest, userId);
        return ImageGenerateResponse.builder()
                .providerName(provider.getName())
                .modelName(getImageModel(provider))
                .size(safeRequest.getSize())
                .images(savedImages)
                .build();
    }

    /**
     * 构建安全的生图请求
     */
    private ImageGenerateRequest buildSafeRequest(ImageGenerateRequest request) {
        String prompt = request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new BusinessException(400, "请输入生图提示词");
        }
        if (prompt.length() > 2000) {
            throw new BusinessException(400, "提示词不能超过 2000 个字符");
        }

        String size = normalizeImageSize(request.getSize());
        int count = request.getN() == null ? 1 : request.getN();
        if (count < 1 || count > MAX_IMAGE_COUNT) {
            throw new BusinessException(400, "一次只能生成 1 到 6 张图片");
        }

        ImageGenerateRequest safeRequest = new ImageGenerateRequest();
        safeRequest.setPrompt(prompt);
        safeRequest.setSize(size);
        safeRequest.setN(count);
        safeRequest.setReferenceImages(normalizeReferenceImages(request.getReferenceImages()));
        return safeRequest;
    }

    /**
     * 清洗并校验生图参考图
     */
    private List<String> normalizeReferenceImages(List<String> rawReferenceImages) {
        if (rawReferenceImages == null || rawReferenceImages.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> referenceImages = rawReferenceImages.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
        if (referenceImages.isEmpty()) {
            return Collections.emptyList();
        }
        if (referenceImages.size() > MAX_REFERENCE_IMAGE_COUNT) {
            throw new BusinessException(400, "参考图最多只能上传 6 张");
        }
        for (String referenceImage : referenceImages) {
            validateReferenceImage(referenceImage);
        }
        return referenceImages;
    }

    /**
     * 校验单张参考图格式和大小
     */
    private void validateReferenceImage(String referenceImage) {
        Matcher matcher = IMAGE_DATA_URL_PATTERN.matcher(referenceImage);
        if (!matcher.matches()) {
            throw new BusinessException(400, "参考图只支持 PNG、JPG、JPEG、WebP 格式");
        }
        byte[] imageBytes = decodeReferenceImageBytes(referenceImage);
        if (imageBytes.length == 0) {
            throw new BusinessException(400, "参考图内容不能为空");
        }
        if (imageBytes.length > MAX_REFERENCE_IMAGE_BYTES) {
            throw new BusinessException(400, "单张参考图不能超过 10MB");
        }
    }

    /**
     * 规整生图尺寸参数
     */
    private String normalizeImageSize(String rawSize) {
        String size = rawSize == null || rawSize.isBlank() ? AUTO_IMAGE_SIZE : rawSize.trim().toLowerCase(Locale.ROOT);
        if (AUTO_IMAGE_SIZE.equals(size)) {
            return AUTO_IMAGE_SIZE;
        }
        size = size.replace('×', 'x').replace('*', 'x');
        Matcher matcher = PIXEL_SIZE_PATTERN.matcher(size);
        if (!matcher.matches()) {
            throw new BusinessException(400, "图片尺寸格式不正确，请使用 auto 或 宽x高，例如 1024x1024");
        }
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        int[] normalizedSize = normalizeImagePixels(width, height);
        return normalizedSize[0] + "x" + normalizedSize[1];
    }

    /**
     * 规整图片宽高像素值
     */
    private int[] normalizeImagePixels(int rawWidth, int rawHeight) {
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw new BusinessException(400, "图片宽高必须大于 0");
        }
        int width = roundToImageUnit(rawWidth);
        int height = roundToImageUnit(rawHeight);
        for (int i = 0; i < 8; i++) {
            int oldWidth = width;
            int oldHeight = height;
            int maxEdge = Math.max(width, height);
            if (maxEdge > MAX_IMAGE_EDGE) {
                double scale = (double) MAX_IMAGE_EDGE / maxEdge;
                width = floorToImageUnit(width * scale);
                height = floorToImageUnit(height * scale);
            }
            double ratio = width >= height ? (double) width / height : (double) height / width;
            if (ratio > MAX_IMAGE_RATIO) {
                if (width >= height) {
                    height = ceilToImageUnit(width / MAX_IMAGE_RATIO);
                } else {
                    width = ceilToImageUnit(height / MAX_IMAGE_RATIO);
                }
            }
            long pixels = (long) width * height;
            if (pixels > MAX_IMAGE_PIXELS) {
                double scale = Math.sqrt((double) MAX_IMAGE_PIXELS / pixels);
                width = floorToImageUnit(width * scale);
                height = floorToImageUnit(height * scale);
            } else if (pixels < MIN_IMAGE_PIXELS) {
                double scale = Math.sqrt((double) MIN_IMAGE_PIXELS / pixels);
                width = ceilToImageUnit(width * scale);
                height = ceilToImageUnit(height * scale);
            }
            if (oldWidth == width && oldHeight == height) {
                break;
            }
        }
        return new int[]{width, height};
    }

    /**
     * 按 16 倍数四舍五入规整尺寸
     */
    private int roundToImageUnit(double value) {
        return Math.max(IMAGE_SIZE_UNIT, (int) Math.round(value / IMAGE_SIZE_UNIT) * IMAGE_SIZE_UNIT);
    }

    /**
     * 按 16 倍数向下规整尺寸
     */
    private int floorToImageUnit(double value) {
        return Math.max(IMAGE_SIZE_UNIT, (int) Math.floor(value / IMAGE_SIZE_UNIT) * IMAGE_SIZE_UNIT);
    }

    /**
     * 按 16 倍数向上规整尺寸
     */
    private int ceilToImageUnit(double value) {
        return Math.max(IMAGE_SIZE_UNIT, (int) Math.ceil(value / IMAGE_SIZE_UNIT) * IMAGE_SIZE_UNIT);
    }

    /**
     * 调用 OpenAI 兼容生图接口
     */
    private List<GeneratedImageItem> callImageApi(ModelProvider provider, ImageGenerateRequest request) {
        try {
            boolean hasReferenceImages = hasReferenceImages(request);
            String apiUrl = normalizeImageApiUrl(getImageBaseUrl(provider), hasReferenceImages);
            HttpRequest httpRequest = hasReferenceImages
                    ? buildImageEditHttpRequest(provider, request, apiUrl)
                    : buildImageGenerationHttpRequest(provider, request, apiUrl);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(500, buildProviderError(response));
            }
            return parseImageItems(response.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Image generation failed, provider={}, model={}", provider.getName(), provider.getModel(), e);
            throw new BusinessException(500, "生图接口调用失败：" + buildSafeErrorMessage(e));
        }
    }

    /**
     * 构建普通文生图 HTTP 请求
     */
    private HttpRequest buildImageGenerationHttpRequest(ModelProvider provider, ImageGenerateRequest request, String apiUrl) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new ImageApiRequestBody(getImageModel(provider), request.getPrompt(), request.getSize(), request.getN()));
        return HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + getImageApiKey(provider))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    /**
     * 构建带参考图的图片编辑 HTTP 请求
     */
    private HttpRequest buildImageEditHttpRequest(ModelProvider provider, ImageGenerateRequest request, String apiUrl) {
        String boundary = "----ai-nexus-image-" + UUID.randomUUID();
        List<byte[]> parts = buildMultipartBodyParts(provider, request, boundary);
        return HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + getImageApiKey(provider))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                .build();
    }

    /**
     * 构建图片编辑接口 multipart 请求分片
     */
    private List<byte[]> buildMultipartBodyParts(ModelProvider provider, ImageGenerateRequest request, String boundary) {
        List<byte[]> parts = new ArrayList<>();
        addTextPart(parts, boundary, "model", getImageModel(provider));
        addTextPart(parts, boundary, "prompt", request.getPrompt());
        addTextPart(parts, boundary, "size", request.getSize());
        addTextPart(parts, boundary, "n", String.valueOf(request.getN()));
        int index = 1;
        for (String referenceImage : request.getReferenceImages()) {
            String mimeType = readReferenceImageMimeType(referenceImage);
            byte[] imageBytes = decodeReferenceImageBytes(referenceImage);
            addFilePart(parts, boundary, "image[]", buildReferenceFileName(index, mimeType), mimeType, imageBytes);
            index++;
        }
        parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return parts;
    }

    /**
     * 添加 multipart 文本字段
     */
    private void addTextPart(List<byte[]> parts, String boundary, String name, String value) {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value)
                + "\r\n";
        parts.add(part.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 添加 multipart 文件字段
     */
    private void addFilePart(List<byte[]> parts, String boundary, String name, String fileName, String mimeType, byte[] bytes) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";
        parts.add(header.getBytes(StandardCharsets.UTF_8));
        parts.add(bytes);
        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判断请求是否带参考图
     */
    private boolean hasReferenceImages(ImageGenerateRequest request) {
        return request.getReferenceImages() != null && !request.getReferenceImages().isEmpty();
    }

    /**
     * 获取生图 API 地址
     */
    private String getImageBaseUrl(ModelProvider provider) {
        if (provider.getImageBaseUrl() != null && !provider.getImageBaseUrl().isBlank()) {
            return provider.getImageBaseUrl().trim();
        }
        return provider.getBaseUrl();
    }

    /**
     * 获取生图 API 密钥
     */
    private String getImageApiKey(ModelProvider provider) {
        if (provider.getImageApiKey() != null && !provider.getImageApiKey().isBlank()) {
            return provider.getImageApiKey().trim();
        }
        return provider.getApiKey();
    }

    /**
     * 获取生图模型名称
     */
    private String getImageModel(ModelProvider provider) {
        if (provider.getImageModel() != null && !provider.getImageModel().isBlank()) {
            return provider.getImageModel().trim();
        }
        return provider.getModel();
    }

    /**
     * 规范化图片生成接口地址
     */
    private String normalizeImageApiUrl(String rawBaseUrl, boolean hasReferenceImages) {
        String baseUrl = rawBaseUrl == null ? "" : rawBaseUrl.trim().replaceAll("/+$", "");
        if (baseUrl.isEmpty()) {
            throw new BusinessException(400, "模型供应商 API 地址不能为空");
        }
        String endpoint = hasReferenceImages ? "/images/edits" : "/images/generations";
        if (baseUrl.endsWith("/images/generations")) {
            return hasReferenceImages ? baseUrl.substring(0, baseUrl.length() - "/images/generations".length()) + endpoint : baseUrl;
        }
        if (baseUrl.endsWith("/images/edits")) {
            return hasReferenceImages ? baseUrl : baseUrl.substring(0, baseUrl.length() - "/images/edits".length()) + endpoint;
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + endpoint;
        }
        return baseUrl + "/v1" + endpoint;
    }

    /**
     * 解析图片结果
     */
    private List<GeneratedImageItem> parseImageItems(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
            throw new BusinessException(500, "生图接口没有返回图片数据");
        }
        List<GeneratedImageItem> images = new ArrayList<>();
        for (JsonNode item : dataNode) {
            String url = readText(item, "url");
            String b64Json = readText(item, "b64_json");
            if (!url.isEmpty() || !b64Json.isEmpty()) {
                images.add(buildImageItem(url, b64Json));
            }
        }
        if (images.isEmpty()) {
            throw new BusinessException(500, "生图接口返回了空图片结果");
        }
        return images;
    }

    /**
     * 构建单张图片结果
     */
    private GeneratedImageItem buildImageItem(String url, String b64Json) {
        if (b64Json != null && !b64Json.isBlank()) {
            return buildBase64ImageItem(url, b64Json);
        }
        return buildUrlImageItem(url);
    }

    /**
     * 构建 base64 图片结果
     */
    private GeneratedImageItem buildBase64ImageItem(String url, String rawBase64) {
        String mimeType = "image/png";
        String imageBase64 = rawBase64 == null ? "" : rawBase64.trim();
        if (imageBase64.startsWith("data:")) {
            int commaIndex = imageBase64.indexOf(',');
            String header = commaIndex > 0 ? imageBase64.substring(0, commaIndex) : "";
            if (commaIndex > 0 && header.contains(";base64")) {
                String headerMimeType = header.substring("data:".length(), header.indexOf(";base64"));
                mimeType = normalizeImageMimeType(headerMimeType);
                imageBase64 = imageBase64.substring(commaIndex + 1);
            }
        }
        imageBase64 = imageBase64.replaceAll("\\s+", "");
        return GeneratedImageItem.builder().url(url).b64Json(imageBase64).mimeType(mimeType).build();
    }

    /**
     * 构建 URL 图片结果并尽量转换成 base64
     */
    private GeneratedImageItem buildUrlImageItem(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return GeneratedImageItem.builder().url("").b64Json("").mimeType("image/png").build();
        }
        try {
            URI imageUri = URI.create(imageUrl);
            String scheme = imageUri.getScheme() == null ? "" : imageUri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme)) {
                return GeneratedImageItem.builder().url(imageUrl).b64Json("").mimeType("image/png").build();
            }
            HttpRequest request = HttpRequest.newBuilder().uri(imageUri).timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().length == 0) {
                log.warn("Download generated image failed, status={}, url={}", response.statusCode(), imageUrl);
                return GeneratedImageItem.builder().url(imageUrl).b64Json("").mimeType("image/png").build();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
            return GeneratedImageItem.builder()
                    .url(imageUrl)
                    .b64Json(Base64.getEncoder().encodeToString(response.body()))
                    .mimeType(normalizeImageMimeType(contentType))
                    .build();
        } catch (Exception e) {
            log.warn("Download generated image error, url={}", imageUrl, e);
            return GeneratedImageItem.builder().url(imageUrl).b64Json("").mimeType("image/png").build();
        }
    }

    /**
     * 保存生成图片和历史记录
     */
    private List<GeneratedImageItem> saveGeneratedImages(List<GeneratedImageItem> images, ModelProvider provider, ImageGenerateRequest request, Long userId) {
        List<GeneratedImageItem> savedImages = new ArrayList<>();
        for (GeneratedImageItem image : images) {
            savedImages.add(saveGeneratedImage(image, provider, request, userId));
        }
        return savedImages;
    }

    /**
     * 保存单张生成图片
     */
    private GeneratedImageItem saveGeneratedImage(GeneratedImageItem image, ModelProvider provider, ImageGenerateRequest request, Long userId) {
        if (image.getB64Json() == null || image.getB64Json().isBlank()) {
            return image;
        }
        try {
            String mimeType = image.getMimeType() == null || image.getMimeType().isBlank() ? "image/png" : image.getMimeType();
            byte[] imageBytes = Base64.getDecoder().decode(image.getB64Json());
            Path imageDir = buildUserImageDirectory(userId);
            Files.createDirectories(imageDir);
            String fileName = UUID.randomUUID() + "." + getFileExtension(mimeType);
            Path filePath = imageDir.resolve(fileName);
            Files.write(filePath, imageBytes);

            ImageHistory history = new ImageHistory();
            history.setPrompt(request.getPrompt());
            history.setProviderName(provider.getName());
            history.setModelName(getImageModel(provider));
            history.setImageSize(request.getSize());
            history.setFileName(fileName);
            history.setFilePath(filePath.toString());
            history.setMimeType(mimeType);
            history.setFileSize((long) imageBytes.length);
            history.setCreatedBy(userId);
            imageHistoryMapper.insert(history);

            image.setHistoryId(history.getId());
            image.setViewUrl("/api/image/history/" + history.getId() + "/view");
            image.setDownloadUrl("/api/image/history/" + history.getId() + "/download");
            return image;
        } catch (Exception e) {
            log.warn("Save generated image history failed, userId={}", userId, e);
            return image;
        }
    }

    /**
     * 根据媒体类型获取文件扩展名
     */
    /**
     * 构建用户生图保存目录
     */
    private Path buildUserImageDirectory(Long userId) {
        return getProjectSiblingPicDirectory().resolve(resolveUserFolderName(userId));
    }

    /**
     * 获取项目同级 pic 目录
     */
    private Path getProjectSiblingPicDirectory() {
        Path projectDir = Paths.get("").toAbsolutePath().normalize();
        if ("rag-server".equalsIgnoreCase(projectDir.getFileName().toString()) && projectDir.getParent() != null) {
            projectDir = projectDir.getParent();
        }
        Path parentDir = projectDir.getParent();
        return parentDir == null ? projectDir.resolve("pic") : parentDir.resolve("pic");
    }

    /**
     * 解析用户专属文件夹名称
     */
    private String resolveUserFolderName(Long userId) {
        String username = "";
        try {
            if (userMapper != null && userId != null) {
                com.rag.entity.SysUser user = userMapper.findById(userId);
                username = user == null ? "" : user.getUsername();
            }
        } catch (Exception e) {
            log.warn("Resolve image user folder failed, userId={}", userId, e);
        }
        if (username == null || username.isBlank()) {
            username = "user-" + (userId == null ? "unknown" : userId);
        }
        return sanitizeFolderName(username);
    }

    /**
     * 清洗文件夹名称
     */
    private String sanitizeFolderName(String rawName) {
        String safeName = rawName == null ? "" : rawName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        safeName = safeName.replaceAll("\\s+", "_").replaceAll("\\.+$", "");
        if (safeName.isBlank()) {
            return "user-unknown";
        }
        return safeName.length() > 80 ? safeName.substring(0, 80) : safeName;
    }

    private String getFileExtension(String mimeType) {
        if ("image/jpeg".equalsIgnoreCase(mimeType) || "image/jpg".equalsIgnoreCase(mimeType)) {
            return "jpg";
        }
        if ("image/webp".equalsIgnoreCase(mimeType)) {
            return "webp";
        }
        if ("image/gif".equalsIgnoreCase(mimeType)) {
            return "gif";
        }
        return "png";
    }

    /**
     * 读取参考图 data URL 中的媒体类型
     */
    private String readReferenceImageMimeType(String referenceImage) {
        Matcher matcher = IMAGE_DATA_URL_PATTERN.matcher(referenceImage);
        if (!matcher.matches()) {
            throw new BusinessException(400, "参考图只支持 PNG、JPG、JPEG、WebP 格式");
        }
        return normalizeReferenceImageMimeType(matcher.group(1));
    }

    /**
     * 解码参考图 base64 内容
     */
    private byte[] decodeReferenceImageBytes(String referenceImage) {
        int commaIndex = referenceImage.indexOf(',');
        if (commaIndex < 0 || commaIndex == referenceImage.length() - 1) {
            throw new BusinessException(400, "参考图内容不能为空");
        }
        try {
            return Base64.getDecoder().decode(referenceImage.substring(commaIndex + 1).replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "参考图 base64 内容不正确");
        }
    }

    /**
     * 统一参考图媒体类型
     */
    private String normalizeReferenceImageMimeType(String mimeType) {
        if ("image/jpg".equalsIgnoreCase(mimeType)) {
            return "image/jpeg";
        }
        return mimeType.toLowerCase(Locale.ROOT);
    }

    /**
     * 生成参考图上传文件名
     */
    private String buildReferenceFileName(int index, String mimeType) {
        String extension = switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
        return "reference-" + index + "." + extension;
    }

    /**
     * 规范化图片媒体类型
     */
    private String normalizeImageMimeType(String contentType) {
        String mimeType = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (Set.of("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif").contains(mimeType)) {
            return "image/jpg".equals(mimeType) ? "image/jpeg" : mimeType;
        }
        return "image/png";
    }

    /**
     * 读取 JSON 文本字段
     */
    private String readText(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        return fieldNode == null || fieldNode.isNull() ? "" : fieldNode.asText("");
    }

    /**
     * 构建供应商错误信息
     */
    private String buildProviderError(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (body.length() > 800) {
            body = body.substring(0, 800) + "...";
        }
        if (body.contains("requires an image model")) {
            return "当前配置的不是生图模型，请到模型设置里填写生图模型名称，不要使用聊天模型名称。供应商返回：" + body;
        }
        return "生图模型调用失败，HTTP " + response.statusCode() + "：" + body;
    }

    /**
     * 构建安全错误信息
     */
    private String buildSafeErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "请检查生图模型配置";
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    /**
     * 把任务实体转换为前端展示对象
     */
    private ImageTaskResponse toTaskResponse(ImageTask task) {
        return ImageTaskResponse.builder()
                .id(task.getId())
                .prompt(task.getPrompt())
                .imageSize(task.getImageSize())
                .imageCount(task.getImageCount())
                .taskStatus(task.getTaskStatus())
                .taskMessage(task.getTaskMessage())
                .progress(task.getProgress())
                .errorMessage(task.getErrorMessage())
                .result(readTaskResult(task.getResponseJson()))
                .createTime(task.getCreateTime())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .build();
    }

    /**
     * 读取任务结果 JSON
     */
    private ImageGenerateResponse readTaskResult(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseJson, ImageGenerateResponse.class);
        } catch (Exception e) {
            log.warn("Read image task result failed", e);
            return null;
        }
    }

    /**
     * 安全写入 JSON 字符串
     */
    private String writeJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Write image task json failed", e);
            return "{}";
        }
    }

    /**
     * OpenAI 兼容生图请求体
     */
    private record ImageApiRequestBody(String model, String prompt, String size, Integer n) {
    }
}
