const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const card = (id, version = 1) => ({ id, version, title: `卡片${id}`, content: `正文${id}`, summary: null,
  tags: [], subject: '数学', parentCardId: null, origin: 'MANUAL', generationMode: 'MANUAL', isEdited: false,
  favorite: false, createdAt: '', updatedAt: '' })
const relation = (id = '9', type = 'PREREQUISITE') => ({ id, sourceCardId: '1', targetCardId: '2', type,
  evidenceText: '<strong>人工确认的说明</strong>' })
const graph = (edges = [relation()], hasMore = false) => ({ nodes: [card('1'), card('2'), card('3')], edges, hasMore })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

async function mount(t, request, initialPath = '/knowledge-graph') {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/views/KnowledgeGraphView.vue')), '应实现关系图页面')
  const vue = require('../web/node_modules/vue'), vueRouter = require('../web/node_modules/vue-router')
  const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
  const ts = require('../web/node_modules/typescript'), cache = new Map(), states = new Map(), confirmations = [], listeners = new Map()
  const oldWindow = global.window, oldDocument = global.Document, oldShadowRoot = global.ShadowRoot, oldStorage = global.sessionStorage
  global.Document = class {}; global.ShadowRoot = class {}
  const stored = new Map(); global.sessionStorage = { getItem: key => stored.get(key) || null,
    setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) }
  let app, allowConfirm = false
  const session = vue.ref({ user: { id: 'alice' }, accessToken: 'test' })
  global.window = { addEventListener: (name, handler) => listeners.set(name, handler),
    removeEventListener: name => listeners.delete(name), confirm(message) { confirmations.push(message); return allowConfirm } }
  t.after(() => { app?.unmount(); global.window = oldWindow; global.Document = oldDocument;
    global.ShadowRoot = oldShadowRoot; global.sessionStorage = oldStorage })
  class RequestError extends Error { constructor(status, message, currentVersion) { super(message); this.status = status; this.currentVersion = currentVersion } }
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
      if (specifier.endsWith('.vue') && !/KnowledgeGraph|TaskConfirm|CardsView|CardDetailView/.test(specifier)) return { render: () => vue.h('div') }
      if (specifier.startsWith('.')) {
        const target = path.resolve(path.dirname(filename), specifier)
        return load(target.endsWith('.vue') ? target : `${target}.ts`)
      }
      throw new Error(`未预期的关系图依赖：${specifier}`)
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
  app = renderer.createApp({ render: () => vue.h(vueRouter.RouterView) }); app.use(router)
  await router.push(initialPath); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { vue, router, root, all, flush, states, confirmations, listeners, session, RequestError,
    confirm: value => { allowConfirm = value }, state: () => states.get('KnowledgeGraphView.vue'),
    text: () => all().map(node => node.text || '').join(' '),
    button: text => all().find(node => node.tag === 'button' && node.text === text) }
}

test('关系类型方向明确，RELATED无箭头，前置与示例保留起点终点方向', async () => {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/relations.ts')), '应实现关系语义与请求逻辑')
  const { relationDescription, graphPath, relationPayload } = await import('../web/src/relations.ts')
  assert.match(relationDescription('RELATED', '甲', '乙'), /甲.*↔.*乙.*无向/)
  assert.match(relationDescription('PREREQUISITE', '甲', '乙'), /甲.*是.*乙.*前置/)
  assert.match(relationDescription('EXAMPLE', '甲', '乙'), /乙.*是.*甲.*示例/)
  assert.equal(graphPath({ subject: ' 数学 ', rootCardId: ' 2 ' }), '/knowledge-graphs?subject=%E6%95%B0%E5%AD%A6&rootCardId=2')
  assert.equal(graphPath({ subject: '', rootCardId: '' }), '/knowledge-graphs')
  assert.equal(graphPath({ subject: '数学', rootCardId: ' ' }), '/knowledge-graphs?subject=%E6%95%B0%E5%AD%A6')
  assert.throws(() => graphPath({ subject: '', rootCardId: '0' }), /ID/)
  const form = { sourceCardId: '2', targetCardId: '1', type: 'RELATED', evidenceText: ' 核对说明 ' }
  assert.throws(() => relationPayload(form, null, null), /读取.*两端/)
  assert.deepEqual(relationPayload(form, card('2', 7), card('1', 4)), { sourceCardId: '2', sourceCardVersion: 7,
    targetCardId: '1', targetCardVersion: 4, type: 'RELATED', evidenceText: '核对说明' })
  assert.throws(() => relationPayload({ ...form, sourceCardId: '3' }, card('2'), card('1')), /重新读取/)
  assert.throws(() => relationPayload({ ...form, evidenceText: ' ' }, card('2'), card('1')), /说明/)
})

test('真实图路由传递学科和直接邻域筛选，展示限量提示、安全说明、方向箭头和不可用来源', async t => {
  const reads = [], edges = [relation('9'), relation('10', 'EXAMPLE'), relation('11', 'RELATED')]
  const h = await mount(t, async url => {
    reads.push(url)
    if (url.startsWith('/knowledge-graphs')) return graph(edges, true)
    if (url.endsWith('/sources')) return { items: [{ chunkId: 's', materialId: 'm', materialTitle: '失效资料', materialVersion: 1,
      isAvailable: false, unavailableReason: 'SOURCE_RESTRICTED', quoteText: '不应泄露的引用', content: '不应泄露的正文' }] }
    return card(url.split('/').at(-1))
  }, '/knowledge-graph?subject=%E6%95%B0%E5%AD%A6&rootCardId=1')
  assert.equal(reads[0], '/knowledge-graphs?subject=%E6%95%B0%E5%AD%A6&rootCardId=1')
  assert.match(h.text(), /中心卡片及直接相邻卡片/)
  assert.match(h.text(), /200.*600.*缩小/)
  const lines = h.all().filter(node => node.tag === 'path' && node.props['data-relation-id'])
  assert.equal(lines.length, 3)
  assert.ok(lines.find(node => node.props['data-relation-id'] === '9').props['marker-end'])
  assert.ok(lines.find(node => node.props['data-relation-id'] === '10').props['marker-end'])
  assert.equal(lines.find(node => node.props['data-relation-id'] === '11').props['marker-end'], undefined)
  await h.state().selectRelation('9'); await h.flush()
  assert.match(h.text(), /卡片1.*是.*卡片2.*前置/)
  assert.match(h.text(), /<strong>人工确认的说明<\/strong>/)
  assert.equal(h.all().some(node => node.tag === 'strong' && node.text === '人工确认的说明'), false, '说明以文本呈现')
  assert.match(h.text(), /原资料暂时受限/)
  assert.doesNotMatch(h.text(), /不应泄露/)
  assert.ok(reads.includes('/cards/1/sources') && reads.includes('/cards/2/sources'))
  await h.state().selectCard('3'); await h.flush()
  assert.match(h.text(), /正文3/)
})

test('图与来源请求失败显示可重试错误，不冒充空图或无来源', async t => {
  let failGraph = true, failSource = true
  const h = await mount(t, async url => {
    if (url.startsWith('/knowledge-graphs')) { if (failGraph) throw new Error('图服务不可用'); return { nodes: [], edges: [], hasMore: false } }
    if (url.endsWith('/sources')) { if (failSource) throw new Error('来源读取失败'); return { items: [] } }
    return card(url.split('/').at(-1))
  })
  assert.match(h.text(), /图服务不可用/); assert.doesNotMatch(h.text(), /当前范围没有卡片|无来源/)
  failGraph = false; await h.state().loadGraph(); await h.flush()
  assert.match(h.text(), /当前范围没有卡片/)
  h.state().graph = graph(); await h.state().selectRelation('9'); await h.flush()
  assert.match(h.text(), /来源读取失败/); assert.doesNotMatch(h.text(), /无自动来源|暂无来源/)
  failSource = false; await h.state().loadSelection(); await h.flush()
  assert.match(h.text(), /无自动来源/)
})

test('关系详情保留卡片的人工与MOCK来源标记，可用引用可打开原资料', async t => {
  const h = await mount(t, async url => {
    if (url.startsWith('/knowledge-graphs')) return graph()
    if (url.endsWith('/sources')) return { items: [{ chunkId: 's', materialId: '41', materialTitle: '自编原笔记',
      materialVersion: 1, isAvailable: true, quoteText: '自编引用', content: '自编完整片段', extractionMethod: 'TEXT' }] }
    return { ...card(url.split('/').at(-1)), origin: 'AI', generationMode: 'MOCK' }
  })
  await h.state().selectRelation('9'); await h.flush()
  assert.match(h.text(), /模拟结果/)
  assert.match(h.text(), /模拟候选 · 人工确认/)
  assert.match(h.text(), /自编引用/)
  assert.ok(h.all().some(node => node.tag === 'a' && node.props.href === '/materials/41'))
})

test('创建必须显式读取双端版本；改ID使快照失效；409保留所有输入并阻止旧版重试', async t => {
  let h, posts = 0, reads = 0, version = 2, body
  h = await mount(t, async (url, method = 'GET', payload) => {
    if (url.startsWith('/knowledge-graphs')) return graph()
    if (method === 'POST') { posts++; body = structuredClone(payload); throw new h.RequestError(409, '卡片版本已变化', 8) }
    reads++; return card(url.split('/').at(-1), version)
  })
  const view = h.state(); h.confirm(true)
  Object.assign(view.form, { sourceCardId: '2', targetCardId: '1', type: 'EXAMPLE', evidenceText: '保留人工说明' })
  await view.createRelation(); assert.equal(posts, 0)
  await view.readEndpoints(); assert.equal(reads, 2)
  view.form.targetCardId = '3'; await h.flush()
  assert.equal(view.targetSnapshot, null)
  await view.createRelation(); assert.equal(posts, 0)
  view.form.targetCardId = '1'; await view.readEndpoints(); await view.createRelation(); await h.flush()
  assert.deepEqual(body, { sourceCardId: '2', sourceCardVersion: 2, targetCardId: '1', targetCardVersion: 2,
    type: 'EXAMPLE', evidenceText: '保留人工说明' })
  assert.deepEqual({ ...view.form }, { sourceCardId: '2', targetCardId: '1', type: 'EXAMPLE', evidenceText: '保留人工说明' })
  assert.match(h.text(), /409.*保留.*重新读取两端/)
  assert.equal(reads, 4, '发生冲突不自动读取或覆盖')
  await view.createRelation(); assert.equal(posts, 1)
  version = 8; await view.readEndpoints(); assert.equal(view.sourceSnapshot.version, 8)
  assert.equal(view.form.evidenceText, '保留人工说明')
})

test('创建、删除成功与后续图读取失败分开；删除失败保留原关系并要求确认', async t => {
  let h, written = false, deleteFail = true, deletes = 0, posts = 0
  h = await mount(t, async (url, method = 'GET') => {
    if (url.startsWith('/knowledge-graphs')) { if (written) throw new Error('图重读失败'); return graph() }
    if (method === 'POST') { posts++; written = true; return relation('12', 'RELATED') }
    if (method === 'DELETE') { deletes++; if (deleteFail) throw new Error('删除服务失败'); written = true; return undefined }
    if (url.endsWith('/sources')) return { items: [] }
    return card(url.split('/').at(-1))
  })
  const view = h.state(); Object.assign(view.form, { sourceCardId: '1', targetCardId: '2', evidenceText: '核对过' })
  await view.readEndpoints(); h.confirm(true); await view.createRelation(); await h.flush()
  assert.equal(posts, 1); assert.match(h.text(), /关联已保存/); assert.match(h.text(), /图重读失败/)
  assert.equal(view.createError, ''); assert.equal(view.dirty, false)
  written = false; await view.loadGraph(); await view.selectRelation('9'); await h.flush()
  h.confirm(false); await view.deleteRelation(); assert.equal(deletes, 0)
  h.confirm(true); await view.deleteRelation(); await h.flush()
  assert.equal(view.graph.edges.length, 1); assert.match(h.text(), /删除服务失败/)
  deleteFail = false; await view.deleteRelation(); await h.flush()
  assert.match(h.text(), /关联已删除/); assert.match(h.text(), /图重读失败/)
  assert.equal(view.deleteError, '')
})

test('快照与写入在途锁定表单、选择与路由，未保存说明离开需确认', async t => {
  let hold = '', pending, h, posts = 0
  h = await mount(t, async (url, method = 'GET') => {
    if (url.startsWith('/knowledge-graphs')) return graph()
    if (hold === 'read' || (hold === 'save' && method === 'POST')) { pending = deferred(); return pending.promise }
    if (method === 'POST') { posts++; return relation() }
    if (url.endsWith('/sources')) return { items: [] }
    return card(url.split('/').at(-1))
  })
  const view = h.state(); Object.assign(view.form, { sourceCardId: '1', targetCardId: '2', evidenceText: '尚未保存' })
  await h.router.push('/cards'); assert.equal(h.router.currentRoute.value.path, '/knowledge-graph')
  hold = 'save'; await view.readEndpoints(); h.confirm(true)
  const saving = view.createRelation(); await h.flush()
  const controls = h.all().filter(node => ['input', 'select', 'textarea', 'button'].includes(node.tag))
  assert.ok(controls.length > 8); assert.ok(controls.every(node => node.props.disabled === true), '在途所有相关控件禁用')
  view.changeForm('evidenceText', '不应更新'); assert.equal(view.form.evidenceText, '尚未保存')
  await view.selectCard('3'); assert.equal(view.selection, null)
  await h.router.push('/knowledge-graph?rootCardId=3'); assert.equal(h.router.currentRoute.value.fullPath, '/knowledge-graph')
  await h.router.push('/cards'); assert.equal(h.router.currentRoute.value.path, '/knowledge-graph')
  pending.reject(new Error('写入失败')); await saving; await h.flush()
  assert.equal(view.form.evidenceText, '尚未保存')
  hold = ''; h.confirm(false); await h.router.push('/knowledge-graph?rootCardId=3')
  assert.equal(h.router.currentRoute.value.fullPath, '/knowledge-graph')
  h.confirm(true); await h.router.push('/knowledge-graph?rootCardId=3'); await h.flush()
  assert.equal(h.state().filters.rootCardId, '3'); assert.equal(h.state().form.evidenceText, '')
})

test('选取读取在途不切换对象；账号切换清空旧说明且迟到结果不落入新账号', async t => {
  let hold = false; const waiting = []
  const h = await mount(t, async url => {
    if (url.startsWith('/knowledge-graphs')) return graph()
    if (hold) { const pending = deferred(); waiting.push({ url, ...pending }); return pending.promise }
    if (url.endsWith('/sources')) return { items: [] }
    return card(url.split('/').at(-1))
  })
  const old = h.state(); hold = true
  const selecting = old.selectRelation('9'); await h.flush()
  await old.selectCard('3'); assert.equal(old.selection.kind, 'relation')
  old.form.evidenceText = '甲账号草稿'
  h.session.value = { user: { id: 'bob' }, accessToken: 'other' }; hold = false; await h.flush()
  assert.notEqual(h.state(), old); assert.equal(h.state().form.evidenceText, ''); assert.equal(h.state().selection, null)
  for (const item of waiting) item.resolve(item.url.endsWith('/sources') ? { items: [] } : card(item.url.split('/').at(-1)))
  await selecting; await h.flush()
  assert.equal(h.state().selection, null); assert.equal(h.state().details.length, 0)
})

test('候选关联真实页面保持默认不选，并分别显示三种类型及方向', async t => {
  const relations = ['RELATED', 'PREREQUISITE', 'EXAMPLE'].map((type, i) => ({ relationKey: String(i),
    source: { candidateKey: 'a' }, target: { candidateKey: 'b' }, type, evidenceText: '候选说明' }))
  const candidate = { taskId: '91', resultVersion: 1, sourceVersion: 1, generationMode: 'MOCK', summary: '摘要', tags: [],
    warnings: [], processedPages: [], relations, cards: [{ candidateKey: 'a', title: '起点甲', content: '甲', sources: [] },
      { candidateKey: 'b', title: '终点乙', content: '乙', sources: [] }] }
  const h = await mount(t, async url => url.endsWith('/result') ? candidate : url.endsWith('/sources') ? [] : { status: 'WAIT_CONFIRM' }, '/ai-tasks/91/confirm')
  assert.match(h.text(), /相关.*起点甲.*↔.*终点乙.*无向/)
  assert.match(h.text(), /前置.*起点甲.*是.*终点乙.*前置/)
  assert.match(h.text(), /示例.*终点乙.*是.*起点甲.*示例/)
  const state = h.states.get('TaskConfirmView.vue')
  assert.ok(state.draft.relations.every(item => item.selected === false))
})
