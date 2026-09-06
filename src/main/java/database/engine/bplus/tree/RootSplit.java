package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;


final class RootSplit {

    private RootSplit() {
        throw new AssertionError("no instances");
    }

    static void grow(PageStore store, int rootPageId, SplitResult split) {
        int leftPageId = PageCopy.copyToNewPage(store, rootPageId);

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


}
