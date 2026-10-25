const test = require('node:test')
const assert = require('node:assert/strict')

test('双端PDF来源定位显示真实页码，旧元数据与笔记仍可区分', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const presenter of [web, mini]) {
    assert.equal(presenter.sourceLocator({ extractionMethod: 'PDF_TEXT', pageNo: 3, chunkNo: 2000300 }), 'PDF 第 3 页')
    assert.equal(presenter.sourceLocator({ extractionMethod: 'METADATA', pageNo: null }), '资料标题元数据，正文未提取')
    assert.equal(presenter.sourceLocator({ extractionMethod: 'TEXT', chunkNo: 2 }), '文字笔记片段 2')
    assert.equal(presenter.sourceLocator(undefined), '来源暂不可用')
    assert.match(presenter.taskErrorMessage('PDF_OCR_REQUIRED'), /OCR.*空白|空白.*OCR/)
    assert.match(presenter.taskErrorMessage('PDF_TEXT_LIMIT_EXCEEDED'), /缩小.*页段/)
  }
})

test('小程序实际任务页保留PDF处理范围与页码，失败任务提供可理解的说明', async () => {
  let definition
  global.Page = value => { definition = value }
  let failed = false
  global.wx = {
    getStorageSync: () => 't'.repeat(43),
    request(options) {
      const data = options.url.includes('/steps?') ? { items: [] }
        : options.url.endsWith('/result') ? { taskId: '91', processedPages: [2], totalPages: 3,
          generationMode: 'MOCK', warnings: ['第3页没有可用文字层，需OCR或为空白页'],
          cards: [{ candidateKey: 'pdf-2', sources: [{ chunkId: '17', quoteText: 'Page two' }] }] }
        : options.url.endsWith('/sources') ? [{ chunkId: '17', extractionMethod: 'PDF_TEXT', pageNo: 2,
          chunkNo: 2000200, content: 'Page two' }]
        : { id: '91', status: failed ? 'FAILED' : 'WAIT_CONFIRM', stage: 'EXTRACT_PDF_TEXT',
          errorCode: failed ? 'PDF_OCR_REQUIRED' : null }
      options.success({ statusCode: 200, data: { data } })
    },
  }
  delete require.cache[require.resolve('../miniprogram/pages/tasks/detail')]
  require('../miniprogram/pages/tasks/detail')
  const page = { ...definition, data: structuredClone(definition.data) }
  page.setData = patch => Object.assign(page.data, patch)
  page.onLoad({ id: '91' }); await page.load()
  assert.equal(page.data.candidateCards[0].sourceViews[0].locator, 'PDF 第 2 页')
  assert.deepEqual(page.data.candidate.processedPages, [2])
  assert.equal(page.data.task.stageLabel, '提取PDF文字')
  failed = true; await page.load()
  assert.match(page.data.task.errorMessage, /OCR/)
  assert.equal(page.data.candidate, null)
})
