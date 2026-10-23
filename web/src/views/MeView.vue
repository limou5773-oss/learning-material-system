<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession, session, type User } from '../auth'

const router = useRouter()
const user = ref<User | null>(session.value?.user || null)
const error = ref('')
const loading = ref(false)

onMounted(async () => {
  try {
    user.value = await api<User>('/users/me')
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession()
      await router.replace('/login')
    } else {
      error.value = cause instanceof Error ? cause.message : '资料加载失败'
    }
  }
})

async function logout() {
  loading.value = true
  error.value = ''
  try {
    await api<void>('/auth/sessions/current', 'DELETE')
    clearSession()
    await router.replace('/login')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '退出失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'long' }).format(new Date(value))
}
</script>

<template>
  <div class="profile-layout">
    <div class="profile-heading">
      <span class="intro-eyebrow">PERSONAL SPACE</span>
      <h1>我的信息</h1>
      <p>这里展示你的账号资料，更多学习功能正在逐步开放。</p>
    </div>

    <section class="profile-card" aria-labelledby="profile-title">
      <el-alert v-if="error" class="form-error" :title="error" type="error" show-icon :closable="false" />
      <template v-if="user">
        <div class="profile-top">
          <div class="avatar" aria-hidden="true">{{ user.displayName.slice(0, 1) }}</div>
          <div>
            <h2 id="profile-title">{{ user.displayName }}</h2>
            <p>@{{ user.username }}</p>
          </div>
        </div>
        <dl class="profile-grid">
          <div class="profile-field"><dt>用户 ID</dt><dd>{{ user.id }}</dd></div>
          <div class="profile-field"><dt>账号类型</dt><dd>{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</dd></div>
          <div class="profile-field"><dt>账号状态</dt><dd><el-tag type="success" effect="plain">{{ user.status === 'ENABLED' ? '正常' : '已停用' }}</el-tag></dd></div>
          <div class="profile-field"><dt>创建时间</dt><dd>{{ formatDate(user.createdAt) }}</dd></div>
        </dl>
        <div class="profile-actions">
          <p><router-link to="/materials">查看我的资料 →</router-link><br />刷新页面后需要重新登录。</p>
          <el-button :loading="loading" @click="logout">退出登录</el-button>
        </div>
      </template>
    </section>
  </div>
</template>
