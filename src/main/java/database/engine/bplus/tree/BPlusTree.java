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

    public boolean delete(byte[] key) {
        Objects.requireNonNull(key, "key");
        return deleteFrom(rootPageId, key, 0);
    }

    private boolean deleteFrom(int pageId, byte[] key, int depth) {
        if (depth >= MAX_DEPTH) {
            throw cycleDetected();
        }
        SlottedPage page = store.get(pageId);
        try {
            return page.type() == PageType.LEAF
                    ? deleteFromLeaf(page, key)
                    : deleteFromInternal(new InternalNode(page), key, depth);
        } finally {
            store.release(pageId);
        }
    }

    private static boolean deleteFromLeaf(SlottedPage leaf, byte[] key) {
        int index = leaf.binarySearch(key);
        if (index < 0) {
            return false;
        }
        leaf.deleteCell(index);
        return true;
    }


    private boolean deleteFromInternal(InternalNode node, byte[] key, int depth) {
        int childSlot = node.findChildSlot(key);
        if (!deleteFrom(node.childAt(childSlot), key, depth + 1)) {
            return false;
        }
        rebalanceIfUnderflowed(node, childSlot);
        return true;
    }

    private void rebalanceIfUnderflowed(InternalNode parent, int childSlot) {
        if (!isUnderflowedLeaf(parent.childAt(childSlot))) {
            return;
        }
        int leftSlot = adjacentPairFor(parent, childSlot);
        if (leftSlot < 0) {
            return; // an only child has no sibling to pool with
        }
        LeafRebalance.rebalance(store, parent, leftSlot);
    }

    private static int adjacentPairFor(InternalNode parent, int childSlot) {
        if (childSlot + 1 < parent.childCount()) {
            return childSlot;
        }
        return childSlot > 0 ? childSlot - 1 : -1;
    }


    private boolean isUnderflowedLeaf(int pageId) {
        SlottedPage page = store.get(pageId);
        try {
            if (page.type() != PageType.LEAF) {
                return false;
            }
            int usedBytes = SlottedPage.USABLE_BYTES - page.freeSpace();
            return usedBytes * 3 < SlottedPage.USABLE_BYTES;
        } finally {
            store.release(pageId);
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
