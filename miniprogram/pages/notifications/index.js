const { ApiError, getToken, request } = require('../../utils/api')

const typeLabels = { RESULT_READY: '结果待确认', TASK_FAILED: '任务失败', REVIEW_DECIDED: '审核结果' }
function targetPath(target) {
  if (!target) return ''
  const validId = id => typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && (id.length < 19 || id <= '9223372036854775807')
  if (target.type === 'AI_TASK' && validId(target.taskId)) return `/pages/tasks/detail?id=${target.taskId}`
  if (target.type === 'MATERIAL' && validId(target.materialId) && validId(target.reviewId)) return `/pages/materials/detail?id=${target.materialId}`
  return ''
}
function present(item) {
  return { ...item, typeLabel: typeLabels[item.type] || item.type,
    readLabel: item.isRead ? '已读' : '未读', readAction: item.isRead ? '标为未读' : '标为已读',
    channelLabel: item.channelStatus === 'NOT_REQUESTED' ? '微信订阅未开通' : item.channelStatus,
    taskAvailable: !!targetPath(item.target), targetAction: item.target && item.target.type === 'MATERIAL' ? '查看资料' : '查看任务' }
}

Page({
  data: { items: [], page: 1, pageSize: 20, total: 0, unreadOnly: false,
    loading: false, saving: false, loaded: false, error: '', actionError: '',
    channelNotice: '微信订阅消息尚未开通，站内通知正常。' },
  onShow() { return this.load() },
  onHide() { this.clearPrivateData() },
  onUnload() { this.clearPrivateData() },
  clearPrivateData() {
    this.requestVersion = (this.requestVersion || 0) + 1
    this.setData({ items: [], total: 0, loading: false, saving: false, loaded: false, error: '', actionError: '' })
  },
  sessionCurrent(token) {
    if (getToken() === token) return true
    this.clearPrivateData()
    this.setData({ page: 1 })
    if (!getToken()) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    else this.setData({ error: '登录账号已变化，请重新读取当前账号的通知。' })
    return false
  },
  handleError(error, field, saved) {
    if (error instanceof ApiError && error.status === 401) {
      this.clearPrivateData()
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    } else {
      const message = error.message || '通知请求失败，请重试'
      this.setData({ [field]: saved ? `已保存已读状态，但通知列表刷新失败：${message}` : message })
    }
  },
  async load(next, saved = false) {
    if (this.data.loading || this.data.saving) return
    const token = getToken()
    if (!token) { this.clearPrivateData(); wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    if (this.sessionToken && this.sessionToken !== token) {
      this.clearPrivateData()
      this.setData({ page: 1 })
    }
    const page = typeof next === 'number' ? next : this.data.page
    const sequence = this.requestVersion = (this.requestVersion || 0) + 1
    this.sessionToken = token
    this.setData({ page, items: [], total: 0, loading: true, loaded: false, error: '', actionError: '' })
    try {
      const result = await request(`/notifications?unreadOnly=${this.data.unreadOnly}&page=${page}&pageSize=${this.data.pageSize}`)
      if (sequence !== this.requestVersion || !this.sessionCurrent(token)) return
      this.setData({ items: result.items.map(present), page: result.page, pageSize: result.pageSize,
        total: result.total, loaded: true })
    } catch (error) {
      if (sequence !== this.requestVersion) return
      if (error instanceof ApiError && error.status === 401 && !getToken()) this.handleError(error, 'error', saved)
      else if (this.sessionCurrent(token)) this.handleError(error, 'error', saved)
    } finally { if (sequence === this.requestVersion) this.setData({ loading: false }) }
  },
  showAll() { return this.changeFilter(false) },
  showUnread() { return this.changeFilter(true) },
  changeFilter(unreadOnly) {
    if (this.data.loading || this.data.saving) return
    this.setData({ unreadOnly })
    return this.load(1)
  },
  previous() { if (this.data.page > 1) return this.load(this.data.page - 1) },
  next() { if (this.data.page * this.data.pageSize < this.data.total) return this.load(this.data.page + 1) },
  toggleRead(event) { return this.saveReadState(event.currentTarget.dataset.id, false) },
  openTask(event) { return this.saveReadState(event.currentTarget.dataset.id, true) },
  async saveReadState(id, openTask) {
    if (this.data.loading || this.data.saving || !this.sessionCurrent(this.sessionToken)) return
    const item = this.data.items.find(row => row.id === id)
    if (!item) return
    if (openTask && !targetPath(item.target)) { this.setData({ actionError: '通知的任务目标无效，无法打开。' }); return }
    const token = this.sessionToken
    const sequence = this.requestVersion = (this.requestVersion || 0) + 1
    this.setData({ saving: true, actionError: '' })
    let saved = false
    try {
      const updated = await request(`/notifications/${encodeURIComponent(id)}/read-state`, {
        method: 'PUT', data: { isRead: openTask ? true : !item.isRead },
      })
      if (sequence !== this.requestVersion || !this.sessionCurrent(token)) return
      this.setData({ items: this.data.items.map(row => row.id === id ? present(updated) : row) })
      if (openTask) {
        const destination = targetPath(updated.target)
        if (!destination) this.setData({ actionError: '已保存已读状态，但通知的任务目标无效，无法打开。' })
        else wx.navigateTo({ url: destination })
      } else saved = true
    } catch (error) {
      if (sequence !== this.requestVersion) return
      if (error instanceof ApiError && error.status === 401 && !getToken()) this.handleError(error, 'actionError')
      else if (this.sessionCurrent(token)) this.handleError(error, 'actionError')
    } finally { if (sequence === this.requestVersion) this.setData({ saving: false }) }
    if (saved && sequence === this.requestVersion) return this.load(this.data.page, true)
  },
})
