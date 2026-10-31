const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('../web/node_modules/typescript')
const identity = (id = '1', token = 'admin-token', role = 'ADMIN') => ({ user: { id, role, username: 'account_' + id, displayName: '用户 ' + id }, accessToken: token })
const task = (id = '2', extra = {}) => ({ id, userId: '8', materialId: '19', goalType: 'REVIEW_CARDS', status: 'SUCCEEDED',
  stage: 'CONFIRMED', stepCount: 2, errorCode: null, createdAt: '2026-10-26T00:00:00Z',
  startedAt: '2026-10-26T00:01:00Z', finishedAt: '2026-10-27T00:01:00Z', durationMs: 86400000, ...extra })
const step = (extra = {}) => ({ stepNo: 1, attemptNo: 1, toolName: 'planner', providerKind: 'MODEL', status: 'SUCCESS',
  errorCode: null, startedAt: '2026-10-26T00:01:00Z', finishedAt: '2026-10-26T00:01:00Z', durationMs: 0, ...extra })
const page = (items = [task()], number = 1, total = items.length) => ({ items, page: number, pageSize: 20, total })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function loadModule(filename) {
  const source = fs.readFileSync(filename, 'utf8'), module = { exports: {} }
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  new Function('require', 'module', 'exports', code)(name => loadModule(path.resolve(path.dirname(filename), `${name}.ts`)), module, module.exports)
  return module.exports
}
async function mount(t, request, options = {}) {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/views/AdminTasksView.vue')), '应实现任务监控实际SFC')
  const vue = require('../web/node_modules/vue'), vueRouter = require('../web/node_modules/vue-router')
  const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
  const ts = require('../web/node_modules/typescript'), cache = new Map(), states = new Map()
  const previous = { window: global.window, Document: global.Document, ShadowRoot: global.ShadowRoot }
  global.Document = class {}; global.ShadowRoot = class {}
  let app
  global.window = {}
  const session = vue.ref(options.session === undefined ? identity() : options.session)
  t.after(() => { app?.unmount(); Object.assign(global, previous) })
  class RequestError extends Error { constructor(status, message) { super(message); this.status = status } }
  function load(filename) {
    if (cache.has(filename)) return cache.get(filename)
    let source = fs.readFileSync(filename, 'utf8'), template
    if (filename.endsWith('.vue')) {
      const descriptor = parse(source).descriptor, script = compileScript(descriptor, { id: filename })
      source = script.content
      template = compileTemplate({ source: descriptor.template.content, filename, id: filename,
        compilerOptions: { bindingMetadata: script.bindings, hoistStatic: false } }).code
    }
    const compile = value => ts.transpileModule(value, { compilerOptions: { module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
    const module = { exports: {} }
    new Function('require', 'module', 'exports', compile(source))(specifier => {
      if (specifier === 'vue') return vue
      if (specifier === 'element-plus') return require('./helpers/element-plus')(vue)
      if (specifier === 'vue-router') return { ...vueRouter, createWebHistory: vueRouter.createMemoryHistory }
      if (specifier.endsWith('/auth')) return { session, clearSession() { session.value = null } }
      if (specifier.endsWith('/api')) return { api: request, ApiRequestError: RequestError }
      if (specifier.endsWith('.vue') && !/AdminTasks|ForbiddenView|App.vue|Layout|UiIcon/.test(specifier)) return { render: () => vue.h('div') }
      if (specifier.startsWith('.')) {
        const target = path.resolve(path.dirname(filename), specifier)
        return load(target.endsWith('.vue') ? target : `${target}.ts`)
      }
      throw new Error(`未预期的任务监控依赖：${specifier}`)
    }, module, module.exports)
    if (template) {
      const rendered = { exports: {} }
      new Function('require', 'module', 'exports', compile(template))(() => vue, rendered, rendered.exports)
      module.exports.default.render = function (...args) {
        states.set(path.basename(filename), vue.getCurrentInstance().setupState)
        return rendered.exports.render.apply(this, args)
      }
    }
    cache.set(filename, module.exports)
    return module.exports
  }
  const router = load(path.join(__dirname, '../web/src/router.ts')).default
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, tagName: tag.toUpperCase(), children: [], props: {}, addEventListener() {}, removeEventListener() {},
      getRootNode: () => ({}), get options() { return this.children.filter(node => node.tag === 'option') } }),
    createText: text => ({ text }), createComment: text => ({ text }), setText(node, text) { node.text = text },
    setElementText(node, text) { node.text = text }, patchProp(node, key, before, value) { node.props[key] = value; node[key] = value },
    insert(node, parent, anchor) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
      node.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node) },
    remove(node) { node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = null },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
  })
  app = renderer.createApp(load(path.join(__dirname, '../web/src/App.vue')).default); app.use(router)
  await router.push(options.path || '/admin/ai-tasks'); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 32; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { router, all, flush, session, RequestError,
    state: (filename = 'AdminTasksView.vue') => states.get(filename), text: () => all().map(node => node.text || '').join(' '),
    button: label => all().find(node => node.tag === 'button' && node.text === label),
    adminLink: () => all().find(node => node.tag === 'a' && node.props.href === '/admin/ai-tasks') }
}

test('ADMIN实际导航和路由读取固定分页，仅展示安全字段且无代执行入口', async t => {
  const reads = []
  const h = await mount(t, async (...args) => { reads.push(args); return page() })
  assert.ok(h.adminLink()); assert.equal(h.router.currentRoute.value.meta.requiresAuth, true)
  assert.equal(h.router.currentRoute.value.meta.requiresRole, 'ADMIN')
  assert.deepEqual(reads, [['/admin/ai-tasks?status=&page=1&pageSize=20']])
  for (const value of ['任务监控', '19', '8', '生成复习卡片', '已完成', '人工确认完成', '包含人工确认等待', '厂商请求次数']) assert.ok(h.text().includes(value), value)
  assert.equal(h.all().filter(node => node.tag === 'th').length, 13, '固定12字段加查看步骤')
  assert.equal(h.all().filter(node => node.tag === 'option').length, 7, '全部和六个任务状态')
  assert.equal(h.all().some(node => node.tag === 'a' && /^\/(materials|ai-tasks)\/\d/.test(node.props.href)), false)
  assert.equal(h.all().some(node => node.tag === 'button' && /取消|重试|执行|确认/.test(node.text || '')), false)
})

test('普通角色与未登录不请求管理API或显示导航', async t => {
  let reads = 0
  const h = await mount(t, async () => { reads++; return page() }, { session: identity('3', 'user-token', 'USER') })
  assert.equal(h.adminLink(), undefined); assert.equal(h.router.currentRoute.value.name, 'forbidden'); assert.match(h.text(), /无权限/)
  h.session.value = null; await h.router.push('/admin/ai-tasks'); await h.flush()
  assert.equal(h.router.currentRoute.value.name, 'login'); assert.equal(h.router.currentRoute.value.query.redirect, '/admin/ai-tasks'); assert.equal(reads, 0)
})

test('上海单日转换复用时区算法，包含夏令时；可选单边日期且没有90天上限', () => {
  const filename = path.join(__dirname, '../web/src/admin-tasks.ts')
  assert.ok(fs.existsSync(filename), '应提供任务日期与展示模块')
  const { adminTaskRange } = loadModule(filename)
  assert.deepEqual(adminTaskRange('', ''), {})
  assert.deepEqual(adminTaskRange('1991-07-01', ''), { from: '1991-06-30T15:00:00.000Z' })
  assert.deepEqual(adminTaskRange('', '2026-10-27'), { to: '2026-10-26T16:00:00.000Z' })
  assert.deepEqual(adminTaskRange('2025-01-01', '2026-10-27'), { from: '2024-12-31T16:00:00.000Z', to: '2026-10-26T16:00:00.000Z' })
  for (const [from, to] of [['2026-02-30', ''], ['2026-10-27', '2026-10-27'], ['2026-10-28', '2026-10-27'], ['0999-12-31', ''], ['1000-01-01', ''], ['', '10000-01-01']]) assert.throws(() => adminTaskRange(from, to), /日期|1000|9999/)
  assert.ok(adminTaskRange('1000-01-02', '9999-12-31').to.endsWith('Z'))
})

test('筛选规范日期边界并回首页；未提交草稿不混入分页和刷新，空白边界始终省略', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); return page([task()], Number(new URLSearchParams(url.split('?')[1]).get('page')), 41) })
  const s = h.state(); s.status = 'FAILED'; s.fromDate = '2025-01-01'; s.toDate = '2026-10-27'
  await s.changePage(2); await s.load()
  assert.equal(reads.at(-1), '/admin/ai-tasks?status=&page=2&pageSize=20')
  await s.applyFilters(); await h.flush()
  let query = new URLSearchParams(reads.at(-1).split('?')[1])
  assert.equal(query.get('status'), 'FAILED'); assert.equal(query.get('page'), '1'); assert.equal(query.get('pageSize'), '20')
  assert.equal(query.get('from'), '2024-12-31T16:00:00.000Z'); assert.equal(query.get('to'), '2026-10-26T16:00:00.000Z')
  s.fromDate = ''; await s.applyFilters(); query = new URLSearchParams(reads.at(-1).split('?')[1]); assert.equal(query.has('from'), false); assert.ok(query.has('to'))
  s.toDate = ''; await s.applyFilters(); query = new URLSearchParams(reads.at(-1).split('?')[1]); assert.equal(query.has('from'), false); assert.equal(query.has('to'), false)
  const count = reads.length; s.fromDate = '2026-02-30'; await s.applyFilters(); await h.flush(); assert.equal(reads.length, count); assert.match(h.text(), /有效.*日期/)
})

test('任务和步骤独立分页，切页、筛选和手动刷新清空选择与旧步骤', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); const p = Number(new URLSearchParams(url.split('?')[1]).get('page'))
    return url.includes('/steps?') ? page(p === 2 ? [] : [step()], p, 21) : page(p === 3 ? [] : [task()], p, 41) })
  const s = h.state(); await s.selectTask(s.results.items[0]); await h.flush()
  assert.equal(reads.at(-1), '/admin/ai-tasks/2/steps?page=1&pageSize=20'); assert.ok(s.steps)
  assert.match(h.text(), /0 毫秒/); assert.equal(h.all().filter(node => node.tag === 'th').length, 22, '12+操作列和9步骤列')
  await s.changeStepPage(2); await h.flush(); assert.equal(s.page, 1); assert.match(h.text(), /本页暂无步骤/)
  assert.equal(reads.at(-1), '/admin/ai-tasks/2/steps?page=2&pageSize=20')
  for (const action of [() => s.changePage(2), () => s.applyFilters(), () => s.load()]) {
    await action(); await h.flush(); assert.equal(s.selected, null); assert.equal(s.steps, null); assert.equal(s.stepPage, 1)
    await s.selectTask(s.results.items[0]); await h.flush()
  }
  await s.changePage(3); await h.flush(); assert.match(h.text(), /本页暂无任务/); assert.equal(h.button('上一页').props.disabled, false)
})

test('真正空页、读取中和失败区分，刷新只由用户触发且加载拒绝重复请求', async t => {
  const pending = deferred(); let mode = 'pending', reads = 0
  const h = await mount(t, async () => { reads++; if (mode === 'pending') return pending.promise; if (mode === 'fail') throw new Error('任务服务不可用'); return page([]) })
  const s = h.state(); assert.match(h.text(), /正在读取任务/); assert.equal(h.button('刷新').props.disabled, true)
  await s.load(); await s.applyFilters(); await s.changePage(2); assert.equal(reads, 1)
  pending.resolve(page([])); await h.flush(); assert.match(h.text(), /暂无任务/)
  mode = 'fail'; await s.load(); await h.flush(); assert.equal(s.results, null); assert.match(h.text(), /任务服务不可用/); assert.doesNotMatch(h.text(), /暂无任务/)
  mode = 'empty'; await s.load(); await h.flush(); assert.match(h.text(), /暂无任务/); assert.equal(reads, 3)
})

test('空步骤和步骤错误清空旧数据，可手动重新读取，保持列表和选中任务', async t => {
  let mode = 'empty', reads = 0
  const h = await mount(t, async url => { reads++; if (url.includes('/steps?')) { if (mode === 'fail') throw new Error('步骤服务不可用'); return page(mode === 'empty' ? [] : [step()]) }; return page() })
  const s = h.state(); await s.selectTask(s.results.items[0]); await h.flush(); assert.match(h.text(), /暂无步骤记录/)
  mode = 'ok'; await s.loadSteps(); await h.flush(); assert.ok(s.steps.items.length)
  mode = 'fail'; await s.loadSteps(); await h.flush(); assert.equal(s.steps, null); assert.match(h.text(), /步骤服务不可用/); assert.doesNotMatch(h.text(), /暂无步骤记录/)
  assert.ok(s.results); assert.equal(s.selected.id, '2'); assert.equal(reads, 4)
})

test('未知枚举不显示原始自由字符串，缺失和运行中与无效用时区分，0毫秒保留', async t => {
  const canary = '<img src=x onerror=alert(1)>'
  const h = await mount(t, async url => url.includes('/steps?') ? page([step({ toolName: canary, providerKind: canary, status: canary, errorCode: canary })]) :
    page([task('2', { goalType: canary, status: canary, stage: canary, errorCode: canary, startedAt: null, finishedAt: null, durationMs: null }),
      task('3', { status: 'RUNNING', startedAt: '2026-10-26T01:00:00Z', finishedAt: null, durationMs: null }), task('4', { durationMs: null }), task('5', { durationMs: 0 })]))
  assert.doesNotMatch(h.text(), /<img|onerror|NaN/); assert.match(h.text(), /未知/); assert.match(h.text(), /未开始/); assert.match(h.text(), /尚未结束/); assert.match(h.text(), /用时未知/); assert.match(h.text(), /0 毫秒/); assert.match(h.text(), /未记录/)
  await h.state().selectTask(h.state().results.items[0]); await h.flush()
  assert.doesNotMatch(h.text(), /<img|onerror/); assert.equal(h.all().some(node => node.tag === 'img'), false)
})

test('任务及步骤显示白名单错误码和说明，未知错误只显示固定标记', async t => {
  const codes = ['TASK_TIMEOUT', 'LEASE_RECOVERED', 'MODEL_TIMEOUT', 'UNRECOGNIZED_ERROR', '<img src=x onerror=private_canary>']
  const h = await mount(t, async url => url.includes('/steps?') ? page(codes.map((errorCode, index) => step({ stepNo: index + 1, errorCode }))) :
    page(codes.map((errorCode, index) => task(String(index + 2), { errorCode }))))
  for (const code of codes.slice(0, 3)) assert.equal(h.all().filter(node => node.tag === 'td' && node.text?.startsWith(`${code} · `)).length, 1, code)
  assert.ok(h.text().includes('TASK_TIMEOUT · 执行异常')); assert.ok(h.text().includes('LEASE_RECOVERED · 执行异常'))
  assert.ok(h.text().includes('MODEL_TIMEOUT · 模型请求超时'))
  assert.ok(h.text().includes('UNRECOGNIZED_ERROR · 未识别错误')); assert.doesNotMatch(h.text(), /private_canary|<img/)
  await h.state().selectTask(h.state().results.items[0]); await h.flush()
  for (const code of codes.slice(0, 3)) assert.equal(h.all().filter(node => node.tag === 'td' && node.text?.startsWith(`${code} · `)).length, 2, `${code}列表与步骤均可见`)
  assert.equal(h.all().filter(node => node.tag === 'th' && node.text === '错误码及说明').length, 2)
  assert.doesNotMatch(h.text(), /private_canary|<img/); assert.equal(h.all().some(node => node.tag === 'img'), false)
})

test('前端错误展示完整覆盖后端安全错误码枚举，任意自由字符串不穿透', () => {
  const { adminErrorLabel } = loadModule(path.join(__dirname, '../web/src/admin-tasks.ts'))
  const backend = fs.readFileSync(path.join(__dirname, '../backend/src/main/java/com/learningmaterial/system/service/impl/AdminTaskServiceImpl.java'), 'utf8')
  const allowed = backend.match(/ERROR_CODES = Set\.of\(([\s\S]*?)\);/)[1].match(/"[A-Z_]+"/g).map(value => JSON.parse(value))
  assert.ok(allowed.length > 80)
  for (const code of allowed) assert.ok(adminErrorLabel(code).startsWith(`${code} · `), code)
  for (const unknown of ['UNRECOGNIZED_ERROR', 'AGENT_PRIVATE_CANARY', 'MODEL_PRIVATE_CANARY', 'SOURCE_PRIVATE_CANARY', 'constructor', 'toString']) assert.equal(adminErrorLabel(unknown), 'UNRECOGNIZED_ERROR · 未识别错误')
  assert.equal(adminErrorLabel(null), '无错误')
})

test('管理员阶段标签覆盖后端现有阶段，受限来源明确展示而非未知', () => {
  const { adminStageLabel } = loadModule(path.join(__dirname, '../web/src/admin-tasks.ts'))
  const backend = fs.readFileSync(path.join(__dirname, '../backend/src/main/java/com/learningmaterial/system/service/impl/AdminTaskServiceImpl.java'), 'utf8')
  const stages = backend.match(/STAGES = Set\.of\(([\s\S]*?)\);/)[1].match(/"[A-Z_]+"/g).map(value => JSON.parse(value))
  assert.equal(adminStageLabel('SOURCE_RESTRICTED'), '原资料受限')
  for (const stage of stages) assert.notEqual(adminStageLabel(stage), '未知', stage)
  assert.equal(adminStageLabel('PRIVATE_STAGE_CANARY'), '未知'); assert.equal(adminStageLabel(null), '未记录')
})

for (const status of [401, 403]) test(`当前列表${status}清空数据并导航到鉴权页面`, async t => {
  let h, fail = false
  h = await mount(t, async () => { if (fail) throw new h.RequestError(status, '权限失效'); return page() })
  const s = h.state(); fail = true; await s.load(); await h.flush()
  assert.equal(s.results, null); assert.equal(s.selected, null); assert.equal(h.router.currentRoute.value.name, status === 401 ? 'login' : 'forbidden'); assert.equal(Boolean(h.session.value), status !== 401)
})

for (const status of [401, 403]) test(`当前步骤${status}撤下列表与步骤并导航`, async t => {
  let h
  h = await mount(t, async url => { if (url.includes('/steps?')) throw new h.RequestError(status, '权限失效'); return page() })
  const s = h.state(); await s.selectTask(s.results.items[0]); await h.flush()
  assert.equal(s.results, null); assert.equal(s.steps, null); assert.equal(s.selected, null); assert.equal(h.router.currentRoute.value.name, status === 401 ? 'login' : 'forbidden')
})

for (const outcome of ['success', 401, 403]) test(`切换任务后步骤迟到${outcome}不能覆盖新选择或退出会话`, async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async url => url.includes('/steps?') ? ++reads === 1 ? pending.promise : page([step({ stepNo: 9 })]) : page([task(), task('3')]))
  const s = h.state(), selecting = s.selectTask(s.results.items[0]); await h.flush()
  assert.match(h.text(), /正在读取步骤/); await s.selectTask(s.results.items[1]); await h.flush()
  if (outcome === 'success') pending.resolve(page([step({ stepNo: 77 })])); else pending.reject(new h.RequestError(outcome, '旧步骤权限失败'))
  await selecting; await h.flush(); assert.equal(s.selected.id, '3'); assert.equal(s.steps.items[0].stepNo, 9)
  assert.equal(h.session.value.accessToken, 'admin-token'); assert.equal(h.router.currentRoute.value.path, '/admin/ai-tasks')
})

for (const action of ['load', 'applyFilters', 'changePage']) for (const outcome of ['success', 401, 403]) test(`${action}清除选择后步骤迟到${outcome}不可恢复或导航`, async t => {
  const pending = deferred()
  const h = await mount(t, async url => url.includes('/steps?') ? pending.promise : page([task()], 1, 41))
  const s = h.state(), selecting = s.selectTask(s.results.items[0]); await h.flush()
  await s[action](2); await h.flush()
  assert.equal(s.selected, null); assert.equal(s.steps, null); assert.equal(s.stepsLoading, false)
  if (outcome === 'success') pending.resolve(page([step()])); else pending.reject(new h.RequestError(outcome, '旧步骤失败'))
  await selecting; await h.flush(); assert.equal(s.selected, null); assert.equal(s.steps, null); assert.equal(s.stepsError, '')
  assert.ok(s.results); assert.equal(h.session.value.accessToken, 'admin-token'); assert.equal(h.router.currentRoute.value.path, '/admin/ai-tasks')
})

for (const transition of ['account', 'token', 'role', 'route', 'leave']) for (const outcome of ['success', 401, 403]) test(`${transition}变化后迟到步骤${outcome}不污染当前会话`, async t => {
  const pending = deferred()
  const h = await mount(t, async url => url.includes('/steps?') ? pending.promise : page())
  const old = h.state(), selecting = old.selectTask(old.results.items[0]); await h.flush()
  if (transition === 'account') h.session.value = identity('4', 'next-token')
  if (transition === 'token') h.session.value = identity('1', 'rotated-token')
  if (transition === 'role') h.session.value.user.role = 'USER'
  if (transition === 'route') await h.router.push('/admin/ai-tasks?source=menu')
  if (transition === 'leave') await h.router.push('/cards')
  await h.flush(); const currentPath = h.router.currentRoute.value.fullPath, token = h.session.value.accessToken
  assert.equal(old.results, null); assert.equal(old.steps, null); assert.equal(old.selected, null)
  if (outcome === 'success') pending.resolve(page([step({ stepNo: 77 })])); else pending.reject(new h.RequestError(outcome, '旧步骤失败'))
  await selecting; await h.flush(); assert.equal(old.steps, null); assert.equal(h.session.value.accessToken, token); assert.equal(h.router.currentRoute.value.fullPath, currentPath)
  if (transition === 'role') { assert.equal(h.adminLink(), undefined); assert.match(h.text(), /无权限/) }
})

for (const transition of ['account', 'token', 'role', 'route', 'leave']) for (const outcome of ['success', 401, 403]) test(`${transition}变化后迟到列表${outcome}不污染当前会话`, async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async () => ++reads === 1 ? pending.promise : page([task('3')]))
  const old = h.state()
  if (transition === 'account') h.session.value = identity('4', 'next-token')
  if (transition === 'token') h.session.value = identity('1', 'rotated-token')
  if (transition === 'role') h.session.value.user.role = 'USER'
  if (transition === 'route') await h.router.push('/admin/ai-tasks?source=menu')
  if (transition === 'leave') await h.router.push('/cards')
  await h.flush(); const currentPath = h.router.currentRoute.value.fullPath, token = h.session.value.accessToken
  if (outcome === 'success') pending.resolve(page([task('99')])); else pending.reject(new h.RequestError(outcome, '旧列表失败'))
  await h.flush(); assert.equal(old.results, null); assert.equal(h.session.value.accessToken, token); assert.equal(h.router.currentRoute.value.fullPath, currentPath)
  if (transition === 'account' || transition === 'token' || transition === 'route') assert.equal(h.state().results.items[0].id, '3')
})
