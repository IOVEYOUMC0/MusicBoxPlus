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

    private static final MusicEditSessionRegistry<MusicEditGUI> sessions = new MusicEditSessionRegistry<>();
    private static final Set<UUID> warnedPlayers = ConcurrentHashMap.newKeySet();
    private static MusicEditListener listener;
    private static MbTask cleanupTask;

    private interface GUIHandler {
        void handleClick(InventoryHolder holder, int rawSlot, boolean isRightClick, boolean isShiftClick);
    }

    // Populated once, from the static initialiser below. Deliberately never cleared: the initialiser
    // does not run again when the plugin is re-enabled on the same classloader (PlugMan-style), so
    // emptying it here would leave every GUI click unhandled for the rest of the JVM's life -- the
    // same failure mode the GUIInputManager registration bug had.
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

        // Construction may fail (invalid layout/items). Do it before acquiring a lease or
        // disturbing the previous session; the registry atomically rechecks admission afterwards.
        MusicEditGUI gui = new MusicEditGUI(music, player);
        MusicEditSessionRegistry.Admission<MusicEditGUI> admission = sessions.admit(playerId, musicId, gui);
        if (!admission.admitted()) {
            Player editor = Bukkit.getPlayer(admission.blockingOwner());
            String editorName = editor != null ? editor.getName() : "another player";
            MessageUtils.send(player, Lang.EDIT_LOCKED, "{player}", editorName);
            MessageUtils.send(player, Lang.EDIT_LOCKED_HINT);
            MessageUtils.send(player, Lang.EDIT_LOCKED_OFFLINE_HINT);
            return false;
        }
        MusicEditGUI previousGui = admission.previous();
        if (previousGui != null) {
            MusicEditGUI.cleanupSession(playerId);
            previousGui.retireSession();
        }
        try {
            gui.open();
            return true;
        } catch (RuntimeException exception) {
            gui.abortFailedOpen();
            throw exception;
        }
    }

    /** @deprecated Legacy integration only; asynchronous close paths must supply the session. */
    @Deprecated
    public static void exitEditMode(UUID musicId) {
        sessions.legacyReleaseMusic(musicId);
    }

    public static boolean exitEditMode(UUID owner, UUID musicId, MusicEditGUI expectedGui) {
        return sessions.release(owner, musicId, expectedGui);
    }

    static boolean isCurrentSession(UUID owner, MusicEditGUI expectedGui) {
        return sessions.isCurrent(owner, expectedGui);
    }

    public static boolean isInEditMode(UUID playerUUID) {
        return sessions.get(playerUUID) != null;
    }

    public static MusicEditGUI getOpenGUI(UUID playerUUID) {
        return sessions.get(playerUUID);
    }

    /** @deprecated Use enterEditMode so the GUI and its lock are installed together. */
    @Deprecated
    public static void setOpenGUI(UUID playerUUID, MusicEditGUI gui) {
        sessions.legacySet(playerUUID, gui);
    }

    /** @deprecated Use owner + expectedGui conditional release. */
    @Deprecated
    public static void removeOpenGUI(UUID playerUUID) {
        sessions.legacyRemove(playerUUID);
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
        for (Map.Entry<UUID, MusicEditGUI> entry : sessions.snapshot().entrySet()) {
            MusicEditGUI gui = entry.getValue();
            if (gui != null && musicId.equals(gui.getMusic().getUniqueId())) {
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null) {
                    Scheduler.entity(owner, () -> {
                        if (owner.isOnline() && isCurrentSession(entry.getKey(), gui)) {
                            gui.refreshFromExternalUpdate();
                        }
                    });
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

        // While the editor holds the player's real inventory in a backup, every item they can
        // touch is a plugin-owned button (GOLD_BLOCK/TNT/END_CRYSTAL/...) that restore() will
        // overwrite on exit -- so anything that leaves the inventory is minted from nothing.
        // The guard is the swapped-out backup, NOT "the editor window is open": renaming closes
        // the window and waits on chat input with no timeout, and the module can be disabled
        // mid-session. Both leave the player carrying buttons with no window to key off.
        if (PlayerInventoryState.hasSavedState(playerUUID)) {
            event.setCancelled(true);
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

        // A saved inventory state means the editor swapped the player's real items out for the
        // editor hotbar, so no drag may move them. This has to be checked before the module state:
        // the old order returned early when the module was disabled mid-session and let plugin
        // items be dragged out into the real inventory.
        if (PlayerInventoryState.hasSavedState(playerUUID)) {
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
                    Runnable request = gui.queuedExternalCloseRequest();
                    Scheduler.entityLater(player, () -> {
                        if (player.isOnline() && isCurrentSession(playerUUID, gui) && !gui.isForceClose()) {
                            request.run();
                        }
                    }, 1L);
                }
            }
        }
    }

    // The other three ways a plugin-owned button can leave the inventory while the editor holds
    // the backup. Same guard as onInventoryClick, and deliberately not gated on the module being
    // enabled -- the backup outlives a mid-session disable.
    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (PlayerInventoryState.hasSavedState(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerSwapHandItems(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (PlayerInventoryState.hasSavedState(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Death empties the live inventory onto the ground -- buttons, not the player's items, which
    // are safe in the backup and come back when the editor closes. Dropping them duplicates every
    // button on the corpse.
    // ponytail: clears the drops instead of substituting the backed-up contents, so dying inside
    // the editor also skips the death penalty. Swap the backup in here if that matters.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (PlayerInventoryState.hasSavedState(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
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
        warnedPlayers.remove(playerUUID);
        // These static maps retain the GUI (and its Player reference) until explicitly
        // removed; nothing else clears them on logout.
        MusicSelectGUI.removeOpenGUI(playerUUID);
        PlayerMusicShopGUI.removeOpenGUI(playerUUID);

        if (isInEditMode(playerUUID)) {
            MusicEditGUI gui = getOpenGUI(playerUUID);
            if (gui != null && exitEditMode(playerUUID, gui.getMusic().getUniqueId(), gui)) {
                gui.retireSession();
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
        // Static state that outlives the listener. openGUIs is cleared by restoreAllPending(), which
        // runs earlier in the shutdown chain; these three were simply left behind. A text-input
        // session holds the player, the song and callbacks that capture a GUI, and a soft reload kept
        // it alive: the player could still answer a prompt from the previous session and have it
        // delivered into a GUI that belongs to the old classloader. musicLocks and warnedPlayers are
        // bounded but equally session-scoped.
        MusicEditTextInputManager.cleanupAllSessions();
        sessions.clear();
        warnedPlayers.clear();
    }

    private static void startCleanupTask() {
        if (cleanupTask != null) {
            return;
        }
        cleanupTask = Scheduler.globalTimer(MusicEditListener::cleanupOfflinePlayers, 6000L, 6000L);
    }

    public static void cleanupOfflinePlayers() {
        sessions.removeOfflineLocks(owner -> Bukkit.getPlayer(owner) == null);
    }

    public static void saveAllActive() {
        for (MusicEditGUI gui : sessions.snapshot().values()) {
            if (gui.hasUnsavedChanges()) {
                PlayerMusicManager.getInstance().stagePendingSave(gui.getMusic());
            }
        }
    }

    public static void restoreAllPending() {
        Map<UUID, MusicEditGUI> retiring = sessions.snapshot();
        sessions.clear(); // Invalidate queued save/confirm callbacks before restoring any later session.
        PlayerInventoryState.restoreAllPending();
        for (MusicEditGUI gui : retiring.values()) {
            gui.stopAutoSaveTask();
            // Teardown must not repaint the hotbar after restoreAllPending restored the real items.
            Scheduler.entityNow(gui.getPlayer(), gui::retireSession);
        }
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

        MusicEditGUI expected = getOpenGUI(playerUUID);
        Scheduler.entity(player, () -> {
            if (expected != null && !isCurrentSession(playerUUID, expected)) return;
            processTextInput(player, data, rawMessage);
        });
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
            retryTextInput(player, data, Lang.EDIT_INPUT_EMPTY_RETRY);
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
                    retryTextInput(player, data, Lang.EDIT_INPUT_INVALID_RETRY);
                    return;
                }
                String safeName = sanitizeInput(input, 32);
                if (safeName.isEmpty()) {
                    retryTextInput(player, data, Lang.EDIT_INPUT_INVALID_RETRY);
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
                // Unreachable with the types this code sets, but a session that is consumed without
                // either a submit or a cancel leaves the menu closed and openingSubGUI pinned.
                if (data.cancelHandler != null) {
                    data.cancelHandler.run();
                }
        }
    }

    // Puts the prompt back instead of swallowing the input session.
    //
    // getTextInputSession already removed the session from the manager, so returning after a rejected
    // value left the player with no prompt and no way to retry. It also pinned the editor's
    // openingSubGUI flag true, because clearing it is the cancelHandler's job and that only ran on
    // submit or cancel -- which made isForceClose() true for the rest of the editor session, so
    // pressing ESC stopped restoring the player's real inventory. The empty-input branch already
    // restored the session and asked for a retry; the rejected-value branches did not.
    private void retryTextInput(Player player, MusicEditTextInputManager.TextInputData data, Lang message) {
        MusicEditGUI.restoreTextInputSession(player.getUniqueId(), data);
        MessageUtils.send(player, message);
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
