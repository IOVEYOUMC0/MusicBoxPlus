package com.huidu.musicboxplus.core.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.RawNbsCustomInstrument;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SongInstrumentsTest {

    private static final Path CORPUS = Path.of("Reference", "boombox", "decompiled", "resources", "songs");

    private static RawNbsCustomInstrument custom(String soundFile) {
        return new RawNbsCustomInstrument("n", soundFile, 45, false);
    }

    @Test
    void vanillaIdsMapToNoteBlockSounds() {
        SongInstruments instruments = SongInstruments.of(16, List.of());
        assertEquals("minecraft:block.note_block.harp", instruments.baseSoundName(0));
        assertEquals("minecraft:block.note_block.pling", instruments.baseSoundName(15));
        assertFalse(instruments.isCustom(15));
    }

    // The split point is the count declared in the file header, regardless of how many vanilla
    // instruments the server actually has.
    @Test
    void customInstrumentsStartAtTheCountDeclaredByTheFile() {
        SongInstruments instruments = SongInstruments.of(10, List.of(custom("mine.ogg")));
        assertFalse(instruments.isCustom(9));
        assertTrue(instruments.isCustom(10));
        assertEquals("mine", instruments.baseSoundName(10));
    }

    // The extension has to be stripped as a literal suffix. With replaceAll(".ogg", "") the dot is
    // a regex wildcard, so the "logg" inside "logg.ogg" is eaten too and the sound name ends up empty.
    @Test
    void oggSuffixIsStrippedLiterally() {
        assertEquals("logg", SongInstruments.of(0, List.of(custom("logg.ogg"))).baseSoundName(0));
        assertEquals("a/b/drum", SongInstruments.of(0, List.of(custom("a/b/drum.OGG"))).baseSoundName(0));
        assertEquals("ogg", SongInstruments.of(0, List.of(custom("ogg"))).baseSoundName(0));
    }

    // A custom instrument pointing straight at the vanilla pling sample resolves to the vanilla
    // sound, so it plays without a resource pack.
    @Test
    void plingFallsBackToTheVanillaSound() {
        assertEquals("minecraft:block.note_block.pling",
                SongInstruments.of(0, List.of(custom("pling.ogg"))).baseSoundName(0));
        assertEquals("minecraft:block.note_block.pling",
                SongInstruments.of(0, List.of(custom("block.note_block.pling"))).baseSoundName(0));
    }

    @Test
    void everyBucketHasItsSuffix() {
        SongInstruments instruments = SongInstruments.of(1, List.of());
        assertEquals("minecraft:block.note_block.harp_-2", instruments.soundName(0, 0));
        assertEquals("minecraft:block.note_block.harp_-1", instruments.soundName(0, 1));
        assertEquals("minecraft:block.note_block.harp", instruments.soundName(0, 2));
        assertEquals("minecraft:block.note_block.harp_1", instruments.soundName(0, 3));
        assertEquals("minecraft:block.note_block.harp_2", instruments.soundName(0, 4));
    }

    // An out-of-range id from a corrupt file must not bring the whole song to a halt.
    @Test
    void outOfRangeInstrumentFallsBackInsteadOfThrowing() {
        SongInstruments instruments = SongInstruments.of(16, List.of());
        assertEquals("minecraft:block.note_block.harp", instruments.baseSoundName(999));
        assertEquals("minecraft:block.note_block.harp", instruments.baseSoundName(-1));
    }

    // Like core.nbs, this layer is pure strings and arithmetic and must stay free of server
    // dependencies: a sound name is a string and a pitch is a float, neither needs a running
    // server. Pulling in org.bukkit would reduce both to something only verifiable by ear in-game.
    @Test
    void soundPackageHasNoBukkitDependency() throws Exception {
        Path dir = Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "core", "sound");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).toList()) {
                String code = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                if (code.contains("org.bukkit")) {
                    offenders.add(p.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "core.sound 必须保持零 org.bukkit 依赖");
    }

    // A custom instrument with an empty sound file is silent. The corpus contains such entries:
    // the "Tempo Changer" in Tokyo_Teddy_Bear.nbs and the placeholder instrument in Monster.nbs.
    @Test
    void emptySoundFileMeansSilent() {
        SongInstruments instruments = SongInstruments.of(16, List.of(custom("")));
        assertTrue(instruments.isSilent(16));
        assertFalse(instruments.isSilent(0));
    }

    // Every note in the corpus must resolve to either a non-empty sound name or a silent instrument.
    @Test
    void everyCorpusNoteResolvesToASoundName() throws Exception {
        assertTrue(Files.isDirectory(CORPUS), "缺少语料目录 " + CORPUS);
        List<String> failures = new ArrayList<>();
        int noteCount = 0;

        List<Path> files;
        try (Stream<Path> stream = Files.list(CORPUS)) {
            files = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().toList();
        }

        for (Path file : files) {
            RawNbsSong song = NbsReader.read(file);
            SongInstruments instruments = SongInstruments.of(song);
            for (RawNbsNote note : song.notes()) {
                noteCount++;
                int bucket = NotePitch.bucketIndex(note.key(), note.finePitch());
                String name = instruments.soundName(note.instrument(), bucket);
                assertNotNull(name);
                if (name.isEmpty() && !instruments.isSilent(note.instrument())) {
                    failures.add(file.getFileName() + " instrument=" + note.instrument()
                            + " 音效名为空却未被判为静音");
                }
            }
        }

        assertTrue(noteCount > 0, "语料里没有音符");
        assertEquals(List.of(), failures);
    }
}
