package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.lang.Lang;
import org.bukkit.Instrument;
import org.bukkit.Material;

import java.util.*;

public class MusicNote {

    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public enum NoteInstrument {
        HARP(Instrument.PIANO, Material.NOTE_BLOCK, Lang.INSTRUMENT_HARP),
        BASS(Instrument.BASS_GUITAR, Material.OAK_PLANKS, Lang.INSTRUMENT_BASS),
        BASS_DRUM(Instrument.BASS_DRUM, Material.STONE, Lang.INSTRUMENT_BASEDRUM),
        SNARE_DRUM(Instrument.SNARE_DRUM, Material.SAND, Lang.INSTRUMENT_SNARE),
        CLICKS(Instrument.STICKS, Material.GLASS, Lang.INSTRUMENT_HAT),
        GUITAR(Instrument.GUITAR, Material.OAK_FENCE, Lang.INSTRUMENT_GUITAR),
        FLUTE(Instrument.FLUTE, Material.CLAY, Lang.INSTRUMENT_FLUTE),
        BELL(Instrument.BELL, Material.GOLD_BLOCK, Lang.INSTRUMENT_BELL),
        CHIME(Instrument.CHIME, Material.PACKED_ICE, Lang.INSTRUMENT_CHIME),
        XYLOPHONE(Instrument.XYLOPHONE, Material.BONE_BLOCK, Lang.INSTRUMENT_XYLOPHONE),
        IRON_XYLOPHONE(Instrument.IRON_XYLOPHONE, Material.IRON_BLOCK, Lang.INSTRUMENT_IRON_XYLOPHONE),
        COW_BELL(Instrument.COW_BELL, Material.SOUL_SAND, Lang.INSTRUMENT_COW_BELL),
        DIDGERIDOO(Instrument.DIDGERIDOO, Material.PUMPKIN, Lang.INSTRUMENT_DIDGERIDOO),
        BIT(Instrument.BIT, Material.EMERALD_BLOCK, Lang.INSTRUMENT_BIT),
        BANJO(Instrument.BANJO, Material.HAY_BLOCK, Lang.INSTRUMENT_BANJO),
        PLING(Instrument.PLING, Material.GLOWSTONE, Lang.INSTRUMENT_PLING),
        TRUMPET("TRUMPET", Instrument.DIDGERIDOO, Material.COPPER_BLOCK, Lang.INSTRUMENT_TRUMPET),
        TRUMPET_EXPOSED("TRUMPET_EXPOSED", Instrument.DIDGERIDOO, Material.EXPOSED_COPPER, Lang.INSTRUMENT_TRUMPET_EXPOSED),
        TRUMPET_WEATHERED("TRUMPET_WEATHERED", Instrument.DIDGERIDOO, Material.WEATHERED_COPPER, Lang.INSTRUMENT_TRUMPET_WEATHERED),
        TRUMPET_OXIDIZED("TRUMPET_OXIDIZED", Instrument.DIDGERIDOO, Material.OXIDIZED_COPPER, Lang.INSTRUMENT_TRUMPET_OXIDIZED);

        private final String bukkitInstrumentName;
        private final Instrument bukkitInstrument;
        private final Material material;
        private final Lang langKey;
        
        private static final NoteInstrument[] VALUES = values();
        private static final Map<Instrument, NoteInstrument> BUKKIT_CACHE = new HashMap<>();
        private static final List<NoteInstrument> INSTRUMENT_LIST = new ArrayList<>();
        
        static {
            for (NoteInstrument ni : VALUES) {
                if (ni.isRuntimeAvailable()) {
                    BUKKIT_CACHE.put(ni.bukkitInstrument, ni);
                }
                INSTRUMENT_LIST.add(ni);
            }
        }

        NoteInstrument(Instrument bukkitInstrument, Material material, Lang langKey) {
            this.bukkitInstrumentName = bukkitInstrument.name();
            this.bukkitInstrument = bukkitInstrument;
            this.material = material;
            this.langKey = langKey;
        }

        NoteInstrument(String bukkitInstrumentName, Instrument fallbackInstrument, Material material, Lang langKey) {
            this.bukkitInstrumentName = bukkitInstrumentName;
            this.bukkitInstrument = resolveInstrument(bukkitInstrumentName, fallbackInstrument);
            this.material = material;
            this.langKey = langKey;
        }

        private static Instrument resolveInstrument(String instrumentName, Instrument fallbackInstrument) {
            try {
                return Instrument.valueOf(instrumentName);
            } catch (IllegalArgumentException ignored) {
                return fallbackInstrument;
            }
        }

        public Instrument getBukkitInstrument() {
            return bukkitInstrument;
        }

        public Material getMaterial() {
            return material;
        }

        public String getDisplayName() {
            return langKey.toString();
        }
        
        public Lang getLangKey() {
            return langKey;
        }

        public boolean isTrumpetFamily() {
            return switch (this) {
                case TRUMPET, TRUMPET_EXPOSED, TRUMPET_WEATHERED, TRUMPET_OXIDIZED -> true;
                default -> false;
            };
        }

        public boolean isRuntimeAvailable() {
            return bukkitInstrumentName.equals(bukkitInstrument.name());
        }

        public static NoteInstrument fromBukkit(Instrument instrument) {
            return BUKKIT_CACHE.getOrDefault(instrument, HARP);
        }

        public static NoteInstrument parseStored(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        // An instrument the runtime cannot play is kept, not rewritten. Playback substitutes a
        // stand-in sound at emit time (VanillaInstrument.fallback), so the note stays a trumpet
        // in the file and sounds like one again the moment the server is new enough. Rewriting it
        // to DIDGERIDOO here used to make the loss permanent: re-exporting wrote instrument id 12.
        public static NoteInstrument normalizeForCurrentConfig(NoteInstrument instrument) {
            return instrument == null ? HARP : instrument;
        }

        public static NoteInstrument parseExternal(String name) {
            return normalizeForCurrentConfig(parseStored(name));
        }

        public static boolean isTrumpetEnabled() {
            return isTrumpetRuntimeAvailable() || isTrumpetFallbackEnabled();
        }

        public static boolean isTrumpetRuntimeAvailable() {
            return Arrays.stream(VALUES)
                    .anyMatch(instrument -> instrument.isTrumpetFamily() && instrument.isRuntimeAvailable());
        }

        public static boolean isTrumpetFallbackEnabled() {
            try {
                MusicBox musicBox = MusicBox.getInstance();
                return musicBox != null && musicBox.getConfigObject() != null
                        && musicBox.getConfigObject().isEnableTrumpetResourcePackFallback();
            } catch (Exception ignored) {
                return false;
            }
        }

        public static boolean shouldUseCustomTrumpetFallback() {
            return !isTrumpetRuntimeAvailable() && isTrumpetFallbackEnabled();
        }

        public static boolean isInstrumentEnabled(NoteInstrument instrument) {
            if (instrument == null) {
                return false;
            }
            if (!instrument.isTrumpetFamily()) {
                return true;
            }
            return isTrumpetEnabled();
        }
        
        public static NoteInstrument[] getAllValues() {
            return VALUES;
        }
        
        public static List<NoteInstrument> getInstrumentList() {
            return INSTRUMENT_LIST;
        }

        public static NoteInstrument[] getAvailableValues() {
            return Arrays.stream(VALUES)
                    .filter(NoteInstrument::isInstrumentEnabled)
                    .toArray(NoteInstrument[]::new);
        }

        public static List<NoteInstrument> getAvailableInstrumentList() {
            return Arrays.stream(VALUES)
                    .filter(NoteInstrument::isInstrumentEnabled)
                    .toList();
        }
        
        public static int getTotalCount() {
            return VALUES.length;
        }

        public static int getAvailableCount() {
            return getAvailableValues().length;
        }
    }

    private final int pitch;
    private final int tick;
    private final List<NoteInstrument> instruments;

    public MusicNote(int pitch, int tick) {
        this.pitch = Math.max(0, pitch);
        this.tick = Math.max(0, tick);
        this.instruments = new ArrayList<>();
    }

    public MusicNote(int pitch, int tick, List<NoteInstrument> instruments) {
        this.pitch = Math.max(0, pitch);
        this.tick = Math.max(0, tick);
        this.instruments = new ArrayList<>(instruments);
    }

    public int getPitch() {
        return pitch;
    }

    public int getTick() {
        return tick;
    }

    public List<NoteInstrument> getInstruments() {
        return new ArrayList<>(instruments);
    }

    public int getInstrumentCount() {
        return instruments.size();
    }

    public boolean addInstrument(NoteInstrument instrument) {
        if (!instruments.contains(instrument)) {
            return instruments.add(instrument);
        }
        return false;
    }

    public boolean removeInstrument(NoteInstrument instrument) {
        return instruments.remove(instrument);
    }

    public void setInstruments(List<NoteInstrument> instruments) {
        this.instruments.clear();
        this.instruments.addAll(instruments);
    }

    public static String getNoteName(int pitch) {
        int noteIndex = pitch % 12;
        int octave = pitch / 12;
        return NOTE_NAMES[noteIndex] + (octave + 1);
    }
}
