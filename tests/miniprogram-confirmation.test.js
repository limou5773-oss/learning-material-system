const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'q'.repeat(43)
const storage = new Map([['lms.accessToken.v1', token]])
let definition, handler, destination = ''
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key), request: options => handler(options),
  reLaunch: options => { destination = options.url }, navigateTo: options => { destination = options.url },
  redirectTo: options => { destination = options.url }, showModal: options => options.success({ confirm: true }),
}
function page(path) {
  try { delete require.cache[require.resolve(path)]; require(path) }
  catch (error) {
    if (error.code === 'MODULE_NOT_FOUND') assert.fail('尚未实现小程序确认保存页面')
    throw error
  }
  const value = { ...definition, data: structuredClone(definition.data) }
  value.setData = patch => Object.assign(value.data, patch)
  return value
}
function install(status = 'WAIT_CONFIRM') {
  handler = options => {
    const data = options.url.endsWith('/users/me') ? { id: '7' }
      : options.url.endsWith('/result') ? { taskId: '91', resultVersion: 1, sourceVersion: 1,
        generationMode: 'MOCK', summary: '模拟摘要', tags: ['模拟结果'], relations: [], warnings: ['模拟生成'],
        cards: [{ candidateKey: 'a', title: '卡片', content: '候选正文',
          sources: [{ chunkId: '17', quoteText: '原文', startOffset: 0, endOffset: 2 }] }] }
      : options.url.endsWith('/sources') ? [{ chunkId: '17', content: '原文', extractionMethod: 'TEXT', chunkNo: 1 }]
      : { id: '91', materialId: '42', materialVersion: 1, status }
    options.success({ statusCode: 200, data: { data } })
  }
}

test('小程序确认失败保留本人草稿，成功后清理并可查看卡片', async () => {
  storage.clear(); storage.set('lms.accessToken.v1', token)
  install()
  const form = page('../miniprogram/pages/tasks/confirm')
  form.onLoad({ id: '91' }); await form.load()
  form.onSummaryInput({ detail: { value: '人工修订摘要' } })
  let attempts = 0
  handler = options => {
    assert.match(options.url, /\/ai-tasks\/91\/confirmation$/)
    assert.equal(options.method, 'PUT')
    assert.equal(options.data.summary, '人工修订摘要')
    assert.equal(options.data.sourceVersion, 1)
    assert.equal(options.data.cards[0].candidateKey, 'a')
    attempts++
    if (attempts === 1) options.fail()
    else options.success({ statusCode: 200, data: { data: {
      taskId: '91', status: 'SUCCEEDED', cardIds: ['123'], resultingMaterialVersion: 2,
      confirmedAt: '2026-10-24T00:00:00Z',
    } } })
  }
  await form.submit()
  assert.match(form.data.error, /无法连接|草稿/)
  assert.equal(form.data.summary, '人工修订摘要')
  assert.ok(storage.has('lms.confirmDraft.v1.7.91'))
  await form.submit()
  assert.equal(form.data.receipt.cardIds[0], '123')
  assert.equal(storage.has('lms.confirmDraft.v1.7.91'), false)
  form.openCard({ currentTarget: { dataset: { id: '123' } } })
  assert.equal(destination, '/pages/cards/detail?id=123')
})

test('小程序409不覆盖草稿，换账号不恢复其他用户草稿', async () => {
  storage.clear(); storage.set('lms.accessToken.v1', token)
  install()
  const form = page('../miniprogram/pages/tasks/confirm')
  form.onLoad({ id: '91' }); await form.load()
  form.onSummaryInput({ detail: { value: '冲突时的修改' } })
  handler = options => options.success({ statusCode: 409, data: { error: {
    code: 'SOURCE_VERSION_CHANGED', message: '来源资料版本已变化',
  } } })
  await form.submit()
  assert.equal(form.data.conflict, true)
  assert.equal(form.data.summary, '冲突时的修改')
  assert.ok(storage.has('lms.confirmDraft.v1.7.91'))
  install()
  const originalHandler = handler
  handler = options => options.url.endsWith('/users/me')
    ? options.success({ statusCode: 200, data: { data: { id: '8' } } }) : originalHandler(options)
  const other = page('../miniprogram/pages/tasks/confirm')
  other.onLoad({ id: '91' }); await other.load()
  assert.equal(other.data.summary, '模拟摘要')
})

test('小程序查看正式卡片时区分模拟来源并保留来源不可用说明', async () => {
  const detail = page('../miniprogram/pages/cards/detail')
  detail.onLoad({ id: '123' })
  handler = options => options.success({ statusCode: 200, data: { data:
    options.url.endsWith('/sources') ? { items: [{ chunkId: '17', materialId: '42',
      isAvailable: false, content: null, quoteText: null, unavailableReason: '原资料已删除' }] }
      : { id: '123', title: '已保存卡片', content: '修订正文', origin: 'AI', generationMode: 'MOCK',
        isEdited: true, tags: [] },
  } })
  await detail.load()
  assert.equal(detail.data.card.generationMode, 'MOCK')
  assert.equal(detail.data.sources[0].content, null)
  assert.equal(detail.data.sources[0].unavailableReason, '原资料已删除')
})

test('小程序知识卡片列表分页，任务只在待确认状态提供编辑入口', async () => {
  const list = page('../miniprogram/pages/cards/index')
  handler = options => {
    const query = new URL(options.url)
    assert.equal(query.pathname.endsWith('/cards'), true)
    assert.equal(query.searchParams.get('page'), '1')
    assert.equal(query.searchParams.get('pageSize'), '20')
    assert.equal(query.searchParams.get('favoriteOnly'), 'false')
    options.success({ statusCode: 200, data: { data: { items: [{ id: '123', title: '已保存卡片',
      generationMode: 'MOCK', tags: [] }], page: 1, pageSize: 20, total: 1 } } })
  }
  await list.load()
  assert.equal(list.data.items[0].id, '123')
  const detail = page('../miniprogram/pages/tasks/detail')
  assert.equal(typeof detail.openConfirmation, 'function', '任务页面尚未接入确认入口')
  detail.setData({ id: '91', task: { status: 'WAIT_CONFIRM' } })
  detail.openConfirmation()
  assert.equal(destination, '/pages/tasks/confirm?id=91')
  detail.setData({ task: { status: 'CANCELLED' } })
  destination = ''
  detail.openConfirmation()
  assert.equal(destination, '')
})

test('确认后查阅正式卡片仍保留扫描页OCR来源和质量说明', async () => {
  storage.set('lms.accessToken.v1', token)
  const detail = page('../miniprogram/pages/cards/detail')
  detail.onLoad({ id: '124' })
  handler = options => options.success({ statusCode: 200, data: { data:
    options.url.endsWith('/sources') ? { items: [{ chunkId: '18', materialId: '43',
      isAvailable: true, content: '识别文字', quoteText: '识别', pageNo: 3,
      extractionMethod: 'OCR', qualityNote: 'OCR置信度较低，请核对原图' }] }
      : { id: '124', title: '已确认卡片', generationMode: 'MOCK', tags: [] },
  } })
  await detail.load()
  assert.equal(detail.data.sources[0].locator, 'PDF 第 3 页 · OCR 识别文字')
  assert.equal(detail.data.sources[0].qualityNote, 'OCR置信度较低，请核对原图')
  assert.equal(detail.data.card.generationMode, 'MOCK')
})

test('本地API桩：MODEL候选经人工确认才写入，正式卡片保留模型与核对标识', async () => {
  storage.clear(); storage.set('lms.accessToken.v1', token)
  const result = { taskId: 'model-local', resultVersion: 1, sourceVersion: 1,
    generationMode: 'MODEL', generationInfo: { provider: 'deepseek', model: 'local-stub', promptVersion: 'test-v1' },
    summary: '本地桩摘要', tags: [], relations: [], warnings: [], processedPages: [],
    cards: [{ candidateKey: 'a', title: '本地桩卡片', content: '需要人工确认的内容',
      sources: [{ chunkId: '17', quoteText: '原文', startOffset: 0, endOffset: 2 }] }] }
  let writes = 0
  handler = options => {
    if (options.method === 'PUT') {
      writes++
      assert.equal(options.data.cards[0].content, '人工核对后正文')
      assert.equal(options.data.generationMode, undefined)
      assert.equal(options.data.generationInfo, undefined)
      options.success({ statusCode: 200, data: { data: { taskId: 'model-local', status: 'SUCCEEDED', cardIds: ['125'], resultingMaterialVersion: 2 } } })
      return
    }
    const data = options.url.endsWith('/users/me') ? { id: '7' }
      : options.url.endsWith('/result') ? result
      : options.url.includes('/steps?') ? { items: [], total: 0 }
      : options.url.endsWith('/sources') ? [{ chunkId: '17', content: '原文', extractionMethod: 'TEXT', chunkNo: 1 }]
      : { id: 'model-local', status: 'WAIT_CONFIRM' }
    options.success({ statusCode: 200, data: { data } })
  }
  const detail = page('../miniprogram/pages/tasks/detail')
  detail.onLoad({ id: 'model-local' }); await detail.load()
  assert.equal(detail.data.generationView.label, '模型生成')
  assert.match(detail.data.generationView.modelLabel, /deepseek.*local-stub/)
  assert.match(detail.data.generationView.warning, /人工核对/)
  assert.equal(writes, 0)
  const form = page('../miniprogram/pages/tasks/confirm')
  form.onLoad({ id: 'model-local' }); await form.load()
  assert.equal(form.data.generationView.label, '模型生成')
  assert.match(form.data.generationView.modelLabel, /deepseek.*local-stub/)
  assert.equal(writes, 0)
  form.onCardInput({ currentTarget: { dataset: { key: 'a', field: 'content' } }, detail: { value: '人工核对后正文' } })
  await form.submit()
  assert.equal(writes, 1)
  assert.deepEqual(form.data.receipt.cardIds, ['125'])
  handler = options => options.success({ statusCode: 200, data: { data:
    options.url.endsWith('/sources') ? { items: [] } : { id: '125', generationMode: 'MODEL', tags: [] } } })
  const card = page('../miniprogram/pages/cards/detail')
  card.onLoad({ id: '125' }); await card.load()
  assert.equal(card.data.card.generationLabel, '模型生成 · 人工确认')
  assert.match(card.data.card.generationWarning, /人工核对/)
  assert.doesNotMatch(card.data.card.generationWarning, /模拟/)
})

test('agent本地桩：关联显示候选标题与已有卡片，明确勾选后才带版本提交', async () => {
  storage.clear(); storage.set('lms.accessToken.v1', token)
  install()
  const previousHandler = handler
  const relation = { relationKey: 'r1', source: { candidateKey: 'a' },
    target: { cardId: '123', cardVersion: 4 }, type: 'RELATED', evidenceText: '同一知识点' }
  handler = options => {
    if (!options.url.endsWith('/result')) return previousHandler(options)
    options.success({ statusCode: 200, data: { data: { taskId: '91', resultVersion: 1, sourceVersion: 1,
      generationMode: 'MODEL', generationInfo: { provider: 'deepseek', model: 'local-stub', promptVersion: 'd16-agent-v1' },
      summary: '摘要', tags: [], relations: [relation], warnings: [],
      cards: [{ candidateKey: 'a', title: '可核对标题', content: '正文', sources: [] }] } } })
  }
  const form = page('../miniprogram/pages/tasks/confirm')
  form.onLoad({ id: '91' }); await form.load()
  assert.equal(form.data.generationView.label, '模型生成')
  assert.equal(form.data.relations[0].sourceLabel, '可核对标题')
  assert.equal(form.data.relations[0].targetLabel, '已有卡片 #123')
  assert.deepEqual(form.input().relations, [])
  form.onRelationSelection({ detail: { value: ['r1'] } })
  assert.deepEqual(form.input().relations, [relation])
  form.data.cards.push({ ...form.data.cards[0], candidateKey: 'b' })
  form.onCardSelection({ detail: { value: ['b'] } })
  assert.throws(() => form.input(), /关联.*未选择/)
})
