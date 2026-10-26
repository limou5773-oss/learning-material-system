<script setup lang="ts">
import { useAdminUsers } from '../admin-users'

const { ownerId, keyword, status, page, results, selected, targetStatus, reason, dirty, busy, loading,
  error, actionError, notice, load, applyFilters, changePage, selectUser, cancelEdit, submit, formatDate } = useAdminUsers()
</script>

<template>
  <div class="materials-layout">
    <div class="section-heading"><div><span class="intro-eyebrow">USER ADMINISTRATION</span><h1>用户管理</h1><p>查看账号状态，记录每次启用或停用的原因。</p></div></div>
    <section class="materials-panel" :aria-busy="busy">
      <form class="admin-filters" @submit.prevent="applyFilters">
        <label>关键词<input v-model="keyword" :disabled="busy" maxlength="100" placeholder="用户名或显示名称" /></label>
        <label>账号状态<select v-model="status" :disabled="busy"><option value="">全部状态</option><option value="ENABLED">启用</option><option value="DISABLED">停用</option></select></label>
        <button type="submit" :disabled="busy">筛选</button><button type="button" :disabled="busy" @click="load">刷新</button>
      </form>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="list-state" role="status">正在读取用户…</p>
      <template v-else-if="results">
        <p v-if="!results.items.length" class="list-state">{{ page > 1 ? '本页暂无用户，请返回上一页查看。' : '暂无用户。' }}</p>
        <div v-else class="admin-table-wrap" tabindex="0" aria-label="用户列表，可横向滚动">
          <table class="admin-table">
            <thead><tr><th scope="col">ID</th><th scope="col">用户名</th><th scope="col">显示名称</th><th scope="col">角色</th><th scope="col">状态</th><th scope="col">微信绑定</th><th scope="col">创建时间</th><th scope="col">操作</th></tr></thead>
            <tbody><tr v-for="item in results.items" :key="item.id">
              <td>{{ item.id }}</td><td>{{ item.username }}</td><td>{{ item.displayName }}</td><td>{{ item.role === 'ADMIN' ? '管理员' : '普通用户' }}</td><td>{{ item.status === 'ENABLED' ? '启用' : '停用' }}</td><td>{{ item.hasWechatBinding ? '已绑定' : '未绑定' }}</td><td><time :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time></td><td><button type="button" :disabled="busy" @click="selectUser(item)">设置状态</button></td>
            </tr></tbody>
          </table>
        </div>
      </template>
      <div v-if="page > 1 || (results && results.total > results.pageSize)" class="pager">
        <button type="button" :disabled="page <= 1 || busy" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} 页<span v-if="results"> · 共 {{ results.total }} 条</span></span>
        <button type="button" :disabled="busy || !results || page * results.pageSize >= results.total" @click="changePage(page + 1)">下一页</button>
      </div>
      <section v-if="selected" class="admin-editor editor-panel" aria-labelledby="admin-status-title">
        <h2 id="admin-status-title">设置账号状态</h2>
        <p>账号：{{ selected.username }}（{{ selected.displayName }}） · 当前状态：{{ selected.status === 'ENABLED' ? '启用' : '停用' }}</p>
        <label for="admin-target-status">目标状态</label>
        <select id="admin-target-status" v-model="targetStatus" :disabled="busy"><option value="ENABLED">启用</option><option value="DISABLED" :disabled="selected.id === ownerId">停用</option></select>
        <p v-if="selected.id === ownerId" class="field-help">不能停用当前登录账号。</p>
        <label for="admin-status-reason">操作原因（必填）</label>
        <textarea id="admin-status-reason" v-model="reason" :disabled="busy" maxlength="1000" rows="4" aria-describedby="admin-reason-help"></textarea>
        <p id="admin-reason-help" class="field-help">原因将记入状态审计。停用后该账号全部登录会话失效，重新启用后须重新登录。</p>
        <div class="editor-actions"><span>{{ reason.length }} / 1000 字{{ dirty ? ' · 未保存' : '' }}</span><div class="admin-actions"><button type="button" :disabled="busy" @click="cancelEdit">取消</button><button type="button" :disabled="busy" @click="submit">确认保存状态</button></div></div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.admin-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin-bottom: 20px; }
.admin-filters label { display: grid; gap: 8px; color: #43516a; font-size: 14px; }
.admin-filters input, .admin-filters select { max-width: 100%; padding: 10px 12px; border: 1px solid #dfe6f0; border-radius: 8px; font: inherit; background: #fff; }
.admin-filters button, .admin-table button { padding: 10px 12px; border: 1px solid #dce5f2; border-radius: 8px; color: #315bc2; background: #fff; cursor: pointer; }
.admin-filters :disabled, .admin-table :disabled { opacity: .5; cursor: default; }
.admin-filters :focus-visible, .admin-table-wrap:focus-visible { outline: 2px solid #315bc2; outline-offset: 2px; }
.admin-table-wrap { overflow-x: auto; }
.admin-table { width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; }
.admin-table th, .admin-table td { padding: 14px 10px; border-bottom: 1px solid #eef1f6; white-space: nowrap; }
.admin-table th { color: #64748b; font-weight: 600; }
.admin-table td:nth-child(3) { min-width: 120px; max-width: 220px; white-space: normal; overflow-wrap: anywhere; }
.admin-notice { color: #286448; }
.admin-editor { margin-top: 28px; padding-top: 24px; border-top: 1px solid #eef1f6; }
.admin-editor h2 { margin-top: 0; font-size: 20px; }
.admin-editor p { overflow-wrap: anywhere; line-height: 1.7; }
.admin-editor .field-help { color: #64748b; font-size: 13px; }
.admin-actions { display: flex; flex-wrap: wrap; gap: 12px; }
@media (max-width: 600px) { .admin-editor .editor-actions { align-items: stretch; flex-direction: column; } .admin-actions button { flex: 1; } }
</style>
