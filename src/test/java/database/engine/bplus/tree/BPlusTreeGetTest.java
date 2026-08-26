package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class BPlusTreeGetTest {


    private static byte[] key(int i) {
        return new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i};
    }

    private static byte[] value(int i) {
        return new byte[]{(byte) i, (byte) 0xAA};
    }

    /**
     * A leaf holding the given keys, returned as its page id.
     */
    private static int leafOf(HeapPageStore store, int... keys) {
        int pageId = store.allocate(PageType.LEAF);
        SlottedPage leaf = store.get(pageId);
        for (int i = 0; i < keys.length; i++) {
            leaf.insertCell(i, key(keys[i]), value(keys[i]));
        }
        store.release(pageId);
        return pageId;
    }

    private static void chain(HeapPageStore store, int leftId, int rightId) {
        SlottedPage left = store.get(leftId);
        left.setRightSibling(rightId);
        store.release(leftId);
    }

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
     *            root:  leftmost -> A,  30 -> B,  50 -> C
     *            A [10 20] -> B [30 40] -> C [50 60]
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

        int rootId = store.allocate(PageType.INTERNAL);
        InternalNode root = new InternalNode(store.get(rootId));
        root.setLeftmostChild(a);
        root.insertSeparator(key(30), b);
        root.insertSeparator(key(50), c);
        store.release(rootId);

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
     *            root:      leftmost -> L,  50 -> R
     *            L:         leftmost -> A,  30 -> B
     *            R:         leftmost -> C,  70 -> D
     * </pre>
     */
    @Test
    void readsThroughTwoInternalLevels() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        int c = leafOf(store, 50, 60);
        int d = leafOf(store, 70, 80);

        int leftId = store.allocate(PageType.INTERNAL);
        InternalNode left = new InternalNode(store.get(leftId));
        left.setLeftmostChild(a);
        left.insertSeparator(key(30), b);
        store.release(leftId);

        int rightId = store.allocate(PageType.INTERNAL);
        InternalNode right = new InternalNode(store.get(rightId));
        right.setLeftmostChild(c);
        right.insertSeparator(key(70), d);
        store.release(rightId);

        int rootId = store.allocate(PageType.INTERNAL);
        InternalNode root = new InternalNode(store.get(rootId));
        root.setLeftmostChild(leftId);
        root.insertSeparator(key(50), rightId);
        store.release(rootId);

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
