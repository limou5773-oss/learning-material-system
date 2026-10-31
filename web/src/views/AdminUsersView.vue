<script setup lang="ts">
import { ElDialog } from 'element-plus'
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
    </section>
    <el-dialog :model-value="!!selected" title="设置账号状态" width="560px" class="workspace-dialog" align-center append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="cancelEdit">
      <section v-if="selected" class="admin-editor editor-panel" aria-label="账号状态设置">
        <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
        <p>账号：{{ selected.username }}（{{ selected.displayName }}） · 当前状态：{{ selected.status === 'ENABLED' ? '启用' : '停用' }}</p>
        <label for="admin-target-status">目标状态</label>
        <select id="admin-target-status" v-model="targetStatus" :disabled="busy"><option value="ENABLED">启用</option><option value="DISABLED" :disabled="selected.id === ownerId">停用</option></select>
        <p v-if="selected.id === ownerId" class="field-help">不能停用当前登录账号。</p>
        <label for="admin-status-reason">操作原因（必填）</label>
        <textarea id="admin-status-reason" v-model="reason" :disabled="busy" maxlength="1000" rows="4" aria-describedby="admin-reason-help"></textarea>
        <p id="admin-reason-help" class="field-help">原因将记入状态审计。停用后该账号全部登录会话失效，重新启用后须重新登录。</p>
        <div class="editor-actions"><span>{{ reason.length }} / 1000 字{{ dirty ? ' · 未保存' : '' }}</span><div class="admin-actions"><button type="button" :disabled="busy" @click="cancelEdit">取消</button><button type="button" :disabled="busy" @click="submit">确认保存状态</button></div></div>
      </section>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin-bottom: 24px; }
.admin-filters label { display: grid; gap: 12px; color: #4d637f; font-size: 14px; font-weight: 500; }
.admin-filters input, .admin-filters select { max-width: 100%; min-height: 40px; padding: 9px 12px; border: 1px solid #e1e9f3; border-radius: var(--r-md); font: inherit; font-size: 14px; background: #fff; }
.admin-filters input:focus, .admin-filters select:focus { border-color: var(--brand); box-shadow: 0 0 0 3px rgba(44, 113, 228, .12); outline: none; }
.admin-filters button, .admin-table button { min-height: 40px; padding: 8px 14px; border: 1px solid var(--line-strong); border-radius: var(--r-md); color: #47617f; background: #fff; font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; transition: background .16s, border-color .16s, color .16s; }
.admin-filters button:hover:not(:disabled), .admin-table button:hover:not(:disabled) { border-color: #bcd3f1; background: #f4f8ff; color: var(--brand-hover); }
.admin-filters :disabled, .admin-table :disabled { opacity: .55; cursor: default; }
.admin-filters :focus-visible, .admin-table-wrap:focus-visible { outline: 2px solid var(--brand-hover); outline-offset: 2px; }
.admin-table-wrap { overflow-x: auto; border: 1px solid var(--line); border-radius: var(--r-md); }
.admin-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 14px; text-align: left; }
.admin-table th, .admin-table td { padding: 12px 14px; border-bottom: 1px solid var(--line); white-space: nowrap; height: 52px; }
.admin-table thead th { background: #f7f9fd; color: #4d637f; font-size: 14px; font-weight: 600; height: 52px; }
.admin-table thead th:first-child { border-radius: var(--r-md) 0 0 0; height: 52px; }
.admin-table thead th:last-child { border-radius: 0 var(--r-md) 0 0; height: 52px; }
.admin-table tbody tr:last-child td { border-bottom: 0; }
.admin-table tbody tr { transition: background-color .16s; }
.admin-table tbody tr:hover { background: #f8fbff; }
.admin-table td:nth-child(3) { min-width: 120px; max-width: 220px; white-space: normal; overflow-wrap: anywhere; height: 52px; }
.admin-table button { min-height: 40px; padding: 6px 12px; font-size: 14px; color: var(--brand-hover); border-color: #d9e6f6; }
.admin-notice { padding: 10px 14px; border-radius: var(--r-md); background: #eef8f2; color: #226e4c; font-size: 12.5px; }
.admin-editor { margin: 0; }
.admin-editor h2 { margin-top: 0; font-size: 17px; }
.admin-editor p { overflow-wrap: anywhere; line-height: 1.7; }
.admin-editor .field-help { color: #74869e; font-size: 12px; }
.admin-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.admin-actions button { min-height: 40px; padding: 8px 16px; border: 1px solid var(--line-strong); border-radius: var(--r-md); background: #fff; color: #47617f; font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; }
.admin-actions button:last-child { border-color: var(--brand); background: var(--brand); color: #fff; }
@media (max-width: 600px) { .admin-editor .editor-actions { align-items: stretch; flex-direction: column; } .admin-actions button { flex: 1; } }
</style>
