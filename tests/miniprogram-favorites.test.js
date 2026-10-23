const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'f'.repeat(43)
let definition
let requestHandler
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: () => token,
  removeStorageSync: () => {},
  request: options => requestHandler(options),
}

function page(path) {
  delete require.cache[require.resolve(path)]
  require(path)
  const instance = { ...definition, data: { ...definition.data } }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}

test('小程序列表组合学科、状态和收藏筛选，并切换本人资料收藏', async () => {
  const list = page('../miniprogram/pages/materials/index')
  list.setData({ subject: '数学', statusIndex: 2, favoritesOnly: true })
  requestHandler = options => {
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    if (options.method === 'PUT') {
      assert.match(options.url, /\/favorites\/MATERIAL\/42$/)
      options.success({ statusCode: 204 })
    } else {
      assert.match(decodeURIComponent(options.url), /\/materials\?page=1&pageSize=20&subject=数学&status=ALL&favorite=true$/)
      options.success({ statusCode: 200, data: { data: { items: [{
        id: '42', title: '线性代数', favorite: false, noteText: '正文', updatedAt: '2026-10-23T00:00:00Z', tags: [],
      }], total: 1 } } })
    }
  }
  await list.load()
  assert.equal(list.data.items[0].favorite, false)
  await list.toggleFavorite({ currentTarget: { dataset: { id: '42' } } })
  assert.equal(list.data.items[0].favorite, true)
})

test('小程序详情的收藏与取消收藏分别调用幂等接口', async () => {
  const detail = page('../miniprogram/pages/materials/detail')
  detail.onLoad({ id: '42' })
  const methods = []
  requestHandler = options => {
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    if (options.url.endsWith('/favorites/MATERIAL/42')) {
      methods.push(options.method)
      options.success({ statusCode: 204 })
    } else {
      options.success({ statusCode: 200, data: { data: {
        id: '42', title: '笔记', type: 'NOTE', favorite: false, version: 1, status: 'ACTIVE', tags: [],
      } } })
    }
  }
  await detail.load()
  await detail.toggleFavorite()
  assert.equal(detail.data.material.favorite, true)
  await detail.toggleFavorite()
  assert.equal(detail.data.material.favorite, false)
  assert.deepEqual(methods, ['PUT', 'DELETE'])
})
