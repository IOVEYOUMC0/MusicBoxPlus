package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.module.edit.PlayerMusic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

public class MusicFileImporter {

    // Hard caps bounding the memory/CPU a single import can consume; import files are untrusted.
    public static final long MAX_IMPORT_FILE_BYTES = com.huidu.musicboxplus.core.nbs.NbsReader.MAX_FILE_BYTES;
    public static final int MAX_IMPORT_NOTES = com.huidu.musicboxplus.core.nbs.NbsReader.MAX_NOTES;

    private static final MusicFileImporter INSTANCE = new MusicFileImporter();

    // MIDI files open with the ASCII chunk id "MThd"; NBS (v1+) opens with a zero short followed by
    // the format version byte. Sniffing the content lets a renamed or extension-less file reach the
    // right parser: keying on the name alone sent an NBS file named "x.mid" into the MIDI parser and
    // produced a misleading error about the wrong format.
    private static final byte[] MIDI_MAGIC = {'M', 'T', 'h', 'd'};
    private static final int NBS_MIN_VERSION = 1;
    private static final int NBS_MAX_VERSION = 6;

    public static MusicFileImporter getInstance() {
        return INSTANCE;
    }

    private MusicFileImporter() {
    }

    /** The format the content itself declares, or null when the first bytes match neither. */
    static ImportFormat sniffFormat(byte[] head) {
        if (head == null) {
            return null;
        }
        if (head.length >= MIDI_MAGIC.length) {
            boolean midi = true;
            for (int i = 0; i < MIDI_MAGIC.length; i++) {
                if (head[i] != MIDI_MAGIC[i]) {
                    midi = false;
                    break;
                }
            }
            if (midi) {
                return ImportFormat.MIDI;
            }
        }
        if (head.length >= 4 && head[0] == 0 && head[1] == 0) {
            int version = head[2] & 0xFF;
            if (version >= NBS_MIN_VERSION && version <= NBS_MAX_VERSION) {
                return ImportFormat.NBS;
            }
        }
        return null;
    }

    /**
     * Content first, file name second. The name is only a fallback for content the sniffer does not
     * recognise (a v0 NBS file, or a MIDI with a non-standard chunk layout).
     */
    public ImportFormat detectFormat(byte[] head, String fileName) throws IOException {
        ImportFormat sniffed = sniffFormat(head);
        if (sniffed != null) {
            return sniffed;
        }
        return detectFormat(fileName);
    }

    public ImportResult importByName(String requestedName, String author, UUID authorUUID) throws IOException {
        File file = resolveImportFile(requestedName);
        if (file == null || !file.exists()) {
            throw new IOException("Import file not found");
        }
        return importFromFile(file, author, authorUUID);
    }

    public ImportResult importFromFile(File file, String author, UUID authorUUID) throws IOException {
        if (file.length() > MAX_IMPORT_FILE_BYTES) {
            throw new IOException("Import file too large (max " + (MAX_IMPORT_FILE_BYTES / (1024 * 1024)) + " MB)");
        }
        byte[] head;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            head = in.readNBytes(8);
        }
        ImportFormat format = detectFormat(head, file.getName());
        return switch (format) {
            case NBS -> new ImportResult(NBSImporter.getInstance().importFromFile(file, author, authorUUID), format, java.util.List.of());
            case MIDI -> {
                MidiImporter.ImportResult result = MidiImporter.getInstance().importFromFile(file, author, authorUUID);
                yield new ImportResult(result.music(), format, result.warnings());
            }
        };
    }

    public ImportResult importFromBytes(String fileName, byte[] bytes, String author, UUID authorUUID) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Import file is empty");
        }
        if (bytes.length > MAX_IMPORT_FILE_BYTES) {
            throw new IOException("Import file too large (max " + (MAX_IMPORT_FILE_BYTES / (1024 * 1024)) + " MB)");
        }
        ImportFormat format = detectFormat(bytes, fileName);
        return switch (format) {
            case NBS -> {
                yield new ImportResult(NBSImporter.getInstance().importFromBytes(bytes, fileName, author, authorUUID), format, java.util.List.of());
            }
            case MIDI -> {
                // The suffix follows the detected format, not the file name: a MIDI file called
                // "song.nbs" still needs the MIDI temp suffix for the parser's diagnostics.
                File tempFile = File.createTempFile("musicboxplus-midi-import-", ".mid");
                try {
                    java.nio.file.Files.write(tempFile.toPath(), bytes);
                    MidiImporter.ImportResult result = MidiImporter.getInstance().importFromFile(tempFile, author, authorUUID);
                    yield new ImportResult(result.music(), format, result.warnings());
                } finally {
                    try {
                        java.nio.file.Files.deleteIfExists(tempFile.toPath());
                    } catch (IOException ignored) {
                        tempFile.deleteOnExit();
                    }
                }
            }
        };
    }

    public File resolveImportFile(String requestedName) throws IOException {
        if (requestedName == null || requestedName.trim().isEmpty()) {
            return null;
        }

        String trimmedName = requestedName.trim();
        File dataFolder = MusicBox.getInstance().getDataFolder();
        Path basePath = dataFolder.toPath().toAbsolutePath().toRealPath();

        if (trimmedName.contains(".")) {
            Path candidate = basePath.resolve(trimmedName).normalize();
            if (java.nio.file.Files.exists(candidate)) {
                candidate = candidate.toRealPath();
            }
            if (!candidate.startsWith(basePath)) {
                throw new IOException("Import path must stay inside the plugin folder");
            }
            return candidate.toFile();
        }

        for (String extension : new String[]{".nbs", ".mid", ".midi"}) {
            Path candidate = basePath.resolve(trimmedName + extension).normalize();
            if (!candidate.startsWith(basePath)) {
                continue;
            }
            if (java.nio.file.Files.exists(candidate)) {
                candidate = candidate.toRealPath();
                if (!candidate.startsWith(basePath)) {
                    continue;
                }
            }
            if (candidate.toFile().exists()) {
                return candidate.toFile();
            }
        }

        Path fallback = basePath.resolve(trimmedName + ".nbs").normalize();
        if (!fallback.startsWith(basePath)) {
            throw new IOException("Import path must stay inside the plugin folder");
        }
        return fallback.toFile();
    }

    public ImportFormat detectFormat(String fileName) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".nbs")) {
            return ImportFormat.NBS;
        }
        if (lower.endsWith(".mid") || lower.endsWith(".midi")) {
            return ImportFormat.MIDI;
        }
        throw new IOException("Unsupported import format");
    }

    public enum ImportFormat {
        NBS("NBS"),
        MIDI("MIDI");

        private final String displayName;

        ImportFormat(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public record ImportResult(PlayerMusic music, ImportFormat format, java.util.List<String> warnings) {
    }
}
