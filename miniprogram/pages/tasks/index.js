const { ApiError, getToken, request } = require('../../utils/api')

const goalLabels = { REVIEW_CARDS: '生成复习卡片', EXTRACT_KEY_POINTS: '提炼课件重点', LINK_NOTES: '整理并关联已有笔记' }
const statusLabels = { QUEUED: '排队中', RUNNING: '执行中', WAIT_CONFIRM: '待确认', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }

Page({
  data: { items: [], page: 1, pageSize: 20, total: 0, loading: false, error: '' },
  onShow() { this.load() },
  async load() {
    if (!getToken()) { wx.reLaunch({ url: '/pages/login/index?reason=expired' }); return }
    this.setData({ loading: true, error: '' })
    try {
      const result = await request(`/ai-tasks?page=${this.data.page}&pageSize=${this.data.pageSize}`)
      this.setData({ items: result.items.map(item => ({ ...item,
        goalLabel: goalLabels[item.goalType] || item.goalType,
        statusLabel: statusLabels[item.status] || item.status,
        shortDate: item.createdAt.slice(0, 10),
      })), total: result.total })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) wx.reLaunch({ url: '/pages/login/index?reason=expired' })
      else this.setData({ error: error.message || '任务列表读取失败' })
    } finally { this.setData({ loading: false }) }
  },
  openDetail(event) { wx.navigateTo({ url: `/pages/tasks/detail?id=${event.currentTarget.dataset.id}` }) },
  previous() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.load() } },
  next() { if (this.data.page * this.data.pageSize < this.data.total) { this.setData({ page: this.data.page + 1 }); this.load() } },
})
