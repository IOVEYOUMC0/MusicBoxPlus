package com.huidu.musicboxplus.module.gui.minecraft.actions;

import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.function.Consumer;

// Wired-up callback that runs one of two runnables depending on which mouse button
// the player used. Either handler may be null to make that button a no-op.
public class ClickAction implements InventoryAction {

    private final Runnable onLeftClick;
    private final Runnable onRightClick;
    private final Consumer<InventoryClickEvent> onLeftEvent;
    private final Consumer<InventoryClickEvent> onRightEvent;

    public ClickAction(Runnable onLeftClick, Runnable onRightClick) {
        this.onLeftClick = onLeftClick;
        this.onRightClick = onRightClick;
        this.onLeftEvent = null;
        this.onRightEvent = null;
    }

    public ClickAction(Runnable onLeftClick) {
        this(onLeftClick, null);
    }

    public ClickAction(Consumer<InventoryClickEvent> onLeftClick,
                       Consumer<InventoryClickEvent> onRightClick) {
        this.onLeftClick = null;
        this.onRightClick = null;
        this.onLeftEvent = onLeftClick;
        this.onRightEvent = onRightClick;
    }

    public ClickAction(Consumer<InventoryClickEvent> onLeftClick) {
        this(onLeftClick, null);
    }

    @Override
    public void onEvent(InventoryClickEvent event) {
        if (event.getClick().isLeftClick()) {
            if (onLeftClick != null) {
                onLeftClick.run();
            }
            if (onLeftEvent != null) {
                onLeftEvent.accept(event);
            }
        } else if (event.getClick().isRightClick() && onRightClick != null) {
            onRightClick.run();
        } else if (event.getClick().isRightClick() && onRightEvent != null) {
            onRightEvent.accept(event);
        }
    }
}
