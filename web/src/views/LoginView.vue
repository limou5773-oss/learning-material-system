<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { session, type SessionToken } from '../auth'

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
    const redirect = typeof route.query.redirect === 'string'
      && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//')
      ? route.query.redirect : '/me'
    await router.replace(redirect)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-layout">
    <section class="intro">
      <span class="intro-eyebrow">YOUR LEARNING, IN ONE PLACE</span>
      <h1>让学习的每一步<br />都有归处。</h1>
      <p>整理散落的笔记与资料，从这里开始。登录后可查看你的个人信息，后续将逐步开放资料管理与智能整理功能。</p>
      <div class="intro-points"><span>统一账号</span><span>跨端使用</span><span>资料归档</span></div>
    </section>

    <section class="auth-card" aria-labelledby="login-title">
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
