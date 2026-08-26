package com.rag.controller;

import com.rag.entity.KbFile;
import com.rag.service.FileService;
import com.rag.service.KnowledgeBaseService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import com.rag.catalog.CatalogService;

/**
 * 文件控制器
 * 提供文件上传、查询、删除和重新处理接口
 * 所有操作都校验用户对知识库的访问权限
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private CatalogService catalogService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    /**
     * 上传文件到指定知识库
     * 后台异步执行解析、分块、向量化
     */
    @PostMapping("/upload")
    public Result<KbFile> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam("kbId") Long kbId,
                                 HttpServletRequest httpRequest) {
        // 第1步：校验当前用户是否有权管理知识库
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkManageAccess(kbId, userId);
        // 第2步：保存文件并提交后台处理
        KbFile kbFile = fileService.upload(kbId, file);
        return Result.success(kbFile);
    }

    /** 查询知识库下的所有文件列表 */
    @GetMapping("/list/{kbId}")
    public Result<List<KbFile>> list(@PathVariable Long kbId, HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkAccess(kbId, userId);
        List<KbFile> files = fileService.getByKbId(kbId);
        return Result.success(files);
    }

    /** 查询文件历史版本。 */
    @GetMapping("/{id}/versions")
    public Result<List<KbFile>> versions(@PathVariable Long id, HttpServletRequest request) {
        KbFile file = fileService.getById(id);
        knowledgeBaseService.checkAccess(file.getKbId(), (Long) request.getAttribute("userId"));
        return Result.success(fileService.getVersions(id));
    }

    /** 回滚到指定文件版本。 */
    @PostMapping("/{id}/rollback")
    public Result<KbFile> rollback(@PathVariable Long id, HttpServletRequest request) {
        KbFile file = fileService.getById(id);
        knowledgeBaseService.checkManageAccess(file.getKbId(), (Long) request.getAttribute("userId"));
        return Result.success(fileService.rollback(id));
    }

    /** 更新文件目录和分类。 */
    @PutMapping("/{id}/catalog")
    public Result<Void> catalog(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        KbFile file = fileService.getById(id);
        knowledgeBaseService.checkManageAccess(file.getKbId(), (Long) request.getAttribute("userId"));
        Long folderId = body.get("folderId") == null ? null : Long.valueOf(body.get("folderId").toString());
        fileService.updateCatalog(id, folderId, body.get("category") == null ? null : body.get("category").toString());
        return Result.success();
    }

    /** 替换文件标签。 */
    @PutMapping("/{id}/tags")
    public Result<List<Map<String,Object>>> tags(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        KbFile file = fileService.getById(id);
        knowledgeBaseService.checkManageAccess(file.getKbId(), (Long) request.getAttribute("userId"));
        List<Long> tagIds = new java.util.ArrayList<>();
        Object raw = body.get("tagIds");
        if (raw instanceof List<?> values) for (Object value : values) tagIds.add(Long.valueOf(value.toString()));
        return Result.success(catalogService.setFileTags(id, tagIds, (Long) request.getAttribute("userId")));
    }

    /** 查询文件标签。 */
    @GetMapping("/{id}/tags")
    public Result<List<Map<String,Object>>> getTags(@PathVariable Long id, HttpServletRequest request) {
        KbFile file = fileService.getById(id);
        knowledgeBaseService.checkAccess(file.getKbId(), (Long) request.getAttribute("userId"));
        return Result.success(catalogService.listFileTags(id));
    }

    /** 根据文件ID查询文件详情 */
    @GetMapping("/{id}")
    public Result<KbFile> getById(@PathVariable Long id, HttpServletRequest httpRequest) {
        // 第1步：查询文件并确定所属知识库
        KbFile file = fileService.getById(id);
        // 第2步：校验当前用户是否有权读取文件
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkAccess(file.getKbId(), userId);
        return Result.success(file);
    }

    /** 重新处理文件：清除旧切片和向量，重新解析和向量化 */
    @PostMapping("/{id}/reprocess")
    public Result<Void> reprocess(@PathVariable Long id, HttpServletRequest httpRequest) {
        // 第1步：查询文件并校验知识库管理权限
        KbFile file = fileService.getById(id);
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkManageAccess(file.getKbId(), userId);
        // 第2步：提交重新处理任务
        fileService.reprocess(id);
        return Result.success();
    }

    /** 删除文件及其切片和向量 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        // 第1步：查询文件并校验知识库管理权限
        KbFile file = fileService.getById(id);
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkManageAccess(file.getKbId(), userId);
        // 第2步：删除文件、文本切片和向量
        fileService.delete(id);
        return Result.success();
    }

    /**
     * 以内联方式预览知识库文件
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable Long id, HttpServletRequest httpRequest) {
        return buildFileResponse(id, httpRequest, false);
    }

    /**
     * 以附件方式下载知识库文件
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, HttpServletRequest httpRequest) {
        return buildFileResponse(id, httpRequest, true);
    }

    /**
     * 构造受权限保护的文件响应
     */
    private ResponseEntity<Resource> buildFileResponse(Long id, HttpServletRequest httpRequest, boolean attachment) {
        KbFile file = fileService.getById(id);
        Long userId = (Long) httpRequest.getAttribute("userId");
        knowledgeBaseService.checkAccess(file.getKbId(), userId);
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path filePath = Paths.get(file.getFilePath()).toAbsolutePath().normalize();
        if (!filePath.startsWith(root) || !Files.isRegularFile(filePath)) {
            throw new com.rag.common.BusinessException(404, "File content not found");
        }
        Resource resource = new FileSystemResource(filePath);
        MediaType mediaType = MediaTypeFactory.getMediaType(file.getFileName())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        ContentDisposition disposition = attachment
                ? ContentDisposition.attachment().filename(file.getFileName(), StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(file.getFileName(), StandardCharsets.UTF_8).build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(disposition);
        return ResponseEntity.ok().headers(headers).body(resource);
    }
}
