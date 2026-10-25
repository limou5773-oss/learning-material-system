const { ApiError, getToken, request } = require('../../utils/api')
const reasons = { SOURCE_DELETED: '原资料已删除，来源内容不可访问', SOURCE_RESTRICTED: '原资料暂时受限，来源内容不可访问' }
Page({
  data: { id: '', card: null, sources: [], loading: false, error: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { this.load() },
  async load() {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '', card: null, sources: [] })
    try {
      const path = `/cards/${encodeURIComponent(this.data.id)}`
      const [card, sourceList] = await Promise.all([request(path), request(`${path}/sources`)])
      this.setData({ card, sources: sourceList.items.map(source => ({ ...source,
        reasonLabel: reasons[source.unavailableReason] || source.unavailableReason || '来源暂不可用' })) })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '卡片读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  openMaterial(event) { wx.navigateTo({ url: `/pages/materials/detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
})
