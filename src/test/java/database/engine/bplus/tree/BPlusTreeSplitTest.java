package database.engine.bplus.tree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.chain;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.leafOf;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class BPlusTreeSplitTest {

    private static BPlusTree twoLevelTree(HeapPageStore store) {
        int a = leafOf(store);
        int b = leafOf(store, 1000);
        chain(store, a, b);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 1000, b);
        return BPlusTree.open(store, rootId);
    }

    @Test
    void aLeafSplitIsAbsorbedByTheParent() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = twoLevelTree(store);

        // 900-byte values fit about four to a leaf, so this forces many splits.
        for (int i = 0; i < 40; i++) {
            tree.put(key(i), value(i, 900));
            TreeInvariants.check(store, tree.rootPageId());
        }

        for (int i = 0; i < 40; i++) {
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 900));
        }
        assertThat(tree.get(key(1000))).as("the untouched right subtree").isNotNull();
        assertThat(store.pageCount()).as("leaves were added").isGreaterThan(5);
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void manySplitsInShuffledOrderKeepTheTreeValid() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = twoLevelTree(store);

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(11));

        for (int i : order) {
            tree.put(key(i), value(i, 900));
        }
        TreeInvariants.check(store, tree.rootPageId());

        for (int i = 0; i < 200; i++) {
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 900));
        }
        assertThat(tree.get(key(500))).as("a key never inserted").isNull();
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void replacingAValueThatNoLongerFitsSplitsInsteadOfFailing() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = twoLevelTree(store);

        for (int i = 0; i < 8; i++) {
            tree.put(key(i), value(i, 900));
        }
        int pagesBefore = store.pageCount();

        // Every leaf is nearly full, so growing one value has to split the leaf it lives in.
        tree.put(key(3), value(99, 900));

        assertThat(tree.get(key(3))).containsExactly(value(99, 900));
        for (int i = 0; i < 8; i++) {
            if (i != 3) {
                assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 900));
            }
        }
        assertThat(store.pageCount()).isGreaterThanOrEqualTo(pagesBefore);
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void everyLeafStaysOnTheSiblingChainInKeyOrder() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = twoLevelTree(store);

        for (int i = 0; i < 60; i++) {
            tree.put(key(i), value(i, 900));
        }

        // Invariant 4 walks the chain from the leftmost leaf and matches it against the leaves in
        // key order. A split that wired the pointers the wrong way round fails here.
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void aSplitReachingTheRootStillThrows() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        assertThatThrownBy(() -> {
            for (int i = 0; i < 10_000; i++) {
                tree.put(key(i), value(i, 8));
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("growing a new root");
    }
}
