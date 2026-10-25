const { ApiError, getToken, request } = require('../../utils/api')

const { externalProcessingConsent, goalDescriptions, relatedSearchDescription } = require('../../utils/source-presentation')

const goals = [
  { value: 'REVIEW_CARDS', label: '生成复习卡片' },
  { value: 'EXTRACT_KEY_POINTS', label: '提炼课件重点' },
  { value: 'LINK_NOTES', label: '整理并关联已有笔记' },
]

Page({
  data: { externalProcessingConsent, goalDescriptions, relatedSearchDescription, id: '', material: null, goals, goalIndex: 0, instruction: '', from: '1', to: '1',
    allowRelatedSearch: false, consent: false, loading: false, saving: false, error: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { this.load() },
  async load() {
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const material = await request(`/materials/${encodeURIComponent(this.data.id)}`)
      this.setData({ material, to: material.type === 'PDF' ? String(Math.min(material.pageCount || 1, 20)) : '1' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '资料读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  onGoalChange(event) { this.setData({ goalIndex: Number(event.detail.value) }) },
  onInstructionInput(event) { this.setData({ instruction: event.detail.value }) },
  onFromInput(event) { this.setData({ from: event.detail.value }) },
  onToInput(event) { this.setData({ to: event.detail.value }) },
  onRelatedChange(event) { this.setData({ allowRelatedSearch: event.detail.value }) },
  onConsentChange(event) { this.setData({ consent: event.detail.value }) },
  async submit() {
    const material = this.data.material
    if (!material || this.data.saving) return
    this.setData({ error: '' })
    if (!this.data.consent) { this.setData({ error: '请先同意资料用于外部处理' }); return }
    const input = {
      materialId: material.id, materialVersion: material.version,
      goalType: goals[this.data.goalIndex].value, instruction: this.data.instruction.trim(),
      allowRelatedSearch: this.data.allowRelatedSearch, externalProcessingConsent: true,
    }
    if (material.type === 'PDF') {
      const from = Number(this.data.from)
      const to = Number(this.data.to)
      if (!Number.isInteger(from) || !Number.isInteger(to) || from < 1 || to < from ||
          to - from + 1 > 20 || to > material.pageCount) {
        this.setData({ error: '请选择 PDF 内连续且不超过 20 页的页段' }); return
      }
      input.pageRange = { from, to }
    }
    const storageKey = `lms.taskSubmit.${material.id}`
    const fingerprint = JSON.stringify(input)
    const stored = wx.getStorageSync(storageKey)
    const requestKey = stored && stored.fingerprint === fingerprint ? stored.requestKey :
      `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
    wx.setStorageSync(storageKey, { fingerprint, requestKey })
    this.setData({ saving: true })
    try {
      const task = await request('/ai-tasks', { method: 'POST', data: { ...input, requestKey } })
      wx.removeStorageSync(storageKey)
      wx.redirectTo({ url: `/pages/tasks/detail?id=${task.id}` })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '提交任务失败' })
    } finally { this.setData({ saving: false }) }
  },
})
