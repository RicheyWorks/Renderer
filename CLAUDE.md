# Renderer — working notes for agents

## What it is
The ecosystem's fifth engine: materialized views folded from SmokeHouse's tail into CSRBT-held
ranked aggregates. Two classes: `Renderer` (facade, registration, lifecycle) and `GroupView`
(the fold + the ranked read surface). Strictly public-surface composition over SmokeHouse.

## Build & test
- Nested composite: requires `../SmokeHouse`, `../SuperBeefSort`, `../CSRBT` as siblings.
  `./gradlew build` runs everything (Gradle 9 wrapper; JVM 17+).
- Tests are seeded double-oracle (`TreeMap` fold reference in `RendererTest`) — required style
  for new view behavior. Tail delivery is on a background thread: every assertion block is
  gated by `awaitCaughtUp`; never assert view state after a write without it.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.

## Invariants (do not break)
- **The fold must stay replace-idempotent.** Per-key memory (`key → (group, weight)`) is what
  makes the subscribe-then-sweep bootstrap race-free. Any new aggregate must be expressible as
  "retract the key's old contribution, add the new one" — no fold that needs event-count
  exactness (that would need snapshot fencing SmokeHouse doesn't expose).
- **A view is a cache.** Never persist view state; re-registration rebuilds from the store.
- **Gapped views fail loudly.** `onGap` marks the view; reads throw until re-registered.
  v1 does not auto-heal — don't silently rebuild inside a read path.
- **One writer per view** (the tail thread); readers synchronize on the view. No background
  threads of Renderer's own — the tail thread belongs to SmokeHouse.

## Roadmap seams (measure before cutting — the ring's rule)
- More folds: min/max per group (needs per-group multiset, still replace-idempotent),
  sliding-window variants (productizing SuperBeefSort's percentile demo).
- Auto-refold on gap — behind a policy flag, never default-silent.
- Carver over views: expose a view's ranked set through an `IndexedStore`-shaped read facade
  so Carver can cost view walks against primary walks.
- JMH once there's a question: fold throughput vs write throughput (does a slow view gap
  under the demo dashboard's write rate?).
