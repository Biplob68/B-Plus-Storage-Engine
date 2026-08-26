package database.engine.bplus.tree;

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
