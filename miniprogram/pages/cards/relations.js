const { ApiError, getToken, request } = require('../../utils/api')
const { sourceLocator, savedGenerationLabel } = require('../../utils/source-presentation')
const { relationNotice, relationPresentation } = require('../../utils/relation-presentation')

const reasons = { SOURCE_DELETED: '原资料已删除，来源内容不可访问', SOURCE_RESTRICTED: '原资料暂时受限，来源内容不可访问' }
function endpoint(label, card, sourceList) {
  return { label, card: { ...card, generationLabel: savedGenerationLabel(card.generationMode, card.isEdited) },
    emptySourceLabel: card.origin === 'MANUAL' ? '人工创建的卡片无自动来源。' : '暂无来源记录。',
    sources: sourceList.items.map(source => ({ ...source, locator: sourceLocator(source),
      quoteText: source.isAvailable ? source.quoteText : null,
      content: source.isAvailable ? source.content : null,
      reasonLabel: reasons[source.unavailableReason] || source.unavailableReason || '来源暂不可用' })) }
}

Page({
  data: { cardId: '', items: [], page: 1, total: 0, loading: false, error: '', relationNotice,
    selectedRelation: null, endpoints: [], detailLoading: false, detailError: '' },
  onLoad(options) { this.setData({ cardId: options.cardId || '' }) },
  onShow() { return this.load() },
  onHide() { this.clearPrivateData() },
  onUnload() { this.clearPrivateData() },
  clearPrivateData() {
    this.listRequest = (this.listRequest || 0) + 1
    this.detailRequest = (this.detailRequest || 0) + 1
    this.setData({ items: [], total: 0, loading: false, error: '',
      selectedRelation: null, endpoints: [], detailLoading: false, detailError: '' })
  },
  sessionCurrent(token) {
    if (getToken() === token) return true
    this.clearPrivateData()
    if (!getToken()) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    else this.setData({ error: '登录账号已变化，请重新读取当前账号的关联。' })
    return false
  },
  handleError(error, field) {
    if (error instanceof ApiError && error.status === 401) {
      this.clearPrivateData(); wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    } else this.setData({ [field]: error.message || '关联读取失败，请重试' })
  },
  async load(next) {
    if (this.data.loading) return
    const token = getToken()
    if (!token) { this.clearPrivateData(); wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    if (!this.data.cardId) { this.setData({ error: '缺少原卡片编号，请返回卡片详情重试。' }); return }
    const page = typeof next === 'number' ? next : this.data.page
    const sequence = this.listRequest = (this.listRequest || 0) + 1
    this.sessionToken = token
    this.detailRequest = (this.detailRequest || 0) + 1
    this.setData({ page, items: [], total: 0, loading: true, error: '',
      selectedRelation: null, endpoints: [], detailLoading: false, detailError: '' })
    try {
      const result = await request(`/card-relations?cardId=${encodeURIComponent(this.data.cardId)}&page=${page}&pageSize=20`)
      if (sequence !== this.listRequest || !this.sessionCurrent(token)) return
      this.setData({ items: result.items.map(item => ({ ...item, ...relationPresentation(item.type) })), total: result.total })
    } catch (error) {
      if (sequence !== this.listRequest) return
      if (error instanceof ApiError && error.status === 401 && !getToken()) this.handleError(error, 'error')
      else if (this.sessionCurrent(token)) this.handleError(error, 'error')
    } finally { if (sequence === this.listRequest) this.setData({ loading: false }) }
  },
  previous() { if (this.data.page > 1) return this.load(this.data.page - 1) },
  next() { if (this.data.page * 20 < this.data.total) return this.load(this.data.page + 1) },
  selectRelation(event) {
    if (this.data.loading || !this.sessionCurrent(this.sessionToken)) return
    const selectedRelation = this.data.items.find(item => item.id === event.currentTarget.dataset.id)
    if (!selectedRelation) return
    this.setData({ selectedRelation })
    return this.readEndpoints()
  },
  retryDetail() { if (!this.data.detailLoading && this.data.selectedRelation) return this.readEndpoints() },
  async readEndpoints() {
    if (!this.sessionCurrent(this.sessionToken)) return
    const relation = this.data.selectedRelation
    const token = this.sessionToken
    const sequence = this.detailRequest = (this.detailRequest || 0) + 1
    this.setData({ endpoints: [], detailLoading: true, detailError: '' })
    try {
      const sourcePath = `/cards/${encodeURIComponent(relation.sourceCardId)}`
      const targetPath = `/cards/${encodeURIComponent(relation.targetCardId)}`
      const [source, sourceList, target, targetList] = await Promise.all([
        request(sourcePath), request(`${sourcePath}/sources`), request(targetPath), request(`${targetPath}/sources`),
      ])
      if (sequence !== this.detailRequest || !this.sessionCurrent(token)) return
      this.setData({ endpoints: [endpoint('起点卡片', source, sourceList), endpoint('终点卡片', target, targetList)] })
    } catch (error) {
      if (sequence !== this.detailRequest) return
      if (error instanceof ApiError && error.status === 401 && !getToken()) this.handleError(error, 'detailError')
      else if (this.sessionCurrent(token)) this.handleError(error, 'detailError')
    } finally { if (sequence === this.detailRequest) this.setData({ detailLoading: false }) }
  },
  openCard(event) {
    if (!this.sessionCurrent(this.sessionToken)) return
    const id = event.currentTarget.dataset.id
    if (this.data.endpoints.some(item => item.card.id === id)) wx.navigateTo({ url: `/pages/cards/detail?id=${encodeURIComponent(id)}` })
  },
  openMaterial(event) {
    if (!this.sessionCurrent(this.sessionToken)) return
    const id = event.currentTarget.dataset.id
    if (this.data.endpoints.some(item => item.sources.some(source => source.isAvailable && source.materialId === id))) {
      wx.navigateTo({ url: `/pages/materials/detail?id=${encodeURIComponent(id)}` })
    }
  },
  openOriginCard() { wx.navigateTo({ url: `/pages/cards/detail?id=${encodeURIComponent(this.data.cardId)}` }) },
})
