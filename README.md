# Renderer

[![CI](https://github.com/RicheyWorks/Renderer/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/Renderer/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

The fifth engine of the ecosystem: the **materialized-view engine** — where the drippings are
collected and rendered down. [SmokeHouse](../SmokeHouse) preserves, [CSRBT](../CSRBT) orders,
[SuperBeefSort](../SuperBeefSort) feeds, [Carver](../Carver) decides how to read — **Renderer
keeps derived aggregates continuously true** by folding SmokeHouse's tail, as the first
load-bearing consumer of the Phase 7 tail primitive.

```java
try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts);
     Renderer<Long, String> renderer = Renderer.over(store)) {

    var orders  = renderer.countBy("ordersByCity",  v -> cityOf(v), Comparator.naturalOrder());
    var revenue = renderer.sumBy("revenueByCity",   v -> cityOf(v), v -> centsOf(v),
                                 Comparator.naturalOrder());

    // writes flow through the store; views stay current off the tail
    revenue.top(5);              // CSRBT order-statistics walk, O(k log n)
    orders.total("austin");
    orders.percentileGroup(99);
}
```

## Design notes

- **The fold is replace-idempotent.** Each view keeps per-key memory (`key → (group, weight)`),
  so replays are no-ops. That makes registration race-free with no snapshot fencing: subscribe
  to the tail first, then base-sweep the current store — overlapping mutations fold at most
  twice with the same result. `awaitCaughtUp` gates reads after a write burst.
- **A view is a cache.** Nothing persists; re-registering rebuilds from the store, and the
  store rebuilds from the log — the ecosystem's one doctrine, one level up. A view that gaps
  (slow consumer, ring overrun) fails loudly on every read until re-registered.
- **Ranked reads are CSRBT order statistics.** Totals live in an `OrderedSet` keyed by
  `(total, group)`, so top-k, and percentile are tree walks, not sorts.
- **Single writer per view.** The tail thread is the only writer; readers synchronize on the
  view. Composition, not modification — public SmokeHouse surfaces only.

## Roadmap (measure before cutting)

More folds (min/max per group, sliding windows), auto-refold on gap behind an explicit policy
flag, and an `IndexedStore`-shaped read facade so [Carver](https://github.com/RicheyWorks/Carver)
can cost view walks against primary walks — each lands with its own oracle test, and JMH
arrives with the first real fold-throughput question.

## The ecosystem

| Engine | Role |
|---|---|
| [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index |
| [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profiles, sorts, feeds |
| [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — tail, watchers, replicas |
| [Carver](https://github.com/RicheyWorks/Carver) | the read planner — decides how to read |
| **Renderer** (this repo) | the materialized-view engine over the tail |
| [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache with an evolved eviction policy |

Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).
Engine 12: [WholeHog](https://github.com/RicheyWorks/WholeHog) — the integration organism: all of them, composed and asserted together.

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Tests are seeded double-oracle (`TreeMap` fold
reference) in the house style. MIT license.
