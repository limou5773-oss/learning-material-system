const { ApiError, getToken, request } = require('../../utils/api')
const { savedGenerationLabel } = require('../../utils/source-presentation')
const { cardListPath } = require('../../utils/card-editor')
Page({
  data: { items: [], page: 1, total: 0, loading: false, error: '', keyword: '', subject: '', tag: '', favoriteOnly: false,
    applied: { keyword: '', subject: '', tag: '', favoriteOnly: false } },
  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) this.getTabBar().setData({ selected: 1 })
    this.load()
  },
  async load(next) {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    const page = typeof next === 'number' ? next : this.data.page
    this.setData({ loading: true, error: '', items: [], total: 0 })
    try {
      const result = await request(cardListPath(this.data.applied, page))
      this.setData({ items: result.items.map(card => ({ ...card, generationLabel: savedGenerationLabel(card.generationMode, card.isEdited) })), page, total: result.total })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '卡片读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }) },
  onSubjectInput(event) { this.setData({ subject: event.detail.value }) },
  onTagInput(event) { this.setData({ tag: event.detail.value }) },
  onFavoriteChange(event) { this.setData({ favoriteOnly: event.detail.value }) },
  toggleFavFilter() {
    const favoriteOnly = !this.data.favoriteOnly
    const { keyword, subject, tag } = this.data
    this.setData({ favoriteOnly, applied: { keyword, subject, tag, favoriteOnly }, page: 1 })
    return this.load(1)
  },
  onPullDownRefresh() { Promise.resolve(this.load()).finally(() => wx.stopPullDownRefresh()) },
  search() {
    if (this.data.loading) return
    const { keyword, subject, tag, favoriteOnly } = this.data
    this.setData({ applied: { keyword, subject, tag, favoriteOnly }, page: 1 })
    return this.load(1)
  },
  previous() { if (this.data.page > 1) return this.load(this.data.page - 1) },
  next() { if (this.data.page * 20 < this.data.total) return this.load(this.data.page + 1) },
  openCard(event) { wx.navigateTo({ url: `/pages/cards/detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
  openTasks() { wx.switchTab({ url: '/pages/tasks/index' }) },
})
