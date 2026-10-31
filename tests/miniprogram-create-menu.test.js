const test = require('node:test')
const assert = require('node:assert/strict')

let definition, navigation, tabNavigation
global.Component = value => { definition = value }
global.wx = {
  navigateTo: options => { navigation = options },
  switchTab: options => { tabNavigation = options.url },
}
require('../miniprogram/custom-tab-bar/index')

function tabBar() {
  const instance = { ...definition.methods, data: structuredClone(definition.data) }
  instance.setData = patch => Object.assign(instance.data, patch)
  return instance
}
test.beforeEach(() => { navigation = null; tabNavigation = '' })

test('新建菜单开关不改变当前导航，切换导航关闭菜单并保留原有四项映射', () => {
  const bar = tabBar()
  bar.setData({ selected: 1 })
  bar.toggleCreate()
  assert.equal(bar.data.createOpen, true)
  assert.equal(bar.data.selected, 1)
  bar.closeCreate()
  assert.equal(bar.data.createOpen, false)
  bar.toggleCreate()
  bar.switchTab({ currentTarget: { dataset: { index: 2, path: bar.data.list[2].pagePath } } })
  assert.equal(tabNavigation, '/pages/tasks/index')
  assert.equal(bar.data.createOpen, false)
  assert.equal(bar.data.list[3].pagePath, '/pages/me/index')
})

test('笔记、图片、卡片入口打开对应现有页面，防止重复跳转，成功后关闭菜单', () => {
  const bar = tabBar()
  assert.deepEqual(bar.data.createActions.map(item => item.path), [
    '/pages/note/new', '/pages/image/new', '/pages/cards/editor',
  ])
  for (const item of bar.data.createActions) {
    bar.toggleCreate()
    const event = { currentTarget: { dataset: { path: item.path } } }
    bar.createItem(event)
    const firstNavigation = navigation
    assert.equal(navigation.url, item.path)
    bar.createItem(event)
    assert.equal(navigation, firstNavigation)
    navigation.success()
    navigation.complete()
    assert.equal(bar.data.createOpen, false)
    assert.equal(bar.data.createBusy, false)
  }
})

test('打开页面失败保留菜单和错误提示，允许重试或取消', () => {
  const bar = tabBar()
  bar.toggleCreate()
  bar.createItem({ currentTarget: { dataset: { path: '/pages/note/new' } } })
  navigation.fail()
  navigation.complete()
  assert.equal(bar.data.createOpen, true)
  assert.equal(bar.data.createBusy, false)
  assert.match(bar.data.createError, /重试/)
  bar.closeCreate()
  assert.equal(bar.data.createOpen, false)
  assert.equal(bar.data.createError, '')
})
