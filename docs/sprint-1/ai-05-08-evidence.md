# Sprint 1 AI 辅助设计编码测试与缺陷分析记录

整理日期：2026 年 10 月 30 日（北京时间）。实际活动发生于 10 月 27—28 日。本文补全 AI-05 至 AI-08，供阶段报告第 3 章及附录引用；首轮功能基线为 `v0.3.0` / `5484c0d7db5675ffea3961d3f5b3ce4eba42b9b6`。

记录依据为[原 AI 使用日志](../ai-usage-log.md)、开发执行记录和 Git 历史。下列设计、编码、测试和缺陷案例是对已有活动的分类展开，不是整理日新增的模型调用。分析说明为回溯摘要，不冒充当时 AI 回答的逐字导出。阅读侧栏 AI 答疑、V5 对话表及其测试不计入首轮。

## 记录与责任说明

开发辅助工具为 Codex，具体底层模型版本未单独留存，不推测填写。DeepSeek 是产品接入的外部服务；首轮的一次真实重点重排调用用于业务验证，不能替代开发 AI 的使用记录。

用户已明确 Java 为主、Vue 3＋Spring Boot、使用 DeepSeek、不使用 Docker、在 `code` 中开发并用 Git/GitHub 管理重要版本，以及平台名为“知笺”。这些是已有人工约束。设计、编码和自检由 Codex 代执行；主要编码责任人为用户，姓名待补。独立人工代码审阅、团队验收人及其意见尚待真实填写。

| 实验类别 | 对应原记录 | 本文展开内容 | 实际日期 |
| --- | --- | --- | --- |
| AI-05 架构与数据库设计 | USE20261027-02 | 架构、数据关系、接口与处理边界，及设计到实现的差异 | 2026-10-27 |
| AI-06 编码辅助 | USE20261027-04、USE20261027-05、USE20261028-01 | 账号课程、上传阅读、重点定位三个增量 | 2026-10-27—28 |
| AI-07 测试用例生成 | USE20261027-01、USE20261027-02，以及上述三个编码记录 | 用例设计、自动化与操作验证、未验证范围 | 2026-10-27—28 |
| AI-08 缺陷分析 | USE20261027-04 及关联启停回归 | INIT-03、INIT-05、S1-ENV-07 三个案例 | 2026-10-27 |

本文中的“代理修订”指 Codex 对自身产出的调整；“人工修改”仅记录有依据的用户约束。未保存针对单一测试或缺陷的独立提示词时，保留其所属轮次的原指令与上下文，不补写成历史原文。

## AI-05 架构与数据库设计

### 使用过程

| 字段 | 实际记录 |
| --- | --- |
| 原记录与日期 | USE20261027-02，2026-10-27；设计提交 `5165b61748386ea6fc2743d7f0f0e416548f8da3` |
| 使用场景 | 将已确认的课程管理、课件上传阅读与重点定位需求转为首轮技术方案，关联 PB01—PB08 及适用 PB20 |
| 提示词原文 | “开始整理”。上下文是上一轮提出的技术架构、数据库、接口和课件处理流程任务；不将这四个字当作完整历史输入 |
| 使用工具 | Codex、文件与 Git 工具、当时的官方资料核对；该设计轮没有真实 DeepSeek 调用 |
| AI 输出 | 技术架构、数据库、API 契约、课件处理流程、技术验证计划五份文档；原设计为 13 张规划表、31 个规划端点 |
| 人工约束与修改 | 采用此前用户确定的 Java、Vue 3＋Spring Boot 和 DeepSeek；“不使用 Docker”在后续初始化时明确并落地。独立人工设计评审和其他修改待填写 |
| 代理修订与取舍 | 采用单仓库、模块化单体；分离业务数据、文件和可重建索引；明确权限、原始页码、任务发布、AI 输出校验和删除边界 |
| 验证方式 | 设计时核对需求、兼容性资料、表和接口编号、JSON 样例及文档链接；没有把资料核对写成业务测试通过 |
| 责任人 | 用户为主要编码责任人，姓名及设计审阅人待确认；本次设计执行者 Codex |
| 采用结论 | 架构用于后续实现；具体落地以迁移、代码和执行记录为准，规划端点数量不表示 31 个端点均已实现 |
| 关联材料 | [架构](../technical-architecture.md)、[数据库](../database-design.md)、[API 契约](../api-contract.md)、[处理流程](../material-pipeline.md)、[技术验证](../technical-validation.md)；原始设计可在[固定提交](https://github.com/05020408/codingdemo/tree/5165b61748386ea6fc2743d7f0f0e416548f8da3/docs)核查 |

### 主要方案与选择理由

| 设计内容 | AI 方案及理由 | 首轮实际采用情况 |
| --- | --- | --- |
| 前后端职责 | Vue 负责课程、阅读和定位交互；Java 后端负责身份、业务、文件、任务与外部接口，便于主要编码者维护 | Vue 3 / Spring Boot，后端 JDK 17；按业务模块组织 |
| 部署结构 | 单个后端实例及其工作线程，降低实验项目的部署和调试负担 | 本机原生 MySQL 与 LibreOffice；不使用 Docker，不增加微服务、Redis 或消息队列 |
| 数据与检索 | MySQL 保存业务事实；文件目录存原件和预览；Lucene 保存可重建的页级索引 | 检索后回查活动状态、归属与修订，索引丢失可重建；Lucene 不替代数据库 |
| 原页映射 | 稳定页面 UUID 与原始页号分开；保留隐藏页、空白页，转换结果校验后才发布 | POI 提取，LibreOffice 转 PDF，PDFBox 校验与渲染；不以页数一致声称版式完全保真 |
| 后台任务 | 数据库任务记录、领取令牌和租约，慢转换在事务外执行，发布前检查来源和生命周期 | 上传阅读与定位增量逐步实现；防止被删除或已过期任务发布旧结果，不承诺外部调用严格只执行一次 |
| AI 定位 | 先检索有限候选，再由 AI 辅助判断，限制可返回的标识并校验原文 | 关键词模式可独立运行；实际实现候选重排，未实现规划中的 AI 同义词扩展或 AI 批量拆分 |
| 数据隔离与删除 | 服务端按用户及课程检查归属，删除后停止访问，避免旧请求或工作者复活数据 | 账号/课程、课件、重点逐步扩展；重点引用的已删页面只留必要墓碑和来源摘要 |

### 数据库落地范围

| 迁移 | 首轮业务表或变更 | 数量 |
| --- | --- | ---: |
| [V1](../../backend/src/main/resources/db/migration/V1__accounts_courses_and_jobs.sql) | app_users、courses、async_jobs、job_attempts、idempotency_requests | 5 |
| [V2](../../backend/src/main/resources/db/migration/V2__materials_and_reader.sql) | materials、file_assets、slides、material_stages、reading_progress | 5 |
| [V3](../../backend/src/main/resources/db/migration/V3__focuses_search_and_ai.sql) | focus_items、focus_slide_links、ai_calls；同时扩展已有页面和阶段字段 | 3 |
| [V4](../../backend/src/main/resources/db/migration/V4__focus_match_order.sql) | 定位结果排序字段，无新增业务表 | 0 |
| 合计 | 不计 Flyway 迁移历史表 | 13 |

原设计目标为 MySQL 8.4，实际开发自检使用 MySQL 8.0.26；不能将目标版本写成已验证环境。V5 的 chat_sessions、chat_turns 属于后续答疑增量。当前设计文档已含后续内容，报告引用首轮时应结合上表及固定 `v0.3.0` 版本。

## AI-06 编码辅助

### 账号与课程增量

| 字段 | 实际记录 |
| --- | --- |
| 原记录与日期 | USE20261027-04，2026-10-27；`v0.1.0` / `70d8bfe517479e35a641e3850e1f8b24f217a981` |
| 场景与提示词 | PB01/PB02 初始化与实现。原文：“现在是不是可以开始进行实现了”“不要使用docker”“‘知笺’这个是平台的名字”；上下文含已确认需求与设计 |
| 工具与 AI 输出 | Codex 通过文件、构建和浏览器工具生成 Vue/Spring Boot 工程、会话认证、课程 CRUD、V1、脚本和 CI；7 项测试及实际记录 |
| 人工修改 | 用户指定无 Docker 和平台名称，已采用；Java/Vue、目录和 Git 管理要求继续沿用。人工代码审阅与修改待填写 |
| 代理修订 | 配置项目级 JDK/Maven，处理 MySQL 中文路径、转换参数、统一参数错误；删除清理保留创建幂等键，避免迟到重放重新创建 |
| 实际验证 | Maven test/verify、前端干净安装/类型检查/构建、真实 MySQL 读写与隔离、打包后重启读回；浏览器登录、创建、刷新、改名及退出 |
| 责任人及结论 | 主要编码责任人用户，姓名与审阅人待补；执行者 Codex。形成可运行初始增量，不等于正式团队验收 |
| 代码与记录 | [认证](../../backend/src/main/java/cn/review/auth/AuthController.java)、[课程](../../backend/src/main/java/cn/review/course/CourseService.java)、[首页](../../frontend/src/views/Courses.vue)、[初始记录](../records/initial-increment-20261027.md)；[历史提交](https://github.com/05020408/codingdemo/commit/70d8bfe517479e35a641e3850e1f8b24f217a981) |

### 课件上传与阅读增量

| 字段 | 实际记录 |
| --- | --- |
| 原记录与日期 | USE20261027-05，2026-10-27；功能提交 `f08a823bc0b2858308c31423dbff538276b20c2e`，发布标签 `v0.2.0` |
| 场景与提示词 | PB03/PB04/PB05 和适用 PB20。原文：“开始实现”；上下文为上一轮提出的课件上传、后台处理和阅读增量 |
| 工具与 AI 输出 | Codex、Maven/npm、原生 MySQL/LibreOffice、浏览器和 Git。生成 V2、上传校验、受控文件接口、处理任务、原页提取/预览、阅读器及续读；本轮未调用 DeepSeek |
| 人工修改 | 沿用 Java、Vue 3/Spring Boot、无 Docker、知笺名称、code 目录与 GitHub 管理；新增人工审阅意见和代码修改待补 |
| 代理修订与取舍 | 阶段分别记录 TEXT/PREVIEW 状态；旧结果发布受任务及来源校验；重试保留成功预览，文件和数据库发布失败有补偿及遗留回收 |
| 实际验证 | 当时 19 项 Maven 测试、前端干净安装/构建；正式接口处理 5/8/145 页样本，413/415、浏览器上传/跳页/续读、正常后端重启持久化 |
| 责任人及结论 | 主要编码责任人用户，姓名与审阅人待补；执行者 Codex。上传阅读链路可运行；当时未实现索引、重点和引用墓碑 |
| 代码与记录 | [课件服务](../../backend/src/main/java/cn/review/material/MaterialService.java)、[处理流水线](../../backend/src/main/java/cn/review/material/MaterialPipeline.java)、[阅读器](../../frontend/src/views/MaterialReader.vue)、[执行记录](../records/material-reader-20261027.md)；[历史提交](https://github.com/05020408/codingdemo/commit/f08a823bc0b2858308c31423dbff538276b20c2e) |

### 重点定位与人工校正增量

| 字段 | 实际记录 |
| --- | --- |
| 原记录与日期 | USE20261028-01，2026-10-28；`v0.3.0` / `5484c0d7db5675ffea3961d3f5b3ce4eba42b9b6` |
| 场景与提示词 | PB06/PB07/PB08、PB04 索引、PB05 跳页及适用 PB20。原文：“开始实现”；上下文为课程重点和定位增量 |
| 工具与 AI 输出 | Codex、Java/Vue、Maven/npm、MySQL/LibreOffice、Git 与浏览器。生成 V3/V4、重点清单、Lucene 索引、定位任务、DeepSeek 校验与额度、人工关联和原页跳转 |
| 人工修改 | 沿用已确认技术与工程约束；独立人工阅读、修改代码及审阅结论待填写 |
| 代理修订与取舍 | 补结果排序和窄屏状态；明确 READ COMMITTED、写锁顺序与乐观版本，补两个客户端同版本编辑回归；批量重点采用规则拆分，AI 定位保持可选 |
| 实际验证 | 初轮 32 项完整检查，补并发后记录为 33 项测试全通过；前端构建、浏览器重点批量/定位/确认/排除/手动关联/跳原页、重启持久化 |
| 产品 AI 验证 | 一次自行构造样本的真实 DeepSeek 重排，原第 1 页及连续原文校验通过，229 输入＋51 输出 tokens；不作为多课程准确率证据 |
| 责任人及结论 | 主要编码责任人用户，姓名与审阅人待补；执行者 Codex。首轮定位主流程具备开发自检，检索质量及正式验收仍需团队确认 |
| 代码与记录 | [重点服务](../../backend/src/main/java/cn/review/focus/FocusService.java)、[定位工作者](../../backend/src/main/java/cn/review/focus/FocusWorker.java)、[页面索引](../../backend/src/main/java/cn/review/search/PageIndex.java)、[重排校验](../../backend/src/main/java/cn/review/search/DeepSeekReranker.java)、[执行记录](../records/focus-location-20261028.md)；[历史提交](https://github.com/05020408/codingdemo/commit/5484c0d7db5675ffea3961d3f5b3ce4eba42b9b6) |

以上源码链接便于当前阅读；文件可能已在后续版本修改。核查某次 AI 产出的范围时，使用对应固定提交查看源码，不能把当前文件整体算作该次生成内容。

## AI-07 测试用例生成

### 从设计到执行

| 字段 | 实际记录 |
| --- | --- |
| 原记录与日期 | USE20261027-01/02 的用例及验证设计，随后 USE20261027-04/05、USE20261028-01 中的测试实现和执行；2026-10-27—28 |
| 场景与提示词 | 初次原文“好的，你先开始整理需要的文档”，技术设计原文“开始整理”；后续随各轮实现指令生成测试。未另存逐条用例专属提问 |
| 工具与 AI 输出 | Codex 生成 35 条全产品验收用例草案、技术验证计划，以及随三个增量落地的 JUnit/集成测试和浏览器检查记录 |
| 人工修改 | 已有技术与功能约束影响测试范围；人工补充的具体用例、预期审核及执行签名待补，不能以代理自测代填 |
| 代理调整 | 将权限、文件边界、原页映射、删除与旧工作者竞态、引文校验、额度和并发版本纳入测试；区分替身、原生转换和真实外部调用 |
| 实际验证 | 真实 MySQL 集成测试、POI/索引单元测试、原生 LibreOffice 转换、本地 HTTP 模型替身；另有正式 HTTP 与浏览器操作，分别见三份执行记录 |
| 责任人 | 主要编码责任人用户，姓名待补；测试代执行 Codex，独立测试与验收负责人待指定 |
| 结论 | 已有适用测试的开发执行证据；35 条是全产品设计用例数，不能标为 35 条全部通过，亦不等于首轮 33 个自动化测试方法 |
| 关联材料 | [全产品用例](../quality-and-acceptance.md)、[首轮验收矩阵](acceptance-report.md)、[技术验证计划](../technical-validation.md)、[后续回归统计](../evidence/sprint-1/later-regression-summary.json) |

### 代表性用例与代码对应

以下预期和测试方法依据已提交代码整理，实际执行结论沿用原日期的执行记录；此表不是 10 月 30 日新一轮运行结果。

| 场景与关联 | 关键输入或操作与预期 | 可核查方法及历史结果 |
| --- | --- | --- |
| 登录与 CSRF，TC01 | 未登录读取拒绝；缺 token 写请求拒绝；登录换 sessionId，退出后拒绝 | [AccountCourseIntegrationTest](../../backend/src/test/java/cn/review/AccountCourseIntegrationTest.java)：authenticationAndCsrfBoundaries；初始及后续增量记录通过 |
| 课程隔离与版本，TC02/03 | 他人不可读改删，旧版本更新冲突，新会话仍读到已保存内容 | 同类：coursesPersistAndArePrivateWithOptimisticUpdates；有真实 MySQL 断言 |
| 创建与删除幂等，TC03、适用 TC33 | 同键同内容返回原 ID，不同内容冲突；删除完成后旧创建请求返回 404，不新增课程 | 同类：duplicateCreationAndDeletionAreIdempotentAndScrubPersonalText；关联 INIT-05 |
| 文件边界，TC06/07 | 超过 20 MiB 返回 413；错误媒体类型 415；ZIP 越界/外部资源拒绝 | [MaterialIntegrationTest](../../backend/src/test/java/cn/review/MaterialIntegrationTest.java)、[MaterialValidationTest](../../backend/src/test/java/cn/review/MaterialValidationTest.java)及阅读记录中的正式 HTTP；恰好上限的有效文件仍待专项验证 |
| 原页映射，TC08 | 隐藏/空白页保留，页数或文字锚点不符不得发布 | [PreviewConverterTest](../../backend/src/test/java/cn/review/PreviewConverterTest.java)：nativeConverterPreservesHiddenAndBlankPages、wrongPageMappingIsRejectedBeforePublishing；原生转换子项实际执行 |
| 未就绪与无匹配，TC09/14 | 索引未就绪不冒充无匹配，丢失索引后允许重建 | [FocusIntegrationTest](../../backend/src/test/java/cn/review/focus/FocusIntegrationTest.java)：noMatchIsDifferentFromNotReadyAndLostIndexCanRebuild |
| AI 输出校验，TC14 | 伪 ID、伪造引文、截断/超大响应拒绝，无自动付费重试；定位可回退关键词 | [DeepSeekRerankerTest](../../backend/src/test/java/cn/review/search/DeepSeekRerankerTest.java)：inventedIdOrQuoteFailsClosedAndQuotaCountsFailures 等；FocusIntegrationTest：aiFailureFallsBackToKeywordAndPendingTextManualLinkIsSafe；故障来自本地替身 |
| 并发、旧任务与人工校正，TC12/15、S1-TC33A | 同版本编辑仅一个成功；修改/删除来源后旧结果不得发布；重定位保留人工判断 | FocusIntegrationTest：simultaneousEditsWithSameVersionCannotLoseAnUpdate、lateResultCannotOverwriteEditedFocusOrChangedSources、locateConfirmRejectManualAndRerunPreserveHumanDecisions |

### 测试结果与范围

历史自动化总数依次为 v0.1.0 的 7 项、v0.2.0 的 19 项、v0.3.0 记录中的 33 项，均为各阶段总量，不累加。首轮 33 项为 AccountCourseIntegrationTest 5、MaterialIntegrationTest 7、MaterialValidationTest 2、PptTextExtractorTest 2、PreviewConverterTest 2、StorageReconcilerTest 1、FocusIntegrationTest 8、PageIndexTest 2、DeepSeekRerankerTest 4。

10 月 30 日另从 `70d8bfe`、`f08a823`、`5484c0d` 的已提交测试源码逐个核对 `@Test` 方法数，分别为 7、19、33，与历史记录一致。这是源码数量核对，不是重新执行测试，也不能独立证明历史执行通过。

v0.3.0 的执行记录为最终 33 项、0 失败/错误/跳过，但最后构建曾因运行中 JAR 被占用而失败，停止后以 `-DskipTests verify` 重打包；重打包不算重新执行测试。现存 `verify-v030-final.log` 没有完整 33 项汇总，`package-v030.log` 为空，不能独立作为完整成功证据。后续 v0.4.0 报告为 46 项，其中 13 项是答疑测试；已归档的统计仅作后续回归补充，不能冒充首轮原报告。

浏览器验证、原生转换、替身故障注入和一次真实 DeepSeek 请求分别支持不同结论。未测的整进程强杀、真实提供商长超时、全部字体/公式保真等仍列在[验收汇总](acceptance-report.md)。没有测量代码覆盖率或定位准确率，不填写示例百分比。

## AI-08 缺陷分析

三个案例均发生于 2026 年 10 月 27 日，执行工具为 Codex、文件/Git、相关构建或运行命令。INIT-03/05 属于 USE20261027-04 的初始实现和自检，上下文指令见 AI-06；S1-ENV-07 是该轮关联的启停回归。未单独保存逐个缺陷的提问和完整 AI 推理，以下按真实问题与修复记录归纳分析过程。

### INIT-03 隐藏页导出缺失

| 字段 | 实际记录 |
| --- | --- |
| 发现与影响 | 自制 5 页 PPTX 含隐藏页，初次导出少一页，原页与 PDF 映射不成立；探针主动抛错，不认可该映射 |
| AI 分析摘要 | 排查重点为隐藏页导出设置在 Windows 进程参数传递时是否生效。现有记录未保留全部初始假设和原始命令行，不补写排查时间或未证实原因 |
| 确认原因 | 原执行记录确认 LibreOffice JSON 过滤参数在 Windows 传递时引号丢失，影响隐藏页导出 |
| 修复措施 | 对 Windows 参数中的引号增加转义，显式设置 ExportHiddenSlides=true、ExportNotesPages=false；使用独立参数启动转换进程 |
| 验证步骤与结果 | 重新转换专用样本，5 页对应 5 页，隐藏页位置正确；后续原生转换测试覆盖隐藏/空白页，错误映射拒绝发布 |
| 人工参与与责任人 | 用户为主要编码责任人，姓名待补；AI 代分析、修改和回归。独立人工复现、代码审阅与关闭意见待补 |
| 结论与限制 | 开发回归已有；不据此声称所有字体、公式或图表均保真 |
| 证据与提交 | [初始记录](../records/initial-increment-20261027.md)、[原始探针](../../backend/src/test/java/cn/review/PptConversionProbe.java)、[产品转换器](../../backend/src/main/java/cn/review/material/PreviewConverter.java)、[原生测试](../../backend/src/test/java/cn/review/PreviewConverterTest.java)。初始处理包含于 `70d8bfe`，产品化代码在后续上传阅读增量；没有独立的 INIT-03 修复提交 |

### INIT-05 删除后创建请求重放风险

| 字段 | 实际记录 |
| --- | --- |
| 发现与影响 | 代理自检发现：若清理课程时移除创建幂等键，迟到的原创建请求可能被当作新请求处理；这是代码审查发现的潜在缺陷，不记为线上用户事故 |
| AI 分析摘要 | 创建去重依赖请求键与哈希，物理删除这些信息会失去“该创建已发生并被删除”的判断依据；只保留原响应又可能留下课程个人文本 |
| 确认依据 | 原记录明确保留键/哈希并清理响应文本；已提交集成用例同时断言重放 404、课程记录不增加及缓存不含原课程文字 |
| 修复措施 | 保留幂等键与请求哈希，擦除响应中的课程文本；已删除课程不能因旧创建请求重新创建或返回旧内容 |
| 验证步骤与结果 | 创建及同键重放→删除并执行清理→检查文本已清除→再次用原创建键重放，返回 404；该用户课程记录总数仍为 1（保留已删除行），没有新增 |
| 人工参与与责任人 | 用户为主要编码责任人，姓名待补；Codex 代执行审查、修改及集成验证。独立人工审阅与关闭意见待补 |
| 结论与限制 | 对该重放路径有开发回归，不将它扩大为所有后续业务的删除验收结论 |
| 证据与提交 | [初始记录](../records/initial-increment-20261027.md)、[任务和清理实现](../../backend/src/main/java/cn/review/job/JobService.java)、[课程实现](../../backend/src/main/java/cn/review/course/CourseService.java)、[AccountCourseIntegrationTest](../../backend/src/test/java/cn/review/AccountCourseIntegrationTest.java) 的 duplicateCreationAndDeletionAreIdempotentAndScrubPersonalText；包含于 `70d8bfe`，无独立问题修复 SHA |

### S1-ENV-07 数据库关闭后立即重启失败

| 字段 | 实际记录 |
| --- | --- |
| 发现与影响 | 实际启停回归中，mysqladmin shutdown 已返回，随即启动项目数据库却报 ibdata1 不可写，影响本地启动可靠性 |
| AI 分析摘要 | 核对项目数据库进程归属及退出时序，区分关闭命令返回与 mysqld 进程实际退出；不能仅凭“不可写”断言是文件权限问题 |
| 确认原因 | 原执行记录及独立修复提交确认：关闭命令返回早于原进程退出和数据文件锁释放 |
| 修复措施 | 先核对监听端口进程的项目配置路径，取得该进程句柄；shutdown 后执行 WaitForExit(30000)，超时则明确报错，不立即宣称已停止 |
| 验证步骤与结果 | 原日期真实连续两轮启动/停止成功；既有 Windows MySQL80 服务一直为 Running |
| 人工参与与责任人 | 用户为主要编码责任人，姓名待补；Codex 代分析、修改和启停验证。独立人工环境复核与关闭意见待补 |
| 结论与范围 | 项目启动工具的环境时序问题；不是 MySQL 数据损坏结论，也不改变应用功能版本范围 |
| 证据与提交 | [停止脚本](../../scripts/Stop-Database.ps1)、[初始记录末尾](../records/initial-increment-20261027.md)；独立修复 `54cade4436d765c6822b4b941a9122026dae04e6` / `v0.1.1`，见[提交差异](https://github.com/05020408/codingdemo/commit/54cade4436d765c6822b4b941a9122026dae04e6) |

其他首轮问题继续维护在[缺陷登记](defect-register.md)。以上三个案例均有开发处理和回归依据，但尚无团队独立关闭签署；不另外虚构平台 Issue 编号、严重程度或节省工时。

## 人工核验填写

完成技术记录后，实际参与者只需在核验时补充下表。若没有独立修改，应据实写“审阅后未修改”并附核验内容；尚未审阅则保持待确认。核验日期填写实际发生日期，不回填为开发日期。

| 类别 | 本次已整理 | 仍需本人填写 | 当前人工状态 |
| --- | --- | --- | --- |
| AI-05 | 设计输入、方案、取舍、原始提交及首轮落地差异 | 责任人姓名、设计审阅范围、采纳/调整意见及核验日期 | 待确认 |
| AI-06 | 三个增量的输入、代码范围、代理修订、提交和验证 | 实际阅读或修改的文件、人工修改理由；无修改也须据实注明 | 待确认 |
| AI-07 | 设计用例与已执行结果对应、测试数量、证据限制 | 人工补充用例、预期核对或实际验收结果、执行人和日期 | 待确认 |
| AI-08 | 三个缺陷的现象、分析、原因、修复及回归证据 | 实际复核案例、审阅意见与关闭/保留结论、确认人和日期 | 待确认 |

本次只补齐 AI-05 至 AI-08 的历史技术材料，不新增业务功能、付费模型调用或历史测试执行；正式 Sprint 验收继续按[文档入口](README.md)安排。

整理核验：已核对本次四份新增或修改文档中的 73 处本地文件链接、三个历史提交的测试方法数、首轮 V1—V4 的 13 张业务表及后续 46 项报告的范围，并检查 Git 差异格式。核验不包含人工代码审阅或新一轮业务验收。
