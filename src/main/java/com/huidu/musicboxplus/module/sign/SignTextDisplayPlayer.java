package com.huidu.musicboxplus.module.sign;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.core.playback.SongUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.List;

public class SignTextDisplayPlayer {
    private final Location anchor;
    private TextDisplay display;

    public SignTextDisplayPlayer(Location anchor) {
        this.anchor = anchor.clone();
    }

    public void spawnOrUpdate(IPlayList playList) {
        Scheduler.region(anchor, () -> {
            World world = anchor.getWorld();
            if (world == null) {
                return;
            }

            if (display == null || !display.isValid()) {
                Location base = anchor.clone().add(0.5, 1.35, 0.5);
                display = world.spawn(base, TextDisplay.class, spawned -> {
                    spawned.setBillboard(Display.Billboard.CENTER);
                    spawned.setSeeThrough(true);
                    spawned.setShadowed(false);
                    spawned.setPersistent(false);
                    spawned.setGravity(false);
                    spawned.setDefaultBackground(false);
                    spawned.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    spawned.setLineWidth(220);
                });
            }

            display.text(buildPlaylistInfoComponent(playList));
        });
    }

    public void remove() {
        Scheduler.region(anchor, () -> {
            if (display != null) {
                display.remove();
                display = null;
            }
        });
    }

    public boolean isActive() {
        return display != null && display.isValid();
    }

    private Component buildPlaylistInfoComponent(IPlayList playList) {
        List<String> lines = SongUtils.generateCompactPlaylistLore(playList, 1, 2);
        return MiniMessageUtils.processComponent(String.join("\n", lines));
    }
}
