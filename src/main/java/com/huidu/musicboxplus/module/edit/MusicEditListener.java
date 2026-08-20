package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.gui.*;
import com.huidu.musicboxplus.module.gui.DeleteConfirmGUI;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class MusicEditListener implements Listener {

    private static final Map<UUID, MusicEditGUI> openGUIs = new ConcurrentHashMap<>();
    private static final Set<UUID> warnedPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, UUID> musicLocks = new ConcurrentHashMap<>();
    private static MusicEditListener listener;
    private static MbTask cleanupTask;
    
    private interface GUIHandler {
        void handleClick(InventoryHolder holder, int rawSlot, boolean isRightClick, boolean isShiftClick);
    }
    
    private static final Map<Class<? extends InventoryHolder>, GUIHandler> GUI_HANDLERS = new ConcurrentHashMap<>();
    
    static {
        GUI_HANDLERS.put(MusicSelectGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            MusicSelectGUI gui = (MusicSelectGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot);
            }
        });
        
        GUI_HANDLERS.put(PlayerMusicShopGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            PlayerMusicShopGUI gui = (PlayerMusicShopGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot, isShiftClick);
            }
        });
        
        GUI_HANDLERS.put(PublishGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            PublishGUI gui = (PublishGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot);
            }
        });
        
        GUI_HANDLERS.put(PublishConfirmGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            PublishConfirmGUI gui = (PublishConfirmGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot, isRightClick, isShiftClick);
            }
        });
        
        GUI_HANDLERS.put(ManagePublishedGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            ManagePublishedGUI gui = (ManagePublishedGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot, isShiftClick);
            }
        });
        
        GUI_HANDLERS.put(PublishReviewGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            PublishReviewGUI gui = (PublishReviewGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot, isRightClick);
            }
        });
        
        GUI_HANDLERS.put(PurchaseConfirmGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            PurchaseConfirmGUI gui = (PurchaseConfirmGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot);
            }
        });

        GUI_HANDLERS.put(DeleteConfirmGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            DeleteConfirmGUI gui = (DeleteConfirmGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot);
            }
        });
        
        GUI_HANDLERS.put(ExitConfirmGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            ExitConfirmGUI gui = (ExitConfirmGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot);
            }
        });
        
        GUI_HANDLERS.put(PastePreviewGUI.class, (holder, rawSlot, isRightClick, isShiftClick) -> {
            PastePreviewGUI gui = (PastePreviewGUI) holder;
            if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                gui.handleClick(rawSlot);
            }
        });
    }

    public static boolean enterEditMode(Player player, PlayerMusic music) {
        UUID musicId = music.getUniqueId();
        UUID playerId = player.getUniqueId();
        UUID currentEditor = musicLocks.get(musicId);
        
        if (currentEditor != null && !currentEditor.equals(playerId)) {
            Player editor = Bukkit.getPlayer(currentEditor);
            String editorName = editor != null ? editor.getName() : "another player";
            MessageUtils.send(player, Lang.EDIT_LOCKED, "{player}", editorName);
            MessageUtils.send(player, Lang.EDIT_LOCKED_HINT);
            MessageUtils.send(player, Lang.EDIT_LOCKED_OFFLINE_HINT);
            return false;
        }

        MusicEditGUI previousGui = openGUIs.remove(playerId);
        if (previousGui != null) {
            previousGui.stopAutoSaveTask();
            if (previousGui.isPlaying()) {
                previousGui.stopMusic();
            }
            musicLocks.remove(previousGui.getMusic().getUniqueId());
        }

        musicLocks.put(musicId, playerId);

        MusicEditGUI gui = new MusicEditGUI(music, player);
        openGUIs.put(playerId, gui);
        gui.open();
        return true;
    }
    
    public static void exitEditMode(UUID musicId) {
        musicLocks.remove(musicId);
    }
    
    public static boolean isInEditMode(UUID playerUUID) {
        return openGUIs.containsKey(playerUUID);
    }
    
    public static MusicEditGUI getOpenGUI(UUID playerUUID) {
        return openGUIs.get(playerUUID);
    }
    
    public static void setOpenGUI(UUID playerUUID, MusicEditGUI gui) {
        openGUIs.put(playerUUID, gui);
    }
    
    public static void removeOpenGUI(UUID playerUUID) {
        openGUIs.remove(playerUUID);
    }

    public static void notifyMusicUpdated(UUID musicId) {
        if (musicId == null) {
            return;
        }
        if (!MusicBox.getInstance().isEnabled()) {
            return;
        }
        // refreshFromExternalUpdate() writes each owner's live inventory, so it must run on
        // that player's own region (Folia). openGUIs is keyed by the owner's UUID, so dispatch
        // per owner rather than iterating on a single (global) thread.
        for (Map.Entry<UUID, MusicEditGUI> entry : openGUIs.entrySet()) {
            MusicEditGUI gui = entry.getValue();
            if (gui != null && musicId.equals(gui.getMusic().getUniqueId())) {
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null && owner.isOnline()) {
                    Scheduler.entity(owner, gui::refreshFromExternalUpdate);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        UUID playerUUID = player.getUniqueId();
        InventoryHolder holder = event.getInventory().getHolder();

        GUIHandler handler = holder != null ? GUI_HANDLERS.get(holder.getClass()) : null;
        if (handler != null) {
            // Cancel FIRST, then decide whether to dispatch. A plugin-owned GUI holder must never
            // leave a click uncancelled: if the owning module was disabled mid-session (the open
            // inventory isn't force-closed), the old "return before setCancelled" let vanilla
            // inventory mechanics move the GUI's display items into the player's inventory = dupe.
            event.setCancelled(true);
            if (!canHandleModuleGui(holder)) {
                return;
            }
            int rawSlot = event.getRawSlot();
            boolean isRightClick = event.isRightClick();
            boolean isShiftClick = event.isShiftClick();
            handler.handleClick(holder, rawSlot, isRightClick, isShiftClick);
            return;
        }

        if (!MusicBox.getInstance().isEditorModuleEnabled()) {
            return;
        }
        if (isInEditMode(playerUUID)) {
            MusicEditGUI gui = getOpenGUI(playerUUID);
            if (gui != null && event.getInventory().equals(gui.getInventory())) {
                event.setCancelled(true);
                int rawSlot = event.getRawSlot();
                boolean isRightClick = event.isRightClick();
                boolean isShiftClick = event.isShiftClick();

                if (rawSlot >= 0 && rawSlot < gui.getInventory().getSize()) {
                    gui.handleGUIClick(rawSlot, isRightClick, isShiftClick);
                } else if (rawSlot >= gui.getInventory().getSize()) {
                    int playerSlot = event.getSlot();
                    if (playerSlot >= 0 && playerSlot < 36) {
                        gui.handleInventoryClick(playerSlot, isRightClick, isShiftClick);
                    }
                } else if (event.getClickedInventory() != null && 
                           event.getClickedInventory().equals(player.getInventory())) {
                    int playerSlot = event.getSlot();
                    if (playerSlot >= 0 && playerSlot < 36) {
                        gui.handleInventoryClick(playerSlot, isRightClick, isShiftClick);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        UUID playerUUID = player.getUniqueId();

        // Cancel drags over any registered module GUI (shop/publish/manage/...) regardless of edit
        // mode or module-enabled state, mirroring the click handler -- defense-in-depth against
        // dragging items into/out of a plugin-owned inventory.
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder != null && GUI_HANDLERS.containsKey(holder.getClass())) {
            event.setCancelled(true);
            return;
        }

        if (!MusicBox.getInstance().isEditorModuleEnabled()) {
            return;
        }
        if (isInEditMode(playerUUID)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof ExitConfirmGUI exitConfirmGUI) {
            Scheduler.entity(player, exitConfirmGUI::handleClose);
            return;
        }

        if (holder instanceof SettingsMenuGUI settingsMenuGUI) {
            Scheduler.entity(player, settingsMenuGUI::handleClose);
            return;
        }

        if (holder instanceof BPMSettingsGUI bpmSettingsGUI) {
            Scheduler.entity(player, bpmSettingsGUI::handleClose);
            return;
        }

        // Drop the static openGUIs reference as soon as the inventory closes, instead of
        // waiting for the player to quit. Without this the closed GUI lingers in the map
        // (bounded to 1 entry per uuid, but a stale Player + UI ref nonetheless) until logout.
        if (holder instanceof MusicSelectGUI) {
            MusicSelectGUI.removeOpenGUI(playerUUID);
            return;
        }
        if (holder instanceof PlayerMusicShopGUI) {
            PlayerMusicShopGUI.removeOpenGUI(playerUUID);
            return;
        }

        if (!MusicBox.getInstance().isEditorModuleEnabled()) {
            return;
        }
        if (isInEditMode(playerUUID)) {
            MusicEditGUI gui = getOpenGUI(playerUUID);
            if (gui != null && event.getInventory().equals(gui.getInventory())) {
                if (!gui.isForceClose()) {
                    Scheduler.entityLater(player, () -> {
                        if (player.isOnline() && isInEditMode(playerUUID)) {
                            gui.handleExternalCloseRequest();
                        }
                    }, 1L);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Restore a real inventory that was left swapped out by the editor when the
        // server crashed/stopped uncleanly while this player was editing. The backup
        // is loaded from disk by PlayerInventoryState.initialize() on startup.
        if (PlayerInventoryState.hasSavedState(player.getUniqueId())) {
            Scheduler.entity(player,
                    () -> PlayerInventoryState.restoreAndRemove(player));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        MusicEditGUI.cleanupSession(playerUUID);
        NoteClipboard.cleanup(playerUUID);
        GUIInputManager.getInstance().cleanup(playerUUID);
        musicLocks.entrySet().removeIf(entry -> playerUUID.equals(entry.getValue()));
        warnedPlayers.remove(playerUUID);
        // These static maps retain the GUI (and its Player reference) until explicitly
        // removed; nothing else clears them on logout.
        MusicSelectGUI.removeOpenGUI(playerUUID);
        PlayerMusicShopGUI.removeOpenGUI(playerUUID);

        if (isInEditMode(playerUUID)) {
            MusicEditGUI gui = openGUIs.remove(playerUUID);
            if (gui != null) {
                gui.stopMusic();
                gui.stopAutoSaveTask();
                musicLocks.remove(gui.getMusic().getUniqueId());
                PlayerMusicManager.getInstance().saveMusicAsync(gui.getMusic(), success -> {
                    if (!success) {
                        MusicBox.getInstance().getLogger().log(
                                Level.WARNING,
                                "Failed to save player music on quit: " + gui.getMusic().getName()
                        );
                    }
                });
                PlayerInventoryState.restoreAndRemove(player);
            }
        }
    }

    public static void register() {
        PlayerInventoryState.initialize();
        if (listener == null) {
            listener = new MusicEditListener();
            MusicBox.getInstance().getServer().getPluginManager().registerEvents(
                    listener, MusicBox.getInstance());
        }
        startCleanupTask();
    }

    public static void unregister() {
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }
    
    private static void startCleanupTask() {
        if (cleanupTask != null) {
            return;
        }
        cleanupTask = Scheduler.globalTimer(() -> {
            musicLocks.entrySet().removeIf(entry -> {
                UUID editorId = entry.getValue();
                Player editor = Bukkit.getPlayer(editorId);
                return editor == null;
            });
        }, 6000L, 6000L);
    }
    
    public static void cleanupOfflinePlayers() {
        musicLocks.entrySet().removeIf(entry -> {
            UUID editorId = entry.getValue();
            Player editor = Bukkit.getPlayer(editorId);
            return editor == null;
        });
    }

    public static void saveAllActive() {
        for (MusicEditGUI gui : openGUIs.values()) {
            if (gui.hasUnsavedChanges()) {
                PlayerMusicManager.getInstance().stagePendingSave(gui.getMusic());
            }
        }
    }

    public static void restoreAllPending() {
        PlayerInventoryState.restoreAllPending();
        for (MusicEditGUI gui : openGUIs.values()) {
            // The same teardown the quit path does. The auto-save timer is not one of the GUI's
            // scheduled tasks, so stopMusic() leaves it running -- and once openGUIs is cleared
            // below, every remaining cancel path is gated on isInEditMode() and can no longer
            // reach this GUI. The timer would then hold the player, the music and the inventory
            // for the rest of the server's life.
            gui.stopAutoSaveTask();
            if (gui.isPlaying()) {
                gui.stopMusic();
            }
        }
        openGUIs.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        this.handleTextInputChat(
                event.getPlayer(),
                PlainTextComponentSerializer.plainText().serialize(event.message()),
                event::setCancelled
        );
    }

    // Legacy chat event is deprecated, kept alongside AsyncChatEvent so servers with plugins
    // that still fire the legacy event keep working; suppression is local to the handler.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        this.handleTextInputChat(
                event.getPlayer(),
                event.getMessage(),
                event::setCancelled
        );
    }

    private void handleTextInputChat(Player player, String rawMessage, java.util.function.Consumer<Boolean> cancelAction) {
        UUID playerUUID = player.getUniqueId();

        if (!MusicEditGUI.hasTextInputSession(playerUUID)) {
            return;
        }

        MusicEditTextInputManager.TextInputData data = MusicEditGUI.getTextInputSession(playerUUID);
        if (data == null) {
            return;
        }
        if (!canHandleTextInput(data)) {
            return;
        }

        cancelAction.accept(true);

        Scheduler.entity(player, () -> processTextInput(player, data, rawMessage));
    }

    private boolean canHandleModuleGui(InventoryHolder holder) {
        MusicBox plugin = MusicBox.getInstance();
        if (holder instanceof DeleteConfirmGUI) {
            return true;
        }
        if (holder instanceof PlayerMusicShopGUI || holder instanceof PurchaseConfirmGUI) {
            return plugin.isPlayerMusicShopModuleEnabled();
        }
        if (holder instanceof PublishGUI || holder instanceof PublishConfirmGUI || holder instanceof ManagePublishedGUI
                || holder instanceof PublishReviewGUI) {
            return plugin.isPublishModuleEnabled();
        }
        return plugin.isEditorModuleEnabled();
    }

    private boolean canHandleTextInput(MusicEditTextInputManager.TextInputData data) {
        MusicBox plugin = MusicBox.getInstance();
        if ("shop_search".equals(data.type)) {
            return plugin.isPlayerMusicShopModuleEnabled();
        }
        if ("publish_description".equals(data.type) || "publish_price".equals(data.type)) {
            return plugin.isPublishModuleEnabled();
        }
        return plugin.isEditorModuleEnabled();
    }

    private void processTextInput(Player player, MusicEditTextInputManager.TextInputData data, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage;

        if (message.equalsIgnoreCase("cancel")) {
            if (data.cancelHandler != null) {
                data.cancelHandler.run();
            }
            MessageUtils.send(player, Lang.EDIT_INPUT_CANCELLED);
            return;
        }

        if (message.trim().isEmpty()) {
            MusicEditGUI.restoreTextInputSession(player.getUniqueId(), data);
            MessageUtils.send(player, Lang.EDIT_INPUT_EMPTY_RETRY);
            return;
        }

        String input = message.trim();

        if (data.submitHandler != null) {
            data.submitHandler.accept(input);
            return;
        }

        switch (data.type) {
            case "name":
                if (input.length() > 32) {
                    MessageUtils.send(player, Lang.OPERATION_FAILED);
                    return;
                }
                String safeName = sanitizeInput(input, 32);
                if (safeName.isEmpty()) {
                    MessageUtils.send(player, Lang.OPERATION_FAILED);
                    return;
                }
                data.music.setName(safeName);
                PlayerMusicManager.getInstance().saveMusicAsync(data.music, success -> Scheduler.entity(player, () -> {
                    if (success) {
                        MessageUtils.send(player, Lang.EDIT_NAME_UPDATED, "{name}", safeName);
                    } else {
                        MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
                    }
                    if (data.cancelHandler != null) {
                        data.cancelHandler.run();
                    }
                }));
                break;

            case "description":
                String safeDesc = sanitizeInput(input, 256);
                data.music.setDescription(safeDesc);
                PlayerMusicManager.getInstance().saveMusicAsync(data.music, success -> Scheduler.entity(player, () -> {
                    if (success) {
                        MessageUtils.send(player, Lang.EDIT_DESCRIPTION_UPDATED);
                    } else {
                        MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
                    }
                    if (data.cancelHandler != null) {
                        data.cancelHandler.run();
                    }
                }));
                break;

            case "shop_search":
                handleShopSearch(player, input);
                break;

            default:
                MessageUtils.send(player, Lang.OPERATION_FAILED);
        }
    }
    
    private String sanitizeInput(String input, int maxLength) {
        if (input == null) return "";
        String sanitized = input.replaceAll("[<>\"'&\\x00-\\x1f\\x7f-\\x9f\\u00a7\\r\\n]", "");
        sanitized = sanitized.replaceAll("[\\u200B-\\u200D\\uFEFF\\u200E\\u200F]", "");
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
        }
        return sanitized.trim();
    }

    private void handleShopSearch(Player player, String query) {
        Scheduler.entity(player, () -> {
            PlayerMusicShopGUI shopGUI = PlayerMusicShopGUI.getOpenGUI(player.getUniqueId());
            if (shopGUI != null) {
                shopGUI.setSearchQuery(query);
                shopGUI.open();
            } else {
                PlayerMusicShopGUI newShopGUI = new PlayerMusicShopGUI(player);
                newShopGUI.setSearchQuery(query);
                newShopGUI.open();
            }
        });
    }
}
