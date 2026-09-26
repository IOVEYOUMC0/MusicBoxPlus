package com.huidu.musicboxplus.module.sign;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.core.playback.SongUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.List;

public class SignTextDisplayPlayer {
    private final Location anchor;
    private TextDisplay display;
    // Text currently on the display. Refreshed on a 5 s timer, so without this the component was
    // rebuilt (MiniMessage parse of the whole playlist block) and pushed to the client every time,
    // for a display that only changes when the playlist moves.
    private String appliedText;

    public SignTextDisplayPlayer(Location anchor) {
        this.anchor = anchor.clone();
    }

    public void spawnOrUpdate(IPlayList playList) {
        Scheduler.region(anchor, () -> {
            World world = anchor.getWorld();
            if (world == null) {
                return;
            }

            List<String> lines = SongUtils.generateCompactPlaylistLore(playList, 1, 2);
            String text = String.join("\n", lines);
            boolean displayLive = display != null && display.isValid();
            if (displayLive && text.equals(appliedText)) {
                return;
            }

            if (!displayLive) {
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

            appliedText = text;
            display.text(MiniMessageUtils.processComponent(text));
        });
    }

    public void remove() {
        Scheduler.region(anchor, () -> {
            if (display != null) {
                display.remove();
                display = null;
            }
            appliedText = null;
        });
    }

    public boolean isActive() {
        return display != null && display.isValid();
    }
}
