# 个人学习助手产品调研

2026 年 11 月 24 日 · 讨论稿

目标是让你带着自己的教材、论文和笔记，在不同设备上持续学习。打开助手时，你可以找到上次卡住的地方，从原文得到帮助，并留下可在下次使用的学习记录。邮箱可以带来新材料，也可用于向老师或同学请教。

本次依据是上游 GitHub 仓库、官方文档和一条公开用户反馈。文中的产品设想尚未在个人设备或真实邮箱上联调。

## DeepSeek Harness 如何应对需求变化

DeepSeek 用插件组织模型、工具、会话、存储、调度和界面。会话模块将事件追加到日志，持久化由存储插件提供。审批模块把授权限定在当前请求，并记录请求与结果。官方将其标为开发者预览，基础 API 仍在迭代。[GitHub 架构说明](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md) · [会话模块](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/session/README.md) · [审批模块](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/interaction/user-approval/README.md) · [官方产品页](https://www.deepseek.com/harness/zh/)

适合借鉴的是能力有清晰职责。例如，从读论文的「直接解释」切换到算法题的「先给一级提示」，改变的是教学方式。换模型时，同一份资料、提问、引用和作答记录可以用于对比。直接基于 Harness 扩展是否划算，可用小样检验。

## 能查原文，也能看见自己学到哪里

Khoj 可以从 Obsidian 定期同步笔记，并支持将回答限定到个人资料。仓库里一位有数百篇笔记的用户反馈，同步完成后依然难以判断某一篇是否已建立索引。因此，除了看搜索结果，还值得让用户查看一份资料的最近索引状态。[Obsidian 集成](https://github.com/khoj-ai/khoj/blob/master/documentation/docs/clients/obsidian.md) · [聊天文档](https://docs.khoj.dev/features/chat/) · [用户反馈 #1363](https://github.com/khoj-ai/khoj/issues/1363)

Google 的 Gemini Notebook 支持选择本次回答使用的来源，点引用可跳到原段。ChatGPT 学习模式使用逐步提示、理解检查和针对作答的反馈。前者帮助读者核对证据，后者帮助学习者知道下一步可以尝试什么。[引用交互](https://support.google.com/gemininotebook/answer/16179559) · [学习模式设计](https://openai.com/index/chatgpt-study-mode/)

可用一篇 PDF 和一份 Markdown 笔记检验这两种体验。助手应能区分论文陈述、个人理解和为解释而构造的例子。点击引用可打开页或段落。改动、改名、删除资料后，再问同一问题，观察索引是否跟上原文。原文改版时，旧回答应能指明引用版本或显示内容已变化。

## 邮件、手机和跨工具接续

Inbox Zero 把自动生成的回复放进邮箱草稿箱。其外部上下文功能可依据发件人和当前邮件往来检索相关信息。OpenClaw 由常驻 Gateway 接受请求，客户端取得运行编号和进度。其文档也说明，部分网页草稿仅存于内存中，刷新可能丢失。[Inbox Zero 仓库](https://github.com/elie222/inbox-zero) · [草稿设置](https://docs.getinboxzero.com/essentials/assistant-settings) · [邮件上下文](https://docs.getinboxzero.com/essentials/context-integrations) · [OpenClaw 网关](https://docs.openclaw.ai/concepts/architecture.md) · [网页状态说明](https://docs.openclaw.ai/web/control-ui.md)

对本项目而言，任务、当前步骤和已保存的草稿应由后台持有。手机可发起、跟进、编辑、确认；关闭桌面聊天窗口后，后台仍在可用的主机上运行。手机重新进入时，应能恢复已经保存的草稿。

收件和发件需要分开验证。多次同步同一来信只应创建一个任务，同一往来的另一封新信可以更新任务。确认发送时绑定收件人、主题、正文和附件版本，编辑后重新确认。重复点击和重试应核对已有发送记录；如果邮箱可能已接受邮件而系统未收到回执，应标记待核对。Gmail 的增量同步游标可作为收件端的具体参考，实际接入方式取决于用户邮箱。[Gmail 同步机制](https://developers.google.com/workspace/gmail/api/guides/sync)

## 用一个学习场景检验产品

1. 导师或同学发来阅读材料。助手关联此前往来中的阅读要求，建立待学任务。也可直接从手机选择自己的资料开始学习。
2. 阅读 ECCT 时，手机同时展示论文对校验矩阵或掩码的说明，以及自己已有的 LDPC 笔记，两者分别标明出处。
3. 助手给出一个小矩阵，让你自己解释其中一个位置为何允许交互。根据你的回答给反馈，在学习记录中保存「哪一步需要提示」，并允许你纠正这条记录。
4. 下次打开后可以继续未解决的问题。如果你想向发件人请教，助手结合原邮件和具体疑问生成草稿，等你编辑并确认后发送。
5. 如果你提出「需要提示才答出来的题，标成待独立复做」，保存这条偏好。换题、换会话验证它能否生效。

## 值得继续讨论的决定

| 决定 | 检验问题 |
|---|---|
| 默认打开体验 | 先看到上次的卡点，还是先选资料开始新问题？这决定手机首页长什么样。 |
| 学习记忆的粒度 | 保存资料位置、卡点和一次作答表现是否已经够用？长期掌握程度需要什么证据？ |
| 运行框架 | 给 Harness 扩展一个最小资料检索工具，与独立实现同一小样比较。能否准确跳回原文、重启后续学、拒绝发送已过期的草稿版本？ |

建议先讨论「学到一半离开后，下次应该接着做什么」。这个决定会影响索引需要提供哪些信息，也会影响后台保存哪些任务事实和学习记录。

