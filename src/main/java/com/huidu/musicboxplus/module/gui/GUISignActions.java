package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.ConfigManager;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.SignUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.containers.SingletonContainer;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.playlist.PlayListListGUI;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class GUISignActions {
    private GUISignActions() {
    }

    static void openSignSetupInventory(PlayerWrapper wrapper, Sign sign) {
        SongContainerGUI rootGUI = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);

        abstract class BooleanButton implements SongContainerGUI.BarButton {
            private final String key;
            protected boolean value = false;

            BooleanButton(String key) {
                this.key = key;
            }

            public String getValue() {
                return this.value ? this.key : null;
            }

            public boolean getValueStatus() {
                return this.value;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    this.value = !this.value;
                    data.refreshInventory();
                });
            }
        }

        BooleanButton randButton = new BooleanButton("R") {
            @Override
            public org.bukkit.inventory.ItemStack getItemStack(PlayerWrapper wrapper) {
                String status = this.getValueStatus() ? Lang.ENABLE.toString() : Lang.DISABLE.toString();
                org.bukkit.inventory.ItemStack item = GUIConfigManager.getInstance().createButtonItem("sign-setup", "random-mode", "{status}", status);
                if (item == null) {
                    item = ItemUtils.createStack(Material.REDSTONE, Lang.RANDOM_MODE_BUTTON.toString("{status}", status), Lang.SIGN_RANDOM_MODE_LORE.toList("{status}", status));
                }
                return item;
            }
        };

        BooleanButton infoSignButton = new BooleanButton("I") {
            @Override
            public org.bukkit.inventory.ItemStack getItemStack(PlayerWrapper wrapper) {
                String status = this.getValueStatus() ? Lang.ENABLE.toString() : Lang.DISABLE.toString();
                org.bukkit.inventory.ItemStack item = GUIConfigManager.getInstance().createButtonItem("sign-setup", "info-sign-mode", "{status}", status);
                if (item == null) {
                    item = ItemUtils.createStack(Material.OAK_SIGN, Lang.SIGN_INFO_MODE_TITLE.toString("{status}", status), Lang.SIGN_INFO_MODE_LORE.toList());
                }
                return item;
            }
        };

        BooleanButton preventDestroy = new BooleanButton("P") {
            @Override
            public org.bukkit.inventory.ItemStack getItemStack(PlayerWrapper wrapper) {
                String status = this.getValueStatus() ? Lang.ENABLE.toString() : Lang.DISABLE.toString();
                org.bukkit.inventory.ItemStack item = GUIConfigManager.getInstance().createButtonItem("sign-setup", "prevent-destroy", "{status}", status);
                if (item == null) {
                    item = ItemUtils.createStack(Material.IRON_BLOCK, Lang.CONTROL_PROTECT_STATUS.toString("{status}", status), Lang.CONTROL_PROTECT_CLICK.toList());
                }
                return item;
            }
        };

        final Supplier<String> signParams = () -> Stream.of(infoSignButton, randButton, preventDestroy).map(BooleanButton::getValue).filter(Objects::nonNull).collect(Collectors.joining("|"));
        HashMap<Character, SongContainerGUI.BarButton> buttonMap = new HashMap<>();
        GUIConfigManager.ButtonMappingConfig mapping = GUIConfigManager.getInstance().getGUIConfig("sign-setup").getButtonMapping();
        Player player = wrapper.getPlayer();
        boolean canUseProtect = player != null && (player.hasPermission(Permissions.ADMIN) || player.hasPermission(Permissions.SIGN_PROTECT));
        if (canUseProtect) {
            buttonMap.put(Character.valueOf(mapping.getPreventDestroy()), preventDestroy);
        }
        buttonMap.put(Character.valueOf(mapping.getInfoSign()), infoSignButton);
        buttonMap.put(Character.valueOf(mapping.getRandom()), randButton);
        buttonMap.put(Character.valueOf(mapping.getPlaylist()), new SongContainerGUI.BarButton() {
            @Override
            public org.bukkit.inventory.ItemStack getItemStack(PlayerWrapper wrapper) {
                org.bukkit.inventory.ItemStack item = GUIConfigManager.getInstance().createButtonItem("sign-setup", "playlist");
                if (item == null) {
                    item = ItemUtils.createStack(Material.PAPER, Lang.SIGN_PLAYLIST_EDITOR.toString(), Lang.SIGN_PLAYLIST_EDITOR_LORE.toList());
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> PlayListListGUI.openAsync(
                    wrapper,
                    container -> new ClickAction(() -> applySign(wrapper, sign, container, signParams)),
                    pl -> null,
                    () -> openSignSetupInventory(wrapper, sign)
                ));
            }
        });

        SongContainerGUI.SongGUIParams params = SongContainerGUI.SongGUIParams.builder()
                .buttonMap(buttonMap)
                .onSongLeftClick((wrapper1, musicData) -> applySign(wrapper1, sign, new SingletonContainer(musicData.getData()), signParams))
                .onContainerRightClick((wrapper12, containerData) -> applySign(wrapper12, sign, containerData.getData(), signParams))
                .build();
        rootGUI.openPage(0, params, "sign-setup");
    }

    private static void applySign(PlayerWrapper wrapper, Sign sign, SongContainer songContainer, Supplier<String> params) {
        Player player = wrapper.getPlayer();
        if (player == null) {
            // PlayerWrapper holds a WeakReference; if the player logged off between opening
            // the GUI and applying the sign, skip the close + owner registration cleanly.
            return;
        }
        // Compute the pure/read values on the (clicking) player's region thread. Reading the
        // line-2 range from the captured Sign snapshot and building the components touches no
        // live block, so it is safe here; only the world write must hop to the sign's region.
        final String songId = songContainer.getNameId();
        final Component displayText = MiniMessageUtils.parseMiniMessage(ConfigManager.getInstance().getSignDisplayText());
        String range = PlainTextComponentSerializer.plainText().serialize(SignUtils.getSignLineComponent(sign, 2)).trim();
        if (range.isEmpty()) {
            range = "24";
        } else {
            try {
                int rangeInt = Integer.parseInt(range);
                if (rangeInt > 256) {
                    rangeInt = 256;
                }
                range = String.valueOf(rangeInt);
            } catch (Exception ex) {
                range = "24";
            }
        }
        final String finalRange = range;
        final String signParams = params.get();
        final java.util.UUID ownerId = player.getUniqueId();
        // Already on the player's region thread, so closing the inventory here is safe.
        player.closeInventory();
        // All block/BlockState mutation must run on the region that owns the sign block, not the
        // player's region (they can differ on Folia). Re-fetch a fresh Sign state there, write the
        // four lines, push it to the world, and register the owner.
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(sign.getLocation(), () -> {
            Block block = sign.getLocation().getBlock();
            if (block.getState() instanceof Sign updatedSign) {
                SignUtils.setSignLine(updatedSign, 0, Component.text(songId, NamedTextColor.AQUA));
                SignUtils.setSignLine(updatedSign, 1, displayText);
                SignUtils.setSignLine(updatedSign, 2, Component.text(finalRange, NamedTextColor.RED));
                SignUtils.setSignLine(updatedSign, 3, Component.text(signParams, NamedTextColor.YELLOW));
                updatedSign.update(true);
                SignPlayer.createSignWithOwner(updatedSign, ownerId);
            }
        });
    }
}
