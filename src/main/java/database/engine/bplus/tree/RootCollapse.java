package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;

/**
 * Takes a level off the tree, which is the only place a B+Tree ever gets shorter.
 *
 * <p>The mirror of {@link RootSplit}. When merges leave the root with a single child, that child's
 * contents move <b>into</b> the root page and the child is freed:
 *
 * <pre>
 *   before              after
 *
 *   ┌────────┐
 *   │  root  │          ┌──────────┐
 *   └───┬────┘    ->    │   root   │   <- same page id, now holding the child's contents
 *       │               └──────────┘
 *   [ one child ]
 * </pre>
 *
 * <p>The root keeps its page id, exactly as it does when growing. And because the level comes off
 * the top, every leaf gets shallower by one at the same moment, so the tree stays balanced.
 */
final class RootCollapse {

    private RootCollapse() {
        throw new AssertionError("no instances");
    }

    static void collapse(PageStore store, int rootPageId) {
        while (collapseOnce(store, rootPageId)) {
            // keep going
        }
    }

    private static boolean collapseOnce(PageStore store, int rootPageId) {
        int onlyChildId = onlyChildOf(store, rootPageId);
        if (onlyChildId == Page.NO_PAGE) {
            return false;
        }
        PageCopy.copyInto(store, onlyChildId, rootPageId);
        store.free(onlyChildId);
        return true;
    }

    private static int onlyChildOf(PageStore store, int rootPageId) {
        SlottedPage root = store.get(rootPageId);
        try {
            if (root.type() != PageType.INTERNAL) {
                return Page.NO_PAGE;
            }
            InternalNode node = new InternalNode(root);
            return node.childCount() == 1 ? node.childAt(0) : Page.NO_PAGE;
        } finally {
            store.release(rootPageId);
        }
    }
}
