package com.huidu.musicboxplus.core.song;

import java.util.UUID;

// Read-only metadata a core song needs from player-created music. core must not depend on
// the module-layer music model (module.edit.PlayerMusic), so the concrete class implements
// this contract and is passed around through it.
public interface PlayerMusicSource {

    UUID getUniqueId();

    String getName();

    String getAuthor();

    int getBpm();

    int getBeatSubdivision();

    int getMaxTick();
}
