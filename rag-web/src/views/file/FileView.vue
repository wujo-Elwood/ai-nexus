<template>
    <div class="file-page page-shell">
      <section class="page-hero">
        <div>
          <span class="eyebrow">File Pipeline</span>
          <h1 class="section-title">{{ kbName || '知识库文件' }}</h1>
          <p class="section-desc">
            上传 PDF、DOC、DOCX、TXT、XLSX 或 Markdown，系统会自动解析、切片并写入向量库。
          </p>
        </div>
        <div class="page-hero-actions">
          <el-button type="primary" size="large" @click="goToChat">
            <el-icon><ChatLineRound /></el-icon>
            去提问
          </el-button>
          <el-button class="page-back-button" plain @click="goBack">
            <el-icon><ArrowLeft /></el-icon>
            返回知识库
          </el-button>
        </div>
      </section>

      <section class="process-strip glass-panel">
        <div class="process-step">
          <span class="step-index">1</span>
          <div>
            <strong>上传文件</strong>
            <p>保存原始资料</p>
          </div>
        </div>
        <div class="process-line"></div>
        <div class="process-step">
          <span class="step-index">2</span>
          <div>
            <strong>解析切片</strong>
            <p>提取文本内容</p>
          </div>
        </div>
        <div class="process-line"></div>
        <div class="process-step">
          <span class="step-index">3</span>
          <div>
            <strong>向量入库</strong>
            <p>完成后可问答</p>
          </div>
        </div>
      </section>

      <section class="file-workbench">
        <div v-if="isOwner" class="upload-panel glass-panel">
          <el-upload
            drag
            :http-request="handleUploadRequest"
            :on-error="handleUploadError"
            :before-upload="beforeUpload"
            :show-file-list="false"
            accept=".pdf,.doc,.docx,.txt,.xlsx,.md"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-title">拖拽文件到这里，或点击上传</div>
            <div class="upload-tip">支持 PDF、DOC、DOCX、TXT、XLSX、Markdown，单个文件不超过 2GB</div>
          </el-upload>
          <div v-if="activeUpload" class="multipart-progress">
            <div class="multipart-progress-head">
              <span>{{ activeUpload.fileName }}</span>
              <span>{{ activeUpload.progress }}%</span>
            </div>
            <el-progress :percentage="activeUpload.progress" :show-text="false" />
            <div class="multipart-actions">
              <el-button v-if="activeUpload.running" size="small" @click="pauseMultipartUpload">
                暂停上传
              </el-button>
              <el-button v-else size="small" type="primary" @click="resumeMultipartUpload">
                继续上传
              </el-button>
              <el-button size="small" text type="danger" @click="cancelActiveMultipartUpload">
                取消
              </el-button>
            </div>
          </div>
        </div>

        <div v-else class="readonly-panel glass-panel">
          <el-icon><View /></el-icon>
          <strong>当前为只读知识库</strong>
          <p>你可以查看文件并使用知识库问答，只有创建者可以上传和管理文件。</p>
        </div>

        <div class="stat-panel">
          <div class="stat-item">
            <span>文件总数</span>
            <strong>{{ fileStats.total }}</strong>
          </div>
          <div class="stat-item">
            <span>处理中</span>
            <strong>{{ fileStats.processing }}</strong>
          </div>
          <div class="stat-item">
            <span>可问答</span>
            <strong>{{ fileStats.completed }}</strong>
          </div>
        </div>
      </section>

      <section class="file-list-panel glass-panel">
        <div class="panel-head">
          <div>
            <h2>文件列表</h2>
            <p>文件完成处理后，会立刻参与知识库召回。</p>
          </div>
          <div class="panel-actions">
            <el-button plain @click="openCatalogDialog">
              <el-icon><Folder /></el-icon>
              目录与标签
            </el-button>
            <el-button plain @click="openHealthDialog">
              <el-icon><DataAnalysis /></el-icon>
              健康评分
            </el-button>
            <el-button v-if="isOwner" plain @click="exportBackup">
              <el-icon><Download /></el-icon>
              导出备份
            </el-button>
            <el-upload
              v-if="isOwner"
              class="restore-upload"
              :show-file-list="false"
              accept=".zip"
              :before-upload="handleRestoreBackup"
            >
              <el-button plain :loading="backupRestoring">
                <el-icon><Upload /></el-icon>
                恢复备份
              </el-button>
            </el-upload>
            <el-button :loading="loading" plain @click="loadFileList">
              <el-icon><Refresh /></el-icon>
              刷新
            </el-button>
          </div>
        </div>

        <el-empty v-if="!loading && fileList.length === 0" description="暂无文件，请先上传文档" />
        <el-table v-else v-loading="loading" :data="fileList" style="width: 100%">
          <el-table-column prop="fileName" label="文件名称" min-width="260">
            <template #default="{ row }">
              <div class="file-name-cell">
                <el-icon><Document /></el-icon>
                <span>{{ row.fileName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="fileType" label="类型" width="110">
            <template #default="{ row }">
              <el-tag>{{ getFileType(row.fileType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="fileSize" label="大小" width="120">
            <template #default="{ row }">
              {{ formatSize(row.fileSize) }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="处理状态" min-width="220">
            <template #default="{ row }">
              <div class="process-status">
                <div class="process-status-head">
                  <el-tag :type="getStatusType(row.status)" round>
                    {{ getProcessStageText(row) }}
                  </el-tag>
                  <span>{{ row.progress ?? getDefaultProgress(row.status) }}%</span>
                </div>
                <el-progress
                  :percentage="row.progress ?? getDefaultProgress(row.status)"
                  :status="row.status === 'FAILED' ? 'exception' : row.status === 'COMPLETED' ? 'success' : ''"
                  :show-text="false"
                />
                <el-tooltip v-if="row.errorMessage" :content="row.errorMessage" placement="top">
                  <p class="error-message">{{ row.errorMessage }}</p>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="上传时间" width="180">
            <template #default="{ row }">
              {{ formatDate(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column v-if="isOwner" label="操作" width="86" fixed="right" align="center">
            <template #default="{ row }">
              <el-tooltip content="更多文件操作" placement="top">
                <el-dropdown trigger="click" @command="command => handleFileCommand(command, row)">
                  <el-button class="file-action-button" text circle>
                    <el-icon><MoreFilled /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="versions">版本管理</el-dropdown-item>
                      <el-dropdown-item command="catalog">目录与分类</el-dropdown-item>
                      <el-dropdown-item command="download">下载文件</el-dropdown-item>
                      <el-dropdown-item
                        command="reprocess"
                        :disabled="row.status === 'PROCESSING' || row.status === 'UPLOADED'"
                      >
                        {{ row.status === 'FAILED' ? '重试处理' : '重新处理' }}
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" divided>删除文件</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <el-dialog v-model="versionDialogVisible" title="文件版本管理" width="720px">
        <div class="dialog-context" v-if="selectedFile">
          <strong>{{ selectedFile.fileName }}</strong>
          <span>版本组：{{ selectedFile.versionGroupId || selectedFile.id }}</span>
        </div>
        <el-table v-loading="versionsLoading" :data="fileVersions" size="small">
          <el-table-column prop="versionNo" label="版本" width="90">
            <template #default="{ row }">V{{ row.versionNo || 1 }}</template>
          </el-table-column>
          <el-table-column prop="fileSize" label="大小" width="120">
            <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column prop="status" label="处理状态" min-width="120">
            <template #default="{ row }">{{ getProcessStageText(row) }}</template>
          </el-table-column>
          <el-table-column prop="createTime" label="上传时间" min-width="170">
            <template #default="{ row }">{{ formatDate(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="状态/操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-tag v-if="row.isCurrent === 1" type="success" size="small">当前版本</el-tag>
              <el-button v-else-if="isOwner" type="primary" text size="small" @click="rollbackVersion(row)">回滚</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-dialog>

      <el-dialog
        v-model="catalogDialogVisible"
        class="catalog-dialog"
        title="目录、标签与分类"
        width="760px"
        :before-close="() => (catalogDialogVisible = false)"
      >
        <el-tabs v-model="catalogTab" v-loading="catalogLoading">
          <el-tab-pane label="目录" name="folders">
            <div v-if="isOwner" class="catalog-create-row">
              <el-select v-model="newFolderParentId" clearable placeholder="父目录">
                <el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" />
              </el-select>
              <el-input v-model="newFolderName" placeholder="输入目录名称" clearable @keyup.enter="createFolder" />
              <el-button type="primary" @click="createFolder">新建目录</el-button>
            </div>
            <el-empty v-if="folders.length === 0" description="暂无目录" />
            <div v-else class="catalog-list">
              <div v-for="folder in folders" :key="folder.id" class="catalog-row">
                <span><el-icon><Folder /></el-icon>{{ folder.name }}</span>
                <div v-if="isOwner" class="catalog-row-actions">
                  <el-button type="primary" text @click="renameFolder(folder)">编辑</el-button>
                  <el-button type="danger" text @click="removeFolder(folder)">删除</el-button>
                </div>
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane label="标签" name="tags">
            <div v-if="isOwner" class="catalog-create-row">
              <el-input v-model="newTagName" placeholder="输入标签名称" clearable @keyup.enter="createTag" />
              <el-input v-model="newTagColor" class="tag-color-input" placeholder="颜色（可选）" />
              <el-button type="primary" @click="createTag">新建标签</el-button>
            </div>
            <el-empty v-if="tags.length === 0" description="暂无标签" />
            <div v-else class="catalog-list">
              <div v-for="tag in tags" :key="tag.id" class="catalog-row">
                <span><el-tag size="small" :color="tag.color || undefined">{{ tag.name }}</el-tag></span>
                <el-button v-if="isOwner" type="danger" text @click="removeTag(tag)">删除</el-button>
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane label="文件分类" name="file-catalog">
            <el-empty v-if="fileList.length === 0" description="暂无文件" />
            <div v-else class="catalog-list">
              <div v-for="file in fileList" :key="file.id" class="catalog-row catalog-file-row">
                <span class="catalog-file-name">{{ file.fileName }}</span>
                <div v-if="isOwner" class="catalog-file-fields">
                  <el-select v-model="file.folderId" size="small" clearable placeholder="目录" @change="saveFileCatalog(file)">
                    <el-option v-for="folder in folders" :key="folder.id" :label="folder.name" :value="folder.id" />
                  </el-select>
                  <el-input v-model="file.category" size="small" placeholder="分类" @change="saveFileCatalog(file)" />
                  <el-select v-model="file.tagIds" size="small" multiple collapse-tags collapse-tags-tooltip placeholder="标签" @change="saveFileTags(file)">
                    <el-option v-for="tag in tags" :key="tag.id" :label="tag.name" :value="tag.id" />
                  </el-select>
                </div>
                <span v-else>{{ file.category || '未分类' }}</span>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-dialog>

      <el-dialog v-model="healthDialogVisible" title="知识库健康评分" width="680px">
        <div v-if="healthData" class="health-score-content">
          <div class="health-score-main">
            <span>当前评分</span>
            <strong>{{ healthData.score }}</strong>
            <el-tag :type="healthData.level === 'HEALTHY' ? 'success' : healthData.level === 'WARNING' ? 'warning' : 'danger'">
              {{ healthLevelText(healthData.level) }}
            </el-tag>
          </div>
          <div class="health-score-grid">
            <div><span>文件总数</span><strong>{{ healthData.fileCount }}</strong></div>
            <div><span>切片总数</span><strong>{{ healthData.chunkCount }}</strong></div>
            <div><span>空文件</span><strong>{{ healthData.emptyFiles }}</strong></div>
            <div><span>低质量切片</span><strong>{{ healthData.lowQualityChunks }}</strong></div>
            <div><span>重复文件</span><strong>{{ healthData.duplicateFiles }}</strong></div>
            <div><span>重复切片</span><strong>{{ healthData.duplicateChunks }}</strong></div>
            <div><span>向量缺失</span><strong>{{ healthData.vectorMissing }}</strong></div>
            <div><span>处理失败</span><strong>{{ healthData.processingFailed }}</strong></div>
          </div>
        </div>
        <el-empty v-else description="暂无健康评分数据" />
        <template #footer>
          <el-button @click="healthDialogVisible = false">关闭</el-button>
          <el-button type="primary" :loading="healthLoading" @click="loadHealthScore">重新计算</el-button>
        </template>
      </el-dialog>
    </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  cancelMultipartUpload,
  completeMultipartUpload,
  deleteFile,
  exportKnowledgeBase,
  getFileVersions,
  getFileList,
  getFileDownloadUrl,
  getMultipartStatus,
  initMultipartUpload,
  reprocessFile,
  restoreKnowledgeBase,
  rollbackFile,
  updateFileCatalog,
  updateFileTags,
  getFileTags,
  uploadFile,
  uploadMultipartChunk
} from '../../api/file'
import { calculateChunkRanges, getMissingChunkIndexes, sha256Hex } from '../../utils/multipartUpload'
import {
  createKbFolder,
  createKbTag,
  deleteKbFolder,
  deleteKbTag,
  getKbCatalog,
  getKbHealth,
  getKbList,
  updateKbFolder
} from '../../api/kb'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ChatLineRound,
  DataAnalysis,
  Download,
  Document,
  Folder,
  MoreFilled,
  Refresh,
  Upload,
  UploadFilled,
  View
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const kbId = route.params.kbId
const kbName = ref('')
const isOwner = ref(false)
const fileList = ref([])
const loading = ref(false)
const pollingTimer = ref(null)

const normalUploadLimit = 10 * 1024 * 1024
const maxUploadSize = 2 * 1024 * 1024 * 1024
const activeUpload = ref(null)
const multipartAbortController = ref(null)
const multipartPauseRequested = ref(false)

// 文件版本管理弹窗状态
const versionDialogVisible = ref(false)
const versionsLoading = ref(false)
const selectedFile = ref(null)
const fileVersions = ref([])

// 目录、标签和分类弹窗状态
const catalogDialogVisible = ref(false)
const catalogTab = ref('folders')
const catalogLoading = ref(false)
const folders = ref([])
const tags = ref([])
const newFolderName = ref('')
const newFolderParentId = ref(null)
const newTagName = ref('')
const newTagColor = ref('')

// 知识库健康评分弹窗状态
const healthDialogVisible = ref(false)
const healthLoading = ref(false)
const healthData = ref(null)

// 备份恢复上传状态
const backupRestoring = ref(false)

const fileStats = computed(() => {
  return fileList.value.reduce((stats, file) => {
    stats.total += 1
    if (file.status === 'COMPLETED') {
      stats.completed += 1
    }
    if (file.status === 'PROCESSING' || file.status === 'UPLOADED') {
      stats.processing += 1
    }
    return stats
  }, { total: 0, processing: 0, completed: 0 })
})

onMounted(() => {
  loadKbInfo()
  loadFileList()
})

onBeforeUnmount(() => {
  stopPolling()
})

// 加载知识库名称
async function loadKbInfo() {
  try {
    // 第1步：查询知识库列表
    const res = await getKbList()
    // 第2步：找到当前知识库
    const kb = res.data?.find(item => item.id === Number(kbId))
    // 第3步：保存知识库名称
    if (kb) {
      kbName.value = kb.name
      isOwner.value = String(kb.createUser) === String(localStorage.getItem('userId'))
    }
  } catch (error) {
    // 第4步：查询失败时记录错误
    console.error(error)
  }
}

// 加载文件列表
async function loadFileList() {
  try {
    // 第1步：打开加载状态
    loading.value = true
    // 第2步：查询当前知识库的文件
    const res = await getFileList(kbId)
    // 第3步：保存文件列表
    fileList.value = res.data || []
    // 第4步：根据处理状态决定是否轮询
    updatePolling()
  } catch (error) {
    // 第5步：加载失败时记录错误
    console.error(error)
  } finally {
    // 第6步：关闭加载状态
    loading.value = false
  }
}

// 打开文件版本列表
async function openVersionDialog(file) {
  selectedFile.value = file
  versionDialogVisible.value = true
  versionsLoading.value = true
  try {
    const response = await getFileVersions(file.id)
    fileVersions.value = response.data || []
  } finally {
    versionsLoading.value = false
  }
}

// 回滚到选中的历史版本
async function rollbackVersion(version) {
  try {
    await ElMessageBox.confirm(`确定将「${selectedFile.value?.fileName || ''}」回滚到 V${version.versionNo || 1} 吗？`, '版本回滚确认', {
      confirmButtonText: '确定回滚',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await rollbackFile(version.id)
    ElMessage.success('已回滚到选定版本')
    versionDialogVisible.value = false
    await loadFileList()
  } catch (error) {
    if (error !== 'cancel') console.error(error)
  }
}

// 下载当前文件原文
async function downloadFile(file) {
  try {
    const response = await fetch(getFileDownloadUrl(file.id), {
      headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
    })
    if (!response.ok) throw new Error('文件下载失败')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.fileName || 'knowledge-file'
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    console.error(error)
    ElMessage.error('文件下载失败')
  }
}

// 处理文件操作菜单命令，避免窄表格中多个按钮互相挤压
function handleFileCommand(command, file) {
  if (command === 'versions') return openVersionDialog(file)
  if (command === 'catalog') return openFileCatalog(file)
  if (command === 'download') return downloadFile(file)
  if (command === 'reprocess') return handleReprocess(file)
  if (command === 'delete') return handleDelete(file.id)
}

// 加载目录和标签数据并打开弹窗
async function openCatalogDialog() {
  catalogDialogVisible.value = true
  catalogLoading.value = true
  try {
    const response = await getKbCatalog(kbId)
    folders.value = response.data?.folders || []
    tags.value = response.data?.tags || []
    //打开文件分类页时补充每个文件的标签关联，保证编辑控件显示真实状态
    await Promise.all(fileList.value.map(async file => {
      const tagResponse = await getFileTags(file.id)
      file.tagIds = (tagResponse.data || []).map(tag => tag.id)
    }))
  } finally {
    catalogLoading.value = false
  }
}

// 从文件行直接打开分类编辑
async function openFileCatalog(file) {
  selectedFile.value = file
  catalogTab.value = 'file-catalog'
  await openCatalogDialog()
}

// 新建知识库目录
async function createFolder() {
  const name = newFolderName.value.trim()
  if (!name) {
    ElMessage.warning('请输入目录名称')
    return
  }
  await createKbFolder(kbId, { name, parentId: newFolderParentId.value || null })
  newFolderName.value = ''
  newFolderParentId.value = null
  ElMessage.success('目录已创建')
  await openCatalogDialog()
}

// 修改目录名称和父目录
async function renameFolder(folder) {
  try {
    const result = await ElMessageBox.prompt('请输入新的目录名称', '编辑目录', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: folder.name,
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空'
    })
    await updateKbFolder(kbId, folder.id, { name: result.value, parentId: folder.parentId || null })
    ElMessage.success('目录已更新')
    await openCatalogDialog()
  } catch (error) {
    if (error !== 'cancel') console.error(error)
  }
}

// 删除知识库目录
async function removeFolder(folder) {
  try {
    await ElMessageBox.confirm(`确定删除目录「${folder.name}」吗？`, '删除目录确认', { type: 'warning' })
    await deleteKbFolder(kbId, folder.id)
    ElMessage.success('目录已删除')
    await openCatalogDialog()
  } catch (error) {
    if (error !== 'cancel') console.error(error)
  }
}

// 新建知识库标签
async function createTag() {
  const name = newTagName.value.trim()
  if (!name) {
    ElMessage.warning('请输入标签名称')
    return
  }
  await createKbTag(kbId, { name, color: newTagColor.value.trim() || null })
  newTagName.value = ''
  newTagColor.value = ''
  ElMessage.success('标签已创建')
  await openCatalogDialog()
}

// 删除知识库标签
async function removeTag(tag) {
  try {
    await ElMessageBox.confirm(`确定删除标签「${tag.name}」吗？`, '删除标签确认', { type: 'warning' })
    await deleteKbTag(kbId, tag.id)
    ElMessage.success('标签已删除')
    await openCatalogDialog()
  } catch (error) {
    if (error !== 'cancel') console.error(error)
  }
}

// 保存文件分类
async function saveFileCatalog(file) {
  await updateFileCatalog(file.id, { folderId: file.folderId || null, category: file.category || null })
  ElMessage.success('目录和分类已保存')
}

// 保存文件标签关联
async function saveFileTags(file) {
  await updateFileTags(file.id, file.tagIds || [])
  ElMessage.success('标签已保存')
}

// 打开健康评分弹窗并加载数据
async function openHealthDialog() {
  healthDialogVisible.value = true
  await loadHealthScore()
}

// 重新计算知识库健康评分
async function loadHealthScore() {
  healthLoading.value = true
  try {
    const response = await getKbHealth(kbId)
    healthData.value = response.data || null
  } finally {
    healthLoading.value = false
  }
}

// 转换健康评分等级文案
function healthLevelText(level) {
  return { HEALTHY: '健康', WARNING: '需关注', CRITICAL: '风险' }[level] || '未知'
}

// 下载知识库备份 ZIP
async function exportBackup() {
  try {
    const response = await exportKnowledgeBase(kbId)
    const blobUrl = URL.createObjectURL(response.data)
    const link = document.createElement('a')
    link.href = blobUrl
    link.download = `${kbName.value || 'knowledge-base'}-backup.zip`
    link.click()
    URL.revokeObjectURL(blobUrl)
    ElMessage.success('备份已导出')
  } catch (error) {
    console.error(error)
  }
}

// 恢复知识库 ZIP 备份
async function handleRestoreBackup(file) {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    ElMessage.error('只支持 ZIP 备份文件')
    return false
  }
  try {
    await ElMessageBox.confirm('恢复备份会按文件摘要跳过重复文件，是否继续？', '恢复备份确认', { type: 'warning' })
    backupRestoring.value = true
    const response = await restoreKnowledgeBase(kbId, file)
    ElMessage.success(`备份恢复完成，共恢复 ${response.data || 0} 个文件`)
    await loadFileList()
  } catch (error) {
    if (error !== 'cancel') console.error(error)
  } finally {
    backupRestoring.value = false
  }
  return false
}

// 上传前校验文件
function beforeUpload(file) {
  // 第1步：定义允许上传的文件类型
  const allowedTypes = [
    'application/pdf',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'text/plain',
    'text/markdown',
    'text/x-markdown'
  ]
  // 第2步：兼容浏览器识别不到 MIME 类型的情况
  const allowedExts = ['.pdf', '.doc', '.docx', '.txt', '.xlsx', '.md']
  const ext = file.name ? file.name.substring(file.name.lastIndexOf('.')).toLowerCase() : ''
  // 第3步：校验文件格式
  if (!allowedTypes.includes(file.type) && !allowedExts.includes(ext)) {
    ElMessage.error('只支持 PDF、DOC、DOCX、TXT、XLSX、Markdown 格式的文件')
    return false
  }
  // 第4步：校验文件大小
  if (file.size > maxUploadSize) {
    ElMessage.error('文件大小不能超过 2GB')
    return false
  }
  // 第5步：校验通过允许上传
  return true
}

// 处理普通文件和大文件两种上传方式
async function handleUploadRequest(options) {
  try {
    if (options.file.size <= normalUploadLimit) {
      const response = await uploadFile(kbId, options.file)
      handleUploadSuccess(response)
      options.onSuccess?.(response)
      return
    }
    activeUpload.value = {
      file: options.file,
      fileName: options.file.name,
      progress: 0,
      running: true,
      onSuccess: options.onSuccess,
      onError: options.onError
    }
    await runMultipartUpload(options.file)
  } catch (error) {
    if (error?.message === 'UPLOAD_PAUSED') {
      return
    }
    activeUpload.value = null
    options.onError?.(error)
    handleUploadError()
  }
}

// 执行大文件分片上传并支持从服务端进度继续
async function runMultipartUpload(file) {
  const resumeKey = `rag-multipart:${kbId}:${file.name}:${file.size}:${file.lastModified}`
  let uploadId = localStorage.getItem(resumeKey)
  let uploadData
  if (uploadId) {
    try {
      const statusResponse = await getMultipartStatus(uploadId)
      uploadData = statusResponse.data
    } catch {
      localStorage.removeItem(resumeKey)
      uploadId = null
    }
  }
  if (!uploadId) {
    const initResponse = await initMultipartUpload({
      kbId: Number(kbId),
      fileName: file.name,
      fileSize: file.size,
      fileType: file.type
    })
    uploadData = initResponse.data
    uploadId = uploadData.uploadId
    localStorage.setItem(resumeKey, uploadId)
  }

  activeUpload.value.uploadId = uploadId
  const ranges = calculateChunkRanges(file.size, Number(uploadData.chunkSize))
  const missingIndexes = getMissingChunkIndexes(uploadData.totalChunks, uploadData.uploadedChunks || [])
  const uploadedSet = new Set((uploadData.uploadedChunks || []).map(index => Number(index)))
  multipartPauseRequested.value = false
  multipartAbortController.value = new AbortController()
  for (const range of ranges) {
    if (!missingIndexes.includes(range.index) || uploadedSet.has(range.index)) {
      updateMultipartProgress(range, file.size)
      continue
    }
    if (multipartPauseRequested.value) {
      activeUpload.value.running = false
      throw new Error('UPLOAD_PAUSED')
    }
    const chunk = file.slice(range.start, range.end)
    const chunkHash = await sha256Hex(chunk)
    await uploadMultipartChunk(uploadId, range.index, chunk, chunkHash, multipartAbortController.value.signal)
    updateMultipartProgress(range, file.size)
  }
  if (multipartPauseRequested.value) {
    activeUpload.value.running = false
    throw new Error('UPLOAD_PAUSED')
  }
  await completeMultipartUpload(uploadId)
  localStorage.removeItem(resumeKey)
  activeUpload.value.progress = 100
  activeUpload.value.running = false
  activeUpload.value.onSuccess?.({ code: 200 })
  activeUpload.value = null
  ElMessage.success('上传成功，正在解析入库')
  await loadFileList()
}

// 更新大文件上传进度
function updateMultipartProgress(range, fileSize) {
  activeUpload.value.progress = Math.min(100, Math.round((range.end / fileSize) * 100))
}

// 暂停当前大文件上传
function pauseMultipartUpload() {
  multipartPauseRequested.value = true
  activeUpload.value.running = false
}

// 继续当前大文件上传
async function resumeMultipartUpload() {
  if (!activeUpload.value?.file) {
    return
  }
  activeUpload.value.running = true
  try {
    await runMultipartUpload(activeUpload.value.file)
  } catch (error) {
    if (error?.message !== 'UPLOAD_PAUSED') {
      ElMessage.error('继续上传失败')
    }
  }
}

// 取消当前大文件上传
async function cancelActiveMultipartUpload() {
  if (!activeUpload.value?.uploadId) {
    activeUpload.value = null
    return
  }
  try {
    await cancelMultipartUpload(activeUpload.value.uploadId)
  } finally {
    const file = activeUpload.value.file
    localStorage.removeItem(`rag-multipart:${kbId}:${file.name}:${file.size}:${file.lastModified}`)
    multipartAbortController.value?.abort()
    activeUpload.value = null
  }
}

// 处理上传成功
function handleUploadSuccess(response) {
  // 第1步：后端返回失败时提示错误
  if (response?.code !== 200) {
    ElMessage.error(response?.message || '上传失败')
    return
  }
  // 第2步：提示上传成功
  ElMessage.success('上传成功，正在解析入库')
  // 第3步：刷新文件列表
  loadFileList()
}

// 处理上传失败
function handleUploadError() {
  // 第1步：提示上传失败
  ElMessage.error('上传失败')
}

// 重新处理文件
async function handleReprocess(row) {
  try {
    // 第1步：确认重新处理
    await ElMessageBox.confirm(
      `确定要重新处理「${row.fileName}」吗？旧的切片和向量会被清除并重新生成。`,
      '重新处理确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    // 第2步：调用重新处理接口
    await reprocessFile(row.id)
    // 第3步：提示并刷新列表
    ElMessage.success('已开始重新处理')
    loadFileList()
  } catch (error) {
    // 第4步：取消操作不提示错误
    if (error !== 'cancel') {
      console.error(error)
      ElMessage.error('重新处理失败')
    }
  }
}

// 删除文件
async function handleDelete(id) {
  try {
    // 第1步：确认删除文件
    await ElMessageBox.confirm('确定要删除这个文件吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    // 第2步：调用删除接口
    await deleteFile(id)
    // 第3步：提示并刷新列表
    ElMessage.success('删除成功')
    loadFileList()
  } catch (error) {
    // 第4步：取消删除不提示错误
    if (error !== 'cancel') {
      console.error(error)
    }
  }
}

// 返回知识库列表
function goBack() {
  // 第1步：跳转到知识库页
  router.push('/kb')
}

// 跳转到聊天页
function goToChat() {
  // 第1步：带上当前知识库编号进入聊天页
  router.push({ path: '/chat', query: { kbId } })
}

// 更新轮询状态
function updatePolling() {
  // 第1步：判断是否存在处理中文件
  const hasProcessing = fileList.value.some(file => file.status === 'PROCESSING' || file.status === 'UPLOADED')
  // 第2步：有处理中文件就启动轮询
  if (hasProcessing) {
    startPolling()
    return
  }
  // 第3步：没有处理中文件就停止轮询
  stopPolling()
}

// 启动文件状态轮询
function startPolling() {
  // 第1步：已有轮询时直接返回
  if (pollingTimer.value) {
    return
  }
  // 第2步：定时刷新文件列表
  pollingTimer.value = window.setInterval(() => {
    loadFileList()
  }, 3000)
}

// 停止文件状态轮询
function stopPolling() {
  // 第1步：没有轮询时直接返回
  if (!pollingTimer.value) {
    return
  }
  // 第2步：清理定时器
  window.clearInterval(pollingTimer.value)
  // 第3步：清空定时器编号
  pollingTimer.value = null
}

// 获取文件类型文案
function getFileType(type) {
  // 第1步：根据 MIME 类型映射显示名称
  if (type?.includes('pdf')) { return 'PDF' }
  if (type?.includes('msword')) { return 'DOC' }
  if (type?.includes('word')) { return 'DOCX' }
  if (type?.includes('spreadsheet') || type?.includes('excel')) { return 'XLSX' }
  if (type?.includes('markdown')) { return 'MD' }
  if (type?.includes('text')) { return 'TXT' }
  // 第2步：无法识别时显示原始类型
  return type
}

// 格式化文件大小
function formatSize(bytes) {
  // 第1步：空值显示 0 B
  if (!bytes) { return '0 B' }
  // 第2步：逐级换算单位
  const units = ['B', 'KB', 'MB', 'GB']
  let index = 0
  let size = bytes
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index++
  }
  // 第3步：返回带单位的文件大小
  return `${size.toFixed(1)} ${units[index]}`
}

// 获取文件状态标签类型
function getStatusType(status) {
  // 第1步：按后端状态映射 Element Plus 标签类型
  const types = { UPLOADED: 'info', PROCESSING: 'warning', COMPLETED: 'success', FAILED: 'danger' }
  // 第2步：未知状态默认显示 info
  return types[status] || 'info'
}

// 获取文件状态文案
function getStatusText(status) {
  // 第1步：按后端状态映射中文文案
  const texts = { UPLOADED: '已上传', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '处理失败' }
  // 第2步：未知状态显示原始状态
  return texts[status] || status
}

// 获取文件处理阶段文案
function getProcessStageText(file) {
  // 第1步：优先按详细处理阶段显示
  const stageTexts = {
    UPLOADED: '等待处理',
    PARSING: '解析文档',
    SPLITTING: '文本切片',
    VECTORIZING: '向量入库',
    COMPLETED: '处理完成',
    FAILED: '处理失败'
  }
  if (file.processStage && stageTexts[file.processStage]) {
    return stageTexts[file.processStage]
  }
  // 第2步：兼容升级前的旧文件记录
  return getStatusText(file.status)
}

// 获取旧文件记录的默认进度
function getDefaultProgress(status) {
  // 第1步：按原有状态提供兼容进度
  const progressMap = { UPLOADED: 0, PROCESSING: 20, COMPLETED: 100, FAILED: 0 }
  return progressMap[status] ?? 0
}

// 格式化日期
function formatDate(dateStr) {
  // 第1步：空日期直接返回空字符串
  if (!dateStr) { return '' }
  // 第2步：按中文日期时间显示
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}
</script>

<style scoped>
.process-strip {
  display: grid;
  grid-template-columns: 1fr 80px 1fr 80px 1fr;
  align-items: center;
  padding: 22px;
  margin-bottom: 20px;
}

.process-step {
  display: flex;
  align-items: center;
  gap: 14px;
}

.process-step strong {
  display: block;
  font-size: 16px;
  color: var(--ink-color);
}

.process-step p {
  margin-top: 4px;
  color: var(--muted-color);
}

.step-index {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  color: #ffffff;
  background: linear-gradient(135deg, #155eef, #0f9f7a);
  font-weight: 900;
}

.process-line {
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(21, 94, 239, 0.3), rgba(15, 159, 122, 0.3));
}

.file-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(320px, 1fr);
  gap: 18px;
  margin-bottom: 20px;
}

.readonly-panel {
  display: flex;
  min-height: 190px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 28px;
  text-align: center;
}

.readonly-panel .el-icon {
  color: var(--primary-color);
  font-size: 34px;
}

.readonly-panel strong {
  color: var(--ink-color);
  font-size: 17px;
}

.readonly-panel p {
  max-width: 460px;
  color: var(--muted-color);
  line-height: 1.7;
}

.upload-panel {
  padding: 14px;
}

.upload-panel :deep(.el-upload-dragger) {
  padding: 42px 18px;
  border: 1px dashed rgba(229, 160, 68, 0.34);
  border-radius: 12px;
  background:
    radial-gradient(circle at 50% 0%, rgba(229, 160, 68, 0.08), transparent 34%),
    rgba(247, 245, 242, 0.025);
}

.upload-icon {
  color: var(--primary-color);
  font-size: 50px;
}

.upload-title {
  margin-top: 14px;
  color: var(--ink-color);
  font-size: 18px;
  font-weight: 900;
}

.upload-tip {
  margin-top: 8px;
  color: var(--muted-color);
}

.multipart-progress {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--line-color);
  border-radius: 10px;
  background: rgba(247, 245, 242, 0.03);
}

.multipart-progress-head,
.multipart-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.multipart-progress-head {
  margin-bottom: 8px;
  color: var(--ink-color);
  font-size: 13px;
}

.multipart-actions {
  justify-content: flex-end;
  margin-top: 10px;
}

.stat-panel {
  display: grid;
  gap: 14px;
}

.stat-item {
  position: relative;
  overflow: hidden;
  min-height: 126px;
  padding: 22px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  color: var(--ink-color);
  background: rgba(30, 31, 35, 0.72);
}

.stat-item::after {
  content: "";
  position: absolute;
  right: -42px;
  bottom: -58px;
  width: 150px;
  height: 150px;
  border-radius: 50%;
  background: rgba(229, 160, 68, 0.1);
}

.stat-item span {
  position: relative;
  z-index: 1;
  display: block;
  color: var(--muted-color);
}

.stat-item strong {
  position: relative;
  z-index: 1;
  display: block;
  margin: 12px 0 8px;
  color: var(--ink-color);
  font-size: 38px;
  line-height: 1;
  font-weight: 900;
}

.file-list-panel {
  padding: 22px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 18px;
}

.panel-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.restore-upload {
  display: inline-flex;
}

.dialog-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  color: var(--muted-color);
  font-size: 13px;
}

.dialog-context strong {
  overflow: hidden;
  color: var(--ink-color);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-create-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.catalog-create-row .el-input:first-child {
  flex: 1;
}

.catalog-create-row .el-select {
  width: 150px;
}

.tag-color-input {
  max-width: 150px;
}

.catalog-list {
  display: grid;
  gap: 8px;
  max-height: 330px;
  overflow-y: auto;
}

.catalog-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 42px;
  padding: 0 12px;
  border: 1px solid var(--line-color);
  border-radius: 8px;
  background: rgba(247, 245, 242, 0.025);
}

.catalog-row > span:first-child {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.catalog-row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.catalog-file-fields {
  flex: 1 1 440px;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(110px, 130px) minmax(120px, 1fr) minmax(120px, 160px);
  gap: 8px;
  align-items: center;
}

.catalog-file-fields > .el-input,
.catalog-file-fields > .el-select {
  width: 100%;
  min-width: 0;
}

.catalog-file-fields > .el-input {
  max-width: 180px;
}

.catalog-file-name {
  flex: 1 1 220px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.health-score-content {
  display: grid;
  gap: 18px;
}

.health-score-main {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: rgba(247, 245, 242, 0.025);
}

.health-score-main span {
  color: var(--muted-color);
}

.health-score-main strong {
  color: var(--ink-color);
  font-size: 40px;
  line-height: 1;
}

.health-score-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.health-score-grid > div {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--line-color);
  border-radius: 8px;
  background: rgba(247, 245, 242, 0.025);
}

.health-score-grid span {
  color: var(--muted-color);
  font-size: 12px;
}

.health-score-grid strong {
  color: var(--ink-color);
  font-size: 20px;
}

.panel-head h2 {
  font-size: 22px;
  font-weight: 900;
}

.panel-head p {
  margin-top: 6px;
  color: var(--muted-color);
}

.file-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-weight: 800;
}

.file-name-cell span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-action-button {
  width: 32px;
  height: 32px;
  color: var(--muted-color);
}

.process-status {
  min-width: 180px;
}

.process-status-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.process-status-head span {
  color: var(--muted-color);
  font-size: 12px;
}

.error-message {
  max-width: 210px;
  margin-top: 6px;
  overflow: hidden;
  color: var(--danger-color, #f56c6c);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 目录弹窗使用深色主题时，覆盖 Element Plus 默认的浅色标签页文字。 */
:global(.catalog-dialog .el-dialog__body) {
  color: var(--ink-color);
}

:global(.catalog-dialog .el-tabs__header) {
  margin-bottom: 16px;
}

:global(.catalog-dialog .el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: var(--line-color);
}

:global(.catalog-dialog .el-tabs__item) {
  min-width: 86px;
  padding: 0 18px;
  color: rgba(247, 245, 242, 0.66);
  font-size: 14px;
  font-weight: 700;
  text-align: center;
}

:global(.catalog-dialog .el-tabs__item:hover),
:global(.catalog-dialog .el-tabs__item.is-active) {
  color: var(--primary-color);
}

:global(.catalog-dialog .el-tabs__active-bar) {
  height: 2px;
  background-color: var(--primary-color);
}

:global(.catalog-dialog .el-input__wrapper),
:global(.catalog-dialog .el-select__wrapper) {
  border: 1px solid rgba(247, 245, 242, 0.1);
  background: rgba(247, 245, 242, 0.06);
  box-shadow: none;
}

:global(.catalog-dialog .el-input__inner),
:global(.catalog-dialog .el-select__placeholder) {
  color: var(--ink-color);
}

:global(.catalog-dialog .el-input__inner::placeholder) {
  color: rgba(247, 245, 242, 0.42);
}

:global(.catalog-dialog .el-empty__description) {
  color: var(--muted-color);
}

/* 目录弹窗加载时保持深色遮罩，避免 Element Plus 默认白色遮罩刺眼。 */
:global(.catalog-dialog .el-loading-mask) {
  background-color: rgba(22, 23, 27, 0.78) !important;
  backdrop-filter: blur(2px);
}

:global(.catalog-dialog .el-loading-spinner .circular) {
  stroke: var(--primary-color);
}

:global(.catalog-dialog .el-loading-spinner .el-loading-text) {
  color: var(--muted-color);
}

@media (max-width: 900px) {
  .page-hero,
  .panel-head {
    flex-direction: column;
    align-items: stretch;
  }

  .process-strip,
  .file-workbench {
    grid-template-columns: 1fr;
  }

  .process-line {
    display: none;
  }

  .panel-actions {
    justify-content: flex-start;
  }

  .health-score-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .catalog-file-fields {
    grid-template-columns: 1fr;
    width: 100%;
    flex-basis: 100%;
  }

  .catalog-file-fields > .el-input {
    max-width: none;
  }
}
</style>
