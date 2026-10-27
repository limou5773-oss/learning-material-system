const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const notification = (id = '1', isRead = false, taskId = '91') => ({ id, type: 'RESULT_READY',
  content: '整理结果已生成，请查看并确认。', target: { type: 'AI_TASK', taskId }, isRead,
  createdAt: '2026-10-26T01:00:00Z', channelStatus: 'NOT_REQUESTED' })
const page = (items = [notification()], number = 1, total = items.length) => ({ items, page: number, pageSize: 20, total })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

test('审核通知仅按MATERIAL与两个正BIGINT字符串跳本人资料，并先标已读', async t => {
  const { notificationTaskPath } = await import('../web/src/notifications.ts')
  const target = { type: 'MATERIAL', materialId: '42', reviewId: '9223372036854775807', url: 'https://evil.example' }
  assert.equal(notificationTaskPath(target), '/materials/42')
  for (const invalid of [{ ...target, materialId: '01' }, { ...target, reviewId: 8 }, { ...target, reviewId: '9223372036854775808' }]) assert.equal(notificationTaskPath(invalid), null)
  const item = { ...notification(), type: 'REVIEW_DECIDED', target }; const calls = []
  const h = await mount(t, async (url, method = 'GET', body) => { calls.push({ url, method, body }); return method === 'PUT' ? { ...item, isRead: true } : page([item]) })
  assert.match(h.text(), /审核结果/); assert.ok(h.button('查看资料')); await h.state().openTask(h.state().results.items[0]); await h.flush()
  assert.deepEqual(calls.at(-1), { url: '/notifications/1/read-state', method: 'PUT', body: { isRead: true } })
  assert.equal(h.router.currentRoute.value.path, '/materials/42')
})

async function mount(t, request, initialPath = '/notifications') {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/views/NotificationsView.vue')), '应实现通知页面')
  const vue = require('../web/node_modules/vue'), vueRouter = require('../web/node_modules/vue-router')
  const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
  const ts = require('../web/node_modules/typescript'), cache = new Map(), states = new Map()
  const previous = { window: global.window, Document: global.Document, ShadowRoot: global.ShadowRoot }
  global.Document = class {}; global.ShadowRoot = class {}; global.window = {}
  let app
  const session = vue.ref({ user: { id: 'alice' }, accessToken: 'alice-token' })
  t.after(() => { app?.unmount(); Object.assign(global, previous) })
  class RequestError extends Error { constructor(status, message) { super(message); this.status = status } }
  function load(filename) {
    if (cache.has(filename)) return cache.get(filename)
    let source = fs.readFileSync(filename, 'utf8'), template
    if (filename.endsWith('.vue')) {
      const descriptor = parse(source).descriptor, script = compileScript(descriptor, { id: filename })
      source = script.content
      template = compileTemplate({ source: descriptor.template.content, filename, id: filename,
        compilerOptions: { bindingMetadata: script.bindings } }).code
    }
    const compile = value => ts.transpileModule(value, { compilerOptions: { module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
    const module = { exports: {} }
    new Function('require', 'module', 'exports', compile(source))(specifier => {
      if (specifier === 'vue') return vue
      if (specifier === 'vue-router') return { ...vueRouter, createWebHistory: vueRouter.createMemoryHistory }
      if (specifier.endsWith('/auth')) return { session, clearSession() { session.value = null } }
      if (specifier.endsWith('/api')) return { api: request, ApiRequestError: RequestError }
      if (specifier.endsWith('.vue') && !/Notifications|App.vue/.test(specifier)) return { render: () => vue.h('div') }
      if (specifier.startsWith('.')) {
        const target = path.resolve(path.dirname(filename), specifier)
        return load(target.endsWith('.vue') ? target : `${target}.ts`)
      }
      throw new Error(`未预期的通知依赖：${specifier}`)
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
  await router.push(initialPath); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { router, all, flush, session, RequestError, state: () => states.get('NotificationsView.vue'),
    text: () => all().map(node => node.text || '').join(' '),
    button: label => all().find(node => node.tag === 'button' && node.text === label) }
}

test('任务入口只接受AI_TASK白名单和正BIGINT字符串，忽略任意URL', async () => {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/notifications.ts')), '应实现通知契约与安全任务入口')
  const { notificationTaskPath } = await import('../web/src/notifications.ts')
  assert.equal(notificationTaskPath({ type: 'AI_TASK', taskId: '9223372036854775807', url: 'https://evil.example' }), '/ai-tasks/9223372036854775807')
  for (const target of [null, {}, { type: 'URL', taskId: '1' }, ...['0', '-1', '01', '1/confirm', '1e2', '9223372036854775808', 1].map(taskId => ({ type: 'AI_TASK', taskId }))]) {
    assert.equal(notificationTaskPath(target), null)
  }
})

test('实际路由和导航入口展示通知，分页与未读筛选发送固定查询并重置首页', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); const query = new URLSearchParams(url.split('?')[1]); return page([notification(query.get('page'))], Number(query.get('page')), 41) })
  assert.ok(h.all().some(node => node.tag === 'a' && node.props.href === '/notifications' && node.children.some(child => child.text === '通知')))
  assert.equal(h.router.currentRoute.value.meta.requiresAuth, true)
  assert.equal(reads[0], '/notifications?unreadOnly=false&page=1&pageSize=20')
  assert.match(h.text(), /微信订阅消息尚未开通，站内通知正常/)
  await h.state().changePage(2); await h.flush()
  assert.equal(reads.at(-1), '/notifications?unreadOnly=false&page=2&pageSize=20')
  await h.state().changeFilter(true); await h.flush()
  assert.equal(reads.at(-1), '/notifications?unreadOnly=true&page=1&pageSize=20')
  assert.equal(h.state().page, 1)
})

test('未读列表写成功后重读服务端总数，高页变空仍显示上一页且不冒充全空', async t => {
  let removed = false
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PUT') { removed = true; return notification('21', true) }
    const query = new URLSearchParams(url.split('?')[1]), p = Number(query.get('page'))
    return page(removed && p === 2 ? [] : [notification('21')], p, removed ? 0 : 21)
  })
  await h.state().changeFilter(true); await h.state().changePage(2)
  await h.state().setRead(h.state().results.items[0], true); await h.flush()
  assert.equal(h.state().results.total, 0)
  assert.match(h.text(), /本页暂无通知/); assert.doesNotMatch(h.text(), /暂无未读通知/)
  assert.equal(h.button('上一页').props.disabled, false)
  await h.state().changePage(1); await h.flush(); assert.equal(h.state().page, 1)
})

test('已读与未读切换严格PUT布尔并采用200返回视图，正文按文本展示', async t => {
  const writes = []; let item = { ...notification(), content: '<strong>固定通知正文</strong>' }
  const h = await mount(t, async (url, method = 'GET', body) => {
    if (method === 'PUT') { writes.push({ url, body }); item = { ...item, isRead: body.isRead }; return item }
    return page([item])
  })
  assert.match(h.text(), /<strong>固定通知正文<\/strong>/)
  assert.equal(h.all().some(node => node.tag === 'strong' && node.text === '固定通知正文'), false)
  await h.state().setRead(h.state().results.items[0], true)
  assert.equal(h.state().results.items[0].isRead, true)
  await h.state().setRead(h.state().results.items[0], false); await h.flush()
  assert.deepEqual(writes, [{ url: '/notifications/1/read-state', body: { isRead: true } }, { url: '/notifications/1/read-state', body: { isRead: false } }])
  assert.equal(h.state().results.items[0].isRead, false)
})

test('查看任务必须等待已读写成功才跳详情，在途锁定并拒绝重复操作', async t => {
  const saving = deferred(); let puts = 0
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PUT') { puts++; return saving.promise }
    return page([notification('1', false, '9223372036854775807')], 1, 21)
  })
  const view = h.state(), opening = view.openTask(view.results.items[0]); await h.flush()
  assert.equal(h.router.currentRoute.value.path, '/notifications')
  const controls = h.all().filter(node => ['button', 'select', 'input'].includes(node.tag))
  assert.ok(controls.length >= 5); assert.ok(controls.every(node => node.props.disabled === true))
  await view.openTask(view.results.items[0]); await view.setRead(view.results.items[0], false)
  await view.changePage(2); await view.changeFilter(true)
  assert.equal(puts, 1); assert.equal(view.page, 1); assert.equal(view.unreadOnly, false)
  saving.resolve(notification('1', true, '9223372036854775807')); await opening; await h.flush()
  assert.equal(h.router.currentRoute.value.path, '/ai-tasks/9223372036854775807')
})

test('已读写失败保留当前页并提示，不跳转且不重读伪造成功', async t => {
  let reads = 0
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PUT') throw new Error('读状态保存失败')
    reads++; return page()
  })
  await h.state().openTask(h.state().results.items[0]); await h.flush()
  assert.equal(reads, 1); assert.equal(h.router.currentRoute.value.path, '/notifications')
  assert.match(h.text(), /读状态保存失败/); assert.equal(h.state().results.items[0].isRead, false)
})

test('写成功后列表重读失败独立提示，不能误报读状态写失败', async t => {
  let written = false
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PUT') { written = true; return notification('1', true) }
    if (written) throw new Error('通知重读服务不可用')
    return page()
  })
  await h.state().setRead(h.state().results.items[0], true); await h.flush()
  assert.match(h.text(), /已标记为已读/); assert.match(h.text(), /通知重读服务不可用/)
  assert.equal(h.state().actionError, ''); assert.doesNotMatch(h.text(), /暂无通知|暂无未读通知/)
})

test('加载、真正空集合和读取失败区分，失败可显式重试', async t => {
  const pending = deferred(); let response = 'pending'
  const h = await mount(t, async () => {
    if (response === 'pending') return pending.promise
    if (response === 'error') throw new Error('通知服务暂不可用')
    return page([])
  })
  assert.match(h.text(), /正在读取通知/); assert.doesNotMatch(h.text(), /暂无通知/)
  assert.equal(h.button('刷新').props.disabled, true)
  pending.resolve(page([])); await h.flush(); assert.match(h.text(), /暂无通知/)
  response = 'error'; await h.state().load(); await h.flush()
  assert.match(h.text(), /通知服务暂不可用/); assert.doesNotMatch(h.text(), /暂无通知/)
  response = 'empty'; await h.state().load(); await h.flush(); assert.match(h.text(), /暂无通知/)
})

test('非法target禁用查看任务且程序调用不能发起写入或导航', async t => {
  let writes = 0
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PUT') writes++
    return page([null, { type: 'URL', url: 'https://evil.example', taskId: '91' }, { type: 'AI_TASK', taskId: '0' }]
      .map((target, index) => ({ ...notification(String(index + 1)), target })))
  })
  assert.equal(h.button('查看任务').props.disabled, true)
  await h.state().openTask(h.state().results.items[0]); await h.flush()
  assert.equal(writes, 0); assert.equal(h.router.currentRoute.value.path, '/notifications')
  assert.equal(h.all().filter(node => node.tag === 'button' && node.text === '查看任务' && node.props.disabled === true).length, 3)
})

test('路由切换隔离迟到响应，返回通知页重新读取', async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async () => { reads++; return reads === 1 ? pending.promise : page([notification('2')]) })
  const old = h.state(); await h.router.push('/cards'); await h.flush()
  pending.resolve(page([notification('secret')])); await h.flush()
  assert.equal(old.results, null)
  await h.router.push('/notifications'); await h.flush()
  assert.equal(h.state().results.items[0].id, '2'); assert.equal(reads, 2)
})

test('账号或同账号token切换立即清空旧数据，旧401不能退出新会话', async t => {
  const waits = []; let hold = false
  const h = await mount(t, async () => {
    if (hold) { const pending = deferred(); waits.push(pending); return pending.promise }
    return page([notification('old')])
  })
  const old = h.state(); hold = true; const loading = old.load(); await h.flush()
  h.session.value = { user: { id: 'bob' }, accessToken: 'bob-token' }; hold = false; await h.flush()
  assert.notEqual(h.state(), old); assert.equal(old.results, null)
  waits[0].reject(new h.RequestError(401, '旧登录过期')); await loading; await h.flush()
  assert.equal(h.session.value.accessToken, 'bob-token'); assert.equal(h.router.currentRoute.value.path, '/notifications')
  const bob = h.state(); hold = true; const reloading = bob.load(); await h.flush()
  h.session.value = { user: { id: 'bob' }, accessToken: 'bob-new-token' }; hold = false; await h.flush()
  assert.notEqual(h.state(), bob); assert.equal(bob.results, null)
  waits[1].reject(new h.RequestError(401, '旧token过期')); await reloading; await h.flush()
  assert.equal(h.session.value.accessToken, 'bob-new-token')
})

test('当前会话401清会话并跳登录，保留通知返回路径', async t => {
  let h, expired = false, reads = 0
  h = await mount(t, async () => { reads++; if (expired) throw new h.RequestError(401, '登录已过期'); return page() })
  expired = true; await h.state().load(); await h.flush()
  assert.equal(h.session.value, null); assert.equal(h.router.currentRoute.value.name, 'login')
  assert.equal(h.router.currentRoute.value.query.redirect, '/notifications')
  assert.equal(reads, 2, '清会话到跳登录之间不发起匿名通知请求')
})

test('查看任务写入期间离页，迟到成功不得跳走或恢复旧列表', async t => {
  const pending = deferred()
  const h = await mount(t, async (url, method = 'GET') => method === 'PUT' ? pending.promise : page())
  const old = h.state(), opening = old.openTask(old.results.items[0]); await h.flush()
  await h.router.push('/cards'); await h.flush()
  pending.resolve(notification('1', true)); await opening; await h.flush()
  assert.equal(h.router.currentRoute.value.path, '/cards'); assert.equal(old.results, null)
})

test('读状态写入迟到401不能清除新账号或跳登录', async t => {
  const pending = deferred()
  const h = await mount(t, async (url, method = 'GET') => method === 'PUT' ? pending.promise : page())
  const old = h.state(), opening = old.openTask(old.results.items[0]); await h.flush()
  h.session.value = { user: { id: 'bob' }, accessToken: 'bob-token' }; await h.flush()
  pending.reject(new h.RequestError(401, '甲账号已过期')); await opening; await h.flush()
  assert.equal(h.session.value.accessToken, 'bob-token'); assert.equal(h.router.currentRoute.value.path, '/notifications')
  assert.equal(h.state().actionError, ''); assert.equal(old.results, null)
})

test('同一路径查询变化重建页面，迟到列表不得覆盖新路由结果', async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async () => ++reads === 1 ? pending.promise : page([notification('2')]))
  const old = h.state(); await h.router.push('/notifications?source=menu'); await h.flush()
  pending.resolve(page([notification('1')])); await h.flush()
  assert.notEqual(h.state(), old); assert.equal(old.results, null)
  assert.equal(h.state().results.items[0].id, '2')
})
