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
        store.get(rootPageId); // fails fast if the id was never allocated
        store.release(rootPageId);
        return new BPlusTree(store, rootPageId);
    }

    public int rootPageId() {
        return rootPageId;
    }


    public byte[] get(byte[] key) {
        Objects.requireNonNull(key, "key");
        int leafPageId = findLeafPageId(key);
        SlottedPage leaf = store.get(leafPageId);
        try {
            int index = leaf.binarySearch(key);
            return index >= 0 ? leaf.value(index) : null;
        } finally {
            store.release(leafPageId);
        }
    }


    public void put(byte[] key, byte[] value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        requireFitsInOneCell(key, value);

        SplitResult rootSplit = insertInto(rootPageId, key, value, 0);
        if (rootSplit != null) {
            RootSplit.growNewRoot(store, rootPageId, rootSplit);
        }
    }

    private SplitResult insertInto(int pageId, byte[] key, byte[] value, int depth) {
        if (depth >= MAX_DEPTH) {
            throw cycleDetected();
        }
        SlottedPage page = store.get(pageId);
        try {
            return page.type() == PageType.LEAF
                    ? insertIntoLeaf(pageId, page, key, value)
                    : insertIntoInternal(pageId, page, key, value, depth);
        } finally {
            store.release(pageId);
        }
    }

    private SplitResult insertIntoLeaf(int pageId, SlottedPage leaf, byte[] key, byte[] value) {
        int index = leaf.binarySearch(key);
        int insertionPoint = index >= 0 ? index : -index - 1;
        if (index >= 0) {
            // insertCell rejects duplicates, so replacing means removing first. Deleting shifts the
            // slots above down one, which leaves exactly the gap the key belongs in.
            leaf.deleteCell(index);
        }
        if (leaf.hasSpaceFor(key.length, value.length)) {
            leaf.insertCell(insertionPoint, key, value);
            return null;
        }
        return LeafSplit.split(store, pageId, key, value);
    }

    private SplitResult insertIntoInternal(int pageId, SlottedPage page, byte[] key, byte[] value, int depth) {
        InternalNode node = new InternalNode(page);
        SplitResult childSplit = insertInto(node.findChild(key), key, value, depth + 1);
        if (childSplit == null) {
            return null;
        }
        if (node.hasSpaceForSeparator(childSplit.separatorKey().length)) {
            node.insertSeparator(childSplit.separatorKey(), childSplit.newPageId());
            return null;
        }
        return InternalSplit.split(store, pageId, childSplit.separatorKey(), childSplit.newPageId());
    }

    private int findLeafPageId(byte[] key) {
        int pageId = rootPageId;

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            SlottedPage page = store.get(pageId);
            int childPageId;
            try {
                if (page.type() == PageType.LEAF) {
                    return pageId;
                }
                childPageId = new InternalNode(page).findChild(key);
            } finally {
                store.release(pageId);
            }
            pageId = childPageId;
        }
        throw cycleDetected();
    }

    private static IllegalStateException cycleDetected() {
        return new IllegalStateException("descent passed " + MAX_DEPTH + " levels; child pointers form a cycle");
    }

    private static void requireFitsInOneCell(byte[] key, byte[] value) {
        int cellBytes = SlottedPage.cellSize(key.length, value.length);
        if (cellBytes > SlottedPage.MAX_CELL_SIZE) {
            throw new IllegalArgumentException("cell of " + cellBytes + " bytes exceeds the "
                    + SlottedPage.MAX_CELL_SIZE + " byte limit; overflow pages are not implemented yet");
        }
    }
}
