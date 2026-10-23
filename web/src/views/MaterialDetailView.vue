<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError, materialFileBlob } from '../api'
import { clearSession } from '../auth'
import type { Material } from '../materials'

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

function clearPreview() {
  if (fileUrl.value) URL.revokeObjectURL(fileUrl.value)
  fileUrl.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  actionError.value = ''
  previewError.value = ''
  clearPreview()
  material.value = null
  try {
    material.value = await api<Material>(`/materials/${encodeURIComponent(String(route.params.id))}`)
    if (material.value.type === 'IMAGE' || material.value.type === 'PDF') {
      try { fileUrl.value = URL.createObjectURL(await materialFileBlob(material.value.id)) }
      catch (cause) {
        if (cause instanceof ApiRequestError && cause.status === 401) {
          clearSession()
          await router.replace({ name: 'login', query: { redirect: route.fullPath } })
        } else previewError.value = cause instanceof Error ? cause.message : '文件预览失败'
      }
    }
  }
  catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession()
      await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else { error.value = cause instanceof Error ? cause.message : '资料加载失败' }
  } finally { loading.value = false }
}

onMounted(load)
onUnmounted(clearPreview)

async function changeArchive() {
  if (!material.value || busy.value) return
  busy.value = true; actionError.value = ''
  try {
    material.value = await api<Material>(`/materials/${material.value.id}`, 'PATCH', {
      version: material.value.version, status: material.value.status === 'ARCHIVED' ? 'ACTIVE' : 'ARCHIVED',
    })
  } catch (cause) { await handleActionError(cause, '状态更新失败') }
  finally { busy.value = false }
}

async function toggleFavorite() {
  if (!material.value || favoriteBusy.value) return
  favoriteBusy.value = true; actionError.value = ''
  try {
    await api<void>(`/favorites/MATERIAL/${material.value.id}`, material.value.favorite ? 'DELETE' : 'PUT')
    material.value.favorite = !material.value.favorite
  } catch (cause) { await handleActionError(cause, '收藏操作失败') }
  finally { favoriteBusy.value = false }
}

async function remove() {
  if (!material.value || busy.value) return
  if (!window.confirm('确定删除这份资料？删除后将无法在资料列表中查看。')) return
  busy.value = true; actionError.value = ''
  try {
    await api<void>(`/materials/${material.value.id}?version=${material.value.version}`, 'DELETE')
    await router.replace('/materials')
  } catch (cause) { await handleActionError(cause, '删除失败') }
  finally { busy.value = false }
}

async function handleActionError(cause: unknown, fallback: string) {
  if (cause instanceof ApiRequestError && cause.status === 401) {
    clearSession()
    await router.replace({ name: 'login', query: { redirect: route.fullPath } })
  } else actionError.value = cause instanceof Error ? cause.message : fallback
}
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" to="/materials">← 返回资料</router-link>
    <p v-if="loading" class="list-state">正在加载资料…</p>
    <section v-if="error" class="materials-panel"><p class="inline-error" role="alert">{{ error }}</p><button class="retry-button" @click="load">重试</button></section>
    <article v-if="material" class="materials-panel detail-panel">
      <span class="note-badge">{{ material.type === 'IMAGE' ? '图片资料' : material.type === 'PDF' ? 'PDF 资料' : '文字笔记' }} · 版本 {{ material.version }}{{ material.status === 'ARCHIVED' ? ' · 已归档' : '' }}</span>
      <h1>{{ material.title }}</h1>
      <div class="detail-meta"><span>{{ material.subject || '未分类' }}</span><span v-for="tag in material.tags" :key="tag.id" class="mini-tag">#{{ tag.name }}</span></div>
      <p v-if="material.type === 'NOTE'" class="detail-text">{{ material.noteText }}</p>
      <div v-else-if="material.type === 'IMAGE'" class="image-preview">
        <img v-if="fileUrl" :src="fileUrl" :alt="material.title" />
        <p v-else-if="previewError" class="inline-error" role="alert">{{ previewError }} <button type="button" @click="load">重试</button></p>
        <p v-else class="list-state">正在读取图片…</p>
      </div>
      <div v-else-if="material.type === 'PDF'" class="pdf-preview">
        <p>{{ material.pageCount ?? '?' }} 页 · {{ material.fileSize == null ? '大小未知' : `${(material.fileSize / 1024 / 1024).toFixed(2)} MiB` }}</p>
        <iframe v-if="fileUrl" :src="fileUrl" :title="`${material.title} PDF 预览`" class="pdf-frame" />
        <p v-else-if="previewError" class="inline-error" role="alert">{{ previewError }} <button type="button" @click="load">重试</button></p>
        <p v-else class="list-state">正在读取 PDF…</p>
        <a v-if="fileUrl" :href="fileUrl" :download="`${material.title}.pdf`" class="pdf-download">下载 PDF</a>
      </div>
      <p v-if="actionError" class="inline-error" role="alert">{{ actionError }} <button type="button" @click="load">刷新版本</button></p>
      <div class="detail-actions">
        <router-link :to="`/materials/${material.id}/organize`">提交整理任务</router-link>
        <router-link v-if="material.type === 'NOTE'" :to="`/materials/${material.id}/edit`">编辑笔记</router-link>
        <button type="button" :disabled="favoriteBusy" @click="toggleFavorite">{{ material.favorite ? '★ 取消收藏' : '☆ 收藏' }}</button>
        <button type="button" :disabled="busy" @click="changeArchive">{{ material.status === 'ARCHIVED' ? '恢复使用' : '归档' }}</button>
        <button type="button" class="danger-action" :disabled="busy" @click="remove">删除</button>
      </div>
    </article>
  </div>
</template>
