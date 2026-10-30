# 第二轮界面优化证据

采集日期：2026 年 10 月 30 日（北京时间）。版本 `v0.4.2`，执行者 Codex。对应[执行记录](../../records/ui-round2-20261030.md)，不是 Sprint 1 原始验收材料。

截图使用独立合成账户、合成课程与自行制作的 PPTX，不包含教师课件、真实学生信息、密码或 Key。AI 回答由本机 HTTP 替身生成并显式标注；不能据此证明真实 DeepSeek 回答质量。桌面阅读主图、历史图与阅读/答疑手机图为替身停止、AI 默认关闭后的历史查看状态，重点图片采集于本地替身验收期间。

| 文件 | 内容 |
| --- | --- |
| [reader-desktop.png](reader-desktop.png) | 1280×900 桌面：目录、PPT 原页、重点上下文、答疑依据与下一问页面 |
| [reader-mobile.png](reader-mobile.png) | 390×844 手机阅读概览：重点上下文、横向目录、页码与 PPT |
| [chat-mobile.png](chat-mobile.png) | 390×844 手机答疑概览：重点上下文、面板切换、原页引用与测试回答 |
| [focus-desktop.png](focus-desktop.png) | 桌面完整重点页：定位筛选、中文重点、原文依据与已确认位置 |
| [focus-mobile.png](focus-mobile.png) | 手机完整重点页：纵向排列，原文卡片与操作入口 |
| [history-desktop.png](history-desktop.png) | 桌面历史页：对话列表、选中项及继续这段答疑入口 |
| [verification-summary.json](verification-summary.json) | 后端 47 项回归、前端构建、源文件/日志/图片哈希及本地替身调用的安全统计 |

截图均已逐张视觉查看。完整页截图高度可能超过所列视口高度。320×800 的溢出测量、临时失败修正、等待停止与请求发出后停止的验证差异，见执行记录，不用单张成功截图代替异常记录。

原始构建日志、临时替身脚本、调用日志与上传样本只保留在 Git 忽略的 `.runtime`；统计不复制环境属性、凭据或完整提示词。所有 UI 状态、模型验证范围、团队评审与远程 CI 均按真实发生情况记录。
