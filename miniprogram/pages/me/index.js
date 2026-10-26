const { ApiError, getToken, clearToken, getMe, logout } = require('../../utils/api')

Page({
  data: { user: null, loading: false, loggingOut: false, error: '' },

  onShow() { this.refresh() },

  openMaterials() { wx.navigateTo({ url: '/pages/materials/index' }) },
  openTasks() { wx.navigateTo({ url: '/pages/tasks/index' }) },
  openNotifications() { wx.navigateTo({ url: '/pages/notifications/index' }) },
  openCards() { wx.navigateTo({ url: '/pages/cards/index' }) },
  newNote() { wx.navigateTo({ url: '/pages/note/new' }) },

  async refresh() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      return
    }
    this.setData({ loading: true, error: '' })
    try {
      const user = await getMe()
      this.setData({ user })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else {
        this.setData({ error: error.message || '资料加载失败' })
      }
    } finally {
      this.setData({ loading: false })
    }
  },

  async submitLogout() {
    if (this.data.loggingOut) return
    this.setData({ loggingOut: true, error: '' })
    try {
      await logout()
      clearToken()
      wx.reLaunch({ url: '/pages/login/index' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else {
        this.setData({ error: error.message || '退出失败，请稍后重试' })
      }
    } finally {
      this.setData({ loggingOut: false })
    }
  },
})
