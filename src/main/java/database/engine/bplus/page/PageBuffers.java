package database.engine.bplus.page;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

final class PageBuffers {

    private static final byte[] ZEROES = new byte[Page.SIZE];

    private PageBuffers() {
        throw new AssertionError("no instances");
    }

    static ByteBuffer view(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.isReadOnly()) {
            throw new IllegalArgumentException("page buffer must be writable");
        }
        if (buffer.capacity() != Page.SIZE) {
            throw new IllegalArgumentException("capacity must be Page.SIZE, got " + buffer.capacity());
        }
        // clear(): absolute get/put bounds-check against the limit, not the capacity
        return buffer.duplicate().clear().order(ByteOrder.BIG_ENDIAN);
    }

    static void zero(ByteBuffer buffer) {
        buffer.put(0, ZEROES, 0, Page.SIZE);
    }
}
