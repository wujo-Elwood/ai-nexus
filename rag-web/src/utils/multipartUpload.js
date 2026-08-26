/**
 * 按文件大小和分片大小生成分片范围
 */
export function calculateChunkRanges(fileSize, chunkSize) {
  const ranges = []
  for (let start = 0, index = 0; start < fileSize; start += chunkSize, index += 1) {
    ranges.push({ index, start, end: Math.min(start + chunkSize, fileSize) })
  }
  return ranges
}

/**
 * 根据服务端已经保存的分片计算需要补传的分片序号
 */
export function getMissingChunkIndexes(totalChunks, uploadedChunks = []) {
  const uploaded = new Set(uploadedChunks.map(index => Number(index)))
  return Array.from({ length: totalChunks }, (_, index) => index)
    .filter(index => !uploaded.has(index))
}

/**
 * 计算浏览器 Blob 的 SHA-256 摘要
 */
export async function sha256Hex(blob) {
  const buffer = await blob.arrayBuffer()
  const digest = await window.crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map(value => value.toString(16).padStart(2, '0'))
    .join('')
}
