# 资料与阅读接口契约

对应[开发设计](../MVP开发设计.md)。沿用[通用 HTTP 约定](tasks.md#http)。示例标识均为虚构值，任务号可在手机端查询。

## K01

`TaskId sync(RequestKey key)` 请求扫描已配置目录。手动入口 `POST /index/sync`，定时触发传本轮键。例如 键 s1 → 202、任务 t1。相同键返回原任务，同目录已有扫描则合并。用 `GET /documents` 读文档 ID、名称、更新时间和状态。目录不可达时任务附错误。成功完成的扫描才确认删除。相同版本的重复步骤产生同一组片段。单文件失败能查看原因，其余文件继续。

Markdown 使用 UTF-8。同步和阅读支持每份至多 64 MiB 的资料。

## K02

`TaskId answer(Question question, RequestKey key)`。例如 `POST /questions` 提交“滑动窗口为何用 while？”及键 q1 → t2。空问题返回 400，同键同问题复用任务。完成结果含回答和逐项引用 ID。候选片段不足以支撑结论时返回证据缺口。模型暂不可用时任务显示失败原因，可用新请求键重新提问。

请求体为 `{question}`。完成后用 `GET /answers/{id}` 读取 `{id, question, claims, gap}`，每项 `claims` 含 `text` 和 `citationIds`。

## Retrieval

知识模块公开 `List<Evidence> search(Query text, int limit)`，供 K02 和 E02 调用。例如 `search("窗口收缩",5)` → 命中段落或空列表。每项返回文档 ID、版本、片段 ID、摘文和锚点。Markdown 锚点含标题路径和段落，PDF 锚点含从 1 起的物理页码。仅返回当前可检索版本。空查询或非正上限为输入错误，索引服务异常与零命中分别返回。只读，重复查询以当时有效索引为准。

## K03

`SourceView open(CitationId id)`。例如 `GET /citations/c1` → 文档 d1、第 3 页及摘文。通过 `GET /documents/{id}/content?version=v1` 在助手内打开原文件。同版直接定位。改版后仅在摘文及邻文能唯一匹配时返回新锚点和改版提示。歧义、摘文消失、文件已删除均返回可展示原因。未知引用 404。读取原文时再次核对版本，文件变化则要求重新解析引用。重复预览不写书签。

`GET /documents/{id}/outline?version=v1` 为 Markdown 阅读器返回与原文同版的可渲染区块及锚点。锚点字段为 `{page, heading, paragraph}`，按格式选用；版本变化返回 409。

## K04

`Bookmark access(DocumentId id, BookmarkCommand op)`。读用 `GET /documents/{id}/bookmark`，写用同路径 PUT。例如 写 d1、版本 v1、第 8 页、页内比例 0.5、预期书签修订 2 → 修订 3。Markdown 用段落锚点及段内比例。首次读取返回起始位置。写时校验文档版本、位置与预期修订。同键重试返回已保存结果，其他过期写入返回 409 和最新书签。临时预览不触发写入。恢复时文档改版则尝试唯一锚点匹配，无法定位时提示重新选择。

写请求携带 `Idempotency-Key` 和 `{version, anchor, ratio, revision}`，比例范围为 0 到 1。
