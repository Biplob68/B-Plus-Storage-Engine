package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.util.Bytes;
import org.junit.jupiter.api.Test;

import static database.engine.bplus.tree.TreeFixture.addSeparator;
import static database.engine.bplus.tree.TreeFixture.linkSiblings;
import static database.engine.bplus.tree.TreeFixture.internalOf;
import static database.engine.bplus.tree.TreeFixture.key;
import static database.engine.bplus.tree.TreeFixture.leafOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A checker with a bug in it is worse than no checker, so each rule gets a tree that breaks it. */
class TreeInvariantsTest {

    @Test
    void anEmptyTreePasses() {
        HeapPageStore store = new HeapPageStore();
        BPlusTree tree = BPlusTree.create(store);

        assertThatCode(() -> TreeInvariants.check(store, tree.rootPageId())).doesNotThrowAnyException();
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void aSingleLeafRootPasses() {
        HeapPageStore store = new HeapPageStore();
        int rootId = leafOf(store, 10, 20, 30);

        assertThatCode(() -> TreeInvariants.check(store, rootId)).doesNotThrowAnyException();
    }

    @Test
    void aTwoLevelTreePasses() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        int c = leafOf(store, 50, 60);
        linkSiblings(store, a, b);
        linkSiblings(store, b, c);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        addSeparator(store, rootId, 50, c);

        assertThatCode(() -> TreeInvariants.check(store, rootId)).doesNotThrowAnyException();
        assertThat(store.borrowedCount()).isZero();
    }

    @Test
    void catchesLeavesAtDifferentDepths() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int c = leafOf(store, 50, 60);
        linkSiblings(store, a, c);

        int middle = internalOf(store, c); // extra level on the right only
        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 50, middle);

        assertThatThrownBy(() -> TreeInvariants.check(store, rootId))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("different depths");
    }

    @Test
    void catchesAKeyOutsideItsSeparatorRange() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 15, 40); // 15 belongs left of the separator 30
        linkSiblings(store, a, b);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);

        assertThatThrownBy(() -> TreeInvariants.check(store, rootId))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("outside its subtree range");
    }

    @Test
    void catchesABrokenSiblingChain() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        int c = leafOf(store, 50, 60);
        linkSiblings(store, a, b);
        // b -> c is missing, which is what a split forgetting to rewire looks like

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);
        addSeparator(store, rootId, 50, c);

        assertThatThrownBy(() -> TreeInvariants.check(store, rootId))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not match the leaves in key order");
    }

    @Test
    void catchesASiblingChainThatLoops() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);
        int b = leafOf(store, 30, 40);
        linkSiblings(store, a, b);
        linkSiblings(store, b, a); // what wiring the pointers in the wrong order produces

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, b);

        assertThatThrownBy(() -> TreeInvariants.check(store, rootId))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("loops");
    }

    @Test
    void catchesTheSamePageUsedTwice() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);

        int rootId = internalOf(store, a);
        addSeparator(store, rootId, 30, a); // same child on both sides

        assertThatThrownBy(() -> TreeInvariants.check(store, rootId))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void catchesAnInternalPageWithNoLeftmostChild() {
        HeapPageStore store = new HeapPageStore();
        int a = leafOf(store, 10, 20);

        int rootId = store.allocate(PageType.INTERNAL);
        SlottedPage page = store.get(rootId);
        page.insertCell(0, key(30), Bytes.encodeInt(a)); // no empty key in slot 0
        store.release(rootId);

        assertThatThrownBy(() -> TreeInvariants.check(store, rootId))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("missing its leftmost child");
    }
}
