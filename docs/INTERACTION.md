# Interaction and information design

How the console looks, moves and shows a running system.

## Direction

**Instrument glass.** The console is a precise instrument that sits in light:
calm surfaces, one accent, and motion that carries you from one place to the
next instead of cutting between screens. It stays white and coral, and nothing
on it is decoration: no particles, no cursor trails, no 3D objects, nothing that
loops unless something is happening.

- **One light.** The page sits in a single coral light from the top left with a
  cool counter-light low on the right, behind a fine grain. The two lights drift
  a few pixels against the pointer, at different depths, so the glass in front
  reads as standing off a lit wall. (`shell/Ambient.tsx`)
- **Type.** Geist for text, Geist Mono for ids and code. Page titles are 27 px
  and tight; figures are tabular so they do not jitter as they update.
- **Glass.** Cards are frosted panes, solid enough for figures to read crisply,
  with 18 px corners. A pane you can open rises toward the pointer, and its rim
  catches the light where the pointer is. Read-only panes do neither.
- **Overview first, details on demand, every view linked.** Each run opens with
  findings (one sentence per thing worth knowing, worst first) and its execution
  as a waterfall; choosing anything selects it in every view.

## The shell

| Part | What it does |
|---|---|
| Sidebar (`shell/Sidebar.tsx`) | Every section, always in view. A pane glides to the page you choose; a lighter one follows the pointer. Folds to a rail of icons (named on hover); a drawer on a phone, dragged or Esc to close. |
| Bar (`shell/TopBar.tsx`) | Where you are, as a breadcrumb whose words roll to the new place. Frosts as content scrolls under it, in proportion to the scroll. Holds the page's guide and demo. |
| Palette (`shell/CommandPalette.tsx`) | Ctrl/⌘ K. Grows out of the search field and folds back into it. Every page and view (by name or address), the app in view, theme, operator access, sign out. |
| Map (`shell/nav.ts`) | The one list all three read, so a page cannot be in one and missing from another. |

## Navigation

Every navigation runs in a view transition (`system/navigation.ts`). The shell
is never part of the picture, so the sidebar marker and breadcrumb animate live
while the page changes beneath them.

| How you got here | What moves |
|---|---|
| Sidebar or palette | The old page lifts away; the new one rises in. |
| A sibling tab | The page slides toward the tab you chose. |
| A card or row that opens something (`data-morph`) | It grows into the new page's header (`data-morph-target`, or the page's own header), and its title flies into the new title (`data-morph-title` on both). |
| Back | The reverse: the header shrinks onto the card or row that opened it, if it is on screen. |

Pages then arrive in reading order: blocks rise a few tens of milliseconds
apart, a page title is revealed through a mask, and blocks below the fold rise
as they are scrolled to (`enterPage`). Pages start downloading when the pointer
reaches a link, so the click starts the transition at once.

Two things a morph needs, learned the hard way:

- **The target exists in the first frame.** A detail page renders its header
  from what the list already knew (passed as router state) while the rest loads.
- **The target stays the same element.** If the loading header is swapped for
  another element mid-transition, the browser abandons the transition. Render
  one header for both states.

## Shared parts

| Part | Where | What it is |
|---|---|---|
| `system/physics/transitions.ts` | everything animated with Motion | the spring presets as Motion transitions, so CSS, hand-painted springs and Motion share one physics |
| `system/interaction/HoverHighlight.tsx` | sidebar, menus, run lists, findings, tab strips | one highlight that slides from item to item under the pointer |
| `system/interaction/Popover.tsx` | app switcher, account menu | a menu that grows out of its trigger; full keyboard behaviour |
| `ActionButton` (`system/controls.tsx`) | start workflow, run optimization, add endpoint | reports working, done and failed by changing shape; shakes once on failure |
| `[data-magnetic]` (`system/physics/magnet.ts`) | primary buttons | leans a few pixels toward the pointer and springs back |
| `system/execution/model.ts`, `findings.ts` | run views | the run as lanes, attempts, waits and critical path; findings with targets |
| `components/Waterfall.tsx`, `RunDetail.tsx` | verification and workflow runs | the waterfall; time budget, a step's facts, the findings (which expand into their evidence and fold back) |

## Rules for motion

- **Springs only.** Every duration and curve is a token from
  `system/physics/tokens.ts`. Nothing states its own 300 ms.
- **Motion shows a change of place or state.** A click, a selection, a value
  arriving, a page changing. Data never overshoots: a bar that bounced past its
  value would, for a moment, show a number that is not true.
- **Hover is quiet.** A highlight slides under the pointer, a card you can open
  lifts a little, a primary button leans a few pixels. Nothing appears, spins or
  sparkles on hover.
- **Anything that pulses means something is happening now.** Once a run has
  finished, it stays still.
- **Interruptible.** A navigation during a transition starts the next one; a
  menu reopened while closing turns around from where it is.

## Performance

Measured in Chromium on a machine without a GPU, against the build before the
redesign on the same machine: idle and scrolling 60 fps, as before. Navigation
runs at 55–60 fps there because transitions are off on such machines (below);
forced on, it averages about 45 fps with one long frame for the snapshot. With
a GPU the snapshot is a texture copy.

- No backdrop blur where nothing sharp passes behind (the sidebar sits over the
  ambient light only). Blur stays on the bar, menus, the palette and the drawer.
- Where the page is drawn in software, a view transition's snapshot costs a
  visible stall, so navigation there is instant and only the entrance plays.
  `localStorage["continuum.motion"] = "full"` keeps transitions anyway.
- Motion's animation features load after first paint. The first script is
  40 KB gzipped larger than before the redesign.

## Accessibility

- With reduced motion set, navigation is instant (no view transition, no
  entrance), Motion's transforms are instant, and the ambient light is still.
  Nothing is lost: no information lives in motion alone.
- The skip link, the menus, the palette and the drawer are fully usable from the
  keyboard; focus moves onto each new page and back to whatever opened a menu.
- axe-core reports no violations on the Command Centre, verification runs,
  workflow runs, Autopilot, the developer portal, sign-in or the landing page,
  in either theme.
