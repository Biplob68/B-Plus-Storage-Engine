package database.engine.bplus.page;

public final class Page {

    /** Bytes in one page. */
    public static final int SIZE = 4096;

    /** "No page". Page 0 is reserved for the file meta page, so it is never a valid pointer. */
    public static final int NO_PAGE = 0;

    private Page() {
        throw new AssertionError("no instances");
    }
}
