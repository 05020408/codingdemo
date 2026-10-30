# 知笺 · 期末复习工作台

帮助学生按课程整理课件，把复习重点定位到 PPT 原页，并逐步加入阅读答疑、笔记和习题。平台名称由用户于 2026 年 10 月 27 日确定为“知笺”。

当前应用版本 `0.4.1`：账户、课程、PPTX 上传阅读、重点清单与定位、阅读 AI 答疑和可选当前页图片已具备；本轮优化课程首页、课程详情和课件列表。笔记和三种习题来源仍待开发。

课程首页支持按名称或备注搜索全部本人课程，搜索与分页保留在地址中；卡片颜色随课程固定，卡片和详情均可通过“管理课程”编辑或删除。课件区支持选择或拖入一份 PPTX，优先显示阅读、定位是否可用，处理详情可展开，失败时仅对允许重试的阶段提供操作。变化、47 项回归及合成界面证据见[第一轮界面优化记录](docs/records/ui-round1-20261030.md)。

打开课件后可在右侧“边读边问”输入问题，窄屏通过“课件阅读 / AI 答疑”切换。发送时固定页面，翻页不改变正在回答的问题。“延续最近对话与页面”发送最近 4 轮成功对话和至多 3 页文字；取消后只使用当前页。“附带本页图片”默认不勾选，不自动把整份课件交给 AI。回答区分课件依据、补充和限制；文字引文逐段校验，图片判断需自行对照原图。对话按课件创建、按课程隔离，每段最多 100 轮，列表显示最近 50 段。答案完成后整段展示，尚无逐字流式输出。

项目直接在本机运行，**不使用 Docker**。AI 默认关闭，关键词定位可独立使用；常规自动化测试使用本地 HTTP 替身，不需要 API Key。真实 AI 业务验证与用量单独记录在[本轮执行记录](docs/records/focus-location-20261028.md)。

## 本地启动（Windows / PowerShell 7）

需要 JDK 17、Node.js 22 和现有 MySQL 8 安装目录。项目使用 Maven Wrapper 3.9.11，不依赖全局 Maven。实际验证环境为 JDK 17.0.9、Node 22.23.1、npm 10.9.8、MySQL 8.0.26；原设计的 MySQL 8.4 尚未实测。

在 `code` 目录首次配置（根据电脑修改安装路径）：

```powershell
./scripts/Initialize-Local.ps1 -JavaHome 'D:/TOOL/JDK17' -MySqlHome 'C:/Program Files/MySQL/MySQL Server 8.0'
```

初始化会使用现有 MySQL 程序新建本项目独立实例，监听 `127.0.0.1:13306`，创建开发库 `review_workspace` 和测试库 `review_workspace_test`。不会改动现有 MySQL80 Windows 服务。随机本地凭据保存在 Git 忽略的 `.runtime/local.json`；数据库文件与日志位于该配置中 `mysqlRuntimeRoot` 指向的 `%LOCALAPPDATA%/ReviewWorkspace/<项目标识>/mysql`，避开旧版 MySQL 对中文配置路径的兼容问题。重复初始化会保留已有数据和凭据。

分别在两个终端运行：

```powershell
# 终端一：数据库与后端；根据本地配置选择 Java 17
./scripts/Start-Backend.ps1
```

```powershell
# 终端二：前端；首次自动 npm ci
./scripts/Start-Frontend.ps1
```

打开 <http://127.0.0.1:5173>，自行注册账号。后端为 `http://127.0.0.1:18080`，健康检查为 `/api/health`；18080 避开本机已有的 8080 服务。前端通过同源代理访问 `/api`，使用 HttpOnly 会话 Cookie 和 CSRF token。使用时不要混用 `localhost` 与 `127.0.0.1`。

停止时在前后端终端按 Ctrl+C；如需停止项目数据库，执行 `./scripts/Stop-Database.ps1`。脚本先核对进程归属，不停止其他实例，数据文件保留。

`.env.example` 仅说明变量，Spring Boot 不会自动加载根目录 `.env`。本地脚本从被忽略的 JSON 配置注入进程环境，不修改全局 Java 配置。常规启动不读取 API Key。需要 AI 时使用 `./scripts/Start-Backend.ps1 -EnableAi`：优先使用进程环境中的 `DEEPSEEK_API_KEY`，否则从仓库外上一级 `apikey.txt` 读取，仅保存在后端进程内存中，不打印或复制进仓库。默认每个用户每天最多 10 次 AI 尝试（北京时间），重点辅助和阅读答疑共用额度；可通过 `AI_DAILY_REQUEST_LIMIT` 调整，失败和结果未知的尝试也计入额度。该脚本默认允许已验证的 `deepseek-flash` 图片能力，但每次仍须手动勾选附图；设置 `$env:AI_VISION_ENABLED='false'` 可关闭。直接运行 JAR 时需自行提供 AI 与图片开关，默认均关闭。停止会取消本地等待并丢弃迟到答案，已经发出的请求仍可能计费；只有主动重试会产生新请求。

课程详情中切换“复习重点”，输入一条重点或按行批量预览后保存。文字提取完成后自动建立 Lucene 中文索引；“定位相关页”只检索本人当前课程已就绪的课件，最多检索 20 个候选并展示 5 个结果，不把检索分数伪装为匹配百分比。可选 AI 只接收当前重点和候选页文字片段，返回的页面标识与连续原文引文必须通过后端校验；不可用时明确回退关键词结果。图片课件不做 OCR，语义改写和复杂公式仍可能漏检，需结合原文确认或手动关联。批量拆分当前使用换行规则，尚未接入 AI 拆分。

已确认/排除的位置会在重新定位时保留；编辑重点或课件文字更新后提示重新核对。删除课件后关联只保留历史来源记录，无法继续打开原件；删除课程会清理重点与关联。索引保存在忽略的 `.runtime/index`，丢失后可从数据库重建；备份仍须包含数据库与原件存储。AI 审计只保存状态、请求指纹、模型与用量，不保存完整提示词或密钥。

课件预览需要本机 LibreOffice。启动后端前设置 `$env:CONVERTER_PATH='C:/Program Files/LibreOffice/program/soffice.exe'`；Windows 使用 `soffice.exe`，Linux 可用 `/usr/bin/libreoffice`。本机已有 `.runtime/tools/libreoffice/program/soffice.exe` 时脚本自动使用，不随源码分发。未配置转换程序时，上传及文字提取仍可进行，预览明确提示失败，配置后可以重试。

上传支持 `.pptx`，每份最多 **20 MiB、300 页**；旧 `.ppt`、加密、损坏、带宏或外部链接资源的文件会被拒绝。隐藏页和空白页保留原始页号；转换后校验页数与可提取的逐页文字锚点，再发布 PDF、图片和缩略图。阅读器提供上下页、键盘左右键、缩略图、页码跳转和续读。静态预览不播放动画、音视频；图片和公式不做 OCR。字体替换及复杂排版仍需用原件核对。

开发文件存于忽略的 `.runtime/data`，测试接口文件使用系统临时目录 `zhijian-material-integration`。文件没有公开静态目录，每次下载均检查登录和课程归属。删除立即停止访问，后台移除文件和阅读数据；页面显示未完成清理及重试入口。备份时须同时保存数据库与存储目录。

## 验证与构建

先停止前端开发服务器，避免 Windows 正在使用 `esbuild.exe` 时干净安装失败。如果运行的是打包后的 JAR，也先停止它，避免 Windows 阻止构建时替换文件。然后运行：

```powershell
./scripts/Test-Local.ps1
```

命令运行 Maven `verify`，随后 `npm ci`、Vue 类型检查和生产构建。覆盖真实 MySQL 接口、课件处理、中文索引、重点定位竞态、人工关联保留及本地 HTTP 的 AI 引文校验/额度/降级。原生转换子项需要 `CONVERTER_PATH`，缺少时跳过且计入 skipped；CI 显式安装原生转换器执行该项。接口故障测试使用转换替身，不能替代真实转换测试。测试 profile 使用独立测试库和随机临时索引，不写开发课程；随机测试记录会累积，不清空已有数据库。实际数量和结果见本轮记录。

GitHub Actions 使用 Ubuntu 24.04 的原生 MySQL、LibreOffice Impress 和字体执行检查，不使用容器，不需要 DeepSeek Key。[CI 工作流](.github/workflows/verify.yml)的远程结果以 [Actions](https://github.com/05020408/codingdemo/actions) 的真实运行状态为准。

PPT 技术探针独立于产品接口，结果只写入被忽略的 `.runtime/probes`：

```powershell
./scripts/Test-Ppt.ps1 -SourcePath '../上课课件/0 课程简介.pptx'
# 可选：传入本机现有 LibreOffice 的 soffice.exe，验证 PDF 页数及文字锚点
./scripts/Test-Ppt.ps1 -SourcePath '../上课课件/0 课程简介.pptx' -ConverterPath 'C:/Program Files/LibreOffice/program/soffice.exe'
```

探针仍用于独立排查；产品接口的本轮执行结果见[课件阅读记录](docs/records/material-reader-20261027.md)。真实上传了 5 页专用样本、8 页及 145 页教师课件，仅在本地保存，没有发送到 GitHub 或 AI。测试未覆盖所有图表、公式、字体、磁盘满和实际进程被强制终止情形。

## 目录与文档

```text
backend/    Spring Boot、MyBatis、Flyway 迁移及测试
frontend/   Vue 3、TypeScript、Pinia、Vue Router
scripts/    本地初始化、启动、关闭和验证
docs/       需求、设计、Scrum 计划、AI 日志及实际执行记录
.github/    持续集成工作流
.runtime/   本机配置、探针产物与截图（不提交）
```

| 文档 | 用途 |
| --- | --- |
| [需求说明](docs/requirements.md) | 完整产品功能与边界 |
| [产品待办](docs/product-backlog.md) / [Sprint 计划](docs/sprint-plan.md) | 21 条故事、分期与实际进度 |
| [Sprint 1 收尾入口](docs/sprint-1/README.md) | 首轮交付清单、验收汇总、缺陷、证据与团队待补事项 |
| [技术架构](docs/technical-architecture.md) / [数据库](docs/database-design.md) / [接口](docs/api-contract.md) | 目标设计；当前仅落地其中一部分 |
| [课件流程](docs/material-pipeline.md) / [技术验证计划](docs/technical-validation.md) | 提取、映射与失败恢复方案 |
| [质量与验收](docs/quality-and-acceptance.md) | 35 条验收用例及实际执行状态 |
| [本轮执行记录](docs/records/initial-increment-20261027.md) | 环境、测试、探针结果与缺陷回归 |
| [课件阅读执行记录](docs/records/material-reader-20261027.md) | v0.2.0 功能、故障测试、真实上传和浏览器证据 |
| [重点定位执行记录](docs/records/focus-location-20261028.md) | v0.3.0 功能、检索与 AI 校验、竞态测试及浏览器证据 |
| [阅读答疑执行记录](docs/records/reader-ai-20261028.md) | v0.4.0 答疑、识图、真实调用与异常验证 |
| [第一轮界面优化记录](docs/records/ui-round1-20261030.md) | v0.4.1 首页搜索、课程管理、课件状态、47 项回归和响应式截图 |
| [实验过程材料](docs/process-evidence.md) / [AI 使用日志](docs/ai-usage-log.md) | 报告证据、真实活动与人工审阅待办 |
| [开发与版本管理](docs/development-workflow.md) / [变更记录](CHANGELOG.md) | 提交、推送、版本和配置规则 |

截止日期、团队角色、看板与人工评审仍需由团队真实补充；本次编码与自检不替代 Scrum 会议、同伴评审或整个 Sprint 验收。

代码仓库：[05020408/codingdemo](https://github.com/05020408/codingdemo)。
