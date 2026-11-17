# 运行界面截图

## 真实 API 验收

`history-live-` 截图来自 2026-11-11 的生产前端构建、独立 MySQL 和虚构日记。桌面采用 1440 × 940 视口，手机采用 390 × 844 视口。桌面按北京时间检验 11 月 11 日，手机用固定时钟检验 2 月 29 日。

- [桌面写作首页](history-live-home-desktop.png)
- [手机写作首页](history-live-home-mobile.png)
- [桌面历史回顾](history-live-records-desktop.png)
- [手机历史回顾](history-live-records-mobile.png)

## 前端布局复验

新版蓝灰配色使用 palette- 前缀，检查结果见 palette-verification.md 与 palette-checks.json。

本目录原有 desktop、mobile、narrow PNG 是 2026-11-11 前端重构后从实际 Vue 页面截取的画面。使用浏览器隔离的虚构 API 数据和项目已有示例照片，展示界面与交互，不代表真实数据库集成验证。

主要入口为 desktop-write-light.png、desktop-write-dark.png、mobile-write-light.png、mobile-navigation-light.png、desktop-journals-light.png。完整检查结果见 frontend-verification.md 和 frontend-checks.json。

../design/accepted-desktop.png 与 accepted-mobile.png 是原有设计参考。当前目录为运行截图。
