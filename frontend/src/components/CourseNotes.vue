<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request } from "../api";
import type { Page } from "../api";
import { formatDate } from "../coursePresentation";
import { noteEndpoint, noteFilename } from "../materialNotes";
import type { NoteDocument, NoteEntry } from "../materialNotes";
import AppIcon from "./AppIcon.vue";

const NoteMarkdown = defineAsyncComponent(() => import("./NoteMarkdown.vue"));
const props = defineProps<{ courseId: string }>();
const route = useRoute(), router = useRouter();
const notes = ref<NoteEntry[]>([]);
const total = ref(0);
const loading = ref(true);
const error = ref("");
const expanded = ref("");
const documentNote = ref<NoteDocument>();
const detailError = ref("");
const pageSize = 20;
const page = computed(() => {
  const value = typeof route.query.notePage === "string" ? Number(route.query.notePage) : 1;
  return Number.isInteger(value) && value > 0 && value <= 1000000 ? value : 1;
});
let generation = 0;
let detailGeneration = 0;

function go(target: number, replace = false) {
  const location = { query: { tab: "notes", notePage: target > 1 ? String(target) : undefined } };
  return replace ? router.replace(location) : router.push(location);
}

async function load() {
  detailGeneration++;
  expanded.value = "";
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const data = await request<Page<NoteEntry>>(noteEndpoint(props.courseId) + "?page=" + page.value + "&size=" + pageSize);
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

async function togglePreview(note: NoteEntry, retry = false) {
  const current = ++detailGeneration;
  if (expanded.value === note.materialId && !retry) {
    expanded.value = "";
    return;
  }
  expanded.value = note.materialId;
  documentNote.value = undefined;
  detailError.value = "";
  try {
    const value = await request<NoteDocument>(noteEndpoint(props.courseId, note.materialId));
    if (current === detailGeneration) documentNote.value = value;
  } catch (cause) {
    if (current === detailGeneration) detailError.value = message(cause);
  }
}

function sourceLocation(note: NoteEntry, target?: number) {
  return {
    path: `/materials/${note.materialId}/read`,
    query: { from: "notes", notes: "open", page: target ? String(target) : undefined, notePage: page.value > 1 ? String(page.value) : undefined },
  };
}

watch([() => props.courseId, page], () => { void load(); }, { immediate: true });
onBeforeUnmount(() => { generation++; detailGeneration++; });
</script>

<template>
  <section class="course-notes" aria-labelledby="course-notes-heading">
    <div class="notes-heading">
      <h2 id="course-notes-heading">课程笔记</h2>
      <span v-if="!loading && !error" class="muted">{{ total }} 份</span>
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
        <li v-for="note in notes" :key="note.materialId" class="note-row">
          <h3 class="note-title"><AppIcon name="file" :size="18" />{{ noteFilename(note.materialName) }}</h3>
          <div class="note-meta">
            <time :datetime="note.updatedAt">{{ formatDate(note.updatedAt) }}</time>
            <span v-if="note.sourceStatus !== 'AVAILABLE'">{{ note.sourceStatus === 'DELETED' ? '课件已删除' : '课件暂不可读' }}</span>
            <button type="button" class="text-button" :aria-expanded="expanded === note.materialId" @click="togglePreview(note)">
              {{ expanded === note.materialId ? "收起预览" : "预览" }}
            </button>
            <RouterLink v-if="note.sourceStatus === 'AVAILABLE'" class="text-button" :to="sourceLocation(note)">打开编辑</RouterLink>
            <a class="text-button" :href="noteEndpoint(courseId, note.materialId) + '/download'">下载 .md</a>
          </div>
          <div v-if="expanded === note.materialId" class="note-detail">
            <template v-if="documentNote">
              <NoteMarkdown v-if="documentNote.markdown.trim()" :content="documentNote.markdown"
                :page-count="documentNote.sourceStatus === 'AVAILABLE' ? documentNote.pageCount : 0"
                @page="router.push(sourceLocation(note, $event))" />
              <p v-else class="muted">这份笔记是空的。</p>
            </template>
            <div v-else-if="detailError">
              <p class="error" role="alert">{{ detailError }}</p>
              <button type="button" class="text-button" @click="togglePreview(note, true)">重试</button>
            </div>
            <p v-else class="muted" role="status">正在加载预览…</p>
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
.note-title { display: flex; align-items: baseline; gap: 8px; margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.note-title svg { flex: none; align-self: center; color: var(--course-tone); }
.note-detail { min-width: 0; padding: 14px 0 0; margin-top: 14px; border-top: 1px solid var(--line); }
.note-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; margin-top: 10px; color: var(--muted); font-size: 13px; }
</style>
