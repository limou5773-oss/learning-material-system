const { ApiError, getToken, getMe, request } = require('../../utils/api')
const { sourceLocator, generationPresentation, relationEndpointLabel } = require('../../utils/source-presentation')
const { relationPresentation } = require('../../utils/relation-presentation')

function tagNames(value) {
  const tags = value.split(/[,，]/).map(name => name.trim()).filter(Boolean)
  if (tags.length > 10 || tags.some(name => name.length > 64)) throw new Error('每组最多10个标签，每个最多64字')
  if (new Set(tags.map(name => name.toLowerCase())).size !== tags.length) throw new Error('标签不能重复')
  return tags
}
function snapshot(data) {
  return { taskId: data.id, resultVersion: data.resultVersion, sourceVersion: data.sourceVersion,
    summary: data.summary, tagsText: data.tagsText, tagMode: data.tagMode, cards: data.cards, relations: data.relations }
}
function validDraft(value, id) {
  return value && value.taskId === id && Number.isInteger(value.resultVersion) && Number.isInteger(value.sourceVersion)
    && typeof value.summary === 'string' && typeof value.tagsText === 'string'
    && ['MERGE', 'REPLACE'].includes(value.tagMode) && Array.isArray(value.cards)
    && value.cards.length > 0 && value.cards.length <= 30 && value.cards.every(card => card
      && typeof card.title === 'string' && typeof card.content === 'string' && typeof card.candidateKey === 'string'
      && typeof card.tagsText === 'string' && typeof card.selected === 'boolean' && Array.isArray(card.sources))
    && Array.isArray(value.relations)
}

Page({
  data: { generationView: null, id: '', userId: '', task: null, resultVersion: 0, sourceVersion: 0, summary: '', tagsText: '',
    tagMode: 'MERGE', cards: [], relations: [], selectedCount: 0, sources: [], warnings: [],
    currentStep: 0, stepLabels: ['摘要与来源', '选择卡片', '关联与保存'],
    loading: false, saving: false, conflict: false, restored: false, receipt: null, error: '', draftWarning: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { if (!this.data.task) this.load() },
  onHide() { this.persistDraft() },
  onUnload() { this.persistDraft() },
  key() { return `lms.confirmDraft.v1.${this.data.userId}.${this.data.id}` },
  persistDraft() {
    if (!this.data.userId || !this.data.cards.length || this.data.receipt) return
    try { wx.setStorageSync(this.key(), snapshot(this.data)) }
    catch { this.setData({ draftWarning: '本机存储暂不可用，请保持页面打开并复制重要修改。' }) }
  },
  async load() {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const user = await getMe()
      this.setData({ userId: user.id })
      let stored
      try { stored = wx.getStorageSync(this.key()) } catch { stored = null }
      const task = await request(`/ai-tasks/${encodeURIComponent(this.data.id)}`)
      const cached = validDraft(stored, this.data.id) ? stored : null
      this.setData({ task })
      if (cached && !this.data.cards.length) this.setData({ ...cached, restored: true,
        selectedCount: cached.cards.filter(card => card.selected).length })
      if (!['WAIT_CONFIRM', 'SUCCEEDED'].includes(task.status)) {
        this.setData({ conflict: true, error: '任务已不可确认，已恢复的草稿仍可复制。' }); return
      }
      const [result, sources] = await Promise.all([
        request(`/ai-tasks/${encodeURIComponent(this.data.id)}/result`),
        request(`/ai-tasks/${encodeURIComponent(this.data.id)}/sources`),
      ])
      if (!this.data.cards.length) this.setData({ resultVersion: result.resultVersion, sourceVersion: result.sourceVersion,
        summary: result.summary, tagsText: result.tags.join('，'), tagMode: 'MERGE',
        cards: result.cards.map(card => ({ ...card, selected: true, tagsText: (card.tags || []).join('，') })),
        relations: result.relations.map(relation => ({ ...relation, selected: false,
          sourceLabel: relationEndpointLabel(relation.source, result.cards),
          targetLabel: relationEndpointLabel(relation.target, result.cards) })),
        selectedCount: result.cards.length })
      this.setData({ relations: this.data.relations.map(relation => ({ ...relation, ...relationPresentation(relation.type),
        sourceLabel: relationEndpointLabel(relation.source, this.data.cards),
        targetLabel: relationEndpointLabel(relation.target, this.data.cards) })),
        generationView: generationPresentation(result.generationMode, result.generationInfo), sources: sources.map(source => ({ ...source, locator: sourceLocator(source) })), warnings: result.warnings || [] })
      if (this.data.resultVersion !== result.resultVersion || this.data.sourceVersion !== result.sourceVersion) {
        this.setData({ conflict: true, error: '候选版本已变化，原草稿已保留，请复制修改后重新整理。' })
      }
    } catch (error) { this.handleError(error) }
    finally { this.setData({ loading: false }) }
  },
  onSummaryInput(event) { this.setData({ summary: event.detail.value }); this.persistDraft() },
  onTagsInput(event) { this.setData({ tagsText: event.detail.value }); this.persistDraft() },
  onTagModeChange(event) { this.setData({ tagMode: event.detail.value === 'REPLACE' ? 'REPLACE' : 'MERGE' }); this.persistDraft() },
  onCardSelection(event) {
    const selected = new Set(event.detail.value)
    const cards = this.data.cards.map(card => ({ ...card, selected: selected.has(card.candidateKey) }))
    this.setData({ cards, selectedCount: cards.filter(card => card.selected).length }); this.persistDraft()
  },
  onCardInput(event) {
    const { key, field } = event.currentTarget.dataset
    if (!['title', 'content', 'tagsText'].includes(field)) return
    this.setData({ cards: this.data.cards.map(card => card.candidateKey === key ? { ...card, [field]: event.detail.value } : card) })
    this.persistDraft()
  },
  onRelationSelection(event) {
    if (this.data.saving || this.data.conflict || !this.data.task || this.data.task.status !== 'WAIT_CONFIRM') return
    const selected = new Set(event.detail.value)
    this.setData({ relations: this.data.relations.map(relation => ({ ...relation, selected: selected.has(relation.relationKey) })) })
    this.persistDraft()
  },
  previousStep() {
    if (this.data.saving || this.data.loading || this.data.currentStep <= 0) return
    this.setData({ currentStep: this.data.currentStep - 1, error: this.data.conflict ? this.data.error : '' })
    this.persistDraft()
    wx.pageScrollTo({ scrollTop: 0, duration: 0 })
  },
  nextStep() {
    if (this.data.saving || this.data.loading || this.data.conflict || this.data.receipt || !this.data.task || this.data.currentStep >= 2) return
    try {
      if (this.data.currentStep === 0) this.summaryInput()
      else this.selectedCardInput()
      this.setData({ currentStep: this.data.currentStep + 1, error: '' })
      this.persistDraft()
      wx.pageScrollTo({ scrollTop: 0, duration: 0 })
    } catch (error) { this.setData({ error: error.message }) }
  },
  summaryInput() {
    if (this.data.summary.length > 4000) throw new Error('摘要最多4000字')
    return tagNames(this.data.tagsText)
  },
  selectedCardInput() {
    const selected = this.data.cards.filter(card => card.selected)
    if (!selected.length) throw new Error('至少选择一张卡片')
    const keys = new Set(selected.map(card => card.candidateKey))
    return selected.map(card => {
      if (!card.title.trim() || !card.content.trim()) throw new Error('请填写选中卡片的标题和正文')
      if (card.title.length > 255 || card.content.length > 8000) throw new Error('标题最多255字，正文最多8000字')
      return { candidateKey: card.candidateKey, title: card.title.trim(), content: card.content,
        summary: card.summary || null, subject: card.subject || null, tags: tagNames(card.tagsText),
        sources: card.sources, parentCandidateKey: keys.has(card.parentCandidateKey) ? card.parentCandidateKey : null }
    })
  },
  input() {
    const tags = this.summaryInput()
    const cards = this.selectedCardInput()
    const keys = new Set(cards.map(card => card.candidateKey))
    const relations = this.data.relations.filter(relation => relation.selected).map(relation => {
      if ([relation.source, relation.target].some(endpoint => endpoint.candidateKey && !keys.has(endpoint.candidateKey))) throw new Error('关联包含未选择的卡片，请取消该关联')
      return { relationKey: relation.relationKey, source: relation.source, target: relation.target,
        type: relation.type, evidenceText: relation.evidenceText }
    })
    return { resultVersion: this.data.resultVersion, sourceVersion: this.data.sourceVersion,
      summary: this.data.summary, tags, tagMode: this.data.tagMode, cards, relations }
  },
  async submit() {
    if (!this.data.task || this.data.saving || this.data.loading || this.data.conflict || this.data.receipt) return
    let input
    try { input = this.input() } catch (error) { this.setData({ error: error.message }); return }
    this.setData({ saving: true, error: '' }); this.persistDraft()
    try {
      if (this.data.task.status !== 'SUCCEEDED') {
        const confirmed = await new Promise(resolve => wx.showModal({ title: '确认保存知识卡片',
          content: `保存 ${input.cards.length} 张卡片，并更新资料摘要和标签？`,
          success: response => resolve(response.confirm), fail: () => resolve(false) }))
        if (!confirmed) return
      }
      const receipt = await request(`/ai-tasks/${encodeURIComponent(this.data.id)}/confirmation`, { method: 'PUT', data: input })
      this.setData({ receipt })
      try { wx.removeStorageSync(this.key()) } catch { /* 已保存结果仍可查看 */ }
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) this.setData({ conflict: true,
        error: `${error.message}。草稿已保留，请返回任务重新整理。` })
      else this.handleError(error)
      this.persistDraft()
    } finally { this.setData({ saving: false }) }
  },
  handleError(error) {
    if (error instanceof ApiError && error.status === 401) { this.persistDraft(); wx.reLaunch({ url: '/pages/login/index?reason=expired' }) }
    else this.setData({ error: error.message || '操作失败，编辑内容保留在当前页面' })
  },
  openCard(event) { wx.navigateTo({ url: `/pages/cards/detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
  openTask() { wx.navigateTo({ url: `/pages/tasks/detail?id=${encodeURIComponent(this.data.id)}` }) },
})
