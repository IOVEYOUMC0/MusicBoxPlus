package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Paging for the instrument picker. The renderer and the click handler used to compute this
// independently, so a divergence would draw one instrument and select another.
class MusicEditInstrumentPagesTest {

    @Test
    void pageSizeIsBoundedByTheSlotsThatExist() {
        assertEquals(9, MusicEditInstrumentPages.pageSize(27, 9), "only 9 slots are free");
        assertEquals(5, MusicEditInstrumentPages.pageSize(5, 9), "the configured size wins when smaller");
        assertEquals(1, MusicEditInstrumentPages.pageSize(0, 9), "a zero page size would divide by zero");
        assertEquals(1, MusicEditInstrumentPages.pageSize(5, 0), "no slots still needs a usable size");
    }

    @Test
    void paginationIsOnlyNeededWhenInstrumentsOverflowOnePage() {
        assertFalse(MusicEditInstrumentPages.needsPagination(9, 9));
        assertTrue(MusicEditInstrumentPages.needsPagination(10, 9));
    }

    @Test
    void totalPagesRoundsUp() {
        assertEquals(2, MusicEditInstrumentPages.totalPages(10, 9));
        assertEquals(3, MusicEditInstrumentPages.totalPages(19, 9));
        assertEquals(1, MusicEditInstrumentPages.totalPages(0, 9), "an empty list is still one page");
        assertEquals(1, MusicEditInstrumentPages.totalPages(5, 0), "a degenerate page size must not divide by zero");
    }

    @Test
    void withoutPaginationTheWholeListShowsAndOffsetIsIgnored() {
        MusicEditInstrumentPages.Page page = MusicEditInstrumentPages.page(5, 9, 3);

        assertEquals(0, page.startIndex());
        assertEquals(5, page.endIndex());
        assertEquals(1, page.totalPages());
    }

    @Test
    void pagesWalkTheListInOrder() {
        assertEquals(0, MusicEditInstrumentPages.page(20, 9, 0).startIndex());
        assertEquals(9, MusicEditInstrumentPages.page(20, 9, 1).startIndex());
        assertEquals(18, MusicEditInstrumentPages.page(20, 9, 2).startIndex());
    }

    @Test
    void theLastPageIsPartial() {
        MusicEditInstrumentPages.Page last = MusicEditInstrumentPages.page(20, 9, 2);

        assertEquals(18, last.startIndex());
        assertEquals(20, last.endIndex(), "the slice must not run past the list");
        assertEquals(2, last.size());
    }

    @Test
    void anOffsetPastTheEndShowsTheLastPageInsteadOfNothing() {
        // The instrument list can shrink (config change) while an offset points at page 2.
        MusicEditInstrumentPages.Page page = MusicEditInstrumentPages.page(10, 9, 99);

        assertEquals(9, page.startIndex());
        assertEquals(10, page.endIndex());
        assertFalse(page.isEmpty());
    }

    @Test
    void aNegativeOffsetIsTreatedAsTheFirstPage() {
        assertEquals(0, MusicEditInstrumentPages.page(20, 9, -4).startIndex());
    }

    @Test
    void aSlotIndexMapsToTheInstrumentItDraws() {
        // Page 1 of 20 instruments, 9 per page: slot 0 shows instrument 9, slot 8 shows 17.
        assertEquals(9, MusicEditInstrumentPages.instrumentIndex(0, 9, 1, true, 20));
        assertEquals(17, MusicEditInstrumentPages.instrumentIndex(8, 9, 1, true, 20));
    }

    @Test
    void aSlotOnAPartialLastPageStillResolves() {
        assertEquals(18, MusicEditInstrumentPages.instrumentIndex(0, 9, 2, true, 20));
        assertEquals(19, MusicEditInstrumentPages.instrumentIndex(1, 9, 2, true, 20));
    }

    @Test
    void aSlotPastTheLastInstrumentResolvesToNothing() {
        assertEquals(-1, MusicEditInstrumentPages.instrumentIndex(5, 9, 2, true, 20),
                "the last page holds two instruments, so slot 5 is empty");
        assertEquals(-1, MusicEditInstrumentPages.instrumentIndex(-1, 9, 0, true, 20));
    }

    @Test
    void withoutPaginationSlotIndicesAreDirect() {
        assertEquals(0, MusicEditInstrumentPages.instrumentIndex(0, 9, 5, false, 5));
        assertEquals(4, MusicEditInstrumentPages.instrumentIndex(4, 9, 5, false, 5));
        assertEquals(-1, MusicEditInstrumentPages.instrumentIndex(5, 9, 5, false, 5));
    }
}
