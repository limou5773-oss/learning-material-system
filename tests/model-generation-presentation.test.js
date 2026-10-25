const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('本地桩：双端按服务端模式显示候选、真实模型标识和人工核对提示', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const presenter of [web, mini]) {
    assert.equal(typeof presenter.generationPresentation, 'function')
    const model = presenter.generationPresentation('MODEL', { provider: 'deepseek', model: 'local-test-model', promptVersion: 'test-v1' })
    assert.equal(model.label, '模型生成')
    assert.match(model.modelLabel, /deepseek.*local-test-model.*test-v1/)
    assert.doesNotMatch(model.label + model.warning, /模拟/)
    assert.match(model.warning, /人工核对/)
    assert.match(model.warning, /引用.*支持|支持.*引用/)
    assert.equal(presenter.generationPresentation('MODEL').modelLabel, '模型标识暂不可用')
    assert.match(presenter.generationPresentation('MOCK', null).label, /模拟/)
    assert.match(presenter.generationPresentation('MOCK').warning, /模拟/)
    assert.equal(presenter.savedGenerationLabel('MODEL'), '模型生成 · 人工确认')
    assert.equal(presenter.savedGenerationLabel('MOCK'), '模拟候选 · 人工确认')
    assert.equal(presenter.savedGenerationLabel(null), '人工确认保存')
    assert.match(presenter.externalProcessingConsent, /所选.*文字.*模型/)
    assert.match(presenter.externalProcessingConsent, /图片.*OCR/)
  }
})

test('双端提供可操作且一致的模型失败提示，不暗示已经成功生成', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const [code, expected] of Object.entries({
    MODEL_NOT_CONFIGURED: /配置.*密钥/, MODEL_AUTH_FAILED: /鉴权/, MODEL_TIMEOUT: /超时/,
    MODEL_RATE_LIMITED: /限流/, MODEL_INVALID_RESPONSE: /格式|响应/,
    MODEL_OUTPUT_INVALID: /来源|引用/, MODEL_SOURCE_TEXT_REQUIRED: /正文|OCR/, MODEL_SOURCE_INVALID: /来源|引用/,
    MODEL_TEXT_LIMIT_EXCEEDED: /上限/, MODEL_ATTEMPT_LIMIT: /上限/,
    MODEL_CONFIG_INVALID: /配置/, MODEL_CREDENTIALS_INVALID: /凭证/,
    MODEL_INPUT_LIMIT: /上限/, MODEL_INVALID_INPUT: /输入/, MODEL_INTERRUPTED: /中断/,
    MODEL_CONNECTION_FAILED: /连接/, MODEL_QUOTA_EXCEEDED: /额度/, MODEL_PROVIDER_UNAVAILABLE: /暂不可用/,
    MODEL_REQUEST_REJECTED: /拒绝/, MODEL_REDIRECT_REJECTED: /跳转/, MODEL_OUTPUT_TRUNCATED: /不完整/,
    MODEL_CONTENT_FILTERED: /可用内容/, MODEL_EMPTY_RESULT: /可用内容/,
  })) {
    assert.match(web.taskErrorMessage(code), expected, code)
    assert.equal(web.taskErrorMessage(code), mini.taskErrorMessage(code), code)
  }
  assert.match(web.taskErrorMessage('MODEL_FUTURE_ERROR'), /模型处理失败/)
})

test('双端视图使用模式展示，同意文案覆盖模型和OCR，任务阶段不写死模拟', () => {
  for (const file of ['web/src/views/TaskDetailView.vue', 'web/src/views/TaskConfirmView.vue',
    'miniprogram/pages/tasks/detail.wxml', 'miniprogram/pages/tasks/confirm.wxml']) {
    const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8')
    assert.doesNotMatch(source, />模拟生成<|模拟候选需核对|aria-label="模拟/)
    assert.match(source, /generationView/)
  }
  for (const file of ['web/src/views/TaskCreateView.vue', 'miniprogram/pages/tasks/new.wxml']) {
    const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8')
    assert.match(source, /externalProcessingConsent/)
    assert.doesNotMatch(source, /当前为模拟|仍为模拟|启用模拟处理器/)
  }
})
