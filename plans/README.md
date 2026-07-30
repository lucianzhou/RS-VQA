# RS-VQA 前端动效升级计划

审计基线：`8af68bb`

这些计划来自对 React、Motion、Radix 和全局 CSS 的完整动效审计。它们只规划高杠杆改进，不建议
增加装饰性动画。执行时应逐项实现、测试和慢放检查，不要一次性混入页面重构。

| 编号 | 计划 | 严重度 | 状态 |
| ---: | --- | --- | --- |
| 001 | [让路由导航连续](./001-make-route-navigation-continuous.md) | HIGH | DONE |
| 002 | [移除 Agent 操作面板的布局动画](./002-remove-layout-animation-from-agent-actions.md) | HIGH | DONE |
| 003 | [让 Popover 与 Toast 可中断](./003-make-popovers-and-toasts-interruptible.md) | MEDIUM | DONE |
| 004 | [用阶段过渡解释异步进度](./004-explain-async-progress-with-stage-transitions.md) | MEDIUM | DONE |
| 005 | [收紧高频缩略图动效](./005-tighten-high-frequency-thumbnail-motion.md) | MEDIUM | DONE |

## 推荐执行顺序

1. `001`：先消除全局导航空档，影响所有页面。
2. `002`：解决 Agent 密集页面的布局和快速反向问题。
3. `003`：统一可逆弹层的物理来源和退出行为。
4. `005`：降低批量人工复核中的高频视觉噪声。
5. `004`：在后端阶段字段保持稳定的前提下改善异步解释。

`001`、`002`、`003` 可独立执行。`004` 不依赖其他计划，但应在 RS-Bot 阶段枚举不再变化时执行。

## 总体验收

- UI 动效通常不超过 300ms；
- 高频操作不等待退场动画；
- 快速反向操作从当前视觉状态继续；
- 动画只使用 transform 和 opacity；
- 弹层从触发器方向出现；
- reduced-motion 保留必要的淡入/状态反馈，移除位移和缩放；
- 10% 慢放下没有空白帧、双重曝光、布局抖动或焦点跳转。
