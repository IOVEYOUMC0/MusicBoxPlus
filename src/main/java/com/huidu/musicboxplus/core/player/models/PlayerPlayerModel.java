package com.huidu.musicboxplus.core.player.models;

import com.huidu.musicboxplus.core.playback.PlayerWrapper;
public class PlayerPlayerModel {
    private final PlayerWrapper wrapper;
    private final MusicBoxSongPlayerModel model;
    private int lastProgressUpdate = 0;

    public PlayerPlayerModel(PlayerWrapper wrapper, MusicBoxSongPlayerModel model) {
        this.wrapper = wrapper;
        this.model = model;
        if (!wrapper.isSeamlessPlayerSwap()) {
            wrapper.setBarProgress(0.0);
        }
        wrapper.setBarVisible(true);
        wrapper.setBarTitle(wrapper.buildBossBarTitle(model.getMusicBoxSong()));
    }
    
    public void addPlayerToSong() {
        model.addPlayer(wrapper.getPlayer());
    }
    
    public void nextTick(int all, int current) {
        ++this.lastProgressUpdate;
        if (this.lastProgressUpdate >= 20) {
            this.lastProgressUpdate = 0;
            double progress = all > 0 ? Math.min(1.0, (double)current / (double)all) : 0.0;
            this.wrapper.setBarProgress(progress);
        }
    }

    public void destroy() {
        this.wrapper.nullActivePlayer(this.getModel().getMusicBoxSongPlayer());
    }

    public PlayerWrapper getWrapper() {
        return this.wrapper;
    }

    public MusicBoxSongPlayerModel getModel() {
        return this.model;
    }

    public int getLastProgressUpdate() {
        return this.lastProgressUpdate;
    }
}
