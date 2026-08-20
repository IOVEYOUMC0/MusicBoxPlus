package com.huidu.musicboxplus.module.web;

import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;

final class WebInstrumentJsonSupport {
    private WebInstrumentJsonSupport() {
    }

    static String getEffectiveInstrumentMode(MusicNote.NoteInstrument instrument) {
        if (ResourcePackInstrumentUtils.shouldUseCustomSound(instrument)) {
            return "resource_pack";
        }
        return "native";
    }

    static String getEffectiveSoundIdentifier(MusicNote.NoteInstrument instrument) {
        String customSound = ResourcePackInstrumentUtils.resolveSoundKey(instrument);
        if (customSound != null && !customSound.isBlank()) {
            return customSound;
        }
        return instrument.getBukkitInstrument().name();
    }
}
