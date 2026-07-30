# 003 — Make popovers and toasts interruptible

- **Status**: DONE (`feature/trusted-application-p1`)
- **Commit**: 8af68bb
- **Severity**: MEDIUM
- **Category**: Interruptibility and physicality
- **Estimated scope**: 3 files, about 90 lines

## Problem

Popover, dialog and toast entrances are CSS keyframes:

```css
/* apps/web/src/styles.css:547, 580-581, 627, 720-723 — current */
.context-menu, .model-popover, .profile-menu {
  animation: popover-in var(--motion-popover) var(--ease-out);
}
.dialog-overlay { animation: fade-in 180ms var(--ease-out); }
.action-dialog { animation: dialog-in 220ms var(--ease-out); }
.undo-toast { animation: toast-in 240ms var(--ease-out); }
@keyframes popover-in { from { opacity: 0; transform: scale(.96) translateY(-3px); } }
@keyframes toast-in { from { opacity: 0; transform: translateY(12px) scale(.98); } }
```

Keyframes restart when a rapidly reversible element is reopened. The toast is conditionally mounted without an exit
state:

```tsx
// apps/web/src/components/AppSidebar.tsx:402 — current
{toast && <div className="undo-toast" role="status" aria-live="polite">...</div>}
```

## Target

- Popovers and dropdowns enter in 180ms ease-out and exit in 120ms ease-out.
- Transform origin comes from the Radix trigger:
  - `var(--radix-popover-content-transform-origin)` for Popover.
  - `var(--radix-dropdown-menu-content-transform-origin)` for DropdownMenu.
- Open state starts at `scale(.96) translateY(-3px)` and reaches identity.
- Closed state targets `scale(.98) translateY(-2px)` and opacity 0.
- Toast uses `AnimatePresence` and Motion transitions so rapid close/reopen retargets from its current state.
- Toast enter: opacity 0, `translateY(10px) scale(.98)` to identity, 200ms ease-out.
- Toast exit: opacity 0, `translateY(6px) scale(.99)`, 140ms ease-out.
- Reduced motion removes translation/scale and keeps a 100ms fade.

## Repo conventions to follow

- Existing tokens at `apps/web/src/styles.css:31-37` already contain the correct curves and durations.
- `ImageLightbox` at `apps/web/src/components/ImageLightbox.tsx:24-75` demonstrates
  `AnimatePresence` with `forceMount` and separate overlay/content states.
- Button press feedback uses `scale(.97)` at `apps/web/src/styles.css:409-410`.

## Steps

1. For the model Popover and DropdownMenu content, replace open-only keyframes with Radix `data-state` transitions.
2. Set the appropriate Radix transform-origin variable on each content type.
3. Ensure content remains mounted long enough for the closed transition. Use the Radix-supported `forceMount` pattern;
   do not implement manual timeouts.
4. Wrap `toast` rendering in `AnimatePresence` and change its root to `motion.div`.
5. Use full `transform` strings rather than Motion `x`/`y` shorthands.
6. Branch transform values with `useReducedMotion`.
7. Add tests for quick model-selector open/close/open and toast close while an undo action is still present.

## Boundaries

- Do NOT alter menu items, focus trapping, keyboard navigation or undo semantics.
- Do NOT change the lightbox; it is the reference implementation.
- Do NOT animate blur or other filters.
- Do NOT add dependencies.

## Verification

- **Mechanical**:
  - `cd apps/web && npm run typecheck`
  - `cd apps/web && npm test -- --run`
  - `cd apps/web && npm run build`
- **Feel check**:
  - Repeatedly click the model selector before each transition completes.
  - Confirm it reverses from the current visual state and scales from the trigger, not the screen center.
  - Archive and immediately undo/close several items; confirm the toast never jumps back to its initial position.
  - At 10% playback, check that enter starts fast and exit is shorter.
  - Enable reduced motion and confirm a short fade remains with no translation or scale.
- **Done when**: no reversible overlay restarts from a visually unrelated origin and keyboard focus behavior is unchanged.
