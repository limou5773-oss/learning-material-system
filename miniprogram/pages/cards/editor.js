const { ApiError, getToken, getMe, request } = require('../../utils/api')
const { cardForm, cardPayload, draftKey, readDraft } = require('../../utils/card-editor')

Page({
  data: { id: '', userId: '', card: null, ready: false, editingVersion: 0, title: '', content: '', summary: '', subject: '', tagsText: '',
    serverCopy: null, currentVersion: null, serverLoading: false, serverError: '', conflict: false,
    loading: false, saving: false, saved: false, changed: false, restored: false, error: '', draftError: '' },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() {
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    if (!this.data.loading && (!this.data.ready || this.loadedToken !== getToken())) this.load()
  },
  onHide() { this.persistDraft() },
  onUnload() { this.persistDraft() },
  form() { const { title, content, summary, subject, tagsText } = this.data; return { title, content, summary, subject, tagsText } },
  persistDraft() {
    if (!this.data.ready || !this.data.userId || this.data.saved) return
    try {
      const key = draftKey(this.data.userId, this.data.id)
      if (this.data.changed) wx.setStorageSync(key, { version: this.data.editingVersion, form: this.form() })
      else wx.removeStorageSync(key)
    } catch { this.setData({ draftError: '本机草稿保存失败，请保留页面并复制未保存内容。' }) }
  },
  async load() {
    if (this.data.loading) return
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '', ready: false, conflict: false, serverCopy: null, serverError: '', currentVersion: null, saved: false, changed: false, restored: false })
    try {
      this.loadedToken = getToken()
      const user = await getMe()
      const card = this.data.id ? await request(`/cards/${encodeURIComponent(this.data.id)}`) : null
      let draft = null
      try { draft = readDraft(wx.getStorageSync(draftKey(user.id, this.data.id)), !!this.data.id) }
      catch { this.setData({ draftError: '本机草稿读取失败，当前显示服务器内容。' }) }
      const conflict = !!draft && !!card && draft.version !== card.version
      this.setData({ userId: user.id, card, ready: true, ...draft ? draft.form : cardForm(card), editingVersion: draft ? draft.version : card ? card.version : 0,
        restored: !!draft, changed: !!draft, conflict, serverCopy: conflict ? card : null, currentVersion: conflict ? card.version : null,
        error: conflict ? '服务器已有新版本，本机草稿已恢复。请先对照内容。' : '' })
    } catch (error) { this.handleError(error, '卡片加载失败') }
    finally { this.setData({ loading: false }) }
  },
  onTitleInput(event) { this.change('title', event.detail.value) },
  onContentInput(event) { this.change('content', event.detail.value) },
  onSummaryInput(event) { this.change('summary', event.detail.value) },
  onSubjectInput(event) { this.change('subject', event.detail.value) },
  onTagsInput(event) { this.change('tagsText', event.detail.value) },
  change(key, value) {
    if (this.data.saving) return
    this.setData({ [key]: value, changed: true }); this.persistDraft()
  },
  async submit() {
    if (!this.data.ready || this.data.saving || this.data.conflict) return
    if (this.loadedToken !== getToken()) { this.setData({ error: '登录账号已变更，请重新加载卡片后编辑。', ready: false }); return }
    let payload
    try { payload = cardPayload(this.form(), this.data.card && this.data.card.tags) } catch (error) { this.setData({ error: error.message }); return }
    this.setData({ saving: true, error: '' })
    try {
      const path = this.data.id ? `/cards/${encodeURIComponent(this.data.id)}` : '/cards'
      const card = await request(path, { method: this.data.id ? 'PATCH' : 'POST', data: this.data.id ? { version: this.data.editingVersion, ...payload } : payload })
      this.setData({ saved: true, changed: false })
      try { wx.removeStorageSync(draftKey(this.data.userId, this.data.id)) }
      catch { this.setData({ draftError: '保存成功，但本机草稿清理失败。' }) }
      wx.redirectTo({ url: `/pages/cards/detail?id=${encodeURIComponent(card.id)}` })
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        this.setData({ conflict: true, currentVersion: error.currentVersion || null, serverCopy: null,
          error: '服务器已有新版本，本机草稿已保留。请先对照内容。' })
        this.persistDraft()
        await this.loadServerCopy()
      } else { this.handleError(error, '保存失败，当前输入已保留'); this.persistDraft() }
    } finally { this.setData({ saving: false }) }
  },
  async loadServerCopy() {
    if (this.data.serverLoading) return
    this.setData({ serverLoading: true, serverError: '' })
    try {
      const serverCopy = await request(`/cards/${encodeURIComponent(this.data.id)}`)
      this.setData({ serverCopy, currentVersion: serverCopy.version })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      this.setData({ serverError: error.message || '读取服务器版本失败' })
    } finally { this.setData({ serverLoading: false }) }
  },
  discardDraft() {
    if (!this.data.serverCopy || this.data.serverLoading) return
    wx.showModal({ title: '放弃本机草稿', content: '确定用服务器版本替换当前五项编辑内容吗？', success: result => {
      if (!result.confirm) return
      const card = this.data.serverCopy
      this.setData({ card, ...cardForm(card), editingVersion: card.version, conflict: false, currentVersion: null,
        serverCopy: null, serverError: '', error: '', changed: false, restored: false })
      this.persistDraft()
    } })
  },
  handleError(error, fallback) {
    if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
    else this.setData({ error: error.message || fallback })
  },
})
