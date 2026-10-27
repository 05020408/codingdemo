# 知笺首个可运行增量执行记录

日期：2026 年 10 月 27 日。执行者：Codex 代理；人类编码责任人与团队审阅者姓名待补充。本记录描述实际编码与自检，不等同团队计划会议、同伴评审或 Sprint 完成。

## 范围与变更

用户授权开始实现，并明确“不使用 Docker”；开发过程中进一步确定平台名称为“知笺”。本轮在 `feature/project-init` 实现 Vue 3 + Spring Boot 基础工程与 PB01、PB02 的账号和课程主流程。首个软件版本标记为 `v0.1.0`，提交以 Git 历史中该标签对应 SHA 为准。

- 注册、大小写统一的账号唯一性、密码 BCrypt 哈希、登录、退出、会话恢复及 CSRF。
- 课程首页、真实空状态、创建弹窗、分页列表、详情、编辑与删除确认；保存成功才显示正式结果。
- 服务端归属检查、乐观版本冲突、相同请求键重放与不同内容冲突、并发创建去重。
- 删除后立即不可见，数据库工作线程清理当前版本的课程文本及幂等响应中的课程文本；保留删除标记、请求哈希和删除任务，防止延迟重试重新创建已删除课程。
- V1 迁移实际创建五张业务表：用户、课程、任务、任务尝试、幂等请求。
- 本机启动/停止/测试脚本、锁文件、Maven Wrapper 与无容器 CI 配置。
- 独立 PPT 文字和转换探针，未提供上传、在线阅读或检索接口。

完整异步任务的跨进程租约恢复、次数上限与失败重试尚未落地。当前删除仅执行一个数据库事务，不进行外部文件或 AI 工作；相关模块开放前必须补齐。AI 开关默认关闭，无新增付费调用。

## 实际环境与隔离

| 部分 | 本次实际结果 |
| --- | --- |
| 操作系统与脚本 | Windows，PowerShell 7 |
| Java | JDK 17.0.9，项目脚本临时设置进程 JAVA_HOME；全局 java 仍可能是 Java 8 |
| Maven | Wrapper 3.9.11；本机全局 Maven 3.6.1 未修改 |
| 前端 | Node 22.23.1、npm 10.9.8、Vue 3.5.44、Vite 7.3.7、TypeScript 5.9.3 |
| 后端 | Spring Boot 3.5.16、MyBatis Starter 3.0.5、Flyway |
| 数据库 | 使用本机 MySQL 8.0.26 程序新建项目实例；13306，UTF8MB4，UTC |
| 端口 | 前端 5173、后端 18080；既有 8080 与 MySQL80 服务不变 |
| 测试数据 | 独立 review_workspace_test；开发库 review_workspace；不清空既有表 |
| 文件与凭据 | .runtime 忽略；数据库数据根在 LocalAppData；Key 仍在仓库外 |
| PPT 处理 | POI 5.5.1、PDFBox 3.0.6、LibreOffice 26.8.1.1（本地探针工具） |

MySQL 8.4 是原设计目标，本轮未安装或验证。GitHub CI 将使用官方 Ubuntu 24.04 runner 自带原生 MySQL，具体镜像版本以运行日志为准。[官方 runner 软件清单](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)

## 自动化和操作结果

实际执行 Maven `test`、`verify`，7 项测试全部通过，无失败、无跳过。前端 `npm ci`、`vue-tsc -b`、`vite build` 成功。打包后的 JAR 也实际启动成功，重新登录仍读到重启前保存的课程；构建产物不提交 Git。

| 测试方法 | 主要验证 |
| --- | --- |
| authenticationAndCsrfBoundaries | 未登录 401、缺 CSRF 403、有效注册登录、登录换 sessionId、大小写重复账号 409、错误密码 401、超长密码 400、哈希与响应脱敏、退出后拒绝 |
| coursesPersistAndArePrivateWithOptimisticUpdates | 课程创建与列表、另账号看不到/不能读改删、编辑版本递增、旧版本 409、新会话读取同一数据库记录 |
| duplicateCreationAndDeletionAreIdempotentAndScrubPersonalText | 创建重放与内容冲突、删除立即隐藏、删除重放同任务、工作线程实际完成、任务隔离、敏感课程文本清理、删除后旧创建键不会复活课程 |
| invalidFieldsHaveStableClientErrors | 空白名、超长名、缺请求标识、错误分页、缺删除版本参数均为 400 |
| concurrentDuplicateSubmissionCreatesOneRecord | 两个并发同键创建返回相同 ID，数据库仅一条课程 |
| preservesOriginalSequenceHiddenAndEmptySlidesAndNestedText | 自制 PPT 的页序、隐藏页、空白页、组合与表格文字，重提取稳定的原始 slideId |
| damagedContentCannotBeReportedAsSuccessful | 损坏输入抛出异常 |

浏览器实际操作：登录 → 真实空列表 → 添加“软件工程”课程 → 刷新仍存在 → 进入详情 → 修改为“软件工程 · 期末复习” → 返回首页显示修改 → 退出返回登录页。注册和课程删除通过接口/集成测试验证，没有在浏览器执行不可恢复删除。检查默认窄屏与 1280×850 桌面布局，未发现明显内容遮挡。截图只包含合成预览账号，存放本机 `.runtime/screenshots/zhijian-desktop.png`、`zhijian-narrow.png`；不包含真实密码和用户课件。

前端断网输入保留、全部表单边界、浏览器删除确认的完整提交、无障碍专项与不同浏览器兼容性尚未全面验证。远程 CI 不由本地通过结果替代，以 [Actions 真实运行](https://github.com/05020408/codingdemo/actions) 为准；无通过结论时不写成功徽章。

## PPT 技术探针

按 [POI XSLF](https://poi.apache.org/components/slideshow/xslf-cookbook.html) 提取原页序、原文件 slideId、隐藏属性及文字；递归组合形状，遍历表格，不读取备注、OCR 或图表底层数据。原始 slideId 只是文件内标识，后续数据库页面 UUID 仍需独立设计。PDF 使用 [LibreOffice 导出参数](https://help.libreoffice.org/latest/en-US/text/shared/guide/pdf_params.html)显式导出隐藏页、关闭备注页，按任务隔离 profile。

| 样本 | SHA-256 | 提取 | 转换与检查 |
| --- | --- | --- | --- |
| 0 课程简介.pptx | 9c62ee0393bc8619f190403c1b9b8390a75127df36a50849e4e1dc307ed38a8c | 8 页，8 页非空，隐藏 0；约 763 ms | PDF 8 页，逐页首行最多 12 字符锚点均匹配；转换及校验约 13,370 ms |
| 第三章_软件开发过程_敏捷过程.pptx | 55baf186ccedca2630c1957062059bccb22503feff764d4b4ae077c4c450b7e8 | 145 页，145 页非空，隐藏 0；约 1,093 ms | PDF 145 页，逐页首行锚点均匹配；转换及校验约 19,357 ms |
| 自制 mapping-fixture.pptx | 由探针生成，未提交二进制样本 | 普通、隐藏、空白、重复文字、纯图片共 5 页 | 修复参数后 PDF 5 页，隐藏 1，非空文字页锚点均匹配；约 10,799 ms |

探针渲染短样本 1/5/8、长样本 1/73/145、专用样本 1/3/5。代理实际查看专用样本第 5 页、短样本第 5 页、长样本第 73 页三张图片：图像可读，真实页脚分别为 5、73，纯图片保留且无假 OCR。没有逐页人工核对全部真实课件，首行锚点也不能证明所有图表、公式、重复页和版式完全正确。

教师课件、提取正文、PDF 和图片均只在被忽略目录内，未上传仓库或 AI。可通过 README 的 Test-Ppt 命令重新提取；转换程序需要本机可用路径，不随源码分发。当前还没有数据库映射发布、索引、文件受控接口或超时恢复验收，不能将 TV02/TV03 整项、PB03–PB05 标为完成。

## 实际发现与修复

| 编号 | 现象、处理和回归 |
| --- | --- |
| INIT-01 | Java PATH 为 8、全局 Maven 3.6.1。改用项目 Java 17 环境和 Maven 3.9.11 Wrapper，编译、测试与打包通过，未改全局设置。 |
| INIT-02 | MySQL 8.0.26 无法读中文路径下的 defaults-file。仅把项目独立数据/配置根放到 LocalAppData 的 ASCII 项目标识目录，真实迁移与读写成功。 |
| INIT-03 | Windows 传递 LibreOffice JSON 参数时引号丢失，隐藏页专用样本导出少一页，探针主动抛错拒绝认可映射。增加 Windows 引号转义，专用样本回归为 5→5，隐藏页在正确原页位置。 |
| INIT-04 | Vite 正运行时 npm ci 删除 esbuild.exe 被 Windows 文件占用拒绝。停止项目预览再干净安装构建成功；Test-Local 增加提前提示，避免误判为源码失败。 |
| INIT-05 | 自检发现删除清理若移除创建请求键，会允许延迟重放再创建课程。保留键/哈希，只擦除响应里的课程文本，集成测试验证旧请求返回 404 且不新增课程。 |
| INIT-06 | 缺少 expectedVersion 查询参数最初未纳入统一 400。补充异常映射并回归；登录密码超出 BCrypt 字节边界也提前返回 400。 |

依赖输出还有 commons-logging 发现提示、自制组合样本的 POI shapeId 警告；本轮未据此宣称已处理所有兼容性提示。真实课件提取与测试没有因此失败，后续完善样本与依赖时复核。

LibreOffice 准备曾被自动审批拒绝：`msiexec` 行政提取命令返回“blocked by policy”，没有更具体原因。未执行安装器；后改为对官方签名有效的 MSI 只读解析文件表，用 7-Zip 提取归档并在项目工具目录组装文件，无安装动作、注册表修改或 MSI 自定义操作，版本命令及上述转换实际成功。该处理不作为完整安装/字体兼容性的证明。

## 尚需完成

1. PB03/PB04 上传校验、资产与材料迁移、转换后台任务、映射验证及安全文件访问。
2. PB05 阅读器、PB06 重点清单、PB07 课程内检索及可验证来源跳转。
3. 当前删除流程扩展到文件、索引、课件与学习数据，并补齐持久任务故障恢复。
4. AI 适配器及结构/故障验证，之后接入答疑、笔记和三种习题来源。
5. 团队真实估算、分工、看板、评审和报告取证；本轮自检不代填团队活动。
