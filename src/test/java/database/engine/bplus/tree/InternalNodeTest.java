package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import static database.engine.bplus.tree.TreeFixture.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalNodeTest {

    private static InternalNode newNode(HeapPageStore store) {
        return new InternalNode(store.get(store.allocate(PageType.INTERNAL)));
    }

    /**
     * leftmost -> 100,  20 -> 200,  40 -> 300,  60 -> 400
     */
    private static InternalNode threeSeparators(HeapPageStore store) {
        InternalNode node = newNode(store);
        node.setLeftmostChild(100);
        node.insertSeparator(key(20), 200);
        node.insertSeparator(key(40), 300);
        node.insertSeparator(key(60), 400);
        return node;
    }

    @Test
    void rejectsALeafPage() {
        HeapPageStore store = new HeapPageStore();
        SlottedPage leaf = store.get(store.allocate(PageType.LEAF));

        assertThatThrownBy(() -> new InternalNode(leaf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an internal page");
    }

    @Test
    void leftmostChildSitsInSlotZeroUnderAnEmptyKey() {
        InternalNode node = newNode(new HeapPageStore());
        node.setLeftmostChild(100);

        assertThat(node.childCount()).isEqualTo(1);
        assertThat(node.separatorAt(0)).isEmpty();
        assertThat(node.childAt(0)).isEqualTo(100);
    }

    @Test
    void separatorsStaySortedWhateverOrderTheyArriveIn() {
        InternalNode node = newNode(new HeapPageStore());
        node.setLeftmostChild(100);
        node.insertSeparator(key(60), 400);
        node.insertSeparator(key(20), 200);
        node.insertSeparator(key(40), 300);

        assertThat(node.childCount()).isEqualTo(4);
        assertThat(node.separatorAt(0)).isEmpty();
        assertThat(node.separatorAt(1)).containsExactly(key(20));
        assertThat(node.separatorAt(2)).containsExactly(key(40));
        assertThat(node.separatorAt(3)).containsExactly(key(60));
        assertThat(node.childAt(1)).isEqualTo(200);
    }

    @Test
    void setLeftmostChildReplacesTheExistingOne() {
        InternalNode node = newNode(new HeapPageStore());
        node.setLeftmostChild(100);
        node.insertSeparator(key(20), 200);

        node.setLeftmostChild(999);

        assertThat(node.childCount()).isEqualTo(2);
        assertThat(node.separatorAt(0)).isEmpty();
        assertThat(node.childAt(0)).isEqualTo(999);
        assertThat(node.childAt(1)).isEqualTo(200);
    }

    @Test
    void findChildRoutesEveryKeyRange() {
        InternalNode node = threeSeparators(new HeapPageStore());

        assertThat(node.findChild(key(10))).as("below every separator").isEqualTo(100);
        assertThat(node.findChild(key(20))).as("exactly on a separator").isEqualTo(200);
        assertThat(node.findChild(key(30))).as("between two separators").isEqualTo(200);
        assertThat(node.findChild(key(40))).isEqualTo(300);
        assertThat(node.findChild(key(59))).isEqualTo(300);
        assertThat(node.findChild(key(60))).isEqualTo(400);
        assertThat(node.findChild(key(9999))).as("above every separator").isEqualTo(400);
    }

    @Test
    void anEmptySearchKeyGoesToTheLeftmostChild() {
        InternalNode node = threeSeparators(new HeapPageStore());

        // The empty key is the smallest key there is, so it belongs in the leftmost subtree.
        assertThat(node.findChild(new byte[0])).isEqualTo(100);
    }

    @Test
    void insertSeparatorRejectsAnEmptyKey() {
        InternalNode node = newNode(new HeapPageStore());
        node.setLeftmostChild(100);

        assertThatThrownBy(() -> node.insertSeparator(new byte[0], 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leftmost child");
    }

    @Test
    void insertSeparatorRejectsADuplicate() {
        InternalNode node = threeSeparators(new HeapPageStore());

        assertThatThrownBy(() -> node.insertSeparator(key(40), 555))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already present");
    }

    @Test
    void findChildFailsLoudlyOnAPageWithNoChildren() {
        InternalNode node = newNode(new HeapPageStore());

        assertThatThrownBy(() -> node.findChild(key(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no children");
    }

    @Test
    void hasSpaceForSeparatorGoesFalseWhenThePageFills() {
        InternalNode node = newNode(new HeapPageStore());
        node.setLeftmostChild(100);
        assertThat(node.hasSpaceForSeparator(4)).isTrue();

        int child = 200;
        for (int i = 1; node.hasSpaceForSeparator(4); i++) {
            node.insertSeparator(key(i), child++);
        }

        assertThat(node.hasSpaceForSeparator(4)).isFalse();
        assertThat(node.childCount()).isGreaterThan(100);
        assertThat(node.findChild(key(1))).isEqualTo(200);
    }
}
