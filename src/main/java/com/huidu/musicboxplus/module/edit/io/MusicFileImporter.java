package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.module.edit.PlayerMusic;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

public class MusicFileImporter {

    // Hard caps bounding the memory/CPU a single import can consume; import files are untrusted.
    public static final long MAX_IMPORT_FILE_BYTES = 5L * 1024 * 1024; // 5 MB
    public static final int MAX_IMPORT_NOTES = 100_000;

    private static final MusicFileImporter INSTANCE = new MusicFileImporter();

    public static MusicFileImporter getInstance() {
        return INSTANCE;
    }

    private MusicFileImporter() {
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
        ImportFormat format = detectFormat(file.getName());
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
        ImportFormat format = detectFormat(fileName);
        return switch (format) {
            case NBS -> {
                try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                    yield new ImportResult(NBSImporter.getInstance().importFromStream(input, fileName, author, authorUUID), format, java.util.List.of());
                }
            }
            case MIDI -> {
                File tempFile = File.createTempFile("musicboxplus-midi-import-", getTempSuffix(fileName));
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

    private String getTempSuffix(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".midi")) {
            return ".midi";
        }
        if (lower.endsWith(".mid")) {
            return ".mid";
        }
        return ".tmp";
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
