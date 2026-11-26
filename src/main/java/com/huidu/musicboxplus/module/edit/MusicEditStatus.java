package com.huidu.musicboxplus.module.edit;

/**
 * The placeholder values the editor writes into its own button lore.
 *
 * Every toolbar button that shows state ("BPM: 120", "Bars 1-8") is rendered from one flat
 * key, value, key, value array handed to the item builder. Building that array inline in the
 * render loop meant the formatting rules -- and the off-by-one that decides whether the last visible
 * bar is included -- could only be checked by opening the editor and reading the tooltip.
 *
 * Pure arithmetic and string formatting; no Bukkit types, so it is unit tested directly.
 */
final class MusicEditStatus {

    private MusicEditStatus() {
    }

    /**
     * Pitch range as shown on the toolbar, inclusive of both ends and clamped to what exists.
     * pitchOffset is the lowest visible pitch, rows how many pitch rows are visible, and maxPitch the
     * highest reachable pitch.
     */
    static String pitchRange(int pitchOffset, int rows, int maxPitch) {
        int start = Math.max(0, pitchOffset);
        int end = Math.min(maxPitch, start + Math.max(0, rows - 1));
        if (end < start) {
            // The window sits entirely above the range (possible after a config change shrinks
            // maxPitch); showing "C5 - C4" would be nonsense, so collapse to the single pitch.
            end = start;
        }
        return MusicNote.getNoteName(start) + " - " + MusicNote.getNoteName(end);
    }

    /**
     * Tick range for the current page, inclusive of both ends.
     *
     * The old code computed startTick + columns - 1; keeping the -1 matters, since
     * a page of 7 columns starting at 0 covers ticks 0..6 and claiming 0..7 would tell the player
     * there is a bar that is not on screen.
     */
    static String tickRange(int tickOffset, int columns) {
        int width = Math.max(1, columns);
        int start = Math.max(0, tickOffset) * width;
        int end = start + width - 1;
        return start + " - " + end;
    }

    /**
     * The full placeholder set for the status-bearing buttons: offsets, both ranges, tempo and size.
     *
     * Order is the contract with the item builder, which scans pairs; the tests pin it because a
     * dropped key silently leaves a raw "{bpm}" in the tooltip.
     */
    static String[] replacements(int pitchOffset, int rows, int maxPitch,
                                 int tickOffset, int columns,
                                 int bpm, String timeSignature, int subdivision, int notes) {
        return new String[]{
                "{pitchOffset}", String.valueOf(pitchOffset),
                "{pitchRange}", pitchRange(pitchOffset, rows, maxPitch),
                "{tickOffset}", String.valueOf(tickOffset),
                "{tickRange}", tickRange(tickOffset, columns),
                "{bpm}", String.valueOf(bpm),
                "{timeSignature}", timeSignature,
                "{subdivision}", String.valueOf(subdivision),
                "{notes}", String.valueOf(notes)
        };
    }
}
