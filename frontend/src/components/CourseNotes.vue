<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request } from "../api";
import type { Page } from "../api";
import { formatDate } from "../coursePresentation";
import AppIcon from "./AppIcon.vue";

interface Note {
  id: string;
  title: string;
  body: string;
  materialId: string;
  materialName: string;
  pageNo: number;
  sourceStatus: "AVAILABLE" | "DELETED" | "UNAVAILABLE";
  updatedAt: string;
}

const props = defineProps<{ courseId: string }>();
const route = useRoute(), router = useRouter();
const notes = ref<Note[]>([]);
const total = ref(0);
const loading = ref(true);
const error = ref("");
const pageSize = 20;
const page = computed(() => {
  const value = typeof route.query.notePage === "string" ? Number(route.query.notePage) : 1;
  return Number.isInteger(value) && value > 0 && value <= 1000000 ? value : 1;
});
let generation = 0;

function go(target: number, replace = false) {
  const location = { query: { tab: "notes", notePage: target > 1 ? String(target) : undefined } };
  return replace ? router.replace(location) : router.push(location);
}

async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const data = await request<Page<Note>>(`/api/courses/${props.courseId}/notes?page=${page.value}&size=${pageSize}`);
    if (generation !== current) return;
    const last = Math.max(1, Math.ceil(data.total / pageSize));
    if (page.value > last) {
      await go(last, true);
      return;
    }
    notes.value = data.items;
    total.value = data.total;
  } catch (cause) {
    if (generation === current) error.value = message(cause);
  } finally {
    if (generation === current) loading.value = false;
  }
}

function heading(note: Note) {
  if (note.title) return note.title;
  const excerpt = Array.from(note.body.split(/\r?\n/, 1)[0] ?? "");
  return excerpt.length > 48 ? excerpt.slice(0, 48).join("") + "…" : excerpt.join("");
}

function sourceLocation(note: Note) {
  return {
    path: `/materials/${note.materialId}/read`,
    query: { page: String(note.pageNo), from: "notes", notePage: page.value > 1 ? String(page.value) : undefined },
  };
}

watch([() => props.courseId, page], () => { void load(); }, { immediate: true });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <section class="course-notes" aria-labelledby="course-notes-heading">
    <div class="notes-heading">
      <h2 id="course-notes-heading">课程笔记</h2>
      <span v-if="!loading && !error" class="muted">{{ total }} 条</span>
    </div>
    <p v-if="loading" class="state-panel" role="status">正在加载笔记…</p>
    <div v-else-if="error" class="state-panel">
      <p class="error" role="alert">{{ error }}</p>
      <button type="button" class="button secondary" @click="load">重新加载</button>
    </div>
    <div v-else-if="!notes.length" class="empty-state">
      <AppIcon name="edit" :size="28" />
      <h3>这门课还没有笔记</h3>
      <p>打开课件，在要记录的那一页点击「记笔记」。</p>
      <RouterLink class="button secondary" :to="{ query: { tab: 'materials' } }">查看课件</RouterLink>
    </div>
    <template v-else>
      <ul class="notes-list">
        <li v-for="note in notes" :key="note.id" class="note-row">
          <details>
            <summary>{{ heading(note) }}</summary>
            <p class="note-body">{{ note.body }}</p>
          </details>
          <div class="note-meta">
            <span class="note-origin"><AppIcon name="file" :size="15" />{{ note.materialName }} · 第 {{ note.pageNo }} 页</span>
            <time :datetime="note.updatedAt">{{ formatDate(note.updatedAt) }}</time>
            <RouterLink v-if="note.sourceStatus === 'AVAILABLE'" class="text-button" :to="sourceLocation(note)">打开原页</RouterLink>
            <span v-else>{{ note.sourceStatus === 'DELETED' ? '课件已删除' : '原页暂不可用' }}</span>
          </div>
        </li>
      </ul>
      <nav v-if="total > pageSize" class="pagination" aria-label="笔记分页">
        <button type="button" class="button secondary" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
        <span>{{ page }} / {{ Math.ceil(total / pageSize) }}</span>
        <button type="button" class="button secondary" :disabled="page * pageSize >= total" @click="go(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>

<style scoped>
.notes-heading { display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px; }
.notes-heading h2 { margin: 0; }
.notes-list { margin: 0; padding: 0; list-style: none; background: #fff; border: 1px solid var(--line); border-radius: 10px; }
.note-row { padding: clamp(14px, 2vw, 22px); }
.note-row + .note-row { border-top: 1px solid var(--line); }
.note-row summary { cursor: pointer; font-weight: 650; overflow-wrap: anywhere; }
.note-body { margin: 12px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.note-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; margin-top: 10px; color: var(--muted); font-size: 13px; }
.note-origin { display: inline-flex; align-items: baseline; gap: 5px; min-width: 0; overflow-wrap: anywhere; }
.note-origin svg { flex: none; align-self: center; color: var(--course-tone); }
</style>
