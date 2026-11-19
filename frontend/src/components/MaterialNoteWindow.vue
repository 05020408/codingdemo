<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { ApiError, message, request } from "../api";
import { noteEndpoint, noteFilename } from "../materialNotes";
import type { NoteDocument } from "../materialNotes";
import AppIcon from "./AppIcon.vue";

const NoteMarkdown = defineAsyncComponent(() => import("./NoteMarkdown.vue"));
const props = defineProps<{ courseId: string; materialId: string; open: boolean; currentPage: number | null }>();
const emit = defineEmits<{ close: []; page: [page: number] }>();
const endpoint = noteEndpoint(props.courseId, props.materialId);
const smallestFloatingWidth = 751;
const panel = ref<HTMLElement>();
const heading = ref<HTMLElement>();
const editor = ref<HTMLTextAreaElement>();
const documentNote = ref<NoteDocument>();
const draft = ref("");
const mode = ref<"edit" | "preview">("edit");
const loadError = ref("");
const saveError = ref("");
const conflict = ref(false);
const saving = ref(false);
const dirty = computed(() => !!documentNote.value && draft.value !== documentNote.value.markdown);
let saveTimer: number | undefined;
let pending: Promise<void> | null = null;
let alive = true;
let loadGeneration = 0;

const location = ref<{ x: number; y: number }>();
let pointer: { id: number; dx: number; dy: number } | null = null;
const placement = computed(() => location.value && {
  "--note-x": location.value.x + "px",
  "--note-y": location.value.y + "px",
});

function stopTimer() {
  window.clearTimeout(saveTimer);
  saveTimer = undefined;
}

function queueSave() {
  stopTimer();
  if (dirty.value && !pending && !saveError.value) {
    saveTimer = window.setTimeout(() => { void saveNow(); }, 800);
  }
}

async function load() {
  stopTimer();
  const current = ++loadGeneration;
  loadError.value = "";
  try {
    const note = await request<NoteDocument>(endpoint);
    if (!alive || current !== loadGeneration) return;
    documentNote.value = note;
    draft.value = note.markdown;
    saveError.value = "";
    conflict.value = false;
  } catch (cause) {
    if (alive && current === loadGeneration) loadError.value = message(cause);
  }
}

function saveNow(): Promise<void> {
  stopTimer();
  if (pending) return pending;
  const existing = documentNote.value;
  if (!existing || !dirty.value || conflict.value) return Promise.resolve();
  const markdown = draft.value;
  saving.value = true;
  saveError.value = "";
  pending = request<NoteDocument>(endpoint, "PUT", {
    markdown, expectedVersion: existing.version,
  }).then(note => {
    if (alive) documentNote.value = note;
  }).catch(cause => {
    if (!alive) return;
    conflict.value = cause instanceof ApiError && cause.code === "VERSION_CONFLICT";
    saveError.value = conflict.value ? "另一窗口已保存新版，你的草稿仍在。" : message(cause);
  }).finally(() => {
    pending = null;
    saving.value = false;
    if (alive) queueSave();
  });
  return pending;
}

async function reloadLatest() {
  if (dirty.value && !window.confirm("载入已保存的版本并放弃当前草稿？")) return;
  await load();
}

function protectDraft(event: BeforeUnloadEvent) {
  event.preventDefault();
  event.returnValue = true;
}

async function canLeave() {
  stopTimer();
  if (pending) await pending;
  if (dirty.value && !saveError.value) await saveNow();
  if (!dirty.value) return true;
  const leave = window.confirm("笔记还有未保存的修改。要离开吗？");
  if (!leave) queueSave();
  return leave;
}

async function insertPage() {
  const number = props.currentPage;
  if (!number || !documentNote.value) return;
  mode.value = "edit";
  await nextTick();
  const field = editor.value;
  if (!field) return;
  const start = field.selectionStart;
  const end = field.selectionEnd;
  const link = "[第 " + number + " 页](#page-" + number + ")";
  draft.value = draft.value.slice(0, start) + link + draft.value.slice(end);
  await nextTick();
  field.focus();
  field.setSelectionRange(start + link.length, start + link.length);
}

function downloadDraft() {
  const note = documentNote.value;
  if (!note) return;
  const url = URL.createObjectURL(new Blob([draft.value], { type: "text/markdown;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = noteFilename(note.materialName);
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function move(x: number, y: number) {
  if (!panel.value || window.innerWidth < smallestFloatingWidth) return;
  const { width, height } = panel.value.getBoundingClientRect();
  location.value = {
    x: Math.max(8, Math.min(x, window.innerWidth - width - 8)),
    y: Math.max(8, Math.min(y, window.innerHeight - height - 8)),
  };
}

function startMove(event: PointerEvent) {
  if (!panel.value || event.button !== 0 || window.innerWidth < smallestFloatingWidth) return;
  const bounds = panel.value.getBoundingClientRect();
  pointer = { id: event.pointerId, dx: event.clientX - bounds.left, dy: event.clientY - bounds.top };
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function drag(event: PointerEvent) {
  if (pointer?.id === event.pointerId) move(event.clientX - pointer.dx, event.clientY - pointer.dy);
}

function keyboardMove(event: KeyboardEvent) {
  if (window.innerWidth < smallestFloatingWidth || !panel.value) return;
  const offsets: Record<string, [number, number]> = {
    ArrowLeft: [-20, 0], ArrowRight: [20, 0], ArrowUp: [0, -20], ArrowDown: [0, 20],
  };
  const offset = offsets[event.key];
  if (!offset) return;
  event.preventDefault();
  const { left, top } = panel.value.getBoundingClientRect();
  move(left + offset[0], top + offset[1]);
}

function fitViewport() {
  if (location.value && props.open) move(location.value.x, location.value.y);
}

watch(draft, queueSave);
watch(dirty, value => {
  if (value) window.addEventListener("beforeunload", protectDraft);
  else window.removeEventListener("beforeunload", protectDraft);
});
watch(() => props.open, async open => {
  if (open) {
    await nextTick();
    fitViewport();
    heading.value?.focus({ preventScroll: true });
  }
});
onBeforeRouteLeave(to => to.path === "/login" || canLeave());
onBeforeRouteUpdate((to, from) => to.path === from.path || canLeave());
onMounted(() => {
  heading.value?.focus({ preventScroll: true });
  void load();
  window.addEventListener("resize", fitViewport);
});
onBeforeUnmount(() => {
  alive = false;
  stopTimer();
  window.removeEventListener("resize", fitViewport);
  window.removeEventListener("beforeunload", protectDraft);
});
</script>

<template>
  <Teleport to="body">
    <section v-show="open" id="material-note-window" ref="panel" class="note-window"
      :style="placement" aria-labelledby="material-note-heading" @keydown.stop>
      <header class="note-window-header">
        <div class="note-drag" @pointerdown="startMove" @pointermove="drag"
          @lostpointercapture="pointer = null" @pointercancel="pointer = null">
          <AppIcon name="edit" :size="17" />
          <h2 id="material-note-heading" ref="heading" tabindex="0" @keydown="keyboardMove"
            title="可以用方向键移动窗口">课件笔记</h2>
        </div>
        <button type="button" class="icon-button" aria-label="收起笔记" title="收起笔记" @click="emit('close')">−</button>
      </header>
      <div v-if="loadError && !documentNote" class="note-load">
        <p class="error" role="alert">{{ loadError }}</p>
        <button type="button" class="button secondary" @click="load">重新载入</button>
      </div>
      <p v-else-if="!documentNote" class="note-load muted" role="status">正在加载笔记…</p>
      <template v-else>
        <div class="note-window-filename" :title="noteFilename(documentNote.materialName)">{{ noteFilename(documentNote.materialName) }}</div>
        <div class="note-window-tools">
          <div class="note-view-switch" role="group" aria-label="笔记显示方式">
            <button type="button" :aria-pressed="mode === 'edit'" @click="mode = 'edit'">编辑</button>
            <button type="button" :aria-pressed="mode === 'preview'" @click="mode = 'preview'">预览</button>
          </div>
          <button type="button" class="button secondary note-insert" :disabled="!currentPage"
            @click="insertPage">插入第 {{ currentPage ?? '—' }} 页引用</button>
        </div>
        <textarea v-show="mode === 'edit'" ref="editor" v-model="draft" class="note-editor"
          :maxlength="Math.max(250000, documentNote.markdown.length)" aria-label="Markdown 笔记正文"
          placeholder="用 Markdown 记录这份课件…" spellcheck="false"></textarea>
        <div v-if="mode === 'preview'" class="note-preview">
          <NoteMarkdown v-if="draft.trim()" :content="draft" :page-count="documentNote.pageCount"
            @page="emit('page', $event)" />
          <p v-else class="muted">写下笔记后，预览会显示在这里。</p>
        </div>
        <div v-if="saveError || loadError" class="note-window-error" role="alert">
          <span>{{ loadError || saveError }}</span>
          <button v-if="conflict" type="button" class="text-button" @click="reloadLatest">载入已保存的版本</button>
        </div>
        <footer class="note-window-footer">
          <span class="muted" role="status">{{ saving ? "保存中…" : saveError ? "修改未保存" : dirty ? "等待保存…" : documentNote.version ? "已保存" : "尚未编辑" }}</span>
          <button type="button" class="button secondary" :disabled="!dirty || saving || conflict" @click="saveNow">保存</button>
          <button type="button" class="button secondary" :disabled="!draft && !documentNote.version" @click="downloadDraft">下载 .md</button>
        </footer>
      </template>
    </section>
  </Teleport>
</template>

<style scoped>
.note-window { position: fixed; z-index: 25; left: var(--note-x, auto); right: 24px; top: var(--note-y, min(120px, 12vh)); display: flex; flex-direction: column; width: min(420px, calc(100vw - 32px)); height: min(560px, 82dvh); min-width: 0; background: #fff; border: 1px solid #c8d7e7; border-radius: 12px; box-shadow: 0 16px 48px #17264030; overflow: hidden; }
.note-window-header { display: flex; flex: none; align-items: center; justify-content: space-between; gap: 6px; padding: 5px 10px 5px 14px; background: #edf4fa; border-bottom: 1px solid #d9e4ee; }
.note-drag { display: flex; flex: 1; align-items: center; gap: 9px; min-height: 36px; cursor: move; touch-action: none; user-select: none; color: var(--accent); }
.note-drag h2 { margin: 0; color: var(--ink); font-size: 16px; }
.note-window-filename { flex: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 10px 14px 0; color: var(--muted); font-size: 12px; }
.note-window-tools, .note-window-footer { display: flex; flex: none; flex-wrap: wrap; align-items: center; gap: 6px 8px; padding: 8px 12px; }
.note-window-tools { justify-content: space-between; }
.note-view-switch { display: inline-flex; gap: 2px; background: #f2f5fa; padding: 2px; border-radius: 6px; }
.note-view-switch button { border: 0; border-radius: 5px; padding: 5px 11px; background: transparent; font: inherit; font-size: 13px; cursor: pointer; color: var(--muted); }
.note-view-switch button[aria-pressed="true"] { background: #fff; color: var(--accent); font-weight: 650; box-shadow: 0 1px 3px #17264016; }
.note-window :is(.note-insert, .note-window-footer .button) { min-height: 32px; padding: 4px 9px; font-size: 12px; }
.note-window .note-editor, .note-preview { flex: 1 1 0; min-height: 0; width: auto; margin: 0 12px; padding: 9px; border: 1px solid #d9e4ee; border-radius: 6px; overflow: auto; }
.note-window .note-editor { resize: none; font: 14px/1.65 ui-monospace, "Microsoft YaHei", monospace; }
.note-preview { font-size: 14px; }
.note-window-error, .note-load { padding: 8px 14px; }
.note-window-error { flex: none; color: #a53939; font-size: 12px; }
.note-window-error button { margin-inline-start: 8px; }
.note-window-footer > span { flex: 1; font-size: 12px; }
@media (max-width: 750px) {
  .note-window { top: auto; right: 0; bottom: 0; left: 0; width: 100%; height: min(400px, 49dvh); border-radius: 12px 12px 0 0; }
  .note-drag { cursor: default; touch-action: auto; }
}
</style>
