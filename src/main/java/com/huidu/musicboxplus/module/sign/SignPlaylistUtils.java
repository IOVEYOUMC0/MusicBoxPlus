package com.huidu.musicboxplus.module.sign;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.SignUtils;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.playback.SongUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class SignPlaylistUtils {

    private SignPlaylistUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }



    public static CompletableFuture<Optional<IPlayList>> parseSignPlaylistAsync(Sign sign) {
        if (!SignPlayer.isPlayerSign(sign)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String rawSongId = SignUtils.getSignLine(sign, 0);
        String songId = SignUtils.stripColor(rawSongId).trim();
        if (songId.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String lineThree = SignUtils.getSignLine(sign, 3);
        boolean rand = lineThree.contains("R");
        boolean hasEnd = SignUtils.hasEnd(sign);

        if (!songId.regionMatches(true, 0, "LIST:", 0, "LIST:".length())) {
            return CompletableFuture.completedFuture(
                MusicBoxSongManager.getContainerById(songId).map(container -> ListPlaylist.fromContainer(container, rand, hasEnd))
            );
        }

        CompletableFuture<Optional<IPlayList>> future = new CompletableFuture<>();
        AsyncTaskManager.runAsync(() -> {
            try {
                Optional<SongContainer> container = MusicBoxSongManager.getContainerById(songId);
                future.complete(container.map(value -> ListPlaylist.fromContainer(value, rand, hasEnd)));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static void setPlayListInfo(Location signLocation, IPlayList list) {
        BukkitUtils.checkPrimary();
        Block block = signLocation.getBlock();
        if (block.getState() instanceof Sign sign) {
            List<String> signText = SongUtils.generateCompactPlaylistLore(list, 1, 2);
            SignSide side = sign.getSide(Side.FRONT);
            for (int i = 0; i < 4; i++) {
                String str = signText.size() > i ? signText.get(i) : "";
                // The compact playlist lore is MiniMessage (e.g. "<green>name</green>");
                // render it as such instead of via the legacy-section serializer, which
                // would leave the tags as literal text on the sign.
                side.line(i, str.isEmpty() ? Component.empty() : MiniMessageUtils.processComponent(str));
            }
            sign.update();
        }
    }
}
