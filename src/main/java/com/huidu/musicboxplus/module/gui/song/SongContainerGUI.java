package com.huidu.musicboxplus.module.gui.song;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.classes.PeekList;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.core.song.songContainers.types.SubSongContainer;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SongContainerGUI {
    private final FullSongContainer container;
    private final PlayerWrapper wrapper;
    private final List<SongGUIItem> items = new LinkedList<SongGUIItem>();
    private GUI currentGUI;
    private int currentPage = 0;
    private SongGUIParams currentParams;
    private String currentGuiType = "song-list";

    public SongContainerGUI(FullSongContainer container, PlayerWrapper wrapper) {
        this.container = container;
        this.wrapper = wrapper;
    }

    public void refreshItems(@Nullable SongGUIParams params) {
        this.items.clear();
        this.container.getSubContainers().stream().map(SongGUIChest::new).collect(Collectors.toCollection(() -> this.items));
        Predicate<MusicBoxSong> songFilter = params != null ? params.getSongFilter() : null;
        this.container.getSongs().stream().filter(song -> songFilter == null || songFilter.test(song)).map(SongGUISong::new).collect(Collectors.toCollection(() -> this.items));
    }

    public void openPage(int page, SongGUIParams params) {
        this.openPage(page, params, "song-list");
    }

    public void openPage(int page, SongGUIParams params, String guiType) {
        if (params == null) {
            params = SongGUIParams.builder().build();
        }
        this.refreshItems(params);
        this.currentPage = page;
        this.currentParams = params;
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        guiType = guiConfig.hasGUIConfig(guiType) ? guiType : "song-list";
        this.currentGuiType = guiType;
        int pageCount = this.getPageCount(guiType);
        String title = guiConfig.getGUITitle(guiType).replace("{container}", this.container.getName()).replace("{page}", String.valueOf(page + 1)).replace("{last_page}", String.valueOf(pageCount));
        GUI gui = this.createGUI(title);
        this.currentGUI = gui;
        this.renderPage(gui, page, params, guiType);
        gui.open(this.getWrapper().getPlayer());
        this.armRecentSongsRefresh();
    }

    // The recent-history button renders only when the per-player history is non-empty, and that
    // history loads asynchronously after join. A GUI opened during the load would show no button
    // until some other click refreshed it; arm a one-shot refresh that lands when the load does.
    private void armRecentSongsRefresh() {
        SongGUIParams params = this.currentParams;
        if (params == null || params.getButtonMap() == null) {
            return;
        }
        PlayerWrapper wrapper = this.wrapper;
        if (wrapper == null || wrapper.isRecentSongsLoaded()) {
            return;
        }
        GUIConfigManager.ButtonMappingConfig mapping = GUIConfigManager.getInstance()
                .getGUIConfig(this.currentGuiType).getButtonMapping();
        if (!params.getButtonMap().containsKey(mapping.getRecentSongs())) {
            return;
        }
        wrapper.onRecentSongsLoaded(() -> this.refreshCurrentPage());
    }

    private void renderPage(GUI gui, int page, SongGUIParams params, String guiType) {
        SubSongContainer parent;
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getGUIConfig(guiType).getButtonMapping();
        int indexLimit = guiConfig.getLayoutCharCount(guiType, mapping.getSongs());
        if (indexLimit <= 0) {
            indexLimit = 36;
        }
        int skipElements = page * indexLimit;
        int pageCount = this.getPageCount(guiType);
        LayoutParser layoutParser = new LayoutParser(gui, guiType);
        if (page > 0) {
            layoutParser.registerSimpleButton(mapping.getPrevious(), "previous", () -> this.openPage(page - 1, params, guiType));
        }
        if (pageCount - 1 > page) {
            layoutParser.registerSimpleButton(mapping.getNext(), "next", () -> this.openPage(page + 1, params, guiType));
        }
        if (this.container.getParentContainer() != null && (parent = this.container.getParentContainer()) instanceof FullSongContainer) {
            layoutParser.registerSimpleButton(mapping.getParent(), "parent", () -> new SongContainerGUI((FullSongContainer)parent, this.wrapper).openPage(0, params, guiType));
        }
        if (params.getButtonMap() != null) {
            Map<Character, BarButton> buttonMap = params.getButtonMap();
            for (Map.Entry<Character, BarButton> entry : buttonMap.entrySet()) {
                char buttonChar = entry.getKey().charValue();
                BarButton button = entry.getValue();
                if (button == null) continue;
                SongGUIData<Void> voidData = new SongGUIData<Void>(this, null, params, page, guiType);
                layoutParser.registerButton(buttonChar, () -> button.getItemStack(this.wrapper), () -> button.getAction(this.wrapper, voidData));
            }
        }
        layoutParser.registerSimpleButton(mapping.getSearch(), "search", () -> GUIInputManager.getInstance().requestSearchInput(
            this.wrapper,
            this,
            params,
            guiType,
            () -> this.openPage(this.currentPage, params, guiType)
        ));
        String layout = guiConfig.getGUILayout(guiType);
        if (layout != null && !layout.isEmpty()) {
            layoutParser.parseAndApply(layout);
        }
        this.populateSongs(gui, layoutParser, mapping, page, params, guiType, skipElements);
    }
    
    private void populateSongs(GUI gui, LayoutParser layoutParser, GUIConfigManager.ButtonMappingConfig mapping, 
            int page, SongGUIParams params, String guiType, int skipElements) {
        this.renderSongSlots(gui, layoutParser, mapping, page, params, guiType, skipElements, false);
    }
    
    public void refreshCurrentPage() {
        if (this.currentGUI == null || this.currentParams == null) {
            return;
        }

        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getGUIConfig(this.currentGuiType).getButtonMapping();
        this.refreshItems(this.currentParams);
        int pageCount = this.getPageCount(this.currentGuiType);
        if (this.currentPage >= pageCount) {
            this.currentPage = Math.max(0, pageCount - 1);
        }
        this.refreshVisiblePage(this.currentGUI, this.currentPage, this.currentParams, this.currentGuiType, mapping);
    }

    private void refreshVisiblePage(GUI gui, int page, SongGUIParams params, String guiType, GUIConfigManager.ButtonMappingConfig mapping) {
        SubSongContainer parent;
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        int indexLimit = guiConfig.getLayoutCharCount(guiType, mapping.getSongs());
        if (indexLimit <= 0) {
            indexLimit = 36;
        }
        int skipElements = page * indexLimit;
        int pageCount = this.getPageCount(guiType);
        LayoutParser layoutParser = new LayoutParser(gui, guiType);

        this.updateSimpleButton(layoutParser, mapping.getPrevious(), page > 0, "previous", () -> this.openPage(page - 1, params, guiType));
        this.updateSimpleButton(layoutParser, mapping.getNext(), pageCount - 1 > page, "next", () -> this.openPage(page + 1, params, guiType));

        parent = this.container.getParentContainer();
        boolean hasParent = parent instanceof FullSongContainer;
        if (hasParent) {
            FullSongContainer parentContainer = (FullSongContainer) parent;
            this.updateSimpleButton(layoutParser, mapping.getParent(), true, "parent", () -> new SongContainerGUI(parentContainer, this.wrapper).openPage(0, params, guiType));
        } else {
            this.clearSlots(layoutParser.getSlotsForChar(mapping.getParent()));
        }

        if (params.getButtonMap() != null) {
            Map<Character, BarButton> buttonMap = params.getButtonMap();
            for (Map.Entry<Character, BarButton> entry : buttonMap.entrySet()) {
                char buttonChar = entry.getKey().charValue();
                BarButton button = entry.getValue();
                if (button == null) {
                    this.clearSlots(layoutParser.getSlotsForChar(buttonChar));
                    continue;
                }
                SongGUIData<Void> voidData = new SongGUIData<Void>(this, null, params, page, guiType);
                this.updateSlots(layoutParser.getSlotsForChar(buttonChar), () -> button.getItemStack(this.wrapper), () -> button.getAction(this.wrapper, voidData));
            }
        }

        this.updateSimpleButton(layoutParser, mapping.getSearch(), true, "search", () -> GUIInputManager.getInstance().requestSearchInput(
            this.wrapper,
            this,
            params,
            guiType,
            () -> this.openPage(this.currentPage, params, guiType)
        ));
        this.updateSongSlots(gui, layoutParser, mapping, page, params, guiType, skipElements);
    }

    private void updateSimpleButton(LayoutParser layoutParser, char character, boolean visible, String buttonName, Runnable action) {
        List<Integer> slots = layoutParser.getSlotsForChar(character);
        if (!visible) {
            this.clearSlots(slots);
            return;
        }
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        this.updateSlots(slots, () -> guiConfig.createButtonItem(this.currentGuiType, buttonName), () -> new ClickAction(action));
    }

    private void updateSongSlots(GUI gui, LayoutParser layoutParser, GUIConfigManager.ButtonMappingConfig mapping,
            int page, SongGUIParams params, String guiType, int skipElements) {
        this.renderSongSlots(gui, layoutParser, mapping, page, params, guiType, skipElements, true);
    }

    // Shared body for populateSongs (fresh GUI, addItem) and updateSongSlots (refresh,
    // updateItem / removeItem). updateMode only changes how the item lands in the inventory:
    // slots beyond the item list are cleared on refresh but simply not added when opening.
    private void renderSongSlots(GUI gui, LayoutParser layoutParser, GUIConfigManager.ButtonMappingConfig mapping,
            int page, SongGUIParams params, String guiType, int skipElements, boolean updateMode) {
        List<SongGUIItem> items = this.getItems();
        PeekList<Material> list = new PeekList<Material>(BukkitUtils.DISCS);
        MusicBoxSong playerSong = this.wrapper.getActivePlayer() != null ? (MusicBoxSong) this.wrapper.getActivePlayer().getMusicBoxSong() : null;
        List<Integer> songSlots = layoutParser.getSlotsForChar(mapping.getSongs());
        int itemIndex = skipElements;
        for (int slot : songSlots) {
            if (itemIndex >= items.size()) {
                if (updateMode) {
                    gui.removeItem(slot);
                }
                continue;
            }
            SongGUIItem item = items.get(itemIndex);
            ++itemIndex;
            if (item instanceof SongGUIChest) {
                FullSongContainer chest = ((SongGUIChest)item).getContainer();
                SongGUIData<FullSongContainer> data = new SongGUIData<FullSongContainer>(this, chest, params, page, guiType);
                List<String> extraLines = params.getExtraContainerLore() != null ? params.getExtraContainerLore().apply(data) : Collections.emptyList();
                ItemStack containerStack = chest.getItemStack(extraLines);
                Runnable containerConsumer = params.getOnContainerRightClick() != null ? () -> params.getOnContainerRightClick().accept(this.wrapper, data) : null;
                ClickAction containerAction = new ClickAction(() -> new SongContainerGUI(chest, this.wrapper).openPage(0, params, guiType), containerConsumer);
                if (updateMode) {
                    gui.updateItem(slot, containerStack, containerAction);
                } else {
                    gui.addItem(slot, containerStack, containerAction);
                }
                continue;
            }
            if (!(item instanceof SongGUISong)) {
                if (updateMode) {
                    gui.removeItem(slot);
                }
                continue;
            }
            MusicBoxSong song = ((SongGUISong)item).getSong();
            SongGUIData<MusicBoxSong> data = new SongGUIData<MusicBoxSong>(this, song, params, page, guiType);
            List<String> extraLines = params.getExtraSongLore() != null ? params.getExtraSongLore().apply(data) : Collections.emptyList();
            boolean enchanted = playerSong != null && song.equals(playerSong);
            ItemStack stack = song.getSongStack(list.getAndNext(), extraLines, enchanted);
            ClickAction songAction = new ClickAction(() -> {
                if (params.getOnSongLeftClick() != null) {
                    params.getOnSongLeftClick().accept(this.wrapper, data);
                }
            }, () -> {
                if (params.getOnSongRightClick() != null) {
                    params.getOnSongRightClick().accept(this.wrapper, data);
                }
            });
            if (updateMode) {
                gui.updateItem(slot, stack, songAction);
            } else {
                gui.addItem(slot, stack, songAction);
            }
        }
    }

    private void updateSlots(List<Integer> slots, Supplier<ItemStack> itemSupplier, Supplier<InventoryAction> actionSupplier) {
        for (int slot : slots) {
            ItemStack item = itemSupplier.get();
            InventoryAction action = actionSupplier != null ? actionSupplier.get() : null;
            if (item == null || action == null) {
                this.currentGUI.removeItem(slot);
            } else {
                this.currentGUI.updateItem(slot, item, action);
            }
        }
    }

    private void clearSlots(List<Integer> slots) {
        for (int slot : slots) {
            this.currentGUI.removeItem(slot);
        }
    }

    private GUI createGUI(String title) {
        return new GUI(title, GUIConfigManager.getInstance().getGUIRows(this.currentGuiType));
    }

    private int getPageCount(String guiType) {
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getGUIConfig(guiType).getButtonMapping();
        int slotsPerPage = guiConfig.getLayoutCharCount(guiType, mapping.getSongs());
        if (slotsPerPage <= 0) {
            slotsPerPage = 36;
        }
        int containerElementSize = this.items.size();
        return Math.max(1, (int)Math.ceil((double)containerElementSize / (double)slotsPerPage));
    }

    public SongGUIData<MusicBoxSong> createSongData(MusicBoxSong song, SongGUIParams params, int page, String guiType) {
        return new SongGUIData<MusicBoxSong>(this, song, params, page, guiType);
    }

    public FullSongContainer getContainer() {
        return this.container;
    }

    public PlayerWrapper getWrapper() {
        return this.wrapper;
    }

    public List<SongGUIItem> getItems() {
        return this.items;
    }

    public static class SongGUIParams {
        @Nullable
        private final Map<Character, BarButton> buttonMap;
        @Nullable
        private final Function<SongGUIData<MusicBoxSong>, List<String>> extraSongLore;
        @Nullable
        private final BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongLeftClick;
        @Nullable
        private final BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongRightClick;
        @Nullable
        private final Function<SongGUIData<FullSongContainer>, List<String>> extraContainerLore;
        @Nullable
        private final BiConsumer<PlayerWrapper, SongGUIData<FullSongContainer>> onContainerRightClick;
        @Nullable
        private final Predicate<MusicBoxSong> songFilter;

        SongGUIParams(@Nullable Map<Character, BarButton> buttonMap, @Nullable Function<SongGUIData<MusicBoxSong>, List<String>> extraSongLore, @Nullable BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongLeftClick, @Nullable BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongRightClick, @Nullable Function<SongGUIData<FullSongContainer>, List<String>> extraContainerLore, @Nullable BiConsumer<PlayerWrapper, SongGUIData<FullSongContainer>> onContainerRightClick, @Nullable Predicate<MusicBoxSong> songFilter) {
            this.buttonMap = buttonMap;
            this.extraSongLore = extraSongLore;
            this.onSongLeftClick = onSongLeftClick;
            this.onSongRightClick = onSongRightClick;
            this.extraContainerLore = extraContainerLore;
            this.onContainerRightClick = onContainerRightClick;
            this.songFilter = songFilter;
        }

        public static SongGUIParamsBuilder builder() {
            return new SongGUIParamsBuilder();
        }

        @Nullable
        public Map<Character, BarButton> getButtonMap() {
            return this.buttonMap;
        }

        @Nullable
        public Function<SongGUIData<MusicBoxSong>, List<String>> getExtraSongLore() {
            return this.extraSongLore;
        }

        @Nullable
        public BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> getOnSongLeftClick() {
            return this.onSongLeftClick;
        }

        @Nullable
        public BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> getOnSongRightClick() {
            return this.onSongRightClick;
        }

        @Nullable
        public Function<SongGUIData<FullSongContainer>, List<String>> getExtraContainerLore() {
            return this.extraContainerLore;
        }

        @Nullable
        public BiConsumer<PlayerWrapper, SongGUIData<FullSongContainer>> getOnContainerRightClick() {
            return this.onContainerRightClick;
        }

        @Nullable
        public Predicate<MusicBoxSong> getSongFilter() {
            return this.songFilter;
        }

        public static class SongGUIParamsBuilder {
            private Map<Character, BarButton> buttonMap;
            private Function<SongGUIData<MusicBoxSong>, List<String>> extraSongLore;
            private BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongLeftClick;
            private BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongRightClick;
            private Function<SongGUIData<FullSongContainer>, List<String>> extraContainerLore;
            private BiConsumer<PlayerWrapper, SongGUIData<FullSongContainer>> onContainerRightClick;
            private Predicate<MusicBoxSong> songFilter;

            SongGUIParamsBuilder() {
            }

            public SongGUIParamsBuilder buttonMap(@Nullable Map<Character, BarButton> buttonMap) {
                this.buttonMap = buttonMap;
                return this;
            }

            public SongGUIParamsBuilder extraSongLore(@Nullable Function<SongGUIData<MusicBoxSong>, List<String>> extraSongLore) {
                this.extraSongLore = extraSongLore;
                return this;
            }

            public SongGUIParamsBuilder onSongLeftClick(@Nullable BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongLeftClick) {
                this.onSongLeftClick = onSongLeftClick;
                return this;
            }

            public SongGUIParamsBuilder onSongRightClick(@Nullable BiConsumer<PlayerWrapper, SongGUIData<MusicBoxSong>> onSongRightClick) {
                this.onSongRightClick = onSongRightClick;
                return this;
            }

            public SongGUIParamsBuilder extraContainerLore(@Nullable Function<SongGUIData<FullSongContainer>, List<String>> extraContainerLore) {
                this.extraContainerLore = extraContainerLore;
                return this;
            }

            public SongGUIParamsBuilder onContainerRightClick(@Nullable BiConsumer<PlayerWrapper, SongGUIData<FullSongContainer>> onContainerRightClick) {
                this.onContainerRightClick = onContainerRightClick;
                return this;
            }

            public SongGUIParamsBuilder songFilter(@Nullable Predicate<MusicBoxSong> songFilter) {
                this.songFilter = songFilter;
                return this;
            }

            public SongGUIParams build() {
                return new SongGUIParams(this.buttonMap, this.extraSongLore, this.onSongLeftClick, this.onSongRightClick, this.extraContainerLore, this.onContainerRightClick, this.songFilter);
            }

            public String toString() {
                return "SongContainerGUI.SongGUIParams.SongGUIParamsBuilder(buttonMap=" + String.valueOf(this.buttonMap) + ", extraSongLore=" + String.valueOf(this.extraSongLore) + ", onSongLeftClick=" + String.valueOf(this.onSongLeftClick) + ", onSongRightClick=" + String.valueOf(this.onSongRightClick) + ", extraContainerLore=" + String.valueOf(this.extraContainerLore) + ", onContainerRightClick=" + String.valueOf(this.onContainerRightClick) + ", songFilter=" + String.valueOf(this.songFilter) + ")";
            }
        }
    }

    public interface BarButton {
        ItemStack getItemStack(PlayerWrapper player);

        InventoryAction getAction(PlayerWrapper player, SongGUIData<Void> data);
    }

    private static interface SongGUIItem {
    }

    private static class SongGUIChest
    implements SongGUIItem {
        private final FullSongContainer container;

        public FullSongContainer getContainer() {
            return this.container;
        }

        public SongGUIChest(FullSongContainer container) {
            this.container = container;
        }
    }

    public class SongGUIData<T> {
        private final SongContainerGUI gui;
        private final T data;
        private final SongGUIParams params;
        private final int page;
        private final String guiType;

        public void refreshInventory() {
            SongContainerGUI.this.refreshCurrentPage();
        }

        public SongContainerGUI getGui() {
            return this.gui;
        }

        public T getData() {
            return this.data;
        }

        public SongGUIParams getParams() {
            return this.params;
        }

        public int getPage() {
            return this.page;
        }

        public String getGuiType() {
            return this.guiType;
        }

        private SongGUIData(SongContainerGUI gui, T data, SongGUIParams params, int page, String guiType) {
            this.gui = gui;
            this.data = data;
            this.params = params;
            this.page = page;
            this.guiType = guiType;
        }
    }

    private static class SongGUISong
    implements SongGUIItem {
        private final MusicBoxSong song;

        public MusicBoxSong getSong() {
            return this.song;
        }

        public SongGUISong(MusicBoxSong song) {
            this.song = song;
        }
    }
}
