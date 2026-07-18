package io.github.richeyworks.renderer;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Double-oracle tests in the house style: every view read is checked against a brute-force
 * fold of a {@code TreeMap} reference. Values look like {@code "city:amount"}; the count view
 * groups by city, the sum view weighs by amount. Seeded and deterministic; the only
 * concession to the tail thread is {@code awaitCaughtUp} before each assertion block.
 */
class RendererTest {

    private static final String[] CITIES = {"austin", "boise", "cleveland", "denver", "erie"};
    private static final long AWAIT = 5_000;

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    private static String value(String city, int amount) {
        return city + ":" + amount;
    }

    private static String cityOf(String v) {
        return v.substring(0, v.indexOf(':'));
    }

    private static long amountOf(String v) {
        return Long.parseLong(v.substring(v.indexOf(':') + 1));
    }

    /** Brute-force fold of the live map: group → (count, sum). */
    private static TreeMap<String, long[]> fold(Map<Long, String> live) {
        TreeMap<String, long[]> totals = new TreeMap<>();
        for (String v : live.values()) {
            long[] t = totals.computeIfAbsent(cityOf(v), c -> new long[2]);
            t[0] += 1;
            t[1] += amountOf(v);
        }
        return totals;
    }

    /** Oracle top-k: totals descending, ties by group descending — the view's documented order. */
    private static List<String> expectedTop(TreeMap<String, long[]> totals, int slot, int k) {
        List<String> groups = new ArrayList<>(totals.keySet());
        groups.sort(Comparator.<String>comparingLong(g -> totals.get(g)[slot])
                .thenComparing(Comparator.naturalOrder()).reversed());
        return groups.subList(0, Math.min(k, groups.size()));
    }

    private static void assertAgrees(TreeMap<String, long[]> oracle,
                                     GroupView<Long, String, String> counts,
                                     GroupView<Long, String, String> sums) {
        assertEquals(oracle.size(), counts.groups());
        assertEquals(oracle.size(), sums.groups());
        for (String city : CITIES) {
            long[] t = oracle.getOrDefault(city, new long[2]);
            assertEquals(t[0], counts.total(city), "count(" + city + ")");
            assertEquals(t[1], sums.total(city), "sum(" + city + ")");
        }
        assertEquals(expectedTop(oracle, 0, 3), counts.top(3), "top3 by count");
        assertEquals(expectedTop(oracle, 1, 3), sums.top(3), "top3 by sum");
    }

    @Test
    void liveFoldMatchesTheBruteForceOracle(@TempDir Path dir) throws IOException {
        Random rnd = new Random(42);
        TreeMap<Long, String> live = new TreeMap<>();
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Renderer<Long, String> renderer = Renderer.over(store)) {
            GroupView<Long, String, String> counts =
                    renderer.countBy("counts", RendererTest::cityOf, Comparator.naturalOrder());
            GroupView<Long, String, String> sums = renderer.sumBy("sums",
                    RendererTest::cityOf, RendererTest::amountOf, Comparator.naturalOrder());

            for (int i = 0; i < 1_500; i++) {
                long key = rnd.nextInt(200);                       // collisions = group moves
                String v = value(CITIES[rnd.nextInt(CITIES.length)], rnd.nextInt(1_000));
                store.put(key, v);
                live.put(key, v);
                if (rnd.nextInt(8) == 0) {
                    long dead = rnd.nextInt(200);
                    store.delete(dead);
                    live.remove(dead);
                }
            }
            assertTrue(renderer.awaitCaughtUp(AWAIT), "views must catch the tail");
            assertAgrees(fold(live), counts, sums);
        }
    }

    @Test
    void lateRegistrationSweepsThenRidesTheTail(@TempDir Path dir) throws IOException {
        Random rnd = new Random(7);
        TreeMap<Long, String> live = new TreeMap<>();
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Renderer<Long, String> renderer = Renderer.over(store)) {
            for (int i = 0; i < 400; i++) {                        // history BEFORE any view
                long key = rnd.nextInt(120);
                String v = value(CITIES[rnd.nextInt(CITIES.length)], rnd.nextInt(1_000));
                store.put(key, v);
                live.put(key, v);
            }
            GroupView<Long, String, String> counts =
                    renderer.countBy("counts", RendererTest::cityOf, Comparator.naturalOrder());
            GroupView<Long, String, String> sums = renderer.sumBy("sums",
                    RendererTest::cityOf, RendererTest::amountOf, Comparator.naturalOrder());
            assertTrue(renderer.awaitCaughtUp(AWAIT));
            assertAgrees(fold(live), counts, sums);                // the base sweep alone

            for (int i = 0; i < 400; i++) {                        // then live on top of it
                long key = rnd.nextInt(120);
                if (rnd.nextInt(5) == 0) {
                    store.delete(key);
                    live.remove(key);
                } else {
                    String v = value(CITIES[rnd.nextInt(CITIES.length)], rnd.nextInt(1_000));
                    store.put(key, v);
                    live.put(key, v);
                }
            }
            assertTrue(renderer.awaitCaughtUp(AWAIT));
            assertAgrees(fold(live), counts, sums);
        }
    }

    @Test
    void overwritesMoveContributionsBetweenGroups(@TempDir Path dir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Renderer<Long, String> renderer = Renderer.over(store)) {
            GroupView<Long, String, String> sums = renderer.sumBy("sums",
                    RendererTest::cityOf, RendererTest::amountOf, Comparator.naturalOrder());
            store.put(1L, value("austin", 100));
            store.put(1L, value("boise", 40));                     // moves the whole contribution
            assertTrue(renderer.awaitCaughtUp(AWAIT));
            assertEquals(0, sums.total("austin"));
            assertEquals(40, sums.total("boise"));
            assertEquals(1, sums.groups(), "zero-total groups leave the surface");
        }
    }

    @Test
    void deletesRetractAndEmptyGroupsVanish(@TempDir Path dir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Renderer<Long, String> renderer = Renderer.over(store)) {
            GroupView<Long, String, String> counts =
                    renderer.countBy("counts", RendererTest::cityOf, Comparator.naturalOrder());
            store.put(1L, value("austin", 1));
            store.put(2L, value("austin", 2));
            store.put(3L, value("boise", 3));
            store.delete(1L);
            store.delete(2L);
            assertTrue(renderer.awaitCaughtUp(AWAIT));
            assertEquals(0, counts.total("austin"));
            assertEquals(1, counts.groups());
            assertEquals(List.of("boise"), counts.top(5));
        }
    }

    @Test
    void percentileRidesTheRankedSurface(@TempDir Path dir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts());
             Renderer<Long, String> renderer = Renderer.over(store)) {
            GroupView<Long, String, String> counts =
                    renderer.countBy("counts", RendererTest::cityOf, Comparator.naturalOrder());
            long key = 0;
            for (int c = 0; c < CITIES.length; c++) {              // distinct counts 1..5
                for (int i = 0; i <= c; i++) {
                    store.put(key++, value(CITIES[c], 1));
                }
            }
            assertTrue(renderer.awaitCaughtUp(AWAIT));
            assertEquals(counts.top(1).get(0), counts.percentileGroup(100),
                    "p100 of the ranked surface is the top group");
        }
    }
}
