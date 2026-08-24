# ADR 0025: The four drawn views share one bottom-bar item on Android

**Status:** Accepted

## Context

§13.1's Android navigation map is:

```text
BottomBar: [Upcoming] [Map*] [Rules] [Settings]        *Map tab is v1.1 on Android; hide behind flag
```

Four items, one of them reserved for a map. ADR 0022 brings all four of
§14's drawn views to Android, not just the map, so that slot no longer
describes what is behind it.

Giving each view its own item would make seven, and Material 3's
`NavigationBar` is specified for three to five destinations — at seven the
labels truncate and the targets fall below the comfortable touch size. The
desktop has no equivalent pressure: §14 puts its seven destinations on a
vertical nav rail, where there is room.

## Decision

One bottom-bar item, **Sky**, in the slot §13.1 reserved for Map. Behind it,
a `PrimaryTabRow` with Map · Timeline · Sky chart · Aurora.

The bottom bar keeps §13.1's four items and its order. What changed is the
breadth of one destination, not the shape of the navigation.

Tap-through from any of the four goes to the existing
`event/{occurrenceId}` route rather than to a detail pane. The desktop shows
`EventDetailPane` beside the chart because it has a second pane to put it
in; Android already has a detail route and a back stack, and inventing a
pane would give the same event two presentations on one platform.

The selected tab is `rememberSaveable`, so it survives rotation and process
death. These are exploratory views — losing the one you were reading because
the screen turned is a small betrayal, and the desktop's equivalent state
survives trivially by living in `DesktopAppState`.

One `SkyViewModel` serves all four rather than one per tab. They overlap
heavily — the map, timeline and sky chart all read occurrences; the map and
aurora dashboard both read the OVATION grid — so four view-models would each
hold their own copy of the same query against the same database.

## Consequences

- §13.1's diagram is out of date on one label ("Map" is now "Sky") and on
  the footnote ("v1.1 on Android; hide behind flag" — it ships unflagged).
  This ADR is that record; per CLAUDE.md the doc is not edited to match.
- A phone user reaches any visualization in two taps (Sky, then the tab),
  against one on the desktop's rail. That is the cost of the item budget.
- §14.4's "dashboard-open forces active polling tier (§7.3.2)" is keyed on
  the tab rather than on screen entry, so opening Sky on the Map tab does
  not force an SWPC poll — only actually opening Aurora does. That is closer
  to the spec's intent than refreshing whenever the destination is entered.

## Alternatives considered

- **Four bottom-bar items plus the three existing ones.** Seven exceeds what
  `NavigationBar` is built for.
- **A Map item, with the other three under Settings or a menu.** Preserves
  §13.1 literally, but buries three views that are the point of ADR 0022 in
  a place nobody browses.
- **A navigation drawer, as the desktop uses a rail.** Room for all seven,
  but it replaces a persistent, one-tap bottom bar with a hidden menu for
  every destination including the three that work well today. Too large a
  change to the app's navigation to make in service of four new screens.
- **Ship only the Map tab in the reserved slot**, per §13.1 as written.
  That is ADR 0022's rejected alternative, not this one's.
