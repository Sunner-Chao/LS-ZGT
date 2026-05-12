<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import AgentDrawer from './AgentDrawer.vue'

const drawerVisible = ref(false)

// 拖拽相关状态
const isDragging = ref(false)
const position = ref({ x: 24, y: window.innerHeight - 80 }) // 初始位置：右下角
const dragStart = ref({ x: 0, y: 0 })
const hasMoved = ref(false)

// 开始拖拽
const startDrag = (e: MouseEvent) => {
  isDragging.value = true
  hasMoved.value = false
  dragStart.value = {
    x: e.clientX - position.value.x,
    y: e.clientY - position.value.y
  }
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
  e.preventDefault()
}

// 拖拽中
const onDrag = (e: MouseEvent) => {
  if (!isDragging.value) return

  const newX = e.clientX - dragStart.value.x
  const newY = e.clientY - dragStart.value.y

  // 边界限制
  const btnSize = 56
  const margin = 10
  const maxX = window.innerWidth - btnSize - margin
  const maxY = window.innerHeight - btnSize - margin

  position.value = {
    x: Math.max(margin, Math.min(maxX, newX)),
    y: Math.max(margin, Math.min(maxY, newY))
  }

  // 检测是否有明显移动
  if (Math.abs(newX - (window.innerWidth - btnSize - 24)) > 10 ||
      Math.abs(newY - (window.innerHeight - btnSize - 24)) > 10) {
    hasMoved.value = true
  }
}

// 停止拖拽
const stopDrag = () => {
  isDragging.value = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
}

// 点击处理（区分拖拽和点击）
const handleClick = () => {
  if (!hasMoved.value) {
    drawerVisible.value = true
  }
}

// 窗口大小变化时调整位置
const handleResize = () => {
  const btnSize = 56
  const margin = 10
  const maxX = window.innerWidth - btnSize - margin
  const maxY = window.innerHeight - btnSize - margin

  position.value = {
    x: Math.max(margin, Math.min(maxX, position.value.x)),
    y: Math.max(margin, Math.min(maxY, position.value.y))
  }
}

onMounted(() => {
  // 初始化位置到右下角
  position.value = {
    x: window.innerWidth - 56 - 24,
    y: window.innerHeight - 56 - 24
  }
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
})
</script>

<template>
  <div
    class="agent-floating-container"
    :style="{ left: position.x + 'px', top: position.y + 'px' }"
  >
    <el-tooltip content="AI 助手 (可拖拽)" placement="left" :show-after="500">
      <button
        class="agent-floating-btn"
        :class="{ dragging: isDragging }"
        @mousedown="startDrag"
        @click="handleClick"
      >
        <el-icon class="agent-floating-icon"><ChatDotRound /></el-icon>
      </button>
    </el-tooltip>

    <AgentDrawer v-model="drawerVisible" />
  </div>
</template>

<style scoped>
.agent-floating-container {
  position: fixed;
  z-index: 1000;
  /* 移除固定的 bottom/right，改用动态的 left/top */
}

.agent-floating-btn {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  border: none;
  cursor: grab;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 20px rgba(59, 130, 246, 0.4);
  transition: all 0.3s ease;
  position: relative;
  user-select: none;
}

.agent-floating-btn.dragging {
  cursor: grabbing;
  transform: scale(1.1);
  box-shadow: 0 8px 32px rgba(59, 130, 246, 0.6);
}

.agent-floating-btn:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 28px rgba(59, 130, 246, 0.5);
}

.agent-floating-btn:active {
  transform: scale(0.95);
}

.agent-floating-icon {
  font-size: 28px;
  color: #ffffff;
  pointer-events: none;
}

.agent-floating-btn::after {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
  opacity: 0;
  animation: agent-pulse 2s ease-in-out infinite;
  z-index: -1;
}

@keyframes agent-pulse {
  0%, 100% {
    opacity: 0;
    transform: scale(1);
  }
  50% {
    opacity: 0.3;
    transform: scale(1.15);
  }
}
</style>
