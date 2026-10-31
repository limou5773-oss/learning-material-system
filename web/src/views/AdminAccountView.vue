<script setup lang="ts">
import { ElDialog } from 'element-plus'
import { useAccountSettings } from '../use-account-settings'
import UiIcon from '../components/UiIcon.vue'

const { user, displayName, passwords, fieldErrors, error, errorAction, notice, loading, pending, activeSetting,
  settingTitle, openSettings, closeSettings, saveDisplayName, changePassword, logout, formatDate } = useAccountSettings()
</script>

<template>
  <div class="materials-layout admin-account-layout">
    <div class="section-heading"><div><h1>管理员账号</h1><p>管理平台管理员的个人资料与登录安全。</p></div></div>
    <el-alert v-if="error && (!errorAction || errorAction === 'logout')" class="form-error" :title="error" type="error" show-icon :closable="false" role="alert" />
    <el-alert v-if="notice" class="form-error" :title="notice" type="success" show-icon :closable="false" role="status" />
    <p v-if="loading" class="account-help" role="status">正在读取管理员信息…</p>

    <div v-if="user" class="admin-account-grid">
      <section class="materials-panel admin-identity" aria-labelledby="admin-name" :aria-busy="loading">
        <div class="admin-avatar" aria-hidden="true">{{ user.displayName.slice(0, 1) }}</div>
        <h2 id="admin-name">{{ user.displayName }}</h2><p class="admin-username">@{{ user.username }}</p>
        <el-tag effect="plain">平台管理员</el-tag>
        <dl class="admin-details"><div><dt>用户 ID</dt><dd>{{ user.id }}</dd></div><div><dt>账号状态</dt><dd>{{ user.status === 'ENABLED' ? '正常' : '已停用' }}</dd></div><div><dt>创建时间</dt><dd>{{ formatDate(user.createdAt) }}</dd></div></dl>
        <p class="account-help identity-note">此账号用于用户管理、内容审核、任务监控与平台统计。</p>
      </section>

      <section class="materials-panel admin-settings" aria-labelledby="admin-settings-title">
        <header><h2 id="admin-settings-title">账号与安全</h2><p class="account-help">选择需要修改的项目，设置将在弹窗中打开。</p></header>
        <div class="admin-setting-row"><span class="setting-icon"><UiIcon name="user" /></span><div><h3>管理员昵称</h3><p>{{ user.displayName }}</p></div><el-button :disabled="loading || !!pending" @click="openSettings('name')">修改昵称</el-button></div>
        <div class="admin-setting-row"><span class="setting-icon"><UiIcon name="shield" /></span><div><h3>登录密码</h3><p>修改密码后，当前账号的所有登录会话都会失效。</p></div><el-button :disabled="loading || !!pending" @click="openSettings('password')">修改密码</el-button></div>
        <div class="admin-setting-row"><span class="setting-icon"><UiIcon name="user" /></span><div><h3>当前会话</h3><p>退出后需重新登录管理端。</p></div><el-button :loading="pending === 'logout'" :disabled="loading || !!pending" @click="logout">退出登录</el-button></div>
      </section>
    </div>

    <el-dialog :model-value="activeSetting !== null" :title="settingTitle" width="520px" class="workspace-dialog" align-center append-to-body :close-on-click-modal="false" :close-on-press-escape="!pending" :show-close="!pending" :before-close="closeSettings">
      <el-alert v-if="error && errorAction && errorAction !== 'logout'" class="form-error" :title="error" type="error" :closable="false" role="alert" />
      <el-form v-if="activeSetting === 'name'" class="account-form" label-position="top" :disabled="loading || !!pending" @submit.prevent="saveDisplayName">
        <el-form-item label="昵称" :error="fieldErrors.displayName"><el-input v-model="displayName" :maxlength="64" autocomplete="nickname" /></el-form-item>
        <div class="dialog-actions"><el-button :disabled="!!pending" @click="closeSettings">取消</el-button><el-button type="primary" native-type="submit" :loading="pending === 'name'">保存昵称</el-button></div>
      </el-form>
      <el-form v-else-if="activeSetting === 'password'" class="account-form" label-position="top" :disabled="loading || !!pending" @submit.prevent="changePassword">
        <p class="account-help">修改后请使用新密码重新登录。</p>
        <el-form-item label="当前密码" :error="fieldErrors.currentPassword"><el-input v-model="passwords.currentPassword" type="password" autocomplete="current-password" show-password /></el-form-item>
        <el-form-item label="新密码" :error="fieldErrors.newPassword"><el-input v-model="passwords.newPassword" type="password" autocomplete="new-password" show-password :maxlength="64" placeholder="8–64 个字符" /></el-form-item>
        <p class="account-help">新密码的 UTF-8 编码不超过 72 字节。</p>
        <div class="dialog-actions"><el-button :disabled="!!pending" @click="closeSettings">取消</el-button><el-button type="primary" native-type="submit" :loading="pending === 'password'">修改并重新登录</el-button></div>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-account-layout { width: 100%; }
.admin-account-grid { display: grid; grid-template-columns: 300px minmax(0, 1fr); align-items: start; gap: 24px; }
.admin-account-grid > section { min-width: 0; border-radius: var(--r-lg); }
.admin-avatar { display: grid; place-items: center; width: 56px; height: 56px; margin-bottom: 16px; border-radius: var(--r-lg); background: var(--brand-soft); color: var(--brand-hover); font-size: 24px; font-weight: 600; }
.admin-identity h2 { margin: 0 0 8px; font-size: 20px; overflow-wrap: anywhere; }
.admin-username { margin: 0 0 16px; color: var(--muted); font-size: 14px; overflow-wrap: anywhere; }
.admin-details { display: grid; gap: 16px; margin: 24px 0; padding-top: 20px; border-top: 1px solid var(--line); }
.admin-details > div { display: flex; justify-content: space-between; gap: 16px; }
.admin-details dt { flex-shrink: 0; color: var(--muted); font-size: 12px; }
.admin-details dd { margin: 0; color: var(--ink); font-size: 14px; text-align: right; overflow-wrap: anywhere; }
.admin-settings h2 { margin: 0 0 8px; font-size: 18px; }
.admin-setting-row { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 16px; min-height: 92px; padding: 20px 0; border-bottom: 1px solid var(--line); }
.admin-setting-row:last-child { border: 0; padding-bottom: 0; }
.setting-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: var(--r-md); color: var(--brand-hover); background: var(--brand-soft); }
.setting-icon .ui-icon { width: 18px; height: 18px; }
.admin-setting-row h3 { margin: 0; font-size: 14px; font-weight: 500; }
.admin-setting-row p { margin: 8px 0 0; color: var(--muted); font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; }
.admin-setting-row .el-button { min-width: 104px; min-height: 40px; border-radius: var(--r-md); }
.account-help { margin: 0 0 20px; color: var(--muted); font-size: 13px; line-height: 1.8; }
.identity-note { margin-bottom: 0; padding-top: 16px; border-top: 1px solid var(--line); font-size: 12px; }
.account-form :deep(.el-input__wrapper) { min-height: 44px; border-radius: var(--r-md); background: var(--surface-2); }
.dialog-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; margin-top: 24px; }
.dialog-actions .el-button { min-height: 40px; margin: 0; border-radius: var(--r-md); }
@media (max-width: 1100px) { .admin-account-grid { grid-template-columns: 1fr; }.admin-details { grid-template-columns: repeat(3, minmax(0, 1fr)); }.admin-details > div { display: block; }.admin-details dd { margin-top: 8px; text-align: left; } }
@media (max-width: 620px) { .admin-details { grid-template-columns: 1fr; }.admin-details > div { display: flex; }.admin-details dd { margin-top: 0; text-align: right; }.admin-setting-row { grid-template-columns: 36px minmax(0, 1fr); gap: 12px; }.admin-setting-row .el-button { grid-column: 2; justify-self: start; } }
</style>
