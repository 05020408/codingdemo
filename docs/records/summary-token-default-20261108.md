# 摘要请求取消应用 token 上限（2026-11-08）

用户原始要求：“不要限制token长度”。本增量针对前一条145页课件摘要失败反馈，移除摘要请求中的应用token预算，不扩展为其他AI场景重写。应用保持0.4.4、deepseek-flash，原生Java/MySQL运行，无Docker；不移动已有版本标签。

## 故障依据与变更

只读检查发现：文字提取145页、无文字页0，计划3个PART加1个FINAL；第一PART失败，审计output_tokens=1800，恰好达到旧分段预算。其余步骤等待，未产出最终Markdown。旧实现只记录非正常完成错误，没有保存具体finish_reason，因此输出截断属于高度吻合的判断，不能追认历史返回必为length。未在证据中保存用户课件正文、账号或凭据。

- SummaryWorker不再传1800/5500；ReaderAiClient的摘要complete入口移除outputLimit与max_tokens字段，未改阅读ask和重点定位请求参数。
- 保留thinking disabled及既有摘要提示词。DeepSeek官方文档说明max_tokens可选，不传时非思考模式默认8K；取消应用参数不等于服务端无限输出。[官方接口文档](https://api-docs.deepseek.com/api/create-chat-completion/)，2026-11-08核对。
- 共享客户端遇finish_reason=length，使用AI_OUTPUT_TRUNCATED提示服务端输出达到上限；其他非stop返回仍拒绝。实际usage先入审计，不发布不完整内容、不自动付费重试。
- 保留输入分段、最多12段、PART结构3000字符、FINAL结构9000字符、最终Markdown12000个UTF-16单元、来源校验、额度、超时与响应体保护。这些边界未因移除token参数而取消。
- 没有修改用户已有任务、重置额度或主动恢复失败版本。旧错误记录保持原样，用户后续明确继续时才会使用新请求。

## 实际验证

使用测试数据库及本地HTTP替身，执行：

```powershell
. ./scripts/Use-LocalEnvironment.ps1 -TestDatabase
./backend/mvnw.cmd -B -ntp -f backend/pom.xml '-Dtest=SummaryIntegrationTest,SummaryContentTest,ReaderChatIntegrationTest' test
```

共34项，失败/错误/跳过均0：摘要集成18、摘要内容3、阅读答疑13。[验证摘要](../evidence/summary-token-default-20261108/verification-summary.json)由本轮Surefire报告提取计数；原始日志位于忽略的.runtime/summary-token-default-20261108-tests.log。后端已重启，健康检查及数据库均UP，前端HTTP 200。

新增两项回归：

1. 长课件两段与最终汇总的3个请求均不含max_tokens；替身上报2200 completion_tokens，任务成功且审计保留2200。这是协议与审计测试，不冒充真实2200-token生成。
2. 单次摘要替身返回finish_reason=length和8192用量，确认失败码明确、无正文/当前版本发布、保留用量、没有自动重试；用户主动恢复后成功。8192是故障夹具，不是本轮真实模型输出。

短课件下载、长材料完整分段、版本快照、取消、显式恢复、来源/并发等相关现有测试同时通过。本轮无付费DeepSeek调用，没有重新发送用户的145页课件，没有新的浏览器或远程CI验证。此前REG-02/03语义问题保持开放；“操作已保存”和失败状态中“覆盖页数”的界面歧义本轮未改。

## 交付

同步摘要文档、README、变更和AI日志USE20261108-02/P-14。验证后重启本机后端加载改动，并按已有授权提交同步GitHub；实际提交和远程状态见Git历史与最终回复。不能以取消请求上限保证所有长课件必然生成成功，提供商默认上限及现有字符/时间等边界仍可能触发。
