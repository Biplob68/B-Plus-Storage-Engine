package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.util.Bytes;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one full internal node into two.
 * <pre>
 *   before   leftmost=c0 | 40->c1 | 60->c2 | 70->c3 | 100->c4 | 140->c5
 *                                             ^ cut
 *
 *   after    left   leftmost=c0 | 40->c1 | 60->c2
 *            right  leftmost=c3 | 100->c4 | 140->c5
 *            70 goes up, and is in neither half
 * </pre>
 *
 */
final class InternalSplit {

    private InternalSplit() {
        throw new AssertionError("no instances");
    }


    static SplitResult split(PageStore store, int internalPageId, byte[] separatorKey, int newChildPageId) {
        SlottedPage page = store.get(internalPageId);
        try {
            InternalNode node = new InternalNode(page);
            List<Child> children = childrenAfterInserting(node, separatorKey, newChildPageId);
            int cut = SplitPolicy.chooseSplitIndex(Child.sizesOf(children), SlottedPage.USABLE_BYTES);

            // Allocating can fail, so it happens while the node is still whole.
            int rightPageId = store.allocate(PageType.INTERNAL);
            moveRightHalf(store, node, rightPageId, children, cut);

            return new SplitResult(children.get(cut).separator(), rightPageId);
        } finally {
            store.release(internalPageId);
        }
    }

    private static void moveRightHalf(PageStore store, InternalNode left, int rightPageId,
                                      List<Child> children, int cut) {
        SlottedPage rightPage = store.get(rightPageId);
        try {
            Child.rewriteAll(new InternalNode(rightPage), rightHalf(children, cut));
            Child.rewriteAll(left, children.subList(0, cut));
        } finally {
            store.release(rightPageId);
        }
    }

    private static List<Child> rightHalf(List<Child> children, int cut) {
        List<Child> half = new ArrayList<>(children.size() - cut);
        half.add(children.get(cut).asLeftmost());
        half.addAll(children.subList(cut + 1, children.size()));
        return half;
    }

    private static List<Child> childrenAfterInserting(InternalNode node, byte[] separator, int childPageId) {
        if (separator.length == 0) {
            throw new IllegalArgumentException("an empty key is the leftmost child, not a separator");
        }
        List<Child> children = Child.readAll(node);
        children.add(insertionPointFor(children, separator), new Child(separator, childPageId));
        return children;
    }

    private static int insertionPointFor(List<Child> children, byte[] separator) {
        for (int i = 1; i < children.size(); i++) {
            if (Bytes.compare(children.get(i).separator(), separator) > 0) {
                return i;
            }
        }
        return children.size();
    }
}
