package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;


final class PageCopy {

    private PageCopy() {
        throw new AssertionError("no instances");
    }

    static int copyToNewPage(PageStore store, int sourcePageId) {
        int copyPageId = store.allocate(typeOf(store, sourcePageId));
        copyInto(store, sourcePageId, copyPageId);
        return copyPageId;
    }


    static void copyInto(PageStore store, int sourcePageId, int targetPageId) {
        SlottedPage source = store.get(sourcePageId);
        try {
            SlottedPage target = store.get(targetPageId);
            try {
                target.reset(source.type());
                for (int i = 0; i < source.cellCount(); i++) {
                    target.insertCell(i, source.key(i), source.value(i));
                }
                target.setRightSibling(source.rightSibling());
            } finally {
                store.release(targetPageId);
            }
        } finally {
            store.release(sourcePageId);
        }
    }

    private static PageType typeOf(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return page.type();
        } finally {
            store.release(pageId);
        }
    }
}
