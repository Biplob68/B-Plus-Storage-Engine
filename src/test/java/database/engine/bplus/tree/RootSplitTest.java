package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static database.engine.bplus.tree.TreeFixture.heightOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.scanAllKeys;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;

class RootSplitTest {

    private static PageType typeOf(HeapPageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return page.type();
        } finally {
            store.release(pageId);
        }
    }

    @Test
    void theRootKeepsItsPageIdWhenTheTreeGrows() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);
        int rootId = tree.rootPageId();

        assertThat(typeOf(store, rootId)).isEqualTo(PageType.LEAF);
        assertThat(heightOf(store, rootId)).isEqualTo(1);

        for (int i = 0; i < 400; i++) {
            tree.put(key(i), value(i, 8));
        }

        assertThat(tree.rootPageId()).as("the root id never changes").isEqualTo(rootId);
        assertThat(typeOf(store, rootId)).as("the leaf root became an internal root")
                .isEqualTo(PageType.INTERNAL);
        assertThat(heightOf(store, rootId)).isEqualTo(2);
        TreeInvariants.check(store, rootId);
    }

    @Test
    void everyLeafGainsALevelAtTheSameTime() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        // 900-byte values fit about four to a leaf, so this reaches three levels.
        for (int i = 0; i < 2_000; i++) {
            tree.put(key(i), value(i, 900));
        }

        // Invariant 1 is the check that matters: all leaves at the same depth.
        TreeInvariants.check(store, tree.rootPageId());
        assertThat(heightOf(store, tree.rootPageId())).isGreaterThanOrEqualTo(3);
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void tenThousandSequentialKeysMatchATreeMap() {
        assertMatchesTreeMap(sequential(10_000), 8);
    }

    @Test
    void tenThousandRandomKeysMatchATreeMap() {
        List<Integer> order = sequential(10_000);
        Collections.shuffle(order, new Random(4242));
        assertMatchesTreeMap(order, 8);
    }

    @Test
    void mixedValueSizesMatchATreeMap() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);
        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);

        List<Integer> order = sequential(1_500);
        Collections.shuffle(order, new Random(9));
        for (int i : order) {
            int length = 10 + (i * 37) % 890; // 10 to 899 bytes, no pattern the split can exploit
            tree.put(key(i), value(i, length));
            model.put(key(i), value(i, length));
        }

        assertContents(store, tree, model);
    }

    @Test
    void updatesAcrossASplitTreeReplaceInPlace() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        for (int i = 0; i < 1_000; i++) {
            tree.put(key(i), value(i, 8));
        }
        for (int i = 0; i < 1_000; i += 3) {
            tree.put(key(i), value(i + 1, 500));
        }

        for (int i = 0; i < 1_000; i++) {
            byte[] expected = i % 3 == 0 ? value(i + 1, 500) : value(i, 8);
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(expected);
        }
        assertThat(scanAllKeys(store, tree.rootPageId())).as("no key was duplicated by an update")
                .hasSize(1_000);
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void aScanOfTheLeafChainReturnsEveryKeyInOrder() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);
        List<Integer> order = sequential(3_000);
        Collections.shuffle(order, new Random(77));
        for (int i : order) {
            tree.put(key(i), value(i, 8));
        }

        List<byte[]> scanned = scanAllKeys(store, tree.rootPageId());

        assertThat(scanned).hasSize(3_000);
        for (int i = 0; i < 3_000; i++) {
            assertThat(scanned.get(i)).as("scan position %d", i).containsExactly(key(i));
        }
    }

    private static void assertMatchesTreeMap(List<Integer> order, int valueLength) {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);
        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);

        for (int i : order) {
            tree.put(key(i), value(i, valueLength));
            model.put(key(i), value(i, valueLength));
        }
        assertContents(store, tree, model);
    }

    private static void assertContents(HeapPageStore store, BPlusTree tree, TreeMap<byte[], byte[]> model) {
        TreeInvariants.check(store, tree.rootPageId());

        for (var entry : model.entrySet()) {
            assertThat(tree.get(entry.getKey())).containsExactly(entry.getValue());
        }
        assertThat(scanAllKeys(store, tree.rootPageId()))
                .as("the leaf chain holds exactly the model's keys, in order")
                .hasSize(model.size())
                .satisfies(scanned -> {
                    int i = 0;
                    for (byte[] expected : model.keySet()) {
                        assertThat(scanned.get(i++)).containsExactly(expected);
                    }
                });
        assertThat(store.borrowedCount()).isZero();
    }

    private static List<Integer> sequential(int count) {
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        return order;
    }
}
