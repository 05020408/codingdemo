# 最终汇总默认输出预算修复（2026-11-09）

用户再次提供截图：“AI输出达到服务端长度上限”。承接此前“不要限制token长度”，基于ee6ca5f落实接口允许最大预算，避免省略参数落入默认8K。应用保持0.4.4、deepseek-flash、非思考模式，原生运行，无Docker，不移动稳定标签。

## 已确认的失败位置

只读审计确认现有145页材料v2：3个PART均SUCCEEDED，输出分别3867、3783、1832 tokens；FINAL输入9206、输出8192，报AI_OUTPUT_TRUNCATED。三个中间结果完整保存，用户页面今日剩余额度5。以上4次是用户在上轮修复后主动继续产生的已有调用，不是本次Codex新测试，不能计入本轮付费请求。

根据现有客户端，只有finish_reason=length才产生该错误码。DeepSeek官方[Chat Completions参数](https://api-docs.deepseek.com/api/create-chat-completion/)说明：非思考模式不传max_tokens默认8K，可设置的最大值为393216（384K），2026-11-09核对。本次可确认最终汇总撞到默认预算，而不是原3000字符规则或前三段再次失败。先前替身仅检查参数缺席，未模拟提供商默认8K，是此前验证的不足。

## 变更

- 仅摘要complete请求显式传max_tokens=393216。该值表示允许的最大输出预算，不要求模型生成这么长；不改模型、不改提示词，审计仍summary-v2，请求指纹反映参数差异。
- 摘要请求超时60秒改10分钟，响应接收256KiB改8MiB，以容纳较长的最终JSON。保留非重定向、连接超时、响应保护和取消检查；每10秒心跳持续延长90秒租约。
- 阅读答疑仍2400 tokens、60秒、256KiB，定位参数不变。没有撤销JSON/真实来源、输入/数据库/答疑上下文校验。
- 页面提示“重新生成预计4次”与“已完成3/4，剩余1步”分别表示新任务与恢复任务。保留成功PART，用户继续时仅排队未成功FINAL；不自动付费重试。
- 没有重置额度、替换用户中间结果、创建新用户版本或主动恢复用户任务。提供商仍有有限上限，不能承诺任意长内容无限输出或保证本次真实汇总已成功。

## 实际验证

```powershell
. ./scripts/Use-LocalEnvironment.ps1 -TestDatabase
./backend/mvnw.cmd -B -ntp -f backend/pom.xml '-Dtest=SummaryIntegrationTest,SummaryContentTest,ReaderChatIntegrationTest' test
```

本地HTTP替身与测试数据库40项通过，失败/错误/跳过均0：摘要集成21、摘要内容6、阅读答疑13。原始日志忽略于.runtime/summary-final-budget-20261109-tests.log。[验证摘要](../evidence/summary-final-budget-20261109/verification-summary.json)由本轮Surefire报告提取计数。

- 更新请求回归：替身模拟不传max_tokens默认8192，超过预算报length；当前三个摘要请求均393216，模拟12000用量成功入审计。该用量是夹具值，不是真实生成12000 tokens。
- 新增FINAL恢复回归：合成长材料3个PART成功，FINAL模拟8192截断；无自动后续请求，主动恢复同一幂等键两次后只产生一次新的FINAL，PART结果原样、调用数由4增至5、最终成功。
- 新增长响应回归：本地HTTP返回超过旧256KiB的有效摘要，完整发布和UTF-8下载一致。该用例验证更大响应可接收，未声称已做8MiB满载或真实长时间服务压测。
- 既有取消、租约、来源、版本、非法引用、显式恢复及共享阅读答疑相关测试同时通过。10分钟等待通过代码核对，未实际等待10分钟或制造真实提供商超时。

前端vue-tsc/Vite构建通过，70模块。浏览器只读核对用户现有页：显示已完成3/4、剩余1步，区别重新生成预计4次与恢复步骤，未点击生成/继续。截图保留在忽略的.runtime，不将教师材料页面提交GitHub。没有本轮手机、跨浏览器、远程CI或真实FINAL重试结果。

无新付费DeepSeek请求、未重发用户课件。无活动任务时重启后端，后端/数据库健康UP，前端HTTP200。同步功能文档、README、变更、过程与AI日志USE20261109-02/P-16，按既有授权正常提交推送；实际提交与远程状态见Git历史和最终回复。
