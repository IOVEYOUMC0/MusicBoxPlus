package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.common.utils.DebugLogger;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.NotePitchMapper;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import org.bukkit.entity.Player;

import javax.sound.midi.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class MidiImporter {

    private static final MidiImporter INSTANCE = new MidiImporter();
    private static final int DEFAULT_MICROSECONDS_PER_QUARTER = 500_000;
    private static final int[] SUBDIVISION_CANDIDATES = new int[]{16, 12, 8, 6, 4, 3, 2, 1};

    public static MidiImporter getInstance() {
        return INSTANCE;
    }

    private MidiImporter() {
    }

    public ImportResult importFromFile(File file, Player player) throws IOException {
        return importFromFile(file, player.getName(), player.getUniqueId());
    }

    public ImportResult importFromFile(File file, String author, UUID authorUUID) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(file);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Failed to parse MIDI file", e);
        }

        if (sequence == null) {
            throw new IOException("Failed to parse MIDI file");
        }
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IOException("Unsupported MIDI division type");
        }

        return convertToPlayerMusic(sequence, author, authorUUID, file.getName());
    }

    // Parses a MIDI file into a raw NBS song without touching the player-music database, unlike
    // importFromFile. Used by the server-side MIDI to NBS auto-conversion.
    public RawNbsSong parseToSong(File file) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(file);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Failed to parse MIDI file", e);
        }
        if (sequence == null) {
            throw new IOException("Failed to parse MIDI file");
        }
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IOException("Unsupported MIDI division type");
        }
        PlayerMusic music = buildPlayerMusic(sequence, "MusicBox", new UUID(0L, 0L), file.getName(), new ArrayList<>());
        return NoteBlockSongConverter.fromPlayerMusic(music).song();
    }

    private ImportResult convertToPlayerMusic(Sequence sequence, String author, UUID authorUUID, String fileName) throws IOException {
        List<String> warnings = new ArrayList<>();
        PlayerMusic music = buildPlayerMusic(sequence, author, authorUUID, fileName, warnings);

        if (!PlayerMusicManager.getInstance().saveMusicSync(music)) {
            throw new IllegalStateException("Failed to save imported music");
        }

        NoteBlockSongConverter.ConversionResult conversionResult = NoteBlockSongConverter.fromPlayerMusic(music);
        warnings.addAll(conversionResult.warnings());

        DebugLogger.debug("MIDI import completed: " + music.getName() + ", notes: " + music.getNoteCount());
        return new ImportResult(music, conversionResult.song(), warnings);
    }

    private PlayerMusic buildPlayerMusic(Sequence sequence, String author, UUID authorUUID, String fileName, List<String> warnings) throws IOException {
        String name = fileName.replaceAll("\\.[^.]+$", "");
        PlayerMusic music = new PlayerMusic(name, author, authorUUID);

        int resolution = Math.max(1, sequence.getResolution());
        List<TimedShortMessage> events = collectSortedEvents(sequence);
        int beatSubdivision = chooseBeatSubdivision(resolution, events);
        int bpm = convertTempoToBpm(findInitialTempo(sequence));
        PlayerMusic.TimeSignature timeSignature = findInitialTimeSignature(sequence);
        if (countTempoChanges(sequence) > 1) {
            warnings.add("tempo_changes_collapsed");
        }
        music.setBeatSubdivision(beatSubdivision);
        music.setBpm(bpm);
        music.setTimeSignature(timeSignature);
        Map<Integer, Integer> channelPrograms = new HashMap<>();

        for (TimedShortMessage event : events) {
            ShortMessage message = event.message();
            int command = message.getCommand();
            int channel = message.getChannel();

            if (command == ShortMessage.PROGRAM_CHANGE) {
                channelPrograms.put(channel, message.getData1());
                continue;
            }

            if (command != ShortMessage.NOTE_ON || message.getData2() <= 0) {
                continue;
            }

            int pitch = convertMidiKeyToPitch(message.getData1());
            int tick = convertMidiTickToEditorTick(event.tick(), resolution, beatSubdivision);
            MusicNote.NoteInstrument instrument = mapMidiInstrument(channel, channelPrograms.getOrDefault(channel, 0), message.getData1());

            MusicNote existingNote = music.getNote(pitch, tick);
            if (existingNote == null) {
                MusicNote note = new MusicNote(pitch, tick);
                note.addInstrument(instrument);
                music.addNote(note);
            } else if (!existingNote.getInstruments().contains(instrument)) {
                existingNote.addInstrument(instrument);
            }
        }

        if (music.getNoteCount() > MusicFileImporter.MAX_IMPORT_NOTES) {
            throw new IOException("Imported file has too many notes ("
                    + music.getNoteCount() + " > " + MusicFileImporter.MAX_IMPORT_NOTES + ")");
        }
        DebugLogger.debug("MIDI parsed: " + name + ", notes: " + music.getNoteCount() + ", BPM: " + bpm + ", subdivision: " + beatSubdivision + ", signature: " + timeSignature);
        return music;
    }

    private List<TimedShortMessage> collectSortedEvents(Sequence sequence) {
        List<TimedShortMessage> events = new ArrayList<>();
        long order = 0;

        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage) {
                    events.add(new TimedShortMessage(event.getTick(), order++, shortMessage));
                }
            }
        }

        events.sort(Comparator.comparingLong(TimedShortMessage::tick).thenComparingLong(TimedShortMessage::order));
        return events;
    }

    private int findInitialTempo(Sequence sequence) {
        long earliestTick = Long.MAX_VALUE;
        int tempo = DEFAULT_MICROSECONDS_PER_QUARTER;

        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (!(message instanceof MetaMessage metaMessage) || metaMessage.getType() != 0x51) {
                    continue;
                }
                byte[] data = metaMessage.getData();
                if (data == null || data.length < 3) {
                    continue;
                }
                if (event.getTick() < earliestTick) {
                    earliestTick = event.getTick();
                    tempo = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                }
            }
        }

        return tempo > 0 ? tempo : DEFAULT_MICROSECONDS_PER_QUARTER;
    }

    private int convertTempoToBpm(int microsecondsPerQuarter) {
        int bpm = Math.round(60_000_000f / Math.max(1, microsecondsPerQuarter));
        return Math.max(20, Math.min(500, bpm));
    }

    private int countTempoChanges(Sequence sequence) {
        int count = 0;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof MetaMessage metaMessage && metaMessage.getType() == 0x51) {
                    count++;
                }
            }
        }
        return count;
    }

    private PlayerMusic.TimeSignature findInitialTimeSignature(Sequence sequence) {
        long earliestTick = Long.MAX_VALUE;
        PlayerMusic.TimeSignature signature = PlayerMusic.TimeSignature.FOUR_FOUR;

        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (!(message instanceof MetaMessage metaMessage) || metaMessage.getType() != 0x58) {
                    continue;
                }
                byte[] data = metaMessage.getData();
                if (data == null || data.length < 2) {
                    continue;
                }
                int numerator = data[0] & 0xFF;
                int denominator = 1 << (data[1] & 0xFF);
                if (event.getTick() < earliestTick) {
                    earliestTick = event.getTick();
                    signature = mapTimeSignature(numerator, denominator);
                }
            }
        }

        return signature;
    }

    private PlayerMusic.TimeSignature mapTimeSignature(int numerator, int denominator) {
        return switch (numerator + "/" + denominator) {
            case "1/4" -> PlayerMusic.TimeSignature.ONE_FOUR;
            case "2/4" -> PlayerMusic.TimeSignature.TWO_FOUR;
            case "3/4" -> PlayerMusic.TimeSignature.THREE_FOUR;
            case "5/4" -> PlayerMusic.TimeSignature.FIVE_FOUR;
            case "7/4" -> PlayerMusic.TimeSignature.SEVEN_FOUR;
            case "3/8" -> PlayerMusic.TimeSignature.THREE_EIGHT;
            case "6/8" -> PlayerMusic.TimeSignature.SIX_EIGHT;
            case "9/8" -> PlayerMusic.TimeSignature.NINE_EIGHT;
            case "12/8" -> PlayerMusic.TimeSignature.TWELVE_EIGHT;
            default -> PlayerMusic.TimeSignature.FOUR_FOUR;
        };
    }

    private int chooseBeatSubdivision(int resolution, List<TimedShortMessage> events) {
        long gcd = 0;
        long previousNoteTick = -1;

        for (TimedShortMessage event : events) {
            ShortMessage message = event.message();
            if (message.getCommand() != ShortMessage.NOTE_ON || message.getData2() <= 0) {
                continue;
            }

            long tick = event.tick();
            if (tick > 0) {
                gcd = gcd(gcd, tick);
            }
            if (previousNoteTick >= 0) {
                long delta = tick - previousNoteTick;
                if (delta > 0) {
                    gcd = gcd(gcd, delta);
                }
            }
            previousNoteTick = tick;
        }

        if (gcd > 0 && resolution % gcd == 0) {
            int exactSubdivision = (int) (resolution / gcd);
            for (int candidate : SUBDIVISION_CANDIDATES) {
                if (candidate == exactSubdivision) {
                    return candidate;
                }
            }
        }

        int bestCandidate = 4;
        long bestDistance = Long.MAX_VALUE;
        for (int candidate : SUBDIVISION_CANDIDATES) {
            if (resolution % candidate != 0) {
                continue;
            }
            long step = resolution / candidate;
            long distance = gcd > 0 ? Math.abs(step - gcd) : step;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private int convertMidiTickToEditorTick(long midiTick, int resolution, int beatSubdivision) {
        double editorTick = midiTick * (double) beatSubdivision / resolution;
        return Math.max(0, (int) Math.round(editorTick));
    }

    private int convertMidiKeyToPitch(int midiKey) {
        return NotePitchMapper.midiKeyToEditorPitch(midiKey);
    }

    private MusicNote.NoteInstrument mapMidiInstrument(int channel, int program, int midiKey) {
        if (channel == 9) {
            return mapPercussionInstrument(midiKey);
        }
        int family = program / 8;
        boolean trumpetEnabled = MusicNote.NoteInstrument.isTrumpetEnabled();
        return switch (family) {
            case 1, 9 -> MusicNote.NoteInstrument.BELL;
            case 2, 15 -> MusicNote.NoteInstrument.PLING;
            case 3 -> MusicNote.NoteInstrument.GUITAR;
            case 4 -> MusicNote.NoteInstrument.BASS;
            case 5, 13 -> MusicNote.NoteInstrument.CHIME;
            case 6, 12 -> MusicNote.NoteInstrument.FLUTE;
            case 7 -> trumpetEnabled ? MusicNote.NoteInstrument.TRUMPET : MusicNote.NoteInstrument.DIDGERIDOO;
            case 8, 14 -> MusicNote.NoteInstrument.BIT;
            case 10 -> MusicNote.NoteInstrument.BANJO;
            case 11 -> MusicNote.NoteInstrument.XYLOPHONE;
            default -> MusicNote.NoteInstrument.HARP;
        };
    }

    private MusicNote.NoteInstrument mapPercussionInstrument(int midiKey) {
        return switch (midiKey) {
            case 35, 36 -> MusicNote.NoteInstrument.BASS_DRUM;
            case 37, 38, 39, 40 -> MusicNote.NoteInstrument.SNARE_DRUM;
            case 41, 43, 45, 47, 48, 50 -> MusicNote.NoteInstrument.BASS;
            case 49, 51, 52, 53, 55, 57 -> MusicNote.NoteInstrument.CHIME;
            case 56, 59 -> MusicNote.NoteInstrument.COW_BELL;
            case 60, 61, 62, 63, 64 -> MusicNote.NoteInstrument.BELL;
            default -> MusicNote.NoteInstrument.CLICKS;
        };
    }

    private long gcd(long a, long b) {
        if (a == 0) {
            return Math.abs(b);
        }
        if (b == 0) {
            return Math.abs(a);
        }
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long temp = a % b;
            a = b;
            b = temp;
        }
        return a;
    }

    private record TimedShortMessage(long tick, long order, ShortMessage message) {
    }

    public record ImportResult(PlayerMusic music, RawNbsSong noteBlockSong, List<String> warnings) {
    }
}
