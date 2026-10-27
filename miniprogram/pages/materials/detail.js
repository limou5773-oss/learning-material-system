const { ApiError, getToken, request, downloadMaterialFile } = require('../../utils/api')

Page({
  data: { id: '', material: null, previewPath: '', previewError: '', loading: false, busy: false, favoriteBusy: false, error: '', actionError: '',
    reviewItems: [], reviewPage: 1, reviewTotal: 0, reviewLoaded: false, reviewLoading: false, reviewSaving: false,
    reviewReason: '', reviewError: '', reviewActionError: '', reviewNotice: '', alreadySubmitted: false },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { return this.load() },
  onHide() { this.clearPrivateData() },
  onUnload() { this.clearPrivateData() },
  clearPrivateData() {
    this.requestVersion = (this.requestVersion || 0) + 1
    this.setData({ material: null, previewPath: '', previewError: '', error: '', actionError: '', loading: false, busy: false, favoriteBusy: false,
      reviewItems: [], reviewTotal: 0, reviewLoaded: false, reviewLoading: false, reviewSaving: false, reviewReason: '', reviewError: '', reviewActionError: '', reviewNotice: '', alreadySubmitted: false })
    if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  current(token, sequence) {
    if (sequence !== this.requestVersion) return false
    if (token && getToken() === token) return true
    this.clearPrivateData()
    if (!getToken()) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    else this.setData({ error: '登录账号已变化，请重新读取当前账号的资料。' })
    return false
  },
  async load() {
    if (this.data.loading || this.data.reviewSaving || this.data.busy) return
    const token = getToken()
    if (!token) { this.clearPrivateData(); wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    if (this.sessionToken && this.sessionToken !== token) this.clearPrivateData()
    this.sessionToken = token
    const sequence = this.requestVersion = (this.requestVersion || 0) + 1
    this.setData({ loading: true, error: '', actionError: '', previewPath: '', previewError: '', material: null })
    try {
      const material = await request(`/materials/${encodeURIComponent(this.data.id)}`)
      if (!this.current(token, sequence)) return
      this.setData({ material })
      await this.loadReviewHistory(1)
      if (!this.current(token, sequence)) return
      if (material.type === 'IMAGE') {
        try { const previewPath = await downloadMaterialFile(this.data.id); if (this.current(token, sequence)) this.setData({ previewPath }) }
        catch (error) {
          if (!this.current(token, sequence)) return
          if (error instanceof ApiError && error.status === 401) {
            wx.reLaunch({ url: '/pages/login/index?reason=expired' })
          } else this.setData({ previewError: error.message || '图片读取失败' })
        }
      }
    } catch (error) {
      if (!this.current(token, sequence)) return
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else { this.setData({ error: error.message || '资料加载失败' }) }
    } finally { if (sequence === this.requestVersion) this.setData({ loading: false }) }
  },
  onReviewReason(event) {
    this.setData({ reviewReason: event.detail.value })
    if (event.detail.value && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '送审理由尚未提交，确定离开？' })
    else if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  async loadReviewHistory(next) {
    if (!this.data.material || this.data.reviewLoading) return
    const token = this.sessionToken, sequence = this.requestVersion
    if (!this.current(token, sequence)) return
    const page = typeof next === 'number' ? next : this.data.reviewPage
    this.setData({ reviewLoading: true, reviewError: '', reviewLoaded: false, reviewItems: [], reviewPage: page })
    try {
      const result = await request(`/materials/${encodeURIComponent(this.data.id)}/review-requests?page=${page}&pageSize=20`)
      if (!this.current(token, sequence)) return
      const labels = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }
      this.setData({ reviewItems: result.items.map(item => ({ ...item, statusLabel: labels[item.status] })), reviewPage: result.page,
        reviewTotal: result.total, reviewLoaded: true,
        alreadySubmitted: page === 1 ? result.items.some(item => item.materialVersion === this.data.material.version) : this.data.alreadySubmitted })
    } catch (error) {
      if (this.current(token, sequence)) this.setData({ reviewError: error.message || '审核历史读取失败，请重试' })
    } finally { if (sequence === this.requestVersion) this.setData({ reviewLoading: false }) }
  },
  reviewPrevious() { if (!this.data.reviewLoading && !this.data.reviewSaving && this.data.reviewPage > 1) return this.loadReviewHistory(this.data.reviewPage - 1) },
  reviewNext() { if (!this.data.reviewLoading && !this.data.reviewSaving && this.data.reviewPage * 20 < this.data.reviewTotal) return this.loadReviewHistory(this.data.reviewPage + 1) },
  refreshReviewMaterial() { return this.load() },
  async submitReview() {
    if (!this.data.material || this.data.loading || this.data.busy || this.data.reviewLoading || this.data.reviewSaving || !this.data.reviewLoaded || this.data.alreadySubmitted) return
    const token = this.sessionToken, sequence = this.requestVersion
    if (!this.current(token, sequence)) return
    const reason = this.data.reviewReason.trim(), material = this.data.material
    if (!reason || reason.length > 1000) { this.setData({ reviewActionError: '请输入 1～1000 个字符的送审理由' }); return }
    this.setData({ reviewSaving: true, reviewActionError: '', reviewNotice: '' })
    try {
      const approved = await new Promise(resolve => wx.showModal({ title: '授权并提交审核',
        content: `授权管理员查看「${material.title}」版本 ${material.version} 的正文和原文件并进行审核？编辑后旧版本授权失效。`,
        success: response => resolve(response.confirm), fail: () => resolve(false) }))
      if (!approved || !this.current(token, sequence)) return
      try { await request(`/materials/${encodeURIComponent(material.id)}/review-requests`, { method: 'POST', data: { version: material.version, reason } }) }
      catch (error) { if (this.current(token, sequence)) this.setData({ reviewActionError: error.message || '送审失败，理由已保留' }); return }
      if (!this.current(token, sequence)) return
      this.setData({ reviewReason: '', reviewNotice: `版本 ${material.version} 的送审已提交。` })
      if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
      await this.loadReviewHistory(1)
    } finally { if (sequence === this.requestVersion) this.setData({ reviewSaving: false }) }
  },
  async openPdf() {
    if (!this.data.material || this.data.material.type !== 'PDF' || this.data.busy || this.data.reviewSaving) return
    const token = this.sessionToken || getToken(), sequence = this.requestVersion
    if (!this.current(token, sequence)) return
    this.setData({ busy: true, previewError: '' })
    try {
      const filePath = await downloadMaterialFile(this.data.id)
      if (!this.current(token, sequence)) return
      await new Promise((resolve, reject) => wx.openDocument({
        filePath, fileType: 'pdf', showMenu: true, success: resolve,
        fail: () => reject(new Error('PDF 无法打开，请稍后重试')),
      }))
    } catch (error) {
      if (!this.current(token, sequence)) return
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else this.setData({ previewError: error.message || 'PDF 预览失败' })
    } finally { if (sequence === this.requestVersion) this.setData({ busy: false }) }
  },
  edit() { if (this.data.material) wx.navigateTo({ url: `/pages/note/edit?id=${this.data.id}` }) },
  newTask() { if (this.data.material && this.data.material.accessState !== 'RESTRICTED') wx.navigateTo({ url: `/pages/tasks/new?id=${this.data.id}` }) },
  async toggleFavorite() {
    const material = this.data.material
    if (!material || this.data.favoriteBusy || this.data.reviewSaving) return
    const token = this.sessionToken || getToken(), sequence = this.requestVersion
    if (!this.current(token, sequence)) return
    this.setData({ favoriteBusy: true, actionError: '' })
    try {
      await request(`/favorites/MATERIAL/${encodeURIComponent(this.data.id)}`, { method: material.favorite ? 'DELETE' : 'PUT' })
      if (this.current(token, sequence)) this.setData({ material: { ...material, favorite: !material.favorite } })
    } catch (error) { if (this.current(token, sequence)) this.handleActionError(error) }
    finally { if (sequence === this.requestVersion) this.setData({ favoriteBusy: false }) }
  },
  async changeArchive() {
    const material = this.data.material
    if (!material || this.data.busy || this.data.reviewSaving) return
    const token = this.sessionToken || getToken(), sequence = this.requestVersion
    if (!this.current(token, sequence)) return
    this.setData({ busy: true, actionError: '' })
    try {
      const updated = await request(`/materials/${encodeURIComponent(this.data.id)}`, {
        method: 'PATCH', data: { version: material.version, status: material.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED' },
      })
      if (this.current(token, sequence)) this.setData({ material: updated,
        alreadySubmitted: false, reviewItems: this.data.reviewItems.map(item => ({ ...item, isCurrent: false })) })
    } catch (error) { if (this.current(token, sequence)) this.handleActionError(error) }
    finally { if (sequence === this.requestVersion) this.setData({ busy: false }) }
  },
  remove() {
    const material = this.data.material
    if (!material || this.data.busy || this.data.reviewSaving) return
    const token = this.sessionToken || getToken(), sequence = this.requestVersion
    if (!this.current(token, sequence)) return
    wx.showModal({ title: '删除资料', content: '删除后将无法在资料列表中查看。确定删除吗？',
      success: async response => {
        if (!response.confirm || !this.current(token, sequence)) return
        this.setData({ busy: true, actionError: '' })
        try {
          await request(`/materials/${encodeURIComponent(this.data.id)}?version=${material.version}`, { method: 'DELETE' })
          if (this.current(token, sequence)) wx.navigateBack()
        } catch (error) { if (this.current(token, sequence)) this.handleActionError(error) }
        finally { if (sequence === this.requestVersion) this.setData({ busy: false }) }
      },
    })
  },
  handleActionError(error) {
    if (error instanceof ApiError && error.status === 401) {
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    } else { this.setData({ actionError: error.message || '操作失败' }) }
  },
})
