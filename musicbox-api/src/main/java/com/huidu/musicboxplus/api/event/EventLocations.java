package com.huidu.musicboxplus.api.event;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;
import com.huidu.musicboxplus.api.player.PositionPlayer;

// Location snapshotting shared by the player events.
//
// Every event that carries a location took its own copy of this, five in all. They sit in the
// published api jar, so a divergence between them is a contract inconsistency a downstream plugin
// sees rather than an internal detail.
final class EventLocations {

    private EventLocations() {
    }

    // Snapshot taken when the event is constructed, so a listener reads where the player was when
    // it fired rather than where it has since moved to.
    @Nullable
    static Location snapshot(@Nullable Object player) {
        if (!(player instanceof PositionPlayer positionPlayer)) {
            return null;
        }
        try {
            Location location = positionPlayer.getLocation();
            return location == null ? null : location.clone();
        } catch (Exception ex) {
            // Reading the location can throw, e.g. once the world is unloaded; the event still
            // has to be constructible.
            return null;
        }
    }
}
