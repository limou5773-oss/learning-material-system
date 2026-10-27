<script setup lang="ts">
import { useAdminReviews } from '../admin-reviews'
import { reviewStatus } from '../reviews'

const { status, page, results, selected, content, fileUrl, accessReason, reason, decision, loading, busy,
  dirty, actionable, error, actionError, notice, load, applyFilters, changePage, selectReview, cancelEdit, viewContent, openFile, submit } = useAdminReviews()
</script>

<template>
  <div class="materials-layout">
    <div class="section-heading"><div><span class="intro-eyebrow">CONTENT REVIEW</span><h1>内容审核</h1><p>仅处理用户主动授权的资料版本。查看正文和原文件、处置决定均留痕。</p></div></div>
    <section class="materials-panel" :aria-busy="busy">
      <form class="review-filters" @submit.prevent="applyFilters">
        <label>审核状态<select v-model="status" :disabled="busy"><option value="PENDING">待审核</option><option value="APPROVED">已通过</option><option value="REJECTED">已驳回</option><option value="">全部状态</option></select></label>
        <button type="submit" :disabled="busy">筛选</button><button type="button" :disabled="busy" @click="load">刷新</button>
      </form>
      <p v-if="notice" class="review-notice" role="status">{{ notice }}</p>
      <p v-if="actionError" class="inline-error" role="alert">{{ actionError }}</p>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="list-state" role="status">正在读取审核队列…</p>
      <template v-else-if="results">
        <p v-if="!results.items.length" class="list-state">{{ page > 1 ? '本页暂无审核记录，请返回上一页。' : '暂无审核记录。' }}</p>
        <div v-else class="review-table-wrap" tabindex="0" aria-label="审核队列，可横向滚动">
          <table class="review-table"><thead><tr><th scope="col">资料 / 版本</th><th scope="col">提交人</th><th scope="col">状态</th><th scope="col">提交原因</th><th scope="col">提交时间</th><th scope="col">操作</th></tr></thead>
            <tbody><tr v-for="item in results.items" :key="item.id"><td>{{ item.title }}<small>#{{ item.materialId }} · {{ item.type }} · 版本 {{ item.materialVersion }}{{ item.isCurrent ? '' : ' · 旧版本' }}</small></td><td>#{{ item.ownerId }}</td><td>{{ reviewStatus(item.status) }}</td><td>{{ item.reason }}</td><td><time :datetime="item.createdAt">{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time></td><td><button type="button" :disabled="busy" @click="selectReview(item)">查看元数据</button></td></tr></tbody>
          </table>
        </div>
      </template>
      <div v-if="page > 1 || (results && results.total > results.pageSize)" class="pager"><button type="button" :disabled="busy || page <= 1" @click="changePage(page - 1)">上一页</button><span>第 {{ page }} 页<span v-if="results"> · 共 {{ results.total }} 条</span></span><button type="button" :disabled="busy || !results || page * results.pageSize >= results.total" @click="changePage(page + 1)">下一页</button></div>
      <section v-if="selected" class="review-editor editor-panel" aria-labelledby="review-title">
        <h2 id="review-title">{{ selected.title }} · 版本 {{ selected.materialVersion }}</h2>
        <p>审核 #{{ selected.id }} · {{ reviewStatus(selected.status) }} · 用户主动送审 · 授权范围：此版本正文和原文件</p>
        <p v-if="selected.decisionReason">处置原因：{{ selected.decisionReason }}</p>
        <p v-if="!actionable" class="field-help">{{ selected.isCurrent ? '该记录已处置，不能再次读取正文或处置。' : '资料已更新或删除，此版本授权已失效，不能读取正文或处置。' }}</p>
        <template v-else>
          <label for="review-access-reason">查看理由（必填，记入审计）</label><textarea id="review-access-reason" v-model="accessReason" :disabled="busy" rows="3" maxlength="1000"></textarea>
          <button type="button" :disabled="busy" @click="viewContent">记录理由并查看正文</button>
          <section v-if="content" class="review-body" aria-label="已授权的当前版本正文">
            <h3>已授权正文</h3><p class="review-text">{{ content.material.noteText || content.material.summary || '此资料为原文件，请按需读取原文件。' }}</p>
            <button v-if="content.fileAccessPath" type="button" :disabled="busy" @click="openFile">读取原文件（留痕）</button>
            <img v-if="fileUrl && content.material.type === 'IMAGE'" :src="fileUrl" :alt="content.material.title" class="review-image" />
            <iframe v-if="fileUrl && content.material.type === 'PDF'" :src="fileUrl" title="审核资料 PDF 预览" class="pdf-frame" />
            <a v-if="fileUrl" :href="fileUrl" download="review-material">下载已授权原文件</a>
          </section>
          <label for="review-decision">处置决定</label><select id="review-decision" v-model="decision" :disabled="busy || !content"><option value="APPROVED">通过，恢复正常整理</option><option value="REJECTED">驳回，限制 AI 并取消未完成任务</option></select>
          <label for="review-decision-reason">处置理由（必填）</label><textarea id="review-decision-reason" v-model="reason" :disabled="busy || !content" rows="4" maxlength="1000"></textarea>
          <p class="field-help">须先在本次登录会话查看此版本正文。处置原因会通知资料本人。{{ dirty ? '当前有尚未提交的理由。' : '' }}</p>
        </template>
        <div class="review-actions"><button type="button" :disabled="busy" @click="cancelEdit">关闭</button><button v-if="actionable" type="button" :disabled="busy || !content" @click="submit">确认处置</button></div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.review-filters, .review-actions { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; }
.review-filters { margin-bottom: 20px; }.review-filters label { display: grid; gap: 8px; }
button, select { padding: 10px 12px; border: 1px solid #dce5f2; border-radius: 8px; color: #315bc2; background: #fff; font: inherit; }
button { cursor: pointer; } :disabled { opacity: .5; cursor: default; } :focus-visible { outline: 2px solid #315bc2; outline-offset: 2px; }
.review-notice { color: #286448; }.review-table-wrap { overflow-x: auto; }.review-table { width: 100%; border-collapse: collapse; text-align: left; font-size: 13px; }
th, td { padding: 14px 10px; border-bottom: 1px solid #eef1f6; vertical-align: top; } td:first-child { min-width: 180px; } td:nth-child(4) { min-width: 180px; max-width: 280px; overflow-wrap: anywhere; } small { display: block; margin-top: 8px; color: #64748b; }
.review-editor { margin-top: 28px; border-top: 1px solid #eef1f6; padding-top: 24px; }.review-editor h2 { font-size: 20px; }.review-editor p { line-height: 1.7; overflow-wrap: anywhere; }.review-body { border-left: 3px solid #dce5f2; padding-left: 16px; margin: 24px 0; }.review-text { white-space: pre-wrap; }.review-image { display: block; max-width: 100%; margin: 16px 0; }.review-body a { display: block; margin: 12px 0; }.field-help { color: #64748b; font-size: 13px; }
</style>
