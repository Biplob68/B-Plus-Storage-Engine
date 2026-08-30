package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.chain;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.keysOf;
import static database.engine.bplus.tree.TreeFixture.leafOf;
import static database.engine.bplus.tree.TreeFixture.rightSiblingOf;
import static database.engine.bplus.tree.TreeFixture.value;
import static org.assertj.core.api.Assertions.assertThat;

class LeafRebalanceTest {

    private static InternalNode parentOf(HeapPageStore store, int pageId) {
        return new InternalNode(store.get(pageId));
    }

    private static int leafOfSize(HeapPageStore store, int first, int count, int valueLength) {
        int pageId = store.allocate(PageType.LEAF);
        SlottedPage leaf = store.get(pageId);
        for (int i = 0; i < count; i++) {
            leaf.insertCell(i, key(first + i), value(first + i, valueLength));
        }
        store.release(pageId);
        return pageId;
    }

    @Test
    void twoSmallLeavesMergeIntoOne() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        int c = leafOf(store, 50, 60);
        chain(store, a, b);
        chain(store, b, c);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        addSeparator(store, rootId, 50, c);
        InternalNode root = parentOf(store, rootId);

        assertThat(LeafRebalance.rebalance(store, root, 0)).isTrue();

        assertThat(keysOf(store, a)).as("everything moved into the left leaf")
                .satisfiesExactly(
                        k -> assertThat(k).containsExactly(key(10)),
                        k -> assertThat(k).containsExactly(key(20)),
                        k -> assertThat(k).containsExactly(key(30)),
                        k -> assertThat(k).containsExactly(key(40)));
        assertThat(root.childCount()).as("the parent lost a separator").isEqualTo(2);
        assertThat(store.freedCount()).as("the emptied page was freed").isEqualTo(1);
        assertThat(rightSiblingOf(store, a)).as("the chain skips the freed page").isEqualTo(c);
        store.release(rootId);
        TreeInvariants.check(store, rootId);
    }

    @Test
    void twoFullLeavesRedistributeInsteadOfMerging() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOfSize(store, 0, 2, 900);   // underflowed
        int b = leafOfSize(store, 10, 4, 900);  // nearly full
        chain(store, a, b);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 10, b);
        InternalNode root = parentOf(store, rootId);
        int pagesBefore = store.pageCount();

        assertThat(LeafRebalance.rebalance(store, root, 0)).isTrue();

        assertThat(store.pageCount()).as("no page was freed").isEqualTo(pagesBefore);
        assertThat(store.freedCount()).isZero();
        assertThat(root.childCount()).as("the parent still has both children").isEqualTo(2);

        List<byte[]> left = keysOf(store, a);
        List<byte[]> right = keysOf(store, b);
        assertThat(left).hasSizeGreaterThan(2).as("the underflowed leaf gained entries");
        assertThat(left.size() + right.size()).isEqualTo(6);
        assertThat(root.separatorAt(1)).as("the separator moved to the new boundary")
                .containsExactly(right.get(0));
        store.release(rootId);
        TreeInvariants.check(store, rootId);
    }

    @Test
    void mergingTheLastTwoLeavesKeepsTheChainEnded() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10);
        int b = leafOf(store, 30);
        chain(store, a, b);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        InternalNode root = parentOf(store, rootId);

        assertThat(LeafRebalance.rebalance(store, root, 0)).isTrue();

        assertThat(rightSiblingOf(store, a)).isEqualTo(database.engine.bplus.page.Page.NO_PAGE);
        assertThat(root.childCount()).as("the parent is down to its leftmost child").isEqualTo(1);
        store.release(rootId);
        TreeInvariants.check(store, rootId);
    }

    @Test
    void anEmptyLeafMergesAway() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store);
        chain(store, a, b);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        InternalNode root = parentOf(store, rootId);

        assertThat(LeafRebalance.rebalance(store, root, 0)).isTrue();

        assertThat(keysOf(store, a)).hasSize(2);
        assertThat(root.childCount()).isEqualTo(1);
        assertThat(store.freedCount()).isEqualTo(1);
        store.release(rootId);
    }
}
