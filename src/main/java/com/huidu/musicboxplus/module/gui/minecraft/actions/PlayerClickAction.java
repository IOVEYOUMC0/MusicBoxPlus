package com.huidu.musicboxplus.module.gui.minecraft.actions;

import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.function.Consumer;

// Click callback that hands the clicking player to whichever consumer matches the
// mouse button used. Either consumer may be null to ignore that button.
public class PlayerClickAction implements InventoryAction {

    private final Consumer<Player> onLeftClick;
    private final Consumer<Player> onRightClick;

    public PlayerClickAction(Consumer<Player> onLeftClick, Consumer<Player> onRightClick) {
        this.onLeftClick = onLeftClick;
        this.onRightClick = onRightClick;
    }

    public PlayerClickAction(Consumer<Player> onLeftClick) {
        this(onLeftClick, null);
    }

    @Override
    public void onEvent(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (event.getClick().isLeftClick()) {
            if (onLeftClick != null) {
                onLeftClick.accept(player);
            }
        } else if (event.getClick().isRightClick() && onRightClick != null) {
            onRightClick.accept(player);
        }
    }
}