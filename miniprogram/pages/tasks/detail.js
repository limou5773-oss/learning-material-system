const { ApiError, getToken, request } = require('../../utils/api')

const goalLabels = { REVIEW_CARDS: '生成复习卡片', EXTRACT_KEY_POINTS: '提炼课件重点', LINK_NOTES: '整理并关联已有笔记' }
const statusLabels = { QUEUED: '排队中', RUNNING: '执行中', WAIT_CONFIRM: '待确认', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const stageLabels = { QUEUED: '等待领取', STARTING: '开始处理', FAILED: '处理失败', CANCELLED: '用户取消', SOURCE_DELETED: '原资料已删除', PREPARE_SOURCE: '准备来源', GENERATE_SUMMARY_CARDS: '生成模拟候选', VALIDATE_CANDIDATE: '核验候选', CANDIDATE_READY: '等待人工确认', CONFIRMED: '人工确认完成' }
const stepStatusLabels = { RUNNING: '进行中', SUCCESS: '成功', FAILED: '失败', UNKNOWN: '结果未知' }

Page({
  data: { id: '', task: null, steps: [], candidate: null, candidateCards: [], consent: false, loading: false, busy: false, error: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { this.onHide(); this.load(); this.timer = setInterval(() => {
    if (this.data.task && ['QUEUED', 'RUNNING'].includes(this.data.task.status)) this.load()
  }, 3000) },
  onHide() { if (this.timer) clearInterval(this.timer); this.timer = null },
  onUnload() { this.onHide() },
  async load() {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const path = `/ai-tasks/${encodeURIComponent(this.data.id)}`
      const [task, history] = await Promise.all([request(path), request(`${path}/steps?page=1&pageSize=100`)])
      let candidate = null
      let candidateCards = []
      if (['WAIT_CONFIRM', 'SUCCEEDED'].includes(task.status)) {
        const [result, sources] = await Promise.all([request(`${path}/result`), request(`${path}/sources`)])
        const byId = Object.fromEntries(sources.map(source => [source.chunkId, source]))
        candidate = result
        candidateCards = result.cards.map(card => ({ ...card, sourceViews: card.sources.map(ref => ({
          ...ref, content: byId[ref.chunkId] && byId[ref.chunkId].content || '',
          locator: byId[ref.chunkId] && byId[ref.chunkId].extractionMethod === 'METADATA'
            ? '资料标题元数据' : `文字笔记片段 ${byId[ref.chunkId] && byId[ref.chunkId].chunkNo || ''}`,
        })) }))
      }
      this.setData({ task: { ...task, goalLabel: goalLabels[task.goalType] || task.goalType,
        statusLabel: statusLabels[task.status] || task.status,
        stageLabel: stageLabels[task.stage] || task.stage || '等待更新' },
        steps: history.items.map(step => ({ ...step, statusLabel: stepStatusLabels[step.status] || step.status })),
        candidate, candidateCards })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '任务读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  onConsentChange(event) { this.setData({ consent: event.detail.value.includes('yes') }) },
  cancel() {
    if (!this.data.task || !this.data.task.canCancel || this.data.busy) return
    wx.showModal({ title: '取消整理任务', content: '确定取消吗？已发出的外部请求可能仍产生费用。',
      success: async response => {
        if (!response.confirm) return
        this.setData({ busy: true, error: '' })
        try {
          await request(`/ai-tasks/${encodeURIComponent(this.data.id)}/cancellations`, { method: 'POST' })
          await this.load()
        } catch (error) { this.handleActionError(error) }
        finally { this.setData({ busy: false }) }
      },
    })
  },
  async retryTask() {
    const task = this.data.task
    if (!task || !task.canRetry || !this.data.consent || this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      const material = await request(`/materials/${encodeURIComponent(task.materialId)}`)
      const storageKey = `lms.taskRetry.${task.id}.${material.version}`
      let requestKey = wx.getStorageSync(storageKey)
      if (!requestKey) {
        requestKey = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
        wx.setStorageSync(storageKey, requestKey)
      }
      const next = await request(`/ai-tasks/${encodeURIComponent(task.id)}/retries`, {
        method: 'POST', data: { requestKey, materialVersion: material.version, externalProcessingConsent: true },
      })
      wx.removeStorageSync(storageKey)
      wx.redirectTo({ url: `/pages/tasks/detail?id=${next.id}` })
    } catch (error) { this.handleActionError(error) }
    finally { this.setData({ busy: false }) }
  },
  handleActionError(error) {
    if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    else this.setData({ error: error.message || '操作失败' })
  },
  openMaterial() { if (this.data.task) wx.navigateTo({ url: `/pages/materials/detail?id=${this.data.task.materialId}` }) },
  openConfirmation() { if (this.data.task && this.data.task.status === 'WAIT_CONFIRM') wx.navigateTo({ url: `/pages/tasks/confirm?id=${encodeURIComponent(this.data.id)}` }) },
  openCards() { wx.navigateTo({ url: '/pages/cards/index' }) },
})
