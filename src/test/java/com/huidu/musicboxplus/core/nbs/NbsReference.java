package com.huidu.musicboxplus.core.nbs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Cross-check baseline produced by a separate reader written against the published openNBS
// spec (scratchpad/nbsread.py). Two implementations agreeing field by field is what shows
// the spec was read correctly, rather than one side's bug becoming the standard.
//
// The fingerprint covers strings and layers, not just the scalars and the notes. It did
// not always: while only version/height/counts and the note tuples were compared, both a
// wrong string charset and a misread layer field passed unnoticed, in the same way the
// version-gated song length field had earlier. A field nobody asserts on is a field
// neither implementation is protected on.
//
// Strings are hashed as code point sequences so the comparison says nothing about which
// charset either side writes to disk.
final class NbsReference {

    record Expected(int version, int vanillaCount, int lengthTicks, int songHeight,
                    int noteCount, int customCount, String notesHash, String stringsHash,
                    String layersHash, int tempoRaw, int layerCount) {
    }

    private NbsReference() {
    }

    // A null value marks a file the reference reader itself could not parse.
    static Map<String, Expected> load(Path reference) throws IOException {
        Map<String, Expected> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(reference, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\|");
            if (p.length < 12) {
                map.put(p[0], null);
                continue;
            }
            map.put(p[0], new Expected(
                    Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]),
                    p[7], p[8], p[9], Integer.parseInt(p[10]), Integer.parseInt(p[11])));
        }
        return map;
    }

    static List<String> compare(String name, Expected want, RawNbsSong song) {
        List<String> out = new ArrayList<>();
        check(out, name, "version", want.version(), song.version());
        check(out, name, "vanillaCount", want.vanillaCount(), song.vanillaInstrumentCount());
        check(out, name, "lengthTicks", want.lengthTicks(), song.lengthTicks());
        check(out, name, "songHeight", want.songHeight(), song.songHeight());
        check(out, name, "noteCount", want.noteCount(), song.notes().size());
        check(out, name, "customInstrCount", want.customCount(), song.customInstruments().size());
        check(out, name, "tempoRaw", want.tempoRaw(), song.tempoRaw());
        check(out, name, "layerCount", want.layerCount(), song.layers().size());
        check(out, name, "notesHash", want.notesHash(), notesHash(song));
        check(out, name, "stringsHash", want.stringsHash(), stringsHash(song));
        check(out, name, "layersHash", want.layersHash(), layersHash(song));
        return out;
    }

    static String notesHash(RawNbsSong song) {
        List<RawNbsNote> sorted = new ArrayList<>(song.notes());
        sorted.sort(Comparator.comparingInt(RawNbsNote::tick)
                .thenComparingInt(RawNbsNote::layer)
                .thenComparingInt(RawNbsNote::instrument)
                .thenComparingInt(RawNbsNote::key)
                .thenComparingInt(RawNbsNote::velocity)
                .thenComparingInt(RawNbsNote::panning)
                .thenComparingInt(RawNbsNote::finePitch));
        StringBuilder sb = new StringBuilder();
        for (RawNbsNote n : sorted) {
            sb.append(n.tick()).append(',').append(n.layer()).append(',')
                    .append(n.instrument()).append(',').append(n.key()).append(',')
                    .append(n.velocity()).append(',').append(n.panning()).append(',')
                    .append(n.finePitch()).append(';');
        }
        return sha16(sb.toString());
    }

    static String stringsHash(RawNbsSong song) {
        StringBuilder sb = new StringBuilder();
        for (String s : List.of(song.title(), song.author(),
                song.originalAuthor(), song.description())) {
            appendCodePoints(sb, s);
            sb.append(';');
        }
        for (RawNbsCustomInstrument ci : song.customInstruments()) {
            appendCodePoints(sb, ci.name());
            sb.append('|');
            appendCodePoints(sb, ci.soundFile());
            sb.append('|').append(ci.pitch()).append(',')
                    .append(ci.pressKey() ? 1 : 0).append(';');
        }
        return sha16(sb.toString());
    }

    static String layersHash(RawNbsSong song) {
        StringBuilder sb = new StringBuilder();
        for (RawNbsLayer layer : song.layers()) {
            appendCodePoints(sb, layer.name());
            sb.append('|').append(layer.locked() ? 1 : 0).append(',')
                    .append(layer.volume()).append(',').append(layer.panning()).append(';');
        }
        return sha16(sb.toString());
    }

    private static void appendCodePoints(StringBuilder sb, String value) {
        String s = value == null ? "" : value;
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append((int) s.charAt(i));
        }
    }

    private static String sha16(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(List<String> out, String file, String field, int want, int got) {
        if (want != got) {
            out.add(file + ": " + field + " expected " + want + " got " + got);
        }
    }

    private static void check(List<String> out, String file, String field, String want, String got) {
        if (!want.equals(got)) {
            out.add(file + ": " + field + " expected " + want + " got " + got);
        }
    }
}
