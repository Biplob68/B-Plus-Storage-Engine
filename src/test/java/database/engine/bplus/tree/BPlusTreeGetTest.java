package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.chain;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.leafOf;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class BPlusTreeGetTest {

    @Test
    void emptyTreeFindsNothing() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        assertThat(tree.get(key(1))).isNull();
        assertThat(tree.get(new byte[0])).isNull();
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void createStartsWithASingleLeafRoot() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        SlottedPage root = store.get(tree.rootPageId());
        assertThat(root.type()).isEqualTo(PageType.LEAF);
        assertThat(root.cellCount()).isZero();
        store.release(tree.rootPageId());
    }

    @Test
    void readsFromASingleLeafRoot() {
        HeapPageStore store = new HeapPageStore();
        int rootId = leafOf(store, 10, 20, 30);
        BPlusTree tree = BPlusTree.open(store, rootId);

        assertThat(tree.get(key(10))).containsExactly(value(10));
        assertThat(tree.get(key(30))).containsExactly(value(30));
        assertThat(tree.get(key(25))).isNull();
        assertThat(store.borrowedCount()).isZero();
    }

    /**
     * <pre>
     *   root:  leftmost -> A,  30 -> B,  50 -> C
     *   A [10 20] -> B [30 40] -> C [50 60]
     * </pre>
     */
    @Test
    void readsThroughOneInternalLevel() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        int c = leafOf(store, 50, 60);
        chain(store, a, b);
        chain(store, b, c);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        addSeparator(store, rootId, 50, c);

        BPlusTree tree = BPlusTree.open(store, rootId);

        for (int k : new int[]{10, 20, 30, 40, 50, 60}) {
            assertThat(tree.get(key(k))).as("key %d", k).containsExactly(value(k));
        }
        assertThat(tree.get(key(5))).as("below every key").isNull();
        assertThat(tree.get(key(25))).as("gap inside a leaf's range").isNull();
        assertThat(tree.get(key(70))).as("above every key").isNull();
        assertThat(store.borrowedCount()).isZero();
    }

    /**
     * <pre>
     *   root:  leftmost -> L,  50 -> R
     *   L:     leftmost -> A,  30 -> B
     *   R:     leftmost -> C,  70 -> D
     * </pre>
     */
    @Test
    void readsThroughTwoInternalLevels() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        int c = leafOf(store, 50, 60);
        int d = leafOf(store, 70, 80);

        int leftId = internalOf(store, a);
        addSeparator(store, leftId, 30, b);

        int rightId = internalOf(store, c);
        addSeparator(store, rightId, 70, d);

        int rootId = internalOf(store, leftId);
        addSeparator(store, rootId, 50, rightId);

        BPlusTree tree = BPlusTree.open(store, rootId);

        for (int k : new int[]{10, 20, 30, 40, 50, 60, 70, 80}) {
            assertThat(tree.get(key(k))).as("key %d", k).containsExactly(value(k));
        }
        assertThat(tree.get(key(45))).isNull();
        assertThat(tree.get(key(90))).isNull();
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void aCycleInChildPointersThrowsInsteadOfHanging() {
        HeapPageStore store = new HeapPageStore();
        int rootId = store.allocate(PageType.INTERNAL);
        InternalNode root = new InternalNode(store.get(rootId));
        root.setLeftmostChild(rootId); // points at itself
        store.release(rootId);

        BPlusTree tree = BPlusTree.open(store, rootId);

        assertThatThrownBy(() -> tree.get(key(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void openRejectsAPageThatWasNeverAllocated() {
        HeapPageStore store = new HeapPageStore();

        assertThatThrownBy(() -> BPlusTree.open(store, 42)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.borrowedCount()).isZero();
    }
}
