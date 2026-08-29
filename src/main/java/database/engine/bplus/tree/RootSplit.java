package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;


final class RootSplit {

    private RootSplit() {
        throw new AssertionError("no instances");
    }

    static void growNewRoot(PageStore store, int rootPageId, SplitResult split) {
        int leftPageId = copyOf(store, rootPageId);

        SlottedPage rootPage = store.get(rootPageId);
        try {
            rootPage.reset(PageType.INTERNAL);
            InternalNode root = new InternalNode(rootPage);
            root.setLeftmostChild(leftPageId);
            root.insertSeparator(split.separatorKey(), split.newPageId());
        } finally {
            store.release(rootPageId);
        }
    }


    private static int copyOf(PageStore store, int sourcePageId) {
        SlottedPage source = store.get(sourcePageId);
        try {
            int copyPageId = store.allocate(source.type());
            SlottedPage copy = store.get(copyPageId);
            try {
                for (int i = 0; i < source.cellCount(); i++) {
                    copy.insertCell(i, source.key(i), source.value(i));
                }
                copy.setRightSibling(source.rightSibling());
            } finally {
                store.release(copyPageId);
            }
            return copyPageId;
        } finally {
            store.release(sourcePageId);
        }
    }
}
