import request from '../utils/request'

export function uploadFile(kbId, file) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('kbId', kbId)
  return request.post('/api/file/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export function getFileList(kbId) {
  return request.get(`/api/file/list/${kbId}`)
}

/** 查询文件历史版本。 */
export function getFileVersions(id) {
  return request.get(`/api/file/${id}/versions`)
}

/** 回滚文件版本。 */
export function rollbackFile(id) {
  return request.post(`/api/file/${id}/rollback`)
}

/** 更新文件目录和分类。 */
export function updateFileCatalog(id, data) {
  return request.put(`/api/file/${id}/catalog`, data)
}

/** 替换文件标签。 */
export function updateFileTags(id, tagIds) {
  return request.put(`/api/file/${id}/tags`, { tagIds })
}

/** 查询文件标签。 */
export function getFileTags(id) {
  return request.get(`/api/file/${id}/tags`)
}

/** 导出知识库备份。 */
export function exportKnowledgeBase(kbId) {
  return request.get(`/api/kb/${kbId}/backup/export`, { responseType: 'blob' })
}

/** 恢复知识库备份。 */
export function restoreKnowledgeBase(kbId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/api/kb/${kbId}/backup/restore`, formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

/** 重新处理文件：清除旧切片和向量，重新解析 */
export function reprocessFile(id) {
  return request.post(`/api/file/${id}/reprocess`)
}

export function deleteFile(id) {
  return request.delete(`/api/file/${id}`)
}

/**
 * 初始化大文件分片上传
 */
export function initMultipartUpload(data) {
  return request.post('/api/file/multipart/init', data)
}

/**
 * 上传一个文件分片
 */
export function uploadMultipartChunk(uploadId, chunkIndex, chunk, chunkHash, signal) {
  return request.put(`/api/file/multipart/${uploadId}/chunks/${chunkIndex}`, chunk, {
    signal,
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-Chunk-SHA256': chunkHash
    },
    timeout: 0
  })
}

/**
 * 查询大文件分片上传状态
 */
export function getMultipartStatus(uploadId) {
  return request.get(`/api/file/multipart/${uploadId}`)
}

/**
 * 合并大文件分片
 */
export function completeMultipartUpload(uploadId, fileSha256) {
  return request.post(`/api/file/multipart/${uploadId}/complete`, null, {
    params: { fileSha256 }
  })
}

/**
 * 取消大文件分片上传
 */
export function cancelMultipartUpload(uploadId) {
  return request.delete(`/api/file/multipart/${uploadId}`)
}

/**
 * 获取文件预览地址
 */
export function getFilePreviewUrl(id) {
  return `/api/file/${id}/preview`
}

/**
 * 获取文件下载地址
 */
export function getFileDownloadUrl(id) {
  return `/api/file/${id}/download`
}
