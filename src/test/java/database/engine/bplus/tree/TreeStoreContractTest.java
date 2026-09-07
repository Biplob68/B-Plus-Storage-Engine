package database.engine.bplus.tree;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static database.engine.bplus.tree.TreeFixture.heightOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same tree scenarios, run against every {@link PageStore}.
 *
 * <p>On a heap store a page is always there. On a pool it can be evicted and read back in the
 * middle of a split, so a page held wrongly, or released twice, only shows up here.
 */
abstract class TreeStoreContractTest {

    /**
     * Big enough that a few hundred keys need several levels.
     */
    private static final int VALUE_BYTES = 200;

    private PageStore store;

    @BeforeEach
    void openStore() {
        store = createStore();
    }

    @AfterEach
    void closeStore() {
        assertThat(borrowedCount()).as("every page borrowed has to be released").isZero();
        releaseStore();
    }

    protected abstract PageStore createStore();

    protected abstract int borrowedCount();

    protected abstract void releaseStore();

    private BPlusTree treeHolding(int keyCount) {
        BPlusTree tree = BPlusTree.create(store);
        for (int i = 0; i < keyCount; i++) {
            tree.put(key(i), value(i, VALUE_BYTES));
        }
        return tree;
    }

    private List<Integer> scannedKeysOf(BPlusTree tree) {
        List<Integer> keys = new ArrayList<>();
        Cursor cursor = tree.scan();
        while (cursor.next()) {
            byte[] key = cursor.key();
            keys.add(((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
                    | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF));
        }
        return keys;
    }

    @Test
    void everyKeyIsReadableAfterTheTreeHasGrown() {
        BPlusTree tree = treeHolding(400);

        for (int i = 0; i < 400; i++) {
            assertThat(tree.get(key(i))).as("key " + i).containsExactly(value(i, VALUE_BYTES));
        }
        assertThat(heightOf(store, tree.rootPageId())).as("the root must have split").isGreaterThan(1);
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void aScanComesBackInKeyOrder() {
        BPlusTree tree = treeHolding(400);

        assertThat(scannedKeysOf(tree)).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 400).boxed().toList());
    }

    @Test
    void deletingEverySecondKeyLeavesTheTreeValid() {
        BPlusTree tree = treeHolding(400);

        for (int i = 0; i < 400; i += 2) {
            assertThat(tree.delete(key(i))).as("key " + i).isTrue();
        }

        TreeInvariants.check(store, tree.rootPageId());
        for (int i = 0; i < 400; i++) {
            byte[] found = tree.get(key(i));
            if (i % 2 == 0) {
                assertThat(found).as("key " + i + " was deleted").isNull();
            } else {
                assertThat(found).as("key " + i).containsExactly(value(i, VALUE_BYTES));
            }
        }
    }

    @Test
    void deletingEverythingCollapsesBackToASingleLeaf() {
        BPlusTree tree = treeHolding(300);
        assertThat(heightOf(store, tree.rootPageId())).isGreaterThan(1);

        for (int i = 0; i < 300; i++) {
            assertThat(tree.delete(key(i))).as("key " + i).isTrue();
        }

        assertThat(heightOf(store, tree.rootPageId())).as("back to one leaf").isEqualTo(1);
        assertThat(scannedKeysOf(tree)).isEmpty();
        TreeInvariants.check(store, tree.rootPageId());
    }

    @Test
    void deletingFromTheFrontKeepsTheTreeValid() {
        BPlusTree tree = treeHolding(300);

        for (int i = 0; i < 150; i++) {
            assertThat(tree.delete(key(i))).as("key " + i).isTrue();
        }

        TreeInvariants.check(store, tree.rootPageId());
        assertThat(scannedKeysOf(tree)).containsExactlyElementsOf(
                java.util.stream.IntStream.range(150, 300).boxed().toList());
    }

    @Test
    void replacingEveryValueKeepsTheKeysWhereTheyWere() {
        BPlusTree tree = treeHolding(300);

        for (int i = 0; i < 300; i++) {
            tree.put(key(i), value(i + 1, VALUE_BYTES));
        }

        TreeInvariants.check(store, tree.rootPageId());
        for (int i = 0; i < 300; i++) {
            assertThat(tree.get(key(i))).as("key " + i).containsExactly(value(i + 1, VALUE_BYTES));
        }
    }

    @Test
    void aRangeScanReturnsOnlyTheRange() {
        BPlusTree tree = treeHolding(400);

        Cursor cursor = tree.scan(key(100), key(110));
        List<Integer> keys = new ArrayList<>();
        while (cursor.next()) {
            byte[] key = cursor.key();
            keys.add(((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16)
                    | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF));
        }

        assertThat(keys).containsExactly(100, 101, 102, 103, 104, 105, 106, 107, 108, 109);
    }
}
