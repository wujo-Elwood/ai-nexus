<template>
  <div class="kb-page page-shell">
    <header class="console-head">
      <div class="head-row">
        <div>
          <h1>知识库</h1>
          <p class="desc">上传资料自动解析、切片、向量化，聊天时按库召回并注明引用。</p>
        </div>
        <div class="head-actions">
          <el-button @click="router.push('/knowledge-gaps')">知识缺口分析</el-button>
          <button class="btn-primary" @click="showCreateDialog">＋ 新建知识库</button>
        </div>
      </div>
      <div class="aurora-band"></div>
    </header>

    <section v-if="rebuildActive" class="rebuild-line">
      <span class="st warn"><span class="dot warn"></span>全局向量重建中</span>
      <span class="rebuild-pct mono">{{ Number(rebuildStatus.progress || 0) }}%</span>
      <span class="rebuild-meta-text">{{ rebuildStatus.completedFiles || 0 }}/{{ rebuildStatus.totalFiles || 0 }} 个文件<template v-if="rebuildStatus.failedFiles"> · 失败 {{ rebuildStatus.failedFiles }}</template></span>
      <router-link class="rebuild-link" to="/tasks">查看任务中心 →</router-link>
    </section>
    <section v-else-if="rebuildStatus.status === 'FAILED'" class="rebuild-line">
      <span class="st bad"><span class="dot bad"></span>⚠ 全局向量重建失败</span>
      <span v-if="rebuildStatus.error" class="rebuild-meta-text">{{ rebuildStatus.error }}</span>
      <router-link class="rebuild-link" to="/tasks">查看任务中心 →</router-link>
    </section>

    <section class="readouts">
      <div class="readout"><div class="val">{{ kbStats.total }}</div><div class="lab">知识库</div></div>
      <div class="readout"><div class="val">{{ kbStats.ready }}</div><div class="lab">可问答</div></div>
      <div class="readout"><div class="val">{{ kbStats.loaded ? kbStats.files : '—' }}</div><div class="lab">文件</div></div>
    </section>

    <section class="toolbar">
      <input
        v-model="searchText"
        type="text"
        class="search"
        placeholder="搜索知识库名称或描述…"
      />
      <div class="spacer"></div>
      <div class="filters">
        <a :class="{ on: filterMode === 'all' }" @click.prevent="filterMode = 'all'" href="#">全部</a>
        <a :class="{ on: filterMode === 'mine' }" @click.prevent="filterMode = 'mine'" href="#">我创建的</a>
        <a :class="{ on: filterMode === 'shared' }" @click.prevent="filterMode = 'shared'" href="#">共享给我的</a>
        <a @click.prevent="loadKbList" href="#">⟳ 刷新</a>
      </div>
    </section>

    <section class="kb-content">
      <el-skeleton v-if="loading" :rows="5" animated />
      <div v-else-if="filteredKbList.length === 0" class="empty-state">
        <template v-if="kbList.length === 0">
          <p>还没有知识库</p>
          <a class="empty-link" @click.prevent="showCreateDialog" href="#">新建第一个知识库</a>
        </template>
        <template v-else>
          <p>没有匹配“{{ searchText }}”的知识库</p>
          <a class="empty-link" @click.prevent="searchText = ''" href="#">清除筛选</a>
        </template>
      </div>
      <template v-else>
        <div class="sys-label list-label">共 {{ filteredKbList.length }} 个知识库</div>
        <div class="kb-rows">
          <article v-for="(kb, index) in filteredKbList" :key="kb.id" class="kb-entry">
            <span class="e-no mono">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="e-main">
              <div class="e-name serif" @click="goToFile(kb.id)">
                {{ kb.name }}
                <!-- 文件统计按需加载，未加载前不显示状态胶囊，避免误报「待上传」 -->
                <span v-if="kb.statsLoaded" class="st" :class="statusKind(kb)"><span class="dot" :class="statusKind(kb)"></span>{{ statusText(kb) }}</span>
              </div>
              <p class="e-desc">{{ kb.description || '暂无描述' }}</p>
            </div>
            <div class="leaders"></div>
            <div class="e-readout mono">
              <template v-if="kb.statsLoaded">
                {{ kb.fileCount || 0 }} 文件 · <b>{{ kb.completedCount || 0 }}</b> 可问答<br>
                更新 {{ formatDate(kb.createTime) }}
              </template>
              <template v-else>
                <a class="stat-load" href="#" @click.stop.prevent="loadKbStats(kb)">
                  {{ kb.statsLoading ? '统计中…' : '展开文件统计' }}
                </a><br>
                更新 {{ formatDate(kb.createTime) }}
              </template>
            </div>
            <div class="e-act">
              <el-dropdown trigger="click">
                <button class="more-btn">更多 ▾</button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="goToFile(kb.id)">{{ isOwner(kb) ? '管理文件' : '查看文件' }}</el-dropdown-item>
                    <el-dropdown-item @click="goToInsights(kb.id)">策略统计</el-dropdown-item>
                    <el-dropdown-item v-if="isOwner(kb)" @click="showEditDialog(kb)">编辑知识库</el-dropdown-item>
                    <el-dropdown-item v-if="isOwner(kb)" @click="openShareDialog(kb)">共享设置</el-dropdown-item>
                    <el-dropdown-item v-if="isOwner(kb)" divided @click="handleDelete(kb.id)">删除知识库</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <a class="row-cta" @click.prevent="goToChat(kb.id)" href="#">提问 →</a>
            </div>
          </article>

          <article class="kb-entry kb-entry-ghost" @click="showCreateDialog">
            <span class="e-no mono">＋</span>
            <div class="e-main">
              <div class="e-name serif">新建知识库</div>
              <p class="e-desc">建立新的图版条目，上传资料后自动进入解析队列。</p>
            </div>
            <div class="leaders"></div>
            <div class="e-act"><a class="row-cta" href="#">＋ 新建</a></div>
          </article>
        </div>
      </template>
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

    <el-dialog v-model="shareVisible" :title="`共享设置 — ${shareKb.name || ''}`" width="560px">
      <p class="share-hint">
        把私有知识库授权给指定用户。可阅读：问答检索和文件查看下载；可管理：还能上传、删除和修改配置。
      </p>
      <div class="share-add">
        <el-select
          v-model="shareForm.userId"
          filterable
          placeholder="搜索并选择用户"
          class="share-user-select"
        >
          <el-option v-for="user in shareUsers" :key="user.id" :label="user.name" :value="user.id" />
        </el-select>
        <el-radio-group v-model="shareForm.permission">
          <el-radio-button value="READ">可阅读</el-radio-button>
          <el-radio-button value="MANAGE">可管理</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :loading="addingGrant" :disabled="!shareForm.userId" @click="handleAddGrant">添加</el-button>
      </div>
      <el-table :data="grants" size="small" empty-text="还没有共享授权">
        <el-table-column prop="granteeName" label="用户" min-width="120" />
        <el-table-column label="权限" width="100">
          <template #default="{ row }">
            <el-tag :type="row.permission === 'MANAGE' ? 'warning' : 'info'" size="small">
              {{ row.permission === 'MANAGE' ? '可管理' : '可阅读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="handleRemoveGrant(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getKbList, createKb, deleteKb, updateKb, getGlobalRebuildStatus, getKbGrants, addKbGrant, removeKbGrant, getShareUsers } from '../../api/kb'
import { getFileList } from '../../api/file'
import { mascot } from '../../composables/useMascot'

const router = useRouter()

const kbList = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const searchText = ref('')
const filterMode = ref('all')
const rebuildStatus = ref({ status: 'COMPLETED', progress: 100 })
let rebuildTimer = null

// 重建进行中才显示页头下的状态行，完成/空闲不渲染
const rebuildActive = computed(() => rebuildStatus.value.rebuilding === true)

// 是否需要轮询：只有全局重建进行中，或某个知识库还有文件在解析时才轮询
const shouldPoll = computed(() => rebuildActive.value || kbList.value.some(kb => (kb.processingCount || 0) > 0))

// 共享设置弹窗
const shareVisible = ref(false)
const shareKb = reactive({ id: null, name: '' })
const grants = ref([])
const shareUsers = ref([])
const shareForm = reactive({ userId: null, permission: 'READ' })
const addingGrant = ref(false)

const form = reactive({
  name: '',
  description: '',
  visibility: 'PRIVATE'
})

const formRules = {
  name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
}

// 根据搜索关键字和归属筛选知识库列表
const filteredKbList = computed(() => {
  let list = kbList.value
  if (filterMode.value === 'mine') {
    list = list.filter(item => isOwner(item))
  } else if (filterMode.value === 'shared') {
    list = list.filter(item => !isOwner(item))
  }
  const keyword = searchText.value.trim().toLowerCase()
  if (!keyword) {
    return list
  }
  return list.filter(item => {
    const name = item.name?.toLowerCase() || ''
    const description = item.description?.toLowerCase() || ''
    return name.includes(keyword) || description.includes(keyword)
  })
})

// 统计知识库数量、可问答数量和文件总数（文件数按需加载，只汇总已展开的行）
const kbStats = computed(() => {
  return kbList.value.reduce((stats, item) => {
    stats.total += 1
    // 未展开统计的行不参与文件数和可问答数量汇总
    if (!item.statsLoaded) {
      return stats
    }
    stats.loaded += 1
    stats.files += item.fileCount || 0
    if ((item.completedCount || 0) > 0) {
      stats.ready += 1
    }
    return stats
  }, { total: 0, ready: 0, files: 0, loaded: 0 })
})

onMounted(() => {
  loadKbList()
  // 只探一次状态，是否开始轮询由 shouldPoll 决定
  loadRebuildStatus()
})

onBeforeUnmount(() => {
  // 离开知识库页面后停止轮询，避免后台持续请求
  if (rebuildTimer) {
    window.clearInterval(rebuildTimer)
    rebuildTimer = null
  }
})

// 加载知识库列表
async function loadKbList() {
  try {
    loading.value = true
    const res = await getKbList()
    const list = res.data || []
    // 第1步：记住已经手动展开过的统计，刷新列表时不重复请求
    const statsCache = new Map(
      kbList.value.filter(kb => kb.statsLoaded).map(kb => [kb.id, kb])
    )
    // 第2步：列表接口不带文件统计，这里只打标记，等用户展开某一行再单独请求
    kbList.value = list.map(kb => {
      const cached = statsCache.get(kb.id)
      return {
        ...kb,
        fileCount: cached ? cached.fileCount : 0,
        completedCount: cached ? cached.completedCount : 0,
        processingCount: cached ? cached.processingCount : 0,
        statsLoaded: Boolean(cached),
        statsLoading: false
      }
    })
  } catch (error) {
    console.error(error)
    ElMessage.error('知识库加载失败')
  } finally {
    loading.value = false
    // 第3步：列表变化后重新判断是否需要轮询
    syncRebuildTimer()
  }
}

// 加载全局向量重建状态
async function loadRebuildStatus() {
  try {
    // 调用状态接口获取最新重建进度
    const res = await getGlobalRebuildStatus()
    if (res.data) {
      rebuildStatus.value = res.data
    }
  } catch (error) {
    console.error(error)
  } finally {
    // 拿到最新状态后按需启动或停止轮询
    syncRebuildTimer()
  }
}

// 按需启停轮询：只有确实有任务在跑时才定时请求，空闲时完全不轮询
function syncRebuildTimer() {
  if (shouldPoll.value && !rebuildTimer) {
    // 第1步：存在进行中的重建/解析，每 2 秒刷新一次进度
    rebuildTimer = window.setInterval(loadRebuildStatus, 2000)
  } else if (!shouldPoll.value && rebuildTimer) {
    // 第2步：任务已结束，停掉定时器，别再打接口
    window.clearInterval(rebuildTimer)
    rebuildTimer = null
  }
}

// 按需加载单个知识库的文件统计，只在用户展开这一行时才请求
async function loadKbStats(kb) {
  // 第1步：已加载或正在加载时直接返回，避免重复请求
  if (kb.statsLoaded || kb.statsLoading) {
    return
  }
  try {
    kb.statsLoading = true
    const res = await getFileList(kb.id)
    const files = res.data || []
    // 第2步：统计文件总数、可问答数量和解析中数量
    kb.fileCount = files.length
    kb.completedCount = files.filter(file => file.status === 'COMPLETED').length
    kb.processingCount = files.filter(file => ['PROCESSING', 'UPLOADED'].includes(file.status)).length
    kb.statsLoaded = true
  } catch (error) {
    console.error(error)
  } finally {
    kb.statsLoading = false
    // 第3步：可能刚发现有文件在解析，重新判断是否需要轮询
    syncRebuildTimer()
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

// ---------- 共享设置 ----------

// 打开共享设置弹窗并加载授权列表和可选用户
async function openShareDialog(kb) {
  shareKb.id = kb.id
  shareKb.name = kb.name || ''
  shareForm.userId = null
  shareForm.permission = 'READ'
  shareVisible.value = true
  try {
    const [grantRes, userRes] = await Promise.all([getKbGrants(kb.id), getShareUsers()])
    grants.value = grantRes.data || []
    shareUsers.value = userRes.data || []
  } catch (error) {
    console.error(error)
  }
}

// 添加授权
async function handleAddGrant() {
  if (!shareForm.userId) {
    return
  }
  // 授权给创建者自己没有意义，前端先拦一层
  const kb = kbList.value.find(item => item.id === shareKb.id)
  if (kb && isOwner({ createUser: shareForm.userId })) {
    ElMessage.warning('不能授权给自己，创建者本身就是可管理状态')
    return
  }
  try {
    addingGrant.value = true
    await addKbGrant(shareKb.id, { granteeType: 'USER', granteeId: shareForm.userId, permission: shareForm.permission })
    ElMessage.success('授权已添加')
    shareForm.userId = null
    shareForm.permission = 'READ'
    grants.value = (await getKbGrants(shareKb.id)).data || []
  } catch (error) {
    console.error(error)
  } finally {
    addingGrant.value = false
  }
}

// 移除授权，权限立即收回
async function handleRemoveGrant(grant) {
  try {
    await ElMessageBox.confirm(`确定移除对「${grant.granteeName}」的授权？移除后对方将无法访问该知识库`, '移除确认', { type: 'warning' })
  } catch (error) {
    return
  }
  try {
    await removeKbGrant(shareKb.id, grant.id)
    ElMessage.success('授权已移除')
    grants.value = (await getKbGrants(shareKb.id)).data || []
  } catch (error) {
    console.error(error)
  }
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
    mascot.playAction('goodbye')   // 挥泪告别
    mascot.speak('再见了，老朋友…我会想你的')
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
function statusText(kb) {
  if ((kb.processingCount || 0) > 0) {
    return '解析中'
  }
  if ((kb.completedCount || 0) > 0) {
    return '可问答'
  }
  return '待上传'
}


// 获取知识库状态语义色（ok / warn / idle）
function statusKind(kb) {
  if ((kb.processingCount || 0) > 0) {
    return 'warn'
  }
  if ((kb.completedCount || 0) > 0) {
    return 'ok'
  }
  return 'idle'
}

// 格式化日期：近两天转相对时间，其余转短日期
function formatDate(dateStr) {
  if (!dateStr) {
    return ''
  }
  const date = new Date(dateStr)
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) {
    return '刚刚'
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`
  }
  return date.toLocaleDateString('zh-CN')
}
</script>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

/* 重建状态行：仅进行中/失败时出现在页头下一行 */
.rebuild-line {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 9px 14px;
  margin-bottom: 14px;
  border: 1px solid var(--amber-line-dim);
  border-radius: 0;
  background: rgba(236, 233, 225, 0.04);
  font-size: 12.5px;
}

.rebuild-pct {
  color: var(--amber-hi);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.rebuild-meta-text {
  color: var(--text-3);
  font-size: 12px;
}

.rebuild-link {
  margin-left: auto;
  color: var(--amber-hi);
  font-size: 12.5px;
}

.rebuild-link:hover {
  text-decoration: underline;
}

.list-label {
  margin-bottom: 10px;
}

/* 行内按需加载文件统计的入口 */
.stat-load {
  color: var(--amber-hi);
  font-size: 12px;
}

.stat-load:hover {
  text-decoration: underline;
}

/* 图鉴目录行：编号 + 衬线名 + 点线 + 右对齐读数 */
.kb-rows {
  display: flex;
  flex-direction: column;
}

.kb-entry {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 18px;
  padding: 20px 8px 18px;
  border-bottom: 1px solid var(--rule);
  transition: background 0.15s var(--motion-curve);
}

.kb-entry:hover {
  background: var(--row-hover);
}

.e-no {
  width: 34px;
  flex: 0 0 auto;
  color: var(--text-3);
  font-size: 11px;
}

.e-main {
  flex: 0 1 auto;
  min-width: 0;
}

.e-name {
  display: flex;
  align-items: baseline;
  gap: 14px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.01em;
  cursor: pointer;
}

.e-name:hover {
  color: #fff;
}

.e-desc {
  margin-top: 5px;
  color: var(--text-2);
  font-size: 12.5px;
  max-width: 560px;
  line-height: 1.7;
}

/* 点线引导：名称区与读数区之间 */
.leaders {
  flex: 1;
  min-width: 40px;
  border-bottom: 1px dotted rgba(236, 233, 225, 0.32);
  transform: translateY(-4px);
}

.e-readout {
  flex: 0 0 auto;
  text-align: right;
  color: var(--text-3);
  font-size: 11px;
  line-height: 1.9;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.e-readout b {
  color: var(--text-1);
  font-weight: 500;
}

.e-act {
  flex: 0 0 auto;
  display: flex;
  gap: 16px;
  align-items: baseline;
}

.kb-entry-ghost {
  cursor: pointer;
}

.kb-entry-ghost .e-name {
  color: var(--text-3);
  font-size: 17px;
}

.kb-entry-ghost:hover .e-name {
  color: var(--text-2);
}

.kb-entry-ghost .e-desc {
  color: var(--text-3);
}

.kb-entry-ghost .leaders {
  border-bottom-style: dashed;
  opacity: 0.55;
}

.more-btn {
  padding: 4px 8px;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 16px;
  line-height: 1;
}

.more-btn:hover {
  color: #ece9e1;
}

.empty-state {
  display: grid;
  place-items: center;
  gap: 8px;
  min-height: 220px;
  padding: 40px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
}

.empty-link {
  color: var(--amber-hi);
  font-size: 13px;
  text-decoration: underline;
  text-underline-offset: 4px;
}

.share-hint {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-2);
}

.share-add {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.share-user-select {
  flex: 1;
}

@media (max-width: 1100px) {
  .kb-entry {
    flex-wrap: wrap;
  }

  .leaders {
    display: none;
  }
}

@media (max-width: 860px) {
  .head-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
