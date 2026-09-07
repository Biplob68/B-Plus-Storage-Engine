package database.engine.bplus.store;

import database.engine.bplus.page.Page;

import java.nio.ByteBuffer;


final class Frame {

    private final ByteBuffer buffer = ByteBuffer.allocate(Page.SIZE);

    private int pageId = Page.NO_PAGE;

    private int pinCount;
    private boolean dirty;

    ByteBuffer buffer() {
        return buffer;
    }

    int pageId() {
        return pageId;
    }

    int pinCount() {
        return pinCount;
    }

    boolean isPinned() {
        return pinCount > 0;
    }

    boolean isDirty() {
        return dirty;
    }

    void assignTo(int pageId) {
        this.pageId = pageId;
        this.pinCount = 0;
        this.dirty = false;
    }

    void pin() {
        pinCount++;
    }

    void unpin() {
        if (pinCount == 0) {
            throw new IllegalStateException(
                    "page " + pageId + " was released more often than it was borrowed");
        }
        pinCount--;
    }

    void markDirty() {
        dirty = true;
    }

    void markClean() {
        dirty = false;
    }
}
