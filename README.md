# 个人学习助手

把 Markdown 和 PDF 放进资料目录，就可以在网页里向资料提问、点开引用核对原文，以及在电脑和手机上接着读。也可以接入邮箱，根据来信和资料起草回复，确认后发送。

## 本地运行

准备 JDK 21 或更高版本、Maven 3.9、Node.js 22.13 或更新的 LTS 版本，以及启用了 pgvector 的 PostgreSQL。下面的命令在项目根目录的 PowerShell 中运行。构建前用 `mvn -v` 确认 Maven 使用的是 JDK 21 或更高版本。

### 1. 配置应用和数据库

```powershell
$env:ASSISTANT_PASSWORD = '替换为你的登录密码'
$env:ASSISTANT_DOCUMENTS = 'D:\学习资料'
$env:ASSISTANT_DB_PASSWORD = '替换为你的数据库密码'
$env:ASSISTANT_MODEL_KEY = '替换为你的模型 API 密钥'
$env:ASSISTANT_SECURE_COOKIE = 'false'

docker compose up -d postgres
```

把 `ASSISTANT_DOCUMENTS` 改为实际存放资料的目录。已有 pgvector 数据库时，可跳过 Docker 命令，并设置 `ASSISTANT_DB_URL`、`ASSISTANT_DB_USER` 和 `ASSISTANT_DB_PASSWORD` 指向该数据库。应用启动时会自动建表。

其他环境变量见 [`.env.example`](.env.example)。直接运行 JAR 时，Java 进程从启动它的环境中读取这些变量。

### 2. 构建并启动

在同一个 PowerShell 窗口中运行。

```powershell
npm --prefix frontend ci
npm --prefix frontend run build
mvn package
java -jar target/learning-assistant-0.1.0.jar
```

### 3. 登录并导入资料

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)，用默认用户名 `learner` 和你设置的密码登录。在「资料库」点击「同步资料」，等索引完成后就可以在「问我的资料」里提问。

## 接入邮箱

按 [`.env.example`](.env.example) 配置 IMAP、SMTP 和发件地址 `ASSISTANT_MAIL_FROM`，重启后在「邮件」里点击「收取新邮件」。使用 SMTP 587 端口和 STARTTLS 时，设置 `ASSISTANT_SMTP_SSL=false` 和 `ASSISTANT_SMTP_STARTTLS=true`。如果邮箱服务商会保存 SMTP 发件副本，可用 `ASSISTANT_IMAP_SENT_FOLDER` 指定发件箱，用于核对发送结果。

## 从手机访问

用 HTTPS 反向代理把访问请求转发到 `127.0.0.1:8080`，并将应用的 `ASSISTANT_SECURE_COOKIE` 恢复为 `true`。电脑和手机打开同一个 HTTPS 地址登录，即可继续上次的阅读进度。

## 项目文档

- [需求说明](个人学习助手-MVP需求文档.md)
- [开发设计](docs/MVP开发设计.md)
