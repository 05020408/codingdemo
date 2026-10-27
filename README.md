# 知笺 · 期末复习工作台

帮助学生按课程整理课件，把复习重点定位到 PPT 原页，并逐步加入阅读答疑、笔记和习题。平台名称由用户于 2026 年 10 月 27 日确定为“知笺”。

当前版本 `0.1.0` 已实现注册、登录、退出及个人课程的创建、首页展示、编辑和异步删除。课程真实保存到 MySQL，刷新、重登仍可读取。课件上传、在线阅读、重点定位、AI 答疑、笔记和三种习题来源仍在开发计划中，当前没有这些业务入口。

项目直接在本机运行，**不使用 Docker**。DeepSeek 基础连接此前已验证，当前应用 AI 默认关闭，本次开发与常规测试没有付费请求。

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

`.env.example` 仅说明变量，Spring Boot 不会自动加载根目录 `.env`。本地脚本从被忽略的 JSON 配置注入进程环境；不修改全局 Java 配置，不读取或打印仓库外的 API Key。

## 验证与构建

先停止前端开发服务器，避免 Windows 正在使用 `esbuild.exe` 时干净安装失败，然后运行：

```powershell
./scripts/Test-Local.ps1
```

命令运行 Maven `verify`（7 项测试：5 项真实 MySQL 集成测试、2 项 PPT 提取测试），随后 `npm ci`、Vue 类型检查和生产构建。测试 profile 默认使用独立测试库，不写开发课程。测试账号使用随机名称，不清空已有数据库；测试库会累积测试记录，可单独归档处理。

GitHub Actions 使用 Ubuntu 24.04 自带的原生 MySQL 服务执行检查，不使用容器，不需要 DeepSeek Key。[CI 工作流](.github/workflows/verify.yml)的远程结果以 [Actions](https://github.com/05020408/codingdemo/actions) 的真实运行状态为准。

PPT 技术探针独立于产品接口，结果只写入被忽略的 `.runtime/probes`：

```powershell
./scripts/Test-Ppt.ps1 -SourcePath '../上课课件/0 课程简介.pptx'
# 可选：传入本机现有 LibreOffice 的 soffice.exe，验证 PDF 页数及文字锚点
./scripts/Test-Ppt.ps1 -SourcePath '../上课课件/0 课程简介.pptx' -ConverterPath 'C:/Program Files/LibreOffice/program/soffice.exe'
```

本机本轮另在 `.runtime/tools/libreoffice` 准备了用于探针的归档解压版本，不会随 Git 分发，也不是新克隆仓库的前置条件。探针未覆盖全部图表、公式、字体、资源上限、超时故障和后台发布流程，不能据此宣称课件模块完成。

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
| [技术架构](docs/technical-architecture.md) / [数据库](docs/database-design.md) / [接口](docs/api-contract.md) | 目标设计；当前仅落地其中一部分 |
| [课件流程](docs/material-pipeline.md) / [技术验证计划](docs/technical-validation.md) | 提取、映射与失败恢复方案 |
| [质量与验收](docs/quality-and-acceptance.md) | 35 条验收用例及实际执行状态 |
| [本轮执行记录](docs/records/initial-increment-20261027.md) | 环境、测试、探针结果与缺陷回归 |
| [实验过程材料](docs/process-evidence.md) / [AI 使用日志](docs/ai-usage-log.md) | 报告证据、真实活动与人工审阅待办 |
| [开发与版本管理](docs/development-workflow.md) / [变更记录](CHANGELOG.md) | 提交、推送、版本和配置规则 |

截止日期、团队角色、看板与人工评审仍需由团队真实补充；本次编码与自检不替代 Scrum 会议、同伴评审或整个 Sprint 验收。

代码仓库：[05020408/codingdemo](https://github.com/05020408/codingdemo)。
