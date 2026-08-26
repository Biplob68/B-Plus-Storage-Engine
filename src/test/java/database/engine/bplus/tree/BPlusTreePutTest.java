package database.engine.bplus.tree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class BPlusTreePutTest {

    @Test
    void putThenGet() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        tree.put(key(10), value(10, 8));
        tree.put(key(30), value(30, 8));
        tree.put(key(20), value(20, 8));

        assertThat(tree.get(key(10))).containsExactly(value(10, 8));
        assertThat(tree.get(key(20))).containsExactly(value(20, 8));
        assertThat(tree.get(key(30))).containsExactly(value(30, 8));
        assertThat(tree.get(key(25))).isNull();
        assertThat(store.borrowedCount()).isZero();
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void keysComeBackSortedWhateverOrderTheyWentIn() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(7));

        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);
        for (int i : order) {
            tree.put(key(i), value(i, 8));
            model.put(key(i), value(i, 8));
            TreeInvariants.check(store, tree.rootPageId());
        }

        for (var entry : model.entrySet()) {
            assertThat(tree.get(entry.getKey())).containsExactly(entry.getValue());
        }
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void putReplacesAnExistingValue() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        tree.put(key(10), value(1, 8));
        tree.put(key(20), value(2, 8));
        tree.put(key(10), value(9, 8));

        assertThat(tree.get(key(10))).containsExactly(value(9, 8));
        assertThat(tree.get(key(20))).as("neighbour untouched").containsExactly(value(2, 8));
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void replacementCanGrowOrShrinkTheValue() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        tree.put(key(10), value(1, 8));
        tree.put(key(20), value(2, 8));

        tree.put(key(10), value(3, 600));
        assertThat(tree.get(key(10))).containsExactly(value(3, 600));

        tree.put(key(10), value(4, 2));
        assertThat(tree.get(key(10))).containsExactly(value(4, 2));
        assertThat(tree.get(key(20))).containsExactly(value(2, 8));
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void aReplacementThatCannotFitLeavesTheOldValueAlone() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        int stored = fillTheRootLeaf(tree);

        // Deleting key 0 frees only its own small cell, nowhere near enough for a 900-byte value.
        assertThatThrownBy(() -> tree.put(key(0), value(0, 900)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full");

        assertThat(tree.get(key(0))).as("old value survives a failed replace").containsExactly(value(0, 8));
        assertThat(tree.get(key(stored - 1))).isNotNull();
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void aFullLeafThrowsUntilSplittingExists() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        int stored = fillTheRootLeaf(tree);

        assertThat(stored).isGreaterThan(100);
        assertThatThrownBy(() -> tree.put(key(stored), value(stored, 8)))
                .isInstanceOf(IllegalStateException.class);

        // everything already stored is still readable
        for (int i = 0; i < stored; i++) {
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 8));
        }
        assertThat(store.pageCount()).as("no split happened").isEqualTo(1);
    }

    @Test
    void emptyKeysAndValuesWork() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        tree.put(new byte[0], new byte[0]);
        tree.put(key(10), value(10, 8));

        assertThat(tree.get(new byte[0])).isEmpty();
        assertThat(tree.get(key(10))).containsExactly(value(10, 8));
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void anOversizedPairIsRejectedBeforeAnythingIsWritten() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        assertThatThrownBy(() -> tree.put(key(10), new byte[2000]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overflow pages");

        assertThat(tree.get(key(10))).isNull();
        assertThat(store.borrowedCount()).isZero();
    }


    private static int fillTheRootLeaf(BPlusTree tree) {
        int i = 0;
        try {
            for (; i < 10_000; i++) {
                tree.put(key(i), value(i, 8));
            }
        } catch (IllegalStateException expected) {
            return i;
        }
        throw new AssertionError("the leaf never filled up");
    }
}
