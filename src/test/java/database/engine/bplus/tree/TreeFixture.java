package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Builders shared by the tree tests, so no test has to know how a page is laid out. */
final class TreeFixture {

    private TreeFixture() {
        throw new AssertionError("no instances");
    }

    static byte[] key(int i) {
        return new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i};
    }

    static byte[] value(int i) {
        return value(i, 8);
    }

    static byte[] value(int i, int length) {
        byte[] value = new byte[length];
        Arrays.fill(value, (byte) i);
        return value;
    }

    /** A leaf holding the given keys, returned as its page id. */
    static int leafOf(PageStore store, int... keys) {
        int pageId = store.allocate(PageType.LEAF);
        SlottedPage leaf = store.get(pageId);
        for (int i = 0; i < keys.length; i++) {
            leaf.insertCell(i, key(keys[i]), value(keys[i]));
        }
        store.release(pageId);
        return pageId;
    }

    /** An internal page with only its leftmost child set, returned as its page id. */
    static int internalOf(PageStore store, int leftmostChildId) {
        int pageId = store.allocate(PageType.INTERNAL);
        InternalNode node = new InternalNode(store.get(pageId));
        node.setLeftmostChild(leftmostChildId);
        store.release(pageId);
        return pageId;
    }

    static void addSeparator(PageStore store, int internalPageId, int separator, int childPageId) {
        InternalNode node = new InternalNode(store.get(internalPageId));
        node.insertSeparator(key(separator), childPageId);
        store.release(internalPageId);
    }

    /** Levels from the root down to a leaf. A single leaf root is height 1. */
    static int heightOf(PageStore store, int rootPageId) {
        int height = 1;
        for (int pageId = rootPageId; ; height++) {
            int childPageId = leftChildOf(store, pageId);
            if (childPageId == Page.NO_PAGE) {
                return height;
            }
            pageId = childPageId;
        }
    }

    /** Every key in the tree, read by walking the leaf chain rather than descending per key. */
    static List<byte[]> scanAllKeys(PageStore store, int rootPageId) {
        List<byte[]> keys = new ArrayList<>();
        int pageId = leftmostLeafOf(store, rootPageId);

        while (pageId != Page.NO_PAGE) {
            SlottedPage leaf = store.get(pageId);
            int nextPageId;
            try {
                for (int i = 0; i < leaf.cellCount(); i++) {
                    keys.add(leaf.key(i));
                }
                nextPageId = leaf.rightSibling();
            } finally {
                store.release(pageId);
            }
            pageId = nextPageId;
        }
        return keys;
    }

    private static int leftmostLeafOf(PageStore store, int rootPageId) {
        int pageId = rootPageId;
        for (int childPageId = leftChildOf(store, pageId); childPageId != Page.NO_PAGE;
             childPageId = leftChildOf(store, pageId)) {
            pageId = childPageId;
        }
        return pageId;
    }

    /** Slot 0's child, or {@link Page#NO_PAGE} when the page is a leaf. */
    private static int leftChildOf(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return page.type() == PageType.LEAF ? Page.NO_PAGE : new InternalNode(page).childAt(0);
        } finally {
            store.release(pageId);
        }
    }

    static List<byte[]> keysOf(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            List<byte[]> keys = new ArrayList<>(page.cellCount());
            for (int i = 0; i < page.cellCount(); i++) {
                keys.add(page.key(i));
            }
            return keys;
        } finally {
            store.release(pageId);
        }
    }

    static int cellCountOf(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return page.cellCount();
        } finally {
            store.release(pageId);
        }
    }

    static int rightSiblingOf(PageStore store, int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            return page.rightSibling();
        } finally {
            store.release(pageId);
        }
    }

    static void chain(PageStore store, int leftLeafId, int rightLeafId) {
        SlottedPage left = store.get(leftLeafId);
        left.setRightSibling(rightLeafId);
        store.release(leftLeafId);
    }
}
