const themes = [
  { ink: "#465fbd", paper: "#edf1fc" },
  { ink: "#1b7973", paper: "#eaf5f1" },
  { ink: "#95632c", paper: "#fbf2e5" },
  { ink: "#78629c", paper: "#f2eef8" },
];

export function courseTheme(id: string) {
  let hash = 0;
  for (const character of id) hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  const theme = themes[hash % themes.length]!;
  return { "--course-tone": theme.ink, "--course-paper": theme.paper };
}

export function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "short", day: "numeric",
  }).format(date);
}
