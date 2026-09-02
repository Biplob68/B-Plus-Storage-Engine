package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.util.Bytes;

import java.util.List;


public final class Cursor {

    private final PageStore store;

    private final byte[] toExclusive;

    private List<Entry> bufferedEntries;
    private int nextIndex;
    private int nextLeafPageId;
    private Entry currentEntry;

    Cursor(PageStore store, int startLeafPageId, byte[] fromInclusive, byte[] toExclusive) {
        this.store = store;
        this.toExclusive = toExclusive;
        bufferLeaf(startLeafPageId);
        if (fromInclusive != null) {
            nextIndex = firstIndexAtOrAfter(fromInclusive);
        }
    }

    public boolean next() {
        while (nextIndex >= bufferedEntries.size()) {
            if (nextLeafPageId == Page.NO_PAGE) {
                finish();
                return false;
            }
            bufferLeaf(nextLeafPageId);
        }

        Entry entry = bufferedEntries.get(nextIndex++);
        if (isPastEnd(entry.key())) {
            finish();
            return false;
        }
        currentEntry = entry;
        return true;
    }

    public byte[] key() {
        return requireCurrentEntry().key();
    }

    public byte[] value() {
        return requireCurrentEntry().value();
    }

    private Entry requireCurrentEntry() {
        if (currentEntry == null) {
            throw new IllegalStateException("no current entry; call next() and check it returned true");
        }
        return currentEntry;
    }

    private void bufferLeaf(int pageId) {
        SlottedPage leaf = store.get(pageId);
        try {
            bufferedEntries = Entry.readAll(leaf);
            nextLeafPageId = leaf.rightSibling();
        } finally {
            store.release(pageId);
        }
        nextIndex = 0;
    }

    private int firstIndexAtOrAfter(byte[] fromInclusive) {
        for (int i = 0; i < bufferedEntries.size(); i++) {
            if (Bytes.compare(bufferedEntries.get(i).key(), fromInclusive) >= 0) {
                return i;
            }
        }
        return bufferedEntries.size();
    }

    private boolean isPastEnd(byte[] key) {
        return toExclusive != null && Bytes.compare(key, toExclusive) >= 0;
    }

    private void finish() {
        currentEntry = null;
        bufferedEntries = List.of();
        nextIndex = 0;
        nextLeafPageId = Page.NO_PAGE;
    }
}
