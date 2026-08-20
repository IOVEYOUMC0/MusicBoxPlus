package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.nbs.NbsWriter;
import com.huidu.musicboxplus.module.edit.PlayerMusic;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

// Writes player-made music out as a real .nbs file, so it can be opened in Note Block Studio or
// dropped back into the songs folder.
public class NBSExporter {

    private static final NBSExporter INSTANCE = new NBSExporter();

    public static NBSExporter getInstance() {
        return INSTANCE;
    }

    private NBSExporter() {
    }

    public ExportResult export(PlayerMusic music) throws IOException {
        File exportDir = new File(MusicBox.getInstance().getDataFolder(), "exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("Failed to create export directory");
        }

        String fileName = sanitizeFileName(music.getName());
        if (fileName.isBlank()) {
            fileName = "musicbox_export";
        }

        File target = new File(exportDir, fileName + ".nbs");
        return export(music, target);
    }

    public ExportResult export(PlayerMusic music, File target) throws IOException {
        NoteBlockSongConverter.ConversionResult conversion = NoteBlockSongConverter.fromPlayerMusic(music);
        NbsWriter.write(conversion.song(), target.toPath());
        return new ExportResult(target, List.copyOf(new LinkedHashSet<>(conversion.warnings())));
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\/:*?\"<>|]", "_").trim();
    }

    public record ExportResult(File file, List<String> warnings) {
    }
}
