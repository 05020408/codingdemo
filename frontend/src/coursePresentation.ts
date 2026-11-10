const themes = [
  { ink: "#465fbd", paper: "#edf1fc" },
  { ink: "#1b7973", paper: "#eaf5f1" },
  { ink: "#95632c", paper: "#fbf2e5" },
  { ink: "#78629c", paper: "#f2eef8" },
];

// Match specific subjects first; other courses share the book icon.
const subjectIcons = [
  { pattern: /计算机网络|计网|通信|通讯|network|communication/i, icon: "network" },
  { pattern: /人工智能|机器学习|深度学习|神经网络|(?:^|[^a-z])ai(?:$|[^a-z])|artificial intelligence|machine learning|deep learning/i, icon: "chip" },
  { pattern: /数据库|数据管理|database|(?:^|[^a-z])sql(?:$|[^a-z])/i, icon: "database" },
  { pattern: /操作系统|计算机组成|计算机体系结构|operating system|computer architecture/i, icon: "computer" },
  { pattern: /数学|微积分|线性代数|概率|统计|高数|math|calculus|algebra|probability|statistics/i, icon: "math" },
  { pattern: /英语|外语|日语|法语|德语|语言学|english|language|japanese|french|german/i, icon: "language" },
  { pattern: /编程|程序设计|软件|算法|数据结构|java|python|javascript|typescript|c\+\+|c#|programming|software|algorithm|data structure/i, icon: "code" },
] as const;

export function courseIcon(name: string) {
  return subjectIcons.find(subject => subject.pattern.test(name.normalize("NFKC")))?.icon ?? "book";
}

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
