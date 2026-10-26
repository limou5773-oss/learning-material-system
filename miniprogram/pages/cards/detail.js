const { ApiError, getToken, request } = require('../../utils/api')
const { sourceLocator, savedGenerationLabel, savedGenerationWarning } = require('../../utils/source-presentation')
const reasons = { SOURCE_DELETED: '原资料已删除，来源内容不可访问', SOURCE_RESTRICTED: '原资料暂时受限，来源内容不可访问' }
Page({
  data: { id: '', card: null, sources: [], loading: false, error: '', favoriteBusy: false, actionError: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { this.load() },
  async load() {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '', card: null, sources: [] })
    try {
      const path = `/cards/${encodeURIComponent(this.data.id)}`
      const [card, sourceList] = await Promise.all([request(path), request(`${path}/sources`)])
      this.setData({ card: { ...card, generationLabel: savedGenerationLabel(card.generationMode, card.isEdited), generationWarning: savedGenerationWarning(card.generationMode, card.isEdited) }, sources: sourceList.items.map(source => ({ ...source,
        locator: sourceLocator(source),
        reasonLabel: reasons[source.unavailableReason] || source.unavailableReason || '来源暂不可用' })) })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '卡片读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  edit() { if (this.data.card) wx.navigateTo({ url: `/pages/cards/editor?id=${encodeURIComponent(this.data.id)}` }) },
  async toggleFavorite() {
    const card = this.data.card
    if (!card || this.data.favoriteBusy) return
    this.setData({ favoriteBusy: true, actionError: '' })
    try {
      await request(`/favorites/CARD/${encodeURIComponent(card.id)}`, { method: card.favorite ? 'DELETE' : 'PUT' })
      this.setData({ card: { ...card, favorite: !card.favorite } })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ actionError: error.message || '收藏操作失败' })
    } finally { this.setData({ favoriteBusy: false }) }
  },
  openMaterial(event) { wx.navigateTo({ url: `/pages/materials/detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
})
