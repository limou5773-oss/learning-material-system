const test = require('node:test')
const assert = require('node:assert/strict')

const token = 't'.repeat(43)
const storage = new Map([['lms.accessToken.v1', token]])
let definition
let requestHandler
let redirected = ''
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  request: options => requestHandler(options),
  redirectTo: options => { redirected = options.url },
  navigateTo: () => {},
  reLaunch: () => {},
  showModal: options => options.success({ confirm: true }),
}

function page(path) {
  delete require.cache[require.resolve(path)]
  require(path)
  const instance = { ...definition, data: { ...definition.data } }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}

test('小程序任务提交在网络失败后复用请求键并带PDF页段和同意标记', async () => {
  const form = page('../miniprogram/pages/tasks/new')
  form.onLoad({ id: '42' })
  const keys = []
  requestHandler = options => {
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    if (options.method === 'POST') {
      keys.push(options.data.requestKey)
      assert.deepEqual(options.data.pageRange, { from: 2, to: 5 })
      assert.equal(options.data.externalProcessingConsent, true)
      if (keys.length === 1) options.fail()
      else options.success({ statusCode: 202, data: { data: { id: '88', status: 'QUEUED' } } })
    } else options.success({ statusCode: 200, data: { data: {
      id: '42', title: '讲义', type: 'PDF', version: 1, pageCount: 30,
    } } })
  }
  await form.load()
  form.setData({ from: '2', to: '5', consent: true })
  await form.submit()
  assert.match(form.data.error, /无法连接服务器/)
  await form.submit()
  assert.equal(keys[0], keys[1])
  assert.equal(redirected, '/pages/tasks/detail?id=88')
})

test('小程序任务列表和详情读取服务端状态', async () => {
  const list = page('../miniprogram/pages/tasks/index')
  requestHandler = options => {
    assert.match(options.url, /\/ai-tasks\?page=1&pageSize=20$/)
    options.success({ statusCode: 200, data: { data: { items: [{
      id: '88', materialId: '42', materialVersion: 1, goalType: 'REVIEW_CARDS',
      status: 'QUEUED', createdAt: '2026-10-23T00:00:00Z',
    }], total: 1 } } })
  }
  await list.load()
  assert.equal(list.data.items[0].statusLabel, '排队中')
  const detail = page('../miniprogram/pages/tasks/detail')
  detail.onLoad({ id: '88' })
  requestHandler = options => options.success({ statusCode: 200, data: { data:
    options.url.includes('/steps?') ? { items: [], total: 0 } : {
      id: '88', materialId: '42', materialVersion: 1, goalType: 'REVIEW_CARDS',
      status: 'QUEUED', stage: 'QUEUED', stepCount: 0,
    } } })
  await detail.load()
  assert.equal(detail.data.task.statusLabel, '排队中')
  assert.equal(detail.data.task.stepCount, 0)
  assert.deepEqual(detail.data.steps, [])
})

test('小程序待确认任务展示模拟候选和可核验来源', async () => {
  const detail = page('../miniprogram/pages/tasks/detail')
  detail.onLoad({ id: '91' })
  const paths = []
  requestHandler = options => {
    paths.push(options.url)
    let data
    if (options.url.endsWith('/steps?page=1&pageSize=100')) data = { items: [], total: 0 }
    else if (options.url.endsWith('/result')) data = {
      taskId: '91', resultVersion: 1, sourceVersion: 1, generationMode: 'MOCK',
      summary: '模拟摘要', tags: ['模拟结果'], relations: [], processedPages: [], warnings: ['模拟生成'],
      cards: [{ candidateKey: 'mock-1', title: '模拟卡片', content: '待核对内容',
        sources: [{ chunkId: '17', quoteText: '实际原文', startOffset: 0, endOffset: 4 }] }],
    }
    else if (options.url.endsWith('/sources')) data = [{ chunkId: '17', content: '实际原文',
      extractionMethod: 'TEXT', chunkNo: 1 }]
    else data = { id: '91', materialId: '42', materialVersion: 1, goalType: 'REVIEW_CARDS',
      status: 'WAIT_CONFIRM', stage: 'CANDIDATE_READY', stepCount: 3 }
    options.success({ statusCode: 200, data: { data } })
  }
  await detail.load()
  assert.equal(detail.data.candidate.generationMode, 'MOCK')
  assert.equal(detail.data.candidateCards[0].sourceViews[0].content, '实际原文')
  assert.equal(detail.data.candidateCards[0].sourceViews[0].locator, '文字笔记片段 1')
  assert.equal(paths.filter(path => path.endsWith('/result')).length, 1)
  assert.equal(paths.filter(path => path.endsWith('/sources')).length, 1)
})

test('小程序取消后状态更新，重试网络失败复用请求键', async () => {
  const detail = page('../miniprogram/pages/tasks/detail')
  detail.onLoad({ id: '88' })
  const retryKeys = []
  let status = 'RUNNING'
  requestHandler = options => {
    if (options.url.includes('/steps?')) {
      options.success({ statusCode: 200, data: { data: { items: [], total: 0 } } }); return
    }
    if (options.url.endsWith('/cancellations')) {
      status = 'CANCELLED'
      options.success({ statusCode: 200, data: { data: { id: '88', status } } }); return
    }
    if (options.url.endsWith('/retries')) {
      retryKeys.push(options.data.requestKey)
      if (retryKeys.length === 1) options.fail()
      else options.success({ statusCode: 202, data: { data: { id: '89', status: 'QUEUED' } } })
      return
    }
    if (options.url.includes('/materials/42')) {
      options.success({ statusCode: 200, data: { data: { id: '42', version: 2 } } }); return
    }
    options.success({ statusCode: 200, data: { data: {
      id: '88', materialId: '42', materialVersion: 1, goalType: 'REVIEW_CARDS',
      status, stage: status, stepCount: 0, canCancel: status === 'RUNNING', canRetry: status === 'CANCELLED',
    } } })
  }
  await detail.load()
  detail.cancel()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(detail.data.task.status, 'CANCELLED')
  detail.onConsentChange({ detail: { value: ['yes'] } })
  await detail.retryTask()
  assert.match(detail.data.error, /无法连接服务器/)
  await detail.retryTask()
  assert.equal(retryKeys[0], retryKeys[1])
  assert.equal(redirected, '/pages/tasks/detail?id=89')
})
