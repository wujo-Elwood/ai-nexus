import assert from 'node:assert/strict'
import { calculateChunkRanges, getMissingChunkIndexes } from '../src/utils/multipartUpload.js'

const ranges = calculateChunkRanges(25 * 1024 * 1024, 10 * 1024 * 1024)
assert.deepEqual(ranges, [
  { index: 0, start: 0, end: 10 * 1024 * 1024 },
  { index: 1, start: 10 * 1024 * 1024, end: 20 * 1024 * 1024 },
  { index: 2, start: 20 * 1024 * 1024, end: 25 * 1024 * 1024 }
])
assert.deepEqual(getMissingChunkIndexes(3, [0, 2]), [1])
console.log('multipart upload helper checks passed')
