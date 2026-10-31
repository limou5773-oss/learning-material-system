const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'd'.repeat(43)
const storage = new Map([['lms.accessToken.v1', token]])
let definition
let chooseHandler
let uploadHandler
let downloadHandler
let requestHandler
let navigation = ''
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  chooseMedia: options => chooseHandler(options),
  uploadFile: options => { uploadHandler(options); return { onProgressUpdate() {} } },
  downloadFile: options => downloadHandler(options),
  request: options => requestHandler(options),
  redirectTo: options => { navigation = options.url },
  navigateTo: options => { navigation = options.url },
  reLaunch: options => { navigation = options.url },
}

function page(path) {
  delete require.cache[require.resolve(path)]
  require(path)
  const instance = { ...definition, data: { ...definition.data } }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}

test('小程序拍照选择后携带会话与表单上传图片', async () => {
  const upload = page('../miniprogram/pages/image/new')
  chooseHandler = options => {
    assert.deepEqual(options.sourceType, ['camera'])
    options.success({ tempFiles: [{ tempFilePath: 'wxfile://tmp/board.jpg', size: 1024 }] })
  }
  upload.camera()
  upload.onTagsInput({ detail: { value: '板书，重点' } })
  uploadHandler = options => {
    assert.equal(options.name, 'file')
    assert.equal(options.filePath, 'wxfile://tmp/board.jpg')
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    assert.equal(options.formData.title, 'board')
    assert.deepEqual(JSON.parse(options.formData.tagsJson), ['板书', '重点'])
    assert.equal(options.formData.allowDuplicate, 'false')
    options.success({ statusCode: 201, data: JSON.stringify({ data: { id: '81', type: 'IMAGE' } }) })
  }
  await upload.submit()
  assert.equal(navigation, '/pages/materials/detail?id=81')
})

test('重复上传先提示，再由用户确认保存副本', async () => {
  const upload = page('../miniprogram/pages/image/new')
  upload.setData({ filePath: 'wxfile://tmp/board.png', title: '板书' })
  let calls = 0
  uploadHandler = options => {
    calls += 1
    if (calls === 1) {
      options.success({ statusCode: 409, data: JSON.stringify({ error: {
        code: 'DUPLICATE_FILE', message: '图片已存在', existingMaterialId: '42',
      } }) })
    } else {
      assert.equal(options.formData.allowDuplicate, 'true')
      options.success({ statusCode: 201, data: JSON.stringify({ data: { id: '82' } }) })
    }
  }
  await upload.submit()
  assert.equal(upload.data.existingId, '42')
  await upload.saveCopy()
  assert.equal(navigation, '/pages/materials/detail?id=82')
})

test('图片详情凭会话令牌下载原图', async () => {
  const detail = page('../miniprogram/pages/materials/detail')
  detail.onLoad({ id: '42' })
  requestHandler = options => options.success({ statusCode: 200, data: { data: {
    id: '42', type: 'IMAGE', title: '图片资料', version: 1, status: 'ACTIVE', tags: [],
  } } })
  downloadHandler = options => {
    assert.match(options.url, /\/materials\/42\/file$/)
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    options.success({ statusCode: 200, tempFilePath: 'wxfile://tmp/downloaded.png' })
  }
  await detail.load()
  assert.equal(detail.data.previewPath, 'wxfile://tmp/downloaded.png')
})
