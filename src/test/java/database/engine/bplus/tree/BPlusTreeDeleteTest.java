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

class BPlusTreeDeleteTest {

    private static BPlusTree treeWith(HeapPageStore store, int count, int valueLength) {
        BPlusTree tree = BPlusTree.create(store);
        for (int i = 0; i < count; i++) {
            tree.put(key(i), value(i, valueLength));
        }
        return tree;
    }

    /** A separator from the root, so a test can delete the exact key an internal page names. */
    private static byte[] aRootSeparator(HeapPageStore store, int rootPageId) {
        SlottedPage page = store.get(rootPageId);
        try {
            assertThat(page.type()).isEqualTo(PageType.INTERNAL);
            return new InternalNode(page).separatorAt(1);
        } finally {
            store.release(rootPageId);
        }
    }

    @Test
    void deletingAKeyRemovesOnlyThatKey() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 400, 8);

        assertThat(tree.delete(key(120))).isTrue();

        assertThat(tree.get(key(120))).isNull();
        assertThat(tree.get(key(119))).containsExactly(value(119, 8));
        assertThat(tree.get(key(121))).containsExactly(value(121, 8));
        TreeInvariants.check(store, tree.rootPageId());
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void deletingAMissingKeyChangesNothing() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 400, 8);
        int pagesBefore = store.pageCount();

        assertThat(tree.delete(key(9_999))).isFalse();
        assertThat(tree.delete(new byte[0])).isFalse();

        assertThat(store.pageCount()).isEqualTo(pagesBefore);
        assertThat(scanAllKeys(store, tree.rootPageId())).hasSize(400);
        TreeInvariants.check(store, tree.rootPageId());
    }

    /**
     * The point of the whole "internal keys are only signposts" rule. After this delete the root
     * still names a key that exists nowhere in the tree, and routing is still correct.
     */
    @Test
    void aSeparatorMayOutliveTheKeyItNames() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 400, 8);
        byte[] separator = aRootSeparator(store, tree.rootPageId());

        assertThat(tree.get(separator)).as("the separator is a real record to begin with").isNotNull();
        assertThat(tree.delete(separator)).isTrue();

        assertThat(tree.get(separator)).as("gone as a record").isNull();
        assertThat(aRootSeparator(store, tree.rootPageId()))
                .as("still a separator in the root")
                .containsExactly(separator);
        TreeInvariants.check(store, tree.rootPageId());

        // Everything either side of it still routes to the right leaf.
        for (int i = 0; i < 400; i++) {
            byte[] k = key(i);
            if (!Arrays.equals(k, separator)) {
                assertThat(tree.get(k)).as("key %d", i).containsExactly(value(i, 8));
            }
        }
    }

    @Test
    void anEmptyLeafLeavesTheTreeValid() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 12, 900); // about four to a leaf, so leaves empty quickly

        for (int i = 0; i < 8; i++) {
            assertThat(tree.delete(key(i))).isTrue();
            TreeInvariants.check(store, tree.rootPageId());
        }

        assertThat(scanAllKeys(store, tree.rootPageId())).hasSize(4);
        for (int i = 8; i < 12; i++) {
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 900));
        }
    }

    @Test
    void deletingEveryKeyLeavesAnEmptyButValidTree() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 400, 8);
        int heightBefore = heightOf(store, tree.rootPageId());

        for (int i = 0; i < 400; i++) {
            assertThat(tree.delete(key(i))).as("key %d", i).isTrue();
        }

        assertThat(scanAllKeys(store, tree.rootPageId())).isEmpty();
        assertThat(tree.get(key(0))).isNull();
        assertThat(store.freedCount()).as("merges reclaimed pages on the way down").isPositive();
        assertThat(heightOf(store, tree.rootPageId()))
                .as("leaves merge, but the root does not collapse yet")
                .isEqualTo(heightBefore);
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void aDeletedKeyCanBePutBack() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 400, 8);

        tree.delete(key(200));
        tree.put(key(200), value(77, 8));

        assertThat(tree.get(key(200))).containsExactly(value(77, 8));
        assertThat(scanAllKeys(store, tree.rootPageId())).as("not duplicated").hasSize(400);
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void mixedPutsAndDeletesMatchATreeMap() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);
        TreeMap<byte[], byte[]> model = new TreeMap<>(Arrays::compareUnsigned);
        Random random = new Random(31337);

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);

        for (int i : order) {
            tree.put(key(i), value(i, 8));
            model.put(key(i), value(i, 8));
            if (random.nextInt(3) == 0) {
                int victim = random.nextInt(2_000);
                assertThat(tree.delete(key(victim)))
                        .as("delete of key %d", victim)
                        .isEqualTo(model.remove(key(victim)) != null);
            }
        }

        TreeInvariants.check(store, tree.rootPageId());
        for (var entry : model.entrySet()) {
            assertThat(tree.get(entry.getKey())).containsExactly(entry.getValue());
        }
        List<byte[]> scanned = scanAllKeys(store, tree.rootPageId());
        assertThat(scanned).hasSize(model.size());
        int i = 0;
        for (byte[] expected : model.keySet()) {
            assertThat(scanned.get(i++)).containsExactly(expected);
        }
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void emptyingLeavesReclaimsPages() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 600, 8);
        int pagesBefore = store.pageCount();

        for (int i = 0; i < 500; i++) {
            assertThat(tree.delete(key(i))).isTrue();
            TreeInvariants.check(store, tree.rootPageId());
        }

        assertThat(store.freedCount()).as("merged leaves were handed back").isPositive();
        assertThat(store.pageCount()).as("the tree shrank").isLessThan(pagesBefore);
        assertThat(scanAllKeys(store, tree.rootPageId())).hasSize(100);
        for (int i = 500; i < 600; i++) {
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 8));
        }
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void largeValuesDeleteCorrectlyToo() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 60, 900); // about four to a leaf

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 60; i += 2) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(5));
        for (int i : order) {
            assertThat(tree.delete(key(i))).as("key %d", i).isTrue();
            TreeInvariants.check(store, tree.rootPageId());
        }

        assertThat(scanAllKeys(store, tree.rootPageId())).hasSize(30);
        for (int i = 1; i < 60; i += 2) {
            assertThat(tree.get(key(i))).as("key %d", i).containsExactly(value(i, 900));
        }
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void aFreedPageIsNeverReferencedAgain() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = treeWith(store, 400, 8);

        for (int i = 0; i < 350; i++) {
            tree.delete(key(i));
        }

        // Every page the walk reaches must still exist; a stale child pointer throws here.
        TreeInvariants.check(store, tree.rootPageId());
        assertThat(scanAllKeys(store, tree.rootPageId())).hasSize(50);
    }
}
