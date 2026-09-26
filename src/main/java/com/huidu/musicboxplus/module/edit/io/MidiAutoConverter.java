package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.NbsWriter;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;

import java.io.File;
import java.util.Locale;

// Converts .mid/.midi files in the song library into .nbs written next to the source, so the
// normal song loader picks them up as first-class songs. Each written file is re-parsed as a
// self-check and deleted if it does not read back, to keep a corrupt .nbs out of the library.
public final class MidiAutoConverter {

    private MidiAutoConverter() {
    }

    // Returns the number of .nbs files written.
    public static int convertFolder(File rootFolder) {
        if (rootFolder == null || !rootFolder.isDirectory()) {
            return 0;
        }
        int[] count = {0};
        convertRecursive(rootFolder, count);
        return count[0];
    }

    private static void convertRecursive(File dir, int[] count) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File file : entries) {
            if (file.isDirectory()) {
                convertRecursive(file, count);
                continue;
            }
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".mid") && !lower.endsWith(".midi")) {
                continue;
            }
            File target = new File(file.getParentFile(), stripExtension(file.getName()) + ".nbs");
            if (target.exists() && target.lastModified() >= file.lastModified()) {
                continue;
            }
            try {
                RawNbsSong song = MidiImporter.getInstance().parseToSong(file);
                // Conversion output is uncredited: strip the source author and original author
                // so the generated .nbs does not carry an external creator signature.
                NbsWriter.write(song.withoutCredit(), target.toPath());
                RawNbsSong verify = NbsReader.read(target.toPath());
                if (verify.notes().isEmpty()) {
                    target.delete();
                    MusicBox.getInstance().getLogger().warning("MIDI->NBS verification failed, skipped: " + file.getName());
                    continue;
                }
                count[0]++;
                MusicBox.getInstance().getLogger().info("Converted MIDI to NBS: " + file.getName() + " -> " + target.getName());
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().warning("Failed to convert MIDI '" + file.getName() + "': " + e.getMessage());
            }
        }
    }

    private static String stripExtension(String fileName) {
        return fileName.replaceAll("\\.[^.]+$", "");
    }
}
