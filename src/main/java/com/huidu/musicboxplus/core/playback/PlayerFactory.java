package com.huidu.musicboxplus.core.playback;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;

// Creates the concrete playback player for a wrapper. core knows the contract, module
// registers the Radio/Speaker implementations at startup via PlayerWrapper.setPlayerFactory.
@FunctionalInterface
public interface PlayerFactory {
    PlayerSongPlayer create(IPlayList playList, PlayerWrapper wrapper);
}
