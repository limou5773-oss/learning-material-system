const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const token = 'r'.repeat(43)
const storage = new Map()
let definition, handler, destination
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key), request: options => handler(options),
  reLaunch: options => { destination = options.url }, navigateTo: options => { destination = options.url },
}
function reset() { storage.clear(); storage.set('lms.accessToken.v1', token); destination = '' }
function page(name = 'cards/relations') {
  const file = path.join(__dirname, '../miniprogram/pages', `${name}.js`)
  assert.ok(fs.existsSync(file), '已确认关联查阅页面尚未实现')
  delete require.cache[require.resolve(file)]; require(file)
  const value = { ...definition, data: structuredClone(definition.data) }
  value.setData = patch => Object.assign(value.data, patch)
  return value
}
const relation = (id = '91', type = 'RELATED', source = '11', target = '12') => ({
  id, sourceCardId: source, targetCardId: target, type, evidenceText: '人工核对后保留的关联说明',
})
function ok(options, data) { options.success({ statusCode: 200, data: { data } }) }
function installed(rows = [relation()], total = rows.length) {
  handler = options => {
    assert.equal(options.header.Authorization, `Bearer ${storage.get('lms.accessToken.v1')}`)
    assert.equal(options.method, 'GET', '只读关联页不能写入关系')
    const url = new URL(options.url)
    if (url.pathname.endsWith('/card-relations')) {
      ok(options, { items: rows, total, page: Number(url.searchParams.get('page')), pageSize: 20 })
    } else if (url.pathname.endsWith('/sources')) {
      ok(options, { items: [{ chunkId: '7', materialId: '31', materialTitle: '来源笔记',
        materialVersion: 2, pageNo: 3, extractionMethod: 'OCR', qualityNote: '请核对识别文字',
        quoteText: '引用', content: '完整来源', isAvailable: true }] })
    } else {
      const id = url.pathname.split('/').pop()
      ok(options, { id, title: `服务端卡片${id}`, origin: 'AI', generationMode: 'MOCK', tags: [] })
    }
  }
}
function click(id) { return { currentTarget: { dataset: { id } } } }

test('小程序卡片详情提供本人已确认关联入口，并注册只读页', () => {
  reset()
  const detail = page('cards/detail')
  detail.onLoad({ id: '9223372036854775806' })
  detail.setData({ card: { id: '9223372036854775806' } })
  assert.equal(typeof detail.openRelations, 'function', '卡片详情缺少关联入口')
  detail.openRelations()
  assert.equal(destination, '/pages/cards/relations?cardId=9223372036854775806')
  const app = JSON.parse(fs.readFileSync(path.join(__dirname, '../miniprogram/app.json'), 'utf8'))
  assert.ok(app.pages.includes('pages/cards/relations'))
  assert.match(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/cards/detail.wxml'), 'utf8'), /bindtap="openRelations"/)
})

test('只读页按原卡片ID鉴权读取分页关系，并展示类型、方向与人工确认说明', async () => {
  reset(); installed([relation(), relation('92', 'PREREQUISITE'), relation('93', 'EXAMPLE')], 41)
  const requests = []; const serve = handler
  handler = options => { requests.push(new URL(options.url)); serve(options) }
  const view = page(); view.onLoad({ cardId: '11' }); await view.load()
  assert.equal(requests[0].pathname, '/api/v1/card-relations')
  assert.equal(requests[0].searchParams.get('cardId'), '11')
  assert.equal(requests[0].searchParams.get('page'), '1')
  assert.equal(requests[0].searchParams.get('pageSize'), '20')
  assert.equal(view.data.items[0].connector, '↔')
  assert.match(view.data.items[0].directionLabel, /无方向/)
  assert.match(view.data.items[1].typeLabel, /前置/)
  assert.match(view.data.items[1].directionLabel, /起点是终点的前置/)
  assert.match(view.data.items[2].typeLabel, /示例/)
  assert.match(view.data.items[2].directionLabel, /终点是起点的示例/)
  assert.equal(view.data.items[2].evidenceText, '人工核对后保留的关联说明')
  assert.match(view.data.relationNotice, /人工确认/)
  assert.match(view.data.relationNotice, /不保证.*事实/)
  await view.next(); assert.equal(requests.at(-1).searchParams.get('page'), '2')
  await view.previous(); assert.equal(requests.at(-1).searchParams.get('page'), '1')
})

test('选择关系真实读取两端卡片及各自来源，展示标题、页码、提取方式与质量', async () => {
  reset(); installed()
  const view = page(); view.onLoad({ cardId: '11' }); await view.load()
  const requests = []; const serve = handler
  handler = options => { requests.push(new URL(options.url).pathname); serve(options) }
  await view.selectRelation(click('91'))
  assert.deepEqual(requests.sort(), ['/api/v1/cards/11', '/api/v1/cards/11/sources', '/api/v1/cards/12', '/api/v1/cards/12/sources'])
  assert.equal(view.data.selectedRelation.id, '91')
  assert.equal(view.data.endpoints[0].card.title, '服务端卡片11')
  assert.equal(view.data.endpoints[1].card.title, '服务端卡片12')
  assert.equal(view.data.endpoints[1].sources[0].materialTitle, '来源笔记')
  assert.equal(view.data.endpoints[1].sources[0].locator, 'PDF 第 3 页 · OCR 识别文字')
  assert.equal(view.data.endpoints[1].sources[0].qualityNote, '请核对识别文字')
  assert.equal(view.data.endpoints[1].sources[0].content, '完整来源')
})

test('失效来源明确说明并移除原文，人工卡片成功返回空来源有单独说明', async () => {
  reset(); installed()
  const view = page(); view.onLoad({ cardId: '11' }); await view.load()
  handler = options => {
    const url = new URL(options.url)
    if (url.pathname.endsWith('/11/sources')) ok(options, { items: ['SOURCE_DELETED', 'SOURCE_RESTRICTED'].map((reason, index) => ({
      chunkId: String(index), materialId: '31', isAvailable: false, unavailableReason: reason,
      quoteText: '不能显示的引用', content: '不能显示的正文',
    })) })
    else if (url.pathname.endsWith('/sources')) ok(options, { items: [] })
    else ok(options, { id: url.pathname.split('/').pop(), title: '本人卡片', origin: 'MANUAL', generationMode: 'MANUAL' })
  }
  await view.selectRelation(click('91'))
  const sources = view.data.endpoints[0].sources
  assert.match(sources[0].reasonLabel, /已删除/)
  assert.match(sources[1].reasonLabel, /受限/)
  assert.ok(sources.every(source => source.quoteText === null && source.content === null))
  assert.match(view.data.endpoints[1].emptySourceLabel, /人工创建.*无自动来源/)
  destination = ''; view.openMaterial(click('31')); assert.equal(destination, '')
})

test('来源失败是可重试错误，未完成读取前不显示旧详情或伪装无来源', async () => {
  reset(); installed([relation(), relation('92', 'EXAMPLE', '13', '14')])
  const view = page(); view.onLoad({ cardId: '11' }); await view.load(); await view.selectRelation(click('91'))
  const pending = []
  handler = options => pending.push(options)
  const read = view.selectRelation(click('92'))
  assert.equal(view.data.endpoints.length, 0)
  assert.equal(view.data.detailLoading, true)
  for (const options of pending) {
    if (options.url.endsWith('/13/sources')) options.fail()
    else ok(options, options.url.endsWith('/sources') ? { items: [] } : { id: '14', title: '新卡片', origin: 'MANUAL' })
  }
  await read
  assert.match(view.data.detailError, /无法连接/)
  assert.equal(view.data.endpoints.length, 0)
  assert.equal(view.data.selectedRelation.id, '92')
  installed(); await view.retryDetail()
  assert.equal(view.data.detailError, '')
  assert.equal(view.data.endpoints.length, 2)
})

test('切换关系后迟到响应不能覆盖新选择，翻页清理详情', async () => {
  reset(); installed([relation(), relation('92', 'PREREQUISITE', '13', '14')], 21)
  const view = page(); view.onLoad({ cardId: '11' }); await view.load()
  const pending = []
  handler = options => pending.push(options)
  const old = view.selectRelation(click('91'))
  installed(); await view.selectRelation(click('92'))
  for (const options of pending) ok(options, options.url.endsWith('/sources') ? { items: [] } : { id: '11', title: '迟到的旧卡片' })
  await old
  assert.equal(view.data.selectedRelation.id, '92')
  assert.equal(view.data.endpoints[0].card.id, '13')
  assert.equal(view.data.detailLoading, false)
  await view.next()
  assert.equal(view.data.selectedRelation, null)
  assert.deepEqual(view.data.endpoints, [])
})

test('列表真实空与读取失败分别呈现，失败可重试且分页在途不重复请求', async () => {
  reset()
  const view = page(); view.onLoad({ cardId: '11' })
  let pending; let count = 0
  handler = options => { pending = options; count++ }
  const read = view.load(); view.load(); view.next(); assert.equal(count, 1)
  pending.success({ statusCode: 500, data: { error: { message: '关系读取失败' } } }); await read
  assert.equal(view.data.error, '关系读取失败')
  assert.deepEqual(view.data.items, [])
  installed([]); await view.load()
  assert.equal(view.data.error, '')
  assert.equal(view.data.total, 0)
  assert.deepEqual(view.data.items, [])
  const template = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/cards/relations.wxml'), 'utf8')
  assert.match(template, /!loading && !error && !items.length && total === 0/)
  assert.match(template, /bindtap="retryDetail"/)
})

test('两端详情及可用原资料有入口，并能正常返回原卡片', async () => {
  reset(); installed()
  const view = page(); view.onLoad({ cardId: '11' }); await view.load(); await view.selectRelation(click('91'))
  view.openCard(click('12')); assert.equal(destination, '/pages/cards/detail?id=12')
  view.openMaterial(click('31')); assert.equal(destination, '/pages/materials/detail?id=31')
  view.openOriginCard(); assert.equal(destination, '/pages/cards/detail?id=11')
})

test('跨端删除使末页越界时仍能返回上一页，空页不冒充完全没有关联', async () => {
  reset(); installed([relation()], 21)
  const view = page(); view.onLoad({ cardId: '11' }); await view.load()
  installed([], 20); await view.next()
  assert.equal(view.data.page, 2); assert.equal(view.data.total, 20); assert.deepEqual(view.data.items, [])
  const template = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/cards/relations.wxml'), 'utf8')
  assert.match(template, /wx:if="\{\{page > 1 \|\| total > 20\}\}"/)
  assert.match(template, /total === 0.*此卡片暂无已确认关联/)
  assert.match(template, /当前页暂无关联，请返回上一页/)
  installed([relation()], 20); await view.previous()
  assert.equal(view.data.page, 1); assert.equal(view.data.items.length, 1)
})

test('无登录与401跳回登录，账号变化或页面离开丢弃在途本人数据', async () => {
  reset(); storage.clear()
  const view = page(); view.onLoad({ cardId: '11' })
  let count = 0; handler = () => { count++ }
  await view.load(); assert.equal(count, 0); assert.equal(destination, '/pages/login/index?reason=expired')
  reset(); handler = options => options.success({ statusCode: 401, data: { error: { message: '登录失效' } } })
  await view.load(); assert.equal(storage.has('lms.accessToken.v1'), false)
  assert.equal(destination, '/pages/login/index?reason=expired')
  reset(); installed(); await view.load()
  const pending = []; handler = options => pending.push(options)
  const read = view.selectRelation(click('91'))
  storage.set('lms.accessToken.v1', 's'.repeat(43))
  for (const options of pending) ok(options, options.url.endsWith('/sources') ? { items: [] } : { id: '11', title: '旧账号卡片' })
  await read
  assert.deepEqual(view.data.items, []); assert.deepEqual(view.data.endpoints, [])
  assert.match(view.data.error, /账号已变化.*重新读取/)
  reset(); installed(); await view.load()
  let late; handler = options => { late = options }
  const listRead = view.load(); view.onHide(); ok(late, { items: [relation()], total: 1, page: 1, pageSize: 20 }); await listRead
  assert.deepEqual(view.data.items, [])
})

test('旧账号关联请求迟到的401不能清除新账号会话或跳转登录', async () => {
  reset(); installed()
  const view = page(); view.onLoad({ cardId: '11' }); await view.load()
  const pending = []; handler = options => pending.push(options)
  const read = view.selectRelation(click('91'))
  const newToken = 's'.repeat(43); storage.set('lms.accessToken.v1', newToken)
  pending[0].success({ statusCode: 401, data: { error: { message: '旧会话已失效' } } })
  for (const options of pending.slice(1)) ok(options, options.url.endsWith('/sources') ? { items: [] } : { id: '12', title: '迟到卡片' })
  await read
  assert.equal(storage.get('lms.accessToken.v1'), newToken)
  assert.equal(destination, '')
  assert.deepEqual(view.data.endpoints, [])
  assert.match(view.data.error, /账号已变化/)
})

test('候选确认补充三种类型方向，关联仍默认不选，恢复旧草稿补标签且保存中不可更改勾选', async () => {
  reset()
  const candidates = ['RELATED', 'PREREQUISITE', 'EXAMPLE'].map((type, index) => ({
    relationKey: String(index), source: { candidateKey: 'a' }, target: { cardId: '12', cardVersion: 2 },
    type, evidenceText: '候选说明',
  }))
  handler = options => ok(options, options.url.endsWith('/users/me') ? { id: '7' }
    : options.url.endsWith('/result') ? { resultVersion: 1, sourceVersion: 1, generationMode: 'MOCK', summary: '摘要', tags: [],
      cards: [{ candidateKey: 'a', title: '候选标题', content: '正文', sources: [] }], relations: candidates }
      : options.url.endsWith('/sources') ? [] : { id: '9', status: 'WAIT_CONFIRM' })
  const form = page('tasks/confirm'); form.onLoad({ id: '9' }); await form.load()
  assert.equal(form.data.relations[0].connector, '↔')
  assert.match(form.data.relations[1].directionLabel, /起点是终点的前置/)
  assert.match(form.data.relations[2].directionLabel, /终点是起点的示例/)
  assert.ok(form.data.relations.every(row => row.selected === false)); assert.deepEqual(form.input().relations, [])
  form.persistDraft()
  const stored = storage.get('lms.confirmDraft.v1.7.9'); stored.relations.forEach(row => { delete row.typeLabel; delete row.directionLabel; delete row.connector })
  const restored = page('tasks/confirm'); restored.onLoad({ id: '9' }); await restored.load()
  assert.match(restored.data.relations[1].typeLabel, /前置/)
  restored.setData({ saving: true }); restored.onRelationSelection({ detail: { value: ['0'] } })
  assert.ok(restored.data.relations.every(row => row.selected === false))
  const template = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/tasks/confirm.wxml'), 'utf8')
  assert.match(template, /item.typeLabel/); assert.match(template, /item.directionLabel/); assert.match(template, /item.connector/)
  assert.match(template, /conflict \|\| saving \|\| task.status !== 'WAIT_CONFIRM'/)
})
