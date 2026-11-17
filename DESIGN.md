---
name: 留白日记
description: 私密图文日记与回顾空间
colors:
  accent: "#0f62fe"
  paper: "#ffffff"
  surface: "#f4f4f4"
  ink: "#161616"
  muted: "#525252"
  line: "#e0e0e0"
  dark-paper: "#262626"
  dark-accent: "#a6c8ff"
typography:
  body:
    fontFamily: '"Noto Serif SC", "Noto Serif CJK SC", "Songti SC", STSong, "SimSun", serif'
    fontSize: "18px"
    lineHeight: 1.95
  label:
    fontFamily: 'Inter, "Noto Sans SC", sans-serif'
    fontSize: "14px"
rounded:
  sm: "8px"
  md: "12px"
spacing:
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "40px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.paper}"
    height: "44px"
---

# 留白日记界面规范

## Overview

遵循用户指定的 Craft 布局和 Bear 中文阅读体验。中性灰导航承托白色写作纸面，经典蓝用于主要操作和当前选择。正文与照片占据主要空间。桌面写作采用导航、正文、回顾侧栏，窄屏依次排列正文和回顾。

## Colors

变量以 frontend/src/styles/tokens.css 为准。浅色纸面 #ffffff，底层 #f4f4f4，正文 #161616，次要文字 #525252，分隔线 #e0e0e0，强调色 #0f62fe，按钮前景 #ffffff。深色纸面 #262626，底层 #161616，正文 #f4f4f4，次要文字 #c6c6c6，强调色 #a6c8ff，按钮前景 #161616。

配色参考 [IBM Carbon](https://www.carbondesignsystem.com/building-blocks/foundations/color/overview) 的中性灰与蓝色体系。蓝色为默认强调色，设置提供玫瑰红备选。旧账号和备份中的 olive 值在前端统一归一化为 blue；保持接口兼容，保存设置后写入 blue。深色主题使用炭灰表面和浅蓝操作色，保证可读性。

## Typography

界面字体采用 Inter、Noto Sans SC 与系统中文无衬线，正文与主要标题采用 Noto Serif SC 及中文宋体回退栈。正文 18–20px、1.95 行高，界面主要文字 14px，次要文字 12–13px。间距使用 4、8、12、16、20、24、32、40px。控件圆角 8px，内容图片圆角 10px。主要触控目标至少 44px。

## Layout

导航以选中背景和字重区分当前位置。管理页用标题、工具行和分隔线组织内容。写作正文最大行宽约 720px。移动端底栏保持主要路径，完整菜单以原生 dialog 展开，支持 Escape、焦点约束与回到触发按钮。

表单保留可见标签。日期使用可操作的原生输入。保存状态来自实际本地与 API 状态。错误、空白和载入分别显示，所有主题使用统一变量。动画限于导航抽屉出现与控件反馈，并遵循 prefers-reduced-motion。

## Components

浏览器界面检查使用独立虚构数据拦截 API，生产代码仍调用原接口。构建和浏览器交互结果记录在 docs/screenshots/frontend-verification.md。外部服务和真实数据库持久化需要独立的集成环境验证。
