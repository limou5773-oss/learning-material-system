<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { session } from '../auth'
import UiIcon from '../components/UiIcon.vue'

defineProps<{
  label: string; home: string; accountPath: string; accountLabel: string; learning?: boolean
  links: readonly { to: string; label: string; icon: InstanceType<typeof UiIcon>['$props']['name'] }[]
}>()
const route = useRoute()
const menuOpen = ref(false)
const sidebar = ref<HTMLElement>()
const mainContent = ref<HTMLElement>()
const menuButton = ref<HTMLButtonElement>()
async function toggleMenu() {
  menuOpen.value = !menuOpen.value
  if (menuOpen.value) {
    await nextTick()
    sidebar.value?.querySelector<HTMLAnchorElement>('a')?.focus()
  }
}
function navigationKeydown(event: KeyboardEvent) {
  if (!menuOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault(); menuOpen.value = false; menuButton.value?.focus()
  } else if (event.key === 'Tab') {
    const links = sidebar.value!.querySelectorAll<HTMLAnchorElement>('a[href]')
    const first = links[0]!, last = links[links.length - 1]!
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
}
const createMenu = ref<HTMLDetailsElement>()
function selected(to: string) {
  if (to === '/cards/organize') return route.path === to
  if (to === '/cards' && route.path === '/cards/organize') return false
  return route.path === to || route.path.startsWith(`${to}/`)
}
watch(() => route.fullPath, () => {
  menuOpen.value = false
  if (createMenu.value) createMenu.value.open = false
})
// 切换业务页面回到内容起点，筛选参数变化保留当前位置。
watch(() => route.path, () => {
  if (mainContent.value) mainContent.value.scrollTop = 0
}, { flush: 'post' })
watch(() => session.value?.user.id, () => { menuOpen.value = false })
</script>

<template>
  <div v-if="session" class="site-shell workspace-shell" @keydown="navigationKeydown">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <button v-if="menuOpen" class="sidebar-backdrop" aria-label="关闭导航菜单" @click="menuOpen = false" />
    <aside ref="sidebar" id="primary-navigation" class="workspace-sidebar" :class="{ 'is-open': menuOpen }">
      <router-link class="brand" :to="home" aria-label="智能学习资料整理平台"><span class="brand-mark"><UiIcon name="cards" /></span><span class="brand-name">智能学习资料<br />整理平台</span></router-link>
      <nav class="workspace-navigation" aria-label="主导航"><p class="nav-group-label">{{ label }}</p><router-link v-for="link in links" :key="link.to" :to="link.to" class="sidebar-link" :class="{ 'is-selected': selected(link.to) }" :aria-current="selected(link.to) ? 'page' : undefined"><UiIcon :name="link.icon" /><span>{{ link.label }}</span></router-link></nav>
      <nav class="sidebar-personal" aria-label="我的账号"><router-link v-if="learning" to="/notifications" class="sidebar-link" :class="{ 'is-selected': selected('/notifications') }"><UiIcon name="bell" /><span>消息通知</span></router-link><router-link :to="accountPath" class="sidebar-link" :class="{ 'is-selected': selected(accountPath) }"><UiIcon name="user" /><span>{{ accountLabel }}</span></router-link></nav>
    </aside>
    <header class="workspace-topbar">
      <button ref="menuButton" class="menu-toggle icon-button" :aria-expanded="menuOpen" aria-controls="primary-navigation" :aria-label="menuOpen ? '收起导航菜单' : '展开导航菜单'" @click="toggleMenu"><UiIcon name="menu" /></button>
      <div class="topnav-actions">
        <details v-if="learning" ref="createMenu" class="create-menu" @keydown.esc="createMenu!.open = false"><summary><UiIcon name="plus" /><span>新建内容</span></summary><div class="create-menu-items"><router-link to="/materials/new"><UiIcon name="note" /><span>文字笔记<small>随手记录学习想法</small></span></router-link><router-link to="/materials/image/new"><UiIcon name="image" /><span>图片资料<small>上传课件与课堂板书</small></span></router-link><router-link to="/materials/pdf/new"><UiIcon name="pdf" /><span>PDF 文档<small>批量导入学习资料</small></span></router-link><router-link to="/cards/new"><UiIcon name="cards" /><span>知识卡片<small>提炼一个清晰的知识点</small></span></router-link></div></details>
        <router-link v-if="learning" class="icon-button" to="/notifications" aria-label="查看消息通知"><UiIcon name="bell" /></router-link>
        <router-link class="account-entry" :class="{ 'is-selected': selected(accountPath) }" :to="accountPath" :aria-label="`进入${accountLabel}`"><span class="account-avatar">{{ session.user.displayName.slice(0, 1) }}</span><span class="account-text"><strong :title="session.user.displayName">{{ session.user.displayName }}</strong><small>{{ learning ? '我的学习账号' : '平台管理员' }}</small></span></router-link>
      </div>
    </header>
    <main ref="mainContent" id="main-content" class="main-area" tabindex="-1"><slot />
    <footer class="site-footer"><span>智能学习资料整理平台 · 让知识有序，让学习专注</span><span>{{ learning ? 'Web 与微信小程序协同' : '平台管理工作台' }}</span></footer></main>
  </div>
</template>
