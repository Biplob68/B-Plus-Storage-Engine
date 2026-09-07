package database.engine.bplus.store;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import database.engine.bplus.tree.PageStore;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A fixed set of page-sized frames sitting between the tree and the file.
 *
 * <pre>
 *   BPlusTree -> PageStore -> BufferPool -> Pager -> file
 *
 *   frame   pageId  pins  dirty
 *   -----   ------  ----  -----
 *     0        7      2   true    in use, changed
 *     1       12      0   true    idle, but giving it up costs a write
 *     2        3      0   false   idle and clean
 * </pre>
 */
public final class BufferPool implements PageStore {

    public static final int DEFAULT_FRAME_COUNT = 64;

    private static final int MIN_FRAME_COUNT = 8;

    private final Pager pager;
    private final Frame[] frames;
    private final Map<Integer, Frame> residentFrames = new HashMap<>();
    private final Deque<Frame> freeFrames = new ArrayDeque<>();

    private BufferPool(Pager pager, int frameCount) {
        this.pager = pager;
        this.frames = new Frame[frameCount];
        for (int i = 0; i < frameCount; i++) {
            frames[i] = new Frame();
            freeFrames.add(frames[i]);
        }
    }

    public static BufferPool of(Pager pager) {
        return of(pager, DEFAULT_FRAME_COUNT);
    }

    public static BufferPool of(Pager pager, int frameCount) {
        Objects.requireNonNull(pager, "pager");
        if (frameCount < MIN_FRAME_COUNT) {
            throw new IllegalArgumentException(
                    "a pool of " + frameCount + " frames is too small; need at least " + MIN_FRAME_COUNT);
        }
        return new BufferPool(pager, frameCount);
    }

    @Override
    public int allocate(PageType type) {
        Objects.requireNonNull(type, "type");
        Frame frame = takeFreeFrame();
        int pageId;
        try {
            pageId = pager.allocate();
        } catch (RuntimeException e) {
            giveBack(frame);
            throw e;
        }
        frame.assignTo(pageId);
        SlottedPage.init(frame.buffer(), type);
        frame.markDirty();
        residentFrames.put(pageId, frame);
        return pageId;
    }

    @Override
    public SlottedPage get(int pageId) {
        Frame frame = residentFrames.get(pageId);
        if (frame == null) {
            frame = readThrough(pageId);
        }
        frame.pin();
        return SlottedPage.wrap(frame.buffer());
    }

    @Override
    public void release(int pageId) {
        Frame frame = requireResident(pageId);
        frame.unpin();
        // get hands out a writable page, so the pool cannot tell a read from a write.
        frame.markDirty();
    }

    @Override
    public void free(int pageId) {
        Frame frame = residentFrames.get(pageId);
        if (frame == null) {
            return;
        }
        if (frame.isPinned()) {
            throw new IllegalStateException("page " + pageId + " is still borrowed and cannot be freed");
        }
        residentFrames.remove(pageId);
        giveBack(frame);
    }

    public void flush() {
        for (Frame frame : frames) {
            if (frame.isDirty()) {
                pager.writePage(frame.pageId(), frame.buffer());
                frame.markClean();
            }
        }
    }

    private Frame readThrough(int pageId) {
        Frame frame = takeFreeFrame();
        try {
            pager.readPage(pageId, frame.buffer());
        } catch (RuntimeException e) {
            giveBack(frame);
            throw e;
        }
        frame.assignTo(pageId);
        residentFrames.put(pageId, frame);
        return frame;
    }

    private void giveBack(Frame frame) {
        frame.assignTo(Page.NO_PAGE);
        freeFrames.add(frame);
    }

    private Frame takeFreeFrame() {
        Frame frame = freeFrames.poll();
        if (frame == null) {
            throw new IllegalStateException("buffer pool is full: all " + frames.length + " frames are in use");
        }
        return frame;
    }

    private Frame requireResident(int pageId) {
        Frame frame = residentFrames.get(pageId);
        if (frame == null) {
            throw new IllegalArgumentException("page " + pageId + " is not in the pool");
        }
        return frame;
    }

    boolean isResident(int pageId) {
        return residentFrames.containsKey(pageId);
    }

    int pinCountOf(int pageId) {
        return requireResident(pageId).pinCount();
    }

    boolean isDirty(int pageId) {
        return requireResident(pageId).isDirty();
    }
}
