const { ApiError, getToken, request } = require('../../utils/api')

Page({
  data: {
    keyword: '', subject: '', favoritesOnly: false, favoriteBusyId: '',
    tagIndex: 0, tagNames: ['全部标签'], tags: [],
    statusIndex: 0, statusNames: ['使用中', '已归档', '全部'], statusValues: ['ACTIVE', 'ARCHIVED', 'ALL'],
    items: [], page: 1, pageSize: 20, total: 0,
    loading: false, error: '',
  },

  onShow() {
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.loadTags()
    this.load()
  },

  async loadTags() {
    try {
      let page = 1
      const tags = []
      while (true) {
        const batch = await request(`/tags?page=${page}&pageSize=100`)
        tags.push(...batch.items)
        if (tags.length >= batch.total) break
        page += 1
      }
      this.setData({ tags, tagNames: ['全部标签', ...tags.map(tag => tag.name)] })
    } catch (error) { this.handleError(error) }
  },

  async load() {
    this.setData({ loading: true, error: '', items: [] })
    try {
      const query = [`page=${this.data.page}`, `pageSize=${this.data.pageSize}`]
      if (this.data.keyword.trim()) query.push(`keyword=${encodeURIComponent(this.data.keyword.trim())}`)
      if (this.data.subject.trim()) query.push(`subject=${encodeURIComponent(this.data.subject.trim())}`)
      if (this.data.tagIndex > 0) query.push(`tagId=${this.data.tags[this.data.tagIndex - 1].id}`)
      if (this.data.statusIndex > 0) query.push(`status=${this.data.statusValues[this.data.statusIndex]}`)
      if (this.data.favoritesOnly) query.push('favorite=true')
      const result = await request(`/materials?${query.join('&')}`)
      this.setData({
        items: result.items.map(item => ({ ...item,
          excerpt: (item.noteText || '').slice(0, 90),
          shortDate: item.updatedAt.slice(0, 10),
        })),
        total: result.total,
      })
    } catch (error) { this.handleError(error) }
    finally { this.setData({ loading: false }) }
  },

  handleError(error) {
    if (error instanceof ApiError && error.status === 401) {
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    } else { this.setData({ error: error.message || '资料加载失败' }) }
  },

  onKeywordInput(event) { this.setData({ keyword: event.detail.value }) },
  onSubjectInput(event) { this.setData({ subject: event.detail.value }) },
  onFavoriteChange(event) { this.setData({ favoritesOnly: event.detail.value, page: 1 }); this.load() },
  onTagChange(event) { this.setData({ tagIndex: Number(event.detail.value), page: 1 }); this.load() },
  onStatusChange(event) { this.setData({ statusIndex: Number(event.detail.value), page: 1 }); this.load() },
  search() { this.setData({ page: 1 }); this.load() },
  previous() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.load() } },
  next() {
    if (this.data.page * this.data.pageSize < this.data.total) {
      this.setData({ page: this.data.page + 1 }); this.load()
    }
  },
  openDetail(event) { wx.navigateTo({ url: `/pages/materials/detail?id=${event.currentTarget.dataset.id}` }) },
  async toggleFavorite(event) {
    const id = String(event.currentTarget.dataset.id)
    const item = this.data.items.find(value => value.id === id)
    if (!item || this.data.favoriteBusyId) return
    this.setData({ favoriteBusyId: id, error: '' })
    try {
      await request(`/favorites/MATERIAL/${encodeURIComponent(id)}`, { method: item.favorite ? 'DELETE' : 'PUT' })
      this.setData({ items: this.data.items.map(value => value.id === id ? { ...value, favorite: !value.favorite } : value) })
      if (this.data.favoritesOnly && item.favorite) await this.load()
    } catch (error) { this.handleError(error) }
    finally { this.setData({ favoriteBusyId: '' }) }
  },
  newNote() { wx.navigateTo({ url: '/pages/note/new' }) },
  newImage() { wx.navigateTo({ url: '/pages/image/new' }) },
})
