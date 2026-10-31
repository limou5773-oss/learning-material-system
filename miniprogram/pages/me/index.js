const { ApiError, getToken, clearToken, request, getMe, updateMe, bindWechat, unbindWechat, logout } = require('../../utils/api')

function newPasswordError(value) {
  if (!value.trim() || value.length < 8 || value.length > 64) return '新密码须为 8–64 个字符'
  let bytes = 0
  for (const character of value) {
    const code = character.codePointAt(0)
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4
  }
  return bytes > 72 ? '新密码的 UTF-8 编码不能超过 72 字节，请减少汉字或表情' : ''
}

Page({
  data: {
    user: null, loading: false, pending: '', error: '', errorAction: '', notice: '', displayName: '',
    currentPassword: '', newPassword: '', unbindPassword: '',
    nameError: '', currentPasswordError: '', newPasswordError: '', unbindPasswordError: '',
    activePanel: '', overview: null, overviewError: '',
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) this.getTabBar().setData({ selected: 3, hidden: false })
    return this.refresh()
  },
  onHide() { this.clearPasswords(); this.setData({ activePanel: '' }); this.setTabBarHidden(false) },
  onUnload() { this._closed = true },

  clearPasswords() { this.setData({ currentPassword: '', newPassword: '', unbindPassword: '' }) },
  isCurrentSession(token) { return !this._closed && getToken() === token },
  openMaterials() { wx.switchTab({ url: '/pages/materials/index' }) },
  openTasks() { wx.switchTab({ url: '/pages/tasks/index' }) },
  openNotifications() { wx.navigateTo({ url: '/pages/notifications/index' }) },
  openCards() { wx.switchTab({ url: '/pages/cards/index' }) },
  openFavorites() { wx.navigateTo({ url: '/pages/favorites/index' }) },
  newNote() { wx.navigateTo({ url: '/pages/note/new' }) },
  newImage() { wx.navigateTo({ url: '/pages/image/new' }) },
  setTabBarHidden(hidden) {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) this.getTabBar().setData({ hidden })
  },
  openPanel(event) {
    if (this.data.pending) return
    this.clearPasswords()
    this.setData({ activePanel: event.currentTarget.dataset.panel, displayName: this.data.user.displayName,
      error: '', errorAction: '', nameError: '', currentPasswordError: '', newPasswordError: '', unbindPasswordError: '' })
    this.setTabBarHidden(true)
  },
  closePanel() {
    if (this.data.pending) return
    this.clearPasswords()
    this.setData({ activePanel: '' })
    this.setTabBarHidden(false)
  },
  stopPropagation() {},
  onNameInput(event) { this.setData({ displayName: event.detail.value, nameError: '' }) },
  onCurrentPasswordInput(event) { this.setData({ currentPassword: event.detail.value, currentPasswordError: '' }) },
  onNewPasswordInput(event) { this.setData({ newPassword: event.detail.value, newPasswordError: '' }) },
  onUnbindPasswordInput(event) { this.setData({ unbindPassword: event.detail.value, unbindPasswordError: '' }) },

  handleError(error, token) {
    if (this._closed || (getToken() && getToken() !== token)) return
    if (error instanceof ApiError && error.status === 401) {
      this.setData({ user: null })
      this.clearPasswords()
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    } else if (this.isCurrentSession(token)) {
      const message = error.code === 'WECHAT_NOT_CONFIGURED'
        ? '微信绑定暂未开放，你可以继续使用账号密码登录。'
        : error.code === 'WECHAT_BIND_CONFLICT'
          ? '此微信已绑定其他账号，请先在原账号解绑。当前账号的绑定状态未改变。'
          : error.code === 'USER_WECHAT_ALREADY_BOUND'
            ? '此账号已绑定其他微信，请先解绑后再操作。当前绑定状态未改变。'
            : error.message
      this.setData({ error: message, errorAction: this.data.pending })
    }
  },

  async refresh() {
    const token = getToken()
    if (!token) {
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      return
    }
    if (this.data.loading || this.data.pending) return
    this.setData({ loading: true, error: '', errorAction: '' })
    try {
      const user = await getMe()
      if (!this.isCurrentSession(token)) return
      this.setData({ user, displayName: user.displayName })
      await this.loadOverview(token)
    } catch (error) {
      this.handleError(error, token)
    } finally {
      if (this.isCurrentSession(token)) this.setData({ loading: false })
    }
  },

  async loadOverview(token) {
    this.setData({ overview: null, overviewError: '' })
    try {
      const [materials, cards, tasks, favorites] = await Promise.all([
        request('/materials?page=1&pageSize=1&status=ALL'),
        request('/cards?page=1&pageSize=1'),
        request('/ai-tasks?page=1&pageSize=1'),
        request('/favorites?page=1&pageSize=1'),
      ])
      if (this.isCurrentSession(token)) {
        this.setData({ overview: { materials: materials.total, cards: cards.total,
          tasks: tasks.total, favorites: favorites.total } })
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) this.handleError(error, token)
      else if (this.isCurrentSession(token)) this.setData({ overviewError: error.message })
    }
  },

  async runAction(action, execute, onSuccess) {
    if (this.data.loading || this.data.pending) return
    const token = getToken()
    if (!token) {
      wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      return
    }
    this.setData({ pending: action, error: '', errorAction: '', notice: '' })
    try {
      const result = await execute()
      if (this.isCurrentSession(token)) onSuccess(result)
    } catch (error) {
      this.handleError(error, token)
    } finally {
      if (!this._closed && (getToken() === token || !getToken())) {
        this.setData({ pending: '' })
        if (action === 'password') this.setData({ currentPassword: '', newPassword: '' })
        if (action === 'unbind') this.setData({ unbindPassword: '' })
      }
    }
  },

  submitName() {
    const displayName = this.data.displayName.trim()
    const nameError = displayName.length < 1 || displayName.length > 64 ? '昵称须为 1–64 个字符' : ''
    this.setData({ nameError })
    if (nameError) return
    return this.runAction('name', () => updateMe({ displayName }), user => {
      this.setData({ user, displayName: user.displayName, notice: '昵称已更新', activePanel: '' })
      this.setTabBarHidden(false)
    })
  },

  submitPassword() {
    const currentPasswordError = this.data.currentPassword ? '' : '请输入当前密码'
    const passwordError = newPasswordError(this.data.newPassword)
    this.setData({ currentPasswordError, newPasswordError: passwordError })
    if (currentPasswordError || passwordError) return
    const { currentPassword, newPassword } = this.data
    return this.runAction('password', () => updateMe({ currentPassword, newPassword }), () => {
      clearToken()
      this.setData({ user: null })
      wx.reLaunch({ url: '/pages/login/index?reason=password-changed' })
    })
  },

  submitBinding() {
    return this.runAction('bind', bindWechat, user => {
      this.setData({ user, notice: '微信绑定成功，下次可直接使用微信登录' })
    })
  },

  submitUnbind() {
    const currentPassword = this.data.unbindPassword
    const unbindPasswordError = currentPassword ? '' : '请输入当前密码以确认解绑'
    this.setData({ unbindPasswordError })
    if (unbindPasswordError) return
    return this.runAction('unbind', () => unbindWechat(currentPassword), () => {
      clearToken()
      this.setData({ user: null })
      wx.reLaunch({ url: '/pages/login/index?reason=wechat-unbound' })
    })
  },

  submitLogout() {
    return this.runAction('logout', logout, () => {
      clearToken()
      this.setData({ user: null })
      wx.reLaunch({ url: '/pages/login/index' })
    })
  },
})
