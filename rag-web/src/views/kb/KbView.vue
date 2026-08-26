<template>
  <div class="kb-page page-shell">
    <section class="page-hero kb-hero">
      <div>
        <span class="eyebrow">Knowledge Base</span>
        <h1 class="section-title">把文档变成可追问的知识库</h1>
        <p class="section-desc">
          上传资料后自动解析、切片、向量化，聊天时按知识库召回相关内容，让回答更贴近你的业务资料。
        </p>
      </div>
      <el-button type="primary" size="large" class="create-btn" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        新建知识库
      </el-button>
    </section>

    <section class="stats-grid">
      <div class="stat-card total-card">
        <span>知识库总数</span>
        <strong>{{ kbStats.total }}</strong>
        <small>已创建的知识集合</small>
      </div>
      <div class="stat-card ready-card">
        <span>可问答</span>
        <strong>{{ kbStats.ready }}</strong>
        <small>已有完成入库文件</small>
      </div>
      <div class="stat-card file-card">
        <span>文件总数</span>
        <strong>{{ kbStats.files }}</strong>
        <small>参与检索的资料</small>
      </div>
    </section>

    <section class="toolbar glass-panel">
      <div class="search-input-wrap">
        <el-icon><Search /></el-icon>
        <input
          v-model="searchText"
          type="text"
          class="search-input"
          placeholder="搜索知识库名称或描述"
        />
      </div>
      <el-button :loading="loading" @click="loadKbList">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </section>

    <section class="kb-content">
      <el-skeleton v-if="loading" :rows="5" animated />
      <div v-else-if="filteredKbList.length === 0" class="empty-state glass-panel">
        <div class="empty-icon">
          <el-icon><Collection /></el-icon>
        </div>
        <h3>还没有知识库</h3>
        <p>点击右上角“新建知识库”，先创建一个资料空间。</p>
      </div>
      <div v-else class="kb-grid">
        <article v-for="kb in filteredKbList" :key="kb.id" class="kb-card motion-card">
          <div class="card-top">
            <div class="card-icon">
              <el-icon><Collection /></el-icon>
            </div>
            <el-dropdown trigger="click">
              <button class="more-btn">
                <el-icon><MoreFilled /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="goToFile(kb.id)">{{ isOwner(kb) ? '管理文件' : '查看文件' }}</el-dropdown-item>
                  <el-dropdown-item v-if="isOwner(kb)" @click="showEditDialog(kb)">编辑知识库</el-dropdown-item>
                  <el-dropdown-item v-if="isOwner(kb)" divided @click="handleDelete(kb.id)">删除知识库</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <h3>{{ kb.name }}</h3>
          <p class="card-desc">{{ kb.description || '暂无描述' }}</p>

          <div class="card-meta">
            <span>
              <el-icon><Document /></el-icon>
              {{ kb.fileCount || 0 }} 个文件
            </span>
            <span>
              <el-icon><Clock /></el-icon>
              {{ formatDate(kb.createTime) }}
            </span>
          </div>

          <div class="card-footer">
            <span class="status-badge" :class="getKbStatusClass(kb)">
              {{ getKbStatusText(kb) }}
            </span>
            <div class="card-actions">
            <el-button @click="goToInsights(kb.id)">策略统计</el-button>
            <el-button @click="goToFile(kb.id)">文件管理</el-button>
              <el-button type="primary" @click="goToChat(kb.id)">去提问</el-button>
            </div>
          </div>
        </article>
      </div>
    </section>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑知识库' : '新建知识库'" width="520px" class="create-dialog">
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：员工制度库" size="large" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            rows="3"
            placeholder="简单说明这个知识库里会放哪些资料"
          />
        </el-form-item>
        <el-form-item v-if="editingId" label="可见范围" prop="visibility">
          <el-radio-group v-model="form.visibility">
            <el-radio-button value="PRIVATE">仅自己可见</el-radio-button>
            <el-radio-button value="PUBLIC">所有用户可读</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="large" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" size="large" @click="handleSave">
          {{ editingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Collection, Document, MoreFilled, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { getKbList, createKb, deleteKb, updateKb } from '../../api/kb'
import { getFileList } from '../../api/file'

const router = useRouter()

const kbList = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const searchText = ref('')

const form = reactive({
  name: '',
  description: '',
  visibility: 'PRIVATE'
})

const formRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
}

// 根据搜索关键字过滤知识库列表
const filteredKbList = computed(() => {
  const keyword = searchText.value.trim().toLowerCase()
  if (!keyword) {
    return kbList.value
  }
  return kbList.value.filter(item => {
    const name = item.name?.toLowerCase() || ''
    const description = item.description?.toLowerCase() || ''
    return name.includes(keyword) || description.includes(keyword)
  })
})

// 统计知识库数量、可问答数量和文件总数
const kbStats = computed(() => {
  return kbList.value.reduce((stats, item) => {
    stats.total += 1
    stats.files += item.fileCount || 0
    if ((item.completedCount || 0) > 0) {
      stats.ready += 1
    }
    return stats
  }, { total: 0, ready: 0, files: 0 })
})

onMounted(() => {
  loadKbList()
})

// 加载知识库列表
async function loadKbList() {
  try {
    loading.value = true
    const res = await getKbList()
    const list = res.data || []
    kbList.value = await Promise.all(list.map(loadKbFileStats))
  } catch (error) {
    console.error(error)
    ElMessage.error('知识库加载失败')
  } finally {
    loading.value = false
  }
}

// 加载单个知识库的文件统计
async function loadKbFileStats(kb) {
  try {
    const res = await getFileList(kb.id)
    const files = res.data || []
    const completedCount = files.filter(file => file.status === 'COMPLETED').length
    const processingCount = files.filter(file => ['PROCESSING', 'UPLOADED'].includes(file.status)).length
    return { ...kb, fileCount: files.length, completedCount, processingCount }
  } catch (error) {
    console.error(error)
    return { ...kb, fileCount: 0, completedCount: 0, processingCount: 0 }
  }
}

// 打开新建知识库弹窗
function showCreateDialog() {
  // 第1步：清空编辑状态和表单内容
  editingId.value = null
  form.name = ''
  form.description = ''
  form.visibility = 'PRIVATE'
  // 第2步：打开知识库表单
  dialogVisible.value = true
}

// 打开编辑知识库弹窗
function showEditDialog(kb) {
  // 第1步：保存待编辑知识库编号
  editingId.value = kb.id
  // 第2步：回填知识库表单
  form.name = kb.name || ''
  form.description = kb.description || ''
  form.visibility = kb.visibility === 'PUBLIC' ? 'PUBLIC' : 'PRIVATE'
  // 第3步：打开知识库表单
  dialogVisible.value = true
}

// 创建或修改知识库
async function handleSave() {
  try {
    // 第1步：校验知识库表单
    await formRef.value.validate()
    saving.value = true
    // 第2步：根据编辑状态调用对应接口
    if (editingId.value) {
      await updateKb(editingId.value, form)
      ElMessage.success('保存成功')
    } else {
      await createKb(form)
      ElMessage.success('创建成功')
    }
    // 第3步：关闭弹窗并刷新知识库列表
    dialogVisible.value = false
    loadKbList()
  } catch (error) {
    if (error) {
      console.error(error)
    }
  } finally {
    // 第4步：关闭保存状态
    saving.value = false
  }
}

// 判断当前用户是否为知识库创建者
function isOwner(kb) {
  // 第1步：统一转成字符串，兼容后端数字和本地字符串类型
  return String(kb.createUser) === String(localStorage.getItem('userId'))
}

// 删除知识库
async function handleDelete(id) {
  try {
    await ElMessageBox.confirm('确定要删除这个知识库吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteKb(id)
    ElMessage.success('删除成功')
    loadKbList()
  } catch (error) {
    if (error !== 'cancel') {
      console.error(error)
    }
  }
}

// 跳转到文件管理页面
function goToFile(kbId) {
  router.push(`/file/${kbId}`)
}

// 打开知识库策略和统计页面
function goToInsights(kbId) {
  router.push(`/kb/${kbId}/insights`)
}

// 跳转到聊天页面并携带知识库编号
function goToChat(kbId) {
  router.push({ path: '/chat', query: { kbId } })
}

// 获取知识库状态文案
function getKbStatusText(kb) {
  if ((kb.processingCount || 0) > 0) {
    return '处理中'
  }
  if ((kb.completedCount || 0) > 0) {
    return '可问答'
  }
  return '待上传'
}

// 获取知识库状态样式
function getKbStatusClass(kb) {
  if ((kb.processingCount || 0) > 0) {
    return 'processing'
  }
  if ((kb.completedCount || 0) > 0) {
    return 'ready'
  }
  return 'pending'
}

// 格式化日期
function formatDate(dateStr) {
  if (!dateStr) {
    return ''
  }
  return new Date(dateStr).toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.kb-hero .create-btn {
  min-width: 150px;
  height: 48px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  position: relative;
  overflow: hidden;
  min-height: 126px;
  padding: 22px;
  border-radius: 12px;
  border: 1px solid var(--line-color);
  background: rgba(30, 31, 35, 0.72);
}

.stat-card::after {
  content: "";
  position: absolute;
  right: -42px;
  bottom: -58px;
  width: 150px;
  height: 150px;
  border-radius: 50%;
  background: rgba(229, 160, 68, 0.1);
}

.stat-card span,
.stat-card small {
  position: relative;
  z-index: 1;
  display: block;
  color: var(--muted-color);
}

.stat-card strong {
  position: relative;
  z-index: 1;
  display: block;
  margin: 12px 0 8px;
  color: var(--ink-color);
  font-size: 38px;
  line-height: 1;
  font-weight: 900;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px;
  margin-bottom: 18px;
  border-radius: 12px;
}

.search-input-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 46px;
  padding: 0 16px;
  border: 1px solid var(--line-color);
  border-radius: 10px;
  background: rgba(247, 245, 242, 0.04);
}

.search-input-wrap .el-icon {
  color: var(--subtle-color);
  font-size: 18px;
}

.search-input {
  width: 100%;
  border: none;
  outline: none;
  color: var(--ink-color);
  background: transparent;
  font-size: 15px;
}

.search-input::placeholder {
  color: var(--subtle-color);
}

.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 400px), 1fr));
  gap: 16px;
}

.kb-card {
  position: relative;
  overflow: hidden;
  min-height: 286px;
  padding: 22px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background:
    radial-gradient(circle at 16% 0%, rgba(229, 160, 68, 0.12), transparent 32%),
    rgba(30, 31, 35, 0.76);
  backdrop-filter: blur(18px);
}

.kb-card::before {
  content: "";
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  background: linear-gradient(90deg, var(--primary-color), transparent);
}

.card-top,
.card-meta,
.card-footer,
.card-actions {
  display: flex;
  align-items: center;
}

.card-top {
  justify-content: space-between;
  margin-bottom: 22px;
}

.card-icon {
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(229, 160, 68, 0.28);
  border-radius: 12px;
  color: var(--primary-dark);
  font-size: 24px;
  background: rgba(229, 160, 68, 0.1);
}

.more-btn {
  width: 36px;
  height: 36px;
  border: 1px solid var(--line-color);
  border-radius: 10px;
  color: var(--muted-color);
  background: rgba(247, 245, 242, 0.04);
}

.kb-card h3 {
  margin-bottom: 10px;
  color: var(--ink-color);
  font-size: 20px;
  font-weight: 850;
}

.card-desc {
  min-height: 48px;
  color: var(--muted-color);
  line-height: 1.7;
}

.card-meta {
  flex-wrap: wrap;
  gap: 14px;
  margin: 20px 0;
  color: var(--muted-color);
}

.card-meta span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.card-footer {
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding-top: 18px;
  border-top: 1px solid var(--line-color);
}

.status-badge {
  display: inline-flex;
  padding: 7px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 850;
}

.status-badge.ready {
  color: var(--success-color);
  background: rgba(88, 214, 141, 0.12);
}

.status-badge.processing {
  color: var(--warning-color);
  background: rgba(244, 179, 90, 0.12);
}

.status-badge.pending {
  color: var(--muted-color);
  background: rgba(247, 245, 242, 0.06);
}

.card-actions {
  flex: 1 1 auto;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.card-actions .el-button {
  margin-left: 0;
}

.empty-state {
  display: grid;
  place-items: center;
  min-height: 320px;
  padding: 40px;
  border-radius: 12px;
  text-align: center;
}

.empty-icon {
  width: 76px;
  height: 76px;
  display: grid;
  place-items: center;
  margin-bottom: 16px;
  border: 1px solid rgba(229, 160, 68, 0.28);
  border-radius: 18px;
  color: var(--primary-color);
  font-size: 36px;
  background: rgba(229, 160, 68, 0.1);
}

.empty-state h3 {
  margin-bottom: 8px;
  color: var(--ink-color);
  font-size: 22px;
}

.empty-state p {
  color: var(--muted-color);
}

@media (max-width: 860px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }

  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .kb-grid {
    grid-template-columns: 1fr;
  }
}
</style>
