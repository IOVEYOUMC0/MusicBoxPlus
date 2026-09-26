package com.huidu.musicboxplus.api.event;

import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.jetbrains.annotations.NotNull;

// BlockRedstoneEvent that also carries the block which triggered the redstone
// change, so listeners can tell power changes caused by a neighbouring source apart
// from those caused by the block itself.
public class SourcedBlockRedstoneEvent extends BlockRedstoneEvent {

    private static final HandlerList handlers = new HandlerList();

    private final Block source;

    public SourcedBlockRedstoneEvent(Block source, Block block, int oldCurrent, int newCurrent) {
        super(block, oldCurrent, newCurrent);
        this.source = source;
    }

    public Block getSource() {
        return source;
    }

    public boolean hasChanged() {
        return getOldCurrent() != getNewCurrent();
    }

    public boolean isOn() {
        return getNewCurrent() > 0;
    }

    public boolean wasOn() {
        return getOldCurrent() > 0;
    }

    // A minor change is one the plugin does not need to react to: either the current
    // did not move at all, or the power stayed on the same side of the threshold.
    public boolean isMinor() {
        return !hasChanged() || wasOn() == isOn();
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}