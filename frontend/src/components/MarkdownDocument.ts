import { defineComponent, h, type VNodeChild } from "vue";
import { RouterLink } from "vue-router";

// Build text nodes, never HTML. Only our source-page paths and explicit HTTPS links are active.
function inline(text: string): VNodeChild[] {
  const tokens = text.split(
    /(\[[^\]\n]+\]\([^\s)]+\)|\*\*[^*\n]+\*\*|`[^`\n]+`)/g,
  );
  return tokens.map((token) => {
    const link = token.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    if (link) {
      if (/^\/materials\/[a-zA-Z0-9-]+\/read\?page=[1-9][0-9]*$/.test(link[2]!))
        return h(RouterLink, { to: link[2] }, () => link[1]);
      if (/^https:\/\/[^\s<>"'\\]+$/.test(link[2]!))
        return h(
          "a",
          { href: link[2], target: "_blank", rel: "noopener noreferrer" },
          link[1],
        );
      return token;
    }
    if (token.startsWith("**") && token.endsWith("**"))
      return h("strong", token.slice(2, -2));
    if (token.startsWith("`") && token.endsWith("`"))
      return h("code", token.slice(1, -1));
    return token;
  });
}
export default defineComponent({
  name: "MarkdownDocument",
  props: { content: { type: String, required: true } },
  setup(props) {
    return () => {
      const nodes: VNodeChild[] = [],
        lines = props.content.replace(/\r/g, "").split("\n");
      let paragraph: string[] = [],
        list: VNodeChild[] = [],
        code: string[] | null = null;
      const flush = () => {
        if (paragraph.length) {
          nodes.push(h("p", inline(paragraph.join("\n"))));
          paragraph = [];
        }
        if (list.length) {
          nodes.push(h("ul", list));
          list = [];
        }
      };
      for (const line of lines) {
        if (line.startsWith("```")) {
          flush();
          if (code) {
            nodes.push(h("pre", h("code", code.join("\n"))));
            code = null;
          } else code = [];
          continue;
        }
        if (code) {
          code.push(line);
          continue;
        }
        const heading = line.match(/^(#{1,6})\s+(.+)$/),
          item = line.match(/^\s*(?:[-*]|\d+\.)\s+(.+)$/);
        if (heading) {
          flush();
          nodes.push(
            h(`h${Math.min(heading[1]!.length + 1, 6)}`, inline(heading[2]!)),
          );
        } else if (item) {
          if (paragraph.length) flush();
          list.push(h("li", inline(item[1]!)));
        } else if (!line.trim()) flush();
        else {
          if (list.length) flush();
          paragraph.push(line);
        }
      }
      flush();
      if (code) nodes.push(h("pre", h("code", code.join("\n"))));
      return h("article", { class: "summary-prose" }, nodes);
    };
  },
});
