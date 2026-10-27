<script setup lang="ts">
import { onMounted, ref } from "vue";
import { request, message } from "../api";
import ReaderChat from "./ReaderChat.vue";
const props = defineProps<{ courseId: string }>();
const items = ref<
    {
      id: string;
      title: string;
      materialId: string;
      materialAvailable: boolean;
      updatedAt: string;
    }[]
  >([]),
  selected = ref(""),
  error = ref(""),
  loading = ref(true);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    items.value = await request(`/api/courses/${props.courseId}/chat-sessions`);
    selected.value = items.value[0]?.id || "";
  } catch (cause) {
    error.value = message(cause);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="course-chat-history">
    <div class="section-heading">
      <h2>答疑记录</h2>
      <span>最近 50 段对话 · 进入课件可以继续提问</span>
    </div>
    <p v-if="loading" role="status">正在加载…</p>
    <p v-else-if="error" class="error" role="alert">
      {{ error }} <button class="text-button" @click="load">重试</button>
    </p>
    <p v-else-if="!items.length" class="state-panel">
      还没有答疑记录。在课件阅读页打开 AI 答疑，即可开始提问。
    </p>
    <template v-else
      ><label for="course-chat-session">选择对话</label
      ><select id="course-chat-session" v-model="selected">
        <option v-for="s in items" :key="s.id" :value="s.id">
          {{ s.title }} ·
          {{
            new Date(s.updatedAt).toLocaleString("zh-CN", {
              month: "numeric",
              day: "numeric",
              hour: "2-digit",
              minute: "2-digit",
            })
          }}{{ s.materialAvailable ? "" : "（来源课件已删除）" }}
        </option></select
      ><RouterLink
        v-if="items.find((s) => s.id === selected)?.materialAvailable"
        class="back-link"
        :to="`/materials/${items.find((s) => s.id === selected)?.materialId}/read?chat=${selected}`"
        >打开课件继续提问 →</RouterLink
      ><ReaderChat
        :key="selected"
        :course-id="courseId"
        :session-id="selected"
        readonly
    /></template>
  </section>
</template>
