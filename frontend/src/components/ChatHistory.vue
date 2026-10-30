<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { request, message } from "../api";
import { formatDate } from "../coursePresentation";
import AppIcon from "./AppIcon.vue";
import ReaderChat from "./ReaderChat.vue";
interface Session { id: string; title: string; materialId: string; materialAvailable: boolean; updatedAt: string }
const props = defineProps<{ courseId: string }>();
const route = useRoute(), router = useRouter();
const items = ref<Session[]>([]), selected = ref(""), error = ref(""), loading = ref(true);
const session = computed(() => items.value.find(s => s.id === selected.value));
let disposed = false;
function restoreSelection() {
  selected.value = items.value.find(s => s.id === route.query.chat)?.id || items.value[0]?.id || "";
}
function pick(id: string) {
  selected.value = id;
  void router.replace({ query: { ...route.query, tab: "chat", chat: id } });
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const data = await request<Session[]>(`/api/courses/${props.courseId}/chat-sessions`);
    if (!disposed) { items.value = data; restoreSelection(); }
  } catch (cause) {
    if (!disposed) error.value = message(cause);
  } finally { if (!disposed) loading.value = false; }
}
watch(() => route.query.chat, restoreSelection);
onMounted(load);
onBeforeUnmount(() => { disposed = true; });
</script>
<template>
  <section class="course-chat-history">
    <div class="section-line"><h2>答疑记录<span v-if="!loading && !error" class="count">{{ items.length }} 段</span></h2><span>显示最近 50 段对话</span></div>
    <div v-if="loading" class="state-panel" role="status">正在加载答疑记录…</div>
    <p v-else-if="error" class="error" role="alert">{{ error }}<button class="text-button" @click="load">重新加载</button></p>
    <div v-else-if="!items.length" class="history-empty"><span class="empty-symbol"><AppIcon name="chat" :size="28" /></span><h3>读课件时，把不懂的地方问清楚</h3><p>在课件阅读页打开 AI 答疑。提问和回答会保存在这里，随时回看来源。</p><RouterLink class="button secondary" :to="{ query: { tab: 'materials' } }">去查看课件<AppIcon name="arrow" :size="16" /></RouterLink></div>
    <div v-else class="history-layout">
      <aside class="history-session-list" aria-label="历史对话"><h3>最近对话</h3><button v-for="item in items" :key="item.id" :class="{ selected: selected === item.id }" :aria-pressed="selected === item.id" @click="pick(item.id)"><strong>{{ item.title }}</strong><span>{{ formatDate(item.updatedAt) }}<span v-if="!item.materialAvailable"> · 来源已删除</span></span></button></aside>
      <div class="history-conversation">
        <div class="history-resume"><p>回看问答与原页依据</p><RouterLink v-if="session?.materialAvailable" class="button secondary" :to="{ path: `/materials/${session.materialId}/read`, query: { chat: selected, panel: 'chat', from: 'history' } }">继续这段答疑<AppIcon name="arrow" :size="16" /></RouterLink><span v-else class="muted">来源课件已删除，仍可回看记录。</span></div>
        <ReaderChat :key="selected" :course-id="courseId" :session-id="selected" readonly />
      </div>
    </div>
  </section>
</template>
