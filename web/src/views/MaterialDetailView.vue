<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiRequestError, materialFileBlob } from '../api'
import { clearSession, session } from '../auth'
import type { Material } from '../materials'
import OwnerMaterialReview from '../components/OwnerMaterialReview.vue'
import MaterialMetaDialog from '../components/MaterialMetaDialog.vue'

const route = useRoute()
const router = useRouter()
const material = ref<Material | null>(null)
const loading = ref(false)
const error = ref('')
const actionError = ref('')
const busy = ref(false)
const favoriteBusy = ref(false)
const fileUrl = ref('')
const previewError = ref('')
const metaOpen = ref(false)
const ownerId = session.value?.user.id, ownerToken = session.value?.accessToken, routePath = route.fullPath
let active = true, sequence = 0
const current = () => active && session.value?.user.id === ownerId && session.value?.accessToken === ownerToken && route.fullPath === routePath
function invalidate() { active = false; sequence++; clearPreview(); material.value = null; error.value = ''; actionError.value = ''; previewError.value = ''; metaOpen.value = false }
watch(() => [session.value?.user.id, session.value?.accessToken, route.fullPath], invalidate, { flush: 'sync' })

function clearPreview() {
  if (fileUrl.value) URL.revokeObjectURL(fileUrl.value)
  fileUrl.value = ''
}

async function load() {
  if (!current() || loading.value || busy.value) return
  const request = ++sequence
  loading.value = true
  error.value = ''
  actionError.value = ''
  previewError.value = ''
  clearPreview()
  material.value = null
  try {
    const value = await api<Material>(`/materials/${encodeURIComponent(String(route.params.id))}`)
    if (!current() || request !== sequence) return
    material.value = value
    if (material.value.type === 'IMAGE' || material.value.type === 'PDF') {
      try { const blob = await materialFileBlob(value.id); if (current() && request === sequence) fileUrl.value = URL.createObjectURL(blob) }
      catch (cause) {
        if (!current() || request !== sequence) return
        if (cause instanceof ApiRequestError && cause.status === 401) {
          clearSession()
          await router.replace({ name: 'login', query: { redirect: route.fullPath } })
        } else previewError.value = cause instanceof Error ? cause.message : '文件预览失败'
      }
    }
  }
  catch (cause) {
    if (!current() || request !== sequence) return
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession()
      await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else { error.value = cause instanceof Error ? cause.message : '资料加载失败' }
  } finally { if (current() && request === sequence) loading.value = false }
}

onMounted(load)
onUnmounted(invalidate)

function goBack() {
  if (window.history.state?.back) router.back()
  else router.push('/materials')
}

function scrollToReview() {
  document.getElementById('owner-review-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function changeArchive() {
  if (!material.value || busy.value) return
  const nextStatus = material.value.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED'
  busy.value = true; actionError.value = ''
  try {
    const updated = await api<Material>(`/materials/${material.value.id}`, 'PATCH', {
      version: material.value.version, status: nextStatus,
    })
    if (!current()) return
    material.value = updated
    ElMessage.success(nextStatus === 'ARCHIVED' ? '已归档。可把列表筛选切到「已归档」找回这份资料。' : '已恢复使用。')
  } catch (cause) { await handleActionError(cause, '状态更新失败') }
  finally { busy.value = false }
}

async function toggleFavorite() {
  if (!material.value || favoriteBusy.value) return
  favoriteBusy.value = true; actionError.value = ''
  try {
    await api<void>(`/favorites/MATERIAL/${material.value.id}`, material.value.favorite ? 'DELETE' : 'PUT')
    if (current() && material.value) {
      material.value.favorite = !material.value.favorite
      ElMessage.success(material.value.favorite ? '已加入收藏。' : '已取消收藏。')
    }
  } catch (cause) { await handleActionError(cause, '收藏操作失败') }
  finally { favoriteBusy.value = false }
}

async function remove() {
  if (!material.value || busy.value) return
  try {
    await ElMessageBox.confirm(
      '删除后这份资料将从列表中移除，由它生成的卡片会保留。此操作不可撤销。',
      '删除资料',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
    )
  } catch { return }
  busy.value = true; actionError.value = ''
  try {
    await api<void>(`/materials/${material.value.id}?version=${material.value.version}`, 'DELETE')
    if (current()) await router.replace('/materials')
  } catch (cause) { await handleActionError(cause, '删除失败') }
  finally { busy.value = false }
}

function applyUpdated(value: Material) {
  if (current()) material.value = value
}

async function handleActionError(cause: unknown, fallback: string) {
  if (!current()) return
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession()
    await router.replace({ name: 'login', query: { redirect: route.fullPath } })
  } else actionError.value = cause instanceof Error ? cause.message : fallback
}
</script>

<template>
  <div class="materials-layout narrow">
    <a href="/materials" class="back-link" @click.prevent="goBack">← 返回资料</a>
    <div v-if="loading" class="loading-panel"><el-skeleton :rows="6" animated /></div>
    <section v-if="error" class="materials-panel">
      <el-alert type="error" show-icon :closable="false" role="alert">
        <template #title>{{ error }} <el-button link type="primary" @click="load">重试</el-button></template>
      </el-alert>
    </section>
    <article v-if="material" class="materials-panel detail-panel">
      <div class="detail-badge-row">
        <el-tag size="small" type="primary" effect="plain">{{ material.type === 'IMAGE' ? '图片资料' : material.type === 'PDF' ? 'PDF 资料' : '文字笔记' }}</el-tag>
        <el-tag size="small" type="info" effect="plain">版本 {{ material.version }}</el-tag>
        <el-tag v-if="material.status === 'ARCHIVED'" size="small" type="warning" effect="plain">已归档</el-tag>
        <el-tag v-if="material.accessState === 'RESTRICTED'" size="small" type="danger" effect="plain">AI 整理受限</el-tag>
        <el-tag v-if="material.favorite" size="small" type="success" effect="plain">已收藏</el-tag>
      </div>
      <h1>{{ material.title }}</h1>
      <div class="detail-meta"><span>{{ material.subject || '未分类' }}</span><el-tag v-for="tag in material.tags" :key="tag.id" size="small" type="info" effect="plain">#{{ tag.name }}</el-tag></div>
      <p v-if="material.type === 'NOTE'" class="detail-text">{{ material.noteText }}</p>
      <div v-else-if="material.type === 'IMAGE'" class="image-preview">
        <img v-if="fileUrl" :src="fileUrl" :alt="material.title" />
        <el-alert v-else-if="previewError" type="error" show-icon :closable="false" role="alert">
          <template #title>{{ previewError }} <el-button link type="primary" @click="load">重试</el-button></template>
        </el-alert>
        <el-skeleton v-else :rows="4" animated />
      </div>
      <div v-else-if="material.type === 'PDF'" class="pdf-preview">
        <p>{{ material.pageCount ?? '?' }} 页 · {{ material.fileSize == null ? '大小未知' : `${(material.fileSize / 1024 / 1024).toFixed(2)} MiB` }}</p>
        <iframe v-if="fileUrl" :src="fileUrl" :title="`${material.title} PDF 预览`" class="pdf-frame" />
        <el-alert v-else-if="previewError" type="error" show-icon :closable="false" role="alert">
          <template #title>{{ previewError }} <el-button link type="primary" @click="load">重试</el-button></template>
        </el-alert>
        <el-skeleton v-else :rows="4" animated />
        <el-link v-if="fileUrl" :href="fileUrl" :download="`${material.title}.pdf`" type="primary" class="pdf-download">下载 PDF</el-link>
      </div>
      <el-alert v-if="actionError" class="form-alert" type="error" show-icon :closable="false" role="alert">
        <template #title>{{ actionError }} <el-button link type="primary" @click="load">刷新版本</el-button></template>
      </el-alert>
      <el-alert v-if="material.accessState === 'RESTRICTED'" class="form-alert" type="warning" show-icon :closable="false">
        <template #title>
          资料已变更，AI 整理已暂停。请到页面底部「资料审核」重新送审，通过后自动恢复。
          <el-button link type="primary" @click="scrollToReview">去送审</el-button>
        </template>
      </el-alert>
      <div class="detail-actions">
        <div class="action-group">
          <el-tooltip v-if="material.accessState === 'RESTRICTED'" placement="top" content="资料内容已变更，需重新送审通过后才能使用 AI 整理">
            <span class="restricted-tip"><el-button disabled>AI 整理已限制</el-button></span>
          </el-tooltip>
          <el-button v-else type="primary" @click="router.push(`/materials/${material.id}/organize`)">提交整理任务</el-button>
          <el-button v-if="material.type === 'NOTE'" @click="router.push(`/materials/${material.id}/edit`)">编辑正文</el-button>
          <el-button v-else @click="metaOpen = true">编辑信息</el-button>
        </div>
        <div class="action-group">
          <el-button :loading="favoriteBusy" @click="toggleFavorite">{{ material.favorite ? '★ 取消收藏' : '☆ 收藏' }}</el-button>
          <el-button :disabled="busy" @click="changeArchive">{{ material.status === 'ARCHIVED' ? '恢复使用' : '归档' }}</el-button>
        </div>
        <div class="action-group action-danger">
          <el-button type="danger" plain :disabled="busy" @click="remove">删除</el-button>
        </div>
      </div>
      <OwnerMaterialReview :material="material" @updated="applyUpdated" />
      <MaterialMetaDialog v-model="metaOpen" :material="material" @updated="applyUpdated" />
    </article>
  </div>
</template>

<style scoped>
.detail-badge-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.detail-panel h1 { margin: 12px 0; }
.detail-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; }
.action-group { display: flex; flex-wrap: wrap; gap: 10px; }
.action-group .el-button { margin: 0; }
.action-danger { margin-left: auto; padding-left: 16px; border-left: 1px solid var(--line); }
.restricted-tip { display: inline-flex; }
.form-alert { margin-top: 16px; }
.pdf-download { margin-top: 14px; font-size: 13px; }
@media (max-width: 620px) {
  .action-danger { margin-left: 0; padding-left: 0; border-left: 0; width: 100%; padding-top: 12px; border-top: 1px solid var(--line); }
}
</style>
