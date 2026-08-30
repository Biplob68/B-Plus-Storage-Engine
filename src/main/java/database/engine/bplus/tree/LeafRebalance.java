package database.engine.bplus.tree;

import database.engine.bplus.page.SlottedPage;

import java.util.ArrayList;
import java.util.List;


final class LeafRebalance {

    private LeafRebalance() {
        throw new AssertionError("no instances");
    }


    static boolean rebalance(PageStore store, InternalNode parent, int leftSlot) {
        int leftPageId = parent.childAt(leftSlot);
        int rightPageId = parent.childAt(leftSlot + 1);
        List<Entry> pooled = pooledEntries(store, leftPageId, rightPageId);

        if (Entry.totalSize(pooled) <= SlottedPage.USABLE_BYTES) {
            merge(store, parent, leftSlot, leftPageId, rightPageId, pooled);
            return true;
        }
        return redistribute(store, parent, leftSlot, leftPageId, rightPageId, pooled);
    }

    private static void merge(PageStore store, InternalNode parent, int leftSlot,
                              int leftPageId, int rightPageId, List<Entry> pooled) {
        int inheritedSibling = rightSiblingOf(store, rightPageId);

        SlottedPage left = store.get(leftPageId);
        try {
            Entry.rewriteAll(left, pooled);
            left.setRightSibling(inheritedSibling);
        } finally {
            store.release(leftPageId);
        }
        parent.removeChild(leftSlot + 1);
        store.free(rightPageId);
    }


    private static boolean redistribute(PageStore store, InternalNode parent, int leftSlot,
                                        int leftPageId, int rightPageId, List<Entry> pooled) {
        int cut = SplitPolicy.chooseSplitIndex(Entry.sizesOf(pooled), SlottedPage.USABLE_BYTES);
        byte[] newSeparator = pooled.get(cut).key();
        if (!parent.canReplaceSeparator(leftSlot + 1, newSeparator)) {
            return false;
        }

        writeEntries(store, leftPageId, pooled.subList(0, cut));
        writeEntries(store, rightPageId, pooled.subList(cut, pooled.size()));
        parent.replaceSeparator(leftSlot + 1, newSeparator);
        return true;
    }

    private static List<Entry> pooledEntries(PageStore store, int leftPageId, int rightPageId) {
        List<Entry> pooled = new ArrayList<>(readEntries(store, leftPageId));
        pooled.addAll(readEntries(store, rightPageId));
        return pooled;
    }

    private static List<Entry> readEntries(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return Entry.readAll(page);
        } finally {
            store.release(pageId);
        }
    }

    private static void writeEntries(PageStore store, int pageId, List<Entry> entries) {
        SlottedPage page = store.get(pageId);
        try {
            Entry.rewriteAll(page, entries);
        } finally {
            store.release(pageId);
        }
    }

    private static int rightSiblingOf(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return page.rightSibling();
        } finally {
            store.release(pageId);
        }
    }
}
