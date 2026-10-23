<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiRequestError, uploadFile } from '../api'
import { clearSession } from '../auth'
import { session } from '../auth'
import type { Material } from '../materials'
import { runUploadQueue } from '../uploadQueue'

type UploadStatus = 'ready' | 'uploading' | 'saved' | 'duplicate' | 'failed'
interface UploadItem {
  key: number
  file: File
  title: string
  status: UploadStatus
  message: string
  materialId: string
  existingId: string
}

const route = useRoute()
const router = useRouter()
const items = ref<UploadItem[]>([])
const subject = ref('')
const tagsText = ref('')
const error = ref('')
const running = ref(false)
let nextKey = 0

function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (running.value || !files.length) return
  error.value = ''
  if (files.length > 10) { error.value = '一次最多选择 10 个 PDF 文件'; return }
  items.value = files.map(file => {
    let message = ''
    if (!/\.pdf$/i.test(file.name) || !['application/pdf', 'application/x-pdf', 'application/octet-stream', ''].includes(file.type)) {
      message = '请选择 PDF 文件'
    } else if (file.size > 30 * 1024 * 1024) message = 'PDF 不能超过 30 MiB'
    else if (file.size === 0) message = '文件为空'
    return { key: ++nextKey, file, title: file.name.replace(/\.pdf$/i, ''),
      status: message ? 'failed' : 'ready', message, materialId: '', existingId: '' }
  })
}

function tags(): string[] | null {
  const values = tagsText.value.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (values.length > 10) { error.value = '最多添加 10 个标签'; return null }
  return values
}

async function upload(item: UploadItem, allowDuplicate = false, sharedTags?: string[]) {
  const selectedTags = sharedTags || tags()
  if (!selectedTags) return
  if (!item.title.trim()) { item.status = 'failed'; item.message = '请填写标题'; return }
  item.status = 'uploading'
  item.message = ''
  item.existingId = ''
  const body = new FormData()
  body.append('file', item.file)
  body.append('title', item.title.trim())
  body.append('subject', subject.value.trim())
  body.append('tagsJson', JSON.stringify(selectedTags))
  body.append('allowDuplicate', String(allowDuplicate))
  try {
    const material = await uploadFile<Material>('/materials/files', body)
    item.materialId = material.id
    item.status = 'saved'
    item.message = `上传成功 · ${material.pageCount ?? '?'} 页`
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409 && cause.existingMaterialId) {
      item.status = 'duplicate'
      item.existingId = cause.existingMaterialId
      item.message = '该 PDF 已上传过，请选择已有资料或保存副本'
    } else if (cause instanceof ApiRequestError && cause.status === 401) {
      item.status = 'failed'
      item.message = '登录已过期'
      clearSession()
      await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else {
      item.status = 'failed'
      item.message = cause instanceof Error ? cause.message : '上传失败'
    }
  }
}

async function submit() {
  if (running.value) return
  error.value = ''
  const selectedTags = tags()
  if (!selectedTags) return
  const batchTags: string[] = selectedTags
  const pending = items.value.filter(item => item.status === 'ready' || (item.status === 'failed' && item.message === '请填写标题'))
  if (!pending.length) { error.value = '没有待上传的有效 PDF'; return }
  running.value = true
  try {
    const results = await runUploadQueue(pending, async item => {
      if (session.value) await upload(item, false, batchTags)
    })
    results.forEach((result, index) => {
      if (result.status === 'rejected') {
        pending[index]!.status = 'failed'
        pending[index]!.message = '上传失败，请重试此文件'
      }
    })
  }
  finally { running.value = false }
}

async function retry(item: UploadItem, allowDuplicate = false) {
  if (running.value || item.status === 'uploading') return
  if (item.status === 'failed' && ['请选择 PDF 文件', 'PDF 不能超过 30 MiB', '文件为空'].includes(item.message)) return
  running.value = true
  try { await upload(item, allowDuplicate) }
  finally { running.value = false }
}
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" to="/materials">← 返回资料</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">PDF LIBRARY</span><h1>批量上传 PDF</h1><p>一次最多 10 个，每个不超过 30 MiB、100 页；同时上传 2 个。</p></div></div>
    <section class="materials-panel editor-panel">
      <label for="pdf-files">选择 PDF 文件</label>
      <input id="pdf-files" type="file" accept="application/pdf,.pdf" multiple :disabled="running" @change="choose" />
      <label for="pdf-subject">学科（应用于本批次）</label><input id="pdf-subject" v-model="subject" maxlength="64" placeholder="可选" :disabled="running" />
      <label for="pdf-tags">标签（应用于本批次）</label><input id="pdf-tags" v-model="tagsText" placeholder="可选，多个标签用逗号隔开" :disabled="running" />
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div v-if="items.length" class="pdf-queue" aria-live="polite">
        <div v-for="item in items" :key="item.key" class="pdf-queue-item">
          <div class="pdf-queue-top"><strong>{{ item.file.name }}</strong><span>{{ (item.file.size / 1024 / 1024).toFixed(2) }} MiB</span></div>
          <label :for="`pdf-title-${item.key}`">标题</label><input :id="`pdf-title-${item.key}`" v-model="item.title" maxlength="255" :disabled="running || item.status === 'saved'" />
          <p class="pdf-queue-status" :class="{ 'inline-error': item.status === 'failed' || item.status === 'duplicate' }">{{ item.message || (item.status === 'uploading' ? '正在上传…' : '等待上传') }}</p>
          <div class="duplicate-actions" v-if="item.status === 'duplicate'">
            <router-link :to="`/materials/${item.existingId}`">查看已有资料</router-link>
            <button type="button" :disabled="running" @click="retry(item, true)">确认保存副本</button>
          </div>
          <div class="duplicate-actions" v-else-if="item.status === 'saved'"><router-link :to="`/materials/${item.materialId}`">查看资料</router-link></div>
          <div class="duplicate-actions" v-else-if="item.status === 'failed' && !['请选择 PDF 文件', 'PDF 不能超过 30 MiB', '文件为空'].includes(item.message)"><button type="button" :disabled="running" @click="retry(item)">重试此文件</button></div>
        </div>
      </div>
      <div class="editor-actions"><span>已选择 {{ items.length }} 个文件</span><button type="button" :disabled="running || !items.some(item => item.status === 'ready')" @click="submit">{{ running ? '正在上传…' : '开始上传' }}</button></div>
    </section>
  </div>
</template>
