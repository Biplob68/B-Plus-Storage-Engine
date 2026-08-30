package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;


final class HeapPageStore implements PageStore {

    private final Map<Integer, ByteBuffer> pages = new HashMap<>();

    /**
     * Ids start at 1, because 0 means {@link Page#NO_PAGE}.
     */
    private int nextPageId = 1;

    private int borrowed;

    private int freed;

    @Override
    public int allocate(PageType type) {
        int pageId = nextPageId++;
        ByteBuffer buffer = ByteBuffer.allocate(Page.SIZE);
        SlottedPage.init(buffer, type);
        pages.put(pageId, buffer);
        return pageId;
    }

    @Override
    public SlottedPage get(int pageId) {
        ByteBuffer buffer = pages.get(pageId);
        if (buffer == null) {
            throw new IllegalArgumentException("no such page: " + pageId);
        }
        borrowed++;
        return SlottedPage.wrap(buffer);
    }

    @Override
    public void release(int pageId) {
        if (!pages.containsKey(pageId)) {
            throw new IllegalArgumentException("no such page: " + pageId);
        }
        borrowed--;
    }

    @Override
    public void free(int pageId) {
        if (pages.remove(pageId) == null) {
            throw new IllegalArgumentException("no such page: " + pageId);
        }
        freed++;
    }

    /** Pages handed back by merges. A real store would put them on a free list. */
    int freedCount() {
        return freed;
    }

    int borrowedCount() {
        return borrowed;
    }

    int pageCount() {
        return pages.size();
    }
}
