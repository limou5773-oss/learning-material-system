const test = require('node:test')
const assert = require('node:assert/strict')

async function forms() {
  try { return await import('../web/src/confirmation.ts') }
  catch (error) {
    if (error.code === 'ERR_MODULE_NOT_FOUND') assert.fail('尚未实现候选确认表单逻辑')
    throw error
  }
}

function candidate() {
  const source = { chunkId: '17', quoteText: '实际原文', startOffset: 0, endOffset: 4 }
  return {
    taskId: '91', resultVersion: 1, sourceVersion: 1, generationMode: 'MOCK', summary: '模拟摘要',
    tags: ['模拟结果'], warnings: ['模拟生成'], processedPages: [], relations: [],
    cards: [
      { candidateKey: 'a', title: '概念', content: '概念内容', tags: ['概念'], sources: [source] },
      { candidateKey: 'b', title: '示例', content: '示例内容', sources: [source], parentCandidateKey: 'a' },
    ],
  }
}

test('确认表单只保存选中且编辑后的卡片，来源不受表单修改影响', async () => {
  const { createConfirmationDraft, buildConfirmationInput } = await forms()
  const original = candidate()
  const draft = createConfirmationDraft(original)
  draft.summary = '人工核对摘要'
  draft.tagsText = '生物，复习'
  draft.cards[0].selected = false
  draft.cards[1].title = '人工修改的示例'
  const input = buildConfirmationInput(draft)
  assert.equal(input.summary, '人工核对摘要')
  assert.deepEqual(input.tags, ['生物', '复习'])
  assert.equal(input.tagMode, 'MERGE')
  assert.deepEqual(input.cards.map(card => card.candidateKey), ['b'])
  assert.equal(input.cards[0].parentCandidateKey, null)
  assert.equal(input.cards[0].title, '人工修改的示例')
  assert.deepEqual(input.cards[0].sources, original.cards[1].sources)
  input.cards[0].sources[0].quoteText = '不得修改原始候选'
  assert.equal(original.cards[1].sources[0].quoteText, '实际原文')
  assert.equal(draft.cards[1].sources[0].quoteText, '实际原文')
})

test('确认前拒绝空选择、重复标签和未选择卡片上的关联', async () => {
  const { createConfirmationDraft, buildConfirmationInput } = await forms()
  const draft = createConfirmationDraft(candidate())
  draft.cards.forEach(card => { card.selected = false })
  assert.throws(() => buildConfirmationInput(draft), /至少选择/)
  draft.cards[0].selected = true
  draft.tagsText = 'Biology，biology'
  assert.throws(() => buildConfirmationInput(draft), /标签.*重复/)
  draft.tagsText = ''
  draft.relations.push({ relationKey: 'r1', source: { candidateKey: 'a' }, target: { candidateKey: 'b' },
    type: 'RELATED', evidenceText: '用户待确认的关联', selected: true })
  assert.throws(() => buildConfirmationInput(draft), /关联.*未选择/)
})

test('候选关联默认不勾选，明确勾选才发送关联', async () => {
  const { createConfirmationDraft, buildConfirmationInput, isConfirmationDraft } = await forms()
  const original = candidate()
  original.relations = [{ relationKey: 'r1', source: { candidateKey: 'a' }, target: { candidateKey: 'b' },
    type: 'RELATED', evidenceText: '仅供用户判断' }]
  const draft = createConfirmationDraft(original)
  assert.equal(draft.relations[0].selected, false)
  assert.deepEqual(buildConfirmationInput(draft).relations, [])
  draft.relations[0].selected = true
  assert.equal(buildConfirmationInput(draft).relations.length, 1)
  assert.equal(isConfirmationDraft(JSON.parse(JSON.stringify(draft))), true)
  assert.equal(isConfirmationDraft({ ...draft, cards: null }), false)
})
