package com.huidu.musicboxplus.core.nbs;

import java.util.List;

// Raw contents of a .nbs file: fields map one-to-one onto the file, with no interpretation or
// remapping.
//
// Three deliberate constraints:
//  1. Every integer field is an int. Most 16-bit NBS fields are unsigned, so a short would
//     silently go negative on long songs (tick > 32767) and on high index values.
//  2. vanillaInstrumentCount keeps whatever the file says and is never compared against the
//     running server's instrument count. Remapping instrument ids is playback-time adaptation and
//     belongs in the core/sound layer.
//  3. This package does not import org.bukkit -- parsing .nbs is pure I/O and arithmetic and must
//     not depend on a running server. It also keeps the parser fully unit-testable.
public record RawNbsSong(
        int version,
        int vanillaInstrumentCount,
        int lengthTicks,
        int songHeight,
        String title,
        String author,
        String originalAuthor,
        String description,
        int tempoRaw,
        int timeSignature,
        boolean loopEnabled,
        int maxLoopCount,
        int loopStartTick,
        List<RawNbsNote> notes,
        List<RawNbsLayer> layers,
        List<RawNbsCustomInstrument> customInstruments) {

    // Ticks per second. The file stores it as an integer scaled by 100.
    public float ticksPerSecond() {
        return tempoRaw / 100f;
    }

    // Copy with the author and original author stripped. Used when the plugin converts a song
    // into a .nbs of its own, so the resulting file carries no external creator credit.
    public RawNbsSong withoutCredit() {
        return new RawNbsSong(version, vanillaInstrumentCount, lengthTicks, songHeight, title,
                "", "", description, tempoRaw, timeSignature, loopEnabled, maxLoopCount,
                loopStartTick, notes, layers, customInstruments);
    }
}
