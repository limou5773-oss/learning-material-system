const { ApiError, getToken, request } = require('../../utils/api')

function draftKey(id) { return `lms.noteDraft.v1.${id}` }

Page({
  data: {
    id: '', material: null, serverCopy: null, title: '', subject: '', tagsText: '', noteText: '',
    loading: false, saving: false, changed: false, saved: false, restored: false, conflict: false, error: '',
  },
  onLoad(options) { this.setData({ id: options.id || '' }) },
  onShow() {
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    if (!this.data.material && !this.data.loading) this.load()
  },
  onHide() { this.persistDraft() },
  onUnload() { this.persistDraft() },
  persistDraft() {
    if (!this.data.changed || this.data.saved || !this.data.material) return
    wx.setStorageSync(draftKey(this.data.id), {
      version: this.data.material.version, title: this.data.title, subject: this.data.subject,
      tagsText: this.data.tagsText, noteText: this.data.noteText,
    })
  },
  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const material = await request(`/materials/${encodeURIComponent(this.data.id)}`)
      const draft = wx.getStorageSync(draftKey(this.data.id))
      const validDraft = draft && typeof draft === 'object' && Number.isInteger(draft.version)
      const form = validDraft ? draft : {
        title: material.title, subject: material.subject || '',
        tagsText: material.tags.map(tag => tag.name).join('，'), noteText: material.noteText || '',
      }
      const conflict = !!validDraft && draft.version !== material.version
      this.setData({ material, serverCopy: conflict ? material : null,
        title: form.title, subject: form.subject, tagsText: form.tagsText, noteText: form.noteText,
        restored: !!validDraft, changed: !!validDraft, conflict,
        error: conflict ? '服务器已有新版本，本地草稿仍在下方。请先对照内容。' : '',
      })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '笔记加载失败' })
    } finally { this.setData({ loading: false }) }
  },
  onTitleInput(event) { this.setData({ title: event.detail.value, changed: true }); this.persistDraft() },
  onSubjectInput(event) { this.setData({ subject: event.detail.value, changed: true }); this.persistDraft() },
  onTagsInput(event) { this.setData({ tagsText: event.detail.value, changed: true }); this.persistDraft() },
  onNoteInput(event) { this.setData({ noteText: event.detail.value, changed: true }); this.persistDraft() },
  async submit() {
    if (!this.data.material || this.data.saving || this.data.conflict) return
    const tags = this.data.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean)
    if (!this.data.title.trim() || !this.data.noteText.trim()) {
      this.setData({ error: '请填写标题和正文' }); return
    }
    if (tags.length > 10) { this.setData({ error: '最多添加 10 个标签' }); return }
    this.setData({ saving: true, error: '' })
    try {
      await request(`/materials/${encodeURIComponent(this.data.id)}`, { method: 'PATCH', data: {
        version: this.data.material.version, title: this.data.title.trim(), subject: this.data.subject.trim(),
        tags, noteText: this.data.noteText,
      } })
      this.setData({ saved: true, changed: false })
      wx.removeStorageSync(draftKey(this.data.id))
      wx.navigateBack()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        this.setData({ conflict: true, error: '服务器已有新版本，本地草稿已保留。请先对照内容。' })
        this.persistDraft()
        try {
          const serverCopy = await request(`/materials/${encodeURIComponent(this.data.id)}`)
          this.setData({ serverCopy })
        } catch (_) { /* 草稿仍保留，可稍后重试 */ }
      } else if (error instanceof ApiError && error.status === 401) {
        this.persistDraft(); wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else { this.setData({ error: error.message || '保存失败，本地草稿已保留' }); this.persistDraft() }
    } finally { this.setData({ saving: false }) }
  },
  discardDraft() {
    wx.showModal({ title: '放弃本地草稿', content: '确定用服务器版本替换当前编辑内容吗？',
      success: response => {
        if (!response.confirm || !this.data.serverCopy) return
        const material = this.data.serverCopy
        wx.removeStorageSync(draftKey(this.data.id))
        this.setData({ material, serverCopy: null, title: material.title, subject: material.subject || '',
          tagsText: material.tags.map(tag => tag.name).join('，'), noteText: material.noteText || '',
          changed: false, restored: false, conflict: false, error: '',
        })
      },
    })
  },
})
