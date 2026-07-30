# 002 — Remove layout animation from Agent actions

- **Status**: DONE (`feature/trusted-application-p1`)
- **Commit**: 8af68bb
- **Severity**: HIGH
- **Category**: Performance and interruptibility
- **Estimated scope**: 2 files, about 50 lines

## Problem

The controlled-action panel animates `height` from `0` to `"auto"`:

```tsx
// apps/web/src/pages/AgentPage.tsx:288 — current
<motion.section
  className="agent-action-center"
  initial={{ opacity: 0, height: 0 }}
  animate={{ opacity: 1, height: "auto" }}
  exit={{ opacity: 0, height: 0 }}
  transition={{ duration: 0.18 }}
>
```

The panel contains selects, textareas and proposal cards. Animating its measured height forces layout on a dense page,
and rapid open/close can reverse from a stale measured size.

## Target

- Do not animate `height`, `max-height`, margin or padding.
- Keep the panel in a stable grid row when visible.
- Enter with opacity and `transform: translateY(-4px)` for 180ms strong ease-out.
- Exit with opacity only for 120ms strong ease-out.
- Reduced motion uses opacity only for 100ms.
- Focus must not move or be lost when the panel finishes entering.

```tsx
<motion.section
  className="agent-action-center"
  initial={{ opacity: 0, transform: "translateY(-4px)" }}
  animate={{ opacity: 1, transform: "translateY(0)" }}
  exit={{ opacity: 0, transform: "translateY(-2px)" }}
  transition={{ duration: 0.18, ease: [0.23, 1, 0.32, 1] }}
>
```

Use `useReducedMotion()` to replace both transform values with `"none"` and duration with `0.1`.

## Repo conventions to follow

- Motion is already imported from `motion/react` in `AgentPage.tsx`.
- The strong ease-out token value is defined at `apps/web/src/styles.css:35`.
- The lightbox uses a mounted presence boundary and preserves Dialog focus behavior at
  `apps/web/src/components/ImageLightbox.tsx:24-75`.

## Steps

1. Import and call `useReducedMotion` in the smallest component that owns the action-panel animation.
2. Replace `height` animation with full `transform` strings and opacity.
3. Use 180ms enter and 120ms exit if separate variants are introduced; otherwise use 180ms for both.
4. Preserve `AnimatePresence initial={false}` and the existing `showActionPanel` condition.
5. Add a test that rapidly toggles the panel open, closed and open, then verifies its form remains interactive.
6. Confirm proposal cards do not get remounted solely due to animation changes.

## Boundaries

- Do NOT redesign the Agent form or change proposal behavior.
- Do NOT change API calls, action names, context selection or confirmation rules.
- Do NOT animate layout properties elsewhere as part of this plan.
- Do NOT add dependencies.

## Verification

- **Mechanical**:
  - `cd apps/web && npm run typecheck`
  - `cd apps/web && npm test -- --run src/pages/FeaturePages.test.tsx`
  - `cd apps/web && npm run build`
- **Feel check**:
  - Open and close “受控操作” repeatedly with several proposal cards present.
  - Confirm the conversation area reflows once per visibility change, not continuously during the animation.
  - At 10% playback, confirm the panel uses a small upward fade and no elastic height measurement.
  - Enable reduced motion and confirm only opacity remains.
- **Done when**: rapid reversal is smooth, form focus remains valid and Performance panel shows no frame-long layout loop.
