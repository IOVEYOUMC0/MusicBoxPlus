package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.gui.SettingsMenuGUI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MusicEditGUI implements InventoryHolder {

    public static void cleanupSession(UUID playerUUID) {
        MusicEditTextInputManager.cleanupSession(playerUUID);
    }

    public static void cleanupAllSessions() {
        MusicEditTextInputManager.cleanupAllSessions();
    }

    private static int getDefaultMaxPitch() {
        return MusicBox.getInstance().getConfigObject().getEditor().getDefaultMaxPitch();
    }

    private static int getExtendedMaxPitch() {
        return MusicBox.getInstance().getConfigObject().getEditor().getExtendedMaxPitch();
    }

    private static int getMinBpm() {
        return MusicBox.getInstance().getConfigObject().getEditor().getMinBpm();
    }

    private static int getMaxBpm() {
        return MusicBox.getInstance().getConfigObject().getEditor().getMaxBpm();
    }

    private static int getBpmStep() {
        return MusicBox.getInstance().getConfigObject().getEditor().getBpmStep();
    }

    private static int getInstrumentsPerPage() {
        return MusicBox.getInstance().getConfigObject().getEditor().getInstrumentsPerPage();
    }

    private static boolean isEditorIncrementalUpdateEnabled() {
        return MusicBox.getInstance().getConfigObject().getPerformance().isEditorIncrementalUpdate();
    }

    private final PlayerMusic music;
    private final Player player;
    private final Inventory inventory;
    private final GUIConfigManager.MusicEditorConfig config;
    private final int maxPitch;
    private int pitchOffset = 0;
    private int tickOffset = 0;
    private boolean isPlaying = false;
    // volatile: written by main-thread edit handlers, read by the async auto-save timer.
    private volatile boolean hasUnsavedChanges = false;
    private boolean openingSubGUI = false;
    private final List<MbTask> scheduledTaskIds = Collections.synchronizedList(new ArrayList<>());
    private MusicNote.NoteInstrument currentInstrument = MusicNote.NoteInstrument.HARP;
    private final List<Integer> editAreaSlots = new ArrayList<>();
    private final EditHistory editHistory = new EditHistory();
    private int currentPlayTick = -1;
    private Mode currentMode = Mode.EDIT;
    private MusicNote selectedNote = null;
    private int instrumentPageOffset = 0;
    private int cachedEditColumns = -1;
    private int cachedEditRows = -1;
    private int clearNoteCount = 0;
    private MbTask autoSaveTask = null;
    private MbTask queuedEditorRenderTask = null;
    private final MusicEditPreviewHighlighter previewHighlighter = new MusicEditPreviewHighlighter();
    private final MusicEditSelectionManager selectionManager = new MusicEditSelectionManager();
    private final MusicEditCloseCoordinator closeCoordinator;
    private final MusicEditClipboardCoordinator clipboardCoordinator;
    private final MusicEditSoundPlayer soundPlayer;
    private final MusicEditNoteRenderer noteRenderer;

    private static long getAutoSaveInterval() {
        return MusicBox.getInstance().getConfigObject().getEditor().getAutoSaveInterval();
    }

    public enum SelectionMode {
        NONE,
        SELECTING,
        SELECTED
    }

    public enum Mode {
        EDIT,
        INSTRUMENT_SELECT,
        CURRENT_INSTRUMENT_SELECT,
        CLEAR_CONFIRM
    }

    public static void startTextInput(Player player, PlayerMusic music, String type) {
        MusicEditTextInputManager.startTextInput(player, music, type);
    }

    public static void startTextInput(Player player, PlayerMusic music, String type, Consumer<String> submitHandler) {
        MusicEditTextInputManager.startTextInput(player, music, type, submitHandler);
    }

    public static void startTextInput(Player player, PlayerMusic music, String type, Consumer<String> submitHandler, Runnable cancelHandler) {
        MusicEditTextInputManager.startTextInput(player, music, type, submitHandler, cancelHandler);
    }

    public static MusicEditTextInputManager.TextInputData getTextInputSession(UUID playerUUID) {
        return MusicEditTextInputManager.getTextInputSession(playerUUID);
    }

    public static void restoreTextInputSession(UUID playerUUID, MusicEditTextInputManager.TextInputData data) {
        MusicEditTextInputManager.restoreTextInputSession(playerUUID, data);
    }

    public static boolean hasTextInputSession(UUID playerUUID) {
        return MusicEditTextInputManager.hasTextInputSession(playerUUID);
    }

    public static void cleanupModuleTextInputSessions() {
        MusicEditTextInputManager.cleanupModuleSessions(type -> {
            if ("shop_search".equals(type)) {
                return com.huidu.musicboxplus.MusicBox.getInstance().isPlayerMusicShopModuleEnabled();
            }
            if ("publish_description".equals(type) || "publish_price".equals(type)) {
                return com.huidu.musicboxplus.MusicBox.getInstance().isPublishModuleEnabled();
            }
            return com.huidu.musicboxplus.MusicBox.getInstance().isEditorModuleEnabled();
        });
    }


    public MusicEditGUI(PlayerMusic music, Player player) {
        this.music = music;
        this.player = player;
        this.config = GUIConfigManager.getInstance().getMusicEditorConfig();
        this.maxPitch = MusicBox.getInstance().getConfigObject().isEnable10octave() ? getExtendedMaxPitch() : getDefaultMaxPitch();
        this.currentInstrument = MusicNote.NoteInstrument.normalizeForCurrentConfig(this.currentInstrument);
        String title = config.getTitle().replace("{name}", music.getName());
        Component titleComponent = MiniMessageUtils.processComponent(title);
        this.inventory = Bukkit.createInventory(this, 6 * 9, titleComponent);
        this.closeCoordinator = new MusicEditCloseCoordinator(player, music);
        this.clipboardCoordinator = new MusicEditClipboardCoordinator(player, music);
        this.soundPlayer = new MusicEditSoundPlayer(player);
        this.noteRenderer = new MusicEditNoteRenderer(this.config, this.maxPitch, getDefaultMaxPitch());
        parseLayout();
    }

    private void parseLayout() {
        editAreaSlots.clear();
        cachedEditColumns = -1;
        cachedEditRows = -1;

        String layout = config.getLayout();
        if (layout == null || layout.isEmpty()) {
            return;
        }

        String[] lines = layout.split("\n");
        for (int row = 0; row < lines.length && row < 6; row++) {
            String line = lines[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                int slot = row * 9 + col;

                if (c == config.getEditAreaChar()) {
                    editAreaSlots.add(slot);
                }
            }
        }
    }

    public void open() {
        // Every Bukkit call here touches the player (inventory state, item give, open,
        // autosave timer) and must run on the player's own region under Folia; entityNow runs
        // inline when the caller already owns that region (click/event paths) and schedules
        // otherwise (command paths on the global region).
        Scheduler.entityNow(player, () -> {
            if (!PlayerInventoryState.hasSavedState(player.getUniqueId())) {
                PlayerInventoryState.saveState(player);
            }
            givePlayerInventoryItems();
            updateInventory();
            player.openInventory(inventory);
            startAutoSaveTask();
        });
    }
    
    private void startAutoSaveTask() {
        stopAutoSaveTask();
        long interval = getAutoSaveInterval();
        autoSaveTask = Scheduler.asyncTimer(() -> {
            if (hasUnsavedChanges) {
                // Clear before saving: an edit that arrives during the save re-sets the
                // flag and is caught next cycle, so no change is ever marked clean without
                // having been persisted. Restore the flag if the save fails.
                hasUnsavedChanges = false;
                PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
                    if (success) {
                        MessageUtils.send(player, Lang.AUTOSAVE_SAVED);
                    } else {
                        hasUnsavedChanges = true;
                        MessageUtils.send(player, Lang.AUTOSAVE_FAILED_MANUAL);
                    }
                });
            }
        }, interval * Scheduler.TICK_MILLIS, interval * Scheduler.TICK_MILLIS, TimeUnit.MILLISECONDS);
    }
    
    public void stopAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    private void closeEditorInventoryForSubGui() {
        openingSubGUI = true;
        player.closeInventory();
        Scheduler.globalLater(() -> openingSubGUI = false, 2L);
    }

    private void givePlayerInventoryItems() {
        if (currentMode == Mode.INSTRUMENT_SELECT) {
            giveInstrumentSelectItems();
            return;
        }
        
        if (currentMode == Mode.CURRENT_INSTRUMENT_SELECT) {
            giveCurrentInstrumentSelectItems();
            return;
        }
        
        if (currentMode == Mode.CLEAR_CONFIRM) {
            giveClearConfirmItems();
            return;
        }
        
        GUIConfigManager.HotbarButtonConfig playConfig = isPlaying ? config.getButton("play-stop") : config.getButton("play");
        GUIConfigManager.HotbarButtonConfig emptyConfig = config.getButton("empty");
        
        String layout = config.getLayout();
        if (layout == null || layout.isEmpty()) {
            return;
        }

        clearPlayerInventoryEditorArea();
        
        String[] lines = layout.split("\n");
        for (int row = 6; row < lines.length && row < 10; row++) {
            String line = lines[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                int layoutSlot = row * 9 + col;
                int playerSlot = layoutSlotToPlayerSlot(layoutSlot);
                
                if (playerSlot < 0 || playerSlot >= 36) continue;
                
                GUIConfigManager.HotbarButtonConfig btnConfig = null;
                String[] replacements = null;
                
                if (c == config.getButtonMapping().getOrDefault("pitch-up", 'U')) {
                    btnConfig = config.getButton("pitch-up");
                    replacements = editorStatusReplacements();
                } else if (c == config.getButtonMapping().getOrDefault("pitch-down", 'D')) {
                    btnConfig = config.getButton("pitch-down");
                    replacements = editorStatusReplacements();
                } else if (c == config.getButtonMapping().getOrDefault("tick-left", 'L')) {
                    btnConfig = config.getButton("tick-left");
                    replacements = editorStatusReplacements();
                } else if (c == config.getButtonMapping().getOrDefault("tick-right", 'R')) {
                    btnConfig = config.getButton("tick-right");
                    replacements = editorStatusReplacements();
                } else if (c == config.getButtonMapping().getOrDefault("instrument", 'I')) {
                    btnConfig = config.getButton("instrument");
                    replacements = new String[]{"{instrument}", currentInstrument.getDisplayName()};
                } else if (c == config.getButtonMapping().getOrDefault("play", 'P')) {
                    btnConfig = playConfig;
                    replacements = new String[]{"{notes}", String.valueOf(music.getNoteCount())};
                } else if (c == config.getButtonMapping().getOrDefault("save", 'S')) {
                    btnConfig = config.getButton("save");
                } else if (c == config.getButtonMapping().getOrDefault("exit", 'H')) {
                    btnConfig = config.getButton("exit");
                } else if (c == config.getButtonMapping().getOrDefault("bpm-up", 'B')) {
                    btnConfig = config.getButton("bpm-up");
                    replacements = new String[]{"{bpm}", String.valueOf(music.getBpm())};
                } else if (c == config.getButtonMapping().getOrDefault("bpm-down", 'T')) {
                    btnConfig = config.getButton("bpm-down");
                    replacements = new String[]{"{bpm}", String.valueOf(music.getBpm())};
                } else if (c == config.getButtonMapping().getOrDefault("time-signature", 'M')) {
                    btnConfig = config.getButton("time-signature");
                    replacements = new String[]{"{timeSignature}", music.getTimeSignature().toString()};
                } else if (c == config.getButtonMapping().getOrDefault("settings", 'G')) {
                    btnConfig = config.getButton("settings");
                    replacements = editorStatusReplacements();
                } else if (c == config.getButtonMapping().getOrDefault("clear-all", 'C')) {
                    btnConfig = config.getButton("clear-all");
                } else if (c == config.getButtonMapping().getOrDefault("undo", 'Y')) {
                    btnConfig = config.getButton("undo");
                    replacements = new String[]{"{canUndo}", (editHistory.canUndo() ? Lang.EDIT_AVAILABLE : Lang.EDIT_UNAVAILABLE).toString()};
                } else if (c == config.getButtonMapping().getOrDefault("redo", 'Z')) {
                    btnConfig = config.getButton("redo");
                    replacements = new String[]{"{canRedo}", (editHistory.canRedo() ? Lang.EDIT_AVAILABLE : Lang.EDIT_UNAVAILABLE).toString()};
                } else if (c == config.getButtonMapping().getOrDefault("copy", 'Q')) {
                    btnConfig = config.getButton("copy");
                    replacements = new String[]{"{selectionCount}", String.valueOf(getSelectedNotes().size())};
                } else if (c == config.getButtonMapping().getOrDefault("paste", 'J')) {
                    btnConfig = config.getButton("paste");
                    replacements = new String[]{"{clipboardSize}", String.valueOf(clipboardCoordinator.getClipboardSize())};
                } else if (c == config.getButtonMapping().getOrDefault("delete-selection", 'K')) {
                    btnConfig = config.getButton("delete-selection");
                    replacements = new String[]{"{selectionCount}", String.valueOf(getSelectedNotes().size())};
                } else if (c == config.getButtonMapping().getOrDefault("batch-instrument", 'W')) {
                    btnConfig = config.getButton("batch-instrument");
                    replacements = new String[]{"{selectionCount}", String.valueOf(getSelectedNotes().size()), "{instrument}", currentInstrument.getDisplayName()};
                } else if (c == config.getEmptyChar()) {
                    btnConfig = emptyConfig;
                }
                
                if (btnConfig != null) {
                    setPlayerInventoryItem(playerSlot, btnConfig, replacements);
                }
            }
        }
        
    }

    private String[] editorStatusReplacements() {
        return new String[]{
                "{pitchOffset}", String.valueOf(pitchOffset),
                "{pitchRange}", getCurrentPitchRange(),
                "{tickOffset}", String.valueOf(tickOffset),
                "{tickRange}", getCurrentTickRange(),
                "{bpm}", String.valueOf(music.getBpm()),
                "{timeSignature}", music.getTimeSignature().toString(),
                "{subdivision}", String.valueOf(music.getBeatSubdivision()),
                "{notes}", String.valueOf(music.getNoteCount())
        };
    }

    private String getCurrentPitchRange() {
        int rows = calculateEditRows();
        int startPitch = Math.max(0, pitchOffset);
        int endPitch = Math.min(maxPitch, startPitch + rows - 1);
        return MusicNote.getNoteName(startPitch) + " - " + MusicNote.getNoteName(endPitch);
    }

    private String getCurrentTickRange() {
        int columns = calculateEditColumns();
        int startTick = tickOffset * columns;
        int endTick = startTick + columns - 1;
        return startTick + " - " + endTick;
    }

    private void clearPlayerInventoryEditorArea() {
        for (int slot = 0; slot < 36; slot++) {
            if (!isEmptyItem(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void giveInstrumentSelectItems() {
        GUIConfigManager.InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicEditInstrumentMenuRenderer.renderInstrumentSelect(player, instrumentConfig, selectedNote, instrumentPageOffset, getInstrumentsPerPage());
    }

    private void giveCurrentInstrumentSelectItems() {
        GUIConfigManager.InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicEditInstrumentMenuRenderer.renderCurrentInstrumentSelect(player, instrumentConfig, currentInstrument, instrumentPageOffset, getInstrumentsPerPage());
    }

    private void giveClearConfirmItems() {
        player.getInventory().clear();
        
        GUIConfigManager.ClearConfirmConfig clearConfig = GUIConfigManager.getInstance().getClearConfirmConfig();
        
        int infoSlot = clearConfig.getSlotForButton("info");
        if (infoSlot < 0) infoSlot = 4;
        
        GUIConfigManager.HotbarButtonConfig infoConfig = clearConfig.getButton("info");
        if (infoConfig != null) {
            List<String> lore = new ArrayList<>();
            for (String line : infoConfig.getLore()) {
                lore.add(line.replace("{count}", String.valueOf(clearNoteCount)));
            }
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), infoConfig.getName(), lore, infoConfig.getCustomModelData());
            player.getInventory().setItem(infoSlot, infoItem);
        }

        int confirmSlot = clearConfig.getSlotForButton("confirm");
        if (confirmSlot < 0) confirmSlot = 0;
        
        GUIConfigManager.HotbarButtonConfig confirmConfig = clearConfig.getButton("confirm");
        if (confirmConfig != null) {
            ItemStack confirmItem = confirmConfig.createItem();
            player.getInventory().setItem(confirmSlot, confirmItem);
        }

        int cancelSlot = clearConfig.getSlotForButton("cancel");
        if (cancelSlot < 0) cancelSlot = 8;
        
        GUIConfigManager.HotbarButtonConfig cancelConfig = clearConfig.getButton("cancel");
        if (cancelConfig != null) {
            ItemStack cancelItem = cancelConfig.createItem();
            player.getInventory().setItem(cancelSlot, cancelItem);
        }
    }

    private int layoutSlotToPlayerSlot(int layoutSlot) {
        int row = layoutSlot / 9;
        int col = layoutSlot % 9;
        
        if (row == 9) {
            return col;
        } else if (row >= 6 && row < 9) {
            return (row - 6) * 9 + col + 9;
        }
        return -1;
    }

    private int playerSlotToLayoutSlot(int playerSlot) {
        if (playerSlot >= 0 && playerSlot < 9) {
            return 9 * 9 + playerSlot;
        } else if (playerSlot >= 9 && playerSlot < 36) {
            return 6 * 9 + (playerSlot - 9);
        }
        return -1;
    }

    private void setPlayerInventoryItem(int playerSlot, GUIConfigManager.HotbarButtonConfig btnConfig, String... replacements) {
        if (playerSlot < 0 || playerSlot >= 36 || btnConfig == null) {
            return;
        }
        ItemStack item = btnConfig.createItem();
        if (replacements != null && replacements.length >= 2) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                // Legacy round-trip for the placeholder pass: displayName()/lore() return
                // Components, but the replacements are plain text, so serialize to legacy
                // first and re-parse below, exactly what the old getDisplayName()/getLore()
                // path did.
                Component nameComponent = meta.displayName();
                String name = nameComponent == null ? null : MiniMessageUtils.toLegacyText(nameComponent);
                List<Component> loreComponents = meta.lore();
                List<String> lore = null;
                if (loreComponents != null) {
                    lore = new ArrayList<>(loreComponents.size());
                    for (Component line : loreComponents) {
                        lore.add(MiniMessageUtils.toLegacyText(line));
                    }
                }
                for (int i = 0; i < replacements.length - 1; i += 2) {
                    String placeholder = replacements[i];
                    String value = replacements[i + 1];
                    if (name != null) {
                        name = name.replace(placeholder, value);
                    }
                    if (lore != null) {
                        List<String> newLore = new ArrayList<>();
                        for (String line : lore) {
                            newLore.add(line.replace(placeholder, value));
                        }
                        lore = newLore;
                    }
                }
                meta.displayName(MiniMessageUtils.processComponent(name));
                meta.lore(MiniMessageUtils.processComponents(lore));
                item.setItemMeta(meta);
            }
        }
        setInventoryItemIfChanged(player.getInventory(), playerSlot, item);
    }

    private void updateInventory() {
        updateInventory(null);
    }

    private void updateInventory(Set<Integer> changedSlots) {
        if (!isEditorIncrementalUpdateEnabled()) {
            changedSlots = null;
        }

        int editCols = calculateEditColumns();
        int editRows = calculateEditRows();
        
        boolean needsFullUpdate = (changedSlots == null || changedSlots.isEmpty());
        
        if (needsFullUpdate) {
            for (int i = 0; i < editAreaSlots.size(); i++) {
                int slot = editAreaSlots.get(i);
                int localRow = i / editCols;
                int localCol = i % editCols;
                
                if (localRow >= editRows) continue;
                
                int pitch = localRow + pitchOffset;
                int tick = localCol + tickOffset * editCols;
                
                if (pitch > maxPitch) continue;

                MusicNote note = music.getNote(pitch, tick);
                boolean isCurrentPlayingTick = isHighlightedTick(tick);
                boolean isCurrentPlayingNote = isHighlightedNote(note, pitch, tick);
                boolean isSelected = isSlotInSelection(slot);
                ItemStack item = noteRenderer.createEditAreaItem(note, pitch, tick, isCurrentPlayingTick, isCurrentPlayingNote, isSelected);
                setInventoryItemIfChanged(inventory, slot, item);
            }
        } else if (changedSlots != null) {
            for (int slot : changedSlots) {
                int index = editAreaSlots.indexOf(slot);
                if (index < 0) continue;
                
                int localRow = index / editCols;
                int localCol = index % editCols;
                
                if (localRow >= editRows) continue;
                
                int pitch = localRow + pitchOffset;
                int tick = localCol + tickOffset * editCols;
                
                if (pitch > maxPitch) continue;

                MusicNote note = music.getNote(pitch, tick);
                boolean isCurrentPlayingTick = isHighlightedTick(tick);
                boolean isCurrentPlayingNote = isHighlightedNote(note, pitch, tick);
                boolean isSelected = isSlotInSelection(slot);
                ItemStack item = noteRenderer.createEditAreaItem(note, pitch, tick, isCurrentPlayingTick, isCurrentPlayingNote, isSelected);
                setInventoryItemIfChanged(inventory, slot, item);
            }
        }
    }

    private void setInventoryItemIfChanged(Inventory targetInventory, int slot, ItemStack item) {
        ItemStack current = targetInventory.getItem(slot);
        if (isSameItem(current, item)) {
            return;
        }
        targetInventory.setItem(slot, item);
    }

    private boolean isSameItem(ItemStack current, ItemStack next) {
        if (isEmptyItem(current) && isEmptyItem(next)) {
            return true;
        }
        if (isEmptyItem(current) || isEmptyItem(next)) {
            return false;
        }
        return current.getAmount() == next.getAmount() && current.isSimilar(next);
    }

    private boolean isEmptyItem(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private int calculateEditColumns() {
        if (cachedEditColumns >= 0) {
            return cachedEditColumns;
        }
        if (editAreaSlots.isEmpty()) {
            cachedEditColumns = 7;
            return cachedEditColumns;
        }
        
        int minCol = 9, maxCol = 0;
        for (int slot : editAreaSlots) {
            int col = slot % 9;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
        cachedEditColumns = maxCol - minCol + 1;
        return cachedEditColumns;
    }

    private int calculateEditRows() {
        if (cachedEditRows >= 0) {
            return cachedEditRows;
        }
        if (editAreaSlots.isEmpty()) {
            cachedEditRows = 6;
            return cachedEditRows;
        }
        
        int minRow = 6, maxRow = 0;
        for (int slot : editAreaSlots) {
            int row = slot / 9;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        cachedEditRows = maxRow - minRow + 1;
        return cachedEditRows;
    }

    private void scheduleEditorViewRender() {
        if (queuedEditorRenderTask != null) {
            return;
        }
        queuedEditorRenderTask = Scheduler.entity(player, () -> {
            queuedEditorRenderTask = null;
            if (!player.isOnline() || MusicEditListener.getOpenGUI(player.getUniqueId()) != this) {
                return;
            }
            givePlayerInventoryItems();
            updateInventory();
        });
    }

    private void cancelQueuedEditorRender() {
        if (queuedEditorRenderTask != null) {
            queuedEditorRenderTask.cancel();
            queuedEditorRenderTask = null;
        }
    }
    
    public void handleGUIClick(int slot, boolean isRightClick, boolean isShiftClick) {
        if (currentMode == Mode.INSTRUMENT_SELECT) {
            return;
        }
        if (editAreaSlots.contains(slot)) {
            handleEditAreaClick(slot, isRightClick, isShiftClick);
        }
    }

    public void handleInventoryClick(int slot, boolean isRightClick, boolean isShiftClick) {
        if (currentMode == Mode.INSTRUMENT_SELECT) {
            handleInstrumentSelectClick(slot);
            return;
        }
        
        if (currentMode == Mode.CURRENT_INSTRUMENT_SELECT) {
            handleCurrentInstrumentSelectClick(slot);
            return;
        }
        
        if (currentMode == Mode.CLEAR_CONFIRM) {
            handleClearConfirmClick(slot);
            return;
        }
        
        char c = getCharAtInventorySlot(slot);
        
        if (c == config.getButtonMapping().getOrDefault("pitch-up", 'U')) {
            if (pitchOffset < maxPitch - calculateEditRows() + 1) {
                pitchOffset++;
                scheduleEditorViewRender();
            }
        } else if (c == config.getButtonMapping().getOrDefault("pitch-down", 'D')) {
            if (pitchOffset > 0) {
                pitchOffset--;
                scheduleEditorViewRender();
            }
        } else if (c == config.getButtonMapping().getOrDefault("tick-left", 'L')) {
            if (tickOffset > 0) {
                tickOffset--;
                scheduleEditorViewRender();
            }
        } else if (c == config.getButtonMapping().getOrDefault("tick-right", 'R')) {
            tickOffset++;
            scheduleEditorViewRender();
        } else if (c == config.getButtonMapping().getOrDefault("instrument", 'I')) {
            currentMode = Mode.CURRENT_INSTRUMENT_SELECT;
            instrumentPageOffset = 0;
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("play", 'P')) {
            if (isPlaying) {
                stopMusic();
            } else {
                playMusic();
            }
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("save", 'S')) {
            saveMusic();
        } else if (c == config.getButtonMapping().getOrDefault("exit", 'H')) {
            close();
        } else if (c == config.getButtonMapping().getOrDefault("bpm-up", 'B')) {
            if (isRightClick) {
                decreaseBpm();
            } else {
                increaseBpm();
            }
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("bpm-down", 'T')) {
            if (isRightClick) {
                increaseBpm();
            } else {
                decreaseBpm();
            }
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("time-signature", 'M')) {
            cycleTimeSignature();
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("settings", 'G')) {
            closeEditorInventoryForSubGui();
            openSettingsMenu();
        } else if (c == config.getButtonMapping().getOrDefault("clear-all", 'C')) {
            clearAllNotes();
            givePlayerInventoryItems();
            updateInventory();
        } else if (c == config.getButtonMapping().getOrDefault("undo", 'Y')) {
            undo();
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("redo", 'Z')) {
            redo();
            givePlayerInventoryItems();
        } else if (c == config.getButtonMapping().getOrDefault("copy", 'Q')) {
            copySelection();
        } else if (c == config.getButtonMapping().getOrDefault("paste", 'J')) {
            int centerPitch = pitchOffset + calculateEditRows() / 2;
            int centerTick = tickOffset * calculateEditColumns() + calculateEditColumns() / 2;
            pasteToPosition(centerPitch, centerTick);
        } else if (c == config.getButtonMapping().getOrDefault("batch-instrument", 'W')) {
            batchChangeInstrument();
        } else if (c == config.getButtonMapping().getOrDefault("delete-selection", 'K')) {
            deleteSelection();
            givePlayerInventoryItems();
        }
    }

    private void handleEditAreaClick(int slot, boolean isRightClick, boolean isShiftClick) {
        int index = editAreaSlots.indexOf(slot);
        if (index < 0) {
            return;
        }
        
        int editCols = calculateEditColumns();
        int localRow = index / editCols;
        int localCol = index % editCols;
        
        int pitch = pitchOffset + localRow;
        int tick = localCol + tickOffset * editCols;
        
        if (pitch > maxPitch) {
            return;
        }
        
        MusicNote note = music.getNote(pitch, tick);
        boolean isExtendedOctave = pitch > getDefaultMaxPitch();
        boolean canEditExtendedOctave = maxPitch > getDefaultMaxPitch();

        if (isShiftClick) {
            handleNoteSelection(slot, note, pitch, tick);
            return;
        }

        if (selectionManager.isInSelectionMode()) {
            clearSelection();
            return;
        }

        if (isRightClick) {
            if (note != null) {
                editHistory.pushAction(EditAction.removeNote(note));
                music.removeNote(note);
                hasUnsavedChanges = true;
                updateInventory();
            }
        } else {
            if (isExtendedOctave && !canEditExtendedOctave) {
                MessageUtils.send(player, Lang.EXTENDED_OCTAVE_DISABLED);
                MessageUtils.send(player, Lang.EXTENDED_OCTAVE_RANGE, "{note}", MusicNote.getNoteName(pitch));
                MessageUtils.send(player, Lang.EXTENDED_OCTAVE_CONFIG);
                return;
            }
            
            if (note == null) {
                note = new MusicNote(pitch, tick);
                note.addInstrument(currentInstrument);
                music.addNote(note);
                editHistory.pushAction(EditAction.addNote(note));
                hasUnsavedChanges = true;
                updateInventory();
                playNoteSound(pitch, currentInstrument);
            } else {
                if (note.getPitch() > getDefaultMaxPitch() && !canEditExtendedOctave) {
                    MessageUtils.send(player, Lang.EDIT_NOTE_EXTENDED_OCTAVE_ONLY_DELETE);
                    return;
                }
                selectedNote = note;
                currentMode = Mode.INSTRUMENT_SELECT;
                givePlayerInventoryItems();
            }
        }
    }

    private void handleNoteSelection(int slot, MusicNote note, int pitch, int tick) {
        selectionManager.beginOrExpandSelection(slot);
        updateInventory();
    }

    private void handleInstrumentSelectClick(int slot) {
        GUIConfigManager.InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicNote.NoteInstrument[] instruments = MusicNote.NoteInstrument.getAvailableValues();
        int totalInstruments = instruments.length;
        List<Integer> instrumentSlots = getPlayerInventorySlotsForInstrumentChar(
                instrumentConfig,
                instrumentConfig.getButtonMapping().getOrDefault("instrument", 'I')
        );
        int instrumentsPerPage = Math.max(1, Math.min(getInstrumentsPerPage(), instrumentSlots.size()));
        boolean needPagination = totalInstruments > instrumentsPerPage;
        
        int prevSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "prev-page");
        int nextSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "next-page");
        int playSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "play-preview");
        int backSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "back");
        
        if (needPagination && slot == prevSlot) {
            if (instrumentPageOffset > 0) {
                instrumentPageOffset--;
                givePlayerInventoryItems();
            }
            return;
        }
        
        if (needPagination && slot == nextSlot) {
            if (instrumentPageOffset < getTotalInstrumentPages(instrumentsPerPage) - 1) {
                instrumentPageOffset++;
                givePlayerInventoryItems();
            }
            return;
        }
        
        if (slot == playSlot) {
            if (selectedNote != null) {
                if (isPlaying) {
                    stopMusic();
                }
                previewColumn(selectedNote.getPitch(), selectedNote.getTick());
            }
            return;
        }
        
        if (slot == backSlot) {
            currentMode = Mode.EDIT;
            selectedNote = null;
            instrumentPageOffset = 0;
            givePlayerInventoryItems();
            updateInventory();
            return;
        }
        
        int instrumentIndex = instrumentSlots.indexOf(slot);
        if (instrumentIndex >= 0 && selectedNote != null) {
            int startIndex = needPagination ? instrumentPageOffset * instrumentsPerPage : 0;
            int actualIndex = startIndex + instrumentIndex;
            
            if (actualIndex < instruments.length) {
                MusicNote.NoteInstrument instrument = instruments[actualIndex];
                if (selectedNote.getInstruments().contains(instrument)) {
                    selectedNote.removeInstrument(instrument);
                } else {
                    selectedNote.addInstrument(instrument);
                }
                hasUnsavedChanges = true;
                flashPreviewHighlight(selectedNote.getPitch(), selectedNote.getTick());
                playNoteSound(selectedNote.getPitch(), instrument);
                givePlayerInventoryItems();
                updateInventory();
            }
        }
    }
    
    private int getTotalInstrumentPages(int instrumentsPerPage) {
        return MusicEditInstrumentMenuRenderer.getTotalInstrumentPages(instrumentsPerPage);
    }

    private List<Integer> getPlayerInventorySlotsForInstrumentChar(GUIConfigManager.InstrumentSelectConfig instrumentConfig, char c) {
        return MusicEditInstrumentMenuRenderer.getPlayerInventorySlotsForInstrumentChar(instrumentConfig, c);
    }

    private int getPlayerInventorySlotForInstrumentButton(GUIConfigManager.InstrumentSelectConfig instrumentConfig, String buttonType) {
        return MusicEditInstrumentMenuRenderer.getPlayerInventorySlotForInstrumentButton(instrumentConfig, buttonType);
    }

    private void handleCurrentInstrumentSelectClick(int slot) {
        GUIConfigManager.InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicNote.NoteInstrument[] instruments = MusicNote.NoteInstrument.getAvailableValues();
        List<Integer> instrumentSlots = getPlayerInventorySlotsForInstrumentChar(
                instrumentConfig,
                instrumentConfig.getButtonMapping().getOrDefault("instrument", 'I')
        );
        int instrumentsPerPage = Math.max(1, Math.min(getInstrumentsPerPage(), instrumentSlots.size()));
        boolean needPagination = instruments.length > instrumentsPerPage;

        int backSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "back");
        if (backSlot < 0) {
            backSlot = 0;
        }

        if (slot == backSlot) {
            currentMode = Mode.EDIT;
            instrumentPageOffset = 0;
            givePlayerInventoryItems();
            updateInventory();
            return;
        }

        if (needPagination) {
            int prevSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "prev-page");
            int nextSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "next-page");
            if (slot == prevSlot && instrumentPageOffset > 0) {
                instrumentPageOffset--;
                givePlayerInventoryItems();
                return;
            }
            if (slot == nextSlot && instrumentPageOffset < getTotalInstrumentPages(instrumentsPerPage) - 1) {
                instrumentPageOffset++;
                givePlayerInventoryItems();
                return;
            }
        }

        int instrumentIndex = instrumentSlots.indexOf(slot);
        if (instrumentIndex >= 0) {
            int actualIndex = instrumentPageOffset * instrumentsPerPage + instrumentIndex;
            if (actualIndex < instruments.length) {
                MusicNote.NoteInstrument instrument = instruments[actualIndex];
                currentInstrument = instrument;
                flashPreviewHighlight(currentPitch(), tickOffset * calculateEditColumns() + (calculateEditColumns() / 2));
                playNoteSound(currentPitch(), instrument);
                givePlayerInventoryItems();
            }
        }
    }

    private void handleClearConfirmClick(int slot) {
        GUIConfigManager.ClearConfirmConfig clearConfig = GUIConfigManager.getInstance().getClearConfirmConfig();
        
        int confirmSlot = clearConfig.getSlotForButton("confirm");
        if (confirmSlot < 0) confirmSlot = 0;
        
        int cancelSlot = clearConfig.getSlotForButton("cancel");
        if (cancelSlot < 0) cancelSlot = 8;
        
        if (slot == confirmSlot) {
            List<MusicNote> allNotes = new ArrayList<>(music.getNotes());
            editHistory.pushAction(EditAction.clearAll(allNotes));
            music.clearNotes();
            PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
                if (success) {
                    hasUnsavedChanges = false;
                    currentMode = Mode.EDIT;
                    givePlayerInventoryItems();
                    updateInventory();
                    MessageUtils.send(player, Lang.EDIT_NOTES_CLEARED, "{count}", String.valueOf(clearNoteCount));
                } else {
                    hasUnsavedChanges = true;
                    MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
                }
            }));
            return;
        }
        
        if (slot == cancelSlot) {
            currentMode = Mode.EDIT;
            givePlayerInventoryItems();
            updateInventory();
            MessageUtils.send(player, Lang.EDIT_CLEAR_CANCELLED);
        }
    }

    private char getCharAtInventorySlot(int playerSlot) {
        int layoutSlot = playerSlotToLayoutSlot(playerSlot);
        if (layoutSlot < 0) {
            return ' ';
        }
        
        int row = layoutSlot / 9;
        int col = layoutSlot % 9;
        
        String layout = config.getLayout();
        if (layout == null || layout.isEmpty()) {
            return ' ';
        }
        
        String[] lines = layout.split("\n");
        if (row >= 0 && row < lines.length) {
            String line = lines[row];
            if (col < line.length()) {
                return line.charAt(col);
            }
        }
        return ' ';
    }

    public void handleHotbarClick(int slot, boolean isRightClick, boolean isShiftClick) {
        handleInventoryClick(slot, isRightClick, isShiftClick);
    }

    private int currentPitch() {
        return pitchOffset + 2;
    }

    private boolean isHighlightedTick(int tick) {
        return (isPlaying && tick == currentPlayTick) || isPreviewHighlightTick(tick);
    }

    private boolean isHighlightedNote(MusicNote note, int pitch, int tick) {
        if (note == null || !isHighlightedTick(tick)) {
            return false;
        }
        if (isPlaying) {
            return true;
        }
        return isPreviewHighlightTick(tick);
    }

    private boolean isPreviewHighlightTick(int tick) {
        return previewHighlighter.isHighlightTick(tick);
    }

    private void flashPreviewHighlight(int pitch, int tick) {
        previewHighlighter.flash(player, pitch, tick, this::updateInventory);
    }

    private void previewColumn(int pitch, int tick) {
        flashPreviewHighlight(pitch, tick);
        List<MusicNote> tickNotes = music.getTickIndexMap().get(tick);
        if (tickNotes == null || tickNotes.isEmpty()) {
            playNoteSound(pitch, currentInstrument);
            return;
        }
        for (MusicNote note : tickNotes) {
            for (MusicNote.NoteInstrument instrument : note.getInstruments()) {
                playNoteSound(note.getPitch(), instrument);
            }
        }
    }

    public void setCurrentInstrument(MusicNote.NoteInstrument instrument) {
        this.currentInstrument = MusicNote.NoteInstrument.normalizeForCurrentConfig(instrument);
    }

    public MusicNote.NoteInstrument getCurrentInstrument() {
        return currentInstrument;
    }

    private void saveMusic() {
        hasUnsavedChanges = true;
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (success) {
                hasUnsavedChanges = false;
                MessageUtils.send(player, Lang.EDIT_SAVED, "{name}", music.getName());
            } else {
                hasUnsavedChanges = true;
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            }
            givePlayerInventoryItems();
        }));
    }

    private void increaseBpm() {
        int newBpm = Math.min(getMaxBpm(), music.getBpm() + getBpmStep());
        if (newBpm == music.getBpm()) {
            return;
        }
        music.setBpm(newBpm);
        hasUnsavedChanges = true;
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (!success) {
                hasUnsavedChanges = true;
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                hasUnsavedChanges = false;
            }
            givePlayerInventoryItems();
        }));
    }

    private void decreaseBpm() {
        int newBpm = Math.max(getMinBpm(), music.getBpm() - getBpmStep());
        if (newBpm == music.getBpm()) {
            return;
        }
        music.setBpm(newBpm);
        hasUnsavedChanges = true;
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (!success) {
                hasUnsavedChanges = true;
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                hasUnsavedChanges = false;
            }
            givePlayerInventoryItems();
        }));
    }

    private void cycleTimeSignature() {
        PlayerMusic.TimeSignature[] signatures = PlayerMusic.TimeSignature.values();
        int currentIndex = 0;
        for (int i = 0; i < signatures.length; i++) {
            if (signatures[i] == music.getTimeSignature()) {
                currentIndex = i;
                break;
            }
        }
        PlayerMusic.TimeSignature newSignature = signatures[(currentIndex + 1) % signatures.length];
        music.setTimeSignature(newSignature);
        hasUnsavedChanges = true;
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (!success) {
                hasUnsavedChanges = true;
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                hasUnsavedChanges = false;
            }
            givePlayerInventoryItems();
        }));
    }

    private void openSettingsMenu() {
        SettingsMenuGUI settingsGUI = new SettingsMenuGUI(music, player, this);
        settingsGUI.open();
    }

    private void clearAllNotes() {
        int count = music.getNoteCount();
        if (count == 0) {
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_CLEAR);
            return;
        }
        
        clearNoteCount = count;
        currentMode = Mode.CLEAR_CONFIRM;
        givePlayerInventoryItems();
    }

    public void playNoteSound(int pitch, MusicNote.NoteInstrument instrument) {
        soundPlayer.playNoteSound(pitch, instrument != null ? instrument : currentInstrument);
    }

    private void playMusic() {
        playMusicFromTick(0);
    }

    private void playMusicFromTick(int startTick) {
        isPlaying = true;
        scheduledTaskIds.clear();
        currentPlayTick = -1;

        if (music.getNoteCount() == 0) {
            isPlaying = false;
            currentPlayTick = -1;
            updateInventory();
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_PLAY);
            return;
        }

        int bpm = music.getBpm();
        double tickDurationMillis = 60000.0 / (bpm * music.getBeatSubdivision());
        int editCols = calculateEditColumns();
        
        java.util.NavigableMap<Integer, List<MusicNote>> tickIndex = music.getTickIndexMap();
        Integer[] tickKeys = tickIndex.tailMap(startTick, true).keySet().toArray(new Integer[0]);
        if (tickKeys.length == 0) {
            isPlaying = false;
            currentPlayTick = -1;
            updateInventory();
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_PLAY);
            return;
        }
        
        startTimedPlayback(tickIndex, tickKeys, Math.max(0, startTick), tickDurationMillis, editCols);

        MessageUtils.send(player, Lang.EDIT_PLAYBACK_STARTED);
    }

    private void startTimedPlayback(java.util.NavigableMap<Integer, List<MusicNote>> tickIndex,
                                    Integer[] tickKeys,
                                    int startTick,
                                    double tickDurationMillis,
                                    int editCols) {
        final int[] currentIndex = {0};
        final int[] timelineTick = {startTick};
        final int endTick = tickKeys[tickKeys.length - 1];
        final double[] accumulator = {tickDurationMillis};
        final long[] lastNano = {System.nanoTime()};
        final int maxBurstPerServerTick = 32;

        MbTask task = Scheduler.entityTimer(player, () -> {
            if (!isPlaying) {
                return;
            }

            long now = System.nanoTime();
            accumulator[0] += (now - lastNano[0]) / 1_000_000.0;
            lastNano[0] = now;

            int processed = 0;
            boolean needsInventoryRefresh = false;
            boolean needsHotbarRefresh = false;
            while (accumulator[0] >= tickDurationMillis && timelineTick[0] <= endTick && processed < maxBurstPerServerTick) {
                accumulator[0] -= tickDurationMillis;
                if (currentIndex[0] < tickKeys.length && tickKeys[currentIndex[0]] == timelineTick[0]) {
                    needsHotbarRefresh |= playPlaybackTick(tickIndex, timelineTick[0], editCols);
                    currentIndex[0]++;
                } else {
                    needsHotbarRefresh |= updatePlaybackPosition(tickIndex, timelineTick[0], editCols);
                }
                needsInventoryRefresh = true;
                timelineTick[0]++;
                processed++;
            }

            if (timelineTick[0] > endTick) {
                finishPlayback();
                return;
            }

            if (needsInventoryRefresh) {
                if (needsHotbarRefresh) {
                    givePlayerInventoryItems();
                }
                updateInventory();
            }
        }, 0L, 1L);

        scheduledTaskIds.add(task);
    }

    private boolean playPlaybackTick(java.util.NavigableMap<Integer, List<MusicNote>> tickIndex, int tick, int editCols) {
        List<MusicNote> tickNotes = tickIndex.get(tick);
        boolean needsHotbarRefresh = updatePlaybackPosition(tickIndex, tick, editCols);

        if (tickNotes == null) {
            return needsHotbarRefresh;
        }
        for (MusicNote n : tickNotes) {
            for (MusicNote.NoteInstrument instrument : n.getInstruments()) {
                playNoteSound(n.getPitch(), instrument);
            }
        }
        return needsHotbarRefresh;
    }

    private boolean updatePlaybackPosition(java.util.NavigableMap<Integer, List<MusicNote>> tickIndex, int tick, int editCols) {
        currentPlayTick = tick;

        if (currentPageHasRemainingNotes(tickIndex, tick, editCols)) {
            return false;
        }

        Integer nextNoteTick = tickIndex.ceilingKey(tick);
        if (nextNoteTick == null) {
            return false;
        }

        int nextOffset = nextNoteTick / editCols;
        if (nextOffset == tickOffset) {
            return false;
        }
        tickOffset = nextOffset;
        return true;
    }

    private boolean currentPageHasRemainingNotes(java.util.NavigableMap<Integer, List<MusicNote>> tickIndex, int tick, int editCols) {
        int pageStart = tickOffset * editCols;
        int pageEnd = pageStart + editCols - 1;
        Integer nextVisibleNoteTick = tickIndex.ceilingKey(Math.max(tick, pageStart));
        return nextVisibleNoteTick != null && nextVisibleNoteTick <= pageEnd;
    }

    private void finishPlayback() {
        if (!isPlaying) {
            return;
        }
        cancelQueuedEditorRender();
        isPlaying = false;
        currentPlayTick = -1;
        for (MbTask task : scheduledTaskIds) {
            task.cancel();
        }
        scheduledTaskIds.clear();
        updateInventory();
        givePlayerInventoryItems();
    }

    public void stopMusic() {
        cancelQueuedEditorRender();
        isPlaying = false;
        currentPlayTick = -1;
        previewHighlighter.clear();
        for (MbTask task : scheduledTaskIds) {
            task.cancel();
        }
        scheduledTaskIds.clear();
        updateInventory();
        MessageUtils.send(player, Lang.EDIT_PLAYBACK_STOPPED);
    }

    public void close() {
        closeCoordinator.close(
                isPlaying,
                this::stopAutoSaveTask,
                this::stopMusic,
                () -> hasUnsavedChanges = false,
                this::finishClose
        );
    }

    public void closeAndSave() {
        closeCoordinator.closeAndSave(
                isPlaying,
                this::stopAutoSaveTask,
                this::stopMusic,
                () -> hasUnsavedChanges = false,
                () -> hasUnsavedChanges = true,
                this::finishClose,
                callback -> PlayerMusicManager.getInstance().saveMusicAsync(music, callback::complete)
        );
    }

    public void closeAfterSave() {
        finishClose();
    }

    private void finishClose() {
        cancelQueuedEditorRender();
        MusicEditListener.exitEditMode(music.getUniqueId());
        MusicEditListener.removeOpenGUI(player.getUniqueId());
        Scheduler.entity(player, () -> {
            player.closeInventory();
            PlayerInventoryState.restoreAndRemove(player);
        });
    }

    public void handleExternalCloseRequest() {
        closeCoordinator.handleExternalCloseRequest(
                openingSubGUI,
                hasUnsavedChanges,
                isPlaying,
                this::close,
                this::closeAndSave,
                this::open
        );
    }

    public PlayerMusic getMusic() {
        return music;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    public boolean isOpeningSubGUI() {
        return openingSubGUI;
    }

    public boolean isForceClose() {
        return openingSubGUI;
    }

    public void setSubGuiOpen(boolean openingSubGUI) {
        this.openingSubGUI = openingSubGUI;
    }

    public void setHasUnsavedChanges(boolean hasUnsavedChanges) {
        this.hasUnsavedChanges = hasUnsavedChanges;
    }

    public void refreshFromExternalUpdate() {
        boolean wasPlaying = isPlaying;
        isPlaying = false;
        currentPlayTick = -1;
        previewHighlighter.clear();
        for (MbTask task : scheduledTaskIds) {
            task.cancel();
        }
        scheduledTaskIds.clear();
        selectedNote = null;
        currentMode = Mode.EDIT;
        instrumentPageOffset = 0;
        selectionManager.clearSelection();
        editHistory.clear();
        hasUnsavedChanges = false;
        updateInventory();
        givePlayerInventoryItems();
        if (wasPlaying) {
            MessageUtils.send(player, Lang.EDIT_PLAYBACK_STOPPED);
        }
    }

    public void undo() {
        EditAction action = editHistory.undo();
        if (action == null) {
            return;
        }
        
        applyUndoAction(action);
        hasUnsavedChanges = true;
        updateInventory();
    }

    public void redo() {
        EditAction action = editHistory.redo();
        if (action == null) {
            return;
        }
        
        applyRedoAction(action);
        hasUnsavedChanges = true;
        updateInventory();
    }

    private void applyUndoAction(EditAction action) {
        switch (action.getType()) {
            case ADD_NOTE:
            case BATCH_ADD:
                for (EditAction.NoteData data : action.getNewNotes()) {
                    MusicNote note = music.getNote(data.getPitch(), data.getTick());
                    if (note != null) {
                        music.removeNote(note);
                    }
                }
                break;
            case REMOVE_NOTE:
            case BATCH_REMOVE, CLEAR_ALL:
                for (EditAction.NoteData data : action.getOldNotes()) {
                    MusicNote note = data.createNote();
                    music.addNote(note);
                }
                break;
            case MODIFY_NOTE:
                for (int i = 0; i < action.getOldNotes().size(); i++) {
                    EditAction.NoteData oldData = action.getOldNotes().get(i);
                    MusicNote note = music.getNote(oldData.getPitch(), oldData.getTick());
                    if (note != null) {
                        note.getInstruments().clear();
                        for (MusicNote.NoteInstrument inst : oldData.getInstruments()) {
                            note.addInstrument(inst);
                        }
                    }
                }
                break;
        }
    }

    private void applyRedoAction(EditAction action) {
        switch (action.getType()) {
            case ADD_NOTE:
            case BATCH_ADD:
                for (EditAction.NoteData data : action.getNewNotes()) {
                    MusicNote note = data.createNote();
                    music.addNote(note);
                }
                break;
            case REMOVE_NOTE:
            case BATCH_REMOVE:
                for (EditAction.NoteData data : action.getOldNotes()) {
                    MusicNote note = music.getNote(data.getPitch(), data.getTick());
                    if (note != null) {
                        music.removeNote(note);
                    }
                }
                break;
            case MODIFY_NOTE:
                for (int i = 0; i < action.getNewNotes().size(); i++) {
                    EditAction.NoteData newData = action.getNewNotes().get(i);
                    MusicNote note = music.getNote(newData.getPitch(), newData.getTick());
                    if (note != null) {
                        note.getInstruments().clear();
                        for (MusicNote.NoteInstrument inst : newData.getInstruments()) {
                            note.addInstrument(inst);
                        }
                    }
                }
                break;
            case CLEAR_ALL:
                music.clearNotes();
                break;
        }
    }

    public void copySelection() {
        clipboardCoordinator.copySelection(getSelectedNotes());
    }

    public void pasteToPosition(int targetPitch, int targetTick) {
        clipboardCoordinator.pasteToPosition(
                targetPitch,
                targetTick,
                () -> openingSubGUI = true,
                () -> openingSubGUI = false,
                () -> {
                    hasUnsavedChanges = true;
                    updateInventory();
                },
                addedNotes -> editHistory.pushAction(EditAction.batchAdd(addedNotes))
        );
    }

    public void deleteSelection() {
        List<MusicNote> selectedNotes = getSelectedNotes();
        if (selectedNotes.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_NO_SELECTION);
            return;
        }
        
        editHistory.pushAction(EditAction.batchRemove(selectedNotes));
        
        for (MusicNote note : selectedNotes) {
            music.removeNote(note);
        }
        
        hasUnsavedChanges = true;
        clearSelection();
        updateInventory();
        MessageUtils.send(player, Lang.EDIT_NOTES_DELETED_MSG, "{count}", String.valueOf(selectedNotes.size()));
    }

    public void batchChangeInstrument() {
        List<MusicNote> selectedNotes = getSelectedNotes();
        if (selectedNotes.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_NO_SELECTION);
            return;
        }
        
        editHistory.pushAction(EditAction.batchModify(selectedNotes, currentInstrument));
        
        int count = 0;
        for (MusicNote note : selectedNotes) {
            note.getInstruments().clear();
            note.addInstrument(currentInstrument);
            count++;
        }
        
        hasUnsavedChanges = true;
        updateInventory();
        givePlayerInventoryItems();
        MessageUtils.send(player, Lang.EDIT_INSTRUMENT_CHANGED, "{count}", String.valueOf(count), "{instrument}", currentInstrument.getDisplayName());
    }

    public List<MusicNote> getSelectedNotes() {
        return selectionManager.getSelectedNotes(music, editAreaSlots, calculateEditColumns(), pitchOffset, tickOffset, maxPitch);
    }

    public void startSelection(int slot) {
        selectionManager.startSelection(slot);
    }

    public void updateSelection(int slot) {
        selectionManager.updateSelection(slot);
    }

    public void endSelection(int slot) {
        selectionManager.endSelection(slot);
        updateInventory();
    }

    public void clearSelection() {
        selectionManager.clearSelection();
        updateInventory();
    }

    public boolean isInSelectionMode() {
        return selectionManager.isInSelectionMode();
    }

    public SelectionMode getSelectionMode() {
        return selectionManager.getMode();
    }

    public boolean isSlotInSelection(int slot) {
        return selectionManager.isSlotInSelection(slot, editAreaSlots, calculateEditColumns());
    }

    public EditHistory getEditHistory() {
        return editHistory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

