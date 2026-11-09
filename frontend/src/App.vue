<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "./stores/auth";
import { message } from "./api";
import CleanupStatus from "./components/CleanupStatus.vue";
import AppIcon from "./components/AppIcon.vue";
const auth = useAuth();
const router = useRouter();
const route = useRoute();
const home = computed(() => route.path === '/');
const courseSpace = computed(() => route.path.startsWith('/courses/'));
const summarySpace = computed(() => route.path.endsWith('/summary'));
const reading = computed(() => route.path.startsWith('/materials/'));
const locationLabel = computed(() => route.path.endsWith('/summary') ? '课件核心摘要' : route.path.startsWith('/materials/') ? '课件阅读' : route.path.startsWith('/courses/') ? '课程空间' : '我的课程');
const logoutError = ref("");
const leaving = ref(false);
async function logout() {
  leaving.value = true;
  logoutError.value = "";
  try {
    await auth.logout();
    await router.replace("/login");
  } catch (error) {
    logoutError.value = message(error);
  } finally {
    leaving.value = false;
  }
}
</script>
<template>
  <div v-if="auth.user" class="workspace" :class="{ 'reading-workspace': reading, 'home-workspace': home, 'course-workspace': courseSpace, 'summary-workspace': summarySpace }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="topbar app-header">
      <div class="home-navigation">
        <RouterLink to="/" class="brand" aria-label="知笺首页"><span class="brand-mark">知</span><span class="app-brand-name">知笺</span></RouterLink>
        <nav aria-label="主导航"><RouterLink to="/" :aria-current="home ? 'page' : undefined"><AppIcon name="courses" :size="17" />我的课程</RouterLink></nav>
        <span v-if="!home" class="app-location">{{ locationLabel }}</span>
      </div>
      <div class="home-account">
        <span class="avatar" aria-hidden="true">{{ auth.user.displayName.slice(0, 1) }}</span>
        <span class="home-account-name" :title="`${auth.user.displayName} (@${auth.user.username})`">{{ auth.user.displayName }}</span>
        <button class="text-button" :disabled="leaving" @click="logout">{{ leaving ? '正在退出…' : '退出' }}</button>
      </div>
    </header>
    <main id="main-content" class="main" tabindex="-1">
      <p v-if="logoutError" role="alert" class="error app-logout-error">{{ logoutError }}</p>
      <CleanupStatus />
      <RouterView :key="$route.path" />
    </main>
  </div>
  <RouterView v-else />
</template>
