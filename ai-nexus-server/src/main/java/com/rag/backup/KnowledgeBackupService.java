package com.rag.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.BusinessException;
import com.rag.entity.KbFile;
import com.rag.service.FileService;
import com.rag.service.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** 知识库导出、备份和恢复服务，所有文件路径都限制在本地上传目录。 */
@Service
public class KnowledgeBackupService {
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FileService fileService;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Value("${file.upload-dir}") private String uploadDir;

    /** 将当前版本文件和元数据导出为 ZIP。 */
    public Path export(Long kbId, Long userId) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path backupDir = root.resolve("backups").normalize();
            Files.createDirectories(backupDir);
            Path target = backupDir.resolve("kb-" + kbId + "-" + System.currentTimeMillis() + ".zip");
            List<KbFile> files = fileService.getByKbId(kbId);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                Map<String,Object> manifest = new LinkedHashMap<>();
                List<Map<String,Object>> metadata = new ArrayList<>();
                for (KbFile file : files) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("id", file.getId()); item.put("fileName", file.getFileName()); item.put("fileType", file.getFileType());
                    item.put("fileSize", file.getFileSize()); item.put("fileSha256", file.getFileSha256()); item.put("versionNo", file.getVersionNo());
                    item.put("category", file.getCategory()); item.put("folderId", file.getFolderId()); item.put("status", file.getStatus());
                    metadata.add(item);
                }
                manifest.put("version", 1); manifest.put("kbId", kbId); manifest.put("files", metadata);
                zip.write(objectMapper.writeValueAsBytes(manifest)); zip.closeEntry();
                for (KbFile file : files) {
                    Path source = Paths.get(file.getFilePath()).toAbsolutePath().normalize();
                    if (!source.startsWith(root) || !Files.isRegularFile(source)) continue;
                    String safeName = Paths.get(file.getFileName()).getFileName().toString();
                    zip.putNextEntry(new ZipEntry("files/" + file.getId() + "/" + safeName));
                    Files.copy(source, zip); zip.closeEntry();
                }
            }
            return target;
        } catch (IOException e) {
            throw new BusinessException("知识库导出失败");
        }
    }

    /** 从 ZIP 恢复文件，按摘要幂等，重复文件不会再次创建。 */
    public int restore(Long kbId, Long userId, MultipartFile multipartFile) {
        knowledgeBaseService.checkManageAccess(kbId, userId);
        Path temp = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("restore-" + UUID.randomUUID() + ".zip");
        int restored = 0;
        try {
            Files.createDirectories(temp.getParent()); multipartFile.transferTo(temp.toFile());
            Map<String,Object> manifest;
            Map<String,Path> extracted = new HashMap<>();
            Path extractRoot = temp.resolveSibling(temp.getFileName().toString().replace(".zip", ""));
            Files.createDirectories(extractRoot);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(temp))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\','/');
                    if (entry.isDirectory() || name.contains("..") || name.startsWith("/")) continue;
                    Path out = extractRoot.resolve(name).normalize();
                    if (!out.startsWith(extractRoot)) throw new BusinessException(400, "备份包含非法路径");
                    Files.createDirectories(out.getParent()); Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                    if (name.startsWith("files/")) extracted.put(name.substring(name.lastIndexOf('/') + 1), out);
                }
            }
            manifest = objectMapper.readValue(Files.readAllBytes(extractRoot.resolve("manifest.json")), new TypeReference<>() {});
            Object rawFiles = manifest.get("files");
            if (rawFiles instanceof List<?> list) {
                for (Object raw : list) {
                    if (!(raw instanceof Map<?,?> meta)) continue;
                    String id = String.valueOf(meta.get("id")); Path source = extracted.get(String.valueOf(meta.get("fileName")));
                    if (source == null || !Files.isRegularFile(source)) continue;
                    Path finalPath = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(UUID.randomUUID() + "_" + Paths.get(String.valueOf(meta.get("fileName"))).getFileName());
                    Files.move(source, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    KbFile file = fileService.registerSavedFile(kbId, String.valueOf(meta.get("fileName")), String.valueOf(meta.get("fileType")), Files.size(finalPath), finalPath);
                    if (file != null) restored++;
                }
            }
            return restored;
        } catch (BusinessException e) { throw e;
        } catch (Exception e) { throw new BusinessException(400, "知识库恢复失败");
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            try {
                Path extractRoot = temp.resolveSibling(temp.getFileName().toString().replace(".zip", ""));
                if (Files.exists(extractRoot)) Files.walk(extractRoot).sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }
}
