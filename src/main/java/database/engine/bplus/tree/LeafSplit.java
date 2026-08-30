package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;

import java.util.List;

final class LeafSplit {

    private LeafSplit() {
        throw new AssertionError("no instances");
    }


    static SplitResult split(PageStore store, int leafPageId, byte[] newKey, byte[] newValue) {
        SlottedPage leaf = store.get(leafPageId);
        try {
            requireLeaf(leaf, leafPageId);
            List<Entry> entries = entriesAfterInserting(leaf, newKey, newValue);
            int splitIndex = SplitPolicy.chooseSplitIndex(Entry.sizesOf(entries), SlottedPage.USABLE_BYTES);

            // Allocating can fail, so it happens while the leaf is still whole.
            int rightPageId = store.allocate(PageType.LEAF);
            moveRightHalf(store, leaf, rightPageId, entries, splitIndex);

            return new SplitResult(entries.get(splitIndex).key(), rightPageId);
        } finally {
            store.release(leafPageId);
        }
    }

    private static void moveRightHalf(PageStore store, SlottedPage leaf, int rightPageId,
                                      List<Entry> entries, int splitIndex) {
        SlottedPage right = store.get(rightPageId);
        try {
            Entry.rewriteAll(right, entries.subList(splitIndex, entries.size()));
            Entry.rewriteAll(leaf, entries.subList(0, splitIndex));
            linkSiblings(leaf, right, rightPageId);
        } finally {
            store.release(rightPageId);
        }
    }

    private static List<Entry> entriesAfterInserting(SlottedPage leaf, byte[] key, byte[] value) {
        int index = leaf.binarySearch(key);
        if (index >= 0) {
            throw new IllegalArgumentException("key is already in the leaf; remove it before splitting");
        }
        List<Entry> entries = Entry.readAll(leaf);
        entries.add(-index - 1, new Entry(key, value));
        return entries;
    }


    private static void linkSiblings(SlottedPage leaf, SlottedPage right, int rightPageId) {
        right.setRightSibling(leaf.rightSibling());
        leaf.setRightSibling(rightPageId);
    }

    private static void requireLeaf(SlottedPage page, int pageId) {
        if (page.type() != PageType.LEAF) {
            throw new IllegalArgumentException("page " + pageId + " is not a leaf");
        }
    }
}
