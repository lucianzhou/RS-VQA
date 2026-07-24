# ADR-001：迁移至 React 前端

- 状态：接受
- 日期：2026-07-24
- 范围：`apps/web`

## 决策

`apps/web` 在 v0.3.0 迁移为 React + TypeScript + Vite。使用 React Router、TanStack Query、React Hook Form、Zod、Lucide React、Vitest、React Testing Library 和 Playwright。Zustand 仅在存在真实跨路由客户端状态时使用。

迁移原地完成，不长期保留 Vue 与 React 两套正式前端。现有 `POST /api/v1/vqa/answers` 在迁移期保持兼容，新的资源型 API 作为正式主链路。

## 原因

- 会话、批量任务、流式 Agent 和多页面设置需要稳定的路由、服务端状态与测试生态。
- React 更贴近本项目的 AI 产品展示与求职技术目标。
- 选择无样式或低样式基础组件，避免默认后台模板覆盖已确认的 Mineral Forest 设计。

## 视觉约束

唯一母版为 Stitch 项目中的 `RS-VQA Light Workspace - Mineral Forest`：

- 主色 `#245B49`，按下态 `#194536`
- 辅助绿 `#4F7A67`
- 选中背景 `#E3EEE7`
- 支撑背景 `#F3F5F1`
- 主文字 `#1D2420`
- 次文字 `#68736D`
- 警示赭色 `#A87536`

主工作区保持白色和充足留白。禁止深色侧栏、全屏绿色、大面积渐变、营销 Hero、卡片套卡片和通用管理后台模板。动效必须即时反馈、可中断、克制，并支持 `prefers-reduced-motion`。

## 迁移映射

| Vue v0.1.2 | React v0.3.0 |
| --- | --- |
| 单页面内存状态 | 路由 + TanStack Query 服务端状态 |
| 最近一次回答 | 不可变多轮消息列表 |
| 临时图像 URL | 受控上传资产 + 本地预览 |
| 固定 Mock 结果 | 模型来源和 release provenance |
| 无测试 | 单元、组件、路由、E2E |

