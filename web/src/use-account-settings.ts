import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, ApiRequestError } from './api'
import { clearSession, session, type User } from './auth'

export function useAccountSettings() {
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
  type Setting = 'name' | 'password' | 'wechat'
  const activeSetting = ref<Setting | null>(null)
  const settingTitles = { name: '修改昵称', password: '修改密码', wechat: '微信绑定' }
  const settingTitle = computed(() => activeSetting.value ? settingTitles[activeSetting.value] : '账号设置')
  let active = true

  function openSettings(setting: Setting) {
    displayName.value = user.value!.displayName
    passwords.currentPassword = passwords.newPassword = passwords.unbindPassword = ''
    Object.assign(fieldErrors, { displayName: '', currentPassword: '', newPassword: '', unbindPassword: '' })
    error.value = errorAction.value = ''
    activeSetting.value = setting
  }

  function closeSettings() {
    if (pending.value) return
    activeSetting.value = null
    passwords.currentPassword = passwords.newPassword = passwords.unbindPassword = ''
  }

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
      activeSetting.value = null
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
      activeSetting.value = null
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
  return { user, displayName, passwords, fieldErrors, error, errorAction, notice, loading, pending, activeSetting, settingTitle, openSettings, closeSettings, saveDisplayName, changePassword, unbindWechat, logout, formatDate }
}
