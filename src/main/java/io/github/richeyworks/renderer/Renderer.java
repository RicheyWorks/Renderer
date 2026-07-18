package io.github.richeyworks.renderer;

import io.github.richeyworks.smokehouse.SmokeHouse;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Renderer — the fifth engine of the ecosystem: the materialized-view engine, where the
 * drippings are collected and rendered down. SmokeHouse preserves, CSRBT orders,
 * SuperBeefSort feeds, Carver decides how to read; <b>Renderer keeps derived aggregates
 * continuously true</b> by folding the store's {@linkplain SmokeHouse#tail tail} — the first
 * load-bearing consumer of the Phase 7 primitive.
 *
 * <pre>
 *   try (SmokeHouse&lt;Long, Order&gt; store = SmokeHouse.open(dir, opts);
 *        Renderer&lt;Long, Order&gt; renderer = Renderer.over(store)) {
 *       GroupView&lt;Long, Order, String&gt; orders  = renderer.countBy("ordersByCity",
 *               Order::city, Comparator.naturalOrder());
 *       GroupView&lt;Long, Order, String&gt; revenue = renderer.sumBy("revenueByCity",
 *               Order::city, Order::cents, Comparator.naturalOrder());
 *       // ... writes flow through the store; views stay current off the tail ...
 *       revenue.top(5);            // CSRBT order-statistics walk
 *   }
 * </pre>
 *
 * <p>Views are caches over the log's truth: registration folds the current store state, the
 * tail keeps the fold current, and nothing about a view is ever persisted — re-registering
 * rebuilds it, the way every index in the ecosystem rebuilds from segments. Composition, not
 * modification: Renderer touches only public SmokeHouse surfaces and holds no store lock.</p>
 */
public final class Renderer<K, V> implements Closeable {

    private final SmokeHouse<K, V> store;
    private final List<GroupView<K, V, ?>> views = new ArrayList<>();

    private Renderer(SmokeHouse<K, V> store) {
        this.store = store;
    }

    /** A renderer over {@code store}. Close it to unsubscribe every view from the tail. */
    public static <K, V> Renderer<K, V> over(SmokeHouse<K, V> store) {
        return new Renderer<>(Objects.requireNonNull(store, "store"));
    }

    /**
     * Register a live count-per-group view: {@code groupOf} buckets each record, the view
     * keeps {@code count(group)} continuously true. Bootstrap folds the store's current
     * state, then the tail takes over.
     */
    public <G> GroupView<K, V, G> countBy(String name, Function<V, G> groupOf,
                                          Comparator<? super G> order) throws IOException {
        return sumBy(name, groupOf, v -> 1L, order);
    }

    /**
     * Register a live sum-per-group view: {@code weightOf} is each record's contribution
     * (a count view is a sum view with weight 1). Weights may be negative; a group whose
     * total reaches zero leaves the ranked surface.
     */
    public <G> GroupView<K, V, G> sumBy(String name, Function<V, G> groupOf,
                                        ToLongFunction<V> weightOf,
                                        Comparator<? super G> order) throws IOException {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(groupOf, "groupOf");
        Objects.requireNonNull(weightOf, "weightOf");
        Objects.requireNonNull(order, "order");
        GroupView<K, V, G> view = new GroupView<>(name, groupOf, weightOf, order);
        view.register(store);
        synchronized (views) {
            views.add(view);
        }
        return view;
    }

    /** Await every registered view catching up to the store's current tail sequence. */
    public boolean awaitCaughtUp(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (views) {
            for (GroupView<K, V, ?> v : views) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0 || !v.awaitCaughtUp(store, left)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Unsubscribe every view from the tail. The store itself is not closed. */
    @Override
    public void close() {
        synchronized (views) {
            for (GroupView<K, V, ?> v : views) {
                v.close();
            }
            views.clear();
        }
    }
}
