const { ApiError, getToken, uploadImage } = require('../../utils/api')

Page({
  data: {
    filePath: '', fileSize: 0, title: '', subject: '', tagsText: '',
    uploading: false, uploadProgress: 0, error: '', existingId: '',
  },
  onShow() {
    if (!getToken()) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
  },
  onUnload() { this._uploadSequence = (this._uploadSequence || 0) + 1 },
  camera() { this.choose('camera') },
  album() { this.choose('album') },
  choose(source) {
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: [source],
      success: result => {
        const selected = result.tempFiles && result.tempFiles[0]
        if (!selected || !selected.tempFilePath) return
        const path = selected.tempFilePath
        if (!/\.(jpe?g|png)$/i.test(path)) {
          this.setData({ error: '请选择 JPEG 或 PNG 图片', filePath: '' }); return
        }
        if (selected.size > 10 * 1024 * 1024) {
          this.setData({ error: '图片不能超过 10 MiB', filePath: '' }); return
        }
        const base = path.split('/').pop().replace(/\.[^.]+$/, '')
        this.setData({ filePath: path, fileSize: selected.size || 0,
          title: this.data.title.trim() || base || '图片资料', error: '', existingId: '' })
      },
      fail: error => {
        if (!error || !String(error.errMsg || '').includes('cancel')) {
          this.setData({ error: '选择图片失败，请重试' })
        }
      },
    })
  },
  onTitleInput(event) { this.setData({ title: event.detail.value }) },
  onSubjectInput(event) { this.setData({ subject: event.detail.value }) },
  onTagsInput(event) { this.setData({ tagsText: event.detail.value }) },
  submit() { return this.upload(false) },
  async upload(allowDuplicate) {
    if (!this.data.filePath || this.data.uploading) return
    const tags = this.data.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean)
    if (!this.data.title.trim()) { this.setData({ error: '请填写标题' }); return }
    if (tags.length > 10) { this.setData({ error: '最多添加 10 个标签' }); return }
    const token = getToken()
    if (!token) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    const sequence = this._uploadSequence = (this._uploadSequence || 0) + 1
    const current = () => sequence === this._uploadSequence && getToken() === token
    this.setData({ uploading: true, uploadProgress: 0, error: '', existingId: '' })
    try {
      const material = await uploadImage(this.data.filePath, {
        title: this.data.title.trim(), subject: this.data.subject.trim(),
        tagsJson: JSON.stringify(tags), allowDuplicate: String(allowDuplicate),
      }, result => {
        if (current() && this.data.uploading) this.setData({ uploadProgress: result.progress })
      })
      if (!current()) return
      wx.redirectTo({ url: `/pages/materials/detail?id=${material.id}` })
    } catch (error) {
      if (sequence !== this._uploadSequence || (getToken() && getToken() !== token)) return
      if (error instanceof ApiError && error.status === 409 && error.existingMaterialId) {
        this.setData({ error: '这张图片已上传过。可查看已有资料或确认保存副本。', existingId: error.existingMaterialId })
      } else if (error instanceof ApiError && error.status === 401) {
        wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      } else this.setData({ error: error.message || '上传失败' })
    } finally { if (sequence === this._uploadSequence) this.setData({ uploading: false }) }
  },
  saveCopy() { return this.upload(true) },
  openExisting() {
    if (this.data.existingId) wx.navigateTo({ url: `/pages/materials/detail?id=${this.data.existingId}` })
  },
})
