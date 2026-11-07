# 2026-11-07 Flash 回归证据

对应[执行记录](../../records/full-regression-20261107.md)，应用从0.4.3修正为0.4.4。所有截图仅包含自建测试账号及合成课件，没有教师资料、Key或密码。功能链路复测通过仍保留 REG-02/03 两项语义问题；不替代团队验收。

| 文件 | 内容 |
| --- | --- |
| [verification-summary.json](verification-summary.json) | 68项JUnit分组、24组初次及复测、9次应用AI审计和一次诊断、布局尺寸、样本哈希及未测范围 |
| [focus-diagnostic.json](focus-diagnostic.json) | 独立诊断复现同页两条合法引文，不是原始失败请求的逐字响应 |
| [long-summary.md](long-summary.md) | 浏览器实际下载的36页合成课件最终v1摘要，仅去除行尾空格和规范换行，保留REG-02 |
| [focus-desktop.png](focus-desktop.png) | Flash原文引文、确认状态、旧来源已删除、新来源可跳转 |
| [reader-desktop.png](reader-desktop.png) | 第2页纯图片、150%缩放、已保存位置及答疑侧栏；放大内容在课件区域内滚动 |
| [history-desktop.png](history-desktop.png) | 三轮真实问答、图片依据、摘要v1和已删除来源 |
| [summary-desktop.png](summary-desktop.png) | 36页主题摘要、版本及共享额度 |
| [summary-mobile.png](summary-mobile.png) | 390×844摘要布局 |
| [reader-mobile.png](reader-mobile.png) | 320×740阅读及第21页来源 |
| [chat-mobile.png](chat-mobile.png) | 320×740答疑、保留草稿和主动选择摘要；没有实际发送该草稿 |

运行日志、账号凭据和一次性探针不归档；本目录JSON不含Cookie、请求头、Key、私有数据库配置。UI截图与下载正文是本次真实操作产物，不冒充性能或语义准确率评估。
