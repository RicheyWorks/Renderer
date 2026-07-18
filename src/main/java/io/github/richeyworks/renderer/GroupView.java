package io.github.richeyworks.renderer;

import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.TailEvent;
import io.github.richeyworks.smokehouse.TailListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * One materialized view: a fold of the store's {@linkplain SmokeHouse#tail tail} into per-group
 * totals, with the ranked surface held in a CSRBT {@link OrderedSet} so top-k, median, and
 * percentile reads are order-statistics walks, not sorts.
 *
 * <h2>The fold is replace-idempotent — that is the whole registration story</h2>
 * The view keeps per-key memory ({@code key → (group, weight)}), so applying a put is
 * "retract the key's old contribution, add the new one" and applying a delete is "retract if
 * present". Replaying an event whose effect is already folded is therefore a no-op. That makes
 * the bootstrap race-free without any snapshot fencing: subscribe to the tail from
 * {@code tailSequence()} <em>first</em>, then base-sweep the current store state; any mutation
 * that commits during the sweep is folded at most twice with the same result. Mid-bootstrap
 * reads may see a transient regression — call {@link #awaitCaughtUp} before trusting reads.
 *
 * <p><b>A view is a cache.</b> Nothing here persists; re-registering rebuilds from the store
 * (and the store rebuilds from the log — the ecosystem's one doctrine, one level up). If the
 * tail drops events for a slow view ({@link #onGap()}), the view marks itself gapped and every
 * read fails loudly until it is re-registered — v1 does not auto-heal, honestly.</p>
 *
 * <p><b>Threading:</b> the tail thread is the view's single writer; readers synchronize on the
 * view. Same single-writer stance as every engine in the ring, one writer per view.</p>
 */
public final class GroupView<K, V, G> implements TailListener<K, V>, AutoCloseable {

    /** One ranked slot: ascending (total, group) — CSRBT order statistics do the rest. */
    record Ranked<G>(long total, G group) { }

    private final String name;
    private final Function<V, G> groupOf;
    private final ToLongFunction<V> weightOf;
    private final Comparator<? super G> order;

    private final Map<K, G> groupOfKey = new HashMap<>();
    private final Map<K, Long> weightOfKey = new HashMap<>();
    private final TreeMap<G, Long> totals;
    private final OrderedSet<Ranked<G>> ranked;

    private volatile long appliedSequence;
    private volatile boolean gapped;
    private AutoCloseable handle;

    GroupView(String name, Function<V, G> groupOf, ToLongFunction<V> weightOf,
              Comparator<? super G> order) {
        this.name = name;
        this.groupOf = groupOf;
        this.weightOf = weightOf;
        this.order = order;
        this.totals = new TreeMap<>(order);
        Comparator<Ranked<G>> byTotalThenGroup = Comparator
                .<Ranked<G>>comparingLong(Ranked::total)
                .thenComparing(Ranked::group, order);
        this.ranked = new OrderedSet<>(new RedBlackStrategy<>(), byTotalThenGroup);
    }

    /** Subscribe-then-sweep bootstrap; see the class javadoc for why the order is safe. */
    void register(SmokeHouse<K, V> store) throws IOException {
        long seq0 = store.tailSequence();
        appliedSequence = seq0 - 1;
        handle = store.tail(seq0, this);
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), this::applySweep);
        }
    }

    // ── The fold (tail thread only) ─────────────────────────────────────────────

    @Override
    public void onEvent(TailEvent<K, V> event) {
        synchronized (this) {
            if (event.deleted()) {
                retract(event.key());
            } else {
                apply(event.key(), event.value());
            }
            appliedSequence = event.sequence();
        }
    }

    @Override
    public void onGap() {
        gapped = true;
    }

    private synchronized void applySweep(K key, V value) {
        apply(key, value);
    }

    private void apply(K key, V value) {
        G group = groupOf.apply(value);
        long weight = weightOf.applyAsLong(value);
        retract(key);
        groupOfKey.put(key, group);
        weightOfKey.put(key, weight);
        addTotal(group, weight);
    }

    private void retract(K key) {
        G old = groupOfKey.remove(key);
        if (old != null) {
            addTotal(old, -weightOfKey.remove(key));
        }
    }

    private void addTotal(G group, long delta) {
        Long old = totals.get(group);
        if (old != null) {
            ranked.remove(new Ranked<>(old, group));
        }
        long now = (old == null ? 0 : old) + delta;
        if (now == 0) {
            totals.remove(group);
        } else {
            totals.put(group, now);
            ranked.add(new Ranked<>(now, group));
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    /** The current total (count or sum) for {@code group}; 0 if absent. */
    public synchronized long total(G group) {
        requireHealthy();
        Long t = totals.get(group);
        return t == null ? 0 : t;
    }

    /** The number of live groups. */
    public synchronized int groups() {
        requireHealthy();
        return totals.size();
    }

    /**
     * The top {@code k} groups by total, descending (ties broken by group order, descending) —
     * a CSRBT order-statistics walk, O(k log n).
     */
    public synchronized List<G> top(int k) {
        requireHealthy();
        int n = ranked.size();
        List<G> out = new ArrayList<>(Math.min(k, n));
        for (int rank = n; rank > n - Math.min(k, n); rank--) {
            out.add(ranked.select(rank).group());
        }
        return out;
    }

    /** The group at the given percentile of the ranked-by-total order (1–100). */
    public synchronized G percentileGroup(int pct) {
        requireHealthy();
        Ranked<G> r = ranked.percentile(pct);
        return r == null ? null : r.group();
    }

    /** The last tail sequence folded in ({@code registrationSequence - 1} before any event). */
    public long appliedSequence() {
        return appliedSequence;
    }

    /** True once the tail dropped events for this view; reads fail loudly from then on. */
    public boolean gapped() {
        return gapped;
    }

    /**
     * Block until every mutation committed before this call is folded in (or the timeout
     * passes). Returns true when caught up. Call before trusting reads after a write burst.
     */
    public boolean awaitCaughtUp(SmokeHouse<K, V> store, long timeoutMillis) {
        long target = store.tailSequence() - 1;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (appliedSequence < target && !gapped) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !gapped;
    }

    private void requireHealthy() {
        if (gapped) {
            throw new IllegalStateException("view '" + name + "' gapped: the tail dropped "
                    + "events it never folded. Re-register the view to re-fold from the store "
                    + "(v1 does not auto-heal).");
        }
    }

    @Override
    public void close() {
        try {
            if (handle != null) {
                handle.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("closing tail handle for view '" + name + "'", e);
        }
    }
}
