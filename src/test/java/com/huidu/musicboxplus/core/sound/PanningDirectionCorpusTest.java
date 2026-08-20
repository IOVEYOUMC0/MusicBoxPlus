package com.huidu.musicboxplus.core.sound;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.RawNbsLayer;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

// Which end of the 0..200 panning range is the left one, decided by evidence rather than
// by a comment.
//
// The spec says 0 is left, but NoteBlockAPI's decoder carries a comment claiming the
// opposite, and that comment was copied into this codebase once already. A statement in
// prose is not enough to settle it, because getting it wrong produces no error -- both
// channels simply swap, and only a listener wearing headphones would notice.
//
// Tokyo_Teddy_Bear.nbs settles it: its author named layers by channel. Every layer whose
// name starts with L must sit on the low side of center, every layer starting with R on
// the high side. If someone flips the direction, this fails.
class PanningDirectionCorpusTest {

    private static final Path SONG = Path.of("Reference", "boombox", "decompiled",
            "resources", "songs", "Tokyo_Teddy_Bear.nbs");

    private static boolean startsWithChannel(String name, char channel) {
        String trimmed = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        return trimmed.length() >= 2
                && trimmed.charAt(0) == channel
                && !Character.isLetterOrDigit(trimmed.charAt(1));
    }

    @Test
    void layersNamedByChannelAgreeThatZeroIsLeft() throws Exception {
        assertTrue(Files.exists(SONG), "missing corpus file " + SONG);
        RawNbsSong song = NbsReader.read(SONG);

        List<String> wrong = new ArrayList<>();
        int left = 0;
        int right = 0;
        for (RawNbsLayer layer : song.layers()) {
            boolean isLeft = startsWithChannel(layer.name(), 'L');
            boolean isRight = startsWithChannel(layer.name(), 'R');
            if (!isLeft && !isRight) {
                continue;
            }
            float offset = StereoPan.leftOffset(layer.panning(), StereoPan.CENTER);
            if (isLeft) {
                left++;
                if (offset <= 0) {
                    wrong.add("layer '" + layer.name() + "' panning=" + layer.panning()
                            + " should offset left but got " + offset);
                }
            } else {
                right++;
                if (offset >= 0) {
                    wrong.add("layer '" + layer.name() + "' panning=" + layer.panning()
                            + " should offset right but got " + offset);
                }
            }
        }

        assertTrue(left >= 5 && right >= 5,
                "expected the song to name layers by channel, found L=" + left + " R=" + right);
        assertTrue(wrong.isEmpty(),
                "panning direction is reversed (" + wrong.size() + " layers):\n  "
                        + String.join("\n  ", wrong));
    }
}
