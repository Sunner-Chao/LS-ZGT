<template>
  <div>
    <slot v-if="!errored"></slot>
    <div v-else class="error-boundary-fallback">
      <div style="color: #fff; background: #922; padding:8px; border-radius:6px; font-size:12px;">
        渲染失败（回退为纯文本）。
        <div style="margin-top:6px; white-space:pre-wrap; font-size:11px; color:#fff">{{ fallbackText }}</div>
      </div>
    </div>
  </div>
</template>

<script>
import { defineComponent } from 'vue'
export default defineComponent({
  name: 'ErrorBoundary',
  props: {
    fallbackText: { type: String, default: '' }
  },
  data() {
    return { errored: false }
  },
  errorCaptured(err, instance, info) {
    try {
      this.errored = true
      const stack = (err && err.stack) ? err.stack : String(err)
      const payload = { time: new Date().toISOString(), message: String(err), stack, info }
      try { localStorage.setItem('luanshu_last_error', JSON.stringify(payload)) } catch (e) {}
      try { const dbg = (window && window.__luanshu_debug) ? window.__luanshu_debug : null; if (dbg && dbg.update) dbg.update({ lastError: stack, info }) } catch (e) {}
      console.error('[ErrorBoundary] captured', info, err)
    } catch (e) {}
    // 阻止错误继续冒泡到父组件，这样一个消息的渲染失败不会导致整个页面崩溃
    return false
  }
})
</script>

<style scoped>
.error-boundary-fallback { display: block; }
</style>
