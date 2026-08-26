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

        int leafPageId = findLeafPageId(key);
        SlottedPage leaf = store.get(leafPageId);
        try {
            int index = leaf.binarySearch(key);
            if (index >= 0) {
                replace(leaf, index, key, value);
            } else {
                leaf.insertCell(-index - 1, key, value);
            }
        } finally {
            store.release(leafPageId);
        }
    }


    private static void replace(SlottedPage leaf, int index, byte[] key, byte[] value) {
        int freedBytes = SlottedPage.entrySize(key.length, leaf.value(index).length);
        int neededBytes = SlottedPage.entrySize(key.length, value.length);
        int availableBytes = leaf.freeSpace() + freedBytes;
        if (neededBytes > availableBytes) {
            throw new IllegalStateException("leaf is full: replacing needs " + neededBytes
                    + " bytes, only " + availableBytes + " would be free");
        }
        // Deleting shifts the slots above down one, which leaves exactly the gap the key belongs in.
        leaf.deleteCell(index);
        leaf.insertCell(index, key, value);
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
        throw new IllegalStateException("descent passed " + MAX_DEPTH + " levels; child pointers form a cycle");
    }

    private static void requireFitsInOneCell(byte[] key, byte[] value) {
        int cellBytes = SlottedPage.cellSize(key.length, value.length);
        if (cellBytes > SlottedPage.MAX_CELL_SIZE) {
            throw new IllegalArgumentException("cell of " + cellBytes + " bytes exceeds the "
                    + SlottedPage.MAX_CELL_SIZE + " byte limit; overflow pages are not implemented yet");
        }
    }
}
