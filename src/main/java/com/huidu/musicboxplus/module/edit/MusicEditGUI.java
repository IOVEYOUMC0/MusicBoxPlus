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
    private volatile long changeVersion = 0;
    private boolean openingSubGUI = false;
    private final List<MbTask> scheduledTaskIds = Collections.synchronizedList(new ArrayList<>());
    private MusicNote.NoteInstrument currentInstrument = MusicNote.NoteInstrument.HARP;
    private final List<Integer> editAreaSlots = new ArrayList<>();
    // The slots currently carrying the preview flash, so clearing it repaints only those. See
    // flashPreviewHighlight.
    private final Set<Integer> previewHighlightSlots = new HashSet<>();
    private final Map<Integer, Integer> editAreaSlotIndexes = new HashMap<>();
    // Scratch set for the playback timer, which runs every server tick: reused instead of allocating
    // a fresh HashSet 20 times a second. Only read by updateInventory(Set) within the same tick.
    private final Set<Integer> playbackChangedSlots = new HashSet<>();
    private String[] layoutLines = new String[0];
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
        editAreaSlotIndexes.clear();
        layoutLines = new String[0];
        cachedEditColumns = -1;
        cachedEditRows = -1;

        String layout = config.getLayout();
        if (layout == null || layout.isEmpty()) {
            return;
        }

        String[] lines = layout.split("\n");
        layoutLines = lines;
        for (int row = 0; row < lines.length && row < 6; row++) {
            String line = lines[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                char c = line.charAt(col);
                int slot = row * 9 + col;

                if (c == config.getEditAreaChar()) {
                    editAreaSlotIndexes.put(slot, editAreaSlots.size());
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
                long version = changeVersion;
                // Clear before saving: an edit that arrives during the save re-sets the
                // flag and is caught next cycle, so no change is ever marked clean without
                // having been persisted. Restore the flag if the save fails.
                hasUnsavedChanges = false;
                PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
                    if (success) {
                        clearUnsavedChangesIfVersion(version);
                        MessageUtils.send(player, Lang.AUTOSAVE_SAVED);
                    } else {
                        restoreUnsavedChangesIfVersion(version);
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
        
        if (layoutLines.length == 0) {
            return;
        }

        clearPlayerInventoryEditorArea();
        
        for (int row = 6; row < layoutLines.length && row < 10; row++) {
            String line = layoutLines[row];
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
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), infoConfig.getName(), lore, infoConfig.getCustomModelData(), infoConfig.getItemModel(), infoConfig.getCraftEngineItem());
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
                Integer index = editAreaSlotIndexes.get(slot);
                if (index == null) continue;
                
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
        if (editAreaSlotIndexes.containsKey(slot)) {
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
        Integer index = editAreaSlotIndexes.get(slot);
        if (index == null) {
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
                markUnsavedChanges();
                // Only this cell changed: adding or removing a note does not alter how any other
                // cell renders (the playing-tick highlight depends on the tick, not on the note).
                // Passing the slot takes the incremental path in updateInventory, instead of
                // rebuilding all 54 edit-area items for one changed icon.
                updateInventory(java.util.Collections.singleton(slot));
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
                markUnsavedChanges();
                // Incremental, for the same reason as the removal above.
                updateInventory(java.util.Collections.singleton(slot));
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
                markUnsavedChanges();
                // flashPreviewHighlight renders the grid itself (its immediate onUpdate), so the
                // explicit updateInventory() that used to follow this was a second identical
                // 54-slot rebuild for the same click.
                flashPreviewHighlight(selectedNote.getPitch(), selectedNote.getTick());
                playNoteSound(selectedNote.getPitch(), instrument);
                givePlayerInventoryItems();
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
            long version = markUnsavedChanges();
            PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
                if (success) {
                    clearUnsavedChangesIfVersion(version);
                    currentMode = Mode.EDIT;
                    givePlayerInventoryItems();
                    updateInventory();
                    MessageUtils.send(player, Lang.EDIT_NOTES_CLEARED, "{count}", String.valueOf(clearNoteCount));
                } else {
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
        
        if (row < 0 || row >= layoutLines.length) {
            return ' ';
        }
        String line = layoutLines[row];
        return col < line.length() ? line.charAt(col) : ' ';
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
        // Only the previewed column is repainted, not the whole edit area twice per click.
        //
        // The flash marks a whole tick column (see isHighlightedTick), so it can change at most
        // editRows cells -- 6 at the default grid -- while this used to call the no-arg
        // updateInventory() for both the flash and its 6-tick expiry. That is 2 x 54 edit-area
        // ItemStacks with their meta copies (createEditAreaItem -> ItemUtils.createStack plus
        // MiniMessage name/lore) per instrument pick or preview click, to move a highlight on one
        // column.
        //
        // The affected set is the union of the column being flashed and the one that was flashing,
        // because the previous column has to lose its highlight. It is captured by both callbacks:
        // at expiry the highlighter has already forgotten which column it was on.
        Set<Integer> flashed = slotsInPreviewColumn(tick);
        Set<Integer> affected = new HashSet<>(this.previewHighlightSlots);
        affected.addAll(flashed);
        this.previewHighlightSlots.clear();
        this.previewHighlightSlots.addAll(flashed);
        previewHighlighter.flash(player, pitch, tick,
                () -> updateInventory(affected),
                () -> {
                    this.previewHighlightSlots.clear();
                    updateInventory(affected);
                });
    }

    // The edit-area slots in one tick's column. Mirrors the localRow/localCol/tick derivation in
    // updateInventory, including the pitch and row bounds it applies.
    private Set<Integer> slotsInPreviewColumn(int tick) {
        Set<Integer> slots = new HashSet<>();
        int editCols = calculateEditColumns();
        int editRows = calculateEditRows();
        for (int i = 0; i < editAreaSlots.size(); i++) {
            int localRow = i / editCols;
            if (localRow >= editRows) {
                continue;
            }
            int localCol = i % editCols;
            if (localCol + tickOffset * editCols != tick) {
                continue;
            }
            if (localRow + pitchOffset > maxPitch) {
                continue;
            }
            slots.add(editAreaSlots.get(i));
        }
        return slots;
    }

    private void previewColumn(int pitch, int tick) {
        flashPreviewHighlight(pitch, tick);
        // getNotesAtTick, not getTickIndexMap().get(tick): the latter copies the whole tick index
        // (a TreeMap with one node per distinct tick, up to ~1e5 on a long imported song) to read a
        // single key, on every "preview this column" click.
        List<MusicNote> tickNotes = music.getNotesAtTick(tick);
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
        long version = markUnsavedChanges();
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (success) {
                clearUnsavedChangesIfVersion(version);
                MessageUtils.send(player, Lang.EDIT_SAVED, "{name}", music.getName());
            } else {
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
        long version = markUnsavedChanges();
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                clearUnsavedChangesIfVersion(version);
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
        long version = markUnsavedChanges();
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                clearUnsavedChangesIfVersion(version);
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
        long version = markUnsavedChanges();
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player, () -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                clearUnsavedChangesIfVersion(version);
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
        
        // Playback reads the song through PlayerMusic's per-tick accessors instead of taking a copy
        // of the whole tick index first. getTickIndexMap() returns `new TreeMap<>(tickIndexMap)`, so
        // pressing play built a full copy of every note in the song on the main thread (100k notes =
        // 100k tree entries) and the loop then walked an Integer[] of every remaining tick, boxed.
        // Both are gone: the loop asks for the notes at one tick, which is a small list copy.
        int from = Math.max(0, startTick);
        if (music.firstNoteTickAtOrAfter(from) == null) {
            isPlaying = false;
            currentPlayTick = -1;
            updateInventory();
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_PLAY);
            return;
        }

        startTimedPlayback(music.lastNoteTick(), from, tickDurationMillis, editCols);

        MessageUtils.send(player, Lang.EDIT_PLAYBACK_STARTED);
    }

    private void startTimedPlayback(int endTick,
                                    int startTick,
                                    double tickDurationMillis,
                                    int editCols) {
        final int[] timelineTick = {startTick};
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
            boolean needsFullInventoryRefresh = false;
            // Reused across ticks: this runs every server tick while a song plays.
            Set<Integer> changedSlots = playbackChangedSlots;
            changedSlots.clear();
            while (accumulator[0] >= tickDurationMillis && timelineTick[0] <= endTick && processed < maxBurstPerServerTick) {
                accumulator[0] -= tickDurationMillis;
                int previousPlayTick = currentPlayTick;
                int previousTickOffset = tickOffset;
                List<MusicNote> tickNotes = music.getNotesAtTick(timelineTick[0]);
                if (!tickNotes.isEmpty()) {
                    needsHotbarRefresh |= playPlaybackTick(tickNotes, timelineTick[0], editCols);
                } else {
                    needsHotbarRefresh |= updatePlaybackPosition(timelineTick[0], editCols);
                }
                if (tickOffset != previousTickOffset) {
                    needsFullInventoryRefresh = true;
                } else {
                    addPlaybackChangedSlots(changedSlots, previousPlayTick, editCols);
                    addPlaybackChangedSlots(changedSlots, currentPlayTick, editCols);
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
                if (needsFullInventoryRefresh) {
                    updateInventory();
                } else if (!changedSlots.isEmpty()) {
                    updateInventory(changedSlots);
                }
            }
        }, 0L, 1L);

        scheduledTaskIds.add(task);
    }

    private boolean playPlaybackTick(List<MusicNote> tickNotes, int tick, int editCols) {
        boolean needsHotbarRefresh = updatePlaybackPosition(tick, editCols);

        for (MusicNote n : tickNotes) {
            for (MusicNote.NoteInstrument instrument : n.getInstruments()) {
                playNoteSound(n.getPitch(), instrument);
            }
        }
        return needsHotbarRefresh;
    }

    private boolean updatePlaybackPosition(int tick, int editCols) {
        currentPlayTick = tick;

        if (currentPageHasRemainingNotes(tick, editCols)) {
            return false;
        }

        Integer nextNoteTick = music.firstNoteTickAtOrAfter(tick);
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

    private boolean currentPageHasRemainingNotes(int tick, int editCols) {
        int pageStart = tickOffset * editCols;
        int pageEnd = pageStart + editCols - 1;
        Integer nextVisibleNoteTick = music.firstNoteTickAtOrAfter(Math.max(tick, pageStart));
        return nextVisibleNoteTick != null && nextVisibleNoteTick <= pageEnd;
    }

    private void addPlaybackChangedSlots(Set<Integer> changedSlots, int tick, int editCols) {
        if (tick < 0) {
            return;
        }
        int localCol = tick - tickOffset * editCols;
        if (localCol < 0 || localCol >= editCols) {
            return;
        }
        int editRows = calculateEditRows();
        for (int localRow = 0; localRow < editRows; localRow++) {
            int index = localRow * editCols + localCol;
            if (index < editAreaSlots.size()) {
                changedSlots.add(editAreaSlots.get(index));
            }
        }
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
                this::clearUnsavedChanges,
                this::finishClose
        );
    }

    public void closeAndSave() {
        closeCoordinator.closeAndSave(
                isPlaying,
                this::stopAutoSaveTask,
                this::stopMusic,
                this::clearUnsavedChanges,
                this::markUnsavedChanges,
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

    public long markUnsavedChanges() {
        changeVersion++;
        hasUnsavedChanges = true;
        return changeVersion;
    }

    public void clearUnsavedChangesIfVersion(long version) {
        if (changeVersion == version) {
            hasUnsavedChanges = false;
        }
    }

    private void restoreUnsavedChangesIfVersion(long version) {
        if (changeVersion == version) {
            hasUnsavedChanges = true;
        }
    }

    private void clearUnsavedChanges() {
        changeVersion++;
        hasUnsavedChanges = false;
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
        if (hasUnsavedChanges) {
            markUnsavedChanges();
        } else {
            clearUnsavedChanges();
        }
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
        clearUnsavedChanges();
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
        markUnsavedChanges();
        updateInventory();
    }

    public void redo() {
        EditAction action = editHistory.redo();
        if (action == null) {
            return;
        }
        
        applyRedoAction(action);
        markUnsavedChanges();
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
                        // getInstruments() returns a copy, so clear()+re-add would merge onto the
                        // real list instead of restoring it; setInstruments is the one that clears.
                        note.setInstruments(new ArrayList<>(oldData.getInstruments()));
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
                        note.setInstruments(new ArrayList<>(newData.getInstruments()));
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
                    markUnsavedChanges();
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
        
        markUnsavedChanges();
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
            // setInstruments, not getInstruments().clear(): getInstruments() returns a copy
            // (MusicNote:213), so clearing it cleared nothing and the old instruments stayed on the
            // note while addInstrument appended to them -- repeated clicks grew every selected
            // note's instrument list instead of replacing it. Same trap the undo path documents at
            // :1500.
            note.setInstruments(java.util.List.of(currentInstrument));
            count++;
        }
        
        markUnsavedChanges();
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
