package database.engine.bplus.tree;

import database.engine.bplus.util.Bytes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.keysOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalSplitTest {

    private static int fullInternal(HeapPageStore store) {
        int pageId = internalOf(store, 1000);
        InternalNode node = new InternalNode(store.get(pageId));
        for (int i = 2; node.hasSpaceForSeparator(4); i += 2) {
            node.insertSeparator(key(i), 1000 + i);
        }
        store.release(pageId);
        return pageId;
    }

    private static List<Integer> childrenOf(HeapPageStore store, int pageId) {
        InternalNode node = new InternalNode(store.get(pageId));
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

    /**
     * The separators only, dropping the empty key that marks the leftmost child.
     */
    private static List<byte[]> separatorsOf(HeapPageStore store, int pageId) {
        List<byte[]> keys = keysOf(store, pageId);
        return keys.subList(1, keys.size());
    }

    /**
     * Six children, all 4-byte separators, so the sizes are 8, 12, 12, 12, 12, 12 and the cut lands
     * on the entry for 70.
     *
     * <pre>
     *   before  leftmost=100 | 40->200 | 60->300 | 70->350 | 100->400 | 140->500
     *   after   left   leftmost=100 | 40->200 | 60->300
     *           right  leftmost=350 | 100->400 | 140->500
     *           70 goes up
     * </pre>
     */
    @Test
    void theKeyAtTheCutGoesUpAndItsChildLeadsTheRightHalf() {
        HeapPageStore store = new HeapPageStore();
        int leftId = internalOf(store, 100);
        addSeparator(store, leftId, 40, 200);
        addSeparator(store, leftId, 60, 300);
        addSeparator(store, leftId, 100, 400);
        addSeparator(store, leftId, 140, 500);

        SplitResult result = InternalSplit.split(store, leftId, key(70), 350);

        assertThat(result.separatorKey()).containsExactly(key(70));
        assertThat(childrenOf(store, leftId)).containsExactly(100, 200, 300);
        assertThat(childrenOf(store, result.newPageId()))
                .as("350 lost its key and now leads the right half")
                .containsExactly(350, 400, 500);
        assertThat(separatorsOf(store, leftId)).satisfiesExactly(
                k -> assertThat(k).containsExactly(key(40)),
                k -> assertThat(k).containsExactly(key(60)));
        assertThat(separatorsOf(store, result.newPageId())).satisfiesExactly(
                k -> assertThat(k).containsExactly(key(100)),
                k -> assertThat(k).containsExactly(key(140)));
    }

    @Test
    void thePushedUpKeyIsInNeitherHalf() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullInternal(store);

        SplitResult result = InternalSplit.split(store, leftId, key(1), 9999);

        assertThat(keysOf(store, leftId))
                .noneSatisfy(k -> assertThat(k).containsExactly(result.separatorKey()));
        assertThat(keysOf(store, result.newPageId()))
                .noneSatisfy(k -> assertThat(k).containsExactly(result.separatorKey()));
    }

    @Test
    void bothHalvesKeepAnEmptyKeyInSlotZero() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullInternal(store);

        SplitResult result = InternalSplit.split(store, leftId, key(1), 9999);

        assertThat(keysOf(store, leftId).get(0)).as("left leftmost").isEmpty();
        assertThat(keysOf(store, result.newPageId()).get(0)).as("right leftmost").isEmpty();
    }

    @Test
    void everyChildSurvivesExactlyOnce() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullInternal(store);
        List<Integer> before = new ArrayList<>(childrenOf(store, leftId));
        before.add(9999);

        SplitResult result = InternalSplit.split(store, leftId, key(1), 9999);

        List<Integer> after = new ArrayList<>(childrenOf(store, leftId));
        after.addAll(childrenOf(store, result.newPageId()));
        assertThat(after).containsExactlyInAnyOrderElementsOf(before);
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void separatorsStayAscendingWithThePushedUpKeyBetweenTheHalves() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullInternal(store);

        SplitResult result = InternalSplit.split(store, leftId, key(1), 9999);

        List<byte[]> ordered = new ArrayList<>(separatorsOf(store, leftId));
        ordered.add(result.separatorKey());
        ordered.addAll(separatorsOf(store, result.newPageId()));

        for (int i = 1; i < ordered.size(); i++) {
            assertThat(Bytes.compare(ordered.get(i - 1), ordered.get(i))).as("separator %d", i).isNegative();
        }
    }

    @Test
    void oneChildIsAddedOverallAndOneKeyLeaves() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullInternal(store);
        int before = childrenOf(store, leftId).size();

        SplitResult result = InternalSplit.split(store, leftId, key(1), 9999);

        int after = childrenOf(store, leftId).size() + childrenOf(store, result.newPageId()).size();
        assertThat(after).as("the new child, and the cut child kept as the right leftmost")
                .isEqualTo(before + 1);
    }

    @Test
    void longSeparatorsStillSplit() {
        HeapPageStore store = new HeapPageStore();
        int leftId = internalOf(store, 100);
        InternalNode node = new InternalNode(store.get(leftId));
        for (int i = 2; node.hasSpaceForSeparator(204); i += 2) {
            node.insertSeparator(longKey(i), 1000 + i);
        }
        int before = node.childCount();
        store.release(leftId);

        SplitResult result = InternalSplit.split(store, leftId, longKey(1), 9999);

        assertThat(childrenOf(store, leftId).size()).isGreaterThan(1);
        assertThat(childrenOf(store, result.newPageId()).size()).isGreaterThan(1);
        assertThat(childrenOf(store, leftId).size() + childrenOf(store, result.newPageId()).size())
                .isEqualTo(before + 1);
    }

    @Test
    void refusesAnEmptySeparator() {
        HeapPageStore store = new HeapPageStore();
        int leftId = fullInternal(store);

        assertThatThrownBy(() -> InternalSplit.split(store, leftId, new byte[0], 9999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leftmost child");
    }

    private static byte[] longKey(int i) {
        byte[] longer = new byte[204];
        System.arraycopy(key(i), 0, longer, 0, 4);
        return longer;
    }
}
