const test = require('node:test')
const assert = require('node:assert/strict')
const token = 'd'.repeat(43)
let currentToken = token
let definition, requestHandler, navigation, confirm = false
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: () => currentToken, removeStorageSync: () => { currentToken = '' },
  request: options => requestHandler(options),
  reLaunch: options => { navigation = options.url },
  switchTab: options => { navigation = options.url },
  showModal: options => { assert.match(options.content, /子卡片.*保留/); options.success({ confirm }) },
}
const card = { id: '42', title: '旧标题', version: 2, generationMode: 'MANUAL', isEdited: false }
function page() {
  delete require.cache[require.resolve('../miniprogram/pages/cards/detail')]
  require('../miniprogram/pages/cards/detail')
  const instance = { ...definition, data: structuredClone(definition.data) }
  instance.setData = patch => Object.assign(instance.data, patch)
  instance.setData({ id: '42', card: { ...card } })
  currentToken = token
  navigation = undefined
  return instance
}

test('取消删除不发送请求；确认后携带读取版本，成功返回卡片列表', async () => {
  const detail = page()
  requestHandler = () => assert.fail('取消不应发送请求')
  confirm = false; detail.deleteCard()
  requestHandler = options => {
    assert.match(options.url, /\/cards\/42\?version=2$/)
    assert.equal(options.method, 'DELETE')
    options.success({ statusCode: 204 })
  }
  confirm = true; detail.deleteCard()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(navigation, '/pages/cards/index')
})

test('删除冲突保留详情，必须重新读取后再次明确确认', async () => {
  const detail = page()
  requestHandler = options => options.success({ statusCode: 409, data: { error: { message: '版本冲突', currentVersion: 3 } } })
  await detail.confirmDelete()
  assert.equal(detail.data.card.version, 2)
  assert.equal(detail.data.deleteConflict, true)
  assert.match(detail.data.actionError, /当前版本 3/)
  assert.equal(navigation, undefined)
  requestHandler = () => assert.fail('冲突未读取不能重新删除')
  await detail.confirmDelete()
  requestHandler = options => options.success({ statusCode: 200, data: { data: options.url.endsWith('/sources') ? { items: [] } : { ...card, version: 3 } } })
  await detail.load()
  assert.equal(detail.data.card.version, 3)
  assert.equal(detail.data.deleteConflict, false)
})

test('网络失败保留详情并释放删除按钮', async () => {
  const detail = page()
  requestHandler = options => options.fail()
  await detail.confirmDelete()
  assert.equal(detail.data.card.id, '42')
  assert.equal(detail.data.deleteBusy, false)
  assert.ok(detail.data.actionError)
  assert.equal(navigation, undefined)
})

test('删除期间离开页面后迟到成功不跳转，仍释放原页面pending', async () => {
  const detail = page()
  let respond
  requestHandler = options => { respond = options.success }
  const pending = detail.confirmDelete()
  detail.onHide()
  respond({ statusCode: 204 })
  await pending
  assert.equal(navigation, undefined)
  assert.equal(detail.data.deleteBusy, false)
})

test('删除的旧401不影响新会话，旧卡片迟到冲突不影响当前详情', async () => {
  const detail = page()
  let respond
  requestHandler = options => { respond = options.success }
  const pending = detail.confirmDelete()
  currentToken = 'n'.repeat(43)
  respond({ statusCode: 401, data: { error: { message: '旧会话已过期' } } })
  await pending
  assert.equal(navigation, undefined)
  assert.equal(currentToken, 'n'.repeat(43))
  assert.equal(detail.data.actionError, '')
  const next = detail.confirmDelete()
  detail.setData({ id: '99', card: { ...card, id: '99' } })
  respond({ statusCode: 409, data: { error: { message: '旧卡片冲突', currentVersion: 3 } } })
  await next
  assert.equal(detail.data.deleteConflict, false)
  assert.equal(detail.data.actionError, '')
})

test('当前页面当前会话的删除401仍转登录', async () => {
  const detail = page()
  requestHandler = options => options.success({ statusCode: 401, data: { error: { message: '会话已过期' } } })
  await detail.confirmDelete()
  assert.equal(navigation, '/pages/login/index?reason=expired')
})
