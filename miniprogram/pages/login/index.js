const { ApiError, getToken, saveToken, login, loginWithWechat, getMe } = require('../../utils/api')

Page({
  data: { username: '', password: '', showPasswordLogin: false, loading: false, checkingSession: false, loginMethod: '', error: '', notice: '' },

  onLoad(options) {
    this._closed = false
    const notices = {
      expired: '登录已失效，请重新登录。',
      'password-changed': '密码已修改，所有设备需要重新登录。',
      'wechat-unbound': '微信已解绑，请使用账号密码重新登录。',
    }
    if (notices[options.reason]) this.setData({ notice: notices[options.reason] })
  },

  onUnload() { this._closed = true },
  onHide() { this.setData({ password: '' }) },

  async onShow() {
    const token = getToken()
    if (!token || this.data.loading || this.data.checkingSession) return
    this.setData({ checkingSession: true, error: '' })
    try {
      await getMe()
      if (!this._closed && getToken() === token) wx.reLaunch({ url: '/pages/me/index' })
    } catch (error) {
      if (this._closed || (getToken() && getToken() !== token)) return
      if (error instanceof ApiError && error.status === 401) {
        this.setData({ notice: '登录已失效，请重新登录。' })
      } else {
        this.setData({ error: error.message })
      }
    } finally {
      if (!this._closed) this.setData({ checkingSession: false })
    }
  },

  onUsernameInput(event) { this.setData({ username: event.detail.value }) },
  onPasswordInput(event) { this.setData({ password: event.detail.value }) },

  togglePasswordLogin() {
    this.setData({ showPasswordLogin: !this.data.showPasswordLogin, password: '', error: '' })
  },

  submit() { return this.performLogin('password') },
  submitWechat() { return this.performLogin('wechat') },

  async performLogin(method) {
    if (this.data.loading || this.data.checkingSession) return
    const username = this.data.username.trim()
    const password = this.data.password
    if (method === 'password' && (!username || !password)) {
      this.setData({ error: '请输入账号和密码' })
      return
    }
    const token = getToken()
    this.setData({ loading: true, loginMethod: method, error: '', notice: '' })
    try {
      const session = await (method === 'wechat' ? loginWithWechat() : login(username, password))
      if (this._closed || getToken() !== token) return
      saveToken(session.accessToken)
      wx.reLaunch({ url: '/pages/me/index' })
    } catch (error) {
      if (this._closed || getToken() !== token) return
      const message = error.code === 'WECHAT_BIND_REQUIRED'
        ? '此微信尚未绑定账号。请先使用已有账号密码登录，再到「我的」绑定微信。没有账号请先在 Web 端注册。'
        : error.code === 'WECHAT_NOT_CONFIGURED'
          ? '微信登录暂未开放，请使用账号密码登录。'
          : error.message
      this.setData({
        error: message,
        showPasswordLogin: this.data.showPasswordLogin
          || error.code === 'WECHAT_BIND_REQUIRED' || error.code === 'WECHAT_NOT_CONFIGURED',
      })
    } finally {
      if (!this._closed) this.setData({ loading: false, loginMethod: '', password: '' })
    }
  },
})
