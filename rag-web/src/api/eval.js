import request from '../utils/request'
// 查询评测问题
export function getEvalCases(kbId){return request.get('/api/eval/cases',{params:{kbId}})}
// 新增评测问题
export function addEvalCase(data){return request.post('/api/eval/cases',data)}
// 删除评测问题
export function deleteEvalCase(id){return request.delete(`/api/eval/cases/${id}`)}
// 执行评测
export function runEval(kbId){return request.post('/api/eval/runs',null,{params:{kbId}})}
// 查询评测历史
export function getEvalRuns(kbId){return request.get('/api/eval/runs',{params:{kbId}})}
