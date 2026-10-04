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
    private TextDisplay backDisplay;
    private Interaction interaction;
    private volatile Component renderedText;
    // The block anchor the display currently sits at; used to pick the owning region for
    // every scheduled entity mutation. Volatile because it is set/read across region threads.
    private volatile Location anchor;

    TextDisplayVisual(String name, TextDisplayPlayer.DisplayOptions options) {
        this.name = name;
        this.options = options;
    }

    private Location base(Location anchor, float yawOffset) {
        Location base = anchor.clone().add(options.getXOffset(),
                1.8 + options.getHeightOffset(), options.getZOffset());
        if (options.isBillboardFixed()) {
            base.setYaw(options.getFixedYaw() + yawOffset);
            base.setPitch(0.0f);
        }
        return base;
    }

    private boolean wantsBackDisplay() {
        return options.isBillboardFixed() && options.isDoubleSided();
    }

    private TextDisplay spawnDisplay(Location location) {
        return location.getWorld().spawn(location, TextDisplay.class, spawned -> {
            spawned.setBillboard(options.isBillboardFixed() ? Display.Billboard.FIXED : Display.Billboard.CENTER);
            spawned.setSeeThrough(true);
            spawned.setShadowed(false);
            spawned.setPersistent(false);
            spawned.setGravity(false);
            spawned.setDefaultBackground(false);
            spawned.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            spawned.setLineWidth(220);
            Component text = this.renderedText;
            if (text != null) {
                spawned.text(text);
            }
        });
    }

    void spawn(Location anchor) {
        this.anchor = anchor.clone();
        Scheduler.region(anchor, () -> {
            removeInternal();
            Location base = base(this.anchor, 0.0f);
            this.display = spawnDisplay(base);
            if (wantsBackDisplay()) {
                this.backDisplay = spawnDisplay(base(this.anchor, 180.0f));
            }
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
        this.renderedText = component;
        if (this.anchor == null) {
            return;
        }
        applyText(this.display, component);
        applyText(this.backDisplay, component);
    }

    // Entity mutations go through each display's own scheduler, not the anchor's region.
    //
    // The anchor says where the display was spawned, not where it is now: adjustPosition() changes the
    // configured x/z offset by an unbounded amount, so a display can be nudged into a neighbouring
    // chunk, and Folia can split a region between two chunks at any time. Scheduling on the anchor's
    // region then mutates an entity that thread does not own. teleport() already dispatches this way
    // for exactly that reason.
    private static void applyText(TextDisplay display, Component component) {
        if (display == null) {
            return;
        }
        Scheduler.entity(display, () -> {
            if (display.isValid()) {
                display.text(component);
            }
        });
    }

    void adjustHeight(double delta, Location anchor) {
        double next = Math.max(-3.0, Math.min(10.0, options.getHeightOffset() + delta));
        options.setHeightOffset(next);
        teleport(anchor);
    }

    void adjustPosition(double deltaX, double deltaZ, Location anchor) {
        options.setXOffset(options.getXOffset() + deltaX);
        options.setZOffset(options.getZOffset() + deltaZ);
        teleport(anchor);
    }

    void teleport(Location anchor) {
        this.anchor = anchor.clone();
        // The entities may still be owned by the OLD region (a relocation can cross a region
        // boundary). Marshal onto each entity's own scheduler, which always runs on whichever
        // region currently owns that entity, and let teleportAsync carry it to the new region.
        // Scheduling on the destination anchor's region would touch entities that thread does
        // not own and throw on Folia.
        final Location base = base(this.anchor, 0.0f);
        final Location backBase = base(this.anchor, 180.0f);
        final TextDisplay d = this.display;
        final TextDisplay back = this.backDisplay;
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
        if (back != null) {
            Scheduler.entity(back, () -> {
                if (back.isValid()) {
                    back.teleportAsync(backBase);
                }
            });
        }
    }

    void applyBillboard() {
        Location a = this.anchor;
        if (a == null) {
            return;
        }
        // The spawn has to happen on the location's region, so the back display is created here and
        // every mutation below is dispatched to the entity that owns it (see applyText).
        Scheduler.region(a, () -> {
            TextDisplay front = this.display;
            if (front == null || !front.isValid()) {
                return;
            }
            applyBillboardTo(front, 0.0f);
            boolean wantsBack = wantsBackDisplay();
            if (wantsBack && (this.backDisplay == null || !this.backDisplay.isValid())) {
                this.backDisplay = spawnDisplay(base(a, 180.0f));
            } else if (!wantsBack && this.backDisplay != null) {
                TextDisplay stale = this.backDisplay;
                this.backDisplay = null;
                removeDisplay(stale);
            }
            applyBillboardTo(this.backDisplay, 180.0f);
        });
    }

    private void applyBillboardTo(TextDisplay display, float yawOffset) {
        if (display == null) {
            return;
        }
        Scheduler.entity(display, () -> {
            if (!display.isValid()) {
                return;
            }
            display.setBillboard(options.isBillboardFixed() ? Display.Billboard.FIXED : Display.Billboard.CENTER);
            if (options.isBillboardFixed()) {
                display.setRotation(options.getFixedYaw() + yawOffset, 0.0f);
            }
        });
    }

    private static void removeDisplay(TextDisplay display) {
        if (display == null) {
            return;
        }
        Scheduler.entity(display, display::remove);
    }

    void remove() {
        try {
            removeInternal();
        } catch (Exception e) {
            // Teardown of the owning player must survive this: the entities are gone or going, and
            // the registry entries are cleared here either way.
            DebugLogger.debug("Failed to clear text display visual: " + e.getMessage());
        }
    }

    private void removeInternal() {
        // Same reasoning as applyText: each entity is removed by whoever owns it. A display left on
        // the anchor's region because it was spawned there is not necessarily still there.
        Interaction interaction = this.interaction;
        TextDisplay front = this.display;
        TextDisplay back = this.backDisplay;
        this.interaction = null;
        this.display = null;
        this.backDisplay = null;
        if (interaction != null) {
            // Map-only, so it is safe on any thread; done first so a display that is already gone
            // can never leave the interaction registered.
            TextDisplayPlayerManager.unregisterInteraction(interaction);
            Scheduler.entity(interaction, interaction::remove);
        }
        removeDisplay(front);
        removeDisplay(back);
    }
}
