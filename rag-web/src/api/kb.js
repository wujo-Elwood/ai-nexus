import request from '../utils/request'

export function getKbList() {
  return request.get('/api/kb')
}

/** 查询全局向量重建进度。 */
export function getGlobalRebuildStatus() {
  return request.get('/api/kb/rebuild-vectors/status')
}

export function createKb(data) {
  return request.post('/api/kb', data)
}

/** 修改知识库名称、描述和可见范围 */
export function updateKb(id, data) {
  return request.put(`/api/kb/${id}`, data)
}

export function deleteKb(id) {
  return request.delete(`/api/kb/${id}`)
}

/** 查询知识库目录和标签。 */
export function getKbCatalog(kbId) {
  return request.get(`/api/kb/${kbId}/catalog`)
}

/** 创建知识库目录。 */
export function createKbFolder(kbId, data) {
  return request.post(`/api/kb/${kbId}/folders`, data)
}

/** 删除知识库目录。 */
export function deleteKbFolder(kbId, id) {
  return request.delete(`/api/kb/${kbId}/folders/${id}`)
}

/** 修改知识库目录名称和父目录。 */
export function updateKbFolder(kbId, id, data) {
  return request.put(`/api/kb/${kbId}/folders/${id}`, data)
}

/** 创建知识库标签。 */
export function createKbTag(kbId, data) {
  return request.post(`/api/kb/${kbId}/tags`, data)
}

/** 删除知识库标签。 */
export function deleteKbTag(kbId, id) {
  return request.delete(`/api/kb/${kbId}/tags/${id}`)
}

/** 查询知识库健康评分。 */
export function getKbHealth(kbId) {
  return request.get(`/api/kb/${kbId}/health`)
}
