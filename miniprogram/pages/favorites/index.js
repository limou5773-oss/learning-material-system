const { ApiError, getToken, request } = require('../../utils/api')

Page({
  data: { items: [], type: '', page: 1, pageSize: 20, total: 0, loading: false, removing: '', error: '', actionError: '' },
  onShow() { this._active = true; return this.load(1) },
  onHide() {
    this._active = false
    this._sequence = (this._sequence || 0) + 1
    this.setData({ items: [], total: 0, loading: false, removing: '', error: '', actionError: '' })
  },
  onUnload() { this.onHide() },
  current(sequence, token) { return this._active && sequence === this._sequence && getToken() === token },
  async load(page = this.data.page) {
    const token = getToken()
    if (!token) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    const sequence = this._sequence = (this._sequence || 0) + 1
    this.setData({ loading: true, page, items: [], total: 0, error: '', actionError: '' })
    try {
      const type = this.data.type ? `&type=${this.data.type}` : ''
      const result = await request(`/favorites?page=${page}&pageSize=${this.data.pageSize}${type}`)
      if (!this.current(sequence, token)) return
      this.setData({ total: result.total, items: result.items.map(item => ({ ...item,
        key: `${item.type}:${item.targetId}`, typeLabel: item.type === 'MATERIAL' ? '学习资料' : '知识卡片',
        dateLabel: item.createdAt.slice(0, 10) })) })
    } catch (error) { this.handleError(error, sequence, token, 'error') }
    finally { if (sequence === this._sequence) this.setData({ loading: false }) }
  },
  filter(event) {
    if (this.data.loading || this.data.removing || this.data.type === event.currentTarget.dataset.type) return
    this.setData({ type: event.currentTarget.dataset.type })
    return this.load(1)
  },
  retry() { return this.load() },
  previous() { if (!this.data.loading && !this.data.removing && this.data.page > 1) return this.load(this.data.page - 1) },
  next() {
    if (!this.data.loading && !this.data.removing && this.data.page * this.data.pageSize < this.data.total) return this.load(this.data.page + 1)
  },
  open(event) {
    const { type, id } = event.currentTarget.dataset
    const page = type === 'MATERIAL' ? 'materials' : 'cards'
    wx.navigateTo({ url: `/pages/${page}/detail?id=${encodeURIComponent(id)}` })
  },
  async remove(event) {
    if (this.data.loading || this.data.removing) return
    const { type, id } = event.currentTarget.dataset
    const token = getToken(), sequence = this._sequence
    if (!token) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ removing: `${type}:${id}`, actionError: '' })
    try {
      await request(`/favorites/${type}/${encodeURIComponent(id)}`, { method: 'DELETE' })
      if (!this.current(sequence, token)) return
      this.setData({ removing: '' })
      await this.load(1)
    } catch (error) { this.handleError(error, sequence, token, 'actionError') }
    finally { if (sequence === this._sequence) this.setData({ removing: '' }) }
  },
  handleError(error, sequence, token, field) {
    if (!this._active || sequence !== this._sequence || (getToken() && getToken() !== token)) return
    if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    else this.setData({ [field]: error.message })
  },
})
