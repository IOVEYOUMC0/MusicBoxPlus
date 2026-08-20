package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.common.utils.DebugLogger;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;

// Owns the floating TextDisplay + Interaction entities for a text player and renders text
// into them; shared by TextDisplayPlayer and the song-less IdleTextDisplay.
// Every entity mutation is marshalled onto the region that owns the display's block
// (Scheduler.region). A region processes its queued tasks in FIFO order, so
// spawn -> render -> teleport -> remove keep their relative ordering. On regular Paper that
// region is just the main thread.
final class TextDisplayVisual {
    private final String name;
    private final TextDisplayPlayer.DisplayOptions options;
    private TextDisplay display;
    private Interaction interaction;
    // The block anchor the display currently sits at; used to pick the owning region for
    // every scheduled entity mutation. Volatile because it is set/read across region threads.
    private volatile Location anchor;

    TextDisplayVisual(String name, TextDisplayPlayer.DisplayOptions options) {
        this.name = name;
        this.options = options;
    }

    private Location base(Location anchor) {
        Location base = anchor.clone().add(0.0, 1.8 + options.getHeightOffset(), 0.0);
        if (options.isBillboardFixed()) {
            base.setYaw(options.getFixedYaw());
            base.setPitch(0.0f);
        }
        return base;
    }

    void spawn(Location anchor) {
        this.anchor = anchor.clone();
        Scheduler.region(anchor, () -> {
            removeInternal();
            Location base = base(this.anchor);
            this.display = base.getWorld().spawn(base, TextDisplay.class, spawned -> {
                spawned.setBillboard(options.isBillboardFixed() ? Display.Billboard.FIXED : Display.Billboard.CENTER);
                spawned.setSeeThrough(true);
                spawned.setShadowed(false);
                spawned.setPersistent(false);
                spawned.setGravity(false);
                spawned.setDefaultBackground(false);
                spawned.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                spawned.setLineWidth(220);
            });
            this.interaction = base.getWorld().spawn(base, Interaction.class, spawned -> {
                spawned.setPersistent(false);
                spawned.setGravity(false);
                spawned.setInteractionWidth(2.4f);
                spawned.setInteractionHeight(1.4f);
                spawned.setResponsive(true);
            });
            TextDisplayPlayerManager.registerInteraction(this.interaction, this.name);
        });
    }

    void render(Component component) {
        Location a = this.anchor;
        if (a == null) {
            return;
        }
        Scheduler.region(a, () -> {
            if (this.display != null && this.display.isValid()) {
                this.display.text(component);
            }
        });
    }

    void adjustHeight(double delta, Location anchor) {
        double next = Math.max(-3.0, Math.min(10.0, options.getHeightOffset() + delta));
        options.setHeightOffset(next);
        teleport(anchor);
    }

    void teleport(Location anchor) {
        this.anchor = anchor.clone();
        // The entities may still be owned by the OLD region (a relocation can cross a region
        // boundary). Marshal onto each entity's own scheduler, which always runs on whichever
        // region currently owns that entity, and let teleportAsync carry it to the new region.
        // Scheduling on the destination anchor's region would touch entities that thread does
        // not own and throw on Folia.
        final Location base = base(this.anchor);
        final TextDisplay d = this.display;
        final Interaction i = this.interaction;
        if (d != null) {
            Scheduler.entity(d, () -> {
                if (d.isValid()) {
                    d.teleportAsync(base);
                }
            });
        }
        if (i != null) {
            Scheduler.entity(i, () -> {
                if (i.isValid()) {
                    i.teleportAsync(base);
                }
            });
        }
    }

    void applyBillboard() {
        Location a = this.anchor;
        if (a == null) {
            return;
        }
        Scheduler.region(a, () -> {
            if (this.display == null || !this.display.isValid()) {
                return;
            }
            this.display.setBillboard(options.isBillboardFixed() ? Display.Billboard.FIXED : Display.Billboard.CENTER);
            if (options.isBillboardFixed()) {
                this.display.setRotation(options.getFixedYaw(), 0.0f);
            }
        });
    }

    void remove() {
        Location a = this.anchor;
        if (a == null) {
            removeInternal();
            return;
        }
        Scheduler.region(a, () -> {
            try {
                removeInternal();
            } catch (Exception e) {
                DebugLogger.debug("Failed to clear text display visual: " + e.getMessage());
            }
        });
    }

    private void removeInternal() {
        if (this.interaction != null) {
            TextDisplayPlayerManager.unregisterInteraction(this.interaction);
            this.interaction.remove();
            this.interaction = null;
        }
        if (this.display != null) {
            this.display.remove();
            this.display = null;
        }
    }
}
