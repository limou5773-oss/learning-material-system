const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('双端解释目标与检索的外发范围，未开启不检索', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const presenter of [web, mini]) {
    assert.match(presenter.goalDescriptions.REVIEW_CARDS, /复习.*卡片/)
    assert.match(presenter.goalDescriptions.EXTRACT_KEY_POINTS, /重点/)
    assert.match(presenter.goalDescriptions.LINK_NOTES, /关联建议.*确认/)
    assert.match(presenter.relatedSearchDescription, /本人已有知识卡片/)
    assert.match(presenter.relatedSearchDescription, /模型启用.*片段.*关联建议/)
    assert.match(presenter.relatedSearchDescription, /不开启.*不检索/)
    assert.match(presenter.externalProcessingConsent, /允许检索.*已有知识卡片.*片段.*模型服务/)
  }
  assert.equal(web.relatedSearchDescription, mini.relatedSearchDescription)
  assert.deepEqual(web.goalDescriptions, mini.goalDescriptions)
})

test('双端实际工具步骤和agent失败提供中文解释，兼容旧步骤与未知工具', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  for (const [name, expected] of Object.entries({ planner: /规划/, search_personal_materials: /检索.*本人.*卡片/,
    prepare_source: /来源/, extract_pdf_text: /PDF/, recognize_image: /识别/,
    generate_summary_cards: /候选/, validate_candidate: /核验/ })) {
    assert.match(web.toolLabel(name), expected)
    assert.equal(web.toolLabel(name), mini.toolLabel(name))
  }
  assert.equal(web.toolLabel('future_tool'), 'future_tool')
  assert.equal(mini.toolLabel('future_tool'), 'future_tool')
  assert.match(web.stageLabels.PLANNER, /规划/)
  assert.match(web.stageLabels.SEARCH_PERSONAL_MATERIALS, /检索/)
  assert.match(web.taskErrorMessage('AGENT_FUTURE_FAILURE'), /智能体.*失败.*未发布候选/)
  assert.equal(web.taskErrorMessage('AGENT_FUTURE_FAILURE'), mini.taskErrorMessage('AGENT_FUTURE_FAILURE'))
  for (const [code, expected] of Object.entries({
    AGENT_PLAN_INVALID: /规划.*格式|工具.*参数/,
    AGENT_ACTION_NOT_ALLOWED: /动作.*状态/,
    AGENT_SEARCH_NOT_ALLOWED: /未允许.*检索/,
    AGENT_PLANNER_LIMIT: /规划.*上限/,
    STEP_LIMIT: /步骤.*上限/,
    AGENT_STOP_SOURCE_UNREADABLE: /来源.*读取/,
    AGENT_STOP_NO_USABLE_SOURCE: /可用正文/,
    AGENT_STOP_UNSUPPORTED_REQUEST: /支持.*请求|请求.*支持/,
  })) {
    assert.match(web.taskErrorMessage(code), expected, code)
    assert.equal(web.taskErrorMessage(code), mini.taskErrorMessage(code), code)
  }
})

test('新关联保留已有卡片版本，默认不保存；显示候选标题和已有卡片标识', async () => {
  const web = await import('../web/src/tasks.ts')
  const mini = require('../miniprogram/utils/source-presentation')
  const { createConfirmationDraft, buildConfirmationInput } = await import('../web/src/confirmation.ts')
  const relation = { relationKey: 'related-1', source: { candidateKey: 'c1' },
    target: { cardId: '57', cardVersion: 3 }, type: 'RELATED', evidenceText: '两者介绍相同概念' }
  const candidate = { taskId: '9', resultVersion: 1, sourceVersion: 2, summary: '摘要', tags: [],
    cards: [{ candidateKey: 'c1', title: '复习标题', content: '正文', sources: [] }], relations: [relation] }
  const draft = createConfirmationDraft(candidate)
  assert.deepEqual(buildConfirmationInput(draft).relations, [])
  draft.relations[0].selected = true
  assert.deepEqual(buildConfirmationInput(draft).relations, [relation])
  draft.cards[0].selected = false
  assert.throws(() => buildConfirmationInput(draft), /至少选择/)
  for (const presenter of [web, mini]) {
    assert.equal(presenter.relationEndpointLabel(relation.source, candidate.cards), '复习标题')
    assert.equal(presenter.relationEndpointLabel(relation.target, candidate.cards), '已有卡片 #57')
  }
})

test('双端页面使用目标说明、检索说明与实际服务端工具和错误展示', () => {
  for (const file of ['web/src/views/TaskCreateView.vue', 'miniprogram/pages/tasks/new.wxml']) {
    const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8')
    assert.match(source, /goalDescriptions/)
    assert.match(source, /relatedSearchDescription/)
  }
  for (const file of ['web/src/views/TaskDetailView.vue', 'miniprogram/pages/tasks/detail.wxml']) {
    const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8')
    assert.match(source, /toolLabel/)
    assert.match(source, /errorMessage|taskErrorMessage\(step.errorCode\)/)
    assert.match(source, /outputSummary/)
  }
})
