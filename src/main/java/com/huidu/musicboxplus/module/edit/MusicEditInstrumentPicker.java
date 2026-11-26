package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.config.InstrumentSelectConfig;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Clicks inside the instrument picker: page arrows, preview, back, and toggling one instrument on the
 * selected note.
 *
 * Split out of MusicEditGUI for the same reason as the note grid: the arithmetic is
 * testable data, while the window only needs to supply state. The paging rules themselves live in
 * MusicEditInstrumentPages, which the renderer also uses, so a click and the icon under it
 * can no longer disagree about which instrument a slot means.
 */
final class MusicEditInstrumentPicker {

    /** The window state one picker click reads. */
    interface Host {
        Player player();

        MusicNote selectedNote();

        int instrumentPageOffset();

        void instrumentPageOffset(int offset);

        int instrumentsPerPage();

        boolean playbackActive();

        void stopPlayback();

        void previewNoteColumn(int pitch, int tick);

        void enterEditMode();

        void clearSelectedNote();

        void paintHotbar();

        void refreshGrid();

        void markEdited();

        void flashCell(int pitch, int tick);

        void previewNote(int pitch, MusicNote.NoteInstrument instrument);
    }

    private final Host host;

    MusicEditInstrumentPicker(Host host) {
        this.host = host;
    }

    void click(int slot) {
        InstrumentSelectConfig config = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicNote.NoteInstrument[] instruments = MusicNote.NoteInstrument.getAvailableValues();
        List<Integer> instrumentSlots = MusicEditInstrumentMenuRenderer.getPlayerInventorySlotsForInstrumentChar(
                config, config.getButtonMapping().getOrDefault("instrument", 'I'));
        int pageSize = MusicEditInstrumentPages.pageSize(host.instrumentsPerPage(), instrumentSlots.size());
        boolean paginated = MusicEditInstrumentPages.needsPagination(instruments.length, pageSize);

        int prevSlot = MusicEditInstrumentMenuRenderer.getPlayerInventorySlotForInstrumentButton(config, "prev-page");
        int nextSlot = MusicEditInstrumentMenuRenderer.getPlayerInventorySlotForInstrumentButton(config, "next-page");
        int playSlot = MusicEditInstrumentMenuRenderer.getPlayerInventorySlotForInstrumentButton(config, "play-preview");
        int backSlot = MusicEditInstrumentMenuRenderer.getPlayerInventorySlotForInstrumentButton(config, "back");

        // The arrows only exist when there is more than one page; on a single page the same slot is
        // free for instruments, so it must not be swallowed as a no-op arrow press.
        if (paginated && slot == prevSlot) {
            if (host.instrumentPageOffset() > 0) {
                host.instrumentPageOffset(host.instrumentPageOffset() - 1);
                host.paintHotbar();
            }
            return;
        }

        if (paginated && slot == nextSlot) {
            int lastPage = MusicEditInstrumentPages.totalPages(instruments.length, pageSize) - 1;
            if (host.instrumentPageOffset() < lastPage) {
                host.instrumentPageOffset(host.instrumentPageOffset() + 1);
                host.paintHotbar();
            }
            return;
        }

        if (slot == playSlot) {
            MusicNote note = host.selectedNote();
            if (note != null) {
                if (host.playbackActive()) {
                    host.stopPlayback();
                }
                host.previewNoteColumn(note.getPitch(), note.getTick());
            }
            return;
        }

        if (slot == backSlot) {
            host.enterEditMode();
            host.clearSelectedNote();
            host.instrumentPageOffset(0);
            host.paintHotbar();
            host.refreshGrid();
            return;
        }

        MusicNote note = host.selectedNote();
        int slotIndex = instrumentSlots.indexOf(slot);
        if (slotIndex < 0 || note == null) {
            return;
        }
        int index = MusicEditInstrumentPages.instrumentIndex(
                slotIndex, pageSize, host.instrumentPageOffset(), paginated, instruments.length);
        if (index < 0) {
            return;
        }

        MusicNote.NoteInstrument instrument = instruments[index];
        if (note.getInstruments().contains(instrument)) {
            note.removeInstrument(instrument);
        } else {
            note.addInstrument(instrument);
        }
        host.markEdited();
        // flashPreviewHighlight renders the grid itself (its immediate onUpdate), so the explicit
        // updateInventory() that used to follow this was a second identical 54-slot rebuild.
        host.flashCell(note.getPitch(), note.getTick());
        host.previewNote(note.getPitch(), instrument);
        host.paintHotbar();
    }
}
