# 001 — Make route navigation continuous

- **Status**: TODO
- **Commit**: 8af68bb
- **Severity**: HIGH
- **Category**: Purpose, performance, cohesion
- **Estimated scope**: 2 files, about 40 lines

## Problem

Every route transition waits for the old route to finish before the new route starts and applies the same vertical
movement to unrelated pages:

```tsx
// apps/web/src/App.tsx:77 — current
<AnimatePresence mode="wait" initial={false}>
  <motion.div
    className="route-frame"
    key={location.pathname}
    initial={{ opacity: 0, y: 5 }}
    animate={{ opacity: 1, y: 0 }}
    exit={{ opacity: 0, y: -3 }}
    transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
  >
```

`mode="wait"` inserts a visible pause in high-frequency navigation. Motion shorthand `y` also performs JS-driven
transform work where a simple opacity transition is sufficient. The animation does not explain route hierarchy.

## Target

- Route changes begin immediately; no blank interval between exit and enter.
- Use an opacity-only 140ms transition for unrelated top-level pages.
- Keep sidebar selection as the primary spatial/navigation signal.
- Reduced-motion keeps the same opacity feedback at 100ms.
- Do not animate route container height or position.

```tsx
<AnimatePresence mode="sync" initial={false}>
  <motion.div
    className="route-frame"
    key={location.pathname}
    initial={{ opacity: 0 }}
    animate={{ opacity: 1 }}
    exit={{ opacity: 0 }}
    transition={{ duration: 0.14, ease: [0.23, 1, 0.32, 1] }}
  >
```

## Repo conventions to follow

- Motion tokens are in `apps/web/src/styles.css:31-37`.
- Strong ease-out is `cubic-bezier(0.23, 1, 0.32, 1)`.
- `MotionConfig` at `apps/web/src/App.tsx:60` already honors user/reduced-motion settings.
- `ImageLightbox` demonstrates `AnimatePresence` with symmetric opacity at
  `apps/web/src/components/ImageLightbox.tsx:24-34`.

## Steps

1. In `apps/web/src/App.tsx`, change route presence mode from `wait` to `sync`.
2. Remove route `y` values and use only opacity.
3. Set duration to `0.14` and easing to `[0.23, 1, 0.32, 1]`.
4. Add or update a route-transition test that switches from workspace to batch and asserts the destination renders
   without waiting on a timer.
5. Do not add per-page decorative transitions in this change.

## Boundaries

- Do NOT modify sidebar collapse or mobile drawer motion.
- Do NOT change route structure, lazy loading, URLs or page data fetching.
- Do NOT add dependencies.
- If the cited component has moved since commit `8af68bb`, stop and refresh this plan before editing.

## Verification

- **Mechanical**:
  - `cd apps/web && npm run typecheck`
  - `cd apps/web && npm test -- --run`
  - `cd apps/web && npm run build`
- **Feel check**:
  - Switch quickly among workspace, batch, reports and RS-Bot.
  - Confirm the destination starts appearing immediately and the canvas never becomes blank.
  - In DevTools Animations, use 10% playback and confirm there is only an opacity crossfade, no vertical drift.
  - Enable reduced motion and confirm the page still changes clearly without positional movement.
- **Done when**: ten rapid route switches have no blank frame, vertical jump or blocked click.
