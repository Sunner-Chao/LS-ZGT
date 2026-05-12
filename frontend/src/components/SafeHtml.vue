<template>
  <div ref="container" class="safe-html-container"></div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'

const props = defineProps<{
  /** 已转换好的 HTML 字符串，或 null */
  html: string | null
  /**
   * 是否将 html 当作纯文本渲染（换行转 <br>，不做 innerHTML）。
   * 默认为 false，即按 HTML 渲染（搭配 v-html）。
   */
  asText?: boolean
}>()

const container = ref<HTMLElement | null>(null)

function setHtml(el: HTMLElement | null, raw: string | null) {
  if (!el) return
  if (props.asText ?? false) {
    // 纯文本模式：保留换行，追加内容
    const text = (raw || '').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&amp;/g, '&').replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ')
    el.textContent = text
  } else {
    // HTML 模式：直接渲染，preserve whitespace
    el.innerHTML = raw || ''
  }
}

onMounted(() => setHtml(container.value, props.html))
watch(() => props.html, (v) => setHtml(container.value, v))
</script>

<style scoped>
.safe-html-container {
  word-break: break-word;
  white-space: pre-wrap;   /* 保留源代码中的换行与空格 */
}
</style>