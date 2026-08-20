package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

public class TextDisplayPlayer extends AbstractBlockPlayer implements TextDisplayHandle {
    private final String name;
    private final DisplayOptions displayOptions;
    private final TextDisplayVisual visual;
    private short lastRenderedTick = Short.MIN_VALUE;

    public TextDisplayPlayer(String name, IPlayList list, Location location, int range) {
        this(name, list, location, range, 1.0f, DisplayOptions.defaults());
    }

    public TextDisplayPlayer(String name, IPlayList list, Location location, int range, float speedMultiplier) {
        this(name, list, location, range, speedMultiplier, DisplayOptions.defaults());
    }

    public TextDisplayPlayer(String name, IPlayList list, Location location, int range, float speedMultiplier, DisplayOptions displayOptions) {
        super(list, location, range, speedMultiplier);
        this.name = name;
        // A text display is a fixture the player placed, so it must not delete itself just
        // because nobody is nearby. The base class arms the range model's auto-destroy timer
        // from config.yml, and isPersistentOnEnd() only guards the end-of-song path, so the
        // timer has to be disabled explicitly here.
        this.getRangePlayerModel().setAutoDestroyMillis(0);
        this.displayOptions = displayOptions != null ? displayOptions.copy() : DisplayOptions.defaults();
        this.visual = new TextDisplayVisual(name, this.displayOptions);
        this.visual.spawn(this.getTargetLocation());
        refreshDisplay();
    }


    private void refreshDisplay() {
        MusicBoxSong current = this.getPlayList() != null ? (MusicBoxSong) this.getPlayList().getCurrent() : null;
        this.visual.render(TextDisplayContent.build(this.name, this.displayOptions, current, this.getTick(),
                this.getMusicBoxModel().getPlaybackSpeedMultiplier()));
        this.lastRenderedTick = this.getTick();
    }

    @Override
    public void tick() {
        super.tick();
        short currentTick = this.getTick();
        if (currentTick == this.lastRenderedTick) {
            return;
        }
        int refreshInterval = MusicBox.getInstance().getConfigObject().getPerformance().getTextDisplayRefreshIntervalTicks();
        if (currentTick < this.lastRenderedTick || currentTick - this.lastRenderedTick >= refreshInterval) {
            refreshDisplay();
        }
    }

    @Override
    protected TextDisplayPlayer runNextSong(IPlayList list) {
        TextDisplayPlayer nextPlayer = new TextDisplayPlayer(this.name, list, this.getLocation(), this.getRange(), this.getMusicBoxModel().getPlaybackSpeedMultiplier(), this.displayOptions);
        this.getMusicBoxModel().copySettingsTo(nextPlayer.getMusicBoxModel());
        TextDisplayPlayerManager.reregister(this.name, nextPlayer);
        return nextPlayer;
    }

    @Override
    protected void songEnd() {
    }

    @Override
    protected @Nullable Location getInfoSign() {
        return null;
    }

    // As in SignPlayer, the reason-taking overload is the one to override: overriding only
    // the no-arg destroy() would let callers of destroy(reason) skip visual.remove() and
    // leave the display entities orphaned in the world forever.
    @Override
    public void destroy(DestroyReason reason) {
        if (!this.isDestroyed()) {
            this.visual.remove();
        }
        super.destroy(reason);
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public DisplayOptions getDisplayOptions() {
        return this.displayOptions;
    }

    @Override
    public void refreshText() {
        refreshDisplay();
    }

    @Override
    public MusicBoxSong getDisplaySong() {
        return (MusicBoxSong) this.getMusicBoxSong();
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean isPersistentOnEnd() {
        // A placed text display should stop at the end and keep showing, never self-destruct.
        return true;
    }

    // setRange(int) is inherited from AbstractBlockPlayer and takes effect live (RangePlayerModel
    // reads the range dynamically each tick), so it already satisfies TextDisplayHandle.setRange.

    @Override
    public void applyVisualOptions() {
        this.visual.applyBillboard();
    }

    // Nudges the floating text up/down by delta blocks. Visual only - the playback/audio
    // origin stays at the player's block location. Clamped to a sane range and applied by
    // teleporting the existing display entities.
    @Override
    public void adjustHeight(double delta) {
        this.visual.adjustHeight(delta, this.getTargetLocation());
    }

    public static class DisplayOptions {
        private boolean showName = true;
        private boolean showSong = true;
        private boolean showProgress = true;
        private boolean showTime = true;
        // Extra vertical offset (blocks) applied on top of the default +1.8 display height.
        private double heightOffset = 0.0;
        // When true the display uses a FIXED billboard (fixed orientation) instead of CENTER (faces players).
        private boolean billboardFixed = false;
        // Yaw used for the FIXED billboard orientation (ignored when not fixed).
        private float fixedYaw = 0.0f;
        // When true, players without musicboxplus.admin may open the (limited) edit menu for this display.
        private boolean allowPublicEdit = false;

        public static DisplayOptions defaults() {
            return new DisplayOptions();
        }

        public DisplayOptions copy() {
            DisplayOptions copy = new DisplayOptions();
            copy.showName = this.showName;
            copy.showSong = this.showSong;
            copy.showProgress = this.showProgress;
            copy.showTime = this.showTime;
            copy.heightOffset = this.heightOffset;
            copy.billboardFixed = this.billboardFixed;
            copy.fixedYaw = this.fixedYaw;
            copy.allowPublicEdit = this.allowPublicEdit;
            return copy;
        }

        public double getHeightOffset() {
            return heightOffset;
        }

        public void setHeightOffset(double heightOffset) {
            this.heightOffset = heightOffset;
        }

        public boolean isShowName() {
            return showName;
        }

        public void setShowName(boolean showName) {
            this.showName = showName;
        }

        public boolean isShowSong() {
            return showSong;
        }

        public void setShowSong(boolean showSong) {
            this.showSong = showSong;
        }

        public boolean isShowProgress() {
            return showProgress;
        }

        public void setShowProgress(boolean showProgress) {
            this.showProgress = showProgress;
        }

        public boolean isShowTime() {
            return showTime;
        }

        public void setShowTime(boolean showTime) {
            this.showTime = showTime;
        }

        public boolean isBillboardFixed() {
            return billboardFixed;
        }

        public void setBillboardFixed(boolean billboardFixed) {
            this.billboardFixed = billboardFixed;
        }

        public float getFixedYaw() {
            return fixedYaw;
        }

        public void setFixedYaw(float fixedYaw) {
            this.fixedYaw = fixedYaw;
        }

        public boolean isAllowPublicEdit() {
            return allowPublicEdit;
        }

        public void setAllowPublicEdit(boolean allowPublicEdit) {
            this.allowPublicEdit = allowPublicEdit;
        }
    }
}
