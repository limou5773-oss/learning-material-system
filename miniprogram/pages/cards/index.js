const { ApiError, getToken, request } = require('../../utils/api')
const { savedGenerationLabel } = require('../../utils/source-presentation')
Page({
  data: { items: [], page: 1, total: 0, loading: false, error: '' },
  onShow() { this.load() },
  async load(next) {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    const page = typeof next === 'number' ? next : this.data.page
    this.setData({ loading: true, error: '' })
    try {
      const result = await request(`/cards?page=${page}&pageSize=20`)
      this.setData({ items: result.items.map(card => ({ ...card, generationLabel: savedGenerationLabel(card.generationMode) })), page, total: result.total })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '卡片读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  previous() { if (this.data.page > 1) this.load(this.data.page - 1) },
  next() { if (this.data.page * 20 < this.data.total) this.load(this.data.page + 1) },
  openCard(event) { wx.navigateTo({ url: `/pages/cards/detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
  openTasks() { wx.navigateTo({ url: '/pages/tasks/index' }) },
})
