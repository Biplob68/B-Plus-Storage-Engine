package database.engine.bplus.tree;

import database.engine.bplus.page.Page;
import database.engine.bplus.page.PageType;
import database.engine.bplus.page.SlottedPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapPageStoreTest {

    @Test
    void idsStartAtOneSoZeroStaysReservedForNoPage() {
        HeapPageStore store = new HeapPageStore();

        assertThat(store.allocate(PageType.LEAF)).isEqualTo(1);
        assertThat(store.allocate(PageType.LEAF)).isEqualTo(2);
        assertThat(store.allocate(PageType.INTERNAL)).isEqualTo(3);
        assertThat(Page.NO_PAGE).isZero();
    }

    @Test
    void allocateFormatsAnEmptyPageOfTheRequestedType() {
        HeapPageStore store = new HeapPageStore();
        int leafId = store.allocate(PageType.LEAF);
        int internalId = store.allocate(PageType.INTERNAL);

        assertThat(store.get(leafId).type()).isEqualTo(PageType.LEAF);
        assertThat(store.get(internalId).type()).isEqualTo(PageType.INTERNAL);
        assertThat(store.get(leafId).cellCount()).isZero();
        assertThat(store.get(leafId).freeSpace()).isEqualTo(SlottedPage.USABLE_BYTES);
    }

    @Test
    void writesSurviveReleaseAndGet() {
        HeapPageStore store = new HeapPageStore();
        int pageId = store.allocate(PageType.LEAF);

        SlottedPage page = store.get(pageId);
        page.insertCell(0, new byte[] {1, 2}, new byte[] {9});
        page.setRightSibling(7);
        store.release(pageId);

        SlottedPage reopened = store.get(pageId);
        assertThat(reopened.cellCount()).isEqualTo(1);
        assertThat(reopened.key(0)).containsExactly(1, 2);
        assertThat(reopened.value(0)).containsExactly(9);
        assertThat(reopened.rightSibling()).isEqualTo(7);
    }

    @Test
    void unknownPageIsRejected() {
        HeapPageStore store = new HeapPageStore();

        assertThatThrownBy(() -> store.get(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.release(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get(Page.NO_PAGE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void borrowedCountTracksGetAndRelease() {
        HeapPageStore store = new HeapPageStore();
        int pageId = store.allocate(PageType.LEAF);

        assertThat(store.borrowedCount()).isZero();
        store.get(pageId);
        store.get(pageId);
        assertThat(store.borrowedCount()).isEqualTo(2);
        store.release(pageId);
        store.release(pageId);
        assertThat(store.borrowedCount()).isZero();
        assertThat(store.pageCount()).isEqualTo(1);
    }
}
