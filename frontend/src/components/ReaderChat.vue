<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { ApiError, message, request } from "../api";
import type { Slide } from "../api";
import { useAuth } from "../stores/auth";
const auth = useAuth();
const props = defineProps<{
  courseId: string;
  materialId?: string;
  slide?: Slide;
  sessionId?: string;
  readonly?: boolean;
}>();
const emit = defineEmits<{ navigate: [page: number] }>();
interface Session {
  id: string;
  title: string;
  materialId: string;
  materialAvailable: boolean;
  updatedAt: string;
}
interface Source {
  slideId: string;
  materialId: string;
  materialName: string;
  pageNo: number;
  imageAttached: boolean;
  truncated: boolean;
  available: boolean;
  changed: boolean;
}
interface Answer {
  explanation: string;
  supplement: string;
  limitations: string;
  citations: { slideId: string; kind: string; quote: string }[];
}
interface Turn {
  id: string;
  sequence: number;
  question: string;
  status: string;
  answer: Answer | null;
  sources: Source[];
  errorCode: string | null;
  errorMessage: string | null;
}
interface Conversation {
  session: Session;
  turns: Turn[];
}
interface Capabilities {
  aiAvailable: boolean;
  imageAvailable: boolean;
  remainingToday: number;
}
const sessions = ref<Session[]>([]),
  selected = ref(""),
  turns = ref<Turn[]>([]),
  caps = ref<Capabilities | null>(null);
const question = ref(""),
  image = ref(false),
  follow = ref(true),
  busy = ref(false),
  loading = ref(true),
  error = ref(""),
  copied = ref("");
const historyEl = ref<HTMLElement>();
const active = computed(() =>
  turns.value.some((t) => ["QUEUED", "RUNNING"].includes(t.status)),
);
const current = computed(() =>
  turns.value.find((t) => ["QUEUED", "RUNNING"].includes(t.status)),
);
let disposed = false,
  poll: ReturnType<typeof setTimeout> | undefined,
  generation = 0;
let attempt: {
  key: string;
  sessionKey: string;
  sessionId: string;
  body: {
    question: string;
    slideId: string;
    useImage: boolean;
    continuePrevious: boolean;
  };
} | null = null;
let retryAttempt: { id: string; key: string } | null = null;
const uncertain = ref(false);
const leaveDialog = ref<HTMLDialogElement>();
let resolveLeave: ((leave: boolean) => void) | null = null;
function decideLeave(leave: boolean) {
  leaveDialog.value?.close();
  const resolve = resolveLeave;
  resolveLeave = null;
  resolve?.(leave);
}
async function capabilities() {
  if (props.materialId && !props.readonly)
    caps.value = await request<Capabilities>(
      `/api/materials/${props.materialId}/chat-capabilities`,
    );
}
async function list() {
  sessions.value = await request<Session[]>(
    `/api/courses/${props.courseId}/chat-sessions${props.materialId ? "?materialId=" + props.materialId : ""}`,
  );
}
async function refresh(scroll = false) {
  const id = selected.value,
    epoch = generation;
  if (!id) {
    turns.value = [];
    return;
  }
  const data = await request<Conversation>(`/api/chat-sessions/${id}`);
  if (disposed || epoch !== generation || id !== selected.value) return;
  turns.value = data.turns;
  if (scroll) {
    await nextTick();
    historyEl.value?.scrollTo({
      top: historyEl.value.scrollHeight,
      behavior: "smooth",
    });
  }
}
function schedule() {
  clearTimeout(poll);
  if (disposed || !active.value) return;
  poll = setTimeout(async () => {
    try {
      await refresh();
      if (!active.value) {
        await capabilities();
        await list();
      }
    } catch (cause) {
      error.value = "暂时无法更新回答：" + message(cause);
    } finally {
      schedule();
    }
  }, 1500);
}
async function choose(id: string) {
  if (busy.value || uncertain.value) return;
  generation++;
  selected.value = id;
  error.value = "";
  turns.value = [];
  clearTimeout(poll);
  try {
    await refresh(true);
    schedule();
  } catch (cause) {
    error.value = message(cause);
  }
}
async function init() {
  loading.value = true;
  error.value = "";
  try {
    await list();
    await capabilities();
    await choose(
      sessions.value.some((s) => s.id === props.sessionId)
        ? props.sessionId!
        : sessions.value[0]?.id || "",
    );
  } catch (cause) {
    error.value = message(cause);
  } finally {
    loading.value = false;
  }
}
async function send() {
  if (busy.value || active.value || props.readonly || !props.slide) return;
  if (!attempt) {
    if (!question.value.trim() || [...question.value.trim()].length > 2000)
      return;
    attempt = {
      key: crypto.randomUUID(),
      sessionKey: crypto.randomUUID(),
      sessionId: selected.value,
      body: {
        question: question.value.trim(),
        slideId: props.slide.id,
        useImage: image.value,
        continuePrevious: follow.value,
      },
    };
  }
  busy.value = true;
  error.value = "";
  try {
    if (!attempt.sessionId) {
      const session = await request<Session>(
        `/api/materials/${props.materialId}/chat-sessions`,
        "POST",
        undefined,
        attempt.sessionKey,
      );
      attempt.sessionId = session.id;
      selected.value = session.id;
    }
    await request(
      `/api/chat-sessions/${attempt.sessionId}/turns`,
      "POST",
      attempt.body,
      attempt.key,
    );
    question.value = "";
    attempt = null;
    uncertain.value = false;
    await refresh(true);
    schedule();
    await list();
  } catch (cause) {
    error.value = message(cause);
    // A lost response may already have queued the turn. Replay the same key and payload.
    uncertain.value = !(cause instanceof ApiError);
    if (!uncertain.value) attempt = null;
  } finally {
    busy.value = false;
  }
}
async function retry(turn: Turn) {
  if (busy.value || active.value) return;
  busy.value = true;
  error.value = "";
  if (!retryAttempt || retryAttempt.id !== turn.id)
    retryAttempt = { id: turn.id, key: crypto.randomUUID() };
  try {
    await request(
      `/api/chat-turns/${turn.id}/retry`,
      "POST",
      undefined,
      retryAttempt.key,
    );
    retryAttempt = null;
    await refresh(true);
    schedule();
  } catch (cause) {
    error.value = message(cause);
    if (cause instanceof ApiError) retryAttempt = null;
  } finally {
    busy.value = false;
  }
}
async function stop(turn: Turn) {
  busy.value = true;
  error.value = "";
  try {
    await request(`/api/chat-turns/${turn.id}/cancel`, "POST");
    await refresh();
    await capabilities();
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
    schedule();
  }
}
async function copy(turn: Turn) {
  if (!turn.answer) return;
  try {
    await navigator.clipboard.writeText(
      [turn.answer.explanation, turn.answer.supplement, turn.answer.limitations]
        .filter(Boolean)
        .join("\n\n"),
    );
    copied.value = turn.id;
  } catch {
    error.value = "复制失败，请选中回答文字后复制。";
  }
}
function cite(turn: Turn, id: string) {
  return turn.sources.find((s) => s.slideId === id);
}
function leave(event: BeforeUnloadEvent) {
  if (auth.user && (question.value.trim() || uncertain.value)) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onBeforeRouteLeave(() => {
  if (!auth.user || (!question.value.trim() && !uncertain.value)) return true;
  if (!leaveDialog.value) return false;
  decideLeave(false);
  return new Promise<boolean>((resolve) => {
    resolveLeave = resolve;
    leaveDialog.value!.showModal();
  });
});
watch(
  () => auth.user,
  (user) => {
    if (!user) decideLeave(true);
  },
);
watch(
  () => props.sessionId,
  () => void choose(props.sessionId || ""),
);
onMounted(() => {
  void init();
  window.addEventListener("beforeunload", leave);
});
onBeforeUnmount(() => {
  disposed = true;
  decideLeave(false);
  clearTimeout(poll);
  window.removeEventListener("beforeunload", leave);
});
</script>
<template>
  <aside class="reader-chat" aria-label="AI 阅读答疑">
    <dialog
      ref="leaveDialog"
      class="dialog"
      aria-labelledby="chat-leave-title"
      @cancel.prevent="decideLeave(false)"
    >
      <h2 id="chat-leave-title">还有未发送的问题</h2>
      <p>离开页面后，输入框中的草稿将不会保留。</p>
      <div class="dialog-actions">
        <button
          type="button"
          class="button"
          autofocus
          @click="decideLeave(false)"
        >
          继续编辑
        </button>
        <button type="button" class="button primary" @click="decideLeave(true)">
          离开页面
        </button>
      </div>
    </dialog>
    <header class="chat-heading">
      <div>
        <span class="eyebrow">知笺 · 阅读助教</span>
        <h2>边读边问</h2>
      </div>
      <span class="chat-model">DeepSeek</span>
    </header>
    <div class="chat-session-bar">
      <label class="sr-only" for="chat-session">选择对话</label>
      <select
        id="chat-session"
        :value="selected"
        :disabled="busy || uncertain"
        @change="choose(($event.target as HTMLSelectElement).value)"
      >
        <option value="">新的课件答疑</option>
        <option v-for="s in sessions" :key="s.id" :value="s.id">
          {{ s.title }} ·
          {{
            new Date(s.updatedAt).toLocaleString("zh-CN", {
              month: "numeric",
              day: "numeric",
              hour: "2-digit",
              minute: "2-digit",
            })
          }}
        </option>
      </select>
      <button
        v-if="!readonly"
        class="text-button"
        :disabled="busy || uncertain || !selected"
        @click="choose('')"
      >
        新对话
      </button>
    </div>
    <p v-if="loading" class="chat-hint" role="status">正在读取对话…</p>
    <div v-else ref="historyEl" class="chat-history" :aria-busy="active">
      <div v-if="!turns.length" class="chat-empty">
        <span class="chat-empty-mark">问</span>
        <h3>{{ readonly ? "暂无对话内容" : "把不懂的地方留在这一页" }}</h3>
        <p>
          解释概念、补充例子，或继续追问。回答会保留本次使用的页面，方便回看原文。
        </p>
      </div>
      <article v-for="turn in turns" :key="turn.id" class="chat-turn">
        <div class="chat-question">
          <span>你 · 第 {{ turn.sequence }} 问</span>
          <p>{{ turn.question }}</p>
        </div>
        <div class="chat-sources">
          <template v-for="source in turn.sources" :key="source.slideId"
            ><button
              v-if="source.available && source.materialId === materialId"
              class="chat-page-tag"
              @click="emit('navigate', source.pageNo)"
            >
              第 {{ source.pageNo }} 页{{
                source.imageAttached ? " · 已附图" : " · 文字"
              }}{{ source.changed ? " · 已更新" : "" }}</button
            ><RouterLink
              v-else-if="source.available"
              class="chat-page-tag"
              :to="`/materials/${source.materialId}/read?page=${source.pageNo}`"
              >第 {{ source.pageNo }} 页{{
                source.imageAttached ? " · 已附图" : ""
              }}</RouterLink
            ><span v-else class="chat-page-tag"
              >{{ source.materialName }} · 第 {{ source.pageNo }} 页 ·
              来源已删除</span
            ><span v-if="source.truncated" class="chat-hint"
              >本页仅使用前 12000 字符</span
            ></template
          >
        </div>
        <div
          v-if="turn.status === 'QUEUED' || turn.status === 'RUNNING'"
          class="chat-generating"
          role="status"
        >
          <span class="chat-pulse"></span
          >{{
            turn.status === "QUEUED" ? "等待生成…" : "正在阅读本次提供的页面…"
          }}<button class="text-button" :disabled="busy" @click="stop(turn)">
            停止
          </button>
        </div>
        <template v-else-if="turn.answer">
          <section v-if="turn.answer.explanation" class="chat-answer">
            <h4>依据课件</h4>
            <p>{{ turn.answer.explanation }}</p>
          </section>
          <div
            v-for="ref in turn.answer.citations"
            :key="ref.slideId + ref.kind + ref.quote"
            class="chat-citation"
          >
            <button
              v-if="
                cite(turn, ref.slideId)?.available &&
                cite(turn, ref.slideId)?.materialId === materialId
              "
              class="chat-cite-link"
              @click="emit('navigate', cite(turn, ref.slideId)!.pageNo)"
            >
              {{ ref.kind === "IMAGE" ? "页面图像依据" : "课件原文" }} · 第
              {{ cite(turn, ref.slideId)?.pageNo }} 页 ↗
            </button>
            <RouterLink
              v-else-if="cite(turn, ref.slideId)?.available"
              class="chat-cite-link"
              :to="`/materials/${cite(turn, ref.slideId)?.materialId}/read?page=${cite(turn, ref.slideId)?.pageNo}`"
              >{{ ref.kind === "IMAGE" ? "页面图像依据" : "课件原文" }} · 第
              {{ cite(turn, ref.slideId)?.pageNo }} 页 ↗</RouterLink
            >
            <strong v-else
              >{{ ref.kind === "IMAGE" ? "页面图像依据" : "课件原文" }} · 第
              {{ cite(turn, ref.slideId)?.pageNo }} 页</strong
            >
            <blockquote v-if="ref.quote">{{ ref.quote }}</blockquote>
            <span v-else>基于本次附带的页面图片，请对照原图核对。</span>
          </div>
          <section
            v-if="turn.answer.supplement"
            class="chat-answer chat-supplement"
          >
            <h4>补充解释与例子</h4>
            <p>{{ turn.answer.supplement }}</p>
          </section>
          <p v-if="turn.answer.limitations" class="chat-limitations">
            {{ turn.answer.limitations }}
          </p>
          <button class="text-button chat-copy" @click="copy(turn)">
            {{ copied === turn.id ? "已复制" : "复制回答" }}
          </button>
        </template>
        <div v-else class="chat-failed">
          <p>{{ turn.errorMessage || "本次没有生成回答。" }}</p>
          <button
            v-if="
              !readonly &&
              turn.sequence === turns.at(-1)?.sequence &&
              turn.sources.every((s) => s.available && !s.changed)
            "
            class="text-button"
            :disabled="busy || active || !caps?.aiAvailable"
            @click="retry(turn)"
          >
            重试本轮（会重新调用 AI）</button
          ><button
            v-if="!readonly"
            class="text-button"
            :disabled="uncertain"
            @click="question = turn.question"
          >
            复制问题到输入框
          </button>
        </div>
      </article>
    </div>
    <p v-if="error" class="error chat-error" role="alert">
      {{ error
      }}<button v-if="!busy && !uncertain" class="text-button" @click="init">
        刷新对话
      </button>
    </p>
    <form
      v-if="!readonly && slide"
      class="chat-composer"
      @submit.prevent="send"
    >
      <div class="chat-context">
        <strong>本次提问 · 第 {{ slide.pageNo }} 页</strong
        ><span v-if="caps?.aiAvailable"
          >今日剩余 {{ caps.remainingToday }} 次</span
        >
      </div>
      <div class="chat-shortcuts">
        <button
          v-for="q in [
            '解释本页的核心概念',
            '用一个例子讲解本页内容',
            '这页有哪些易错点？',
          ]"
          :key="q"
          type="button"
          :disabled="busy || uncertain"
          @click="question = q"
        >
          {{
            q
              .replace("解释本页的核心概念", "解释本页")
              .replace("用一个例子讲解本页内容", "举个例子")
              .replace("这页有哪些易错点？", "易错点")
          }}
        </button>
      </div>
      <label class="sr-only" for="chat-question">向 AI 提问</label
      ><textarea
        id="chat-question"
        v-model="question"
        rows="3"
        maxlength="4000"
        :disabled="busy || uncertain"
        placeholder="例如：这两个概念有什么区别？"
        @keydown.ctrl.enter.prevent="send"
      ></textarea>
      <div class="chat-options">
        <label
          ><input
            v-model="follow"
            type="checkbox"
            :disabled="busy || uncertain"
          />延续最近对话与页面</label
        ><label
          ><input
            v-model="image"
            type="checkbox"
            :disabled="busy || uncertain || !caps?.imageAvailable"
          />附带本页图片</label
        >
      </div>
      <p class="chat-hint">
        {{
          image ? "本次包含本页图片和文字" : "本次仅包含本页文字，不含页面图片"
        }}{{ follow ? "；同时提供最近 4 轮成功对话及至多 3 页文字" : "" }}，交由
        DeepSeek 处理。AI 回答请结合课件核对。
      </p>
      <p v-if="caps && !caps.aiAvailable" class="chat-hint">
        AI 答疑尚未启用，请联系管理员配置。历史记录仍可查看。
      </p>
      <p v-if="active" class="chat-hint">
        正在回答第
        {{ current?.sources[0]?.pageNo }} 页的问题，翻页不会改变该问题的来源。
      </p>
      <div class="chat-send-row">
        <span>{{ [...question.trim()].length }}/2000 · Ctrl + Enter</span
        ><button
          class="button primary"
          :disabled="
            busy ||
            active ||
            !caps?.aiAvailable ||
            !question.trim() ||
            [...question.trim()].length > 2000 ||
            (!uncertain && caps.remainingToday <= 0)
          "
        >
          {{
            busy
              ? "正在发送…"
              : uncertain
                ? "确认发送结果 / 重试发送"
                : "发送问题"
          }}
        </button>
      </div>
    </form>
  </aside>
</template>
