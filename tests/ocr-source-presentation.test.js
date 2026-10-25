const test = require('node:test')
const assert = require('node:assert/strict')

test('双端明确区分图片OCR、扫描PDF OCR和PDF文字层，并解释配置及识别错误', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const presenter of [web, mini]) {
    assert.equal(presenter.sourceLocator({ extractionMethod: 'OCR', pageNo: null }), '图片 OCR 识别文字')
    assert.equal(presenter.sourceLocator({ extractionMethod: 'OCR', pageNo: 3 }), 'PDF 第 3 页 · OCR 识别文字')
    assert.equal(presenter.sourceLocator({ extractionMethod: 'PDF_TEXT', pageNo: 2 }), 'PDF 第 2 页')
    assert.match(presenter.taskErrorMessage('OCR_NOT_CONFIGURED'), /配置/)
    assert.match(presenter.taskErrorMessage('OCR_EMPTY_RESULT'), /文字|内容/)
    assert.match(presenter.taskErrorMessage('OCR_TIMEOUT'), /超时/)
    assert.match(presenter.taskErrorMessage('OCR_AUTH_FAILED'), /凭证|密钥/)
  }
})
