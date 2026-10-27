const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vue = require('../web/node_modules/vue')
const vueRouter = require('../web/node_modules/vue-router')
const { parse, compileScript, compileTemplate } = require('../web/node_modules/@vue/compiler-sfc')
const ts = require('../web/node_modules/typescript')
const identity = (id = '1', token = 'admin-token', role = 'ADMIN') => ({ user: { id, role }, accessToken: token })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const statuses = ['QUEUED', 'RUNNING', 'WAIT_CONFIRM', 'SUCCEEDED', 'FAILED', 'CANCELLED']
const sample = (overrides = {}) => ({ from: '2026-10-20T16:00:00Z', to: '2026-10-27T16:00:00Z', timezone: 'Asia/Shanghai',
  activeMaterialCount: 12, restrictedMaterialCount: 2, subjects: [{ key: '<img src=x onerror=alert(1)>', count: 7 }, { key: '未分类', count: 5 }],
  uploadTrend: [{ date: '2026-10-21', count: 0 }, { date: '2026-10-22', count: 4 }], modelAttemptCount: 9, ocrAttemptCount: 3,
  retryAttemptCount: 2, unknownRetryAttempts: 1, taskStatuses: statuses.map((key, index) => ({ key, count: index })),
  taskCompletionRate: 0.6, candidateTaskCount: 8, knownCosts: [{ currency: 'CNY', amount: '0.000000', knownAttempts: 1 }, { currency: 'USD', amount: '2.250000', knownAttempts: 2 }],
  unknownCostAttempts: 9, metricNotes: ['候选包含 MOCK 及后来失效的合格结果。'], ...overrides })
const empty = () => sample({ activeMaterialCount: 0, restrictedMaterialCount: 0, subjects: [],
  uploadTrend: [{ date: '2026-10-21', count: 0 }], modelAttemptCount: 0, ocrAttemptCount: 0, retryAttemptCount: 0,
  unknownRetryAttempts: 0, taskStatuses: statuses.map(key => ({ key, count: 0 })), taskCompletionRate: null,
  candidateTaskCount: 0, knownCosts: [], unknownCostAttempts: 0 })

function loadModule(filename, dependencies = {}) {
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => dependencies[name] || {}, module, module.exports)
  return module.exports
}

async function mount(t, request, options = {}) {
  assert.ok(fs.existsSync(path.join(__dirname, '../web/src/views/AdminStatisticsView.vue')), '应实现统计大屏实际SFC')
  const cache = new Map(), states = new Map(), chartCalls = [], observers = [], loaded = []
  const previous = { window: global.window, Document: global.Document, ShadowRoot: global.ShadowRoot, ResizeObserver: global.ResizeObserver }
  global.Document = class {}; global.ShadowRoot = class {}; global.window = {}
  global.ResizeObserver = class { constructor(callback) { this.callback = callback; observers.push(this) }
    observe(target) { this.target = target } disconnect() { this.disconnected = true } }
  let app
  const session = vue.ref(options.session === undefined ? identity() : options.session)
  class RequestError extends Error { constructor(status, message) { super(message); this.status = status } }
  t.after(() => { app?.unmount(); Object.assign(global, previous) })
  function load(filename) {
    if (cache.has(filename)) return cache.get(filename)
    loaded.push(filename)
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
      if (specifier === 'echarts/core') return { use() {}, init(target, theme, config) {
        const record = { target, config, options: [], resizes: 0, disposed: false }; chartCalls.push(record)
        return { setOption(value) { record.options.push(value) }, resize() { record.resizes++ }, dispose() { record.disposed = true } }
      } }
      if (specifier.startsWith('echarts/')) return new Proxy({}, { get: (_, key) => key })
      if (specifier.endsWith('.vue') && !/Statistics|App.vue|ForbiddenView/.test(specifier)) return { render: () => vue.h('div') }
      if (specifier.startsWith('.')) {
        const target = path.resolve(path.dirname(filename), specifier)
        return load(target.endsWith('.vue') ? target : `${target}.ts`)
      }
      throw new Error(`未预期的统计依赖：${specifier}`)
    }, module, module.exports)
    if (template) {
      const rendered = { exports: {} }
      new Function('require', 'module', 'exports', compile(template))(() => vue, rendered, rendered.exports)
      module.exports.default.render = function (...args) {
        states.set(path.basename(filename), vue.getCurrentInstance().setupState)
        return rendered.exports.render.apply(this, args)
      }
    }
    cache.set(filename, module.exports); return module.exports
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
  await router.push(options.path || '/admin/statistics'); await router.isReady()
  const root = { children: [] }; app.mount(root)
  const all = (node = root) => [node, ...(node.children || []).flatMap(child => all(child))]
  const flush = async () => { for (let i = 0; i < 32; i++) { await Promise.resolve(); await vue.nextTick() } }
  await flush()
  return { router, all, flush, session, RequestError, chartCalls, observers, loaded,
    state: () => states.get('AdminStatisticsView.vue'), text: () => all().map(node => node.text || '').join(' '),
    unmount() { app.unmount(); app = null }, link: () => all().find(node => node.tag === 'a' && node.props.href === '/admin/statistics') }
}

test('ADMIN统计入口按需加载；普通账号或匿名不会加载页面或请求统计', async t => {
  let reads = 0
  const h = await mount(t, async () => { reads++; return sample() }, { session: identity('2', 'user', 'USER') })
  assert.equal(h.link(), undefined); assert.equal(reads, 0)
  assert.equal(h.router.currentRoute.value.name, 'forbidden')
  assert.equal(h.loaded.some(file => /AdminStatisticsView/.test(file)), false)
  h.session.value = null; await h.router.push('/admin/statistics'); await h.flush()
  assert.equal(h.router.currentRoute.value.name, 'login'); assert.equal(reads, 0)
})

test('默认请求显式上海7个自然日与UTC Z参数；展示后端口径、原金额和安全文本表', async t => {
  const reads = []
  const h = await mount(t, async url => { reads.push(url); return sample() })
  assert.ok(h.link()); assert.equal(h.router.currentRoute.value.meta.requiresRole, 'ADMIN')
  const query = new URLSearchParams(reads[0].split('?')[1])
  assert.equal(query.get('timezone'), 'Asia/Shanghai')
  assert.match(query.get('from'), /T16:00:00(?:\.000)?Z$/); assert.match(query.get('to'), /T16:00:00(?:\.000)?Z$/)
  assert.equal((Date.parse(query.get('to')) - Date.parse(query.get('from'))) / 86400000, 7)
  assert.match(h.text(), /60%/); assert.match(h.text(), /CNY/); assert.match(h.text(), /0\.000000/)
  assert.match(h.text(), /USD/); assert.match(h.text(), /2\.250000/); assert.match(h.text(), /历史归属未知/)
  assert.match(h.text(), /当前库存不受日期筛选/); assert.match(h.text(), /MOCK/)
  assert.match(h.text(), /<img src=x onerror=alert\(1\)>/); assert.equal(h.all().some(node => node.tag === 'img'), false)
  assert.equal(h.chartCalls.length, 3); assert.ok(h.chartCalls.every(chart => chart.config.renderer === 'svg'))
  for (const chart of h.chartCalls) { const option = chart.options.at(-1); assert.equal(option.tooltip.renderMode, 'richText'); assert.equal(option.aria.enabled, true) }
})

test('日期按上海日期计算，不受运行设备时区影响，跨月跨年边界正确', async () => {
  const filename = path.join(__dirname, '../web/src/statistics.ts')
  assert.ok(fs.existsSync(filename), '应提供上海日期转换')
  const { defaultStatisticsDates, statisticsRange } = loadModule(filename)
  assert.deepEqual(defaultStatisticsDates(new Date('2026-12-31T16:30:00Z'), 7), { from: '2026-12-26', to: '2027-01-02' })
  assert.deepEqual(statisticsRange('2026-12-31', '2027-01-02'), { from: '2026-12-30T16:00:00.000Z', to: '2027-01-01T16:00:00.000Z', timezone: 'Asia/Shanghai' })
  assert.throws(() => statisticsRange('2026-02-30', '2026-03-03'), /有效日期/)
  assert.throws(() => statisticsRange('', '2026-03-03'), /日期/)
})

test('上海历史夏令时日期按该日时区偏移转换为UTC', () => {
  const { statisticsRange } = loadModule(path.join(__dirname, '../web/src/statistics.ts'))
  assert.deepEqual(statisticsRange('1991-07-01', '1991-07-02'), {
    from: '1991-06-30T15:00:00.000Z', to: '1991-07-01T15:00:00.000Z', timezone: 'Asia/Shanghai',
  })
  assert.deepEqual(statisticsRange('1991-09-15', '1991-09-16'), {
    from: '1991-09-14T15:00:00.000Z', to: '1991-09-15T16:00:00.000Z', timezone: 'Asia/Shanghai',
  })
})

test('日期草稿不会冒充已应用范围；查询清空旧数据并禁止重复，刷新沿用已应用范围', async t => {
  const pending = deferred(), reads = []
  const h = await mount(t, async url => { reads.push(url); return reads.length === 2 ? pending.promise : sample() })
  const s = h.state(); s.fromDate = '2026-10-01'; s.toDate = '2026-10-04'
  assert.equal(s.appliedRange.from, sample().from)
  const querying = s.applyRange(); await h.flush(); assert.equal(s.statistics, null); assert.match(h.text(), /正在读取统计/)
  await s.applyRange(); await s.load(); assert.equal(reads.length, 2)
  pending.resolve(sample({ from: '2026-09-30T16:00:00Z', to: '2026-10-03T16:00:00Z' })); await querying; await h.flush()
  assert.equal(new URLSearchParams(reads[1].split('?')[1]).get('from'), '2026-09-30T16:00:00.000Z')
  s.fromDate = '2026-09-01'; await s.load(); assert.equal(reads[2], reads[1])
})

test('日期必填、正向且最多90日；快捷近30日显式提交，不发送非法范围', async t => {
  const reads = []; const h = await mount(t, async url => { reads.push(url); return sample() }); const s = h.state()
  for (const [from, to] of [['', '2026-10-02'], ['2026-10-02', '2026-10-02'], ['2026-10-03', '2026-10-02'], ['2026-01-01', '2026-04-02']]) {
    s.fromDate = from; s.toDate = to; await s.applyRange(); assert.ok(s.error); assert.equal(reads.length, 1)
  }
  s.fromDate = '2026-01-01'; s.toDate = '2026-04-01'; await s.applyRange(); assert.equal(reads.length, 2)
  await s.preset(30); const query = new URLSearchParams(reads.at(-1).split('?')[1])
  assert.equal((Date.parse(query.get('to')) - Date.parse(query.get('from'))) / 86400000, 30)
})

test('空数据不画假扇区，完成率null与费用空数组明确显示未知状态', async t => {
  const h = await mount(t, async () => empty())
  assert.match(h.text(), /暂无可计算任务/); assert.match(h.text(), /未提供/)
  assert.match(h.text(), /暂无上传事件/); assert.match(h.text(), /暂无当前资料/); assert.match(h.text(), /暂无任务/)
  assert.equal(h.chartCalls.length, 0); assert.doesNotMatch(h.text(), /NaN|0%/)
  assert.equal(h.all().filter(node => node.tag === 'table').length, 3)
})

test('500明确错误并撤下旧数据，重试恢复；空统计与读取失败分开', async t => {
  let fail = false; const h = await mount(t, async () => { if (fail) throw new Error('数据库统计读取失败'); return sample() })
  const s = h.state(); fail = true; await s.load(); await h.flush()
  assert.equal(s.statistics, null); assert.match(h.text(), /数据库统计读取失败/); assert.doesNotMatch(h.text(), /暂无任务|60%/)
  fail = false; await s.load(); await h.flush(); assert.match(h.text(), /60%/); assert.equal(s.error, '')
})

for (const status of [401, 403]) test(`当前统计${status}清除数据并离开页面`, async t => {
  let h, fail = false; h = await mount(t, async () => { if (fail) throw new h.RequestError(status, '权限失败'); return sample() })
  const s = h.state(); fail = true; await s.load(); await h.flush()
  assert.equal(s.statistics, null); assert.equal(h.router.currentRoute.value.name, status === 401 ? 'login' : 'forbidden')
  assert.equal(Boolean(h.session.value), status !== 401)
})

for (const outcome of ['success', 401, 403]) test(`换号和离页后迟到${outcome}不能恢复旧数据或破坏新会话`, async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async () => ++reads === 2 ? pending.promise : sample())
  const old = h.state(), loading = old.load(); h.session.value = identity('4', 'next-admin'); await h.flush()
  assert.equal(old.statistics, null)
  if (outcome === 'success') pending.resolve(sample()); else pending.reject(new h.RequestError(outcome, '旧权限失败'))
  await loading; await h.flush(); assert.equal(old.statistics, null); assert.equal(h.session.value.accessToken, 'next-admin')
  assert.equal(h.router.currentRoute.value.path, '/admin/statistics')
  const current = h.state(); await h.router.push('/cards'); await h.flush(); assert.equal(current.statistics, null)
})

test('角色降级同步清除统计并隐藏导航，不继续请求', async t => {
  let reads = 0; const h = await mount(t, async () => { reads++; return sample() })
  const s = h.state(); h.session.value = identity('1', 'admin-token', 'USER'); await h.flush()
  assert.equal(s.statistics, null); assert.equal(h.link(), undefined); assert.equal(h.router.currentRoute.value.name, 'forbidden'); assert.equal(reads, 1)
})

for (const outcome of ['success', 401, 403]) test(`路由更新后迟到${outcome}不能恢复旧数据或改变当前页面`, async t => {
  const pending = deferred(); let reads = 0
  const h = await mount(t, async () => ++reads === 2 ? pending.promise : sample())
  const old = h.state(), loading = old.load()
  await h.router.push('/admin/statistics?source=menu'); await h.flush()
  assert.equal(old.statistics, null); assert.ok(h.state().statistics)
  if (outcome === 'success') pending.resolve(sample()); else pending.reject(new h.RequestError(outcome, '旧路由失败'))
  await loading; await h.flush(); assert.equal(old.statistics, null); assert.ok(h.state().statistics)
  assert.equal(h.router.currentRoute.value.fullPath, '/admin/statistics?source=menu'); assert.equal(h.session.value.accessToken, 'admin-token')
})

test('图表基于全部真实数据更新，ResizeObserver触发resize且卸载dispose与disconnect', async t => {
  const many = Array.from({ length: 24 }, (_, index) => ({ key: `学科${index}`, count: 24 - index }))
  const h = await mount(t, async () => sample({ subjects: many }))
  const subject = h.chartCalls.find(chart => chart.options.at(-1).series[0].type === 'bar')
  assert.equal(subject.options.at(-1).series[0].data.length, 24)
  assert.equal(h.all().filter(node => node.tag === 'th' && /^学科\d/.test(node.text || '')).length, 24)
  h.state().statistics.subjects = [{ key: '更新学科', count: 91 }]; await h.flush()
  assert.deepEqual(subject.options.at(-1).series[0].data, [91]); assert.equal(subject.options.at(-1).yAxis.data[0], '更新学科')
  assert.equal(h.chartCalls.length, 3)
  for (const observer of h.observers) observer.callback([{ contentRect: { width: 400, height: 280 } }])
  assert.ok(h.chartCalls.every(chart => chart.resizes === 1)); h.unmount()
  assert.ok(h.chartCalls.every(chart => chart.disposed)); assert.ok(h.observers.every(observer => observer.disconnected))
})
