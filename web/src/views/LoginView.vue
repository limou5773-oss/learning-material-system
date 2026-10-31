<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { session, type SessionToken } from '../auth'
import UiIcon from '../components/UiIcon.vue'

const route = useRoute()
const router = useRouter()
const form = reactive({ username: String(route.query.username || ''), password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (!form.username.trim() || !form.password) {
    error.value = '请输入账号和密码'
    return
  }
  loading.value = true
  try {
    session.value = await api<SessionToken>('/auth/sessions', 'POST', {
      username: form.username,
      password: form.password,
      clientType: 'WEB',
    })
    form.password = ''
    const defaultHome = session.value.user.role === 'ADMIN' ? '/admin/statistics' : '/materials'
    const requested = typeof route.query.redirect === 'string'
      && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
      ? route.query.redirect : ''
    const redirect = session.value.user.role === 'ADMIN' && ['/', '/me', '/materials'].includes(requested)
      ? defaultHome : requested || defaultHome
    await router.replace(redirect)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-layout editorial-auth">
    <section class="intro">
      <header class="masthead">
        <span class="masthead-name">Learning Library</span>
        <span class="masthead-meta">No.01 — 2026</span>
      </header>
      <p class="kicker"><UiIcon name="spark" />你的个人学习空间</p>
      <h1>收好每一份资料，<br /><em>留住每一点收获。</em></h1>
      <div class="rule-row" aria-hidden="true"><i class="rule-line"></i><span>笔记 · 课件 · 灵感</span></div>
      <p class="lede">手机随手拍，电脑专注整理。把课堂笔记、课件与灵感收在一起，借助 AI 提炼要点，逐步建立自己的知识库。</p>
      <ul class="index-points">
        <li><span>01</span>双端资料同步</li>
        <li><span>02</span>AI 智能整理</li>
        <li><span>03</span>知识卡片积累</li>
      </ul>
    </section>

    <section class="auth-card" aria-labelledby="login-title">
      <div class="card-head"><span class="card-issue">Member Access</span></div>
      <h2 id="login-title">欢迎回来</h2>
      <p class="card-subtitle">使用账号密码登录学材</p>
      <el-alert v-if="error" class="form-error" :title="error" type="error" show-icon :closable="false" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-button class="primary-action" type="primary" native-type="submit" :loading="loading">登录</el-button>
      </el-form>
      <p class="card-bottom">还没有账号？<router-link to="/register">创建账号</router-link></p>
    </section>
  </div>
</template>
