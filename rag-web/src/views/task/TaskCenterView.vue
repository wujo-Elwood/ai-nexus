<template>
  <div class="task-page page-shell">
    <section class="page-hero"><div><span class="eyebrow">Task Center</span><h1 class="section-title">统一任务中心</h1><p class="section-desc">集中查看文件处理、上传、抽取、智能体和生图任务。</p></div><el-button :loading="loading" @click="loadTasks">刷新</el-button></section>
    <section class="toolbar glass-panel"><el-select v-model="filters.status" clearable placeholder="全部状态" @change="loadTasks"><el-option label="处理中" value="PROCESSING"/><el-option label="失败" value="FAILED"/><el-option label="完成" value="COMPLETED"/><el-option label="成功" value="SUCCESS"/></el-select><el-select v-model="filters.taskType" clearable placeholder="全部类型" @change="loadTasks"><el-option v-for="item in types" :key="item.value" :label="item.label" :value="item.value"/></el-select></section>
    <section class="glass-panel rebuild-panel">
      <div class="rebuild-head"><div><span class="eyebrow">Vector Rebuild</span><h2>全局向量重建</h2></div><el-tag :type="rebuildStatus.rebuilding ? 'warning' : rebuildStatus.status === 'FAILED' ? 'danger' : 'success'">{{ rebuildStatus.rebuilding ? '重建中' : rebuildStatus.status === 'FAILED' ? '失败' : '已完成' }}</el-tag></div>
      <el-progress :percentage="Number(rebuildStatus.progress || 0)" :status="rebuildStatus.status === 'FAILED' ? 'exception' : undefined" />
      <div class="rebuild-meta"><span>已完成 {{ rebuildStatus.completedFiles || 0 }} / {{ rebuildStatus.totalFiles || 0 }} 个文件</span><span>失败 {{ rebuildStatus.failedFiles || 0 }} 个</span><span v-if="rebuildStatus.startedBy">发起用户 ID：{{ rebuildStatus.startedBy }}</span></div>
      <p v-if="rebuildStatus.error" class="rebuild-error">{{ rebuildStatus.error }}</p>
    </section>
    <section class="glass-panel task-panel"><el-table :data="tasks" v-loading="loading"><el-table-column prop="taskType" label="类型" width="100"/><el-table-column prop="taskName" label="任务" min-width="240"/><el-table-column prop="status" label="状态" width="120"/><el-table-column label="进度" width="180"><template #default="{row}"><el-progress :percentage="Number(row.progress || 0)" :show-text="true"/></template></el-table-column><el-table-column prop="message" label="说明" min-width="220"/><el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button v-if="row.retryable" link type="primary" @click="retry(row)">重试</el-button><el-button v-if="row.cancellable" link type="danger" @click="cancel(row)">取消</el-button></template></el-table-column></el-table><div v-if="!loading && tasks.length===0" class="empty">暂无任务</div></section>
  </div>
</template>
<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cancelTask, getTasks, retryTask } from '../../api/tasks'
import { getGlobalRebuildStatus } from '../../api/kb'
const loading=ref(false); const tasks=ref([]); const rebuildStatus=ref({status:'COMPLETED',progress:100}); const filters=reactive({status:'',taskType:''}); const types=[{value:'FILE',label:'文件处理'},{value:'UPLOAD',label:'分片上传'},{value:'EXTRACT',label:'文档抽取'},{value:'IMAGE',label:'AI 生图'}]; let rebuildTimer=null
onMounted(async()=>{await Promise.all([loadTasks(),loadRebuildStatus()]); rebuildTimer=window.setInterval(loadRebuildStatus,2000)})
onBeforeUnmount(()=>{if(rebuildTimer){window.clearInterval(rebuildTimer);rebuildTimer=null}})
// 加载统一任务
async function loadTasks(){loading.value=true;try{const res=await getTasks(filters);tasks.value=res.data||[]}catch(error){console.error(error)}finally{loading.value=false}}
// 加载全局向量重建状态
async function loadRebuildStatus(){try{const res=await getGlobalRebuildStatus();if(res.data)rebuildStatus.value=res.data}catch(error){console.error(error)}}
// 重试任务
async function retry(row){try{await retryTask(row.taskType,row.taskId);ElMessage.success('已提交重试');await loadTasks()}catch(error){console.error(error)}}
// 取消任务
async function cancel(row){try{await cancelTask(row.taskType,row.taskId);ElMessage.success('已取消');await loadTasks()}catch(error){console.error(error)}}
</script>
<style scoped>
.toolbar{display:flex;gap:12px;padding:14px;margin-bottom:18px}.rebuild-panel{padding:18px;margin-bottom:18px}.rebuild-head,.rebuild-meta{display:flex;align-items:center;justify-content:space-between;gap:12px}.rebuild-head h2{margin:4px 0 14px}.rebuild-meta{margin-top:10px;color:var(--muted-color);font-size:13px}.rebuild-error{color:var(--el-color-danger);margin:10px 0 0}.task-panel{padding:18px}.empty{padding:48px;text-align:center;color:var(--muted-color)}@media(max-width:600px){.toolbar{flex-direction:column}.toolbar .el-select{width:100%}.rebuild-meta{align-items:flex-start;flex-direction:column}}
</style>
