<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Material } from '../materials'

const route = useRoute()
const router = useRouter()
const material = ref<Material | null>(null)
const editingVersion = ref(0)
const form = reactive({ title: '', subject: '', tags: '', noteText: '' })
const baseline = ref('')
const loading = ref(true)
const saving = ref(false)
const saved = ref(false)
const error = ref('')
const conflict = ref(false)
const serverCopy = ref<Material | null>(null)
const snapshot = () => JSON.stringify(form)
const draftKey = `lms.noteDraft.v1.${String(route.params.id)}`
const dirty = computed(() => !!material.value && !saved.value && snapshot() !== baseline.value)

watch(form, () => {
  if (!material.value || saved.value) return
  try {
    if (snapshot() === baseline.value) sessionStorage.removeItem(draftKey)
    else sessionStorage.setItem(draftKey, JSON.stringify({ version: editingVersion.value, form: { ...form } }))
  } catch { /* 存储不可用时仍保留页面中的输入 */ }
})

function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => !dirty.value || window.confirm('笔记有未保存的修改，确定离开吗？'))
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api<Material>(`/materials/${encodeURIComponent(String(route.params.id))}`)
    material.value = data
    editingVersion.value = data.version
    Object.assign(form, { title: data.title, subject: data.subject || '', tags: data.tags.map(tag => tag.name).join('，'), noteText: data.noteText || '' })
    baseline.value = snapshot()
    try {
      const draft = JSON.parse(sessionStorage.getItem(draftKey) || 'null')
      if (draft && Number.isInteger(draft.version) && draft.form && typeof draft.form.title === 'string'
          && typeof draft.form.subject === 'string' && typeof draft.form.tags === 'string'
          && typeof draft.form.noteText === 'string') {
        editingVersion.value = draft.version
        Object.assign(form, draft.form)
        if (draft.version !== data.version) {
          conflict.value = true
          serverCopy.value = data
          error.value = '服务器已有新版本，本地草稿已恢复。请先对照内容。'
        }
      }
    } catch { /* 无法读取草稿时继续显示服务器版本 */ }
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '笔记加载失败'
  } finally { loading.value = false }
}

async function loadServerCopy() {
  try {
    serverCopy.value = await api<Material>(`/materials/${encodeURIComponent(String(route.params.id))}`)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取服务器版本失败' }
}

async function submit() {
  if (!material.value || saving.value) return
  error.value = ''; conflict.value = false
  const tags = form.tags.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (!form.title.trim() || !form.noteText.trim()) { error.value = '请填写标题和正文'; return }
  if (tags.length > 10) { error.value = '最多添加 10 个标签'; return }
  saving.value = true
  try {
    const updated = await api<Material>(`/materials/${material.value.id}`, 'PATCH', {
      version: editingVersion.value, title: form.title.trim(), subject: form.subject.trim(), tags, noteText: form.noteText,
    })
    saved.value = true
    try { sessionStorage.removeItem(draftKey) } catch { /* 页面跳转不受存储异常影响 */ }
    await router.replace(`/materials/${updated.id}`)
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409) {
      conflict.value = true
      error.value = `这篇笔记已有新版本${cause.currentVersion ? `（版本 ${cause.currentVersion}）` : ''}。当前编辑内容仍在页面中，请先对照服务器版本。`
      await loadServerCopy()
    } else if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '保存失败，当前内容仍在页面中'
  } finally { saving.value = false }
}

function discardDraft() {
  if (!serverCopy.value || !window.confirm('确定放弃本地草稿，使用服务器版本吗？')) return
  const data = serverCopy.value
  material.value = data
  editingVersion.value = data.version
  Object.assign(form, { title: data.title, subject: data.subject || '', tags: data.tags.map(tag => tag.name).join('，'), noteText: data.noteText || '' })
  baseline.value = snapshot()
  conflict.value = false
  serverCopy.value = null
  error.value = ''
  try { sessionStorage.removeItem(draftKey) } catch { /* 仅影响草稿缓存 */ }
}
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" :to="`/materials/${route.params.id}`">← 返回笔记</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">EDIT NOTE</span><h1>编辑文字笔记</h1><p v-if="material">正在编辑版本 {{ editingVersion }}</p></div></div>
    <div v-if="loading" class="loading-panel"><el-skeleton :rows="6" animated /></div>
    <section v-else-if="material" class="materials-panel form-panel">
      <el-alert v-if="error" class="form-alert" :title="error" type="error" show-icon :closable="false" role="alert" />
      <el-alert v-if="conflict && serverCopy" class="form-alert" type="warning" :closable="false" show-icon>
        <template #title><strong>服务器版本 {{ serverCopy.version }}：{{ serverCopy.title }}</strong></template>
        <div class="server-copy">
          <p>{{ serverCopy.noteText }}</p>
          <span>本页草稿未被替换。请先对照并复制需要保留的内容。</span>
          <el-button class="discard-button" @click="discardDraft">放弃草稿，使用服务器版本</el-button>
        </div>
      </el-alert>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="标题" required><el-input v-model="form.title" maxlength="255" /></el-form-item>
        <div class="form-row-2">
          <el-form-item label="学科"><el-input v-model="form.subject" maxlength="64" /></el-form-item>
          <el-form-item label="标签"><el-input v-model="form.tags" placeholder="多个标签用逗号隔开" /></el-form-item>
        </div>
        <el-form-item label="笔记正文" required>
          <el-input v-model="form.noteText" type="textarea" :rows="13" maxlength="20000" />
        </el-form-item>
        <div class="editor-actions"><span>{{ form.noteText.length }} / 20000 字{{ dirty ? ' · 未保存' : '' }}</span><el-button type="primary" native-type="submit" :loading="saving" :disabled="conflict">{{ saving ? '正在保存…' : '保存修改' }}</el-button></div>
      </el-form>
    </section>
    <section v-else-if="error" class="materials-panel"><el-alert :title="error" type="error" show-icon :closable="false" role="alert" /><el-button class="retry-button" @click="load">重试</el-button></section>
  </div>
</template>

<style scoped>
.form-alert { margin-bottom: 18px; }
.form-alert .server-copy { margin-top: 10px; }
.form-alert .server-copy p { max-height: 240px; overflow: auto; margin: 0 0 10px; padding: 12px; border-radius: var(--r-md); background: rgba(255, 255, 255, .7); white-space: pre-wrap; overflow-wrap: anywhere; }
.form-alert .server-copy span { display: block; margin-bottom: 10px; color: var(--el-text-color-regular); font-size: 12px; }
.retry-button { margin-top: 14px; }
.editor-actions { justify-content: space-between; }
</style>
