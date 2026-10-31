const { ApiError, getToken, request } = require('../../utils/api')
const { sourceLocator, savedGenerationLabel, savedGenerationWarning } = require('../../utils/source-presentation')
const reasons = { SOURCE_DELETED: '原资料已删除，来源内容不可访问', SOURCE_RESTRICTED: '原资料暂时受限，来源内容不可访问' }
Page({
  _deleteEpoch: 0,
  _closed: false,
  data: { id: '', card: null, sources: [], loading: false, error: '', favoriteBusy: false, actionError: '', deleteBusy: false, deleteConflict: false },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { this.load() },
  onHide() { this._deleteEpoch++ },
  onUnload() { this._deleteEpoch++; this._closed = true },
  async load() {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const path = `/cards/${encodeURIComponent(this.data.id)}`
      const [card, sourceList] = await Promise.all([request(path), request(`${path}/sources`)])
      this.setData({ actionError: '', deleteConflict: false, card: { ...card, generationLabel: savedGenerationLabel(card.generationMode, card.isEdited), generationWarning: savedGenerationWarning(card.generationMode, card.isEdited) }, sources: sourceList.items.map(source => ({ ...source,
        locator: sourceLocator(source),
        reasonLabel: reasons[source.unavailableReason] || source.unavailableReason || '来源暂不可用' })) })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '卡片读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  edit() { if (this.data.card) wx.navigateTo({ url: `/pages/cards/editor?id=${encodeURIComponent(this.data.id)}` }) },
  openRelations() { if (this.data.card) wx.navigateTo({ url: `/pages/cards/relations?cardId=${encodeURIComponent(this.data.id)}` }) },
  deleteCard() {
    if (!this.data.card || this.data.deleteBusy || this.data.deleteConflict) return
    wx.showModal({ title: '删除卡片', content: '删除后不可恢复。相关关联、收藏及画布位置将清理；直接子卡片会成为根卡片并保留，原始资料不受影响。', confirmText: '确认删除', confirmColor: '#a3362d', success: result => {
      if (result.confirm) this.confirmDelete()
    } })
  },
  async confirmDelete() {
    if (this.data.deleteBusy || this.data.deleteConflict) return
    const card = this.data.card
    const token = getToken(), epoch = this._deleteEpoch
    const isCurrentPage = () => !this._closed && this._deleteEpoch === epoch
      && this.data.id === card.id && this.data.card?.id === card.id
    this.setData({ deleteBusy: true, actionError: '' })
    try {
      await request(`/cards/${encodeURIComponent(card.id)}?version=${card.version}`, { method: 'DELETE' })
      if (!isCurrentPage() || getToken() !== token) return
      wx.switchTab({ url: '/pages/cards/index' })
    } catch (error) {
      // The request helper clears the original token before rejecting a current 401.
      if (!isCurrentPage() || (getToken() !== token && !(error instanceof ApiError && error.status === 401 && !getToken()))) return
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else if (error instanceof ApiError && error.status === 409) {
        this.setData({ deleteConflict: true, actionError: `卡片已被修改${error.currentVersion === undefined ? '' : `，当前版本 ${error.currentVersion}`}。请重新读取并核对后再删除。` })
      } else this.setData({ actionError: error.message || '卡片删除失败' })
    } finally { if (!this._closed) this.setData({ deleteBusy: false }) }
  },
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
