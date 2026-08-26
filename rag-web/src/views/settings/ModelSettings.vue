<template>
  <div class="settings-page page-shell">
    <section class="page-hero">
      <div>
        <span class="eyebrow">Model Providers</span>
        <h1 class="section-title">模型供应商配置</h1>
        <p class="section-desc">
          统一管理大模型 API 地址、密钥和模型名称。AI 生图可以单独配置生图地址、密钥和模型；不填写时沿用当前供应商的普通模型配置。
        </p>
      </div>
      <el-button type="primary" size="large" @click="openDialog()">
        <el-icon><Plus /></el-icon>
        新增供应商
      </el-button>
    </section>

    <section class="provider-panel glass-panel">
      <el-table :data="providerTableList" :row-class-name="getProviderRowClass" stripe class="provider-table">
        <el-table-column prop="name" label="供应商名称" min-width="170">
          <template #default="{ row }">
            <div class="provider-name-cell">
              <span>{{ row.name }}</span>
              <span v-if="row.isActive === 1" class="active-provider-mark">使用中</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="对话模型" width="180" show-overflow-tooltip />
        <el-table-column prop="imageModel" label="生图模型" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.imageModel || '沿用对话模型' }}
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="API 地址" min-width="240" show-overflow-tooltip />
        <el-table-column prop="imageBaseUrl" label="生图 API 地址" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.imageBaseUrl || '沿用普通 API 地址' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">
              {{ row.isActive === 1 ? '使用中' : '未启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="center">
          <template #default="{ row }">
            <el-button v-if="row.isActive !== 1" type="primary" link @click="handleActivate(row)">
              激活
            </el-button>
            <el-button type="primary" link @click="openDialog(row)">
              编辑
            </el-button>
            <el-button type="danger" link :disabled="row.isActive === 1" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑供应商' : '新增供应商'"
      width="720px"
      destroy-on-close
    >
      <el-form :model="form" label-position="top" class="provider-form">
        <div class="form-section">
          <h3>对话模型配置</h3>
          <el-form-item label="供应商名称">
            <el-input v-model="form.name" placeholder="例如：OpenAI、DeepSeek、Mimo" />
          </el-form-item>
          <el-form-item label="API 地址">
            <el-input v-model="form.baseUrl" placeholder="例如：https://api.openai.com" />
          </el-form-item>
          <el-form-item label="API 密钥">
            <el-input v-model="form.apiKey" type="password" show-password placeholder="请输入 API Key" />
          </el-form-item>
          <el-form-item label="对话模型名称">
            <el-input v-model="form.model" placeholder="例如：gpt-5.4-mini、deepseek-chat" />
          </el-form-item>
        </div>

        <div class="form-section image-section">
          <h3>AI 生图配置</h3>
          <p>这里专门给 AI 生图使用。不填地址或密钥时，会沿用上面的普通 API 地址和密钥。</p>
          <el-form-item label="生图 API 地址">
            <el-input v-model="form.imageBaseUrl" placeholder="例如：https://zyyc.mxou.cn/v1" />
          </el-form-item>
          <el-form-item label="生图 API 密钥">
            <el-input v-model="form.imageApiKey" type="password" show-password placeholder="不填则沿用普通 API Key" />
          </el-form-item>
          <el-form-item label="生图模型名称">
            <el-input v-model="form.imageModel" placeholder="默认：gpt-image-2" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  activateModelProvider,
  createModelProvider,
  deleteModelProvider,
  getModelProviderList,
  updateModelProvider
} from '../../api/modelProvider'

const providerList = ref([])
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = ref(createEmptyForm())

// 供应商表格列表，当前使用中的供应商固定展示在第一行
const providerTableList = computed(() => {
  return [...providerList.value].sort((left, right) => {
    if (left.isActive === right.isActive) {
      return 0
    }
    return left.isActive === 1 ? -1 : 1
  })
})

onMounted(() => {
  loadList()
})

// 创建空的供应商表单
function createEmptyForm() {
  return {
    name: '',
    baseUrl: '',
    apiKey: '',
    model: '',
    imageBaseUrl: '',
    imageApiKey: '',
    imageModel: 'gpt-image-2'
  }
}

// 加载供应商列表
async function loadList() {
  try {
    const res = await getModelProviderList()
    providerList.value = res.data || []
  } catch (error) {
    console.error(error)
    ElMessage.error('供应商列表加载失败')
  }
}

// 打开新增或编辑弹窗
function openDialog(row) {
  if (row) {
    editingId.value = row.id
    form.value = {
      name: row.name || '',
      baseUrl: row.baseUrl || '',
      apiKey: row.apiKey || '',
      model: row.model || '',
      imageBaseUrl: row.imageBaseUrl || '',
      imageApiKey: row.imageApiKey || '',
      imageModel: row.imageModel || 'gpt-image-2'
    }
  } else {
    editingId.value = null
    form.value = createEmptyForm()
  }
  dialogVisible.value = true
}

// 保存供应商配置
async function handleSave() {
  if (!form.value.name || !form.value.baseUrl || !form.value.apiKey || !form.value.model) {
    ElMessage.warning('请填写对话模型的必填字段')
    return
  }
  saving.value = true
  try {
    const payload = normalizePayload(form.value)
    if (editingId.value) {
      await updateModelProvider(editingId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await createModelProvider(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadList()
  } catch (error) {
    console.error(error)
    ElMessage.error('操作失败')
  } finally {
    saving.value = false
  }
}

// 清理提交给后端的空字符串
function normalizePayload(data) {
  return {
    ...data,
    imageBaseUrl: cleanBlank(data.imageBaseUrl),
    imageApiKey: cleanBlank(data.imageApiKey),
    imageModel: cleanBlank(data.imageModel) || 'gpt-image-2'
  }
}

// 把空白字符串转成空值
function cleanBlank(value) {
  const text = String(value || '').trim()
  return text || null
}

// 获取供应商表格行样式，当前使用中的供应商整行高亮
function getProviderRowClass({ row }) {
  return row.isActive === 1 ? 'active-provider-row' : ''
}

// 激活供应商
async function handleActivate(row) {
  try {
    await activateModelProvider(row.id)
    ElMessage.success(`已切换到 ${row.name}`)
    loadList()
  } catch (error) {
    console.error(error)
    ElMessage.error('切换失败')
  }
}

// 删除供应商
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除供应商“${row.name}”吗？`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteModelProvider(row.id)
    ElMessage.success('已删除')
    loadList()
  } catch (error) {
    console.error(error)
    ElMessage.error('删除失败')
  }
}
</script>

<style scoped>
.provider-panel {
  padding: 22px;
  border-radius: 12px;
}

.provider-table {
  width: 100%;
}

.provider-table :deep(.active-provider-row) {
  position: relative;
}

.provider-table :deep(.active-provider-row td) {
  background: rgba(245, 174, 70, 0.12) !important;
  border-top: 1px solid rgba(245, 174, 70, 0.36);
  border-bottom: 1px solid rgba(245, 174, 70, 0.36);
}

.provider-table :deep(.active-provider-row td:first-child) {
  border-left: 2px solid rgba(245, 174, 70, 0.86);
}

.provider-table :deep(.active-provider-row td:last-child) {
  border-right: 1px solid rgba(245, 174, 70, 0.36);
}

.provider-name-cell {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-weight: 800;
}

.active-provider-mark {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 9px;
  border: 1px solid rgba(245, 174, 70, 0.48);
  border-radius: 999px;
  background: rgba(245, 174, 70, 0.14);
  color: #f5ae46;
  font-size: 12px;
  font-weight: 850;
  line-height: 1;
  white-space: nowrap;
}

.provider-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.form-section {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--line-color);
  border-radius: 12px;
  background: rgba(247, 245, 242, 0.035);
}

.form-section h3 {
  margin: 0 0 14px;
  color: var(--ink-color);
  font-size: 16px;
  font-weight: 850;
}

.form-section p {
  margin: -6px 0 14px;
  color: var(--muted-color);
  line-height: 1.6;
}

.image-section {
  border-color: rgba(229, 160, 68, 0.24);
  background: rgba(229, 160, 68, 0.055);
}

@media (max-width: 760px) {
  .provider-form {
    grid-template-columns: 1fr;
  }
}
</style>
