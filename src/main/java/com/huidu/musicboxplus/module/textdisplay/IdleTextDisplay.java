package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.bukkit.Location;

// A placed text display that has no song assigned yet. It owns the same floating
// display/interaction entities as TextDisplayPlayer (via TextDisplayVisual) and renders the idle
// placeholder, but runs no playback. TextDisplayPlayerManager.setSong/setPlaylist replaces it
// with a real TextDisplayPlayer.
public class IdleTextDisplay implements TextDisplayHandle {
    private final String name;
    private final TextDisplayPlayer.DisplayOptions displayOptions;
    private final TextDisplayVisual visual;
    private int range;
    private Location location;

    public IdleTextDisplay(String name, Location location, int range, TextDisplayPlayer.DisplayOptions displayOptions) {
        this.name = name;
        this.location = BukkitUtils.centerBlock(location);
        this.range = range;
        this.displayOptions = displayOptions != null ? displayOptions.copy() : TextDisplayPlayer.DisplayOptions.defaults();
        this.visual = new TextDisplayVisual(name, this.displayOptions);
        this.visual.spawn(this.location);
        refreshText();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public TextDisplayPlayer.DisplayOptions getDisplayOptions() {
        return this.displayOptions;
    }

    @Override
    public void refreshText() {
        this.visual.render(TextDisplayContent.build(this.name, this.displayOptions, null, (short) 0, 1.0f));
    }

    @Override
    public void adjustHeight(double delta) {
        this.visual.adjustHeight(delta, this.location);
    }

    @Override
    public MusicBoxSong getDisplaySong() {
        return null;
    }

    @Override
    public Location getLocation() {
        return this.location.clone();
    }

    @Override
    public int getRange() {
        return this.range;
    }

    @Override
    public void setRange(int range) {
        // No live playback yet; just store it so it carries over when a song is assigned.
        this.range = range;
    }

    @Override
    public void applyVisualOptions() {
        this.visual.applyBillboard();
    }

    @Override
    public void destroy() {
        this.visual.remove();
    }

    @Override
    public boolean isActive() {
        return false;
    }

    // Moves the placeholder to a new block location, keeping the same display entities.
    void relocate(Location newLocation) {
        this.location = BukkitUtils.centerBlock(newLocation);
        this.visual.teleport(this.location);
    }
}
