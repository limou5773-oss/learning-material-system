const { ApiError, getToken, request } = require('../../utils/api')

Page({
  data: { title: '', subject: '', tagsText: '', noteText: '', loading: false, error: '' },
  onShow() {
    if (!getToken()) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
  },
  onTitleInput(event) { this.setData({ title: event.detail.value }) },
  onSubjectInput(event) { this.setData({ subject: event.detail.value }) },
  onTagsInput(event) { this.setData({ tagsText: event.detail.value }) },
  onNoteInput(event) { this.setData({ noteText: event.detail.value }) },

  async submit() {
    if (this.data.loading) return
    const title = this.data.title.trim()
    const tags = this.data.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean)
    if (!title || !this.data.noteText.trim()) {
      this.setData({ error: '请填写标题和正文' }); return
    }
    if (tags.length > 10) { this.setData({ error: '最多添加 10 个标签' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const material = await request('/materials', { method: 'POST', data: {
        type: 'NOTE', title, subject: this.data.subject.trim(), tags, noteText: this.data.noteText,
      } })
      wx.redirectTo({ url: `/pages/materials/detail?id=${material.id}` })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else { this.setData({ error: error.message || '保存失败' }) }
    } finally { this.setData({ loading: false }) }
  },
})
