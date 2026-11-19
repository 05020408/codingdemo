<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { message, request } from "../api";

const props = defineProps<{
  courseId: string;
  source: { slideId: string; materialName: string; pageNo: number };
}>();
const emit = defineEmits<{ close: []; saved: [] }>();
const dialog = ref<HTMLDialogElement>();
const bodyInput = ref<HTMLTextAreaElement>();
const title = ref("");
const body = ref("");
const busy = ref(false);
const error = ref("");
let pendingKey = "";
let pendingBody = "";
let saved = false;
const previousFocus = document.activeElement;

function hasDraft() {
  return !saved && !!(title.value.trim() || body.value.trim());
}
function canLeave() {
  return !busy.value && (!hasDraft() || window.confirm("笔记尚未保存，要放弃本次编辑吗？"));
}
function protectDraft(event: BeforeUnloadEvent) {
  event.preventDefault();
  event.returnValue = true;
}
function close() {
  if (canLeave()) emit("close");
}

watch([title, body], () => {
  window.removeEventListener("beforeunload", protectDraft);
  if (hasDraft()) window.addEventListener("beforeunload", protectDraft);
});
onBeforeRouteLeave(to => to.path === "/login" || canLeave());
onBeforeRouteUpdate((to, from) => to.path === from.path || canLeave());
onMounted(() => {
  dialog.value?.showModal();
  bodyInput.value?.focus();
});
onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", protectDraft);
  dialog.value?.close();
  if (previousFocus instanceof HTMLElement) void nextTick(() => previousFocus.focus());
});

async function save() {
  if (busy.value || !body.value.trim()) return;
  const input = { slideId: props.source.slideId, title: title.value.trim(), body: body.value.trim() };
  const serialized = JSON.stringify(input);
  if (serialized !== pendingBody) {
    pendingKey = crypto.randomUUID();
    pendingBody = serialized;
  }
  busy.value = true;
  error.value = "";
  try {
    await request(`/api/courses/${props.courseId}/notes`, "POST", input, pendingKey);
    saved = true;
    emit("saved");
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="slide-note-heading" @cancel.prevent="close">
    <form @submit.prevent="save">
      <div class="dialog-heading">
        <h2 id="slide-note-heading">记笔记</h2>
        <button type="button" class="icon-button" aria-label="关闭" :disabled="busy" @click="close">×</button>
      </div>
      <p class="note-source muted">
        {{ source.materialName }} · 第 {{ source.pageNo }} 页
      </p>
      <label for="slide-note-title">标题 <span class="optional">选填</span></label>
      <input id="slide-note-title" v-model="title" maxlength="100" :disabled="busy" />
      <label for="slide-note-body">笔记正文</label>
      <textarea id="slide-note-body" ref="bodyInput" v-model="body" rows="7" maxlength="8000" required :disabled="busy" />
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button type="button" class="button secondary" :disabled="busy" @click="close">取消</button>
        <button class="button primary" :disabled="busy || !body.trim()">
          {{ busy ? "保存中…" : "保存笔记" }}
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.note-source { margin: 0 0 18px; overflow-wrap: anywhere; }
</style>
