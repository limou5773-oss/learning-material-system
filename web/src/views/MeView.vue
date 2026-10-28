<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, ApiRequestError } from '../api'
import { clearSession, session, type User } from '../auth'

const router = useRouter()
const accountToken = session.value?.accessToken
const user = ref<User | null>(session.value?.user || null)
const displayName = ref(user.value?.displayName || '')
const passwords = reactive({ currentPassword: '', newPassword: '', unbindPassword: '' })
const fieldErrors = reactive({ displayName: '', currentPassword: '', newPassword: '', unbindPassword: '' })
const error = ref('')
const errorAction = ref('')
const notice = ref('')
const loading = ref(true)
const pending = ref('')
let active = true

function isCurrentSession() {
  return active && !!accountToken && session.value?.accessToken === accountToken
}

function setUser(updated: User) {
  user.value = updated
  displayName.value = updated.displayName
  session.value = { ...session.value!, user: updated }
}

async function showError(cause: unknown) {
  if (!isCurrentSession()) return
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession()
    await router.replace('/login')
  } else {
    error.value = cause instanceof Error ? cause.message : '操作失败，请稍后重试'
    errorAction.value = pending.value
  }
}

onMounted(async () => {
  try {
    const updated = await api<User>('/users/me')
    if (isCurrentSession()) setUser(updated)
  } catch (cause) {
    await showError(cause)
  } finally {
    if (isCurrentSession()) loading.value = false
  }
})

onUnmounted(() => {
  active = false
  passwords.currentPassword = passwords.newPassword = passwords.unbindPassword = ''
})

function begin(action: string) {
  if (loading.value || pending.value || !isCurrentSession()) return false
  pending.value = action
  error.value = notice.value = ''
  errorAction.value = ''
  return true
}

async function saveDisplayName() {
  const value = displayName.value.trim()
  fieldErrors.displayName = value.length < 1 || value.length > 64 ? '昵称须为 1–64 个字符' : ''
  if (fieldErrors.displayName || !begin('name')) return
  try {
    const updated = await api<User>('/users/me', 'PATCH', { displayName: value })
    if (!isCurrentSession()) return
    setUser(updated)
    notice.value = '昵称已更新'
  } catch (cause) {
    await showError(cause)
  } finally {
    if (isCurrentSession()) pending.value = ''
  }
}

async function changePassword() {
  fieldErrors.currentPassword = passwords.currentPassword ? '' : '请输入当前密码'
  fieldErrors.newPassword = passwords.newPassword.trim().length === 0 || passwords.newPassword.length < 8 || passwords.newPassword.length > 64
    ? '新密码须为 8–64 个字符'
    : new TextEncoder().encode(passwords.newPassword).length > 72 ? '新密码的 UTF-8 编码不能超过 72 字节，请减少汉字或表情' : ''
  if (fieldErrors.currentPassword || fieldErrors.newPassword || !begin('password')) return
  try {
    await api<User>('/users/me', 'PATCH', {
      currentPassword: passwords.currentPassword,
      newPassword: passwords.newPassword,
    })
    if (!isCurrentSession()) return
    clearSession()
    ElMessage.success('密码已修改，请重新登录')
    await router.replace('/login')
  } catch (cause) {
    await showError(cause)
  } finally {
    passwords.currentPassword = passwords.newPassword = ''
    if (isCurrentSession()) pending.value = ''
  }
}

async function unbindWechat() {
  fieldErrors.unbindPassword = passwords.unbindPassword ? '' : '请输入当前密码以确认解绑'
  if (fieldErrors.unbindPassword || !begin('unbind')) return
  try {
    await api<void>('/users/me/wechat-binding', 'DELETE', { currentPassword: passwords.unbindPassword })
    if (!isCurrentSession()) return
    setUser({ ...user.value!, hasWechatBinding: false })
    notice.value = '微信已解绑，小程序需要重新使用账号密码登录'
  } catch (cause) {
    await showError(cause)
  } finally {
    passwords.unbindPassword = ''
    if (isCurrentSession()) pending.value = ''
  }
}

async function logout() {
  if (!begin('logout')) return
  try {
    await api<void>('/auth/sessions/current', 'DELETE')
    if (!isCurrentSession()) return
    clearSession()
    await router.replace('/login')
  } catch (cause) {
    await showError(cause)
  } finally {
    if (isCurrentSession()) pending.value = ''
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
      <p>管理个人资料、账号密码与微信绑定。</p>
    </div>

    <section class="profile-card" aria-labelledby="profile-title" :aria-busy="loading">
      <el-alert v-if="error && !errorAction" class="form-error" :title="error" type="error" show-icon :closable="false" role="alert" />
      <el-alert v-if="notice" class="form-error" :title="notice" type="success" show-icon :closable="false" role="status" />
      <p v-if="loading" class="account-help">正在读取本人信息…</p>
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

        <section class="account-section" aria-labelledby="name-title">
          <h3 id="name-title">个人资料</h3>
          <el-alert v-if="error && errorAction === 'name'" class="form-error" :title="error" type="error" :closable="false" role="alert" />
          <el-form label-position="top" :disabled="loading || !!pending" @submit.prevent="saveDisplayName">
            <el-form-item label="昵称" :error="fieldErrors.displayName">
              <el-input v-model="displayName" :maxlength="64" autocomplete="nickname" />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="pending === 'name'">保存昵称</el-button>
          </el-form>
        </section>

        <section class="account-section" aria-labelledby="password-title">
          <h3 id="password-title">修改密码</h3>
          <p class="account-help">修改后，Web 和小程序中的所有登录会话都会失效。</p>
          <el-alert v-if="error && errorAction === 'password'" class="form-error" :title="error" type="error" :closable="false" role="alert" />
          <el-form label-position="top" :disabled="loading || !!pending" @submit.prevent="changePassword">
            <el-form-item label="当前密码" :error="fieldErrors.currentPassword">
              <el-input v-model="passwords.currentPassword" type="password" autocomplete="current-password" show-password />
            </el-form-item>
            <el-form-item label="新密码" :error="fieldErrors.newPassword">
              <el-input v-model="passwords.newPassword" type="password" autocomplete="new-password" show-password :maxlength="64" placeholder="8–64 个字符，UTF-8 编码不超过 72 字节" />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="pending === 'password'">修改密码并重新登录</el-button>
          </el-form>
        </section>

        <section class="account-section" aria-labelledby="wechat-title">
          <h3 id="wechat-title">微信绑定 <el-tag :type="user.hasWechatBinding ? 'success' : 'info'" effect="plain">{{ user.hasWechatBinding ? '已绑定' : '未绑定' }}</el-tag></h3>
          <el-alert v-if="error && errorAction === 'unbind'" class="form-error" :title="error" type="error" :closable="false" role="alert" />
          <template v-if="user.hasWechatBinding">
            <p class="account-help">解绑后，小程序需重新登录。当前 Web 登录会保留。</p>
            <el-form label-position="top" :disabled="loading || !!pending" @submit.prevent="unbindWechat">
              <el-form-item label="当前密码" :error="fieldErrors.unbindPassword">
                <el-input v-model="passwords.unbindPassword" type="password" autocomplete="current-password" show-password placeholder="输入当前密码确认解绑" />
              </el-form-item>
              <el-button native-type="submit" :loading="pending === 'unbind'">确认解绑微信</el-button>
            </el-form>
          </template>
          <p v-else class="account-help">在微信小程序中使用此账号密码登录后，前往「我的」绑定微信，即可使用微信登录。</p>
        </section>

        <el-alert v-if="error && errorAction === 'logout'" class="form-error" :title="error" type="error" :closable="false" role="alert" />
        <div class="profile-actions">
          <p><router-link to="/materials">查看我的资料 →</router-link><br />刷新页面后需要重新登录。</p>
          <el-button :loading="pending === 'logout'" :disabled="loading || !!pending" @click="logout">退出登录</el-button>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.account-section { padding: 24px 0; border-top: 1px solid #edf0f5; }
.account-section h3 { display: flex; align-items: center; gap: 12px; margin: 0 0 16px; font-size: 17px; }
.account-help { margin: 0 0 18px; color: #68758a; font-size: 13px; line-height: 1.7; }
.account-section .el-form { max-width: 460px; }
</style>
