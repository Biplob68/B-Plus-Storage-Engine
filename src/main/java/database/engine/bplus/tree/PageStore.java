package database.engine.bplus.tree;

import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;


public interface PageStore {

    /**
     * Formats a new page of the given type and returns its id.
     *
     * <p>Ids start at 1. Page 0 is {@link database.engine.bplus.page.Page#NO_PAGE}, so handing it
     * out would make "no sibling" and "page 0" the same value.
     */
    int allocate(PageType type);

    /**
     * Borrows a page.
     */
    SlottedPage get(int pageId);

    /**
     * Gives back a page borrowed with {@link #get}.
     */
    void release(int pageId);
}
