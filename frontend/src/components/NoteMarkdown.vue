<script lang="ts">
import MarkdownIt from "markdown-it";
import { katex } from "@mdit/plugin-katex";
import "katex/dist/katex.min.css";

const pageLink = /^#page-([1-9]\d*)$/;
const parser = new MarkdownIt({ html: false, breaks: true })
  .use(katex, { trust: false, maxExpand: 200, maxSize: 10 })
  .disable("image");

// Only HTTPS links and page references for the current document are interactive.
parser.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  const token = tokens[index]!;
  const href = String(token.attrGet("href") ?? "");
  const match = pageLink.exec(href);
  if (match && Number(match[1]) <= Number(env?.pageCount ?? 0)) {
    token.attrSet("class", "note-page-reference");
  } else if (/^https:\/\//i.test(href)) {
    token.attrSet("target", "_blank");
    token.attrSet("rel", "noopener noreferrer");
  } else {
    token.attrs = token.attrs?.filter(([name]) => name !== "href") ?? [];
  }
  return renderer.renderToken(tokens, index, options);
};
</script>

<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{ content: string; pageCount: number }>();
const emit = defineEmits<{ page: [page: number] }>();
const rendered = computed(() => parser.render(props.content, { pageCount: props.pageCount }));

function followPage(event: MouseEvent) {
  if (!(event.target instanceof Element)) return;
  const anchor = event.target.closest("a.note-page-reference");
  const match = pageLink.exec(anchor?.getAttribute("href") ?? "");
  if (match) {
    event.preventDefault();
    emit("page", Number(match[1]));
  }
}
</script>

<template>
  <article class="note-markdown" v-html="rendered" @click="followPage"></article>
</template>

<style scoped>
.note-markdown { min-width: 0; overflow-wrap: anywhere; line-height: 1.7; color: var(--ink); }
.note-markdown > :deep(:first-child) { margin-top: 0; }
.note-markdown :deep(h1) { font-size: 1.4em; }
.note-markdown :deep(h2) { font-size: 1.22em; }
.note-markdown :deep(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; }
.note-markdown :deep(:is(td, th)) { padding: 5px 9px; border: 1px solid var(--line); }
.note-markdown :deep(:is(pre, blockquote)) { margin-inline: 0; padding: 10px 14px; background: #f2f5fa; }
.note-markdown :deep(:is(pre, .katex-display)) { max-width: 100%; overflow: auto; }
.note-markdown :deep(pre:has(.language-text)) { white-space: pre-wrap; overflow-wrap: anywhere; }
.note-markdown :deep(a[href]) { color: var(--accent); text-decoration: underline; }
</style>
