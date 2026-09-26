package com.huidu.musicboxplus.core.sound;

// NBS vanilla instrument id -> Minecraft sound name.
//
// Declaration order IS the NBS id: ordinal() is the number stored in the file, so never reorder.
//
// Ids 16..19 are the note block trumpet timbres, added in 1.26. On an older server the client
// has no such sound and the note is silent, so fallback() names a stock instrument to play
// instead. That table is pure policy; whether the runtime actually needs it is decided by
// NoteEmitter, which is the only place that can ask the server what sounds it has.
public enum VanillaInstrument {

    HARP("harp"),
    BASS("bass"),
    BASEDRUM("basedrum"),
    SNARE("snare"),
    HAT("hat"),
    GUITAR("guitar"),
    FLUTE("flute"),
    BELL("bell"),
    CHIME("chime"),
    XYLOPHONE("xylophone"),
    IRON_XYLOPHONE("iron_xylophone"),
    COW_BELL("cow_bell"),
    DIDGERIDOO("didgeridoo"),
    BIT("bit"),
    BANJO("banjo"),
    PLING("pling"),
    TRUMPET("trumpet"),
    TRUMPET_EXPOSED("trumpet_exposed"),
    TRUMPET_WEATHERED("trumpet_weathered"),
    TRUMPET_OXIDIZED("trumpet_oxidized");

    private static final String SOUND_NAME_PREFIX = "minecraft:block.note_block.";

    private static final VanillaInstrument[] BY_ID = values();

    private final String soundName;

    VanillaInstrument(String suffix) {
        this.soundName = SOUND_NAME_PREFIX + suffix;
    }

    // Fully qualified sound name, e.g. minecraft:block.note_block.harp.
    //
    // A String rather than org.bukkit.Sound: Sound turned from an enum into an interface in
    // 1.21.3, so code compiled against its constants throws IncompatibleClassChangeError when
    // loaded on the other side of that change. The sound name is plain data and survives the
    // API reshape, and it keeps this package free of any server dependency.
    public String soundName() {
        return soundName;
    }

    // What to play when the running server has no sound for this instrument, or null to stay
    // silent. Didgeridoo is the closest sustained timbre the stock set has to a trumpet.
    public VanillaInstrument fallback() {
        return switch (this) {
            case TRUMPET, TRUMPET_EXPOSED, TRUMPET_WEATHERED, TRUMPET_OXIDIZED -> DIDGERIDOO;
            default -> null;
        };
    }

    public static int count() {
        return BY_ID.length;
    }

    // Out-of-range ids fall back to harp, matching how the NBS editor treats unknown instruments.
    public static VanillaInstrument byId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            return HARP;
        }
        return BY_ID[id];
    }

    public static String soundNameById(int id) {
        return byId(id).soundName();
    }
}
