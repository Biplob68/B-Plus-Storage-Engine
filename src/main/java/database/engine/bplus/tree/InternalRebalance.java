package database.engine.bplus.tree;

import database.engine.bplus.page.SlottedPage;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixes an underflowed internal node using the sibling next to it.
 *
 * <p>The mirror of {@link InternalSplit}, and it inherits the same asymmetry.
 *
 * <pre>
 *   L        ""->a | k1->b            parent separator between them: s
 *   R        ""->d | k3->e
 *
 *   pooled   ""->a | k1->b | s->d | k3->e
 *                             ^ s came down and now leads R's old leftmost
 * </pre>
 *
 */
final class InternalRebalance {

    private InternalRebalance() {
        throw new AssertionError("no instances");
    }

    static boolean rebalance(PageStore store, InternalNode parent, int leftSlot) {
        int leftPageId = parent.childAt(leftSlot);
        int rightPageId = parent.childAt(leftSlot + 1);
        List<Child> pooled = pooledChildren(store, leftPageId, rightPageId, parent.separatorAt(leftSlot + 1));

        if (Child.totalSize(pooled) <= SlottedPage.USABLE_BYTES) {
            merge(store, parent, leftSlot, leftPageId, rightPageId, pooled);
            return true;
        }
        return redistribute(store, parent, leftSlot, leftPageId, rightPageId, pooled);
    }

    /**
     * The parent's separator comes down and becomes the key for the right node's leftmost child.
     */
    private static List<Child> pooledChildren(PageStore store, int leftPageId, int rightPageId, byte[] divider) {
        List<Child> right = readChildren(store, rightPageId);

        List<Child> pooled = new ArrayList<>(readChildren(store, leftPageId));
        pooled.add(new Child(divider, right.get(0).pageId()));
        pooled.addAll(right.subList(1, right.size()));
        return pooled;
    }

    private static void merge(PageStore store, InternalNode parent, int leftSlot,
                              int leftPageId, int rightPageId, List<Child> pooled) {
        writeChildren(store, leftPageId, pooled);
        parent.removeChild(leftSlot + 1);
        store.free(rightPageId);
    }

    private static boolean redistribute(PageStore store, InternalNode parent, int leftSlot,
                                        int leftPageId, int rightPageId, List<Child> pooled) {
        int cut = SplitPolicy.chooseSplitIndex(Child.sizesOf(pooled), SlottedPage.USABLE_BYTES);
        byte[] newDivider = pooled.get(cut).separator();
        if (!parent.canReplaceSeparator(leftSlot + 1, newDivider)) {
            return false;
        }

        List<Child> rightHalf = new ArrayList<>(pooled.size() - cut);
        rightHalf.add(pooled.get(cut).asLeftmost());
        rightHalf.addAll(pooled.subList(cut + 1, pooled.size()));

        writeChildren(store, leftPageId, pooled.subList(0, cut));
        writeChildren(store, rightPageId, rightHalf);
        parent.replaceSeparator(leftSlot + 1, newDivider);
        return true;
    }

    private static List<Child> readChildren(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return Child.readAll(new InternalNode(page));
        } finally {
            store.release(pageId);
        }
    }

    private static void writeChildren(PageStore store, int pageId, List<Child> children) {
        SlottedPage page = store.get(pageId);
        try {
            Child.rewriteAll(new InternalNode(page), children);
        } finally {
            store.release(pageId);
        }
    }
}
