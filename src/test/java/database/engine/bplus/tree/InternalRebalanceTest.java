package database.engine.bplus.tree;

import database.engine.bplus.util.Bytes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.chain;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.keysOf;
import static database.engine.bplus.tree.TreeFixture.leafOf;
import static org.assertj.core.api.Assertions.assertThat;

/** Merging and redistributing two adjacent internal nodes, driven directly. */
class InternalRebalanceTest {

    private static InternalNode nodeAt(HeapPageStore store, int pageId) {
        return new InternalNode(store.get(pageId));
    }

    private static List<Integer> childrenOf(HeapPageStore store, int pageId) {
        InternalNode node = nodeAt(store, pageId);
        try {
            List<Integer> children = new ArrayList<>(node.childCount());
            for (int i = 0; i < node.childCount(); i++) {
                children.add(node.childAt(i));
            }
            return children;
        } finally {
            store.release(pageId);
        }
    }

    private static byte[] separatorAt(HeapPageStore store, int pageId, int slotIndex) {
        InternalNode node = nodeAt(store, pageId);
        try {
            return node.separatorAt(slotIndex);
        } finally {
            store.release(pageId);
        }
    }

    /**
     * The pull-down, on a shape small enough to write out.
     *
     * <pre>
     *   root:  leftmost -> L,  50 -> R
     *   L:     leftmost -> leaf1,  30 -> leaf2
     *   R:     leftmost -> leaf3,  70 -> leaf4
     *
     *   merged L:  leftmost -> leaf1,  30 -> leaf2,  50 -> leaf3,  70 -> leaf4
     *                                              ^^ came down from the root
     * </pre>
     */
    @Test
    void mergingPullsTheParentsSeparatorDown() {
        HeapPageStore store = new HeapPageStore();
        int leaf1 = leafOf(store, 10);
        int leaf2 = leafOf(store, 30);
        int leaf3 = leafOf(store, 50);
        int leaf4 = leafOf(store, 70);
        chain(store, leaf1, leaf2);
        chain(store, leaf2, leaf3);
        chain(store, leaf3, leaf4);

        int left = internalOf(store, leaf1);
        addSeparator(store, left, 30, leaf2);
        int right = internalOf(store, leaf3);
        addSeparator(store, right, 70, leaf4);
        int rootId = internalOf(store, left);
        addSeparator(store, rootId, 50, right);

        InternalNode root = nodeAt(store, rootId);
        assertThat(InternalRebalance.rebalance(store, root, 0)).isTrue();
        store.release(rootId);

        assertThat(childrenOf(store, left)).containsExactly(leaf1, leaf2, leaf3, leaf4);
        assertThat(keysOf(store, left)).satisfiesExactly(
                k -> assertThat(k).as("leftmost").isEmpty(),
                k -> assertThat(k).containsExactly(key(30)),
                k -> assertThat(k).as("the root's separator came down").containsExactly(key(50)),
                k -> assertThat(k).containsExactly(key(70)));
        assertThat(childrenOf(store, rootId)).as("the root is down to one child").containsExactly(left);
        assertThat(store.freedCount()).isEqualTo(1);
        TreeInvariants.check(store, rootId);
    }

    /**
     * A full node next to a nearly empty one, so the boundary has to move a long way and the key
     * that goes back up is clearly a different one.
     *
     * <p>Child ids here are made up, because a rebalance never follows them. It only rewrites the
     * two pages and the parent's separator.
     */
    @Test
    void redistributingSendsTheKeyAtTheCutBackUp() {
        HeapPageStore store = new HeapPageStore();
        int left = internalOf(store, 1_000);
        fillWithSeparators(store, left, 1, 5_000);
        int right = internalOf(store, 2_000);
        addSeparator(store, right, 6_000, 200_000);
        addSeparator(store, right, 6_001, 200_001);

        int rootId = internalOf(store, left);
        addSeparator(store, rootId, 5_500, right);
        int childrenBefore = childrenOf(store, left).size() + childrenOf(store, right).size();
        int pagesBefore = store.pageCount();

        InternalNode root = nodeAt(store, rootId);
        assertThat(InternalRebalance.rebalance(store, root, 0)).isTrue();
        store.release(rootId);

        assertThat(store.pageCount()).as("redistribution frees nothing").isEqualTo(pagesBefore);
        assertThat(childrenOf(store, left).size() + childrenOf(store, right).size())
                .as("no child was lost or duplicated")
                .isEqualTo(childrenBefore);
        assertThat(separatorAt(store, right, 0)).as("the right node leads with an empty key").isEmpty();
        assertThat(childrenOf(store, right).size()).as("the empty node was filled up").isGreaterThan(3);

        byte[] divider = separatorAt(store, rootId, 1);
        assertThat(divider).as("a key from the left node went up to become the new divider")
                .isNotEqualTo(key(5_500));

        List<byte[]> leftKeys = keysOf(store, left);
        assertThat(Bytes.compare(leftKeys.get(leftKeys.size() - 1), divider))
                .as("everything left of the divider stays left")
                .isNegative();
        List<byte[]> rightKeys = keysOf(store, right);
        assertThat(Bytes.compare(divider, rightKeys.get(1)))
                .as("everything right of the divider sorts after it")
                .isNegative();
    }

    /** Adds separators until the node is full, keeping keys inside {@code [from, toExclusive)}. */
    private static void fillWithSeparators(HeapPageStore store, int pageId, int from, int toExclusive) {
        InternalNode node = nodeAt(store, pageId);
        for (int i = from; i < toExclusive && node.hasSpaceForSeparator(4); i++) {
            node.insertSeparator(key(i), 100_000 + i);
        }
        store.release(pageId);
    }
}
