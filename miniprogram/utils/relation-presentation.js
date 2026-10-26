const relationNotice = '以下关系由用户人工确认保存；关联说明不保证当前卡片正文事实正确，请结合两端来源核对。'
const presentations = {
  RELATED: { typeLabel: '相关知识', connector: '↔', directionLabel: '无方向关联，两端地位相同' },
  PREREQUISITE: { typeLabel: '前置知识', connector: '→', directionLabel: '起点是终点的前置知识' },
  EXAMPLE: { typeLabel: '示例', connector: '→', directionLabel: '终点是起点的示例' },
}
function relationPresentation(type) { return presentations[type] }

module.exports = { relationNotice, relationPresentation }
