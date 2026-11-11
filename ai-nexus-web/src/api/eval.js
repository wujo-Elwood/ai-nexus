import request from '../utils/request'
// 查询评测问题
export function getEvalCases(kbId){return request.get('/api/eval/cases',{params:{kbId}})}
// 新增评测问题
export function addEvalCase(data){return request.post('/api/eval/cases',data)}
// 删除评测问题
export function deleteEvalCase(id){return request.delete(`/api/eval/cases/${id}`)}
// 执行评测（逐条跑真实检索链路，用例多时会比较慢，单独放宽超时）
export function runEval(kbId){return request.post('/api/eval/runs',null,{params:{kbId},timeout:300000})}
// 查询评测历史
export function getEvalRuns(kbId){return request.get('/api/eval/runs',{params:{kbId}})}
// 查询某次评测明细
export function getEvalRunDetail(runId){return request.get(`/api/eval/runs/${runId}`)}
