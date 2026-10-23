const { ApiError, getToken, request, downloadMaterialFile } = require('../../utils/api')

Page({
  data: { id: '', material: null, previewPath: '', previewError: '', loading: false, busy: false, favoriteBusy: false, error: '', actionError: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() { this.load() },
  async load() {
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '', actionError: '', previewPath: '', previewError: '', material: null })
    try {
      const material = await request(`/materials/${encodeURIComponent(this.data.id)}`)
      this.setData({ material })
      if (material.type === 'IMAGE') {
        try { this.setData({ previewPath: await downloadMaterialFile(this.data.id) }) }
        catch (error) {
          if (error instanceof ApiError && error.status === 401) {
            wx.reLaunch({ url: '/pages/login/index?reason=expired' })
          } else this.setData({ previewError: error.message || '图片读取失败' })
        }
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else { this.setData({ error: error.message || '资料加载失败' }) }
    } finally { this.setData({ loading: false }) }
  },
  async openPdf() {
    if (!this.data.material || this.data.material.type !== 'PDF' || this.data.busy) return
    this.setData({ busy: true, previewError: '' })
    try {
      const filePath = await downloadMaterialFile(this.data.id)
      await new Promise((resolve, reject) => wx.openDocument({
        filePath, fileType: 'pdf', showMenu: true, success: resolve,
        fail: () => reject(new Error('PDF 无法打开，请稍后重试')),
      }))
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else this.setData({ previewError: error.message || 'PDF 预览失败' })
    } finally { this.setData({ busy: false }) }
  },
  edit() { if (this.data.material) wx.navigateTo({ url: `/pages/note/edit?id=${this.data.id}` }) },
  newTask() { if (this.data.material) wx.navigateTo({ url: `/pages/tasks/new?id=${this.data.id}` }) },
  async toggleFavorite() {
    const material = this.data.material
    if (!material || this.data.favoriteBusy) return
    this.setData({ favoriteBusy: true, actionError: '' })
    try {
      await request(`/favorites/MATERIAL/${encodeURIComponent(this.data.id)}`, { method: material.favorite ? 'DELETE' : 'PUT' })
      this.setData({ material: { ...material, favorite: !material.favorite } })
    } catch (error) { this.handleActionError(error) }
    finally { this.setData({ favoriteBusy: false }) }
  },
  async changeArchive() {
    const material = this.data.material
    if (!material || this.data.busy) return
    this.setData({ busy: true, actionError: '' })
    try {
      const updated = await request(`/materials/${encodeURIComponent(this.data.id)}`, {
        method: 'PATCH', data: { version: material.version, status: material.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED' },
      })
      this.setData({ material: updated })
    } catch (error) { this.handleActionError(error) }
    finally { this.setData({ busy: false }) }
  },
  remove() {
    const material = this.data.material
    if (!material || this.data.busy) return
    wx.showModal({ title: '删除资料', content: '删除后将无法在资料列表中查看。确定删除吗？',
      success: async response => {
        if (!response.confirm) return
        this.setData({ busy: true, actionError: '' })
        try {
          await request(`/materials/${encodeURIComponent(this.data.id)}?version=${material.version}`, { method: 'DELETE' })
          wx.navigateBack()
        } catch (error) { this.handleActionError(error) }
        finally { this.setData({ busy: false }) }
      },
    })
  },
  handleActionError(error) {
    if (error instanceof ApiError && error.status === 401) {
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    } else { this.setData({ actionError: error.message || '操作失败' }) }
  },
})
