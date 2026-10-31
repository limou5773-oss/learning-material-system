const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const filters = { keyword: ' 概念 ', subject: ' 数学 ', tag: '', favoriteOnly: false }
const card = (id, parentCardId = null) => ({ id, parentCardId, version: 1, title: `卡片${id}`, content: '正文',
  summary: null, tags: [], subject: '数学', origin: 'MANUAL', generationMode: 'MANUAL', isEdited: false,
  favorite: false, createdAt: '', updatedAt: '' })
async function logic() {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/organization.ts')), '应实现卡片组织逻辑')
  return import('../web/src/organization.ts')
}

test('组织范围使用100项分页，读取完整两页且保留筛选；超200拒绝呈现局部全集', async () => {
  const { loadOrganizationRange } = await logic()
  const paths = []
  const cards = await loadOrganizationRange(async url => {
    paths.push(new URL(url, 'https://test'))
    const page = Number(paths.at(-1).searchParams.get('page'))
    return { total: 101, items: page === 1 ? Array.from({ length: 100 }, (_, i) => card(String(i))) : [card('100')] }
  }, filters)
  assert.equal(cards.length, 101)
  assert.deepEqual(paths.map(url => Object.fromEntries(url.searchParams)), [1, 2].map(page => ({
    keyword: '概念', subject: '数学', tag: '', favoriteOnly: 'false', page: String(page), pageSize: '100' })))
  let reads = 0
  await assert.rejects(() => loadOrganizationRange(async () => { reads++; return { total: 201, items: [card('a')] } }, filters), /200.*缩小/)
  assert.equal(reads, 1)
  await assert.rejects(() => loadOrganizationRange(async url => ({ total: url.includes('page=2') ? 201 : 101,
    items: Array.from({ length: 100 }, (_, i) => card(String(i))) }), filters), /200.*缩小/)
})

test('布局恢复保留负坐标及顺序，新卡片排列为网格且提示未载入已保存ID', async () => {
  const { prepareLayout, canvasBounds, layoutPayload } = await logic()
  const saved = [{ cardId: 'a', x: -80, y: -30, orderNo: 7 }, { cardId: 'outside', x: 5, y: 6, orderNo: 8 }]
  const prepared = prepareLayout([card('a'), card('b')], saved)
  assert.deepEqual(prepared.items[0], saved[0])
  assert.equal(prepared.items[1].cardId, 'b')
  assert.notEqual(prepared.items[1].orderNo, 7)
  assert.deepEqual(prepared.unloadedIds, ['outside'])
  const bounds = canvasBounds(prepared.items)
  assert.ok(prepared.items.every(item => item.x + bounds.offsetX >= 24 && item.y + bounds.offsetY >= 24))
  assert.equal(prepared.items[0].x, -80)
  assert.deepEqual(layoutPayload(0, []), { layoutVersion: 0, items: [] })
  for (const patch of [{ x: NaN }, { y: 100001 }, { x: -100001 }, { orderNo: 1.1 }, { orderNo: -1 }, { orderNo: 2147483648 }]) {
    assert.throws(() => layoutPayload(0, [{ ...prepared.items[0], ...patch }]))
  }
  assert.throws(() => layoutPayload(0, [prepared.items[0], { ...prepared.items[1], orderNo: 7 }]), /顺序.*重复/)
  assert.throws(() => layoutPayload(0, [prepared.items[0], prepared.items[0]]), /卡片.*重复/)
  assert.throws(() => layoutPayload(0, Array.from({ length: 201 }, (_, i) => ({ cardId: String(i), x: 0, y: 0, orderNo: i }))), /200/)
})

test('层级解析画当前父子边，将范围外父级单独标识且不冒充根', async () => {
  const { hierarchyGraph } = await logic()
  const graph = hierarchyGraph([card('root'), card('child', 'root'), card('outside-child', 'absent')])
  assert.deepEqual(graph.edges.map(edge => [edge.parentId, edge.childId]), [['root', 'child']])
  assert.equal(graph.nodes.find(node => node.id === 'child').depth, 1)
  const outside = graph.nodes.find(node => node.id === 'outside-child')
  assert.equal(outside.externalParentId, 'absent')
  assert.equal(outside.isRoot, false)
  assert.equal(graph.nodes.find(node => node.id === 'root').isRoot, true)
})

test('最大合法堆叠顺序旁新增卡片仍分配可保存的空闲顺序', async () => {
  const { prepareLayout, layoutPayload } = await logic()
  const saved = [{ cardId: 'a', x: 0, y: 0, orderNo: 2147483647 }, { cardId: 'b', x: 10, y: 20, orderNo: 0 }]
  const prepared = prepareLayout([card('a'), card('b'), card('new')], saved)
  assert.deepEqual(prepared.items.slice(0, 2), saved)
  assert.equal(prepared.items[2].orderNo, 1)
  assert.doesNotThrow(() => layoutPayload(1, prepared.items))
})

async function mountOrganization(t, request) {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/views/CardOrganizationView.vue')), '应实现真实卡片组织页面')
  const vue = require('../web/node_modules/vue'), vueRouter = require('../web/node_modules/vue-router')
  const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
  const ts = require('../web/node_modules/typescript'), cache = new Map(), states = new Map(), confirmations = []
  const oldWindow = global.window, oldDocument = global.Document, oldShadowRoot = global.ShadowRoot
  global.Document = class {}; global.ShadowRoot = class {}
  let allowConfirm = false, app
  const listeners = new Map()
  global.window = { addEventListener(name, handler) { listeners.set(name, handler) },
    removeEventListener(name) { listeners.delete(name) }, confirm(message) { confirmations.push(message); return allowConfirm } }
  t.after(() => { app?.unmount(); global.window = oldWindow; global.Document = oldDocument; global.ShadowRoot = oldShadowRoot })
  class RequestError extends Error { constructor(status, message, currentVersion) { super(message); this.status = status; this.currentVersion = currentVersion } }
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
      if (specifier.endsWith('/auth')) return { session: vue.ref({ user: { id: 'alice', role: 'USER', username: 'test_user', displayName: '测试用户' } }), clearSession() {} }
      if (specifier.endsWith('/api')) return { api: request, ApiRequestError: RequestError }
      if (specifier.endsWith('.vue') && !/Organization|Layout|UiIcon/.test(specifier)) return { render: () => vue.h('div') }
      if (specifier.startsWith('.')) {
        const target = path.resolve(path.dirname(filename), specifier)
        return load(target.endsWith('.vue') ? target : `${target}.ts`)
      }
      throw new Error(`未预期的组织页依赖：${specifier}`)
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
    createElement: tag => ({ tag, tagName: tag.toUpperCase(), children: [], props: {}, addEventListener() {},
      removeEventListener() {}, getRootNode: () => ({}), setPointerCapture() {}, hasPointerCapture: () => false }),
    createText: text => ({ text }), createComment: text => ({ text }), setText(node, text) { node.text = text },
    setElementText(node, text) { node.text = text }, patchProp(node, key, before, value) { node.props[key] = value; node[key] = value },
    insert(node, parent, anchor) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
      node.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node) },
    remove(node) { node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = null },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
  })
  app = renderer.createApp({ render: () => vue.h(vueRouter.RouterView) }); app.use(router)
  await router.push('/cards/organize'); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 5; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { vue, router, root, all, flush, states, confirmations, listeners, RequestError,
    confirm: value => { allowConfirm = value }, state: () => states.get('CardOrganizationView.vue'),
    text: () => all().map(node => node.text || '').join(' ') }
}

test('真实组织页拖动与键盘保存原逻辑坐标，刷新恢复并清空完整布局', async t => {
  let saved = { layoutKey: 'default', layoutVersion: 0, items: [] }, payload
  const h = await mountOrganization(t, async (url, method = 'GET', body) => {
    if (url === '/card-layouts/default') {
      if (method === 'PUT') { payload = structuredClone(body); saved = { layoutKey: 'default', ...body, layoutVersion: body.layoutVersion + 1 } }
      return structuredClone(saved)
    }
    return { total: 2, items: [card('a'), card('b', 'outside')] }
  })
  const view = h.state()
  assert.ok(view?.ready, '真实 /cards/organize 路由应挂载组织页面')
  const handle = h.all().find(node => node.props?.['aria-label'] === '移动卡片a')
  assert.ok(handle)
  handle.props.onPointerdown({ button: 0, pointerId: 1, clientX: 100, clientY: 100, currentTarget: handle, preventDefault() {} })
  handle.props.onPointermove({ pointerId: 1, clientX: 20, clientY: 70 })
  await h.flush()
  const moving = h.all().find(node => node.props?.class?.includes('canvas-card'))
  assert.equal(moving.props.style.left, '-56px', '拖动期间冻结显示原点，使卡片实际跟随指针')
  handle.props.onPointercancel({ pointerId: 1 })
  handle.props.onPointermove({ pointerId: 1, clientX: 900, clientY: 900 })
  assert.deepEqual(view.items.find(item => item.cardId === 'a'), { cardId: 'a', x: -80, y: -30, orderNo: 0 })
  await h.flush()
  assert.equal(moving.props.style.left, '24px', '结束拖动后再偏移显示全部负坐标范围')
  handle.props.onKeydown({ key: 'ArrowLeft', shiftKey: false, preventDefault() {} })
  assert.equal(view.items.find(item => item.cardId === 'a').x, -90)
  await view.saveLayout(); await h.flush()
  assert.equal(payload.items[0].x, -90)
  assert.equal(payload.layoutVersion, 0)
  assert.equal(view.dirty, false)
  await view.load(); await h.flush()
  assert.equal(view.items[0].x, -90)
  h.confirm(true); view.clearCanvas(); await view.saveLayout(); await h.flush()
  assert.deepEqual(payload.items, [])
  await view.load(); await h.flush()
  assert.deepEqual(view.items, [], '已保存的空画布重读后应保持为空')
})

test('真实组织页409保留本地位置和旧版本，服务器快照显式放弃后应用', async t => {
  let reads = 0, h, rejectRead = true
  h = await mountOrganization(t, async (url, method = 'GET') => {
    if (url !== '/card-layouts/default') return { total: 1, items: [card('a')] }
    if (method === 'PUT') throw new h.RequestError(409, '布局已变化', 3)
    reads++
    if (reads > 1 && rejectRead) throw new Error('快照读取失败')
    return { layoutKey: 'default', layoutVersion: reads === 1 ? 1 : 3,
      items: [{ cardId: 'a', x: reads === 1 ? 1 : 300, y: 2, orderNo: 0 }] }
  })
  const view = h.state(); view.moveItem({ cardId: 'a', x: -60, y: 40 }); await view.saveLayout(); await h.flush()
  assert.equal(view.layoutVersion, 1)
  assert.equal(view.items[0].x, -60)
  assert.equal(view.currentVersion, 3)
  assert.equal(view.conflict, true)
  assert.equal(reads, 1, '冲突后由用户显式读取，不自动应用服务器快照')
  await view.loadServerCopy(); assert.match(view.serverError, /快照读取失败/)
  rejectRead = false; await view.loadServerCopy(); await h.flush()
  assert.equal(view.items[0].x, -60)
  assert.equal(view.layoutVersion, 1)
  view.discardLocal(); assert.equal(view.items[0].x, -60)
  h.confirm(true); view.discardLocal(); await h.flush()
  assert.equal(view.items[0].x, 300)
  assert.equal(view.layoutVersion, 3)
  assert.equal(view.conflict, false)
})

test('真实组织页筛选重读与离开确认，在途保存/读取/父级请求锁定编辑并保留父级失败选择', async t => {
  let h, finish, held = '', sentParent
  h = await mountOrganization(t, async (url, method = 'GET', body) => {
    if (held && (held === 'load' || (held === 'save' && method === 'PUT') || url.endsWith('/parent'))) {
      if (url.endsWith('/parent')) sentParent = { url, body }
      return new Promise((resolve, reject) => { finish = { resolve, reject } })
    }
    if (url === '/card-layouts/default') return { layoutKey: 'default', layoutVersion: method === 'PUT' ? 1 : 0, items: method === 'PUT' ? body.items : [] }
    return { total: 2, items: [card('a'), card('b', 'outside')] }
  })
  const view = h.state(); view.moveItem({ cardId: 'a', x: 10, y: 20 }); await h.flush()
  await view.load(); assert.equal(view.items[0].x, 10); assert.ok(h.confirmations.length)
  await h.router.push('/cards'); assert.equal(h.router.currentRoute.value.path, '/cards/organize')
  const checkLocked = async () => {
    await h.flush()
    const controls = h.all().filter(node => ['input', 'select', 'button'].includes(node.tag))
    assert.ok(controls.length > 5)
    assert.ok(controls.every(node => node.props.disabled === true), '请求期间所有画布、筛选、选择和父级按钮应禁用')
    const before = JSON.stringify(view.items)
    view.moveItem({ cardId: 'a', x: 999, y: 999 }); view.clearCanvas()
    assert.equal(JSON.stringify(view.items), before)
    await h.router.push('/cards'); assert.equal(h.router.currentRoute.value.path, '/cards/organize')
  }
  held = 'save'; const saving = view.saveLayout(); await checkLocked()
  finish.reject(new Error('保存失败')); await saving
  assert.equal(view.items[0].x, 10)
  h.confirm(true); held = 'load'; const loading = view.load(); await checkLocked()
  finish.reject(new Error('加载失败')); await loading
  held = ''; await view.load(); await h.flush()
  view.selectCard('a'); view.parentChoice = 'b'; held = 'parent'
  const parenting = view.saveParent(); await checkLocked()
  assert.deepEqual(sentParent, { url: '/cards/a/parent', body: { version: 1, parentCardId: 'b' } })
  finish.reject(new h.RequestError(422, '父子层级不能成环')); await parenting; await h.flush()
  assert.equal(view.parentChoice, 'b')
  assert.equal(view.cards[0].version, 1)
  assert.match(view.error, /不能成环/)
  view.selectCard('b'); await h.flush()
  assert.match(h.text(), /父卡片未载入.*outside/)
})

test('真实坐标顺序控件校验并保存，层级切换渲染真实父子连线和范围外标识', async t => {
  let payload
  const h = await mountOrganization(t, async (url, method = 'GET', body) => {
    if (url === '/card-layouts/default') {
      if (method === 'PUT') payload = structuredClone(body)
      return { layoutKey: 'default', layoutVersion: method === 'PUT' ? 1 : 0, items: body?.items || [] }
    }
    return { total: 3, items: [card('a'), card('b', 'a'), card('c', 'outside')] }
  })
  const view = h.state(), numbers = () => h.all().filter(node => node.tag === 'input' && node.props.type === 'number')
  numbers()[0].props.onChange({ target: { value: '-125.5' } }); await h.flush()
  numbers()[1].props.onChange({ target: { value: '-20' } }); await h.flush()
  numbers()[2].props.onChange({ target: { value: '17' } }); await h.flush()
  assert.deepEqual(view.items[0], { cardId: 'a', x: -125.5, y: -20, orderNo: 17 })
  const invalid = { value: '' }; numbers()[0].props.onChange({ target: invalid }); await h.flush()
  assert.match(view.error, /坐标/)
  assert.equal(invalid.value, '-125.5')
  assert.equal(view.items[0].x, -125.5)
  numbers()[2].props.onChange({ target: { value: '1' } }); await h.flush()
  assert.match(view.error, /顺序.*重复/)
  assert.equal(view.items[0].orderNo, 17)
  await view.saveLayout(); await h.flush()
  assert.equal(payload.items[0].orderNo, 17)
  const toggle = h.all().find(node => node.tag === 'button' && node.text === '父子层级图')
  toggle.props.onClick(); await h.flush()
  assert.equal(h.all().filter(node => node.tag === 'path').length, 1)
  assert.match(h.text(), /根卡片/)
  assert.match(h.text(), /父卡片未载入.*outside/)
})

test('真实组织页明确提示未载入已保存ID，完整替换前确认且超限不提供保存', async t => {
  let saves = 0, oversized = false, h
  h = await mountOrganization(t, async (url, method = 'GET', body) => {
    if (url === '/card-layouts/default') {
      if (method === 'PUT') { saves++; assert.deepEqual(body.items.map(item => item.cardId), ['a']) }
      return { layoutKey: 'default', layoutVersion: method === 'PUT' ? 5 : 4,
        items: method === 'PUT' ? body.items : [{ cardId: 'a', x: 0, y: 0, orderNo: 0 },
          { cardId: 'not-loaded', x: 40, y: 10, orderNo: 1 }] }
    }
    return { total: oversized ? 201 : 1, items: [card('a')] }
  })
  assert.match(h.text(), /已保存但当前未载入.*not-loaded/)
  assert.match(h.text(), /完整替换 default/)
  await h.state().saveLayout(); assert.equal(saves, 0)
  assert.match(h.confirmations.at(-1), /移除 1 张未载入/)
  h.confirm(true); await h.state().saveLayout(); assert.equal(saves, 1)
  oversized = true; await h.state().load(); await h.flush()
  assert.equal(h.state().ready, false)
  assert.match(h.text(), /201.*200.*缩小/)
  assert.equal(h.all().some(node => node.tag === 'button' && node.text?.startsWith('保存当前画布')), false)
})

test('真实组织路由query切换确认并重建范围，加载在途阻止旧范围写入新路由', async t => {
  const reads = []; let hold = false, finish
  const h = await mountOrganization(t, async url => {
    if (url === '/card-layouts/default') return { layoutKey: 'default', layoutVersion: 0, items: [] }
    const keyword = new URL(url, 'https://test').searchParams.get('keyword'); reads.push(keyword)
    if (hold) return new Promise(resolve => { finish = resolve })
    return { total: 1, items: [card(keyword || 'initial')] }
  })
  const first = h.state(); first.moveItem({ cardId: 'initial', x: 40, y: 50 })
  await h.router.push('/cards/organize?keyword=second'); await h.flush()
  assert.equal(h.router.currentRoute.value.fullPath, '/cards/organize')
  h.confirm(true); hold = true
  await h.router.push('/cards/organize?keyword=second'); await h.flush()
  const second = h.state(); assert.notEqual(second, first)
  assert.equal(second.filters.keyword, 'second')
  await h.router.push('/cards/organize?keyword=third'); await h.flush()
  assert.equal(h.router.currentRoute.value.fullPath, '/cards/organize?keyword=second')
  finish({ total: 1, items: [card('second')] }); await h.flush()
  assert.equal(second.cards[0].id, 'second')
  hold = false; await h.router.push('/cards/organize?keyword=third'); await h.flush()
  assert.equal(h.state().cards[0].id, 'third')
  assert.deepEqual(reads, ['', 'second', 'third'])
})

test('真实父级保存提交当前卡片版本，成功采用返回值，409保留选择并停止旧版重试', async t => {
  let h, stale = false, parents = 0
  h = await mountOrganization(t, async (url, method = 'GET', body) => {
    if (url === '/card-layouts/default') return { layoutKey: 'default', layoutVersion: 0, items: [] }
    if (url.endsWith('/parent')) {
      parents++
      assert.equal(method, 'PUT'); assert.equal(body.parentCardId, 'b')
      assert.equal(body.version, stale ? 2 : 1)
      if (stale) throw new h.RequestError(409, '已有新版本', 3)
      return { ...card('a', 'b'), version: 2 }
    }
    return { total: 2, items: [card('a'), card('b')] }
  })
  const view = h.state(); view.parentChoice = 'b'; await view.saveParent(); await h.flush()
  assert.equal(view.cards[0].parentCardId, 'b')
  assert.equal(view.cards[0].version, 2)
  assert.equal(view.cards[0].content, '正文')
  stale = true; await view.saveParent(); await h.flush()
  assert.equal(view.parentChoice, 'b')
  assert.equal(view.cards[0].version, 2)
  assert.equal(view.parentConflict, true)
  assert.match(view.error, /新版本 3.*选择已保留/)
  await view.saveParent(); assert.equal(parents, 2)
})

test('没有移动也发生布局409时，通用重读仍需明确确认放弃本地快照', async t => {
  let h, reads = 0
  h = await mountOrganization(t, async (url, method = 'GET') => {
    if (url !== '/card-layouts/default') return { total: 1, items: [card('a')] }
    if (method === 'PUT') throw new h.RequestError(409, '布局已变化', 3)
    reads++
    return { layoutKey: 'default', layoutVersion: reads === 1 ? 1 : 3,
      items: [{ cardId: 'a', x: reads === 1 ? 10 : 300, y: 20, orderNo: 0 }] }
  })
  const view = h.state(); assert.equal(view.dirty, false)
  await view.saveLayout(); await h.flush()
  assert.equal(view.conflict, true)
  await view.load(); await h.flush()
  assert.equal(reads, 1, '不能通过普通重读跳过冲突快照的放弃确认')
  assert.equal(view.layoutVersion, 1)
  h.confirm(true); await view.load(); await h.flush()
  assert.equal(reads, 2)
  assert.equal(view.layoutVersion, 3)
})
