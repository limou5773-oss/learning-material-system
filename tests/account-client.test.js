const test = require('node:test')
const assert = require('node:assert/strict')

const token = 'a'.repeat(43), otherToken = 'b'.repeat(43)
const storage = new Map()
let definition, requestHandler, loginHandler, navigation
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  request: options => requestHandler(options),
  login: options => loginHandler(options),
  reLaunch: options => { navigation = options.url },
}
const api = require('../miniprogram/utils/api')
const user = { id: '1', username: 'student', displayName: '原昵称', hasWechatBinding: false }
function page(name) {
  const filename = `../miniprogram/pages/${name}/index`
  delete require.cache[require.resolve(filename)]
  require(filename)
  const instance = { ...definition, data: { ...definition.data } }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}
const succeed = (request, data) => request.success({ statusCode: 200, data: { data } })
const fail = (request, statusCode, code, message) => request.success({ statusCode, data: { error: { code, message } } })
test.beforeEach(() => { storage.clear(); navigation = ''; requestHandler = () => { throw new Error('未设置请求') } })

test('已绑定微信登录无需账号密码，成功后直接进入我的信息', async () => {
  const login = page('login')
  login.onLoad({})
  assert.equal(login.data.showPasswordLogin, false)
  assert.equal(login.data.username, '')
  assert.equal(login.data.password, '')
  loginHandler = options => options.success({ code: 'fresh-bound-code' })
  requestHandler = options => {
    assert.match(options.url, /\/auth\/wechat-sessions$/)
    assert.deepEqual(options.data, { code: 'fresh-bound-code' })
    succeed(options, { accessToken: token, user: { ...user, hasWechatBinding: true } })
  }
  await login.submitWechat()
  assert.equal(api.getToken(), token)
  assert.equal(navigation, '/pages/me/index')
})

test('微信登录每次取得新code，未绑定和未配置给出可操作提示，不保存会话', async () => {
  const login = page('login')
  login.onLoad({})
  let attempts = 0
  loginHandler = options => options.success({ code: `fresh-${++attempts}` })
  requestHandler = options => {
    assert.match(options.url, /\/auth\/wechat-sessions$/)
    assert.deepEqual(options.data, { code: `fresh-${attempts}` })
    assert.equal(options.header.Authorization, undefined)
    if (attempts === 1) fail(options, 409, 'WECHAT_BIND_REQUIRED', '尚未绑定')
    else if (attempts === 2) fail(options, 503, 'WECHAT_NOT_CONFIGURED', '未配置')
    else succeed(options, { accessToken: token })
  }
  await login.submitWechat()
  assert.match(login.data.error, /已有账号密码登录.*「我的」绑定/)
  assert.equal(api.getToken(), '')
  await login.submitWechat()
  assert.match(login.data.error, /账号密码登录/)
  assert.equal(api.getToken(), '')
  await login.submitWechat()
  assert.equal(api.getToken(), token)
  assert.equal(navigation, '/pages/me/index')
})

test('昵称trim后更新本人，提交中防重，超过72字节的新密码在字段旁提示', async () => {
  api.saveToken(token)
  const me = page('me')
  me.setData({ user, displayName: '  新昵称  ' })
  let pending, requests = 0
  requestHandler = options => { requests++; pending = options }
  const saving = me.submitName()
  await me.submitName()
  assert.equal(requests, 1)
  assert.deepEqual(pending.data, { displayName: '新昵称' })
  succeed(pending, { ...user, displayName: '新昵称' })
  await saving
  assert.equal(me.data.user.displayName, '新昵称')
  me.setData({ currentPassword: 'old-password', newPassword: '密'.repeat(25) })
  await me.submitPassword()
  assert.match(me.data.newPasswordError, /72 字节/)
  assert.equal(requests, 1)
})

test('原密码错误保留会话并清空密码；改密成功清token并回登录', async () => {
  api.saveToken(token)
  const me = page('me')
  me.setData({ user, currentPassword: 'wrong-pass', newPassword: 'new-password' })
  requestHandler = options => fail(options, 422, 'CURRENT_PASSWORD_INCORRECT', '当前密码不正确')
  await me.submitPassword()
  assert.equal(api.getToken(), token)
  assert.equal(me.data.errorAction, 'password')
  assert.equal(me.data.currentPassword, '')
  assert.equal(me.data.newPassword, '')
  assert.equal(navigation, '')
  me.setData({ currentPassword: 'old-password', newPassword: 'new-password' })
  requestHandler = options => {
    assert.deepEqual(options.data, { currentPassword: 'old-password', newPassword: 'new-password' })
    succeed(options, user)
  }
  await me.submitPassword()
  assert.equal(api.getToken(), '')
  assert.match(navigation, /reason=password-changed$/)
})

test('绑定冲突保留状态；成功绑定后以密码解绑，清token回登录', async () => {
  api.saveToken(token)
  const me = page('me')
  me.setData({ user })
  loginHandler = options => options.success({ code: 'fresh-bind' })
  requestHandler = options => fail(options, 409, 'WECHAT_BIND_CONFLICT', '已绑定其他账号')
  await me.submitBinding()
  assert.equal(me.data.user.hasWechatBinding, false)
  assert.equal(api.getToken(), token)
  assert.match(me.data.error, /原账号解绑/)
  requestHandler = options => succeed(options, { ...user, hasWechatBinding: true })
  await me.submitBinding()
  assert.equal(me.data.user.hasWechatBinding, true)
  await me.submitUnbind()
  assert.match(me.data.unbindPasswordError, /当前密码/)
  me.setData({ unbindPassword: 'old-password' })
  requestHandler = options => {
    assert.equal(options.method, 'DELETE')
    assert.deepEqual(options.data, { currentPassword: 'old-password' })
    options.success({ statusCode: 204 })
  }
  await me.submitUnbind()
  assert.equal(api.getToken(), '')
  assert.equal(me.data.unbindPassword, '')
  assert.match(navigation, /reason=wechat-unbound$/)
})

test('账号变化后迟到资料与微信code不能写入新会话', async () => {
  api.saveToken(token)
  const me = page('me')
  let pending
  requestHandler = options => { pending = options }
  const refreshing = me.refresh()
  api.saveToken(otherToken)
  succeed(pending, user)
  await refreshing
  assert.equal(me.data.user, null)
  api.saveToken(token)
  let completeLogin
  loginHandler = options => { completeLogin = options.success }
  const binding = api.bindWechat()
  api.saveToken(otherToken)
  completeLogin({ code: 'late-code' })
  await assert.rejects(binding, error => error.code === 'SESSION_CHANGED')
  assert.equal(api.getToken(), otherToken)
})
