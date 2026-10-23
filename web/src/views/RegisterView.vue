<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import type { User } from '../auth'

const router = useRouter()
const form = reactive({ username: '', displayName: '', password: '', confirmPassword: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (!/^[A-Za-z0-9_]{6,32}$/.test(form.username)) {
    error.value = '用户名需为 6–32 位英文字母、数字或下划线'
    return
  }
  if (!form.displayName.trim() || form.displayName.length > 64) {
    error.value = '显示名称需为 1–64 个字符'
    return
  }
  if (form.password.length < 8 || form.password.length > 64 || new TextEncoder().encode(form.password).length > 72) {
    error.value = '密码需为 8–64 个字符，UTF-8 编码不超过 72 字节'
    return
  }
  if (form.password !== form.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await api<User>('/auth/registrations', 'POST', {
      username: form.username,
      password: form.password,
      displayName: form.displayName,
    })
    form.password = ''
    form.confirmPassword = ''
    await router.replace({ name: 'login', query: { username: form.username } })
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-layout">
    <section class="intro">
      <span class="intro-eyebrow">START YOUR LEARNING SPACE</span>
      <h1>为你的知识，<br />留一个位置。</h1>
      <p>创建个人账号，开始建立属于自己的学习资料空间。注册后使用同一账号登录 Web 和后续的小程序。</p>
      <div class="intro-points"><span>自主整理</span><span>安心保存</span><span>随时查阅</span></div>
    </section>

    <section class="auth-card" aria-labelledby="register-title">
      <h2 id="register-title">创建账号</h2>
      <p class="card-subtitle">只需几步，开启你的学习空间</p>
      <el-alert v-if="error" class="form-error" :title="error" type="error" show-icon :closable="false" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" maxlength="32" placeholder="6–32 位字母、数字或下划线" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="form.displayName" autocomplete="nickname" maxlength="64" placeholder="希望如何称呼你" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" show-password placeholder="再输入一次密码" />
        </el-form-item>
        <el-button class="primary-action" type="primary" native-type="submit" :loading="loading">创建账号</el-button>
      </el-form>
      <p class="card-bottom">已有账号？<router-link to="/login">返回登录</router-link></p>
    </section>
  </div>
</template>
