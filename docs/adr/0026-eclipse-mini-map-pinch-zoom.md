# ADR 0026: §13.3's eclipse mini-map zooms, and only under two fingers

**Status:** Accepted

## Context

§13.3 specifies the eclipse block's map as a

> path mini-map for eclipses [static canvas drawing of centralPath +
> location markers — no tile map needed]

and the implementation took "static" literally: one identity `MapCamera`, no
gestures at all.

That reading is right about what the view is *for* — "does this track come
anywhere near me?", answered without a tap — and wrong about what it can
*show*. The canvas is the whole world at 2:1 in a phone's width: about
360 px wide on a mid-range device, so one pixel is a degree of longitude,
and totality is a band 100–250 km across. The one thing a reader wants after
"it crosses Spain" is "where in Spain", and the mini-map is physically
incapable of saying. §14.1's map on the Sky tab can zoom, but it is a
different route showing every event at once, and it has no idea which
eclipse the reader was just reading about.

The obstacle is not the drawing. `MapCamera` already carries the zoom, the
clamping and the focus-preserving arithmetic, and the mini-map's draw pass
differs from the Sky tab's base layer only in that it hard-codes the
identity camera. The obstacle is the gesture: this canvas lives inside
EventDetail's `LazyColumn`, and a one-finger drag on it is how the reader
scrolls past it. `detectTransformGestures` — what §14.1's map tab uses —
claims every drag from the first pointer, which would turn two thirds of a
screen into a region the page cannot be scrolled from.

## Decision

The mini-map zooms and pans, driven **only by gestures with at least two
pointers down**. A single finger is never consumed and reaches the list.

The detector is a small `awaitEachGesture` loop rather than
`detectTransformGestures`: it reads `calculateZoom`/`calculatePan`/
`calculateCentroid` from the same events the stock detector does, but skips
(and does not consume) any event with fewer than two pressed pointers. Once
the list has claimed a drag, the gesture is over — the stock detector's
`canceled` rule — and the reader lifts off and pinches again. Fighting the
parent for a drag it already won moves both.

Two-finger drag pans, because zoom without pan means the reader can only
inspect wherever the pinch happened to land.

The view still **opens** at the identity camera: the whole world, exactly
filling the 2:1 canvas. A camera fitted to the track would zoom a narrow
path to fill the frame, which reads as "this is happening everywhere" — the
opposite of what a mini-map is for. §13.3's "at a glance" is a statement
about the default view, and the default view is unchanged.

The zoom-then-pan composition moves to `MapCamera.transformed` in `:core`
(ADR 0021's line), and §14.1's Android map tab is rewired through it. It was
already written out once, with a comment explaining why folding the pan in
before the zoom drifts under the fingers; a second copy of that reasoning in
this file is how one of them ends up right and the other wrong.

## Consequences

- §13.3's "static canvas drawing" is now false of the implementation for the
  gestures, and true of everything else in the bracket: still a canvas, still
  `centralPath` + location markers, still no tile map and no tile licence.
  Per CLAUDE.md the design doc is not edited to match; this ADR is the
  record.
- The camera is keyed on `occurrence.id`, so the zoom does not follow the
  reader from one eclipse to the next through the shared detail route.
- The header gains a zoom readout and a Reset button above 1×, and reserves
  the button's height at 1× so the first pinch does not shove the map out
  from under the fingers on it.
- **Not reachable with TalkBack.** A pinch is not a gesture a screen reader
  passes through, and no zoom-in/out buttons are added: the canvas is
  described in one sentence for a listener, and §13.3's times tables above it
  carry the facts. The `contentDescription` therefore stays silent about the
  zoom rather than announcing a state its listener can neither reach nor
  change.
- §14.1's map tab keeps one-finger pan. It fills a tab of its own, with
  nothing behind it that wants a drag, so the pointer-count gate would cost
  it an affordance and buy nothing.

## Alternatives considered

- **Leave it static; send the reader to the Sky tab's map.** That map opens
  on every eclipse in the window with no way to say "the one I was reading
  about", and reaching it is Back, Sky, Map, then find the track. The
  question is asked on the detail screen.
- **`detectTransformGestures`, as on the Sky tab.** One line instead of
  twenty, and it takes the one-finger drag with it: the reader gets a map
  they can zoom and a page they cannot scroll past it.
- **A one-finger drag that only pans once zoomed in.** Keeps scrolling at 1×
  and breaks it at 3×, which is worse than either — the surface changes what
  it does to a drag depending on state the reader is not tracking.
- **Fit the camera to the track on open.** Answers "where in Spain" without a
  gesture, and destroys "does it come near me": every eclipse would fill the
  frame, and a track over Chile and one over the reader's own city would look
  the same.
- **A fullscreen map route from the eclipse block.** A third presentation of
  the same map, with its own back stack entry, for a view the reader is
  already looking at.

## Related

- [ADR 0021](0021-chart-math-in-core.md) — why `MapCamera.transformed` lands
  in `:core` rather than in either gesture handler.
- [ADR 0022](0022-android-visualization-parity.md) — the mini-map as one of
  Android's drawn views, and the one it left without gestures.
- [ADR 0023](0023-natural-earth-on-android.md) — the coastlines this canvas
  zooms into.
