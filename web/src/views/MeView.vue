<script setup lang="ts">
import { ElDialog } from 'element-plus'
import { useAccountSettings } from '../use-account-settings'
import UiIcon from '../components/UiIcon.vue'

const { user, displayName, passwords, fieldErrors, error, errorAction, notice, loading, pending, activeSetting,
  settingTitle, openSettings, closeSettings, saveDisplayName, changePassword, unbindWechat, logout, formatDate } = useAccountSettings()
const quickLinks = [
  { to: '/materials', label: '我的资料', icon: 'folder' },
  { to: '/cards', label: '知识卡片', icon: 'cards' },
  { to: '/ai-tasks', label: '整理任务', icon: 'tasks' },
  { to: '/notifications', label: '消息通知', icon: 'bell' },
] as const
</script>

<template>
  <div class="profile-layout account-page">
    <div class="profile-heading"><h1>个人中心</h1><p>管理个人资料、登录密码与微信绑定。</p></div>
    <el-alert v-if="error && (!errorAction || errorAction === 'logout')" class="form-error" :title="error" type="error" show-icon :closable="false" role="alert" />
    <el-alert v-if="notice" class="form-error" :title="notice" type="success" show-icon :closable="false" role="status" />
    <p v-if="loading" class="account-help" role="status">正在读取本人信息…</p>

    <template v-if="user">
      <section class="materials-panel account-overview" aria-labelledby="profile-title" :aria-busy="loading">
        <div class="account-identity">
          <div class="account-avatar" aria-hidden="true">{{ user.displayName.slice(0, 1) }}</div>
          <div class="account-name"><h2 id="profile-title">{{ user.displayName }}</h2><p>@{{ user.username }}</p></div>
          <el-tag :type="user.status === 'ENABLED' ? 'success' : 'info'" effect="plain">{{ user.status === 'ENABLED' ? '账号正常' : '账号已停用' }}</el-tag>
        </div>
        <dl class="account-metadata">
          <div><dt>用户 ID</dt><dd>{{ user.id }}</dd></div>
          <div><dt>账号类型</dt><dd>普通用户</dd></div>
          <div><dt>微信状态</dt><dd>{{ user.hasWechatBinding ? '已绑定' : '未绑定' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDate(user.createdAt) }}</dd></div>
        </dl>
      </section>

      <div class="account-body">
        <section class="materials-panel account-settings" aria-labelledby="settings-title">
          <header class="settings-heading"><h2 id="settings-title">账号设置</h2><p>选择需要修改的项目。</p></header>
          <div class="setting-row">
            <span class="setting-icon" aria-hidden="true"><UiIcon name="user" /></span>
            <div class="setting-copy"><h3>个人昵称</h3><p>{{ user.displayName }}</p></div>
            <el-button :disabled="loading || !!pending" @click="openSettings('name')">修改昵称</el-button>
          </div>
          <div class="setting-row">
            <span class="setting-icon" aria-hidden="true"><UiIcon name="shield" /></span>
            <div class="setting-copy"><h3>登录密码</h3><p>修改后，Web 与小程序的所有登录会话都会失效。</p></div>
            <el-button :disabled="loading || !!pending" @click="openSettings('password')">修改密码</el-button>
          </div>
          <div class="setting-row">
            <span class="setting-icon" aria-hidden="true"><UiIcon name="graph" /></span>
            <div class="setting-copy"><h3>微信绑定</h3><p>{{ user.hasWechatBinding ? '已绑定，可在微信小程序中使用微信登录。' : '尚未绑定，可在微信小程序中完成绑定。' }}</p></div>
            <el-button :disabled="loading || !!pending" @click="openSettings('wechat')">{{ user.hasWechatBinding ? '管理绑定' : '查看绑定方式' }}</el-button>
          </div>
          <div class="setting-row session-row">
            <span class="setting-icon" aria-hidden="true"><UiIcon name="user" /></span>
            <div class="setting-copy"><h3>当前会话</h3><p>刷新页面或退出后需要重新登录。</p></div>
            <el-button :loading="pending === 'logout'" :disabled="loading || !!pending" @click="logout">退出登录</el-button>
          </div>
        </section>

        <aside class="materials-panel account-shortcuts" aria-labelledby="shortcuts-title">
          <header class="settings-heading"><h2 id="shortcuts-title">快捷入口</h2><p>常用学习功能</p></header>
          <nav class="account-links" aria-label="学习快捷入口">
            <router-link v-for="link in quickLinks" :key="link.to" :to="link.to"><UiIcon :name="link.icon" /><span>{{ link.label }}</span><UiIcon name="arrow" /></router-link>
          </nav>
        </aside>
      </div>

      <el-dialog :model-value="activeSetting !== null" :title="settingTitle" width="520px" class="workspace-dialog" align-center append-to-body :close-on-click-modal="false" :close-on-press-escape="!pending" :show-close="!pending" :before-close="closeSettings">
        <el-alert v-if="error && errorAction && errorAction !== 'logout'" class="form-error" :title="error" type="error" :closable="false" role="alert" />

        <el-form v-if="activeSetting === 'name'" class="account-form" label-position="top" :disabled="loading || !!pending" @submit.prevent="saveDisplayName">
          <el-form-item label="昵称" :error="fieldErrors.displayName"><el-input v-model="displayName" :maxlength="64" autocomplete="nickname" /></el-form-item>
          <p class="account-help">昵称用于 Web 和微信小程序中的个人资料展示。</p>
          <div class="account-form-actions"><el-button :disabled="!!pending" @click="closeSettings">取消</el-button><el-button type="primary" native-type="submit" :loading="pending === 'name'">保存昵称</el-button></div>
        </el-form>

        <el-form v-else-if="activeSetting === 'password'" class="account-form" label-position="top" :disabled="loading || !!pending" @submit.prevent="changePassword">
          <p class="account-help">修改后，Web 和小程序中的所有登录会话都会失效，请重新登录。</p>
          <el-form-item label="当前密码" :error="fieldErrors.currentPassword"><el-input v-model="passwords.currentPassword" type="password" autocomplete="current-password" show-password /></el-form-item>
          <el-form-item label="新密码" :error="fieldErrors.newPassword"><el-input v-model="passwords.newPassword" type="password" autocomplete="new-password" show-password :maxlength="64" placeholder="8–64 个字符" /></el-form-item>
          <p class="account-help">新密码的 UTF-8 编码不超过 72 字节。</p>
          <div class="account-form-actions"><el-button :disabled="!!pending" @click="closeSettings">取消</el-button><el-button type="primary" native-type="submit" :loading="pending === 'password'">修改并重新登录</el-button></div>
        </el-form>

        <template v-else-if="activeSetting === 'wechat'">
          <el-form v-if="user.hasWechatBinding" class="account-form" label-position="top" :disabled="loading || !!pending" @submit.prevent="unbindWechat">
            <p class="account-help">微信已绑定。解绑后，小程序需重新登录，当前 Web 登录会保留。</p>
            <el-form-item label="当前密码" :error="fieldErrors.unbindPassword"><el-input v-model="passwords.unbindPassword" type="password" autocomplete="current-password" show-password placeholder="输入当前密码确认解绑" /></el-form-item>
            <div class="account-form-actions"><el-button :disabled="!!pending" @click="closeSettings">取消</el-button><el-button type="primary" native-type="submit" :loading="pending === 'unbind'">确认解绑微信</el-button></div>
          </el-form>
          <div v-else class="binding-guide">
            <p class="account-help">在微信小程序中完成绑定后，即可使用微信登录。</p>
            <ol><li>打开微信小程序，使用此账号密码登录。</li><li>前往「我的」页面，点击绑定微信。</li><li>绑定成功后，Web 与小程序共用此账号资料。</li></ol>
            <div class="account-form-actions"><el-button type="primary" @click="closeSettings">知道了</el-button></div>
          </div>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<style scoped>
.account-page { width: 100%; max-width: 1100px; }
.profile-heading { margin-bottom: 24px; }
.account-page .materials-panel { padding: 24px; border-radius: var(--r-lg); }
.account-overview { margin-bottom: 24px; }
.account-identity { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; }
.account-avatar { display: grid; place-items: center; width: 52px; height: 52px; flex-shrink: 0; border-radius: var(--r-lg); background: var(--brand-soft); color: var(--brand-hover); font-size: 22px; font-weight: 600; }
.account-name { min-width: 0; }
.account-name h2 { margin: 0 0 6px; font-size: 20px; line-height: 1.4; overflow-wrap: anywhere; }
.account-name p { margin: 0; color: var(--muted); font-size: 14px; overflow-wrap: anywhere; }
.account-identity > .el-tag { margin-left: auto; }
.account-metadata { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px 24px; margin: 20px 0 0; padding-top: 20px; border-top: 1px solid var(--line); }
.account-metadata dt { margin-bottom: 8px; color: var(--muted); font-size: 12px; }
.account-metadata dd { margin: 0; color: #2b4162; font-size: 14px; font-weight: 500; line-height: 1.7; overflow-wrap: anywhere; }
.account-body { display: grid; grid-template-columns: minmax(0, 1fr) 288px; align-items: start; gap: 24px; }
.account-settings, .account-shortcuts { min-width: 0; }
.settings-heading h2 { margin: 0; color: var(--ink); font-size: 18px; line-height: 1.5; }
.settings-heading p { margin: 8px 0 0; color: var(--muted); font-size: 14px; line-height: 1.7; }
.setting-row { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 16px; min-height: 78px; padding: 16px 0; border-bottom: 1px solid var(--line); }
.setting-row:last-child { padding-bottom: 0; border-bottom: 0; }
.setting-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: var(--brand-soft); color: var(--brand-hover); }
.setting-icon .ui-icon { width: 17px; height: 17px; }
.setting-copy { min-width: 0; }
.setting-copy h3 { margin: 0; color: var(--ink); font-size: 14px; font-weight: 600; line-height: 1.6; }
.setting-copy p { margin: 6px 0 0; color: var(--muted); font-size: 14px; line-height: 1.7; overflow-wrap: anywhere; }
.setting-row .el-button { min-width: 112px; min-height: 40px; margin: 0; border-radius: var(--r-md); }
.account-links { display: grid; gap: 8px; margin-top: 16px; }
.account-links a { display: flex; align-items: center; gap: 12px; min-height: 48px; padding: 12px; border-radius: var(--r-md); color: var(--el-text-color-regular); font-size: 14px; }
.account-links a:hover { background: var(--surface-2); color: var(--brand-hover); }
.account-links .ui-icon { width: 16px; height: 16px; }
.account-links .ui-icon:last-child { margin-left: auto; width: 14px; height: 14px; }
.account-help { margin: 0 0 20px; color: var(--muted); font-size: 14px; line-height: 1.8; }
.account-form :deep(.el-input__wrapper) { min-height: 44px; border-radius: var(--r-md); background: var(--surface-2); }
.account-form-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; margin-top: 24px; }
.account-form-actions .el-button { min-height: 40px; margin: 0; border-radius: var(--r-md); }
.binding-guide ol { display: grid; gap: 12px; margin: 0; padding-left: 20px; color: var(--el-text-color-regular); font-size: 13px; line-height: 1.8; }
@media (max-width: 1100px) {
  .account-body { grid-template-columns: 1fr; }
  .account-links { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 620px) {
  .account-page .materials-panel { padding: 16px; }
  .account-identity { gap: 12px; }
  .account-name { flex: 1; }
  .account-identity > .el-tag { margin-left: 0; }
  .account-metadata { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 16px; }
  .setting-row { grid-template-columns: 36px minmax(0, 1fr); gap: 12px; }
  .setting-row .el-button { grid-column: 2; justify-self: start; }
}
</style>
