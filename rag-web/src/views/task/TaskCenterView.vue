<template>
  <div class="task-page page-shell">
    <section class="page-hero"><div><span class="eyebrow">Task Center</span><h1 class="section-title">统一任务中心</h1><p class="section-desc">集中查看文件处理、上传、抽取、智能体和生图任务。</p></div><el-button :loading="loading" @click="loadTasks">刷新</el-button></section>
    <section class="toolbar glass-panel"><el-select v-model="filters.status" clearable placeholder="全部状态" @change="loadTasks"><el-option label="处理中" value="PROCESSING"/><el-option label="失败" value="FAILED"/><el-option label="完成" value="COMPLETED"/><el-option label="成功" value="SUCCESS"/></el-select><el-select v-model="filters.taskType" clearable placeholder="全部类型" @change="loadTasks"><el-option v-for="item in types" :key="item.value" :label="item.label" :value="item.value"/></el-select></section>
    <section class="glass-panel task-panel"><el-table :data="tasks" v-loading="loading"><el-table-column prop="taskType" label="类型" width="100"/><el-table-column prop="taskName" label="任务" min-width="240"/><el-table-column prop="status" label="状态" width="120"/><el-table-column label="进度" width="180"><template #default="{row}"><el-progress :percentage="Number(row.progress || 0)" :show-text="true"/></template></el-table-column><el-table-column prop="message" label="说明" min-width="220"/><el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button v-if="row.retryable" link type="primary" @click="retry(row)">重试</el-button><el-button v-if="row.cancellable" link type="danger" @click="cancel(row)">取消</el-button></template></el-table-column></el-table><div v-if="!loading && tasks.length===0" class="empty">暂无任务</div></section>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { cancelTask, getTasks, retryTask } from '../../api/tasks'
const loading=ref(false); const tasks=ref([]); const filters=reactive({status:'',taskType:''}); const types=[{value:'FILE',label:'文件处理'},{value:'UPLOAD',label:'分片上传'},{value:'EXTRACT',label:'文档抽取'},{value:'AGENT',label:'智能体'},{value:'IMAGE',label:'AI 生图'}]
onMounted(loadTasks)
// 加载统一任务
async function loadTasks(){loading.value=true;try{const res=await getTasks(filters);tasks.value=res.data||[]}catch(error){console.error(error)}finally{loading.value=false}}
// 重试任务
async function retry(row){try{await retryTask(row.taskType,row.taskId);ElMessage.success('已提交重试');await loadTasks()}catch(error){console.error(error)}}
// 取消任务
async function cancel(row){try{await cancelTask(row.taskType,row.taskId);ElMessage.success('已取消');await loadTasks()}catch(error){console.error(error)}}
</script>
<style scoped>
.toolbar{display:flex;gap:12px;padding:14px;margin-bottom:18px}.task-panel{padding:18px}.empty{padding:48px;text-align:center;color:var(--muted-color)}@media(max-width:600px){.toolbar{flex-direction:column}.toolbar .el-select{width:100%}}
</style>
