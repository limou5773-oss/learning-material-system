const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')

const token = 'k'.repeat(43)
const storage = new Map([['lms.accessToken.v1', token]])
let definition, requestHandler, navigation
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key), request: options => requestHandler(options),
  navigateTo: options => { navigation = options.url }, redirectTo: options => { navigation = options.url },
  reLaunch: options => { navigation = options.url }, showModal: options => options.success({ confirm: true }),
}
function page(name) {
  const filename = `../miniprogram/pages/cards/${name}.js`
  assert.ok(fs.existsSync(require('node:path').join(__dirname, filename)), '卡片编辑页面应已实现')
  delete require.cache[require.resolve(filename)]
  require(filename)
  const instance = { ...definition, data: structuredClone(definition.data) }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}
const card = { id: '42', title: '原卡片', content: '原正文', summary: '原摘要', subject: '数学', tags: ['重点'],
  version: 1, origin: 'MANUAL', generationMode: 'MANUAL', isEdited: false, favorite: false }
function ok(options, data) { options.success({ statusCode: 200, data: { data } }) }
function fail(options, status, message, currentVersion) {
  options.success({ statusCode: status, data: { error: { code: 'VERSION_CONFLICT', message, currentVersion } } })
}
function setForm(editor, overrides = {}) {
  editor.setData({ title: '新标题', content: '新正文\n保留换行', summary: '新摘要', subject: '语文', tagsText: '重点，新标签', changed: true, ...overrides })
}
function editorRequests(options) { ok(options, options.url.endsWith('/users/me') ? { id: 'alice' } : card) }

test('Web卡片表单按契约校验、保留正文并构造五字段载荷', async () => {
  const web = await import('../web/src/knowledge.ts')
  assert.equal(typeof web.cardPayload, 'function')
  const form = { title: ' 标题 ', content: '\n 正文\n', summary: ' 摘要 ', subject: ' 数学 ', tagsText: '重点，复习' }
  assert.deepEqual(web.cardPayload(form), { title: '标题', content: '\n 正文\n', summary: '摘要', subject: '数学', tags: ['重点', '复习'] })
  for (const [key, value] of [['title', ' '], ['title', 'a'.repeat(256)], ['content', ' '], ['content', 'a'.repeat(8001)],
    ['summary', 'a'.repeat(2001)], ['subject', 'a'.repeat(65)], ['tagsText', 'a'.repeat(65)], ['tagsText', '重，重'],
    ['tagsText', Array.from({ length: 11 }, (_, i) => String(i)).join(',')]]) {
    assert.throws(() => web.cardPayload({ ...form, [key]: value }), key)
  }
})

test('Web草稿键按本人和卡片隔离，拒绝不完整或非法版本草稿', async () => {
  const web = await import('../web/src/knowledge.ts')
  assert.equal(typeof web.cardDraftKey, 'function')
  assert.notEqual(web.cardDraftKey('alice', '42'), web.cardDraftKey('bob', '42'))
  assert.notEqual(web.cardDraftKey('alice', '42'), web.cardDraftKey('alice', '43'))
  const form = { title: '草稿', content: '正文', summary: '', subject: '', tagsText: '' }
  assert.deepEqual(web.readCardDraft(JSON.stringify({ version: 1, form }), true), { version: 1, form })
  assert.equal(web.readCardDraft(JSON.stringify({ version: 1, form: { title: '旧草稿' } }), true), null)
  assert.equal(web.readCardDraft(JSON.stringify({ version: 0, form }), true), null)
  assert.equal(web.readCardDraft('{broken', true), null)
})

test('Web只改标题保留原标签边界，明确编辑标签文本后按逗号分隔', async () => {
  const web = await import('../web/src/knowledge.ts')
  const original = { ...card, tags: ['a,b', '中文，标签'] }
  const form = { ...web.cardForm(original), title: '只改标题' }
  assert.deepEqual(web.cardPayload(form, original.tags).tags, original.tags)
  assert.deepEqual(web.cardPayload({ ...form, tagsText: '新标签，复习,测试' }, original.tags).tags,
    ['新标签', '复习', '测试'])
})

test('小程序只改标题及恢复同版草稿时，实际PATCH保留含逗号标签', async t => {
  t.after(() => { storage.clear(); storage.set('lms.accessToken.v1', token) })
  storage.clear(); storage.set('lms.accessToken.v1', token)
  const original = { ...card, tags: ['a,b', '中文，标签'] }
  requestHandler = options => ok(options, options.url.endsWith('/users/me') ? { id: 'alice' } : original)
  const first = page('editor'); first.onLoad({ id: '42' }); await first.load()
  first.onTitleInput({ detail: { value: '只改标题' } })
  const restored = page('editor'); restored.onLoad({ id: '42' }); await restored.load()
  assert.equal(restored.data.restored, true)
  assert.equal(restored.data.title, '只改标题')
  let payload
  requestHandler = options => {
    assert.equal(options.method, 'PATCH')
    payload = options.data
    ok(options, { ...original, title: '只改标题', version: 2 })
  }
  await restored.submit()
  assert.deepEqual(payload.tags, original.tags)
  assert.equal(payload.title, '只改标题')
  assert.equal(restored.data.saved, true)
})

test('Web筛选构造编码字面量关键词，翻页保留组合条件', async () => {
  const web = await import('../web/src/knowledge.ts')
  assert.equal(typeof web.cardListPath, 'function')
  const url = new URL(web.cardListPath({ keyword: ' %_引号" ', subject: ' 数学 ', tag: '重点', favoriteOnly: true }, 3), 'https://test')
  assert.equal(url.pathname, '/cards')
  assert.deepEqual(Object.fromEntries(url.searchParams), { keyword: '%_引号"', subject: '数学', tag: '重点', favoriteOnly: 'true', page: '3', pageSize: '20' })
})

test('双端区分人工创建与AI人工修改，来源声明为创建时历史依据', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const presenter of [web, mini]) {
    assert.equal(presenter.savedGenerationLabel('MANUAL'), '人工创建')
    assert.match(presenter.savedGenerationWarning('MANUAL'), /人工创建.*无自动来源/)
    assert.match(presenter.savedGenerationLabel('MODEL', true), /已人工修改/)
    assert.match(presenter.savedGenerationWarning('MODEL', true), /人工修改.*创建时.*历史依据/)
    assert.match(presenter.savedGenerationWarning('MOCK', true), /模拟.*人工修改.*创建时/)
  }
})

test('小程序人工创建只提交五字段，成功清除本人新建草稿并显示详情', async () => {
  const editor = page('editor'); editor.onLoad({})
  requestHandler = options => {
    if (options.method === 'POST') {
      assert.deepEqual(options.data, { title: '新标题', content: '新正文\n保留换行', summary: '新摘要', subject: '语文', tags: ['重点', '新标签'] })
      options.success({ statusCode: 201, data: { data: card } })
    } else editorRequests(options)
  }
  await editor.load(); setForm(editor); editor.persistDraft()
  const draftKeys = [...storage.keys()].filter(key => key.startsWith('lms.cardDraft.'))
  assert.equal(draftKeys.length, 1)
  assert.match(draftKeys[0], /alice.*new/)
  await editor.submit()
  assert.equal(storage.has(draftKeys[0]), false)
  assert.equal(navigation, '/pages/cards/detail?id=42')
})

test('小程序卡片字段边界在请求前拒绝并保留所有输入', async () => {
  const editor = page('editor'); editor.onLoad({}); requestHandler = editorRequests; await editor.load()
  for (const invalid of [{ title: ' ' }, { title: 'a'.repeat(256) }, { content: 'a'.repeat(8001) }, { summary: 'a'.repeat(2001) },
    { subject: 'a'.repeat(65) }, { tagsText: '重复，重复' }, { tagsText: 'a'.repeat(65) },
    { tagsText: Array.from({ length: 11 }, (_, i) => String(i)).join(',') }]) {
    setForm(editor, invalid)
    requestHandler = () => assert.fail('校验失败不应请求服务器')
    await editor.submit()
    assert.ok(editor.data.error)
    for (const [key, value] of Object.entries(invalid)) assert.equal(editor.data[key], value)
  }
})

test('小程序409保留五字段和原版本，服务器读取失败可显式重试再放弃', async () => {
  const editor = page('editor'); editor.onLoad({ id: '42' }); requestHandler = editorRequests; await editor.load(); setForm(editor)
  let reads = 0
  requestHandler = options => {
    if (options.method === 'PATCH') { assert.equal(options.data.version, 1); fail(options, 409, '已有新版本', 2) }
    else { reads++; fail(options, 503, '读取暂时失败') }
  }
  await editor.submit()
  assert.equal(editor.data.conflict, true)
  assert.equal(editor.data.currentVersion, 2)
  assert.equal(editor.data.editingVersion, 1)
  assert.equal(editor.data.content, '新正文\n保留换行')
  assert.equal(editor.data.summary, '新摘要')
  assert.match(editor.data.serverError, /读取暂时失败/)
  await editor.submit(); assert.equal(reads, 1)
  requestHandler = options => ok(options, { ...card, version: 2, title: '服务器标题', content: '服务器正文' })
  await editor.loadServerCopy()
  assert.equal(editor.data.title, '新标题')
  editor.discardDraft()
  assert.equal(editor.data.title, '服务器标题')
  assert.equal(editor.data.editingVersion, 2)
  assert.equal(editor.data.conflict, false)
})

test('小程序草稿拒绝残缺形状且账号切换不会恢复他人草稿', async () => {
  storage.clear(); storage.set('lms.accessToken.v1', token)
  let who = 'alice'
  requestHandler = options => ok(options, options.url.endsWith('/users/me') ? { id: who } : card)
  const alice = page('editor'); alice.onLoad({ id: '42' }); await alice.load(); setForm(alice); alice.persistDraft()
  who = 'bob'
  const bob = page('editor'); bob.onLoad({ id: '42' }); await bob.load()
  assert.equal(bob.data.title, card.title)
  bob.setData({ title: 'Bob草稿', changed: true }); bob.persistDraft()
  const bobKey = [...storage.keys()].find(key => key.includes('bob'))
  storage.set(bobKey, { version: 1, form: { title: '残缺' } })
  const refreshed = page('editor'); refreshed.onLoad({ id: '42' }); await refreshed.load()
  assert.equal(refreshed.data.title, card.title)
})

test('小程序筛选提交回第一页，分页保留已应用条件，失败清空旧结果', async () => {
  const list = page('index'); list.setData({ page: 4, items: [card], total: 100 })
  assert.equal(typeof list.search, 'function')
  list.setData({ keyword: '%_', subject: '数学', tag: '重点', favoriteOnly: true })
  const paths = []
  requestHandler = options => { paths.push(new URL(options.url)); ok(options, { items: [card], total: 60 }) }
  await list.search()
  assert.equal(list.data.page, 1)
  list.setData({ keyword: '尚未提交' }); await list.next()
  assert.equal(paths[1].searchParams.get('keyword'), '%_')
  assert.equal(paths[1].searchParams.get('page'), '2')
  assert.equal(paths[1].searchParams.get('favoriteOnly'), 'true')
  requestHandler = options => fail(options, 503, '筛选失败')
  await list.search()
  assert.equal(list.data.items.length, 0)
  assert.equal(list.data.total, 0)
  assert.match(list.data.error, /筛选失败/)
})

test('小程序收藏幂等入口不改内容版本，失败不伪装成功并阻止重复请求', async () => {
  const detail = page('detail'); detail.onLoad({ id: '42' }); detail.setData({ card })
  assert.equal(typeof detail.toggleFavorite, 'function')
  let pending, count = 0
  requestHandler = options => { count++; pending = options }
  const first = detail.toggleFavorite(); await detail.toggleFavorite()
  assert.equal(count, 1); assert.match(pending.url, /\/favorites\/CARD\/42$/); assert.equal(pending.method, 'PUT')
  pending.success({ statusCode: 204 }); await first
  assert.equal(detail.data.card.favorite, true); assert.equal(detail.data.card.version, 1)
  requestHandler = options => { assert.equal(options.method, 'DELETE'); fail(options, 503, '收藏失败') }
  await detail.toggleFavorite()
  assert.equal(detail.data.card.favorite, true); assert.match(detail.data.actionError, /收藏失败/)
})

test('小程序成功编辑发送原版本且清除已保存草稿', async () => {
  requestHandler = editorRequests
  const editor = page('editor'); editor.onLoad({ id: '43' }); await editor.load(); setForm(editor); editor.persistDraft()
  requestHandler = options => {
    assert.equal(options.method, 'PATCH'); assert.equal(options.data.version, 1)
    ok(options, { ...card, id: '43', version: 2, isEdited: true })
  }
  await editor.submit()
  assert.equal(editor.data.saved, true)
  assert.equal([...storage.keys()].some(key => key.startsWith('lms.cardDraft.') && key.endsWith('.43')), false)
})

test('小程序在途保存锁定五字段，失败后保留输入并可继续编辑', async () => {
  requestHandler = editorRequests
  const editor = page('editor'); editor.onLoad({ id: '42' }); await editor.load(); setForm(editor)
  let pending
  requestHandler = options => { pending = options }
  const saving = editor.submit()
  for (const [handler, field] of [['onTitleInput', 'title'], ['onContentInput', 'content'], ['onSummaryInput', 'summary'],
    ['onSubjectInput', 'subject'], ['onTagsInput', 'tagsText']]) {
    const before = editor.data[field]
    editor[handler]({ detail: { value: '保存期间的新输入' } })
    assert.equal(editor.data[field], before, `${field}在保存期间应锁定`)
  }
  fail(pending, 503, '保存失败'); await saving
  assert.equal(editor.data.saved, false)
  editor.onContentInput({ detail: { value: '失败后继续修改' } })
  assert.equal(editor.data.content, '失败后继续修改')
})

test('Web真实编辑路由切换卡片时确认草稿、重建状态并隔离迟到加载', async t => {
  const path = require('node:path')
  const vue = require('../web/node_modules/vue')
  const vueRouter = require('../web/node_modules/vue-router')
  const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
  const ts = require('../web/node_modules/typescript')
  const editors = [], drafts = new Map(), reads = [], patches = [], confirmations = []
  const previousWindow = global.window, previousStorage = global.sessionStorage
  const previousDocument = global.Document, previousShadowRoot = global.ShadowRoot
  global.Document = class {}; global.ShadowRoot = class {}
  let allowLeave = false, lateLoad, app, holdSave = false, finishSave
  global.window = { addEventListener() {}, removeEventListener() {}, confirm: message => {
    confirmations.push(message); return allowLeave
  } }
  global.sessionStorage = { getItem: key => drafts.get(key) || null,
    setItem: (key, value) => drafts.set(key, value), removeItem: key => drafts.delete(key) }
  t.after(() => { app?.unmount(); global.window = previousWindow; global.sessionStorage = previousStorage
    global.Document = previousDocument; global.ShadowRoot = previousShadowRoot })
  const auth = { session: vue.ref({ user: { id: 'alice' } }), clearSession() {} }
  const api = { ApiRequestError: class extends Error {}, api: async (url, method = 'GET', body) => {
    if (method === 'PATCH') {
      patches.push({ url, body })
      if (holdSave) return new Promise(resolve => { finishSave = resolve })
      return { ...card, id: url.split('/').at(-1) }
    }
    reads.push(url)
    if (url === '/cards/44') return new Promise(resolve => { lateLoad = resolve })
    return { ...card, id: url.split('/').at(-1), title: `卡片${url.split('/').at(-1)}`, tags: ['a,b', '中文，标签'] }
  } }
  const cache = new Map()
  function loadWebFile(filename) {
    if (cache.has(filename)) return cache.get(filename)
    let source = fs.readFileSync(filename, 'utf8'), template
    if (filename.endsWith('.vue')) {
      const descriptor = parse(source).descriptor
      const script = compileScript(descriptor, { id: filename, inlineTemplate: filename.endsWith('CardEditorRoute.vue') })
      source = script.content
      if (filename.endsWith('CardEditorView.vue')) template = compileTemplate({ source: descriptor.template.content,
        filename, id: filename, compilerOptions: { bindingMetadata: script.bindings } }).code
    }
    const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
    const module = { exports: {} }
    new Function('require', 'module', 'exports', output)(specifier => {
      if (specifier === 'vue') return vue
      if (specifier === 'vue-router') return { ...vueRouter, createWebHistory: vueRouter.createMemoryHistory }
      if (specifier.endsWith('/auth')) return auth
      if (specifier.endsWith('/api')) return api
      if (specifier.endsWith('/knowledge')) return loadWebFile(path.join(__dirname, '../web/src/knowledge.ts'))
      if (specifier.endsWith('.vue')) {
        if (/CardEditor(View|Route)\.vue$/.test(specifier)) return loadWebFile(path.resolve(path.dirname(filename), specifier))
        return { render: () => vue.h('div') }
      }
      throw new Error(`未预期的编辑器依赖：${specifier}`)
    }, module, module.exports)
    if (template) {
      const rendered = { exports: {} }
      new Function('require', 'module', 'exports', ts.transpileModule(template,
        { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(() => vue, rendered, rendered.exports)
      module.exports.default.render = function (...args) {
        editors.push(vue.getCurrentInstance().setupState)
        return rendered.exports.render.apply(this, args)
      }
    }
    cache.set(filename, module.exports)
    return module.exports
  }
  const router = loadWebFile(path.join(__dirname, '../web/src/router.ts')).default
  const renderer = vue.createRenderer({
    createElement: tag => ({ tag, children: [], props: {}, addEventListener() {}, removeEventListener() {}, getRootNode: () => ({}) }),
    createText: text => ({ text }), createComment: text => ({ text }),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text },
    patchProp(node, key, oldValue, value) { node.props[key] = value },
    insert(node, parent, anchor) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, node) },
    remove(node) { const siblings = node.parent.children; siblings.splice(siblings.indexOf(node), 1); node.parent = null },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
  })
  app = renderer.createApp({ render: () => vue.h(vueRouter.RouterView) })
  app.use(router)
  await router.push('/cards/42/edit'); await router.isReady()
  const root = { children: [] }
  app.mount(root)
  const fields = node => [node, ...(node.children || []).flatMap(fields)].filter(item => ['input', 'textarea'].includes(item.tag))
  const flush = async () => { await Promise.resolve(); await vue.nextTick(); await Promise.resolve(); await vue.nextTick() }
  await flush()
  const first = editors.at(-1)
  first.form.title = '42的未保存草稿'; await vue.nextTick()
  await router.push('/cards/43/edit'); await flush()
  assert.equal(router.currentRoute.value.params.id, '42', '取消切换应留在原卡片')
  assert.equal(confirmations.length, 1)
  allowLeave = true
  await router.push('/cards/43/edit'); await flush()
  const second = editors.at(-1)
  assert.notEqual(second, first)
  assert.equal(second.form.title, '卡片43')
  assert.equal(JSON.parse(drafts.get('lms.cardDraft.v1.alice.42')).form.title, '42的未保存草稿')
  second.form.title = '43的新标题'; holdSave = true
  const saving = second.submit(); await flush()
  assert.equal(fields(root).length, 5)
  assert.ok(fields(root).every(node => node.props.disabled === true), '请求期间五字段必须禁用，不能丢失后续输入')
  await router.push('/cards/46/edit'); await flush()
  assert.equal(router.currentRoute.value.fullPath, '/cards/43/edit', '保存尚未完成时应阻止切换卡片')
  finishSave({ ...card, id: '43' }); await saving; await flush()
  assert.equal(router.currentRoute.value.fullPath, '/cards/43', '保存成功后应能跳转本卡详情')
  assert.equal(patches.at(-1).url, '/cards/43')
  assert.deepEqual(patches.at(-1).body.tags, ['a,b', '中文，标签'])
  await router.push('/cards/44/edit'); await flush()
  await router.push('/cards/45/edit'); await flush()
  lateLoad({ ...card, id: '44', title: '迟到的44' }); await flush()
  assert.equal(editors.at(-1).form.title, '卡片45')
  assert.ok(reads.includes('/cards/44') && reads.includes('/cards/45'))
  await router.push('/cards/42/edit'); await flush()
  assert.equal(editors.at(-1).form.title, '42的未保存草稿')
  assert.equal(editors.at(-1).restored, true)
})
