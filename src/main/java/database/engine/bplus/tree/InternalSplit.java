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
 */
final class InternalSplit {

    private record Child(byte[] separator, int pageId) {
    }

    private static final byte[] LEFTMOST_SEPARATOR = new byte[0];

    private static final int CHILD_ID_BYTES = 4;

    private InternalSplit() {
        throw new AssertionError("no instances");
    }


    static SplitResult split(PageStore store, int internalPageId, byte[] separatorKey, int newChildPageId) {
        SlottedPage page = store.get(internalPageId);
        try {
            InternalNode node = new InternalNode(page);
            List<Child> children = childrenAfterInserting(node, separatorKey, newChildPageId);
            int cut = SplitPolicy.chooseSplitIndex(entrySizes(children), SlottedPage.USABLE_BYTES);

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
            writeChildren(new InternalNode(rightPage), rightHalf(children, cut));
            writeChildren(left, children.subList(0, cut));
        } finally {
            store.release(rightPageId);
        }
    }


    private static List<Child> rightHalf(List<Child> children, int cut) {
        List<Child> half = new ArrayList<>(children.size() - cut);
        half.add(new Child(LEFTMOST_SEPARATOR, children.get(cut).pageId()));
        half.addAll(children.subList(cut + 1, children.size()));
        return half;
    }


    private static List<Child> childrenAfterInserting(InternalNode node, byte[] separator, int childPageId) {
        if (separator.length == 0) {
            throw new IllegalArgumentException("an empty key is the leftmost child, not a separator");
        }
        List<Child> children = new ArrayList<>(node.childCount() + 1);
        for (int i = 0; i < node.childCount(); i++) {
            children.add(new Child(node.separatorAt(i), node.childAt(i)));
        }
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


    private static int[] entrySizes(List<Child> children) {
        int[] sizes = new int[children.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = SlottedPage.entrySize(children.get(i).separator().length, CHILD_ID_BYTES);
        }
        return sizes;
    }

    private static void writeChildren(InternalNode node, List<Child> children) {
        node.clear();
        node.setLeftmostChild(children.get(0).pageId());
        for (int i = 1; i < children.size(); i++) {
            node.insertSeparator(children.get(i).separator(), children.get(i).pageId());
        }
    }
}
