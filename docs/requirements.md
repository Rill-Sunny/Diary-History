# 需求与完成度

## 产品范围

留白日记面向单人长期记录。新日记默认为私人内容；账号只能访问自己的日记、媒体、日记本、标签、统计、导出和回收站。分享必须由作者主动提交，并由管理员审核固定快照。普通日记保存不需要审核。核心日记功能没有付费或社交模块。

视觉方向参考 Craft 的文档层级和 Bear 的正文阅读体验。前端提供桌面与窄屏布局、两套全局主题和强调色。`docs/design/accepted-desktop.png` 与 `docs/design/accepted-mobile.png` 是设计参考图，不是运行截图。

## 功能对照

| 原始需求 | 可使用的功能 | 主要位置 |
|---|---|---|
| §3–5 技术与界面 | Vue 3、TypeScript、Vite、Tiptap、HTML/CSS 和浅深主题。Java 后端通过 MyBatis 访问 MySQL，Flyway 管理迁移。桌面与窄屏提供相同核心入口。 | `frontend/src/`、`src/main/java/`、`src/main/resources/db/migration/` |
| §6 账号和权限 | 邮箱验证、邮箱或用户名登录、密码重置和修改、新邮箱确认、头像、独立会话和立即注销。数据访问由服务端检查所有者或审核权限。 | `auth/`、`SecurityConfig.java` |
| §7 日记与资料库 | 同日多篇、补写、修改日期、自动日期标题、中文标题和正文检索、组合筛选、默认日记本、自定义本子和封面、标签样式与图标。 | `journal/`、`WriteView.vue`、`JournalListView.vue`、`LibraryView.vue` |
| §8 编辑器和图片 | 富文本格式、字号和字体、工具栏隐藏、选择/批量选择/拖放/粘贴图片。图片可排序、设宽度和说明、放大、居中裁剪至 16:9、旋转 90°。 | `JournalEditor.vue`、`media/` |
| §9 自动保存和离线 | 应用壳缓存、账号隔离的 IndexedDB、待传图片、版本冲突处理和持久操作 ID。清空保留最后有效内容，正常离开时按删除处理。 | `sw.js`、`offline.ts`、`WriteView.vue`、`JournalService.java` |
| §10 写作与覆盖 | 今日和本月活动、连续天数、热力图、日期覆盖计数。离线活动按实际写作日期同步。 | `StatsController.java`、`RecordsView.vue` |
| §11–12 分享与审核 | 固定分享快照、批准后只读、重审、到期、撤销、分享专用展示图。私人标签自定义图标需通过独立审核。 | `share/`、`journal/`、`ShareView.vue`、`AdminView.vue` |
| §13 历史上的今天 | 登录后首页直接显示当天世界历史事件，全年 366 个日期均有条目和来源链接。回顾页显示完整公开事件和当前账号历年同日记录。 | `WorldHistory.vue`、`RecordsView.vue`、`world-history.json` |
| §14 写作提醒 | 邮件和 Web Push 可独立开关。后台按账号时区和星期检查，写过当天日记时跳过。设置显示浏览器订阅、服务配置和最近发送状态。 | `reminder/`、`ReminderSettings.vue`、`sw.js` |
| §15–16 导入与阅读导出 | Markdown 分日预览、缺图提示、ZIP 路径检查、重复跳过。按日记本、日期和勾选范围导出离线 HTML 或通过浏览器打印为 PDF，两档图片质量。 | `DataView.vue`、`markdownImport.ts`、`readingExport.ts`、`data/` |
| §17–18 完整备份和回收站 | 追加恢复日记、图片、关系、活动、可选偏好及未过期回收站条目。回收站保留原删除时间，恢复不会重新激活旧分享。 | `data/`、`TrashCleanupService.java` |
| §19–20 部署配置 | 独立数据库、私有媒体目录、可配置 SMTP 与 Web Push、内置历史资料、浏览器打印依赖及迁移。 | `.env.example`、`docs/deployment.md` |

## 运行条件与验收边界

- SMTP 和 Web Push 需部署凭据和相应网络。真实设备的关闭网页后通知受浏览器、系统权限和推送服务影响。
- 历史事件使用随构建发布的公开资料。更新方式与许可见 [部署说明](deployment.md) 和 [依赖与许可证](dependency-licenses.md)。
- 课程 PPT 延续此前记录的跳过范围。真实页面和 API 的验收结果见 [验证记录](test-report.md)。
