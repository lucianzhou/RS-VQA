# 004 — Explain asynchronous progress with stage transitions

- **Status**: TODO
- **Commit**: 8af68bb
- **Severity**: MEDIUM
- **Category**: Purpose and missed opportunity
- **Estimated scope**: 4 files, about 120 lines

## Problem

RS-Bot changes a text label while displaying the same infinite line:

```tsx
// apps/web/src/components/RsBotChat.tsx:82-87 — current
<article className="rsbot-answer is-pending">
  <span className="rsbot-avatar"><Bot size={compact ? 15 : 18} /></span>
  <div>
    <strong>{stageLabel(stage)}</strong>
    <span className="rsbot-progress-line" />
  </div>
</article>
```

The moving line communicates activity but not continuity between planning, tool execution and answer generation.
Batch and model analysis likewise expose progress mostly as spinners or generic bars. Adding decorative motion would
not solve the uncertainty; transitions must explain state changes.

## Target

- Keep one stable progress region so stage text does not resize the transcript.
- Crossfade stage labels over 140ms strong ease-out; do not move the surrounding message.
- Add a compact three-state indicator for RS-Bot: `规划 -> 调用工具 -> 组织回答`.
- Completed stages become a static check; the active stage uses the existing linear activity line.
- Unknown/fallback stages show one neutral “处理中” state rather than guessing progress.
- Do not show fake percentages.
- Batch task status remains based on actual completed/total counts.
- Reduced motion keeps label opacity and static active-state color; the moving line stays disabled as it is now.

## Repo conventions to follow

- `stageLabel` remains the single text mapping source.
- `aria-live="polite"` is already on the transcript at `RsBotChat.tsx:65`.
- Reduced-motion behavior for progress lines is at `apps/web/src/styles.css:1696-1705`.
- Current progress uses only transform at `apps/web/src/styles.css:1378-1391`.

## Steps

1. Define a small ordered stage model next to `stageLabel`; map backend stages to planning, tool and answer states.
2. Render a stable `role="status"` region with an accessible full sentence. Mark decorative dots/lines
   `aria-hidden="true"` to avoid repeated screen-reader announcements.
3. Wrap only the stage label in `AnimatePresence mode="sync"` and key by normalized stage.
4. Use opacity-only 140ms transitions. Do not use `y`, `x`, width or height animation.
5. Preserve the existing cancel button and pending user question.
6. Add unit tests for every stage mapping, unknown stage fallback and reduced-motion-safe markup.
7. For batch/model pages, audit existing actual progress data and only add stage copy where the backend has a real state.
   Do not infer queue or model percentages from elapsed time.

## Boundaries

- Do NOT change backend Agent stages or fabricate timing estimates.
- Do NOT add skeletons to already stable content.
- Do NOT animate every tool invocation card.
- Do NOT add dependencies.

## Verification

- **Mechanical**:
  - `cd apps/web && npm run typecheck`
  - `cd apps/web && npm test -- --run src/components/RsBotChat.test.tsx`
  - `cd apps/web && npm run build`
- **Feel check**:
  - Run an RS-Bot request that calls two tools.
  - Confirm the status progresses without transcript height jumps or duplicate messages.
  - Throttle the network and confirm no percentage is invented.
  - At 10% playback, confirm only the label crossfades.
  - Enable reduced motion and confirm the active stage remains understandable without movement.
- **Done when**: users can name the current phase, screen readers receive concise status, and no progress value exceeds
  what the backend actually knows.
