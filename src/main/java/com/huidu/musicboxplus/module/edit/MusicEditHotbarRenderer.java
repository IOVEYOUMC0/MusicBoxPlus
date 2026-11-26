package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.config.HotbarButtonConfig;
import com.huidu.musicboxplus.common.config.MusicEditorConfig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;


/**
 * Draws the editor's toolbar into the player's own inventory.
 *
 * The toolbar is not a Bukkit inventory: rows 6..9 of the layout are the player's main inventory
 * and row 9 their hotbar, so "rendering a menu" here means overwriting slots of the player's
 * inventory and restoring them on exit. That is why the loop needs slot translation at all, and why
 * it is worth keeping away from the editor window's other responsibilities.
 *
 * Everything it needs from the window is behind Host; the item construction itself stays
 * in HotbarButtonConfig-driven code the window already owns.
 */
final class MusicEditHotbarRenderer {

    /** The window state one toolbar pass reads. */
    interface Host {
        Player player();

        /** The editor window config (button definitions, layout, empty character). */
        MusicEditorConfig editorConfig();

        MusicEditLayout editLayout();

        MusicEditHotbarActions hotbarActions();

        PlayerMusic music();

        MusicNote.NoteInstrument currentInstrument();

        boolean playbackActive();

        /** Placeholder values for the status-bearing buttons. */
        String[] statusReplacements();

        int clipboardSize();

        boolean canUndo();

        boolean canRedo();

        int selectionCount();

        void paintButton(int playerSlot, HotbarButtonConfig button, String[] replacements);

        boolean slotBlank(ItemStack item);
    }

    private final Host host;

    MusicEditHotbarRenderer(Host host) {
        this.host = host;
    }

    /** Paints the toolbar, or does nothing when the layout defines no toolbar rows. */
    void render() {
        MusicEditLayout layout = host.editLayout();
        if (layout.lineCount() == 0) {
            return;
        }

        HotbarButtonConfig playConfig = host.playbackActive()
                ? host.editorConfig().getButton("play-stop")
                : host.editorConfig().getButton("play");
        HotbarButtonConfig emptyConfig = host.editorConfig().getButton("empty");

        // Asked once per pass, and only when a visible button actually shows it: answering it walks
        // the selection rectangle with one synchronized, string-keyed lookup per cell.
        int selectionCount = needsSelectionCount(layout) ? host.selectionCount() : 0;
        // Same reason at smaller scale: five buttons share this identical placeholder set, and
        // building it per button formats both ranges and eight values five times per repaint.
        String[] statusReplacements = needsStatus(layout) ? host.statusReplacements() : null;

        clearEditorArea();

        for (int row = 6; row < layout.lineCount() && row < 10; row++) {
            String line = layout.line(row);
            for (int col = 0; col < line.length() && col < 9; col++) {
                int playerSlot = MusicEditLayout.layoutSlotToPlayerSlot(row * 9 + col);
                if (playerSlot < 0 || playerSlot >= 36) {
                    continue;
                }

                MusicEditHotbarActions.Action action = host.hotbarActions().actionFor(line.charAt(col));
                HotbarButtonConfig button;
                String[] replacements;
                if (action != null) {
                    // The play button swaps to its "stop" icon while a song runs, so it comes from
                    // the pre-resolved pair rather than straight from the config key.
                    button = action == MusicEditHotbarActions.Action.PLAY
                            ? playConfig
                            : host.editorConfig().getButton(action.configKey());
                    replacements = replacementsFor(action, selectionCount, statusReplacements);
                } else if (line.charAt(col) == host.editorConfig().getEmptyChar()) {
                    button = emptyConfig;
                    replacements = null;
                } else {
                    button = null;
                    replacements = null;
                }

                if (button != null) {
                    host.paintButton(playerSlot, button, replacements);
                }
            }
        }
    }

    /** Whether any visible toolbar cell is one of the buttons that shows the editor status block. */
    private boolean needsStatus(MusicEditLayout layout) {
        return anyVisibleAction(layout, MusicEditHotbarActions.Action.PITCH_UP,
                MusicEditHotbarActions.Action.PITCH_DOWN,
                MusicEditHotbarActions.Action.TICK_LEFT,
                MusicEditHotbarActions.Action.TICK_RIGHT,
                MusicEditHotbarActions.Action.SETTINGS);
    }

    /** Whether any visible toolbar cell is a button whose tooltip shows the selection size. */
    private boolean needsSelectionCount(MusicEditLayout layout) {
        return anyVisibleAction(layout, MusicEditHotbarActions.Action.COPY,
                MusicEditHotbarActions.Action.DELETE_SELECTION,
                MusicEditHotbarActions.Action.BATCH_INSTRUMENT);
    }

    /** Shared scan for the "compute once, only if a visible button needs it" checks above. */
    private boolean anyVisibleAction(MusicEditLayout layout, MusicEditHotbarActions.Action... wanted) {
        for (int row = 6; row < layout.lineCount() && row < 10; row++) {
            String line = layout.line(row);
            for (int col = 0; col < line.length() && col < 9; col++) {
                MusicEditHotbarActions.Action action = host.hotbarActions().actionFor(line.charAt(col));
                for (MusicEditHotbarActions.Action candidate : wanted) {
                    if (action == candidate) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Placeholders for one button, or null when the button shows no state.
     *
     * Kept next to the render pass because it is a property of what the button displays, not of
     * the window; the values themselves come from the host.
     *
     * selectionCount is passed in rather than fetched here on purpose. Answering it walks
     * the selection rectangle and calls music.getNote for every cell, and each of those is a
     * synchronized map lookup keyed by a freshly built string. Called per button that is
     * O(buttons x selection area) locked lookups for one repaint; the render pass asks once and passes
     * the result in as selectionCount.
     */
    private String[] replacementsFor(MusicEditHotbarActions.Action action, int selectionCount, String[] statusReplacements) {
        return switch (action) {
            case PITCH_UP, PITCH_DOWN, TICK_LEFT, TICK_RIGHT, SETTINGS -> statusReplacements;
            case INSTRUMENT -> new String[]{"{instrument}", host.currentInstrument().getDisplayName()};
            case PLAY -> new String[]{"{notes}", String.valueOf(host.music().getNoteCount())};
            case BPM_UP, BPM_DOWN -> new String[]{"{bpm}", String.valueOf(host.music().getBpm())};
            case TIME_SIGNATURE -> new String[]{
                    "{timeSignature}", host.music().getTimeSignature().toString()};
            case UNDO -> new String[]{"{canUndo}", host.canUndo()
                    ? com.huidu.musicboxplus.common.lang.Lang.EDIT_AVAILABLE.toString()
                    : com.huidu.musicboxplus.common.lang.Lang.EDIT_UNAVAILABLE.toString()};
            case REDO -> new String[]{"{canRedo}", host.canRedo()
                    ? com.huidu.musicboxplus.common.lang.Lang.EDIT_AVAILABLE.toString()
                    : com.huidu.musicboxplus.common.lang.Lang.EDIT_UNAVAILABLE.toString()};
            case COPY, DELETE_SELECTION -> new String[]{
                    "{selectionCount}", String.valueOf(selectionCount)};
            case BATCH_INSTRUMENT -> new String[]{
                    "{selectionCount}", String.valueOf(selectionCount),
                    "{instrument}", host.currentInstrument().getDisplayName()};
            case PASTE -> new String[]{"{clipboardSize}", String.valueOf(host.clipboardSize())};
            default -> null;
        };
    }

    /** Clears the 36 slots the toolbar is allowed to overwrite. */
    void clearEditorArea() {
        Player player = host.player();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = player.getInventory().getItem(slot);
            if (!host.slotBlank(current)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

}
