package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.nbs.NbsWriter;
import com.huidu.musicboxplus.module.edit.PlayerMusic;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
        return new ExportResult(target, warningsFor(music, conversion));
    }

    // Warns when the song's name cannot be carried by the file's title field.
    //
    // A .nbs header string is one byte per character (Note Block Studio, NoteBlockAPI and this
    // plugin's reader all treat it that way), so a Chinese -- or any non-Latin-1 -- name cannot be
    // stored in it. NbsWriter now keeps the low byte of each character rather than throwing the name
    // away as '?', but no NBS tool will display it as the name the player typed. The file NAME on
    // disk keeps the name intact, and dropping the export back into the songs folder picks the name
    // up from there, so the warning says where the real name lives instead of implying data loss.
    private List<String> warningsFor(PlayerMusic music, NoteBlockSongConverter.ConversionResult conversion) {
        List<String> warnings = new java.util.ArrayList<>(conversion.warnings());
        String name = music.getName();
        if (name != null && !StringUtils.fitsSingleByteString(name)) {
            String message = LogLocale.text(MusicBox.getInstance(),
                    "This song's name contains characters the .nbs format cannot store (it holds one "
                            + "byte per character), so the title inside the file will not read back as "
                            + "the name you typed. The file name on disk is kept intact: '"
                            + targetName(name) + "'.",
                    "这首歌的名字含有 .nbs 格式无法保存的字符（该格式一个字符只占一个字节），文件内的标题"
                            + "不会显示为你输入的名字；磁盘上的文件名会完整保留：'" + targetName(name) + "'。");
            warnings.add(message);
        }
        return warnings;
    }

    private String targetName(String name) {
        String fileName = sanitizeFileName(name);
        return (fileName.isBlank() ? "musicbox_export" : fileName) + ".nbs";
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\/:*?\"<>|]", "_").trim();
    }

    public record ExportResult(File file, List<String> warnings) {
    }
}
