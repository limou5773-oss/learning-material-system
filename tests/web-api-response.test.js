const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('../web/node_modules/typescript')

function client(t, response) {
  const previous = global.fetch
  global.fetch = async () => response
  t.after(() => { global.fetch = previous })
  const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../web/src/api.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', source)(() => ({ session: { value: { accessToken: 'software-test-token' } } }), module, module.exports)
  return module.exports
}

for (const status of [500, 401]) test(`空的 ${status} 响应保留 HTTP 状态并报告中文格式错误`, async t => {
  const cause = new SyntaxError('Unexpected end of JSON input')
  const api = client(t, { status, ok: false, json: async () => { throw cause } })
  await assert.rejects(api.api('/admin/statistics'), error => {
    assert.ok(error instanceof api.ApiRequestError)
    assert.equal(error.status, status)
    assert.match(error.message, /服务器响应格式错误/)
    assert.equal(error.cause, cause)
    return true
  })
})

test('不可解析的成功响应明确失败，不伪装成空数据或成功', async t => {
  const api = client(t, { status: 200, ok: true, json: async () => { throw new SyntaxError('invalid JSON') } })
  await assert.rejects(api.api('/admin/statistics'), error => error instanceof api.ApiRequestError && error.status === 200 && /响应格式错误/.test(error.message))
})

test('有效业务错误继续保留服务端说明及版本冲突信息', async t => {
  const api = client(t, { status: 409, ok: false, json: async () => ({ error: { message: '资料版本已变化', currentVersion: 2 } }) })
  await assert.rejects(api.api('/materials/1', 'PATCH', { version: 1 }), error => error instanceof api.ApiRequestError && error.status === 409 && error.currentVersion === 2 && error.message === '资料版本已变化')
})
