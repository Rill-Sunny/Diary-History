# 直接依赖与许可证

下表汇总项目直接声明的运行/构建依赖；许可证名称以依赖发布的 `LICENSE` 或包元数据为准。传递依赖不在此逐项列出，发布或再分发前应重新生成完整依赖清单并核验各自的版权和 NOTICE 文件。

## 后端

| 依赖 | 版本/版本管理 | 用途 | 许可证 |
|---|---|---|---|
| Spring Boot Web、Security、Validation、Mail、Test | 3.5.16 | Web、安全、校验、邮件和测试基础 | Apache-2.0 |
| MyBatis Spring Boot Starter | 3.0.5 | SQL 映射 | Apache-2.0 |
| Flyway Core / MySQL | 由 Spring Boot BOM 管理 | 版本化数据库迁移 | Apache-2.0（以实际解析版本许可证为准） |
| MySQL Connector/J | 由 Spring Boot BOM 管理 | MySQL JDBC 驱动 | GPL-2.0 with FOSS License Exception（请随发行版保留驱动声明） |

## 前端

| 依赖 | 锁文件解析版本 | 用途 | 许可证 |
|---|---:|---|---|
| Vue | 3.5.45 | UI 框架 | MIT |
| Vite / Vue 插件 | 7.3.7 / 6.0.10 | 开发服务器与构建 | MIT |
| TypeScript | 5.9.3 | 类型检查 | Apache-2.0 |
| Tiptap 核心相关包 | 3.33.0 | 富文本编辑器 | MIT |
| fflate | 0.8.3 | ZIP 导入导出 | MIT |
| idb | 8.0.4 | IndexedDB 封装 | ISC |
| vue-tsc / `@vue/tsconfig` / `@types/node` | 3.3.14 / 0.8.1 / 24.19.2 | Vue 类型检查与配置 | MIT / MIT / MIT |

版本以 `frontend/package-lock.json` 和 Maven 解析结果为准。当前未生成包含所有传递依赖、哈希和 NOTICE 文件的发布 SBOM；本清单用于项目说明，不替代发行前的许可证复核。

## 世界历史事件资料

`frontend/src/data/world-history.json` 摘自中文维基百科各日期条目的大事记。著作权归原条目贡献者。项目筛选、简化字形并在必要时截取首句，以 CC BY-SA 4.0 发布该资料。数据中的 `revision` 和页面上的来源链接指向确切的原始修订及作者记录。[中文维基百科日期条目示例](https://zh.wikipedia.org/w/index.php?oldid=95520511) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.zh-hans)
