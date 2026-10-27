# 首轮接口契约

设计基线日期：2026 年 10 月 27 日；实现进度更新于 10 月 28 日。以下 31 个编号端点在 v0.3.0 已落地，另提供健康、清理及重点能力查询。API24 当前采用规则拆分预览，尚未调用 AI 拆分。实际响应及边界见[重点定位记录](records/focus-location-20261028.md)。

本轮增加 `GET /api/cleanup-jobs`，返回本人尚未完成或失败的清理任务（最多 20 条），以及 `POST /api/jobs/{id}/retries`，携带 Idempotency-Key，以 202 重试本人失败的 COURSE_PURGE/MATERIAL_PURGE。普通处理重试仍走 API15。

实际 DTO 以源码为准：CourseDTO 暂无 counts；上传 jobs 为 `{jobId,type}` 列表；删除与处理重试返回 `{id,status}`；MaterialDTO 的 `stages` 是 TEXT、PREVIEW、INDEX、QUESTION_GENERATION 的对象映射。阶段含 status、available、publishedRevision、requestedRevision、jobId、error；失败信息为安全的 code/message/retryable。canLocate 根据已发布索引、文字修订和索引文件可用性计算；QUESTION_GENERATION 仍为 NOT_APPLICABLE。SlideDTO 有 previewAvailable、previewUrl、thumbnailUrl，文字修订独立于预览修订。JobDTO 的 progress/error 可为空，清理重试不重置 attemptCount。

v0.3.0 增加 `GET /api/courses/{id}/focus-capabilities`，返回 aiAvailable、readyMaterials、totalMaterials、maxBatchSize。API28 接受可选 `useAi`（默认 false），返回 `{jobId,status}`，AI 故障不会自动重发，而在重点的 warnings 中明确说明关键词回退。重点 DTO 增加 method、warnings、errorCode、errorMessage；人工关联响应为 `{link,focusVersion}`，关联列表为 `{matchStatus,items,warnings,focusVersion}`。链接项包含 linkId、slideId、materialId/materialName、pageNo、title、origin、decision、needsReview、sourceAvailable、previewAvailable、evidenceText、reason、version；来源快照不可用时不提供可打开状态。

## 1 通用协议

- 路径统一以 `/api` 开头；普通请求和响应为 UTF-8 JSON，上传为 multipart，文件内容使用对应媒体类型。
- ID 为 UUID 字符串，时间为 UTC ISO 8601；页面号从 1 开始。示例 ID 只是协议样例，不是实际数据。
- 列表使用 page、size，默认 1、20，size 最大 100；响应包含 items、page、size、total。排序字段由服务端白名单限定。
- 数据访问使用会话 Cookie，所有写请求携带 `/auth/csrf` 返回的头名与 token；不允许客户端指定 ownerId。
- 业务创建、批量保存、上传、定位、重试、拆分预览和删除使用 `Idempotency-Key`。登录、退出不依赖业务幂等表；PATCH 和关联 PUT 依赖版本检查，阅读位置 PUT 本身为覆盖式操作。
- 修改元数据携带 expectedVersion；旧版本返回 409 VERSION_CONFLICT，不覆盖更新。删除使用同名查询参数。关联更新分别检查重点和已有链接版本。
- 响应带 requestId 便于诊断；不返回文件系统路径、密钥、密码哈希或内部堆栈。受保护 JSON 和文件均使用 `Cache-Control: no-store`。

### 成功与失败格式

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "id": "32a423c9-2342-4d1a-8514-674188e3a061",
    "name": "软件过程",
    "version": 1
  },
  "requestId": "8e1d643a-f636-4cc5-a435-2273ee35d48b"
}
```

```json
{
  "code": "VALIDATION_FAILED",
  "message": "课程名称不能为空",
  "data": null,
  "details": [{"field": "name", "reason": "不能为空"}],
  "requestId": "d61f07d6-d2f9-4436-8cb9-e99630b7115e"
}
```

创建通常返回 201，后台任务返回 202 且 code 为 ACCEPTED，普通成功为 200，无内容成功为 204 且没有 JSON。安全过滤器的错误也转换为该错误格式，不能给前端返回登录 HTML。

### 幂等重放

同一用户、操作、资源和请求键的规范化请求摘要一致时，返回首次完成结果；相同键不同请求返回 409 IDEMPOTENCY_CONFLICT。原请求仍进行时返回 409 REQUEST_IN_PROGRESS，并提供受控重试提示。已知已创建任务的请求返回原 taskId，不再次调用外部服务。

处理重放前仍检查当前归属和删除状态，不能通过旧响应读回已删数据。删除操作的重复请求可返回原清理任务的安全状态。上传摘要包含文件 SHA256，文件输入仍需接受大小和内容校验。

## 2 接口清单

| 编号 | 方法与路径 | 用途与核心参数 | 成功响应 |
| --- | --- | --- | --- |
| API01 | GET `/api/auth/csrf` | 匿名或登录用户获取 CSRF token | 200，headerName、token |
| API02 | POST `/api/auth/register` | username、password、可选 displayName | 201，用户摘要；不自动登录 |
| API03 | POST `/api/auth/login` | username、password | 200，用户摘要并建立会话 |
| API04 | POST `/api/auth/logout` | 退出当前会话 | 204 |
| API05 | GET `/api/auth/me` | 当前登录身份 | 200，用户摘要；匿名 401 |
| API06 | GET `/api/courses` | page、size | 200，课程分页列表 |
| API07 | POST `/api/courses` | name、可选 remark；幂等键 | 201，CourseDTO |
| API08 | GET `/api/courses/{courseId}` | 本人课程详情和当前已支持的计数 | 200，CourseDTO 与 counts |
| API09 | PATCH `/api/courses/{courseId}` | name 或 remark、expectedVersion | 200，最新 CourseDTO |
| API10 | DELETE `/api/courses/{courseId}` | expectedVersion 查询参数；幂等键 | 202，清理任务与不可再访问状态 |
| API11 | GET `/api/courses/{courseId}/materials` | page、size | 200，课件摘要与阶段分页 |
| API12 | POST `/api/courses/{courseId}/materials` | multipart 的 file；幂等键 | 202，materialId、初始任务和状态 URL |
| API13 | GET `/api/materials/{materialId}` | 课件元数据、阶段、能力与原件入口 | 200，MaterialDTO |
| API14 | DELETE `/api/materials/{materialId}` | expectedVersion 查询参数；幂等键 | 202，课件清理任务 |
| API15 | POST `/api/materials/{materialId}/processing-retries` | stage、expectedVersion；幂等键 | 202，新的任务或已存在活动任务 |
| API16 | GET `/api/materials/{materialId}/slides` | page、size，按原始 pageNo 升序 | 200，SlideSummaryDTO 分页 |
| API17 | GET `/api/slides/{slideId}` | 本人页面文字及预览入口 | 200，SlideDTO |
| API18 | GET `/api/assets/{assetId}/content` | 受控原件、PDF、图片或缩略图，disposition 可选 | 200，二进制内容 |
| API19 | GET `/api/materials/{materialId}/reading-progress` | 当前用户上次阅读位置 | 200，lastPageNo 可为 null |
| API20 | PUT `/api/materials/{materialId}/reading-progress` | lastPageNo | 200，已保存位置和时间 |
| API21 | GET `/api/courses/{courseId}/focuses` | page、size，可选 masteryStatus、matchStatus | 200，FocusDTO 分页 |
| API22 | POST `/api/courses/{courseId}/focuses` | content、可选 originalInput；幂等键 | 201，FocusDTO |
| API23 | POST `/api/courses/{courseId}/focuses/batch` | items、可选 originalInput；幂等键 | 201，全部已保存重点 |
| API24 | POST `/api/courses/{courseId}/focus-split-previews` | text；幂等键 | 200，拆分建议与 warnings，不写重点表 |
| API25 | GET `/api/focuses/{focusId}` | 重点正文、修订与掌握状态 | 200，FocusDTO |
| API26 | PATCH `/api/focuses/{focusId}` | content 或 masteryStatus、expectedVersion | 200，最新 FocusDTO |
| API27 | DELETE `/api/focuses/{focusId}` | expectedVersion 查询参数；幂等键 | 204，关联取消，后续历史不连带删除 |
| API28 | POST `/api/focuses/{focusId}/locate` | expectedVersion；幂等键 | 202，定位 jobId 和固定的 contentRevision |
| API29 | GET `/api/focuses/{focusId}/matches` | 默认返回未排除结果，可选 includeRejected | 200，matchStatus、items、warnings |
| API30 | PUT `/api/focuses/{focusId}/matches/{slideId}` | decision、expectedFocusVersion、已有链接的 expectedLinkVersion | 200，最新链接与重点版本 |
| API31 | GET `/api/jobs/{jobId}` | 当前用户任务的安全状态 | 200，JobDTO |

API01 至 API04 允许匿名进入安全处理链，但写请求仍需 CSRF；其余要求登录。所有路径中的资源均由服务端验证归属。API31 对已删课程仅允许所有者查询其清理任务的脱敏状态，普通历史任务不再可见。

## 3 数据对象

### CourseDTO 与 counts

CourseDTO 包含 id、name、remark、status、version、createdAt、updatedAt。课程处于 DELETING 或 DELETED 时普通详情和列表不再返回。

首轮 counts 仅返回已实现的 materialCount、focusCount、locatedFocusCount、masteredFocusCount。统计有效来源时排除已删除或需核对的链接；没有数据时计数为 0。尚未开发的练习统计不返回假 0 成绩，而是暂不暴露对应字段。

### MaterialDTO 与阶段

MaterialDTO 包含 id、courseId、displayName、sizeBytes、contentVersion、slideCount、version、mappingStatus、stages、capabilities、originalDownloadUrl 和时间。原文件名只用于展示，URL 由后端构造。

```json
{
  "id": "6e447424-4f1b-4413-9a03-756bd4414d84",
  "courseId": "32a423c9-2342-4d1a-8514-674188e3a061",
  "displayName": "敏捷过程.pptx",
  "contentVersion": 1,
  "slideCount": 145,
  "version": 1,
  "mappingStatus": "PENDING",
  "stages": {
    "TEXT": {"status": "SUCCEEDED", "available": true, "publishedRevision": 1},
    "PREVIEW": {"status": "RUNNING", "available": false, "publishedRevision": null},
    "INDEX": {"status": "SUCCEEDED", "available": true, "publishedRevision": 1},
    "QUESTION_GENERATION": {"status": "NOT_APPLICABLE", "available": false, "publishedRevision": null}
  },
  "capabilities": {"canDownload": true, "canReadPreview": false, "canLocate": true}
}
```

该对象仅示例 text/index 已完成而 preview 尚未就绪的状态，不是实际 145 页课件已通过处理。完整阶段对象还含 jobId、requestedRevision、error 和可重试标记。

### SlideDTO

包含 id、materialId、pageNo、isHidden、title、text、textRevision、previewAvailable、previewUrl、thumbnailUrl。预览未发布时 URL 为 null；跳页仍使用原 pageNo，不将 PDF 页序当作原页号。

### FocusDTO 与匹配结果

FocusDTO 包含 id、courseId、content、originalInput、contentRevision、masteryStatus、matchStatus、version、lastLocateJobId 和时间。masteryStatus 与 matchStatus 的枚举以数据库设计为准。

匹配项包含 linkId、slideId、materialId、materialName、pageNo、origin、decision、matchedFocusRevision、needsReview、sourceAvailable、previewAvailable、evidenceText、reason、version。自动候选不伪装成人工确认，sourceAvailable=false 时不能继续访问来源。

### JobDTO

包含 id、type、status、targetId、attemptCount、maxAttempts、progress、resultSummary、error、createdAt 和 finishedAt。progress 只在已知工作单位时返回 done、total，未知时为 null。错误只返回安全 code、message、retryable；不提供内部路径和完整调用参数。

## 4 输入边界

| 输入 | 设计边界 |
| --- | --- |
| 账号 | 3 至 32 个 ASCII 字母、数字或下划线，规范化后唯一 |
| 显示名 | 1 至 50 字符 |
| 密码 | 8 至 64 字符，所选编码器的字节上限在实现时补充并前后端一致 |
| 课程名与备注 | 名称 trim 后 1 至 100 字符；备注最多 1000 字符 |
| 重点 | trim 后 1 至 2000 字符；批量最多 50 条，原始输入最多 10000 字符 |
| 拆分预览 | text 非空且最多 10000 字符；超出返回校验错误，不静默截掉重点 |
| 上传 | 首期 PPTX，单文件上限 20 MiB，即 20971520 字节；整体 multipart 上限留出协议开销 |
| 处理重试 | stage 仅支持 TEXT、PREVIEW、INDEX；源需 ACTIVE，且对应依赖已满足 |
| 关联操作 | decision 仅 CONFIRMED 或 REJECTED；新手动关联需同课程有效页面 |
| 阅读位置 | 必须在已知原始 slideCount 范围内，不提交未验证的页码 |

字符长度按 Unicode 码点口径，密码另做编码器字节校验；若数据库和前端计数实现不同必须在边界测试中校准。上传媒体类型和扩展名不替代内容校验。

## 5 关键请求示例

### 上传接受结果

API12 返回 202，其 data 结构为：

```json
{
  "materialId": "6e447424-4f1b-4413-9a03-756bd4414d84",
  "statusUrl": "/api/materials/6e447424-4f1b-4413-9a03-756bd4414d84",
  "jobs": [
    {"jobId": "db843377-1ddb-4585-acd2-602933dc6755", "type": "TEXT_EXTRACT"},
    {"jobId": "ea24ce4d-7f5d-479c-9794-af7bdeecb10c", "type": "PREVIEW_RENDER"}
  ]
}
```

前端初始每 2 秒查询状态，长时间未变化后退避，最大间隔建议 10 秒；切离页面停止轮询，重新进入从服务端恢复。GET 不创建或恢复任务。

### 保存重点与发起定位

API22 请求：

```json
{"content": "Scrum 的三个角色及其职责"}
```

API28 请求：

```json
{"expectedVersion": 1}
```

服务端固定当前 contentRevision、可检索范围及来源文字和索引修订，返回 jobId。相同正文已有活动定位任务时返回该任务，不排队运行多个同目标请求。定位成功后通过 API29 读取结果；定位返回 NO_MATCH 是业务结果，不作为系统异常。部分资料未就绪通过 warnings 说明，全部未就绪为 PROCESSING_NOT_READY，发布前来源修订变化为 SOURCE_CHANGED。

### 确认或手动关联

API30 请求：

```json
{
  "decision": "CONFIRMED",
  "expectedFocusVersion": 2,
  "expectedLinkVersion": 1
}
```

新手动链接的 expectedLinkVersion 为 null，由服务端设置 origin=MANUAL；已有链接保留实际来源。操作在一个事务中更新链接和重点版本，重新确认时记录当前正文修订。已删除来源不能确认，返回 SOURCE_UNAVAILABLE；排除已有候选不影响其他关联。

## 6 状态与错误

| HTTP | 业务码示例 | 前端行为 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED、INVALID_PAGE | 显示字段或范围错误，保留输入 |
| 401 | UNAUTHENTICATED | 清理身份状态，引导登录，不自动重放付费请求 |
| 403 | CSRF_INVALID | 刷新 token，说明状态；只有确认请求未执行时才安全重试 |
| 404 | RESOURCE_NOT_FOUND | 不泄露资源是否属于他人，提示不可用 |
| 409 | VERSION_CONFLICT、IDEMPOTENCY_CONFLICT、REQUEST_IN_PROGRESS | 保留编辑，提示刷新或等待，不覆盖服务器版本 |
| 409 | PROCESSING_NOT_READY、SOURCE_UNAVAILABLE、SOURCE_CHANGED | 展示依赖或来源变化，不强行进入错误页面 |
| 413 | FILE_TOO_LARGE | 显示当前大小上限 |
| 415 | UNSUPPORTED_FILE_TYPE | 说明支持格式与转换建议 |
| 422 | INVALID_DOCUMENT、NO_EXTRACTABLE_TEXT | 说明文件内容问题；扫描件不伪称可识别 |
| 429 | AI_QUOTA_EXCEEDED、RATE_LIMITED | 显示受控重试提示，不循环调用 |
| 503 | AI_NOT_CONFIGURED、AI_UNAVAILABLE、STORAGE_UNAVAILABLE | 保留非相关功能，提示服务状态 |
| 504 | AI_TIMEOUT | 保留输入，状态不确定时先对账，避免重复收费 |

后台异常通常表现为 API31 返回 200 且任务 status=FAILED，error 内提供业务码；不是所有任务失败都让查询接口返回 500。失败阶段和已可用能力分别展示。

## 7 权限与删除竞态

列表按当前用户课程查询；对象接口重新检查所属课程。直接访问资产同时核验课程、引用该资产的课件状态及资产 READY 状态，不仅验证“知道 assetId”。

删除必须先有明确的前端确认，再提交带 expectedVersion 的请求。服务端逻辑删除事务完成即可返回 202，普通读取随后返回 404；物理清理进度只通过本人清理任务查询。旧后台任务即使外部调用已返回，也不得重新公开数据。

## 8 首轮联调顺序

API01 至 API10 → API12、API13 与 API31 → API16 至 API20 → API21 至 API30 → 删除、重试和跨用户异常回归。AI 预览 API24 是可选增强；未配置 AI 时手工保存重点、批量录入和基础检索仍可用。

后续 AI 对话、笔记、习题与作答接口在对应故事精化时补充，不把当前列表误称为全部产品 API。
