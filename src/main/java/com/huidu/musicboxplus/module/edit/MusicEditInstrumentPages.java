package com.huidu.musicboxplus.module.edit;

/**
 * Paging rules for the instrument picker.
 *
 * The same arithmetic appeared three times -- twice in the renderer, once in the click handler --
 * as needPagination ? offset * pageSize : 0 plus a Math.min for the end index. Two
 * of those copies decide what is drawn and one decides what a click selects, so a divergence would
 * paint one instrument and pick another.
 *
 * Also holds the one "no pagination" special case: when every instrument fits on one page the
 * offset is ignored entirely, which is what kept the picker from showing an empty second page.
 */
final class MusicEditInstrumentPages {

    /** A visible slice of the instrument list. */
    record Page(int startIndex, int endIndex, int totalPages) {
        int size() {
            return endIndex - startIndex;
        }

        boolean isEmpty() {
            return endIndex <= startIndex;
        }
    }

    private MusicEditInstrumentPages() {
    }

    /** Slots the picker actually has, bounded by the configured page size. */
    static int pageSize(int configuredPerPage, int availableSlots) {
        return Math.max(1, Math.min(configuredPerPage, availableSlots));
    }

    static boolean needsPagination(int totalInstruments, int pageSize) {
        return totalInstruments > pageSize;
    }

    static int totalPages(int totalInstruments, int pageSize) {
        int total = Math.max(0, totalInstruments);
        if (pageSize <= 0) {
            // A degenerate page size cannot hold anything, so report a single page; the caller's
            // pageSize() already clamps to 1, and returning e.g. 5 here would contradict it.
            return 1;
        }
        return Math.max(1, (int) Math.ceil((double) total / pageSize));
    }

    /**
     * The slice for one page.
     *
     * Without pagination the whole list is returned regardless of offset. With pagination the
     * offset is clamped into range, so a stale offset (left over after the instrument list shrank)
     * shows the last page instead of an empty one.
     */
    static Page page(int totalInstruments, int pageSize, int offset) {
        int size = Math.max(1, pageSize);
        int total = Math.max(0, totalInstruments);
        int pages = totalPages(total, size);
        if (!needsPagination(total, size)) {
            return new Page(0, total, pages);
        }
        int page = Math.max(0, Math.min(offset, pages - 1));
        int start = page * size;
        return new Page(start, Math.min(start + size, total), pages);
    }

    /** Index of an instrument from its slot position on the current page, or -1 when out of range. */
    static int instrumentIndex(int slotIndex, int pageSize, int offset, boolean paginated, int totalInstruments) {
        if (slotIndex < 0) {
            return -1;
        }
        Page page = page(totalInstruments, pageSize, offset);
        int index = (paginated ? page.startIndex() : 0) + slotIndex;
        return index < page.endIndex() || !paginated && index < totalInstruments ? index : -1;
    }

    /**
     * Layout slot to player-inventory slot for the picker grid.
     *
     * Deliberately not the editor toolbar's mapping (MusicEditLayout): the picker
     * draws its 3x9 grid into main inventory rows 9..35 and its control row into hotbar row 0, while
     * the toolbar uses layout rows 6..9. Sharing one mapping between them would silently move every
     * picker button one row down.
     */
    static int layoutSlotToPlayerSlot(int layoutSlot) {
        if (layoutSlot < 0) {
            return -1;
        }
        int row = layoutSlot / 9;
        int col = layoutSlot % 9;
        if (row >= 0 && row < 3) {
            return 9 + row * 9 + col;
        }
        if (row == 3) {
            return col;
        }
        return -1;
    }
}
