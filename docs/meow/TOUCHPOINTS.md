# Touch-point registry

Every place moonmeow code has been welded into an upstream (Artemis) file, per
`CLAUDE.md` §2 and §3. Additive-only is the rule; everything listed here is an
exception that had to earn its place.

Audit before each upstream sync:

```bash
git grep -n 'MEOW-TOUCH' -- app/src
```

A growing registry means features are being welded into upstream code instead of
layered beside it. Keep it short.

---

## `MEOW-TOUCH(inline-pinch-zoom)`

**Feature:** pinch-to-zoom that works inline, with no mode toggle.

**Why upstream had to be touched at all:** zooming used to require flipping
`Game.isPanZoomMode` from the game menu or the overlay button. While the mode was on,
touch drove the local view and the mouse was gone; while it was off, pinching did
nothing. Chrome Remote Desktop has no such mode, and the round trip through a menu is
the single biggest reason this app feels clunky next to it. The gesture has to be
routed at the point where touch events are already dispatched, which is inside
`Game`. The explicit Pan/Zoom mode is left intact for users who prefer it.

### `app/src/main/java/com/limelight/Game.java` — 4 sites

| Site | Edit |
| --- | --- |
| field declaration | `private InlinePinchZoomController inlinePinchZoom;` |
| `onCreate`, beside the existing `PanZoomHandler` construction | one constructor call wiring the controller to the handler and to two method references |
| finger branch of `handleMotionEvent`, immediately after the `isPanZoomMode` block | one `if` that offers the event to the controller and returns early if it was consumed |
| beside `cancelStaleTouchState` | `cancelInFlightTouchContexts()`, a 7-line helper |

The touch hook is skipped when `prefConfig.enableMultiTouchScreen` is set. In that
mode the host receives native touch events, so a pinch belongs to the remote
application rather than to our local view; stealing it would be a regression.

`cancelInFlightTouchContexts()` exists because a gesture is ambiguous when it starts.
By the time it is confirmed as a pinch, the `TouchContext`s have already seen the
pointers go down. Without cancelling them, lifting the fingers at the end of a pinch
would land on the host as a two-finger-tap right click.

### `app/src/main/java/com/limelight/utils/PanZoomHandler.java` — extract + implement

The scale transform was moved out of `ScaleListener.onScale` into `pinchBy(...)` and
the pan transform out of `GestureListener.onScroll` into `panBy(...)`, both verbatim;
the listeners now delegate. The class declares
`implements InlinePinchZoomController.ZoomTarget`.

This is a multi-line upstream edit rather than a new class on purpose. The
alternative — reimplementing the scale clamp, focal pivot and bounds constraint in
`meow/` — would have created a second copy of the zoom transform *and* a second
source of truth for `scaleFactor`, which `Game` reads back for the
`rememberZoomPan` preference. One implementation, driven from two entry points, is
the smaller long-term liability. The explicit Pan/Zoom mode now provably runs the
same code as the inline path.

### New code (additive, in `meow/`)

- `app/src/main/java/com/limelight/meow/gesture/TwoFingerGestureArbiter.java` —
  scroll-vs-pinch disambiguation. Plain Java, no Android imports.
- `app/src/main/java/com/limelight/meow/gesture/InlinePinchZoomController.java` —
  event routing. Its core, `handle(...)`, takes plain numbers so it is testable
  without fabricating `MotionEvent`s.

Tested by `app/src/test/java/com/limelight/meow/gesture/`.

### Invariant worth knowing before you retune anything

`TwoFingerGestureArbiter`'s span slop must stay below
`TrackpadContext.TAP_MOVEMENT_THRESHOLD` (30px), which is how far one finger must
travel before the trackpad code confirms a move and starts emitting scroll packets.
A symmetric pinch moves each finger by half the span change and an anchored pinch
moves one finger by the whole span change, so latching at or under 30px of span
change is what stops a scroll blip from leaking to the host at the start of a pinch.
The default is `ViewConfiguration.getScaledTouchSlop()` (~24px at 3x density).
