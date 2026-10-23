const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'b'.repeat(43)
const storage = new Map([['lms.accessToken.v1', token]])
let pageDefinition
let requestHandler
let redirectedTo
global.Page = definition => { pageDefinition = definition }
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  request: options => requestHandler(options),
  redirectTo: options => { redirectedTo = options.url },
  reLaunch: options => { redirectedTo = options.url },
}

function page(modulePath) {
  delete require.cache[require.resolve(modulePath)]
  require(modulePath)
  const instance = { ...pageDefinition, data: { ...pageDefinition.data } }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}

test('笔记页面用当前账号令牌发送 NOTE 并跳转到新详情', async () => {
  const form = page('../miniprogram/pages/note/new')
  form.setData({ title: '  离散数学  ', subject: '数学', tagsText: '期末，重点', noteText: '复习集合与关系' })
  requestHandler = options => {
    assert.match(options.url, /\/api\/v1\/materials$/)
    assert.equal(options.method, 'POST')
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    assert.deepEqual(options.data, {
      type: 'NOTE', title: '离散数学', subject: '数学', tags: ['期末', '重点'], noteText: '复习集合与关系',
    })
    options.success({ statusCode: 201, data: { data: { id: '42' } } })
  }
  await form.submit()
  assert.equal(redirectedTo, '/pages/materials/detail?id=42')
  assert.equal(form.data.loading, false)
})

test('笔记页面在输入不完整时不发请求', async () => {
  const form = page('../miniprogram/pages/note/new')
  requestHandler = () => { throw new Error('不应发送请求') }
  await form.submit()
  assert.match(form.data.error, /标题和正文/)
})

test('资料页读取列表和标签并生成可显示的摘要', async () => {
  const list = page('../miniprogram/pages/materials/index')
  requestHandler = options => {
    if (options.url.includes('/tags?')) {
      options.success({ statusCode: 200, data: { data: { items: [{ id: '7', name: '期末' }], total: 1 } } })
    } else {
      assert.match(options.url, /\/materials\?page=1&pageSize=20$/)
      options.success({ statusCode: 200, data: { data: { items: [{
        id: '42', title: '离散数学', noteText: '正文示例', updatedAt: '2026-10-22T14:00:00Z', tags: [],
      }], total: 1 } } })
    }
  }
  await list.loadTags()
  await list.load()
  assert.deepEqual(list.data.tagNames, ['全部标签', '期末'])
  assert.equal(list.data.items[0].excerpt, '正文示例')
  assert.equal(list.data.items[0].shortDate, '2026-10-22')
})
