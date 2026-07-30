# 005 — Tighten high-frequency thumbnail motion

- **Status**: TODO
- **Commit**: 8af68bb
- **Severity**: MEDIUM
- **Category**: Frequency, duration and accessibility
- **Estimated scope**: 2 files, about 45 lines

## Problem

Batch upload and result grids can contain up to 200 images. Every hover lifts the tile and scales the image for 420ms:

```css
/* apps/web/src/styles.css:379-384 and 446-450 — current */
.batch-thumbnail:hover { transform: translateY(-2px); }
.batch-thumbnail img { transition: transform 420ms var(--ease-out); }
.batch-thumbnail:hover img { transform: scale(1.035); }
.batch-result-thumbnail:hover { transform: translateY(-1px); }
.batch-result-thumbnail img { transition: transform 420ms var(--ease-out); }
.batch-result-thumbnail:hover img { transform: scale(1.045); }
```

This interaction can occur tens or hundreds of times during review. A 420ms zoom is longer than the 300ms UI budget,
and hover movement is not gated to devices with a fine pointer.

## Target

- Gate hover-only transforms with `@media (hover: hover) and (pointer: fine)`.
- Remove tile lift; border, shadow and overlay are sufficient feedback.
- Reduce image zoom to `scale(1.025)` for 180ms strong ease-out.
- Keep active press at `scale(.98)` for 140ms.
- Keyboard focus always shows the maximize overlay without requiring hover.
- Reduced motion removes image zoom but preserves border/overlay feedback.

## Repo conventions to follow

- `--motion-popover: 180ms` and `--ease-out` are at `apps/web/src/styles.css:32,35`.
- Existing focus-visible overlay is at `apps/web/src/styles.css:383-384`.
- Press feedback elsewhere targets roughly `.97` at `apps/web/src/styles.css:409-410`.

## Steps

1. Move thumbnail hover border, shadow, image zoom and overlay rules into a fine-pointer media query.
2. Remove `translateY` from both upload and result thumbnail hover states.
3. Change image transform duration from 420ms to `var(--motion-popover)`.
4. Change hover scale to `1.025` for both thumbnail types.
5. Add a reduced-motion rule that sets thumbnail image transition to opacity only and transform to none.
6. Add a UI test or Playwright assertion that focus-visible exposes the maximize affordance.

## Boundaries

- Do NOT change grid dimensions, pagination, image loading or lightbox behavior.
- Do NOT remove press feedback.
- Do NOT add animation to filenames or status icons.
- Do NOT add dependencies.

## Verification

- **Mechanical**:
  - `cd apps/web && npm run typecheck`
  - `cd apps/web && npm test -- --run src/pages/FeaturePages.test.tsx`
  - `cd apps/web && npm run build`
- **Feel check**:
  - Move the pointer rapidly across a full 20-image page.
  - Confirm tiles remain spatially stable and feedback completes promptly.
  - Use keyboard Tab and confirm the maximize overlay is visible.
  - Emulate touch and confirm no hover lift/zoom sticks after tapping.
  - Enable reduced motion and confirm there is no zoom.
- **Done when**: high-frequency review feels stable, touch has no sticky hover transform and keyboard affordance remains.
