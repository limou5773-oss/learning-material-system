const I = {
  materialsGray: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjOTRhMGIwIiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNNiAyaDhsNCA0djE2SDZ6Ii8+PHBhdGggZD0iTTE0IDJ2NGg0Ii8+PHBhdGggZD0iTTkgMTJoNiIvPjxwYXRoIGQ9Ik05IDE2aDYiLz48L3N2Zz4=',
  materialsBlue: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMmY3YmY2IiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cGF0aCBkPSJNNiAyaDhsNCA0djE2SDZ6Ii8+PHBhdGggZD0iTTE0IDJ2NGg0Ii8+PHBhdGggZD0iTTkgMTJoNiIvPjxwYXRoIGQ9Ik05IDE2aDYiLz48L3N2Zz4=',
  cardsGray: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjOTRhMGIwIiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSIzIiB5PSI1IiB3aWR0aD0iMTQiIGhlaWdodD0iMTAiIHJ4PSIyIi8+PHBhdGggZD0iTTcgMTloMTRWOSIvPjwvc3ZnPg==',
  cardsBlue: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMmY3YmY2IiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSIzIiB5PSI1IiB3aWR0aD0iMTQiIGhlaWdodD0iMTAiIHJ4PSIyIi8+PHBhdGggZD0iTTcgMTloMTRWOSIvPjwvc3ZnPg==',
  tasksGray: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjOTRhMGIwIiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSI0IiB5PSIzIiB3aWR0aD0iMTYiIGhlaWdodD0iMTgiIHJ4PSIyIi8+PHBhdGggZD0iTTggOGg4Ii8+PHBhdGggZD0iTTggMTJoOCIvPjxwYXRoIGQ9Ik04IDE2aDUiLz48L3N2Zz4=',
  tasksBlue: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMmY3YmY2IiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSI0IiB5PSIzIiB3aWR0aD0iMTYiIGhlaWdodD0iMTgiIHJ4PSIyIi8+PHBhdGggZD0iTTggOGg4Ii8+PHBhdGggZD0iTTggMTJoOCIvPjxwYXRoIGQ9Ik04IDE2aDUiLz48L3N2Zz4=',
  imageBlue: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMmY3YmY2IiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48cmVjdCB4PSIzIiB5PSIzIiB3aWR0aD0iMTgiIGhlaWdodD0iMTgiIHJ4PSIzIi8+PGNpcmNsZSBjeD0iOCIgY3k9IjgiIHI9IjEuNSIvPjxwYXRoIGQ9Im0zIDE3IDUtNSA0IDQgMy0zIDYgNiIvPjwvc3ZnPg==',
  meGray: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjOTRhMGIwIiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48Y2lyY2xlIGN4PSIxMiIgY3k9IjgiIHI9IjQiLz48cGF0aCBkPSJNNCAyMWMwLTQgNC02IDgtNnM4IDIgOCA2Ii8+PC9zdmc+',
  meBlue: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMmY3YmY2IiBzdHJva2Utd2lkdGg9IjEuOCIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIiBzdHJva2UtbGluZWpvaW49InJvdW5kIj48Y2lyY2xlIGN4PSIxMiIgY3k9IjgiIHI9IjQiLz48cGF0aCBkPSJNNCAyMWMwLTQgNC02IDgtNnM4IDIgOCA2Ii8+PC9zdmc+'
}

Component({
  data: {
    selected: 0,
    hidden: false,
    createOpen: false,
    createBusy: false,
    createError: '',
    createActions: [
      { key: 'note', label: '新增笔记', description: '随手记录学习灵感', path: '/pages/note/new', icon: I.materialsBlue },
      { key: 'image', label: '上传图片', description: '拍照或从相册选择', path: '/pages/image/new', icon: I.imageBlue },
      { key: 'card', label: '新建卡片', description: '整理一个知识点', path: '/pages/cards/editor', icon: I.cardsBlue }
    ],
    list: [
      { pagePath: '/pages/materials/index', text: '资料', icon: I.materialsGray, iconActive: I.materialsBlue },
      { pagePath: '/pages/cards/index', text: '卡片', icon: I.cardsGray, iconActive: I.cardsBlue },
      { pagePath: '/pages/tasks/index', text: '任务', icon: I.tasksGray, iconActive: I.tasksBlue },
      { pagePath: '/pages/me/index', text: '我的', icon: I.meGray, iconActive: I.meBlue }
    ]
  },
  methods: {
    toggleCreate() {
      if (this.data.createBusy) return
      this.setData({ createOpen: !this.data.createOpen, createError: '' })
    },
    closeCreate() {
      if (this.data.createBusy) return
      this.setData({ createOpen: false, createError: '' })
    },
    stopPropagation() {},
    createItem(e) {
      if (this.data.createBusy) return
      this.setData({ createBusy: true, createError: '' })
      wx.navigateTo({
        url: e.currentTarget.dataset.path,
        success: () => this.setData({ createOpen: false }),
        fail: () => this.setData({ createError: '页面打开失败，请重试' }),
        complete: () => this.setData({ createBusy: false })
      })
    },
    switchTab(e) {
      if (this.data.createBusy) return
      this.closeCreate()
      const { path, index } = e.currentTarget.dataset
      if (index === this.data.selected) return
      wx.switchTab({ url: path })
    }
  }
})
