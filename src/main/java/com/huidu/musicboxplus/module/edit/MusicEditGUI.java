package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.LogLocale;
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
import com.huidu.musicboxplus.common.config.ClearConfirmConfig;
import com.huidu.musicboxplus.common.config.HotbarButtonConfig;
import com.huidu.musicboxplus.common.config.InstrumentSelectConfig;
import com.huidu.musicboxplus.common.config.MusicEditorConfig;

public class MusicEditGUI implements InventoryHolder, MusicEditPlayback.Host, MusicEditNoteEditor.Host, MusicEditHotbarRenderer.Host, MusicEditInstrumentPicker.Host, MusicEditSessionCloser.Host {

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
    private final MusicEditorConfig config;
    private final int maxPitch;
    private int pitchOffset = 0;
    private int tickOffset = 0;
    private boolean isPlaying = false;
    // volatile: written by main-thread edit handlers, read by the async auto-save timer.
    private final MusicEditDirtyState dirtyState = new MusicEditDirtyState();
    private boolean openingSubGUI = false;
    private final List<MbTask> scheduledTaskIds = Collections.synchronizedList(new ArrayList<>());
    private MusicNote.NoteInstrument currentInstrument = MusicNote.NoteInstrument.HARP;
    // The slots currently carrying the preview flash, so clearing it repaints only those. See
    // flashPreviewHighlight.
    private final Set<Integer> previewHighlightSlots = new HashSet<>();
    // Scratch set for the playback timer, which runs every server tick: reused instead of allocating
    // a fresh HashSet 20 times a second. Only read by updateInventory(Set) within the same tick.
    private final Set<Integer> playbackChangedSlots = new HashSet<>();
    private final MusicEditLayout layout = new MusicEditLayout();
    private final MusicEditPlayback playback = new MusicEditPlayback(this);
    private MusicEditHotbarActions hotbarActions;
    private final MusicEditNoteEditor noteEditor = new MusicEditNoteEditor(this);
    private final MusicEditHotbarRenderer hotbarRenderer = new MusicEditHotbarRenderer(this);
    private final MusicEditInstrumentPicker instrumentPicker = new MusicEditInstrumentPicker(this);
    private final MusicEditSessionCloser sessionCloser = new MusicEditSessionCloser(this);
    private final EditHistory editHistory = new EditHistory();
    private int currentPlayTick = -1;
    private Mode currentMode = Mode.EDIT;
    private MusicNote selectedNote = null;
    private int instrumentPageOffset = 0;
    private int clearNoteCount = 0;
    private MbTask autoSaveTask = null;
    private MbTask queuedEditorRenderTask = null;
    private final MusicEditPreviewHighlighter previewHighlighter = new MusicEditPreviewHighlighter();
    private final MusicEditSelectionManager selectionManager = new MusicEditSelectionManager();
    private final MusicEditSessionGuard sessionGuard;
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
        MusicEditGUI expected = MusicEditListener.getOpenGUI(player.getUniqueId());
        if (expected != null && expected.music == music) {
            Consumer<String> guardedSubmit = submitHandler == null ? null
                    : input -> expected.sessionGuard.runIfEditing(() -> submitHandler.accept(input));
            Runnable guardedCancel = cancelHandler == null ? null
                    : () -> expected.sessionGuard.runIfEditing(cancelHandler);
            MusicEditTextInputManager.startTextInput(player, music, type, guardedSubmit, guardedCancel);
        } else {
            MusicEditTextInputManager.startTextInput(player, music, type, submitHandler, cancelHandler);
        }
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
        this.sessionGuard = new MusicEditSessionGuard(() -> player.isOnline()
                && MusicEditListener.isCurrentSession(player.getUniqueId(), this));
        this.closeCoordinator = new MusicEditCloseCoordinator(player, music, sessionGuard);
        this.clipboardCoordinator = new MusicEditClipboardCoordinator(player, music);
        this.soundPlayer = new MusicEditSoundPlayer(player);
        this.noteRenderer = new MusicEditNoteRenderer(this.config, this.maxPitch, getDefaultMaxPitch());
        parseLayout();
    }

    private void parseLayout() {
        layout.parse(config);
        // Rebuilt with the layout: both the renderer and the click handler read this table, so a
        // config reload cannot leave them disagreeing about which character is which button.
        this.hotbarActions = MusicEditHotbarActions.create(config.getButtonMapping());
        java.util.List<Character> duplicates = hotbarActions.duplicateChars(config.getButtonMapping());
        if (!duplicates.isEmpty()) {
            MusicBox.getInstance().getLogger().warning(LogLocale.text(MusicBox.getInstance(),
                    "gui-config.yml maps more than one editor button to the same character: " + duplicates
                            + "; only the first one can be clicked.",
                    "gui-config.yml 中多个编辑按钮映射到相同字符: " + duplicates + "；只有第一个能被点击。"));
        }
    }

    public void open() {
        // Every Bukkit call here touches the player (inventory state, item give, open,
        // autosave timer) and must run on the player's own region under Folia; entityNow runs
        // inline when the caller already owns that region (click/event paths) and schedules
        // otherwise (command paths on the global region).
        Scheduler.entityNow(player, () -> sessionGuard.runIfEditing(() -> {
            try {
                openingSubGUI = false;
                if (!PlayerInventoryState.hasSavedState(player.getUniqueId())) {
                    PlayerInventoryState.saveState(player);
                }
                givePlayerInventoryItems();
                updateInventory();
                player.openInventory(inventory);
                if (sessionGuard.canEdit()) startAutoSaveTask();
            } catch (RuntimeException exception) {
                abortFailedOpen();
                throw exception;
            }
        }));
    }

    private void startAutoSaveTask() {
        stopAutoSaveTask();
        long interval = getAutoSaveInterval();
        autoSaveTask = Scheduler.asyncTimer(() -> Scheduler.entity(player, () -> {
            if (sessionGuard.canEdit() && dirtyState.isDirty()) {
                long version = dirtyState.version();
                // Keep dirty until completion, including while an exit prompt is being opened.
                saveForEditingSession(success -> {
                    if (success) {
                        clearUnsavedChangesIfVersion(version);
                        MessageUtils.send(player, Lang.AUTOSAVE_SAVED);
                    } else {
                        restoreUnsavedChangesIfVersion(version);
                        MessageUtils.send(player, Lang.AUTOSAVE_FAILED_MANUAL);
                    }
                });
            }
        }), interval * Scheduler.TICK_MILLIS, interval * Scheduler.TICK_MILLIS, TimeUnit.MILLISECONDS);
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
        Scheduler.entityLater(player, () -> {
            if (MusicEditListener.isCurrentSession(player.getUniqueId(), this)) openingSubGUI = false;
        }, 2L);
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

        hotbarRenderer.render();
    }

    private String[] editorStatusReplacements() {
        return MusicEditStatus.replacements(
                pitchOffset, calculateEditRows(), maxPitch,
                tickOffset, calculateEditColumns(),
                music.getBpm(), music.getTimeSignature().toString(),
                music.getBeatSubdivision(), music.getNoteCount());
    }

    private String getCurrentPitchRange() {
        return MusicEditStatus.pitchRange(pitchOffset, calculateEditRows(), maxPitch);
    }

    private String getCurrentTickRange() {
        return MusicEditStatus.tickRange(tickOffset, calculateEditColumns());
    }
    // --- MusicEditHotbarRenderer.Host: the toolbar as one render pass sees it -----------------

    @Override
    public com.huidu.musicboxplus.common.config.MusicEditorConfig editorConfig() {
        return config;
    }

    @Override
    public MusicEditLayout editLayout() {
        return layout;
    }

    @Override
    public MusicEditHotbarActions hotbarActions() {
        return hotbarActions;
    }

    @Override
    public boolean playbackActive() {
        return isPlaying;
    }

    @Override
    public String[] statusReplacements() {
        return editorStatusReplacements();
    }

    @Override
    public int clipboardSize() {
        return clipboardCoordinator.getClipboardSize();
    }

    @Override
    public boolean canUndo() {
        return editHistory.canUndo();
    }

    @Override
    public boolean canRedo() {
        return editHistory.canRedo();
    }

    @Override
    public int selectionCount() {
        return getSelectedNotes().size();
    }

    @Override
    public void paintButton(int playerSlot, com.huidu.musicboxplus.common.config.HotbarButtonConfig button, String[] replacements) {
        setPlayerInventoryItem(playerSlot, button, replacements);
    }

    @Override
    public boolean slotBlank(ItemStack item) {
        return isEmptyItem(item);
    }

    private void giveInstrumentSelectItems() {
        InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicEditInstrumentMenuRenderer.renderInstrumentSelect(player, instrumentConfig, selectedNote, instrumentPageOffset, getInstrumentsPerPage());
    }

    private void giveCurrentInstrumentSelectItems() {
        InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
        MusicEditInstrumentMenuRenderer.renderCurrentInstrumentSelect(player, instrumentConfig, currentInstrument, instrumentPageOffset, getInstrumentsPerPage());
    }

    private void giveClearConfirmItems() {
        player.getInventory().clear();

        ClearConfirmConfig clearConfig = GUIConfigManager.getInstance().getClearConfirmConfig();

        int infoSlot = clearConfig.getSlotForButton("info");
        if (infoSlot < 0) infoSlot = 4;

        HotbarButtonConfig infoConfig = clearConfig.getButton("info");
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

        HotbarButtonConfig confirmConfig = clearConfig.getButton("confirm");
        if (confirmConfig != null) {
            ItemStack confirmItem = confirmConfig.createItem();
            player.getInventory().setItem(confirmSlot, confirmItem);
        }

        int cancelSlot = clearConfig.getSlotForButton("cancel");
        if (cancelSlot < 0) cancelSlot = 8;

        HotbarButtonConfig cancelConfig = clearConfig.getButton("cancel");
        if (cancelConfig != null) {
            ItemStack cancelItem = cancelConfig.createItem();
            player.getInventory().setItem(cancelSlot, cancelItem);
        }
    }

    private int layoutSlotToPlayerSlot(int layoutSlot) {
        return MusicEditLayout.layoutSlotToPlayerSlot(layoutSlot);
    }

    private int playerSlotToLayoutSlot(int playerSlot) {
        return MusicEditLayout.playerSlotToLayoutSlot(playerSlot);
    }

    private void setPlayerInventoryItem(int playerSlot, HotbarButtonConfig btnConfig, String... replacements) {
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
        // Resolved once for this pass: the selection anchors cannot move while it runs, and the
        // per-cell test used to re-scan the edit-area list for all three slots on every cell.
        MusicEditSelectionManager.Rect selectionRect = selectionManager.selectionRect(layout.editAreaSlots(), editCols);

        boolean needsFullUpdate = (changedSlots == null || changedSlots.isEmpty());

        if (needsFullUpdate) {
            for (int i = 0; i < layout.editAreaSlots().size(); i++) {
                int slot = layout.editAreaSlots().get(i);
                int localRow = i / editCols;
                int localCol = i % editCols;

                if (localRow >= editRows) continue;

                int pitch = localRow + pitchOffset;
                int tick = localCol + tickOffset * editCols;

                if (pitch > maxPitch) continue;

                MusicNote note = music.getNote(pitch, tick);
                boolean isCurrentPlayingTick = isHighlightedTick(tick);
                boolean isCurrentPlayingNote = isHighlightedNote(note, pitch, tick);
                boolean isSelected = MusicEditSelectionManager.isIndexInSelection(i, editCols, selectionRect);
                ItemStack item = noteRenderer.createEditAreaItem(note, pitch, tick, isCurrentPlayingTick, isCurrentPlayingNote, isSelected);
                setInventoryItemIfChanged(inventory, slot, item);
            }
        } else if (changedSlots != null) {
            // Per-tick playback repaints only the columns the playhead moved between: at 20 Hz that is
            // at most two columns (12 slots by default), each rebuilt from the note and the current
            // highlight state.
            //
            // Reviewed for caching the rendered ItemStack per cell state and left alone: the empty
            // and note cells carry their own pitch and tick in the name and lore, so a cache is one
            // entry per cell (54 of them) times the highlight/selection states, invalidated on every
            // config or alias reload -- and the absolute cost is small (12 items per server tick,
            // tens of microseconds each), the same order that the review's own progress-bar finding
            // was judged not worth the risk for.
            for (int slot : changedSlots) {
                Integer index = layout.indexOf(slot);
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
                boolean isSelected = MusicEditSelectionManager.isIndexInSelection(index, editCols, selectionRect);
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
        return layout.columns();
    }

    private int calculateEditRows() {
        return layout.rows();
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
        if (!sessionGuard.canEdit()) return;
        if (currentMode == Mode.INSTRUMENT_SELECT) {
            return;
        }
        if (layout.isEditAreaSlot(slot)) {
            handleEditAreaClick(slot, isRightClick, isShiftClick);
        }
    }

    public void handleInventoryClick(int slot, boolean isRightClick, boolean isShiftClick) {
        if (!sessionGuard.canEdit()) return;
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

        MusicEditHotbarActions.Action action = hotbarActions.actionFor(getCharAtInventorySlot(slot));
        if (action == null) {
            return;
        }
        switch (action) {
            case PITCH_UP -> {
                if (pitchOffset < maxPitch - calculateEditRows() + 1) {
                    pitchOffset++;
                    scheduleEditorViewRender();
                }
            }
            case PITCH_DOWN -> {
                if (pitchOffset > 0) {
                    pitchOffset--;
                    scheduleEditorViewRender();
                }
            }
            case TICK_LEFT -> {
                if (tickOffset > 0) {
                    tickOffset--;
                    scheduleEditorViewRender();
                }
            }
            case TICK_RIGHT -> {
                tickOffset++;
                scheduleEditorViewRender();
            }
            case INSTRUMENT -> {
                currentMode = Mode.CURRENT_INSTRUMENT_SELECT;
                instrumentPageOffset = 0;
                givePlayerInventoryItems();
            }
            case PLAY -> {
                if (isPlaying) {
                    stopMusic();
                } else {
                    playMusic();
                }
                givePlayerInventoryItems();
            }
            case SAVE -> saveMusic();
            // Same path as closing the window: with unsaved edits (or while playing) this opens the
            // save/discard/cancel prompt instead of silently dropping the changes. close() was meant
            // for the confirmed paths only -- it clears the unsaved marker on the way out.
            case EXIT -> handleExternalCloseRequest();
            case BPM_UP -> {
                if (isRightClick) {
                    decreaseBpm();
                } else {
                    increaseBpm();
                }
                givePlayerInventoryItems();
            }
            case BPM_DOWN -> {
                if (isRightClick) {
                    increaseBpm();
                } else {
                    decreaseBpm();
                }
                givePlayerInventoryItems();
            }
            case TIME_SIGNATURE -> {
                cycleTimeSignature();
                givePlayerInventoryItems();
            }
            case SETTINGS -> {
                closeEditorInventoryForSubGui();
                openSettingsMenu();
            }
            case CLEAR_ALL -> {
                clearAllNotes();
                givePlayerInventoryItems();
                updateInventory();
            }
            case UNDO -> {
                undo();
                givePlayerInventoryItems();
            }
            case REDO -> {
                redo();
                givePlayerInventoryItems();
            }
            case COPY -> copySelection();
            case PASTE -> {
                int centerPitch = pitchOffset + calculateEditRows() / 2;
                int centerTick = tickOffset * calculateEditColumns() + calculateEditColumns() / 2;
                pasteToPosition(centerPitch, centerTick);
            }
            case BATCH_INSTRUMENT -> batchChangeInstrument();
            case DELETE_SELECTION -> {
                deleteSelection();
                givePlayerInventoryItems();
            }
        }
    }

    private void handleEditAreaClick(int slot, boolean isRightClick, boolean isShiftClick) {
        Integer index = layout.indexOf(slot);
        if (index == null) {
            return;
        }
        noteEditor.click(slot, index, isRightClick, isShiftClick);
    }

    private void handleNoteSelection(int slot, MusicNote note, int pitch, int tick) {
        selectionManager.beginOrExpandSelection(slot);
        updateInventory();
    }

    private void handleInstrumentSelectClick(int slot) {
        instrumentPicker.click(slot);
    }
    private int getTotalInstrumentPages(int instrumentsPerPage) {
        return MusicEditInstrumentPages.totalPages(
                MusicNote.NoteInstrument.getAvailableCount(), instrumentsPerPage);
    }

    private List<Integer> getPlayerInventorySlotsForInstrumentChar(InstrumentSelectConfig instrumentConfig, char c) {
        return MusicEditInstrumentMenuRenderer.getPlayerInventorySlotsForInstrumentChar(instrumentConfig, c);
    }

    private int getPlayerInventorySlotForInstrumentButton(InstrumentSelectConfig instrumentConfig, String buttonType) {
        return MusicEditInstrumentMenuRenderer.getPlayerInventorySlotForInstrumentButton(instrumentConfig, buttonType);
    }

    private void handleCurrentInstrumentSelectClick(int slot) {
        InstrumentSelectConfig instrumentConfig = GUIConfigManager.getInstance().getInstrumentSelectConfig();
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
        ClearConfirmConfig clearConfig = GUIConfigManager.getInstance().getClearConfirmConfig();

        int confirmSlot = clearConfig.getSlotForButton("confirm");
        if (confirmSlot < 0) confirmSlot = 0;

        int cancelSlot = clearConfig.getSlotForButton("cancel");
        if (cancelSlot < 0) cancelSlot = 8;

        if (slot == confirmSlot) {
            // Claim the click before the asynchronous save. The switch back to EDIT only happened in
            // the callback, so a second click on the same slot while the first save was in flight
            // pushed a second EditAction.clearAll -- an undo step that restores nothing -- and
            // rewrote the song again.
            if (currentMode != Mode.CLEAR_CONFIRM) {
                return;
            }
            currentMode = Mode.EDIT;
            List<MusicNote> allNotes = new ArrayList<>(music.getNotes());
            editHistory.pushAction(EditAction.clearAll(allNotes));
            music.clearNotes();
            long version = markUnsavedChanges();
            // Repaint immediately when the mode changes, not only after a successful save.
            // On failure the cleared notes remain undoable/dirty and the toolbar must be EDIT.
            givePlayerInventoryItems();
            updateInventory();
            int removedCount = clearNoteCount;
            saveForEditingSession(success -> {
                if (success) {
                    clearUnsavedChangesIfVersion(version);
                    MessageUtils.send(player, Lang.EDIT_NOTES_CLEARED, "{count}", String.valueOf(removedCount));
                } else {
                    MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
                }
            });
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
        return layout.charAtPlayerSlot(playerSlot);
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

    // The edit-area slots in one tick's column, from the grid arithmetic that owns that mapping.
    private Set<Integer> slotsInPreviewColumn(int tick) {
        return MusicEditGrid.columnSlots(layout.editAreaSlots(), tick,
                calculateEditColumns(), calculateEditRows(), pitchOffset, tickOffset, maxPitch);
    }

    @Override
    public void flashCell(int pitch, int tick) {
        flashPreviewHighlight(pitch, tick);
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

    private void saveForEditingSession(Consumer<Boolean> completion) {
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> Scheduler.entity(player,
                () -> sessionGuard.runIfEditing(() -> completion.accept(success))));
    }

    private void saveMusic() {
        long version = markUnsavedChanges();
        saveForEditingSession(success -> {
            if (success) {
                clearUnsavedChangesIfVersion(version);
                MessageUtils.send(player, Lang.EDIT_SAVED, "{name}", music.getName());
            } else {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            }
            givePlayerInventoryItems();
        });
    }

    private void increaseBpm() {
        int newBpm = Math.min(getMaxBpm(), music.getBpm() + getBpmStep());
        if (newBpm == music.getBpm()) {
            return;
        }
        music.setBpm(newBpm);
        long version = markUnsavedChanges();
        saveForEditingSession(success -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                clearUnsavedChangesIfVersion(version);
            }
            givePlayerInventoryItems();
        });
    }

    private void decreaseBpm() {
        int newBpm = Math.max(getMinBpm(), music.getBpm() - getBpmStep());
        if (newBpm == music.getBpm()) {
            return;
        }
        music.setBpm(newBpm);
        long version = markUnsavedChanges();
        saveForEditingSession(success -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                clearUnsavedChangesIfVersion(version);
            }
            givePlayerInventoryItems();
        });
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
        saveForEditingSession(success -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                clearUnsavedChangesIfVersion(version);
            }
            givePlayerInventoryItems();
        });
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
        playback.play();
    }

    private void playMusicFromTick(int startTick) {
        playback.playFromTick(startTick);
    }

    public void stopMusic() {
        playback.stop();
    }

    // --- MusicEditPlayback.Host: the window as the playback clock sees it ---------------------

    @Override
    public Player player() {
        return player;
    }

    @Override
    public PlayerMusic music() {
        return music;
    }

    @Override
    public MusicEditLayout layout() {
        return layout;
    }

    @Override
    public int columns() {
        return calculateEditColumns();
    }

    @Override
    public int rows() {
        return calculateEditRows();
    }

    @Override
    public int tickOffset() {
        return tickOffset;
    }

    @Override
    public void tickOffset(int tickOffset) {
        this.tickOffset = tickOffset;
    }

    @Override
    public boolean playing() {
        return isPlaying;
    }

    @Override
    public void playing(boolean playing) {
        this.isPlaying = playing;
    }

    @Override
    public int playTick() {
        return currentPlayTick;
    }

    @Override
    public void playTick(int playTick) {
        this.currentPlayTick = playTick;
    }

    @Override
    public Set<Integer> changedSlots() {
        return playbackChangedSlots;
    }

    @Override
    public List<MbTask> scheduledTasks() {
        return scheduledTaskIds;
    }

    @Override
    public void refreshAll() {
        updateInventory();
    }

    @Override
    public void refreshSlots(Set<Integer> slots) {
        updateInventory(slots);
    }

    @Override
    public void refreshHotbar() {
        givePlayerInventoryItems();
    }

    @Override
    public void cancelQueuedRender() {
        cancelQueuedEditorRender();
    }

    @Override
    public void clearPreviewHighlight() {
        previewHighlighter.clear();
    }

    @Override
    public void playNote(int pitch, MusicNote.NoteInstrument instrument) {
        playNoteSound(pitch, instrument);
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
                this::open,
                callback -> PlayerMusicManager.getInstance().saveMusicAsync(music,
                        success -> Scheduler.entity(player, () -> callback.complete(success)))
        );
    }

    public void closeAfterSave() {
        finishClose();
    }

    private void finishClose() {
        sessionCloser.finishClose();
    }

    @Override
    public void retireSession() {
        sessionGuard.invalidate();
        stopAutoSaveTask();
        cancelQueuedEditorRender();
        previewHighlighter.clear();
        isPlaying = false;
        currentPlayTick = -1;
        for (MbTask task : scheduledTaskIds) task.cancel();
        scheduledTaskIds.clear();
    }

    void abortFailedOpen() {
        sessionCloser.abortFailedOpen();
    }

    Runnable queuedExternalCloseRequest() {
        long token = sessionGuard.closeRequestToken();
        return () -> sessionGuard.runCloseRequest(token, this::handleExternalCloseRequest);
    }

    public void handleExternalCloseRequest() {
        closeCoordinator.handleExternalCloseRequest(
                openingSubGUI,
                dirtyState.isDirty(),
                isPlaying,
                this::close,
                this::closeAndSave,
                this::open
        );
    }

    public PlayerMusic getMusic() {
        return music;
    }

    // The editing player. Needed by callers that must reach that player's own region before touching
    // them -- the shutdown chain in particular (see MusicEditListener.restoreAllPending).
    Player getPlayer() {
        return player;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean hasUnsavedChanges() {
        return dirtyState.isDirty();
    }

    public long markUnsavedChanges() {
        return dirtyState.markChanged();
    }

    public void clearUnsavedChangesIfVersion(long version) {
        dirtyState.clearIfUnchangedSince(version);
    }

    private void restoreUnsavedChangesIfVersion(long version) {
        dirtyState.restoreIfUnchangedSince(version);
    }

    private void clearUnsavedChanges() {
        // Unconditional: this path discards the edits, so there is nothing to compare against. The
        // version still advances so a completion from before the discard cannot clear a later edit.
        dirtyState.markChanged();
        dirtyState.clear();
    }

    public boolean isOpeningSubGUI() {
        return openingSubGUI;
    }

    public boolean isForceClose() {
        return openingSubGUI || sessionGuard.isTransitioning();
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
        return selectionManager.getSelectedNotes(music, layout.editAreaSlots(), calculateEditColumns(), pitchOffset, tickOffset, maxPitch);
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
        return selectionManager.isSlotInSelection(slot, layout.editAreaSlots(), calculateEditColumns());
    }

    public EditHistory getEditHistory() {
        return editHistory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // --- MusicEditNoteEditor.Host: the song grid as one click sees it -------------------------
    //
    // Names differ from the window's own public API on purpose: `clearSelection`, `markUnsavedChanges`
    // and `playNoteSound` already exist here with their own contracts, and overloading them to mean
    // "do it for the click handler" would make two callers of the same name behave differently.

    @Override
    public int pitchOffset() {
        return pitchOffset;
    }

    @Override
    public int maxPitch() {
        return maxPitch;
    }

    @Override
    public int defaultMaxPitch() {
        return getDefaultMaxPitch();
    }

    @Override
    public MusicNote.NoteInstrument currentInstrument() {
        return currentInstrument;
    }

    @Override
    public void selectNote(MusicNote note) {
        this.selectedNote = note;
    }

    @Override
    public void enterInstrumentSelectMode() {
        this.currentMode = Mode.INSTRUMENT_SELECT;
    }

    @Override
    public void beginOrExpandSelection(int slot) {
        selectionManager.beginOrExpandSelection(slot);
    }

    @Override
    public void pushEditAction(EditAction action) {
        editHistory.pushAction(action);
    }

    @Override
    public void markEdited() {
        markUnsavedChanges();
    }

    @Override
    public boolean selectionActive() {
        return selectionManager.isInSelectionMode();
    }

    @Override
    public void dropSelection() {
        clearSelection();
    }

    @Override
    public void previewNote(int pitch, MusicNote.NoteInstrument instrument) {
        playNoteSound(pitch, instrument);
    }

    // --- MusicEditInstrumentPicker.Host: the picker as one click sees it ----------------------

    @Override
    public MusicNote selectedNote() {
        return selectedNote;
    }

    @Override
    public int instrumentPageOffset() {
        return instrumentPageOffset;
    }

    @Override
    public void instrumentPageOffset(int offset) {
        this.instrumentPageOffset = offset;
    }

    @Override
    public int instrumentsPerPage() {
        return getInstrumentsPerPage();
    }

    @Override
    public void stopPlayback() {
        stopMusic();
    }

    @Override
    public void previewNoteColumn(int pitch, int tick) {
        previewColumn(pitch, tick);
    }

    @Override
    public void enterEditMode() {
        this.currentMode = Mode.EDIT;
    }

    @Override
    public void clearSelectedNote() {
        this.selectedNote = null;
    }

    @Override
    public void paintHotbar() {
        givePlayerInventoryItems();
    }

    @Override
    public void refreshGrid() {
        updateInventory();
    }

    // --- MusicEditSessionCloser.Host: ending a session as the closer sees it ------------------

    @Override
    public boolean beginClose() {
        return sessionGuard.beginClose();
    }

    @Override
    public boolean isCurrentSession() {
        return MusicEditListener.isCurrentSession(player.getUniqueId(), this);
    }

    @Override
    public void closeInventory() {
        player.closeInventory();
    }

    @Override
    public void restoreInventory() {
        PlayerInventoryState.restoreAndRemove(player);
    }

    @Override
    public void exitEditMode() {
        MusicEditListener.exitEditMode(player.getUniqueId(), music.getUniqueId(), this);
    }
}
