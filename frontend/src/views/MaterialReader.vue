<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { message, request } from "../api";
import type { Course, Material, Page, ReadingProgress, Slide } from "../api";
import AppIcon from "../components/AppIcon.vue";
import ReaderChat from "../components/ReaderChat.vue";

const route = useRoute(), router = useRouter();
const id = String(route.params.id);
const material = ref<Material | null>(null), courseName = ref("");
const slides = ref<Slide[]>([]), page = ref(1), pageInput = ref("1");
const loading = ref(true), error = ref(""), pageError = ref("");
const imageError = ref(false), imageLoading = ref(true), imageRevision = ref(0);
const saveError = ref(""), savedPage = ref<number | null>(null);
const focusContext = ref(""), outlineOpen = ref(true), chatOpen = ref(true);
const readerPanel = ref(route.query.panel === "chat" ? "chat" : "read");
const zoom = ref(100), thumbnails = ref<HTMLElement>(), canvas = ref<HTMLElement>();
const focusId = computed(() => typeof route.query.focus === "string" ? route.query.focus : "");
const selected = computed(() => slides.value.find(s => s.pageNo === page.value));
const returnLocation = computed(() => ({
  path: `/courses/${material.value?.courseId}`,
  hash: focusId.value ? "#focus-results" : undefined,
  query: focusId.value
    ? { tab: "focus", focus: focusId.value, focusPage: route.query.focusPage, mastery: route.query.mastery, match: route.query.match }
    : route.query.from === "history" ? { tab: "chat", chat: route.query.chat } : { tab: "materials" },
}));
let disposed = false, generation = 0, saveChain = Promise.resolve();

async function loadFocus() {
  const focus = focusId.value;
  focusContext.value = "";
  if (!focus || !material.value) return;
  try {
    const value = await request<{ content: string; courseId: string }>(`/api/focuses/${encodeURIComponent(focus)}`);
    if (!disposed && focus === focusId.value && value.courseId === material.value?.courseId) focusContext.value = value.content;
  } catch { /* A removed or inaccessible focus must not prevent reading the course material. */ }
}
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const meta = await request<Material>(`/api/materials/${id}`);
    if (!meta.capabilities.canReadPreview) throw new Error("课件预览尚未就绪，请返回课程查看处理状态。");
    let items: Slide[] = [];
    for (let n = 1; n <= Math.ceil(meta.slideCount / 100); n++) {
      const data = await request<Page<Slide>>(`/api/materials/${id}/slides?page=${n}&size=100`);
      items.push(...data.items);
    }
    const progress = await request<ReadingProgress>(`/api/materials/${id}/reading-progress`);
    if (disposed || current !== generation) return;
    material.value = meta;
    slides.value = items;
    page.value = Math.max(1, Math.min(meta.slideCount, progress.lastPageNo ?? 1));
    pageInput.value = String(page.value);
    savedPage.value = progress.lastPageNo;
    imageError.value = false;
    imageLoading.value = true;
    applyQueryPage();
    await loadFocus();
    try {
      const course = await request<Course>(`/api/courses/${meta.courseId}`);
      if (!disposed && current === generation) courseName.value = course.name;
    } catch { courseName.value = ""; }
  } catch (cause) {
    if (!disposed && current === generation) error.value = message(cause);
  } finally {
    if (!disposed && current === generation) {
      loading.value = false;
      await nextTick();
      scrollThumbnail();
    }
  }
}
function save(target: number) {
  saveChain = saveChain.then(async () => {
    try {
      await request(`/api/materials/${id}/reading-progress`, "PUT", { lastPageNo: target });
      if (!disposed) { savedPage.value = target; saveError.value = ""; }
    } catch (cause) {
      if (!disposed) saveError.value = "阅读位置未保存：" + message(cause);
    }
  });
}
function scrollThumbnail() {
  const item = thumbnails.value?.querySelector<HTMLElement>(`[data-page="${page.value}"]`);
  if (!item || !thumbnails.value) return;
  const box = thumbnails.value.getBoundingClientRect(), rect = item.getBoundingClientRect();
  if (rect.top < box.top || rect.bottom > box.bottom) thumbnails.value.scrollTop += rect.top - box.top - 10;
  if (rect.left < box.left || rect.right > box.right) thumbnails.value.scrollLeft += rect.left - box.left - 10;
}
function go(target: number, updateUrl = true) {
  if (!material.value || !Number.isInteger(target) || target < 1 || target > material.value.slideCount) {
    pageError.value = `请输入 1–${material.value?.slideCount ?? 1} 之间的整数页码。`;
    return;
  }
  pageError.value = "";
  pageInput.value = String(target);
  if (target !== page.value) {
    page.value = target;
    imageError.value = false;
    imageLoading.value = true;
    if (canvas.value) { canvas.value.scrollTop = 0; canvas.value.scrollLeft = 0; }
    void nextTick(scrollThumbnail);
    save(target);
  }
  if (updateUrl && route.query.page !== String(target)) void router.replace({ query: { ...route.query, page: String(target) } });
}
function applyQueryPage() {
  if (route.query.page === undefined) return;
  const raw = route.query.page;
  if (typeof raw !== "string" || !/^\d+$/.test(raw)) {
    pageError.value = "链接中的页码无效，请使用页码框跳转。";
    return;
  }
  const target = Number(raw), previous = page.value;
  go(target, false);
  if (target === previous && !pageError.value && savedPage.value !== target) save(target);
}
function setPanel(panel: string) {
  readerPanel.value = panel;
  if (panel === "chat") chatOpen.value = true;
  void router.replace({ query: { ...route.query, panel: panel === "chat" ? "chat" : undefined } });
}
function toggleChat() {
  chatOpen.value = !chatOpen.value;
  setPanel(chatOpen.value ? "chat" : "read");
}
function sessionChanged(session: string) {
  if (!disposed && (route.query.chat || "") !== session)
    void router.replace({ query: { ...route.query, chat: session || undefined } });
}
function openSource(target: number) {
  go(target, false);
  readerPanel.value = "read";
  void router.replace({ query: { ...route.query, page: String(page.value), panel: undefined } });
}
function key(event: KeyboardEvent) {
  if (loading.value || error.value || event.altKey || event.ctrlKey || event.metaKey ||
    (event.target instanceof Element && event.target.closest("input,textarea,button,select,summary,a,[contenteditable],[role=dialog]"))) return;
  if (event.key === "ArrowRight" && page.value < (material.value?.slideCount ?? 1)) { event.preventDefault(); go(page.value + 1); }
  if (event.key === "ArrowLeft" && page.value > 1) { event.preventDefault(); go(page.value - 1); }
}
watch(() => route.query.page, () => { if (!loading.value) applyQueryPage(); });
watch(focusId, () => { if (!loading.value) void loadFocus(); });
watch(() => route.query.panel, value => {
  readerPanel.value = value === "chat" ? "chat" : "read";
  if (value === "chat") chatOpen.value = true;
});
onMounted(() => { void load(); window.addEventListener("keydown", key); });
onBeforeUnmount(() => { disposed = true; generation++; window.removeEventListener("keydown", key); });
</script>

<template>
  <section class="reader-page" :class="{ 'with-focus': !!focusContext }">
    <div v-if="loading" class="state-panel" role="status">正在打开课件，恢复上次阅读位置…</div>
    <div v-else-if="error" class="state-panel">
      <h2>暂时无法打开课件</h2><p class="error" role="alert">{{ error }}</p>
      <div class="focus-actions"><RouterLink class="button secondary" to="/">返回我的课程</RouterLink><button class="button secondary" @click="load">重新加载</button></div>
    </div>
    <template v-else-if="material">
      <header class="reader-heading">
        <div class="reader-title">
          <RouterLink class="back-link" :to="returnLocation">← {{ focusId ? "返回重点与定位结果" : route.query.from === 'history' ? "返回答疑记录" : courseName || "返回课程" }}</RouterLink>
          <h1>{{ material.displayName }}</h1><p>{{ material.slideCount }} 页课件 · 保留原始页码<span class="reader-key-hint"> · ← → 翻页</span></p>
        </div>
        <div class="reader-view-actions">
          <button class="button secondary" :aria-expanded="outlineOpen" aria-controls="page-directory" @click="outlineOpen = !outlineOpen"><AppIcon name="outline" :size="18" />页面目录</button>
          <button class="button" :class="chatOpen ? 'primary' : 'secondary'" :aria-expanded="chatOpen" aria-controls="reader-answer-panel" @click="toggleChat"><AppIcon name="chat" :size="18" />{{ chatOpen ? "收起答疑" : "打开答疑" }}</button>
          <a v-if="material.capabilities.canDownload" class="icon-button reader-download" :href="material.originalDownloadUrl + '?disposition=attachment'" aria-label="下载课件原件" title="下载原件"><AppIcon name="download" /></a>
        </div>
      </header>
      <aside v-if="focusContext" class="reader-focus-context"><span class="focus-context-label">正在核对的重点</span><p>{{ focusContext }}</p><RouterLink :to="returnLocation">返回定位结果<AppIcon name="arrow" :size="15" /></RouterLink></aside>
      <nav class="reader-panel-tabs" aria-label="阅读面板">
        <button :class="{ active: readerPanel === 'read' }" :aria-pressed="readerPanel === 'read'" @click="setPanel('read')"><AppIcon name="file" :size="17" />课件阅读<span>第 {{ page }} 页</span></button>
        <button :class="{ active: readerPanel === 'chat' }" :aria-pressed="readerPanel === 'chat'" @click="setPanel('chat')"><AppIcon name="chat" :size="17" />AI 答疑</button>
      </nav>
      <div class="reader-layout" :class="{ 'has-outline': outlineOpen, 'has-chat': chatOpen, 'show-chat': readerPanel === 'chat' }">
        <aside v-show="outlineOpen" id="page-directory" class="page-directory" aria-label="课件页面目录">
          <div class="directory-heading"><span>页面目录</span><span>{{ slides.length }}</span></div>
          <div ref="thumbnails" class="thumbnail-list"><button v-for="slide in slides" :key="slide.id" class="thumbnail-item" :class="{ selected: slide.pageNo === page }" :data-page="slide.pageNo" :aria-current="slide.pageNo === page ? 'page' : undefined" :aria-label="`第 ${slide.pageNo} 页${slide.isHidden ? '，隐藏页' : ''}`" :title="slide.title || `第 ${slide.pageNo} 页`" @click="go(slide.pageNo)"><img v-if="slide.thumbnailUrl" :src="slide.thumbnailUrl" alt="" loading="lazy" /><span><b>{{ String(slide.pageNo).padStart(2, '0') }}</b><small v-if="slide.isHidden">隐藏页</small></span></button></div>
        </aside>
        <div class="reader-content">
          <div class="reader-toolbar">
            <div class="page-controls"><button class="icon-button" :disabled="page === 1" aria-label="上一页" title="上一页" @click="go(page - 1)"><AppIcon name="previous" :size="19" /></button><form class="page-jump" @submit.prevent="go(Number(pageInput))"><label for="reader-page-number" class="sr-only">跳转页码</label><input id="reader-page-number" v-model="pageInput" inputmode="numeric" autocomplete="off" :aria-invalid="!!pageError" aria-describedby="reader-page-total" /><span id="reader-page-total">/ {{ material.slideCount }}</span><button type="submit" class="text-button">跳转</button></form><button class="icon-button" :disabled="page === material.slideCount" aria-label="下一页" title="下一页" @click="go(page + 1)"><AppIcon name="chevron" :size="19" /></button></div>
            <div class="zoom-controls"><AppIcon name="zoom" :size="16" /><label for="reader-zoom" class="sr-only">课件缩放</label><select id="reader-zoom" v-model.number="zoom"><option :value="100">适合宽度</option><option v-for="value in [125, 150, 175, 200]" :key="value" :value="value">{{ value }}%</option></select></div>
          </div>
          <p v-if="pageError" class="error" role="alert">{{ pageError }}</p>
          <div ref="canvas" class="slide-viewport" tabindex="0" aria-label="课件页面，可使用左右方向键翻页"><div class="slide-paper" :style="{ width: zoom + '%' }" :aria-busy="imageLoading"><div v-if="imageError" class="image-error" role="alert"><AppIcon name="file" :size="28" /><p>页面图片加载失败，请检查网络后重试。</p><button class="button secondary" @click="imageError = false; imageLoading = true; imageRevision++">重新加载图片</button></div><template v-else><p v-if="imageLoading" class="image-loading" role="status">正在加载第 {{ page }} 页…</p><img :key="`${page}-${imageRevision}`" :src="selected?.previewUrl ? selected.previewUrl + '?v=' + imageRevision : undefined" :alt="`${material.displayName}，原始第 ${page} 页`" @load="imageLoading = false" @error="imageLoading = false; imageError = true" /></template></div></div>
          <div class="reading-status" role="status"><span class="original-page">原页 {{ String(page).padStart(3, '0') }}<span v-if="selected?.isHidden" class="stage-badge">隐藏页</span></span><span v-if="!saveError"><AppIcon v-if="savedPage === page" name="check" :size="14" />{{ savedPage === page ? "位置已保存" : savedPage === null ? "翻页后自动保存" : "正在保存位置…" }}</span></div>
          <p v-if="saveError" class="error" role="alert">{{ saveError }}<button class="text-button" @click="save(page)">重新保存</button></p>
          <details class="slide-text"><summary>本页提取文字<span>用于检索与答疑</span></summary><pre>{{ selected?.text || "本页暂无可提取文字。图片、公式和图表请以课件页面为准。" }}</pre></details>
        </div>
        <ReaderChat v-show="chatOpen" id="reader-answer-panel" :course-id="material.courseId" :material-id="material.id" :material-name="material.displayName" :slide="selected" :session-id="typeof route.query.chat === 'string' ? route.query.chat : undefined" collapsible @close="toggleChat" @navigate="openSource" @session-change="sessionChanged" />
      </div>
    </template>
  </section>
</template>
