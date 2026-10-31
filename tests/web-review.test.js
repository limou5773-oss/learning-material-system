const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const user = (id = '2', status = 'ENABLED', role = 'USER') => ({ id, username: `account_${id}`,
  displayName: `用户 ${id}`, role, status, hasWechatBinding: false, createdAt: '2026-10-26T01:00:00Z' })
const identity = (id = '1', token = 'admin-token', role = 'ADMIN') => ({ user: user(id, 'ENABLED', role), accessToken: token })
const page = (items = [user()], number = 1, total = items.length) => ({ items, page: number, pageSize: 20, total })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

async function mount(t, request, options = {}) {
  const vue = require('../web/node_modules/vue'), vueRouter = require('../web/node_modules/vue-router')
  const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
  const ts = require('../web/node_modules/typescript'), cache = new Map(), states = new Map(), prompts = [], events = new Map()
  const previous = { window: global.window, Document: global.Document, ShadowRoot: global.ShadowRoot }
  global.Document = class {}; global.ShadowRoot = class {}
  let consent = true, app
  global.window = { confirm(message) { prompts.push(message); return consent },
    addEventListener(name, fn) { events.set(name, fn) }, removeEventListener(name, fn) { if (events.get(name) === fn) events.delete(name) } }
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
      if (specifier.endsWith('/api')) return { api: request, ApiRequestError: RequestError, reviewFileBlob: options.file || (async () => new Blob(['file'])), materialFileBlob: async () => new Blob(['owner']) }
      if (specifier.endsWith('.vue') && !/AdminReviews|OwnerMaterialReview|MaterialDetail|ForbiddenView|App.vue|Layout|UiIcon/.test(specifier)) return { render: () => vue.h('div') }
      if (specifier.startsWith('.')) {
        const target = path.resolve(path.dirname(filename), specifier)
        return load(target.endsWith('.vue') ? target : `${target}.ts`)
      }
      throw new Error(`未预期的用户管理依赖：${specifier}`)
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
  await router.push(options.path || '/admin/reviews'); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 32; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { router, all, flush, session, RequestError, prompts, events, consent(value) { consent = value },
    state: (filename = 'AdminReviewsView.vue') => states.get(filename), text: () => all().map(node => node.text || '').join(' '),
    button: label => all().find(node => node.tag === 'button' && node.text === label),
    adminLink: () => all().find(node => node.tag === 'a' && node.props.href === '/admin/reviews') }
}

const review = (id = '8', overrides = {}) => ({ id, materialId: '42', materialVersion: 3, ownerId: '2',
  title: '<script>资料</script>', type: 'NOTE', accessScope: 'MATERIAL_CONTENT', triggerType: 'USER_SUBMITTED',
  status: 'PENDING', version: 1, reason: '请审核当前内容', decisionReason: null, isCurrent: true,
  createdAt: '2026-10-26T01:00:00Z', ...overrides })
const material = (overrides = {}) => ({ id: '42', type: 'NOTE', title: '资料', version: 3,
  accessState: 'NORMAL', status: 'ACTIVE', tags: [], noteText: '<b>受控正文</b>', ...overrides })
const content = (overrides = {}) => ({ reviewId: '8', material: material(), fileAccessPath: null, auditId: '9', ...overrides })

test('审核队列ADMIN入口默认只读PENDING元数据，普通账号禁止访问', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); return page([review()]) })
  assert.ok(h.adminLink(), 'ADMIN应显示内容审核入口')
  assert.equal(h.router.currentRoute.value.meta.requiresRole, 'ADMIN')
  assert.deepEqual(reads, ['/admin/reviews?status=PENDING&page=1&pageSize=20'])
  assert.match(h.text(), /<script>资料<\/script>/)
  assert.doesNotMatch(h.text(), /受控正文/)
  h.session.value = identity('3', 'user', 'USER'); await h.flush()
  assert.equal(h.adminLink(), undefined); assert.equal(h.router.currentRoute.value.name, 'forbidden')
  assert.equal(reads.length, 1)
})

test('已失效审核可筛选并展示失效时间，不能再次读取正文或处置', async t => {
  const reads = []
  const item = review('8', { status: 'SUPERSEDED', isCurrent: false, supersededAt: '2026-10-30T12:00:00Z' })
  const h = await mount(t, async url => { reads.push(url); return page([item]) })
  assert.ok(h.all().some(n => n.tag === 'option' && n.props.value === 'SUPERSEDED'))
  const state = h.state(); state.status = 'SUPERSEDED'; await state.applyFilters(); await h.flush()
  assert.match(reads.at(-1), /status=SUPERSEDED/)
  state.selectReview(state.results.items[0]); await h.flush()
  assert.match(h.text(), /已失效，需重新送审/); assert.match(h.text(), /失效时间/)
  assert.equal(state.actionable, false); assert.equal(h.button('记录理由并查看正文'), undefined)
  await state.viewContent(); assert.equal(reads.length, 2)
})

test('先理由POST查看正文，原文件需另行鉴权读取；未查看和旧版本不能处置', async t => {
  const calls = [], files = []
  const h = await mount(t, async (url, method = 'GET', body) => {
    calls.push({ url, method, body }); return method === 'POST' ? content({ material: material({ type: 'PDF' }), fileAccessPath: '/api/v1/admin/reviews/8/file' }) : page([review(), review('9', { isCurrent: false })])
  }, { file: async (...args) => { files.push(args); return new Blob(['pdf']) } })
  const s = h.state(); await s.selectReview(s.results.items[0]); s.reason = '依据规则'; await s.submit()
  assert.equal(calls.length, 1)
  s.accessReason = '  核实当前版本  '; await s.viewContent(); await h.flush()
  assert.deepEqual(calls[1], { url: '/admin/reviews/8/content-accesses', method: 'POST', body: { reason: '核实当前版本' } })
  assert.match(h.text(), /<b>受控正文<\/b>/); assert.equal(h.all().some(n => n.tag === 'b'), false)
  await s.openFile(); assert.deepEqual(files, [['8', '/api/v1/admin/reviews/8/file']])
  await s.selectReview(s.results.items[1]); assert.equal(s.content, null); assert.equal(s.fileUrl, '')
  s.accessReason = '查看旧版本'; await s.viewContent(); s.reason = '决定'; await s.submit(); assert.equal(calls.length, 2)
})

test('处置必须原因和确认且只提交一次；写成功后列表失败分别提示', async t => {
  const pending = deferred(); let writes = 0, reads = 0, body
  const h = await mount(t, async (url, method = 'GET', data) => {
    if (method === 'POST') return content()
    if (method === 'PUT') { writes++; body = data; return pending.promise }
    if (++reads > 1) throw new Error('队列刷新失败'); return page([review()])
  })
  const s = h.state(); s.selectReview(s.results.items[0]); s.accessReason = '核实'; await s.viewContent()
  s.reason = '  内容不符规则  '; s.decision = 'REJECTED'; h.consent(false); await s.submit(); assert.equal(writes, 0)
  h.consent(true); const saving = s.submit(); await h.flush(); await s.submit(); await s.applyFilters(); await h.router.push('/cards')
  assert.equal(writes, 1); assert.equal(h.router.currentRoute.value.path, '/admin/reviews')
  assert.deepEqual(body, { version: 1, decision: 'REJECTED', reason: '内容不符规则' })
  pending.resolve(review('8', { status: 'REJECTED', version: 2 })); await saving; await h.flush()
  assert.equal(s.content, null); assert.equal(s.selected, null); assert.equal(s.reason, '')
  assert.match(h.text(), /处置已保存/); assert.match(h.text(), /队列刷新失败/); assert.equal(s.actionError, '')
})

test('409保留理由并撤掉正文授权，刷新或切换需确认，失败与空队列分开', async t => {
  let h, failing = false
  h = await mount(t, async (url, method = 'GET') => {
    if (method === 'POST') return content()
    if (method === 'PUT') throw new h.RequestError(409, '送审版本已变化，请刷新')
    if (failing) throw new Error('读取失败'); return page([review()])
  })
  const s = h.state(); s.selectReview(s.results.items[0]); s.accessReason = '查看'; await s.viewContent(); s.reason = '保留的决定原因'
  await s.submit(); assert.equal(s.reason, '保留的决定原因'); assert.equal(s.content, null); assert.match(s.actionError, /刷新/)
  h.consent(false); s.status = ''; await s.applyFilters(); assert.equal(s.selected.id, '8')
  h.consent(true); failing = true; await s.load(); await h.flush(); assert.equal(s.selected, null); assert.equal(s.reason, '')
  assert.match(h.text(), /读取失败/); assert.doesNotMatch(h.text(), /暂无审核/)
})

for (const response of ['success', '401']) test(`受控正文在token角色路由变化后清空，旧${response}不能恢复或退出新会话`, async t => {
    const pending = deferred()
    const h = await mount(t, async (url, method = 'GET') => method === 'POST' ? pending.promise : page([review()]))
    const old = h.state(); old.selectReview(old.results.items[0]); old.accessReason = '旧账号查看'; const reading = old.viewContent()
    h.session.value = identity('4', `new-${response}`); await h.flush()
    if (response === 'success') pending.resolve(content()); else pending.reject(new h.RequestError(401, '旧401'))
    await reading; await h.flush(); assert.equal(old.content, null); assert.equal(old.accessReason, '')
    assert.equal(h.session.value.accessToken, `new-${response}`); assert.equal(h.router.currentRoute.value.path, '/admin/reviews')
    const s = h.state(); s.selectReview(s.results.items[0]); await h.router.push('/admin/reviews?source=menu'); await h.flush()
    assert.equal(s.selected, null); assert.equal(s.results, null)
})

test('本人送审绑定展示version并说明授权范围，409保留草稿，restricted禁AI且保留正文', async t => {
  let h, writes = [], conflict = true, version = 3
  h = await mount(t, async (url, method = 'GET', body) => {
    if (method === 'POST') { writes.push(body); if (conflict) throw new h.RequestError(409, '请刷新资料版本'); return review() }
    if (url.includes('review-requests')) return page([])
    return material({ accessState: 'RESTRICTED', version })
  }, { path: '/materials/42', session: identity('2', 'owner', 'USER') })
  const s = h.state('OwnerMaterialReview.vue'); assert.ok(s, '资料详情应挂载本人审核组件')
  assert.match(h.text(), /正文和原文件/); assert.match(h.text(), /修正后重新送审/); assert.match(h.text(), /受控正文/)
  assert.equal(h.all().some(n => n.tag === 'a' && n.props.href === '/materials/42/organize'), false)
  s.reason = '  请求解除限制  '; await s.submit(); assert.deepEqual(writes, [{ version: 3, reason: '请求解除限制' }]); assert.equal(s.reason, '  请求解除限制  ')
  version = 4; await s.refreshMaterial(); await h.flush(); assert.equal(s.reason, '  请求解除限制  ')
  conflict = false; await h.state('OwnerMaterialReview.vue').submit(); await h.flush(); assert.equal(writes.at(-1).version, 4)
})

test('本人翻阅旧历史仍记得当前版本已送审，不允许从第二页重复提交', async t => {
  let writes = 0
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'POST') { writes++; return review() }
    if (url.includes('review-requests')) return url.includes('page=2') ? page([review('1', { materialVersion: 1 })], 2, 21) : page([review()], 1, 21)
    return material()
  }, { path: '/materials/42', session: identity('2', 'owner', 'USER') })
  const s = h.state('OwnerMaterialReview.vue'); assert.equal(s.alreadySubmitted, true)
  await s.changePage(2); await h.flush(); s.reason = '不能重复送审'; await s.submit(); assert.equal(writes, 0)
  assert.equal(s.alreadySubmitted, true)
})

for (const status of [401, 403]) test(`当前审核${status}清除正文与预览并离开审核页`, async t => {
  let h, fail = false
  h = await mount(t, async (url, method = 'GET') => {
    if (fail) throw new h.RequestError(status, '当前权限失败')
    return method === 'POST' ? content() : page([review()])
  })
  const s = h.state(); s.selectReview(s.results.items[0]); s.accessReason = '核实'; await s.viewContent(); assert.ok(s.content)
  fail = true; s.accessReason = '再次查看'; await s.viewContent(); await h.flush()
  assert.equal(s.content, null); assert.equal(s.fileUrl, ''); assert.equal(s.reason, '')
  assert.equal(h.router.currentRoute.value.name, status === 401 ? 'login' : 'forbidden')
  assert.equal(Boolean(h.session.value), status !== 401)
})

test('审核文件传输使用固定同源白名单路径、Bearer和no-store，拒绝外站与其他review', async t => {
  const ts = require('../web/node_modules/typescript'), calls = [], oldFetch = global.fetch
  t.after(() => { global.fetch = oldFetch })
  global.fetch = async (...args) => { calls.push(args); return { ok: true, blob: async () => new Blob(['pdf']) } }
  const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../web/src/api.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }; new Function('require', 'module', 'exports', source)(() => ({ session: { value: identity() } }), module, module.exports)
  await module.exports.reviewFileBlob('8', '/api/v1/admin/reviews/8/file')
  assert.deepEqual(calls, [['/api/v1/admin/reviews/8/file', { headers: { Authorization: 'Bearer admin-token' }, cache: 'no-store' }]])
  for (const path of ['https://evil.example/file', '//evil.example/file', '/api/v1/admin/reviews/9/file', '/api/v1/admin/reviews/8/file?token=secret']) await assert.rejects(module.exports.reviewFileBlob('8', path), /路径无效/)
  assert.equal(calls.length, 1)
})

test('筛选和刷新撤销已显示的正文与ObjectURL，分页始终使用已应用的状态', async t => {
  const reads = [], revoked = [], original = URL.revokeObjectURL
  URL.revokeObjectURL = value => { revoked.push(value); original(value) }
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'POST') return content({ fileAccessPath: '/api/v1/admin/reviews/8/file' })
    reads.push(url); return page([review()], Number(new URLSearchParams(url.split('?')[1]).get('page')), 41)
  })
  t.after(() => { URL.revokeObjectURL = original })
  const s = h.state(); s.selectReview(s.results.items[0]); s.accessReason = '查看'; await s.viewContent(); await s.openFile(); const url = s.fileUrl
  assert.ok(url.startsWith('blob:')); s.status = 'REJECTED'; await s.changePage(2)
  assert.equal(s.content, null); assert.equal(s.fileUrl, ''); assert.ok(revoked.includes(url)); assert.match(reads.at(-1), /status=PENDING&page=2/)
  await s.applyFilters(); assert.equal(reads.at(-1), '/admin/reviews?status=REJECTED&page=1&pageSize=20')
})

test('审核原文件迟到成功不能在新会话创建预览', async t => {
  const pending = deferred()
  const h = await mount(t, async (url, method = 'GET') => method === 'POST' ? content({ fileAccessPath: '/api/v1/admin/reviews/8/file' }) : page([review()]), { file: () => pending.promise })
  const old = h.state(); old.selectReview(old.results.items[0]); old.accessReason = '查看'; await old.viewContent(); const opening = old.openFile()
  h.session.value = identity('4', 'next-file-session'); await h.flush(); pending.resolve(new Blob(['old private file'])); await opening
  assert.equal(old.fileUrl, ''); assert.equal(old.content, null); assert.equal(h.state().fileUrl, '')
})
