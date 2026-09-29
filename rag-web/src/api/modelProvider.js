import request from '../utils/request'

export function getModelProviderList() {
  return request.get('/api/model-provider/list')
}

export function getActiveModelProvider() {
  return request.get('/api/model-provider/active')
}

export function createModelProvider(data) {
  return request.post('/api/model-provider', data)
}

export function updateModelProvider(id, data) {
  return request.put(`/api/model-provider/${id}`, data)
}

export function deleteModelProvider(id) {
  return request.delete(`/api/model-provider/${id}`)
}

export function activateModelProvider(id) {
  return request.put(`/api/model-provider/${id}/activate`)
}

export function activateImageModelProvider(id) {
  return request.put(`/api/model-provider/${id}/activate-image`)
}

export function activateVisionModelProvider(id) {
  return request.put(`/api/model-provider/${id}/activate-vision`)
}
