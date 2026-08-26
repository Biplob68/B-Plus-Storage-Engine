package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;

import java.util.Objects;


public final class BPlusTree {

    private static final int MAX_DEPTH = 64;

    private final PageStore store;
    private final int rootPageId;

    private BPlusTree(PageStore store, int rootPageId) {
        this.store = store;
        this.rootPageId = rootPageId;
    }

    public static BPlusTree create(PageStore store) {
        Objects.requireNonNull(store, "store");
        return new BPlusTree(store, store.allocate(PageType.LEAF));
    }

    public static BPlusTree open(PageStore store, int rootPageId) {
        Objects.requireNonNull(store, "store");
        store.get(rootPageId);
        store.release(rootPageId);
        return new BPlusTree(store, rootPageId);
    }

    public int rootPageId() {
        return rootPageId;
    }

    public byte[] get(byte[] key) {
        Objects.requireNonNull(key, "key");
        int pageId = rootPageId;

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            SlottedPage page = store.get(pageId);
            int childPageId;
            try {
                if (page.type() == PageType.LEAF) {
                    int index = page.binarySearch(key);
                    return index >= 0 ? page.value(index) : null;
                }
                childPageId = new InternalNode(page).findChild(key);
            } finally {
                store.release(pageId);
            }
            pageId = childPageId;
        }
        throw new IllegalStateException("descent passed " + MAX_DEPTH + " levels; child pointers form a cycle");
    }
}
