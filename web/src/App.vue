<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { session } from './auth'
import UiIcon from './components/UiIcon.vue'
import AdminLayout from './layouts/AdminLayout.vue'
import LearningLayout from './layouts/LearningLayout.vue'

const route = useRoute()
const activeLayout = computed(() => route.meta.workspace === 'admin'
  || (route.name === 'forbidden' && session.value?.user.role === 'ADMIN') ? AdminLayout : LearningLayout)
</script>

<template>
  <component :is="activeLayout" v-if="route.meta.requiresAuth"><router-view /></component>
  <div v-else class="site-shell public-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="site-header"><router-link class="brand" to="/login" aria-label="智能学习资料整理平台"><span class="brand-mark"><UiIcon name="cards" /></span><span class="brand-name">智能学习资料整理平台</span></router-link><nav class="header-links" aria-label="账号导航"><router-link to="/login">登录</router-link><router-link to="/register" class="nav-register">创建账号 <span>↗</span></router-link></nav></header>
    <main id="main-content" class="main-area" tabindex="-1"><router-view /></main>
    <footer class="site-footer"><span>智能学习资料整理平台 · 让知识有序，让学习专注</span><span>Web 与微信小程序协同</span></footer>
  </div>
</template>
