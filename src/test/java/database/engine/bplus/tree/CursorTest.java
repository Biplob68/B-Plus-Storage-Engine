package database.engine.bplus.tree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.linkSiblings;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.leafOf;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {

    private static BPlusTree treeWith(HeapPageStore store, int count, int valueLength) {
        BPlusTree tree = BPlusTree.create(store);
        for (int i = 0; i < count; i++) {
            tree.put(key(i), value(i, valueLength));
        }
        return tree;
    }

    private static List<byte[]> drain(Cursor cursor) {
        List<byte[]> keys = new ArrayList<>();
        while (cursor.next()) {
            keys.add(cursor.key());
        }
        return keys;
    }

    @Test
    void scanReturnsEveryKeyInOrder() {
        HeapPageStore store = new HeapPageStore();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(3));

        BPlusTree tree = BPlusTree.create(store);
        for (int i : order) {
            tree.put(key(i), value(i, 8));
        }

        List<byte[]> keys = drain(tree.scan());

        assertThat(keys).hasSize(3_000);
        for (int i = 0; i < 3_000; i++) {
            assertThat(keys.get(i)).as("position %d", i).containsExactly(key(i));
        }
        assertThat(store.borrowedCount()).as("a cursor holds no page").isZero();
    }

    @Test
    void scanCarriesValuesToo() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 500, 8);

        Cursor cursor = tree.scan();
        int seen = 0;
        while (cursor.next()) {
            assertThat(cursor.value()).as("key %d", seen).containsExactly(value(seen, 8));
            seen++;
        }
        assertThat(seen).isEqualTo(500);
    }

    @Test
    void anEmptyTreeScansNothing() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        assertThat(drain(tree.scan())).isEmpty();
    }

    @Test
    void aBoundedRangeStopsAtTheUpperBound() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 1_000, 8);

        List<byte[]> keys = drain(tree.scan(key(200), key(260)));

        assertThat(keys).hasSize(60);
        assertThat(keys.get(0)).as("lower bound is inclusive").containsExactly(key(200));
        assertThat(keys.get(59)).as("upper bound is exclusive").containsExactly(key(259));
    }

    @Test
    void boundsThatMissEveryKeyStillBehave() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 500, 8);

        assertThat(drain(tree.scan(key(9_000), null))).as("from past the end").isEmpty();
        assertThat(drain(tree.scan(null, key(0)))).as("to before the start").isEmpty();
        assertThat(drain(tree.scan(key(300), key(300)))).as("empty range").isEmpty();
        assertThat(drain(tree.scan(new byte[0], null))).as("from before every key").hasSize(500);
    }

    @Test
    void aStartKeyThatIsNotInTheTreeLandsOnTheNextOneUp() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);
        for (int i = 0; i < 200; i += 2) {
            tree.put(key(i), value(i, 8));
        }

        List<byte[]> keys = drain(tree.scan(key(51), key(59)));

        assertThat(keys).satisfiesExactly(
                k -> assertThat(k).containsExactly(key(52)),
                k -> assertThat(k).containsExactly(key(54)),
                k -> assertThat(k).containsExactly(key(56)),
                k -> assertThat(k).containsExactly(key(58)));
    }

    /**
     * A delete can leave an empty leaf sitting in the chain, so the walk has to step over it rather
     * than assume every leaf has entries.
     */
    @Test
    void anEmptyLeafInTheMiddleIsSkipped() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store);
        int c = leafOf(store, 50, 60);
        linkSiblings(store, a, b);
        linkSiblings(store, b, c);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        addSeparator(store, rootId, 50, c);
        TreeInvariants.check(store, rootId);

        List<byte[]> keys = drain(BPlusTree.open(store, rootId).scan());

        assertThat(keys).satisfiesExactly(
                k -> assertThat(k).containsExactly(key(10)),
                k -> assertThat(k).containsExactly(key(20)),
                k -> assertThat(k).containsExactly(key(50)),
                k -> assertThat(k).containsExactly(key(60)));
    }

    @Test
    void scanningAfterDeletesMatchesWhatIsLeft() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 1_000, 8);
        for (int i = 0; i < 1_000; i += 3) {
            tree.delete(key(i));
        }

        List<byte[]> keys = drain(tree.scan());

        assertThat(keys).hasSize(1_000 - (1_000 + 2) / 3);
        for (byte[] k : keys) {
            assertThat(tree.get(k)).as("every scanned key is still gettable").isNotNull();
        }
    }

    @Test
    void everyRangeMatchesATreeMapSubMap() {
        HeapPageStore store = new HeapPageStore();
        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);
        BPlusTree tree = BPlusTree.create(store);
        for (int i = 0; i < 800; i++) {
            tree.put(key(i), value(i, 8));
            model.put(key(i), value(i, 8));
        }

        Random random = new Random(1234);
        for (int trial = 0; trial < 50; trial++) {
            int from = random.nextInt(800);
            int to = from + random.nextInt(800 - from + 1);

            List<byte[]> scanned = drain(tree.scan(key(from), key(to)));
            List<byte[]> expected = new ArrayList<>(model.subMap(key(from), key(to)).keySet());

            assertThat(scanned).as("range [%d, %d)", from, to).hasSameSizeAs(expected);
            for (int i = 0; i < expected.size(); i++) {
                assertThat(scanned.get(i)).containsExactly(expected.get(i));
            }
        }
    }

    @Test
    void readingBeforeOrAfterTheRangeThrows() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 10, 8);

        Cursor fresh = tree.scan();
        assertThatThrownBy(fresh::key)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("call next()");

        Cursor finished = tree.scan();
        while (finished.next()) {
            // walk to the end
        }
        assertThatThrownBy(finished::value).isInstanceOf(IllegalStateException.class);
        assertThat(finished.next()).as("staying finished is safe").isFalse();
    }
}
