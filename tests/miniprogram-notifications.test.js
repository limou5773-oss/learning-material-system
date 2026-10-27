const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const tokenKey = 'lms.accessToken.v1'
const token = 'n'.repeat(43)
const storage = new Map()
let definition, handler, destination
global.Page = value => { definition = value }
global.wx = {
  getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key), request: options => handler(options),
  reLaunch: options => { destination = options.url }, navigateTo: options => { destination = options.url },
}
function reset() { storage.clear(); storage.set(tokenKey, token); destination = '' }
function page(name = 'notifications/index') {
  const file = path.join(__dirname, '../miniprogram/pages', `${name}.js`)
  assert.ok(fs.existsSync(file), '站内通知页面尚未实现')
  delete require.cache[require.resolve(file)]; require(file)
  const view = { ...definition, data: structuredClone(definition.data) }
  view.setData = patch => Object.assign(view.data, patch)
  return view
}
const notification = (id = '91', isRead = false, taskId = '9223372036854775807') => ({
  id, type: 'RESULT_READY', content: '整理结果已生成，请查看并确认。',
  target: { type: 'AI_TASK', taskId }, isRead, createdAt: '2026-10-26T02:00:00Z', channelStatus: 'NOT_REQUESTED',
})
function ok(options, data) { options.success({ statusCode: 200, data: { data } }) }
function fail(options, status = 500, message = '通知读取失败') {
  options.success({ statusCode: status, data: { error: { message } } })
}
function installed(rows = [notification()], total = rows.length) {
  handler = options => {
    assert.equal(options.header.Authorization, `Bearer ${storage.get(tokenKey)}`)
    assert.equal(options.method, 'GET')
    const url = new URL(options.url)
    assert.equal(url.pathname, '/api/v1/notifications')
    ok(options, { items: rows, page: Number(url.searchParams.get('page')), pageSize: 20, total })
  }
}
const click = id => ({ currentTarget: { dataset: { id } } })
const template = () => fs.readFileSync(path.join(__dirname, '../miniprogram/pages/notifications/index.wxml'), 'utf8')

test('审核通知白名单资料与审核ID并先标已读，兼容既有任务通知', async () => {
  reset(); const target = { type: 'MATERIAL', materialId: '42', reviewId: '8', url: 'https://evil.example' }
  const item = { ...notification(), type: 'REVIEW_DECIDED', target }; installed([item]); const view = page(); await view.load()
  assert.equal(view.data.items[0].typeLabel, '审核结果'); assert.equal(view.data.items[0].targetAction, '查看资料')
  let write; handler = options => { write = options }; const opening = view.openTask(click('91')); assert.equal(destination, '')
  ok(write, { ...item, isRead: true }); await opening; assert.equal(destination, '/pages/materials/detail?id=42')
  for (const invalid of [{ ...target, materialId: '../me' }, { ...target, reviewId: 8 }, { ...target, reviewId: '9223372036854775808' }]) {
    reset(); installed([{ ...item, target: invalid }]); const invalidView = page(); await invalidView.load()
    let writes = 0; handler = () => { writes++ }; await invalidView.openTask(click('91')); assert.equal(writes, 0); assert.equal(destination, '')
  }
})

test('我的信息提供通知入口并注册页面，明确微信渠道尚未开通', async () => {
  reset()
  const me = page('me/index')
  assert.equal(typeof me.openNotifications, 'function', '我的信息缺少通知入口')
  me.openNotifications()
  assert.equal(destination, '/pages/notifications/index')
  const app = JSON.parse(fs.readFileSync(path.join(__dirname, '../miniprogram/app.json'), 'utf8'))
  assert.ok(app.pages.includes('pages/notifications/index'))
  assert.match(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/me/index.wxml'), 'utf8'), /bindtap="openNotifications"/)
  const view = page(); installed(); await view.onShow()
  assert.match(view.data.channelNotice, /微信订阅消息尚未开通.*站内通知正常/)
  assert.equal(view.data.items[0].channelStatus, 'NOT_REQUESTED')
  assert.match(view.data.items[0].channelLabel, /未开通/)
})

test('通知按PageView鉴权读取，全量分页与未读筛选从首页开始', async () => {
  reset(); installed([notification(), { ...notification('92', true), type: 'TASK_FAILED' }], 41)
  const requests = []; const serve = handler
  handler = options => { requests.push(new URL(options.url)); serve(options) }
  const view = page(); await view.load()
  assert.equal(requests[0].searchParams.get('unreadOnly'), 'false')
  assert.equal(requests[0].searchParams.get('page'), '1')
  assert.equal(requests[0].searchParams.get('pageSize'), '20')
  assert.equal(view.data.items[0].typeLabel, '结果待确认')
  assert.equal(view.data.items[1].typeLabel, '任务失败')
  assert.equal(view.data.items[0].content, '整理结果已生成，请查看并确认。')
  await view.next(); assert.equal(view.data.page, 2)
  await view.showUnread()
  assert.equal(view.data.page, 1); assert.equal(view.data.unreadOnly, true)
  assert.equal(requests.at(-1).searchParams.get('unreadOnly'), 'true')
  assert.equal(requests.at(-1).searchParams.get('page'), '1')
  await view.next(); await view.previous(); assert.equal(view.data.page, 1)
  await view.showAll(); assert.equal(view.data.unreadOnly, false)
})

test('已读与未读切换真实PUT布尔值，采用返回通知并刷新列表', async () => {
  reset(); installed()
  const view = page(); await view.load()
  const writes = []; let row = notification(); let reads = 0
  handler = options => {
    if (options.method === 'PUT') {
      assert.equal(new URL(options.url).pathname, '/api/v1/notifications/91/read-state')
      writes.push(options.data)
      row = { ...row, isRead: options.data.isRead }
      ok(options, row)
    } else { reads++; ok(options, { items: [row], page: 1, pageSize: 20, total: 1 }) }
  }
  await view.toggleRead(click('91'))
  assert.equal(view.data.items[0].isRead, true)
  await view.toggleRead(click('91'))
  assert.equal(view.data.items[0].isRead, false)
  assert.deepEqual(writes, [{ isRead: true }, { isRead: false }])
  assert.equal(reads, 2)
})

test('未读筛选中标记已读后采用真实新总数，末页空集合仍能返回', async () => {
  reset(); installed([notification()], 21)
  const view = page(); await view.showUnread(); await view.next()
  handler = options => options.method === 'PUT' ? ok(options, notification('91', true))
    : ok(options, { items: [], page: 2, pageSize: 20, total: 20 })
  await view.toggleRead(click('91'))
  assert.equal(view.data.page, 2); assert.equal(view.data.total, 20); assert.deepEqual(view.data.items, [])
  installed([notification()], 20); await view.previous()
  assert.equal(view.data.page, 1); assert.equal(view.data.items.length, 1)
  assert.match(template(), /page > 1 \|\| total > pageSize/)
  assert.match(template(), /当前页暂无通知，请返回上一页/)
  assert.match(template(), /page === 1 && total === 0/)
})

test('跨端变化令高页总数归零时仍显示上一页且不冒充完全无通知', async () => {
  reset(); installed([notification()], 21)
  const view = page(); await view.load()
  installed([], 0); await view.next()
  assert.equal(view.data.page, 2); assert.equal(view.data.total, 0)
  installed([], 0); await view.previous(); assert.equal(view.data.page, 1)
  assert.match(template(), /page > 1 \|\| total > 0/)
})

test('查看任务必须先保存已读，再按字符串BIGINT跳入真实任务详情路径', async () => {
  reset(); installed()
  const view = page(); await view.load()
  let write
  handler = options => { write = options }
  const opening = view.openTask(click('91'))
  assert.equal(destination, ''); assert.equal(view.data.saving, true)
  assert.equal(write.method, 'PUT'); assert.deepEqual(write.data, { isRead: true })
  ok(write, notification('91', true)); await opening
  assert.equal(destination, '/pages/tasks/detail?id=9223372036854775807')
  assert.equal(view.data.items[0].isRead, true)
})

test('已读保存失败保留当前列表，提示可重试且不跳任务', async () => {
  reset(); installed(); const view = page(); await view.load()
  handler = options => fail(options, 500, '已读状态保存失败')
  await view.openTask(click('91'))
  assert.equal(destination, ''); assert.equal(view.data.items[0].isRead, false)
  assert.equal(view.data.actionError, '已读状态保存失败'); assert.equal(view.data.error, '')
  handler = options => ok(options, notification('91', true))
  await view.openTask(click('91'))
  assert.equal(destination, '/pages/tasks/detail?id=9223372036854775807')
})

test('已读已保存但列表刷新失败时，与写失败分别说明并支持读取重试', async () => {
  reset(); installed(); const view = page(); await view.load()
  handler = options => options.method === 'PUT' ? ok(options, notification('91', true)) : fail(options)
  await view.toggleRead(click('91'))
  assert.match(view.data.error, /已保存.*刷新失败/); assert.equal(view.data.actionError, '')
  installed([notification('91', true)]); await view.load()
  assert.equal(view.data.error, ''); assert.equal(view.data.items[0].isRead, true)
})

test('任务target仅接受AI_TASK和正BIGINT字符串，拒绝数值、越界与任意URL', async () => {
  reset()
  const targets = [
    { type: 'URL', taskId: '1', url: 'https://example.com' },
    { type: 'AI_TASK', taskId: 42 }, { type: 'AI_TASK', taskId: '0' },
    { type: 'AI_TASK', taskId: '-1' }, { type: 'AI_TASK', taskId: '9223372036854775808' },
    { type: 'AI_TASK', taskId: '../me/index' }, { type: 'AI_TASK', taskId: '1?url=evil' },
  ]
  for (const target of targets) {
    installed([{ ...notification(), target }]); const view = page(); await view.load()
    let writes = 0; handler = () => { writes++ }
    await view.openTask(click('91'))
    assert.equal(writes, 0); assert.equal(destination, '')
    assert.match(view.data.actionError, /任务目标无效/)
  }
})

test('列表加载、保存中禁用重复刷新、筛选、翻页及行操作', async () => {
  reset(); const view = page(); let pending; let count = 0
  handler = options => { pending = options; count++ }
  const reading = view.load()
  view.load(); view.showUnread(); view.next(); view.previous(); view.toggleRead(click('91')); view.openTask(click('91'))
  assert.equal(count, 1); assert.equal(view.data.unreadOnly, false)
  ok(pending, { items: [notification()], page: 1, pageSize: 20, total: 21 }); await reading
  const writing = view.openTask(click('91'))
  assert.equal(view.data.saving, true)
  view.load(); view.showUnread(); view.next(); view.previous(); view.toggleRead(click('91')); view.openTask(click('91'))
  assert.equal(count, 2); assert.equal(view.data.page, 1); assert.equal(view.data.unreadOnly, false)
  fail(pending, 500, '请重试'); await writing
  assert.equal(view.data.saving, false)
  assert.match(template(), /disabled="\{\{loading \|\| saving/)
})

test('真正空列表、网络错误与在途状态分别显示，失败不伪装空集合', async () => {
  reset(); const view = page()
  handler = options => options.fail()
  await view.load(); assert.match(view.data.error, /无法连接服务器/)
  assert.equal(view.data.loaded, false)
  installed([]); await view.load()
  assert.equal(view.data.error, ''); assert.equal(view.data.loaded, true)
  assert.deepEqual(view.data.items, [])
  assert.match(template(), /!loading && !error && loaded && !items.length && page === 1 && total === 0/)
  assert.match(template(), /bindtap="load"/)
})

test('缺少会话与当前会话401清空本人数据并返回登录', async () => {
  reset(); storage.clear(); const view = page(); let count = 0
  handler = () => { count++ }; await view.load()
  assert.equal(count, 0); assert.equal(destination, '/pages/login/index?reason=expired')
  reset(); installed(); await view.load()
  handler = options => fail(options, 401, '登录失效'); await view.load()
  assert.equal(storage.has(tokenKey), false); assert.deepEqual(view.data.items, [])
  assert.equal(destination, '/pages/login/index?reason=expired')
})

test('离页后列表与保存的迟到响应不能恢复本人数据或跳详情', async () => {
  reset(); installed(); const view = page(); await view.load()
  let pending; handler = options => { pending = options }
  const reading = view.load(); view.onHide()
  ok(pending, { items: [notification()], page: 1, pageSize: 20, total: 1 }); await reading
  assert.deepEqual(view.data.items, []); assert.equal(view.data.loading, false)
  installed(); await view.onShow(); handler = options => { pending = options }
  const writing = view.openTask(click('91')); view.onUnload()
  ok(pending, notification('91', true)); await writing
  assert.equal(destination, ''); assert.deepEqual(view.data.items, [])
})

test('账号变化丢弃本人列表和写入结果，新账号从首页重新读取', async () => {
  reset(); installed([notification()], 21); const view = page(); await view.load(); await view.next()
  let pending; handler = options => { pending = options }
  const writing = view.openTask(click('91'))
  storage.set(tokenKey, 's'.repeat(43)); ok(pending, notification('91', true)); await writing
  assert.equal(destination, ''); assert.deepEqual(view.data.items, [])
  assert.match(view.data.error, /账号已变化.*重新读取/)
  installed([notification('92')]); await view.onShow()
  assert.equal(view.data.page, 1); assert.equal(view.data.items[0].id, '92')
})

test('旧账号列表和保存迟到401均不清除新token或退出新会话', async () => {
  for (const operation of ['load', 'openTask']) {
    reset(); installed(); const view = page(); await view.load()
    let pending; handler = options => { pending = options }
    const request = operation === 'load' ? view.load() : view.openTask(click('91'))
    const newToken = 's'.repeat(43); storage.set(tokenKey, newToken)
    fail(pending, 401, '旧会话失效'); await request
    assert.equal(storage.get(tokenKey), newToken); assert.equal(destination, '')
    assert.deepEqual(view.data.items, []); assert.match(view.data.error, /账号已变化/)
  }
})
