package com.rag.catalog;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** 知识库目录和标签数据访问接口。 */
@Mapper
public interface CatalogMapper {
    /** 查询目录树的扁平节点。 */
    @Select("SELECT id,kb_id,parent_id,name,create_user,create_time FROM kb_file_folder WHERE kb_id=#{kbId} ORDER BY parent_id,id")
    List<Map<String,Object>> listFolders(@Param("kbId") Long kbId);

    /** 新增目录。 */
    @Insert("INSERT INTO kb_file_folder(kb_id,parent_id,name,create_user,create_time) VALUES(#{kbId},#{parentId},#{name},#{userId},NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFolder(Map<String,Object> data);

    /** 修改目录名称和父目录。 */
    @Update("UPDATE kb_file_folder SET parent_id=#{parentId}, name=#{name} WHERE id=#{id} AND kb_id=#{kbId}")
    int updateFolder(@Param("id") Long id, @Param("kbId") Long kbId, @Param("parentId") Long parentId, @Param("name") String name);

    /** 删除目录。 */
    @Delete("DELETE FROM kb_file_folder WHERE id=#{id} AND kb_id=#{kbId}")
    int deleteFolder(@Param("id") Long id, @Param("kbId") Long kbId);

    /** 查询知识库标签。 */
    @Select("SELECT id,kb_id,name,color,create_user,create_time FROM kb_file_tag WHERE kb_id=#{kbId} ORDER BY name")
    List<Map<String,Object>> listTags(@Param("kbId") Long kbId);

    /** 新增标签。 */
    @Insert("INSERT INTO kb_file_tag(kb_id,name,color,create_user,create_time) VALUES(#{kbId},#{name},#{color},#{userId},NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTag(Map<String,Object> data);

    /** 删除标签。 */
    @Delete("DELETE FROM kb_file_tag WHERE id=#{id} AND kb_id=#{kbId}")
    int deleteTag(@Param("id") Long id, @Param("kbId") Long kbId);

    /** 清理标签关联。 */
    @Delete("DELETE FROM kb_file_tag_rel WHERE file_id=#{fileId}")
    int clearFileTags(@Param("fileId") Long fileId);

    /** 删除知识库下全部文件的标签关联。 */
    @Delete("DELETE r FROM kb_file_tag_rel r INNER JOIN kb_file f ON r.file_id=f.id WHERE f.kb_id=#{kbId}")
    int deleteTagRelsByKbId(@Param("kbId") Long kbId);

    /** 删除知识库下全部标签。 */
    @Delete("DELETE FROM kb_file_tag WHERE kb_id=#{kbId}")
    int deleteTagsByKbId(@Param("kbId") Long kbId);

    /** 删除知识库下全部目录。 */
    @Delete("DELETE FROM kb_file_folder WHERE kb_id=#{kbId}")
    int deleteFoldersByKbId(@Param("kbId") Long kbId);

    /** 新增文件标签关联。 */
    @Insert("INSERT IGNORE INTO kb_file_tag_rel(file_id,tag_id,create_time) VALUES(#{fileId},#{tagId},NOW())")
    int addFileTag(@Param("fileId") Long fileId, @Param("tagId") Long tagId);

    /** 查询文件标签。 */
    @Select("SELECT t.id,t.name,t.color FROM kb_file_tag t INNER JOIN kb_file_tag_rel r ON r.tag_id=t.id WHERE r.file_id=#{fileId} ORDER BY t.name")
    List<Map<String,Object>> listFileTags(@Param("fileId") Long fileId);
}
