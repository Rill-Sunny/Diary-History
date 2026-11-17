# 部署说明

## 运行依赖

- JDK 17+、Node 20.19+。仓库包含 Maven Wrapper，首次运行 `mvnw.cmd` 需联网下载 Maven 3.9.16；系统已安装 Maven 3.9+ 时也可直接调用 `mvn`。
- MySQL 8.x 独立数据库；schema 使用 `utf8mb4`。
- 持久化磁盘目录用于 `APP_MEDIA_ROOT`。
- SMTP 支持账号验证、修改邮箱、密码重置和邮件提醒。启用这些功能需可用的发件账号和 SMTP 连接。
- 生产部署使用 HTTPS。Web Push 还需要 VAPID 密钥、支持推送的浏览器、通知授权和可访问的推送服务。

## 数据库

先审核 `db/create-database.sql`，将示例密码替换为随机值，并在独立 MySQL 实例执行。脚本只建立 `liubai_diary` schema 和 `liubai_app` 账号。通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 指向该库。应用首次启动时 Flyway 自动执行 `src/main/resources/db/migration` 中的版本迁移；`spring.flyway.clean-disabled=true`，不使用 clean。

本次在独立 MySQL 8.4.11 临时实例成功运行 V1、V3、V4、V5 和 V6。Flyway 对该 MySQL 版本仍有支持范围提示。升级前备份数据库和媒体目录，并将数据库账号的 DDL 权限限定在本项目库。

## 配置变量

可参考项目根目录 `.env.example`。Spring Boot 不会默认加载 `.env`，请将变量写入 PowerShell 会话、IDE Run Configuration、容器 secret 或服务管理器。

| 变量 | 用途 |
|---|---|
| `SPRING_DATASOURCE_URL` | MySQL JDBC 地址 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 项目专用数据库账号 |
| `APP_BASE_URL` | 邮件中生成的前端验证链接 |
| `APP_MEDIA_ROOT` | 私有媒体目录；必须持久化并备份 |
| `APP_ADMIN_EMAIL` / `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` / `APP_ADMIN_NICKNAME` | 首次引导管理员；密码至少 10 位且不超过 BCrypt 限制 |
| `SPRING_MAIL_HOST` / `PORT` / `USERNAME` / `PASSWORD` | 账号邮件 SMTP |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` / `...STARTTLS_ENABLE` | SMTP 身份验证与 STARTTLS |
| `APP_MAIL_FROM` | 发件人地址 |
| `APP_PUSH_PUBLIC_KEY` / `APP_PUSH_PRIVATE_KEY` | Web Push VAPID 密钥，使用不带填充的 Base64URL 格式 |
| `APP_PUSH_SUBJECT` | 站点运营者的 `mailto:` 地址或 HTTPS 联系地址 |
| `APP_PUSH_ALLOWED_HOSTS` | 可选的推送服务域名列表，以逗号分隔 |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | HTTPS 部署设为 `true` |

管理员口令不得提交到仓库。启动时邮箱若已属于普通账号，应用会拒绝提升权限；管理员用户名冲突时也会启动失败。

自定义 JDBC 地址时，保留 `connectionTimeZone=%2B00%3A00&forceConnectionTimeZoneToSession=true`。数据库会话用 UTC 处理时间戳，日记所属日期和写作活动日期按各账号的时区计算。

## 前端与服务

本地后端在项目根目录使用 `.\mvnw.cmd spring-boot:run` 启动；也可在 Maven 已安装时用 `mvn spring-boot:run`。生产前端执行 `npm ci` 与 `npm run build`，将生成的 `frontend/dist` 作为静态文件发布。反向代理要把 `/api` 转发到 Spring Boot 服务，并转发 Cookie、CSRF 请求头和 HTTPS 信息。当前前端构建已在本环境执行；完整生产反向代理尚未验证。

将 `var/media` 映射到持久化卷，限制目录权限，设置容量告警和异机备份。数据库与媒体应使用同一备份周期；恢复时先恢复数据库和文件，再启动应用。单节点本地文件实现没有跨节点共享存储支持。

### 更新后端后重启服务

Vite 会更新前端源码，但 `java -jar` 启动的后端不会自动加载新代码。修改代码或生成新 JAR 后，需要停止旧后端再启动新版；不要覆盖正在运行的 JAR。重启时保持相同的数据库和 `APP_MEDIA_ROOT` 配置，并设置 `SPRING_DATASOURCE_PASSWORD`。重启会结束原有登录会话，浏览器刷新后需重新登录。

如果日历、分享或数据页同时报 HTTP 404，先核对后端进程启动时间、所用 JAR 和接口路径。`/api/health` 返回 `ok` 只表示服务运行，并不证明运行的是新版。当前版本应提供 `/api/stats/coverage`、`/api/data/exportable` 和 `/api/settings/avatar`，且 Flyway 已迁移至 V6。只有确认旧服务停止且数据库备份完成后，才启动新版并检查迁移日志；不要通过删除数据库解决版本不一致。

## 邮件、Web Push 和历史事件

### 启用提醒

1. 要启用邮件提醒，配置 SMTP 变量和获准使用的 `APP_MAIL_FROM`。登录后，在设置中分别启用邮件或浏览器通知。
2. 要启用 Web Push，生成一对 P-256 VAPID 密钥，并将输出写入部署环境。私钥仅存放在后端环境中。

   ```powershell
   node -e "const {generateKeyPairSync}=require('node:crypto'); const {publicKey,privateKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'}); const {x,y}=publicKey.export({format:'jwk'}); console.log('APP_PUSH_PUBLIC_KEY='+Buffer.concat([Buffer.of(4),Buffer.from(x,'base64url'),Buffer.from(y,'base64url')]).toString('base64url')); console.log('APP_PUSH_PRIVATE_KEY='+privateKey.export({format:'jwk'}).d)"
   ```

3. 设置 `APP_PUSH_SUBJECT`，使用 HTTPS 提供生产前端，并在设置中订阅当前浏览器。iPhone 和 iPad 用户先将站点添加到主屏幕，再从主屏幕打开应用并订阅。[WebKit 平台说明](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)

服务端每分钟检查到期提醒，发送窗口为计划时间起 30 分钟。相同账号、当地日期和渠道共享一条发送记录。当天已有同步的活动时跳过提醒。提醒可能早于尚未联网的离线活动同步。服务中断导致投递结果无法确认时，状态会显示为未知。

### 导出 PDF

在数据管理中选中日记，点击 **打印 / 保存为 PDF**。浏览器加载正文和内嵌图片后打开打印窗口。选择保存为 PDF，使用装有中文字体的浏览器完成导出。离线 HTML 将图片随文档一同下载。

### 更新世界历史事件

前端随构建发布 `frontend/src/data/world-history.json`，涵盖全年 366 个日期。写作首页展示当天两条，窄屏展示一条。回顾页展示当天全部七条。应用按用户时区选择日期，读取随版本发布的资料，无需配置 API 密钥或访问外网。数据缺失时只提示当天暂无资料。

在有网络的 Windows PowerShell 7 中运行 `./scripts/update-history.ps1`，再重新构建前端。脚本通过 MediaWiki Action API 读取中文维基百科各日期条目的修订，从大事记筛选短条目，转为简体并记录原始修订号。只有全年检查通过后才写出文件。公开事件资料的更新无需访问数据库、账号或日记。[MediaWiki API 文档](https://www.mediawiki.org/wiki/API:Revisions)

### 后台清理

回收站每天 03:15 UTC 执行到期清理，也可通过 `APP_TRASH_CLEANUP_CRON` 配置。注销立即撤销账号权限和清理数据。媒体文件遇到磁盘错误时，后台根据持久任务重试。多实例部署未做压力验证。

## 升级和排障

- Flyway 校验失败：核对已应用迁移，不要手工改版本表或删除用户表后重试。
- 无法连接数据库：检查服务地址、端口、账号 host 匹配和专用 schema 权限。
- 邮件不可用：检查 SMTP 端口、账号、STARTTLS、发件人授权和服务器日志；未配置时普通注册流程会被拒绝。
- 旧账号验证后仍无法登录：旧版可能将未验证注册写成停用状态。逐个核对账号的注册和停用记录后处理。
- 图片丢失：核对 `APP_MEDIA_ROOT` 权限、挂载路径和数据库 `media_assets` 元数据是否来自同一备份。
- 会话跨域失败：建议让静态资源与 `/api` 同源，开启 HTTPS 并检查反向代理的 Cookie 属性。
- 对外发布前需设置安全的管理员口令、受限数据库账号、HTTPS、日志保留策略和备份演练。
