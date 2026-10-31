const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'p'.repeat(43)
const storage = new Map()
let definition, requestHandler, uploadOptions, progress, destination
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  request: options => requestHandler(options),
  uploadFile: options => { uploadOptions = options; return { onProgressUpdate: listener => { progress = listener } } },
  navigateTo: options => { destination = options.url },
  redirectTo: options => { destination = options.url },
  reLaunch: options => { destination = options.url },
  pageScrollTo() {},
}
function page(path) {
  storage.clear(); storage.set('lms.accessToken.v1', token); destination = ''
  delete require.cache[require.resolve(path)]
  require(path)
  const value = { ...definition, data: structuredClone(definition.data) }
  value.setData = patch => Object.assign(value.data, patch)
  return value
}
const event = dataset => ({ currentTarget: { dataset } })

test('图片上传显示原生进度，100%后仍等待服务端，失败可重试', async () => {
  const form = page('../miniprogram/pages/image/new')
  form.setData({ filePath: 'wxfile://board.jpg', title: '板书' })
  const pending = form.submit()
  assert.equal(typeof progress, 'function')
  progress({ progress: 42 }); assert.equal(form.data.uploadProgress, 42)
  progress({ progress: 100 })
  assert.equal(form.data.uploading, true); assert.equal(destination, '')
  uploadOptions.success({ statusCode: 500, data: JSON.stringify({ error: { message: '保存失败' } }) })
  await pending
  assert.equal(form.data.error, '保存失败'); assert.equal(form.data.uploading, false)
  const retry = form.submit()
  assert.equal(form.data.uploadProgress, 0)
  uploadOptions.success({ statusCode: 201, data: JSON.stringify({ data: { id: '42' } }) })
  await retry
  assert.equal(destination, '/pages/materials/detail?id=42')
})

test('离开上传页面后的迟到进度与成功响应不更新页面或跳转', async () => {
  const form = page('../miniprogram/pages/image/new')
  form.setData({ filePath: 'wxfile://board.jpg', title: '板书' })
  const pending = form.submit()
  form.onUnload(); progress({ progress: 90 })
  uploadOptions.success({ statusCode: 201, data: JSON.stringify({ data: { id: '42' } }) })
  await pending
  assert.equal(form.data.uploadProgress, 0); assert.equal(destination, '')
})

test('收藏统一列表支持筛选、分页、详情与显式取消失败', async () => {
  const list = page('../miniprogram/pages/favorites/index')
  let failed = false
  requestHandler = options => {
    if (options.method === 'DELETE') {
      assert.match(options.url, /\/favorites\/CARD\/9$/)
      options.success({ statusCode: 500, data: { error: { message: '取消失败' } } }); return
    }
    if (failed) { options.fail(); return }
    options.success({ statusCode: 200, data: { data: { items: [
      { type: 'MATERIAL', targetId: '9', title: '资料', createdAt: '2026-10-28T00:00:00Z' },
      { type: 'CARD', targetId: '9', title: '卡片', createdAt: '2026-10-28T00:00:00Z' },
    ], total: 23 } } })
  }
  await list.onShow()
  assert.notEqual(list.data.items[0].key, list.data.items[1].key)
  list.open(event({ type: 'MATERIAL', id: '9' })); assert.equal(destination, '/pages/materials/detail?id=9')
  list.open(event({ type: 'CARD', id: '9' })); assert.equal(destination, '/pages/cards/detail?id=9')
  await list.remove(event({ type: 'CARD', id: '9' }))
  assert.equal(list.data.items.length, 2); assert.equal(list.data.actionError, '取消失败')
  requestHandler = options => {
    assert.match(options.url, /type=CARD/)
    options.success({ statusCode: 200, data: { data: { items: [], total: 23 } } })
  }
  await list.filter(event({ type: 'CARD' })); assert.equal(list.data.page, 1)
  await list.next(); assert.equal(list.data.page, 2)
  failed = true; requestHandler = options => options.fail()
  await list.load()
  assert.match(list.data.error, /无法连接/)
  assert.equal(list.data.items.length, 0)
})

test('离开收藏页和切换账号后忽略旧请求，过期会话返回登录', async () => {
  const list = page('../miniprogram/pages/favorites/index')
  let pendingRequest
  requestHandler = options => { pendingRequest = options }
  const first = list.onShow(); list.onHide()
  pendingRequest.success({ statusCode: 200, data: { data: { items: [{ type: 'CARD', targetId: '1' }], total: 1 } } })
  await first; assert.equal(list.data.items.length, 0)
  const second = list.onShow()
  storage.set('lms.accessToken.v1', 'n'.repeat(43))
  pendingRequest.success({ statusCode: 401, data: { error: { message: '旧会话过期' } } })
  await second; assert.equal(destination, '')
  requestHandler = options => options.success({ statusCode: 401, data: { error: { message: '会话过期' } } })
  await list.onShow(); assert.match(destination, /login\/index/)
})

test('分步确认只在当前步骤校验，回退修改保留，关联在最终提交校验', () => {
  const form = page('../miniprogram/pages/tasks/confirm')
  requestHandler = () => assert.fail('前进和后退不应提交请求')
  form.setData({ userId: '7', id: '91', task: { status: 'WAIT_CONFIRM' },
    cards: ['a', 'b'].map(key => ({ candidateKey: key, title: key, content: '正文', tagsText: '', sources: [], selected: true })),
    relations: [{ relationKey: 'r', source: { candidateKey: 'a' }, target: { candidateKey: 'b' }, selected: true }] })
  form.onTagsInput({ detail: { value: '重复，重复' } }); form.nextStep()
  assert.equal(form.data.currentStep, 0); assert.match(form.data.error, /标签不能重复/)
  form.onTagsInput({ detail: { value: '学习' } }); form.nextStep()
  assert.equal(form.data.currentStep, 1)
  form.onCardSelection({ detail: { value: ['a'] } }); form.nextStep()
  assert.equal(form.data.currentStep, 2)
  assert.throws(() => form.input(), /关联包含未选择/)
  form.previousStep(); assert.equal(form.data.cards[1].selected, false)
  form.previousStep(); assert.equal(form.data.tagsText, '学习')
  form.nextStep(); form.nextStep()
  form.onRelationSelection({ detail: { value: [] } })
  assert.equal(form.input().cards.length, 1); assert.equal(form.input().relations.length, 0)
})
