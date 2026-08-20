package com.huidu.musicboxplus.module.gui.minecraft.actions;

import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;

// Wired-up callback that runs one of two runnables depending on which mouse button
// the player used. Either handler may be null to make that button a no-op.
public class ClickAction implements InventoryAction {

    private final Runnable onLeftClick;
    private final Runnable onRightClick;

    public ClickAction(Runnable onLeftClick, Runnable onRightClick) {
        this.onLeftClick = onLeftClick;
        this.onRightClick = onRightClick;
    }

    public ClickAction(Runnable onLeftClick) {
        this(onLeftClick, null);
    }

    @Override
    public void onEvent(InventoryClickEvent event) {
        if (event.getClick().isLeftClick()) {
            if (onLeftClick != null) {
                onLeftClick.run();
            }
        } else if (event.getClick().isRightClick() && onRightClick != null) {
            onRightClick.run();
        }
    }
}