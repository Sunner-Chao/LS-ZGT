<script setup lang="ts">
import WelcomeItem from './WelcomeItem.vue'
import ToolingIcon from './icons/IconTooling.vue'
import EcosystemIcon from './icons/IconEcosystem.vue'
import CommunityIcon from './icons/IconCommunity.vue'
import SupportIcon from './icons/IconSupport.vue'

const openReadmeInEditor = () => fetch('/__open-in-editor?file=README.md')
</script>

<template>
  <div class="welcome-container">
    <h2>欢迎使用知识库助手</h2>
    <p>选择左侧知识库并上传文件开始使用</p>
  </div>
</template>

<style scoped>
.welcome-container {
  padding: 2rem;
  text-align: center;
  color: #666;
}

.welcome-container h2 {
  margin-bottom: 1rem;
  color: #333;
}
</style>
