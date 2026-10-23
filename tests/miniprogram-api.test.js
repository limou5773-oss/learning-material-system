const test = require('node:test')
const assert = require('node:assert/strict')

const storage = new Map()
let nextRequest
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  request: options => nextRequest(options),
}

const api = require('../miniprogram/utils/api')
const token = 'a'.repeat(43)

test.beforeEach(() => {
  storage.clear()
  nextRequest = () => { throw new Error('没有设置请求模拟') }
})

test('小程序登录标注客户端类型并保存会话', async () => {
  nextRequest = options => {
    assert.equal(options.method, 'POST')
    assert.equal(options.data.clientType, 'MINIPROGRAM')
    assert.equal(options.header.Authorization, undefined)
    options.success({ statusCode: 201, data: { data: { accessToken: token }, requestId: 'r1' } })
  }
  const session = await api.login('student_1', 'password123')
  api.saveToken(session.accessToken)
  assert.equal(api.getToken(), token)
})

test('本人请求带令牌，401 清除本地会话', async () => {
  api.saveToken(token)
  nextRequest = options => {
    assert.equal(options.header.Authorization, `Bearer ${token}`)
    options.success({ statusCode: 401, data: { error: { code: 'UNAUTHORIZED', message: '请先登录' } } })
  }
  await assert.rejects(api.getMe(), error => error.status === 401 && error.code === 'UNAUTHORIZED')
  assert.equal(api.getToken(), '')
})

test('退出请求处理 204；网络错误时保留会话以便重试', async () => {
  api.saveToken(token)
  nextRequest = options => options.success({ statusCode: 204, data: '' })
  assert.equal(await api.logout(), undefined)
  assert.equal(api.getToken(), token)
  nextRequest = options => options.fail({ errMsg: 'request:fail' })
  await assert.rejects(api.getMe(), error => error.status === 0)
  assert.equal(api.getToken(), token)
})

test('拒绝无效令牌和非契约成功响应', async () => {
  assert.throws(() => api.saveToken('bad'), /无效/)
  nextRequest = options => options.success({ statusCode: 200, data: { userId: '1' } })
  await assert.rejects(api.getMe(), error => error.message === '服务端响应格式错误')
})
