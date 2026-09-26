package com.huidu.musicboxplus.api.player.loop;

// Playback loop mode. Display text is a presentation concern and lives in the
// common.lang layer, so this enum carries only the stable key.
public enum LoopMode {
    OFF("off"),
    SINGLE("single"),
    ALL("all");

    private final String key;

    LoopMode(String key) {
        this.key = key;
    }

    public String getKey() {
        return this.key;
    }

    public LoopMode next() {
        LoopMode[] values = LoopMode.values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static LoopMode fromKey(String key) {
        for (LoopMode mode : LoopMode.values()) {
            if (!mode.key.equalsIgnoreCase(key)) continue;
            return mode;
        }
        return OFF;
    }
}
