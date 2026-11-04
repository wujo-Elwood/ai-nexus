package com.rag.backup;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Path;

/** 知识库导入导出接口。 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBackupController {
    @Autowired private KnowledgeBackupService backupService;

    /** 下载知识库备份 ZIP。 */
    @GetMapping("/{kbId}/backup/export")
    public ResponseEntity<Resource> export(@PathVariable Long kbId, HttpServletRequest request) {
        Path path = backupService.export(kbId, (Long) request.getAttribute("userId"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=knowledge-base-" + kbId + ".zip")
                .body(new FileSystemResource(path));
    }

    /** 上传 ZIP 并恢复知识库文件。 */
    @PostMapping("/{kbId}/backup/restore")
    public Result<Integer> restore(@PathVariable Long kbId, @RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return Result.success(backupService.restore(kbId, (Long) request.getAttribute("userId"), file));
    }
}
