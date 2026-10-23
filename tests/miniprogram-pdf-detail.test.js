const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'p'.repeat(43)
let definition
let opened = ''
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: () => token,
  removeStorageSync: () => {},
  request: options => options.success({ statusCode: 200, data: { data: {
    id: '57', type: 'PDF', title: '讲义', pageCount: 2, version: 1, status: 'ACTIVE', tags: [],
  } } }),
  downloadFile: options => {
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    assert.match(options.url, /\/materials\/57\/file$/)
    options.success({ statusCode: 200, tempFilePath: 'wxfile://download/lecture.pdf' })
  },
  openDocument: options => {
    assert.equal(options.filePath, 'wxfile://download/lecture.pdf')
    assert.equal(options.fileType, 'pdf')
    opened = options.filePath
    options.success()
  },
}

test('小程序PDF详情使用会话下载并调用原生文档预览', async () => {
  delete require.cache[require.resolve('../miniprogram/pages/materials/detail')]
  require('../miniprogram/pages/materials/detail')
  const detail = { ...definition, data: { ...definition.data } }
  detail.setData = patch => Object.assign(detail.data, patch)
  detail.onLoad({ id: '57' })
  await detail.load()
  assert.equal(detail.data.material.pageCount, 2)
  await detail.openPdf()
  assert.equal(opened, 'wxfile://download/lecture.pdf')
  assert.equal(detail.data.busy, false)
})
