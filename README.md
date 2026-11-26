# 个人学习助手

在手机和电脑上问自己的资料、核对原文、续读并处理邮件。项目以 [MVP 需求](个人学习助手-MVP需求文档.md)和[接口与验收](docs/接口与验收.md)为准。

## 构建

使用 Java 21、Maven 3.9 和 Node.js 22.13 或更新的 LTS 版本。从项目根目录运行。

```sh
npm --prefix frontend ci
npm --prefix frontend run build
mvn package
```

产物 `target/learning-assistant-0.1.0.jar` 包含网页。

## 运行

1. 准备启用 pgvector 的 PostgreSQL，或设置 `ASSISTANT_DB_PASSWORD` 后运行 `docker compose up -d postgres`。应用启动时通过 Flyway 建表。
2. 在启动进程的环境中设置 `ASSISTANT_PASSWORD`、资料目录 `ASSISTANT_DOCUMENTS`、数据库和模型配置。变量见 [.env.example](.env.example)。直接启动 JAR 时由系统或服务管理器注入这些变量。
3. 使用邮件功能时配置 IMAP 和 SMTP。465 端口通常使用隐式 TLS。使用 587 和 STARTTLS 时设置 `ASSISTANT_SMTP_SSL=false`、`ASSISTANT_SMTP_STARTTLS=true`。只有服务商会保留 SMTP 发件副本时才填写 `ASSISTANT_IMAP_SENT_FOLDER` 供结果核对。
4. 运行 `java -jar target/learning-assistant-0.1.0.jar`。应用默认监听 `127.0.0.1:8080`。用 HTTPS 反向代理转发至该地址，电脑和手机打开同一 HTTPS 地址并登录。本机 HTTP 开发时可临时设置 `ASSISTANT_SECURE_COOKIE=false`。

登录后从「资料库」点击「同步资料」，或等待后台定时扫描。改用新嵌入模型时同步资料，完成后启用新索引。定期备份资料和数据库，草稿附件的字节保存在数据库中。

## 验收

设置 `ASSISTANT_TEST_DB_URL`、`ASSISTANT_TEST_DB_USER` 和 `ASSISTANT_TEST_DB_PASSWORD` 指向专用测试数据库，再运行 `mvn verify`。测试使用临时资料、可控模型和本地测试邮箱。浏览器组合用例需先构建网页，安装 Chromium（`npx playwright install chromium`，在 `frontend` 目录执行），再设置 `ASSISTANT_BROWSER_E2E=1`。也可通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE` 指定本机 Chromium 路径。

[需求与测试映射](docs/requirements-map.yaml)
