package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.util.Bytes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static database.engine.bplus.tree.TreeFixture.cellCountOf;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.keysOf;
import static database.engine.bplus.tree.TreeFixture.rightSiblingOf;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeafSplitTest {

    /** Fills a fresh leaf with keys 0, 2, 4, ... until it is full. Returns its page id. */
    private static int fullLeaf(HeapPageStore store, int valueLength) {
        int pageId = store.allocate(PageType.LEAF);
        SlottedPage leaf = store.get(pageId);
        for (int i = 0; leaf.hasSpaceFor(4, valueLength); i += 2) {
            leaf.insertCell(leaf.cellCount(), key(i), value(i, valueLength));
        }
        store.release(pageId);
        return pageId;
    }

    @Test
    void everyKeyLandsInExactlyOneHalfAndBothStaySorted() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullLeaf(store, 8);
        int before = cellCountOf(store, leftId);

        SplitResult result = LeafSplit.split(store, leftId, key(1), value(1, 8));

        List<byte[]> left = keysOf(store, leftId);
        List<byte[]> right = keysOf(store, result.newPageId());
        assertThat(left.size() + right.size()).isEqualTo(before + 1);
        assertThat(left).isNotEmpty();
        assertThat(right).isNotEmpty();

        List<byte[]> all = new ArrayList<>(left);
        all.addAll(right);
        for (int i = 1; i < all.size(); i++) {
            assertThat(Bytes.compare(all.get(i - 1), all.get(i))).as("entry %d", i).isNegative();
        }
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void theSeparatorIsTheFirstKeyOfTheRightHalfAndStaysThere() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullLeaf(store, 8);

        SplitResult result = LeafSplit.split(store, leftId, key(1), value(1, 8));

        List<byte[]> right = keysOf(store, result.newPageId());
        assertThat(result.separatorKey()).containsExactly(right.get(0));

        // Copy up, not push up: the key is still a record in the right leaf.
        SlottedPage rightPage = store.get(result.newPageId());
        assertThat(rightPage.binarySearch(result.separatorKey())).isZero();
        store.release(result.newPageId());
    }

    @Test
    void everyKeyOnTheLeftSortsBeforeTheSeparatorAndEveryKeyOnTheRightDoesNot() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullLeaf(store, 8);

        SplitResult result = LeafSplit.split(store, leftId, key(1), value(1, 8));

        for (byte[] k : keysOf(store, leftId)) {
            assertThat(Bytes.compare(k, result.separatorKey())).isNegative();
        }
        for (byte[] k : keysOf(store, result.newPageId())) {
            assertThat(Bytes.compare(k, result.separatorKey())).isNotNegative();
        }
    }

    @Test
    void theNewLeafTakesOverTheOldRightSibling() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullLeaf(store, 8);
        int neighbourId = store.allocate(PageType.LEAF);
        SlottedPage left = store.get(leftId);
        left.setRightSibling(neighbourId);
        store.release(leftId);

        SplitResult result = LeafSplit.split(store, leftId, key(1), value(1, 8));

        assertThat(rightSiblingOf(store, leftId)).as("left now points at the new leaf")
                .isEqualTo(result.newPageId());
        assertThat(rightSiblingOf(store, result.newPageId())).as("new leaf inherits the old neighbour")
                .isEqualTo(neighbourId);
    }

    @Test
    void aLeafWithNoNeighbourLeavesTheChainEnded() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullLeaf(store, 8);

        SplitResult result = LeafSplit.split(store, leftId, key(1), value(1, 8));

        assertThat(rightSiblingOf(store, leftId)).isEqualTo(result.newPageId());
        assertThat(rightSiblingOf(store, result.newPageId())).isEqualTo(Page.NO_PAGE);
    }

    @Test
    void theNewKeyCanLandOnEitherSide() {
        HeapPageStore store = new HeapPageStore();

        int lowId = fullLeaf(store, 8);
        SplitResult low = LeafSplit.split(store, lowId, key(1), value(1, 8));
        assertThat(keysOf(store, lowId).get(0)).as("a low key goes to the left half").containsExactly(key(0));
        assertThat(keysOf(store, lowId)).anySatisfy(k -> assertThat(k).containsExactly(key(1)));

        int highId = fullLeaf(store, 8);
        int lastKey = (cellCountOf(store, highId) - 1) * 2;
        SplitResult high = LeafSplit.split(store, highId, key(lastKey + 1), value(7, 8));
        List<byte[]> right = keysOf(store, high.newPageId());
        assertThat(right.get(right.size() - 1)).as("a high key goes to the right half")
                .containsExactly(key(lastKey + 1));
        assertThat(low.newPageId()).isNotEqualTo(high.newPageId());
    }

    @Test
    void mixedValueSizesStillLeaveBothHalvesWithinCapacity() {
        HeapPageStore store = new HeapPageStore();
        int leftId = store.allocate(PageType.LEAF);
        SlottedPage leaf = store.get(leftId);
        int[] lengths = {900, 20, 700, 30, 800, 40, 600};
        int i = 0;
        for (int length : lengths) {
            if (!leaf.hasSpaceFor(4, length)) {
                break;
            }
            leaf.insertCell(leaf.cellCount(), key(i += 2), value(i, length));
        }
        store.release(leftId);

        SplitResult result = LeafSplit.split(store, leftId, key(1), value(1, 900));

        assertThat(cellCountOf(store, leftId)).isPositive();
        assertThat(cellCountOf(store, result.newPageId())).isPositive();
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void refusesAKeyThatIsAlreadyThere() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullLeaf(store, 8);

        assertThatThrownBy(() -> LeafSplit.split(store, leftId, key(0), value(0, 8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in the leaf");
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void refusesAnInternalPage() {
        HeapPageStore store = new HeapPageStore();
        int internalId = internalOf(store, 99);

        assertThatThrownBy(() -> LeafSplit.split(store, internalId, key(1), value(1, 8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a leaf");
    }
}
