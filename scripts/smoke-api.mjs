const BASE = 'http://127.0.0.1:8080/api/v1'
const results = []
let token = null
const uniq = Date.now().toString().slice(-8)

async function call(method, path, body, opts = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token && !opts.noAuth) headers.Authorization = `Bearer ${token}`
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  const payload = data && typeof data === 'object' && 'data' in data ? data.data : data
  return { status: res.status, data: payload, error: data?.error }
}
function check(name, cond, detail = '') {
  results.push({ name, pass: !!cond, detail })
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
}
const login = (u, p) => call('POST', '/auth/sessions', { username: u, password: p, clientType: 'WEB' }, { noAuth: true })

// ========== 认证 ==========
let r = await login('2410085246', 'wrong-pass')
check('错误密码拒绝登录', r.status === 401, `status=${r.status}`)

r = await login('2410085246', '123456789')
check('正确密码登录', (r.status === 200 || r.status === 201) && !!r.data?.accessToken, `status=${r.status}`)
token = r.data?.accessToken

r = await call('GET', '/users/me')
check('读取本人信息', r.status === 200 && r.data?.username === '2410085246', `role=${r.data?.role}`)

const userToken = token
token = null
r = await call('GET', '/materials')
check('未携带 token 访问资料 401', r.status === 401, `status=${r.status}`)
token = userToken

// ========== 资料 ==========
r = await call('POST', '/materials', {
  type: 'NOTE', title: `回归测试笔记 ${uniq}`, subject: '测试', tags: ['smoke'],
  noteText: '回归测试创建的正文，用于验证创建、读取、更新、删除四条链路。',
})
check('创建文字笔记', r.status === 201 && !!r.data?.id, `status=${r.status}`)
const materialId = r.data?.id
let version = r.data?.version

r = await call('GET', `/materials/${materialId}`)
check('读取笔记详情', r.status === 200 && String(r.data?.title).includes(uniq), `version=${r.data?.version}`)

r = await call('PATCH', `/materials/${materialId}`, { version, title: `回归测试笔记 ${uniq}（改）`, noteText: '更新后的正文。' })
check('更新笔记（乐观锁）', r.status === 200 && r.data?.version > version, `newVersion=${r.data?.version} status=${r.status}`)
const staleVersion = version
version = r.data?.version

r = await call('PATCH', `/materials/${materialId}`, { version: staleVersion, title: '过期写入' })
check('旧版本写入被拒 409', r.status === 409, `status=${r.status}`)

r = await call('GET', '/materials?page=1&pageSize=5')
check('资料列表分页', r.status === 200 && Array.isArray(r.data?.items), `total=${r.data?.total}`)

r = await call('PUT', `/favorites/MATERIAL/${materialId}`)
check('收藏资料', r.status === 200 || r.status === 201 || r.status === 204, `status=${r.status}`)
r = await call('DELETE', `/favorites/MATERIAL/${materialId}`)
check('取消收藏', r.status === 200 || r.status === 204, `status=${r.status}`)

// ========== 送审 ==========
r = await call('POST', `/materials/${materialId}/review-requests`, { version, reason: '回归测试送审' })
check('提交内容审核', r.status === 200 || r.status === 201, `status=${r.status} state=${r.data?.status}`)
const reviewId = r.data?.id

// ========== 卡片 ==========
r = await call('POST', '/cards', { title: `回归测试卡片 ${uniq}`, summary: '摘要', content: '卡片正文', subject: '测试', tags: ['smoke'] })
check('创建知识卡片', r.status === 201 && !!r.data?.id, `status=${r.status}`)
const cardId = r.data?.id
const cardVersion = r.data?.version

r = await call('GET', `/cards/${cardId}`)
check('读取卡片详情', r.status === 200 && String(r.data?.title).includes(uniq), `status=${r.status}`)

r = await call('GET', '/cards?page=1&pageSize=5')
check('卡片列表分页', r.status === 200 && Array.isArray(r.data?.items), `total=${r.data?.total}`)

// ========== 错误映射 ==========
r = await call('GET', '/definitely-not-a-route')
check('未知路径 404', r.status === 404, `status=${r.status}`)
r = await call('PATCH', '/materials')
check('不支持的方法 405', r.status === 405, `status=${r.status}`)
const raw = await fetch(BASE + '/auth/sessions', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: 'x' })
check('不支持的媒体类型 415', raw.status === 415, `status=${raw.status}`)

// ========== 通知 ==========
r = await call('GET', '/notifications?page=1&pageSize=5')
check('通知列表', r.status === 200 && Array.isArray(r.data?.items), `total=${r.data?.total}`)

// ========== AI 任务 ==========
r = await call('POST', '/ai-tasks', {
  materialId, materialVersion: version, requestKey: `smoke-${uniq}`,
  goalType: 'REVIEW_CARDS', instruction: '提炼一张知识卡片', allowRelatedSearch: false, externalProcessingConsent: true,
})
check('提交 AI 整理任务', r.status === 202 || r.status === 201, `status=${r.status} taskStatus=${r.data?.status}`)
const taskId = r.data?.id

if (taskId) {
  await new Promise(res => setTimeout(res, 1500))
  r = await call('GET', `/ai-tasks/${taskId}`)
  check('读取任务状态', r.status === 200 && !!r.data?.status, `status=${r.data?.status}`)
}

// ========== 管理员 ==========
token = (await login('admin_review', 'Admin@2026')).data?.accessToken
r = await call('GET', '/admin/users?page=1&pageSize=5')
check('管理员读取用户列表', r.status === 200 && Array.isArray(r.data?.items), `total=${r.data?.total}`)

r = await call('GET', '/admin/statistics?from=2026-09-30T16:00:00Z&to=2026-10-30T16:00:00Z&timezone=Asia/Shanghai')
check('管理员读取统计', r.status === 200, `status=${r.status} ${r.error?.message ?? ''}`)

r = await call('GET', '/admin/reviews?page=1&pageSize=10')
check('管理员读取审核队列', r.status === 200 && Array.isArray(r.data?.items), `total=${r.data?.total}`)
check('刚提交的审核出现在队列中', Array.isArray(r.data?.items) && r.data.items.some(x => String(x.id) === String(reviewId)), `reviewId=${reviewId}`)

r = await call('GET', '/admin/ai-tasks?page=1&pageSize=5')
check('管理员读取任务监控', r.status === 200 && Array.isArray(r.data?.items), `total=${r.data?.total}`)

token = userToken
r = await call('GET', '/admin/users?page=1&pageSize=5')
check('普通用户访问管理接口 403', r.status === 403, `status=${r.status}`)

// ========== 清理 ==========
r = await call('GET', `/materials/${materialId}`)
const latestVersion = r.data?.version
r = await call('DELETE', `/materials/${materialId}?version=${latestVersion}`)
check('删除测试笔记', r.status === 204 || r.status === 200, `status=${r.status}`)

r = await call('DELETE', `/cards/${cardId}?version=${cardVersion}`)
check('删除测试卡片', r.status === 204 || r.status === 200, `status=${r.status}`)

const pass = results.filter(x => x.pass).length
console.log(`\n合计 ${results.length} 项，通过 ${pass} 项，失败 ${results.length - pass} 项`)
if (results.length !== pass) {
  console.log('失败明细：')
  results.filter(x => !x.pass).forEach(x => console.log(' -', x.name, x.detail))
  process.exitCode = 1
}
