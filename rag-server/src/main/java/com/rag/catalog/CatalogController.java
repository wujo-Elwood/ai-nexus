package com.rag.catalog;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 知识库目录和标签接口。 */
@RestController
@RequestMapping("/api/kb")
public class CatalogController {
    @Autowired private CatalogService catalogService;

    /** 查询目录和标签。 */
    @GetMapping("/{kbId}/catalog")
    public Result<Map<String,Object>> list(@PathVariable Long kbId, HttpServletRequest request) {
        return Result.success(catalogService.list(kbId, (Long) request.getAttribute("userId")));
    }

    /** 创建目录。 */
    @PostMapping("/{kbId}/folders")
    public Result<Map<String,Object>> createFolder(@PathVariable Long kbId, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        Long parentId = body.get("parentId") == null ? null : Long.valueOf(body.get("parentId").toString());
        return Result.success(catalogService.createFolder(kbId, parentId, String.valueOf(body.get("name")), (Long) request.getAttribute("userId")));
    }

    /** 删除目录。 */
    @DeleteMapping("/{kbId}/folders/{id}")
    public Result<Void> deleteFolder(@PathVariable Long kbId, @PathVariable Long id, HttpServletRequest request) {
        catalogService.deleteFolder(kbId, id, (Long) request.getAttribute("userId")); return Result.success();
    }

    /** 修改目录名称和父目录。 */
    @PutMapping("/{kbId}/folders/{id}")
    public Result<Void> updateFolder(@PathVariable Long kbId, @PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        Long parentId = body.get("parentId") == null ? null : Long.valueOf(body.get("parentId").toString());
        catalogService.updateFolder(kbId, id, parentId, String.valueOf(body.get("name")), (Long) request.getAttribute("userId"));
        return Result.success();
    }

    /** 创建标签。 */
    @PostMapping("/{kbId}/tags")
    public Result<Map<String,Object>> createTag(@PathVariable Long kbId, @RequestBody Map<String,Object> body, HttpServletRequest request) {
        return Result.success(catalogService.createTag(kbId, String.valueOf(body.get("name")), body.get("color") == null ? null : body.get("color").toString(), (Long) request.getAttribute("userId")));
    }

    /** 删除标签。 */
    @DeleteMapping("/{kbId}/tags/{id}")
    public Result<Void> deleteTag(@PathVariable Long kbId, @PathVariable Long id, HttpServletRequest request) {
        catalogService.deleteTag(kbId, id, (Long) request.getAttribute("userId")); return Result.success();
    }
}
