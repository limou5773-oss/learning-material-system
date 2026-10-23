const { ApiError, getToken, saveToken, login, getMe } = require('../../utils/api')

Page({
  data: { username: '', password: '', loading: false, error: '', notice: '' },

  onLoad(options) {
    if (options.reason === 'expired') this.setData({ notice: '登录已失效，请重新登录。' })
  },

  async onShow() {
    if (!getToken()) return
    try {
      await getMe()
      wx.reLaunch({ url: '/pages/me/index' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        this.setData({ notice: '登录已失效，请重新登录。' })
      } else {
        this.setData({ error: error.message || '无法验证已有登录状态' })
      }
    }
  },

  onUsernameInput(event) { this.setData({ username: event.detail.value }) },
  onPasswordInput(event) { this.setData({ password: event.detail.value }) },

  async submit() {
    if (this.data.loading) return
    const username = this.data.username.trim()
    const password = this.data.password
    if (!username || !password) {
      this.setData({ error: '请输入账号和密码' })
      return
    }
    this.setData({ loading: true, error: '', notice: '' })
    try {
      const session = await login(username, password)
      saveToken(session.accessToken)
      this.setData({ password: '' })
      wx.reLaunch({ url: '/pages/me/index' })
    } catch (error) {
      this.setData({ error: error.message || '登录失败，请稍后重试', password: '' })
    } finally {
      this.setData({ loading: false })
    }
  },
})
