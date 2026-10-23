const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'c'.repeat(43)
const storage = new Map([['lms.accessToken.v1', token]])
let definition
let requestHandler
let navigation = ''
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  request: options => requestHandler(options),
  navigateBack: () => { navigation = 'back' },
  navigateTo: options => { navigation = options.url },
  reLaunch: options => { navigation = options.url },
  showModal: options => options.success({ confirm: true }),
}

function page(path) {
  delete require.cache[require.resolve(path)]
  require(path)
  const instance = { ...definition, data: { ...definition.data } }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}

const material = { id: '42', title: '原标题', subject: '数学', noteText: '原正文', version: 1,
  status: 'ACTIVE', tags: [{ id: '7', name: '重点' }] }

test('编辑冲突保留本地草稿并可对照服务器版本', async () => {
  const editor = page('../miniprogram/pages/note/edit')
  editor.onLoad({ id: '42' })
  let getCount = 0
  requestHandler = options => {
    if (options.method === 'PATCH') {
      assert.equal(options.data.version, 1)
      assert.equal(options.data.noteText, '我正在写的正文')
      options.success({ statusCode: 409, data: { error: { code: 'VERSION_CONFLICT', message: '资料已被更新' } } })
    } else {
      getCount += 1
      options.success({ statusCode: 200, data: { data: getCount === 1 ? material : { ...material, version: 2, noteText: '服务器新正文' } } })
    }
  }
  await editor.load()
  editor.onNoteInput({ detail: { value: '我正在写的正文' } })
  assert.equal(storage.get('lms.noteDraft.v1.42').noteText, '我正在写的正文')
  await editor.submit()
  assert.equal(editor.data.conflict, true)
  assert.equal(editor.data.noteText, '我正在写的正文')
  assert.equal(editor.data.serverCopy.noteText, '服务器新正文')
  assert.equal(storage.get('lms.noteDraft.v1.42').version, 1)
  editor.discardDraft()
  assert.equal(editor.data.noteText, '服务器新正文')
  assert.equal(editor.data.material.version, 2)
  assert.equal(storage.has('lms.noteDraft.v1.42'), false)
})

test('编辑成功清除草稿并返回详情', async () => {
  const editor = page('../miniprogram/pages/note/edit')
  editor.onLoad({ id: '42' })
  requestHandler = options => {
    if (options.method === 'PATCH') {
      assert.equal(options.data.version, 1)
      options.success({ statusCode: 200, data: { data: { ...material, version: 2 } } })
    } else options.success({ statusCode: 200, data: { data: material } })
  }
  await editor.load()
  editor.onTitleInput({ detail: { value: '新标题' } })
  await editor.submit()
  assert.equal(storage.has('lms.noteDraft.v1.42'), false)
  assert.equal(navigation, 'back')
})

test('详情归档和删除均提交当前版本', async () => {
  const detail = page('../miniprogram/pages/materials/detail')
  detail.onLoad({ id: '42' })
  detail.setData({ material })
  requestHandler = options => {
    if (options.method === 'PATCH') {
      assert.deepEqual(options.data, { version: 1, status: 'ARCHIVED' })
      options.success({ statusCode: 200, data: { data: { ...material, version: 2, status: 'ARCHIVED' } } })
    } else {
      assert.equal(options.method, 'DELETE')
      assert.match(options.url, /\/materials\/42\?version=2$/)
      options.success({ statusCode: 204 })
    }
  }
  await detail.changeArchive()
  assert.equal(detail.data.material.version, 2)
  detail.remove()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(navigation, 'back')
})
