# DeepSeek 基础连接验证记录

## 执行信息

| 项目 | 实际记录 |
| --- | --- |
| 日期 | 2026 年 10 月 27 日，Asia/Shanghai |
| 关联验证 | TV05 的基础连接子项，完整 TV05 尚未通过 |
| 执行方式 | Codex 代用户执行一次临时 HTTP 请求；未初始化业务工程 |
| 用户指令 | “我的deepseekapikey已经在本地，你读取一下试试】” |
| 仓库基线 | `5165b61748386ea6fc2743d7f0f0e416548f8da3`，是执行前文档版本，不是已实现的应用版本 |
| 密钥来源 | 项目根目录的 `apikey.txt`，位于 `code` Git 仓库之外；仅在请求进程内存读取 |
| 请求地址 | `https://api.deepseek.com/chat/completions` |
| 请求模型 | `deepseek-flash` |
| 参数 | thinking 关闭，stream=false，max_tokens=8 |
| 测试输入 | `Reply with exactly OK.` |

接口与参数依据[DeepSeek 官方请求说明](https://api-docs.deepseek.com/api/create-chat-completion/)。本次请求没有发送课件、个人资料或项目源码。

## 实际结果

| 指标 | 实际值 |
| --- | --- |
| HTTP 状态 | 200 |
| 返回内容 | `OK` |
| 单次请求耗时 | 约 5.66 秒，不作为性能基准 |
| prompt_tokens | 9 |
| completion_tokens | 1 |
| total_tokens | 10 |

结论：本地文件中的 Key 在此次请求中认证成功，指定模型基础文本调用可用。未查询账户余额，未据 token 数推算费用。

## 范围与后续

本次只验证一轮极短文本请求。结构化 JSON、候选页面重排、流式对话、图片能力、限流、超时恢复、额度控制和应用集成均未验证，不能将完整 TV05 或相关 PB、TC 标记为通过。

没有保存 Key、带凭据的请求头或完整响应到仓库，也没有修改系统环境变量。本记录仅保存固定测试文本、参数和脱敏结果。后续正式应用从后端受控配置读取密钥。
