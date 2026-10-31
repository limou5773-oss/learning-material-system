const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const withinMain = node => Boolean(node && (node.tag === 'main' || withinMain(node.parent)))

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
      if (specifier.endsWith('/api')) return { api: request, ApiRequestError: RequestError }
      if (specifier.endsWith('.vue') && !/AdminUsers|ForbiddenView|MaterialsView|App.vue|Layout|UiIcon/.test(specifier)) return { render: () => vue.h('div') }
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
  await router.push(options.path || '/admin/users'); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 32; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { router, all, flush, session, RequestError, prompts, events, consent(value) { consent = value },
    state: (filename = 'AdminUsersView.vue') => states.get(filename), text: () => all().map(node => node.text || '').join(' '),
    button: label => all().find(node => node.tag === 'button' && node.text === label),
    adminLink: () => all().find(node => node.tag === 'a' && node.props.href === '/admin/users') }
}

test('ADMIN实际导航和管理路由读取固定用户分页，七字段按文本展示', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); return page([{ ...user(), displayName: '<strong>用户</strong>' }]) })
  assert.ok(h.adminLink(), 'ADMIN应显示用户管理导航')
  assert.equal(h.router.currentRoute.value.meta.requiresAuth, true)
  assert.equal(h.router.currentRoute.value.meta.requiresRole, 'ADMIN')
  assert.deepEqual(reads, ['/admin/users?keyword=&status=&page=1&pageSize=20'])
  for (const text of ['用户管理', 'account_2', '<strong>用户</strong>', '普通用户', '启用', '未绑定']) assert.ok(h.text().includes(text), text)
  assert.equal(h.all().some(node => node.tag === 'strong' && node.text === '用户'), false)
  assert.equal(h.all().filter(node => node.tag === 'th').length, 8, '七字段加操作列')
})

test('普通角色直接进入显示无权限，不显示导航或请求管理API；未登录先跳登录', async t => {
  let requests = 0
  const h = await mount(t, async () => { requests++; return page() }, { session: identity('3', 'user-token', 'USER') })
  assert.equal(h.adminLink(), undefined); assert.match(h.text(), /无权限/); assert.equal(requests, 0)
  assert.equal(h.router.currentRoute.value.name, 'forbidden')
  h.session.value = null; await h.router.push('/admin/users'); await h.flush()
  assert.equal(h.router.currentRoute.value.name, 'login'); assert.equal(h.router.currentRoute.value.query.redirect, '/admin/users')
  assert.equal(requests, 0)
})

test('关键词和状态筛选规范查询并重置首页，高页为空仍可返回', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); const p = Number(new URLSearchParams(url.split('?')[1]).get('page')); return page(p === 2 ? [] : [user()], p, 41) })
  await h.state().changePage(2); await h.flush()
  assert.match(h.text(), /本页暂无用户/); assert.equal(h.button('上一页').props.disabled, false)
  h.state().keyword = '  ali%_  '; h.state().status = 'DISABLED'
  await h.state().applyFilters(); await h.flush()
  assert.equal(reads.at(-1), '/admin/users?keyword=ali%25_&status=DISABLED&page=1&pageSize=20')
  assert.equal(h.state().page, 1)
  h.state().keyword = 'a'.repeat(101); await h.state().applyFilters(); await h.flush()
  assert.equal(reads.length, 3); assert.match(h.text(), /关键词.*100/)
})

test('读取中、真正空列表和读取失败区分，刷新只由用户触发', async t => {
  const pending = deferred(); let mode = 'pending', reads = 0
  const h = await mount(t, async () => { reads++; if (mode === 'pending') return pending.promise; if (mode === 'error') throw new Error('用户服务不可用'); return page([]) })
  assert.match(h.text(), /正在读取用户/); assert.equal(h.button('刷新').props.disabled, true)
  pending.resolve(page([])); await h.flush(); assert.match(h.text(), /暂无用户/)
  mode = 'error'; await h.state().load(); await h.flush()
  assert.match(h.text(), /用户服务不可用/); assert.doesNotMatch(h.text(), /暂无用户/); assert.equal(reads, 2)
  mode = 'empty'; await h.state().load(); await h.flush(); assert.match(h.text(), /暂无用户/)
})

test('未提交的关键词和状态不混入分页或刷新，提交筛选始终回首页', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); return page([user()], Number(new URLSearchParams(url.split('?')[1]).get('page')), 41) })
  h.state().keyword = '新关键词'; h.state().status = 'DISABLED'
  await h.state().changePage(2); await h.state().load()
  assert.equal(reads.at(-1), '/admin/users?keyword=&status=&page=2&pageSize=20')
  await h.state().applyFilters(); await h.flush()
  assert.equal(reads.at(-1), '/admin/users?keyword=%E6%96%B0%E5%85%B3%E9%94%AE%E8%AF%8D&status=DISABLED&page=1&pageSize=20')
})

test('选择账号后必须确认目标状态与trim原因才PATCH，成功使用返回视图并重读', async t => {
  const writes = []; let item = user(), reads = 0
  const h = await mount(t, async (url, method = 'GET', body) => {
    if (method === 'PATCH') { writes.push({ url, body }); item = { ...item, status: body.status }; return item }
    reads++; return page([item])
  })
  assert.equal(typeof h.state().selectUser, 'function', '应实现账号状态编辑入口')
  await h.state().selectUser(h.state().results.items[0]); h.state().reason = '  账号违规核实  '
  h.consent(false); await h.state().submit(); await h.flush(); assert.equal(writes.length, 0)
  assert.match(h.prompts.at(-1), /account_2/); assert.match(h.prompts.at(-1), /停用/)
  h.consent(true); await h.button('确认保存状态').props.onClick(); await h.flush()
  assert.deepEqual(writes, [{ url: '/admin/users/2/status', body: { status: 'DISABLED', reason: '账号违规核实' } }])
  assert.equal(reads, 2); assert.equal(h.state().results.items[0].status, 'DISABLED')
  assert.equal(h.state().reason, ''); assert.equal(h.state().selected, null); assert.match(h.text(), /状态已保存/)
})

test('空原因、超长原因和自行停用在客户端阻止写入，允许同值明确操作', async t => {
  const writes = []
  const h = await mount(t, async (url, method = 'GET', body) => { if (method === 'PATCH') { writes.push(body); return user('1', body.status, 'ADMIN') }; return page([user('1', 'ENABLED', 'ADMIN')]) })
  await h.state().selectUser(h.state().results.items[0]); assert.equal(h.state().targetStatus, 'ENABLED')
  assert.match(h.text(), /不能停用当前登录账号/)
  h.state().reason = ' '; await h.state().submit(); assert.equal(writes.length, 0)
  h.state().reason = 'a'.repeat(1001); await h.state().submit(); assert.equal(writes.length, 0)
  h.state().reason = '有效原因'; h.state().targetStatus = 'DISABLED'; await h.state().submit(); assert.equal(writes.length, 0)
  h.state().targetStatus = 'ENABLED'; await h.state().submit(); await h.flush()
  assert.deepEqual(writes, [{ status: 'ENABLED', reason: '有效原因' }])
})

test('409或写失败保留账号目标和原因，不重试且不重读列表', async t => {
  let h, writes = 0, reads = 0, status = 409
  h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PATCH') { writes++; throw new h.RequestError(status, status === 409 ? '不能停用最后启用管理员' : '状态保存服务不可用') }
    reads++; return page()
  })
  await h.state().selectUser(h.state().results.items[0]); h.state().reason = '保留审核原因'
  await h.state().submit(); await h.flush()
  assert.equal(writes, 1); assert.equal(reads, 1); assert.equal(h.state().selected.id, '2')
  assert.equal(h.state().targetStatus, 'DISABLED'); assert.equal(h.state().reason, '保留审核原因'); assert.match(h.text(), /最后启用管理员/)
  status = 500; await h.state().submit(); await h.flush()
  assert.equal(writes, 2); assert.equal(reads, 1); assert.equal(h.state().reason, '保留审核原因'); assert.match(h.text(), /状态保存服务不可用/)
})

test('写成功后读取失败独立提示且保留写成功结果，不冒充写失败或空列表', async t => {
  let saved = false
  const h = await mount(t, async (url, method = 'GET') => {
    if (method === 'PATCH') { saved = true; return user('2', 'DISABLED') }
    if (saved) throw new Error('列表重读失败'); return page()
  })
  await h.state().selectUser(h.state().results.items[0]); h.state().reason = '已核实'
  await h.state().submit(); await h.flush()
  assert.match(h.text(), /状态已保存/); assert.match(h.text(), /列表重读失败/)
  assert.equal(h.state().actionError, ''); assert.equal(h.state().reason, ''); assert.doesNotMatch(h.text(), /暂无用户/)
})

test('写入在途锁定控件、切换和路由，拒绝重复提交', async t => {
  const pending = deferred(); let writes = 0, reads = 0
  const h = await mount(t, async (url, method = 'GET') => { if (method === 'PATCH') { writes++; return pending.promise }; reads++; return page([user(), user('3')], 1, 41) })
  const view = h.state(); await view.selectUser(view.results.items[0]); view.reason = '原因'
  const saving = view.submit(); await h.flush()
  const controls = h.all().filter(node => ['button', 'select', 'input', 'textarea'].includes(node.tag) && withinMain(node))
  assert.ok(controls.length >= 8); assert.ok(controls.every(node => node.props.disabled === true))
  await view.submit(); await view.selectUser(view.results.items[1]); await view.cancelEdit(); await view.applyFilters(); await view.changePage(2); await view.load()
  await h.router.push('/admin/reviews'); await h.flush()
  assert.equal(h.router.currentRoute.value.path, '/admin/users'); assert.equal(writes, 1); assert.equal(reads, 1)
  assert.equal(view.selected.id, '2'); assert.equal(view.reason, '原因')
  pending.resolve(user('2', 'DISABLED')); await saving; await h.flush(); assert.equal(reads, 2)
})

test('脏原因切换对象、筛选、取消、离页或同路径查询变化需确认', async t => {
  const h = await mount(t, async () => page([user(), user('3')]))
  const view = h.state(); await view.selectUser(view.results.items[0]); view.reason = '尚未保存'
  h.consent(false)
  await view.selectUser(view.results.items[1]); await view.cancelEdit(); await view.applyFilters(); await view.changePage(2)
  assert.equal(view.selected.id, '2'); assert.equal(view.reason, '尚未保存'); assert.equal(view.page, 1)
  await h.router.push('/admin/reviews'); await h.router.push('/admin/users?source=menu'); await h.flush()
  assert.equal(h.router.currentRoute.value.fullPath, '/admin/users'); assert.equal(view.reason, '尚未保存')
  let blocked = false; const unload = { preventDefault() { blocked = true }, returnValue: undefined }
  h.events.get('beforeunload')(unload); assert.equal(blocked, true); assert.equal(unload.returnValue, '')
  h.consent(true); await view.selectUser(view.results.items[1]); await h.flush()
  assert.equal(view.selected.id, '3'); assert.equal(view.reason, '')
  view.reason = '另一个原因'; await h.router.push('/admin/reviews'); await h.flush()
  assert.equal(h.router.currentRoute.value.path, '/admin/reviews'); assert.equal(view.results, null); assert.equal(view.selected, null); assert.equal(view.reason, '')
})

test('账号、token、角色变化清空列表草稿，旧401和403不能退出或拒绝新会话', async t => {
  const waits = []; let hold = false
  const h = await mount(t, async () => { if (hold) { const pending = deferred(); waits.push(pending); return pending.promise }; return page() })
  for (const [next, status] of [[identity('4', 'next-token'), 401], [identity('4', 'rotated-token'), 403]]) {
    const old = h.state(); await old.selectUser(old.results.items[0]); old.reason = '旧账号原因'
    hold = true; const loading = old.load(); await h.flush(); hold = false
    h.session.value = next; await h.flush()
    assert.notEqual(h.state(), old); assert.equal(old.results, null); assert.equal(old.selected, null); assert.equal(old.reason, '')
    waits.at(-1).reject(new h.RequestError(status, '旧权限失败')); await loading; await h.flush()
    assert.equal(h.session.value.accessToken, next.accessToken); assert.equal(h.router.currentRoute.value.path, '/admin/users')
  }
  const old = h.state(); await old.selectUser(old.results.items[0]); old.reason = '角色变更前原因'
  h.session.value.user.role = 'USER'; await h.flush()
  assert.equal(old.results, null); assert.equal(old.reason, ''); assert.equal(h.adminLink(), undefined); assert.match(h.text(), /无权限/)
})

test('当前401退出回登录，当前403显示无权限；失败不会伪装空用户', async t => {
  let h, status = 0, reads = 0
  h = await mount(t, async () => { reads++; if (status) throw new h.RequestError(status, '当前鉴权失败'); return page() })
  status = 403; await h.state().load(); await h.flush()
  assert.equal(h.router.currentRoute.value.name, 'forbidden'); assert.ok(h.session.value); assert.match(h.text(), /无权限/)
  status = 0; await h.router.push('/admin/users'); await h.flush()
  await h.state().selectUser(h.state().results.items[0]); h.state().reason = '失效前原因'
  status = 401; await h.state().load(); await h.flush()
  assert.equal(h.session.value, null); assert.equal(h.router.currentRoute.value.name, 'login')
  assert.equal(h.router.currentRoute.value.query.redirect, '/admin/users'); assert.equal(reads, 4)
})

test('同路径路由变化清空旧页，迟到成功不得覆盖新结果', async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async () => ++reads === 1 ? pending.promise : page([user('3')]))
  const old = h.state(); await h.router.push('/admin/users?source=menu'); await h.flush()
  pending.resolve(page([user('secret')])); await h.flush()
  assert.notEqual(h.state(), old); assert.equal(old.results, null); assert.equal(h.state().results.items[0].id, '3')
})

test('写入迟到401在新账号下无效，不能导航或恢复旧草稿', async t => {
  const pending = deferred()
  const h = await mount(t, async (url, method = 'GET') => method === 'PATCH' ? pending.promise : page())
  const old = h.state(); await old.selectUser(old.results.items[0]); old.reason = '旧原因'
  const saving = old.submit(); await h.flush(); h.session.value = identity('4', 'next-token'); await h.flush()
  pending.reject(new h.RequestError(401, '旧账号过期')); await saving; await h.flush()
  assert.equal(h.session.value.accessToken, 'next-token'); assert.equal(h.router.currentRoute.value.path, '/admin/users')
  assert.equal(old.results, null); assert.equal(old.reason, ''); assert.equal(h.state().actionError, '')
})

for (const first of ['tags', 'materials']) {
  const second = first === 'tags' ? 'materials' : 'tags'
  test(`资料页${first}先401后${second}迟到401，登录返回路径不能嵌套`, async t => {
    const waits = { tags: deferred(), materials: deferred() }, reads = []
    const destination = '/materials?source=profile'
    const h = await mount(t, async url => {
      const type = url.startsWith('/tags?') ? 'tags' : 'materials'
      reads.push(type); return waits[type].promise
    }, { path: destination, session: identity('4', 'disabled-user-token', 'USER') })
    assert.deepEqual(reads, ['tags', 'materials'], '实际资料SFC挂载同时读取标签和列表')
    waits[first].reject(new h.RequestError(401, '账号停用，会话失效')); await h.flush()
    assert.equal(h.session.value, null); assert.equal(h.router.currentRoute.value.name, 'login')
    assert.equal(h.router.currentRoute.value.query.redirect, destination)
    waits[second].reject(new h.RequestError(401, '同一旧会话的迟到401')); await h.flush()
    assert.equal(h.router.currentRoute.value.query.redirect, destination, '迟到401不能把登录页作为新的返回路径')
  })

  test(`资料页${first}401后重新登录，旧${second}迟到401不能清除新token`, async t => {
    const waits = { tags: deferred(), materials: deferred() }, seen = new Set()
    const h = await mount(t, async url => {
      const type = url.startsWith('/tags?') ? 'tags' : 'materials'
      if (!seen.has(type)) { seen.add(type); return waits[type].promise }
      return page([])
    }, { path: '/materials', session: identity('4', 'disabled-user-token', 'USER') })
    waits[first].reject(new h.RequestError(401, '账号停用，会话失效')); await h.flush()
    assert.equal(h.router.currentRoute.value.name, 'login')
    h.session.value = identity('4', 'enabled-user-new-token', 'USER')
    await h.router.replace(h.router.currentRoute.value.query.redirect); await h.flush()
    assert.equal(h.router.currentRoute.value.path, '/materials')
    waits[second].reject(new h.RequestError(401, '旧token迟到401')); await h.flush()
    assert.equal(h.session.value?.accessToken, 'enabled-user-new-token', '旧响应不能退出重新启用后的新登录')
    assert.equal(h.router.currentRoute.value.path, '/materials')
  })
}
