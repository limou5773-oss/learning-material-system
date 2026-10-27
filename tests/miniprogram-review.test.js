const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const key = 'lms.accessToken.v1', token = 'r'.repeat(43), storage = new Map()
let definition, handler, destination, confirm = true
global.Page = value => { definition = value }
global.wx = { getStorageSync: k => storage.get(k), removeStorageSync: k => storage.delete(k),
  request: o => handler(o), reLaunch: o => { destination = o.url }, navigateTo: o => { destination = o.url },
  showModal: o => o.success({ confirm }) }
const material = (v = 3) => ({ id: '42', title: '资料', type: 'NOTE', version: v, noteText: '原正文', accessState: 'RESTRICTED', tags: [], status: 'ACTIVE' })
const row = { id: '8', materialId: '42', materialVersion: 3, version: 1, title: '资料', status: 'REJECTED', isCurrent: true, decisionReason: '请修正', reason: '请求审核' }
function page() { storage.set(key, token); destination = ''; confirm = true; const file = '../miniprogram/pages/materials/detail'; delete require.cache[require.resolve(file)]; require(file)
  const v = { ...definition, data: structuredClone(definition.data) }; v.setData = p => Object.assign(v.data, p); v.onLoad({ id: '42' }); return v }
function ok(o, data) { o.success({ statusCode: 200, data: { data } }) }
function fail(o, status, message) { o.success({ statusCode: status, data: { error: { message } } }) }
function serve(v = 3, rows = [row]) { handler = o => ok(o, o.url.includes('review-requests') ? { items: rows, page: 1, pageSize: 20, total: rows.length } : material(v)) }
const template = () => fs.readFileSync(path.join(__dirname, '../miniprogram/pages/materials/detail.wxml'), 'utf8')

test('小程序本人审核历史和受限提示保留原文，受限AI入口程序调用亦禁止', async () => {
  const v = page(); serve(); await v.load(); assert.equal(typeof v.submitReview, 'function', '应实现本人送审')
  assert.equal(v.data.reviewItems[0].decisionReason, '请修正'); assert.equal(v.data.material.noteText, '原正文')
  v.newTask(); assert.equal(destination, ''); assert.match(template(), /修正后重新送审/); assert.match(template(), /正文和原文件/)
  assert.match(template(), /disabled="\{\{[^}]*material.accessState === 'RESTRICTED'/)
})

test('小程序送审提交当前展示版本与trim原因，取消确认不写且409保留草稿', async () => {
  const v = page(); serve(3, []); await v.load(); assert.equal(typeof v.submitReview, 'function')
  v.onReviewReason({ detail: { value: '  请求审核  ' } }); let calls = 0, body
  handler = o => { calls++; body = o.data; fail(o, 409, '资料版本已变化，请刷新') }
  confirm = false; await v.submitReview(); assert.equal(calls, 0)
  confirm = true; await v.submitReview(); assert.equal(calls, 1); assert.deepEqual(body, { version: 3, reason: '请求审核' })
  assert.equal(v.data.reviewReason, '  请求审核  '); assert.match(v.data.reviewActionError, /刷新/)
  serve(4); await v.refreshReviewMaterial(); assert.equal(v.data.material.version, 4); assert.equal(v.data.reviewReason, '  请求审核  ')
})

test('小程序送审成功和历史重读失败区分，离页迟到401不退出新账号', async () => {
  const v = page(); serve(3, []); await v.load(); assert.equal(typeof v.submitReview, 'function'); v.onReviewReason({ detail: { value: '审核原因' } })
  handler = o => o.method === 'POST' ? ok(o, row) : fail(o, 500, '历史服务不可用')
  await v.submitReview(); assert.match(v.data.reviewNotice, /送审已提交/); assert.match(v.data.reviewError, /历史服务不可用/); assert.equal(v.data.reviewReason, '')
  let pending; handler = o => { pending = o }; const loading = v.load(); storage.set(key, 's'.repeat(43)); v.onHide(); fail(pending, 401, '旧会话')
  await loading; assert.equal(storage.get(key), 's'.repeat(43)); assert.equal(destination, ''); assert.equal(v.data.material, null); assert.deepEqual(v.data.reviewItems, [])
})

test('小程序翻阅旧历史不会解除当前版本已送审提示', async () => {
  const v = page(); serve(); await v.load(); assert.equal(v.data.alreadySubmitted, true)
  handler = o => ok(o, { items: [{ ...row, id: '1', materialVersion: 1 }], page: 2, pageSize: 20, total: 21 })
  await v.loadReviewHistory(2); assert.equal(v.data.alreadySubmitted, true)
})

test('小程序账号已变化时禁止用旧资料发起新送审，请求之前清空本人数据', async () => {
  const v = page(); serve(3, []); await v.load(); v.onReviewReason({ detail: { value: '旧会话理由' } })
  storage.set(key, 's'.repeat(43)); let writes = 0; handler = o => { writes++; ok(o, row) }
  await v.submitReview(); assert.equal(writes, 0); assert.equal(v.data.material, null); assert.equal(v.data.reviewReason, '')
})

test('本人详情离页后收藏迟到成功不能恢复资料，旧PDF401不能退出新会话', async () => {
  const v = page(); serve(); await v.load(); let pending
  handler = o => { pending = o }; const saving = v.toggleFavorite(); v.onHide(); ok(pending, undefined); await saving
  assert.equal(v.data.material, null)
  serve(); await v.load(); v.setData({ material: { ...material(), type: 'PDF' } }); wx.downloadFile = o => { pending = o }
  const reading = v.openPdf(); storage.set(key, 's'.repeat(43)); pending.success({ statusCode: 401 }); await reading
  assert.equal(storage.get(key), 's'.repeat(43)); assert.equal(destination, ''); assert.equal(v.data.material, null)
})
