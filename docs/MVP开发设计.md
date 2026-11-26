# 个人学习助手 · MVP 开发设计

v0.4 · 2026-11-26 · MVP 实现。依据[需求文档](../个人学习助手-MVP需求文档.md)与[开发约束](../AGENTS-draft.md)。[验收用例](接口与验收.md)按相同编号追踪。

## 运行与数据边界

Java 21、Maven、Spring Boot 模块化单体。Spring MVC 提供 REST，Spring Security 校验会话与 CSRF 令牌。两端经 HTTPS 访问。响应式网页用 Vue 3、TypeScript、Vite、Vue Router、Axios、Element Plus。PDF.js 和 markdown-it 展示原文。

原文留在指定目录，凭编号读取并校验真实路径。PostgreSQL 保存业务状态，MyBatis-Plus 读写业务表，pgvector 保存向量。Spring AI 调用嵌入和对话模型，输入限于本次任务的往来与片段。邮箱和模型凭据由部署环境注入。db-scheduler 在 Java 应用中执行持久化任务。

## 业务入口规划

路径相对 `src/main/java/assistant/`。[需求映射](requirements-map.yaml)登记入口和自动化验收用例。

| 需求 | 入口文件与方法 | 持久数据、职责与失败处理 |
|---|---|---|
| [K01](api/knowledge.md#k01) | `knowledge/SyncDocuments.java#sync` | 文档有稳定 ID，摘要标识版本。完整扫描后确认删除，目录失联保留记录。改名按摘要唯一匹配。提取失败记原因，扫描件可读。 |
| [K02](api/knowledge.md#k02) | `knowledge/AnswerQuestion.java#answer` | 任务保存问题、回答和引用。RAG 用中文双字词项和英文术语做关键词检索，与语义向量结果按排名融合。仅接受本次召回的引用，缺依据则说明缺口。 |
| [K03](api/knowledge.md#k03) | `reader/OpenCitation.java#open` | 引用含文档 ID、版本、锚点、原句和邻文。改版后在同文档唯一匹配才跳转，否则显示歧义、未找到或已删除。 |
| [K04](api/knowledge.md#k04) | `reader/ReadingPosition.java#access` | 按用户和文档保存主动阅读的位置、文档版本及书签修订号。过时写入返回冲突，引用预览只读。 |
| [E01](api/mail.md#e01) | `mail/SyncMailbox.java#sync` | Angus Mail 通过 IMAP 收信。账号、文件夹、UIDVALIDITY、UID 唯一，来信与游标同事务写入。游标失效重扫核对邮件标识和指纹，按回复头归入往来。 |
| [E02](api/mail.md#e02) | `draft/ReplyDraft.java#manage` | 用户选信后才建拟稿任务。保存草稿修订、固定附件及内部引文。新来信标记上下文有更新，编辑冲突返回服务器版本。 |
| [E03](api/mail.md#e03) | `delivery/SendDraft.java#confirm` | 确认时锁定草稿、冻结外发快照并建立唯一发送记录。JavaMailSender 经 SMTP 发信。发出前持久化发送中及 Message-ID。中断按发送契约核对。 |
| [M01](api/tasks.md#m01) | `task/OpenTask.java#query` | 保存任务号、步骤、状态、结果和错误。手机经相同 API 发起操作并轮询进度。服务重启后从已完成步骤恢复。 |

## 共享能力与变更范围

HTTP／定时器 → 业务入口 → 所属模块的公开能力 → 存储或外部适配器。后台编排调用步骤。各入口之间没有调用边。问答与拟稿共用知识模块的[片段检索](api/knowledge.md#retrieval)，拟稿还读取邮件模块的[往来历史](api/mail.md#history)。耗时操作交给任务模块的[持久调度](api/tasks.md#jobs)。C01 由手机串联这些接口，按端到端用例验收。

Markdown 按标题和段落切分，PDFBox 按物理页取文。新片段和向量按文档版本暂存，发布前校验该版仍是目标版本，同事务切换有效索引。已发现变化且未完成重建的资料显示更新中，检索排除过期片段。更换嵌入模型时按新模型重建整套索引，再切换查询模型与索引。

Caffeine 以嵌入配置和查询文本缓存查询向量。每次检索仍从 PostgreSQL 读取当前有效片段。任务队列可携带 W3C 追踪上下文，工作线程执行时恢复；旧任务的空上下文仍可执行。

增加格式时扩展解析器，复测引用与续读。目录读取成为热点时，Redis 可缓存带版本的结果。OCR 需要独立队列时，RabbitMQ 消费者可复用处理函数，以同事务待投递记录衔接业务提交。首版只部署应用与 PostgreSQL。

备份资料、数据库及附件快照。恢复后核对书签、草稿、发送记录并演练重建索引。记录检索、模型耗时与任务等待时间。
