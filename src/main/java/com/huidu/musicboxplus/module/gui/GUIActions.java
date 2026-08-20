package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.DebugLogger;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.playlist.PlayListEditorGUI;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;
import com.huidu.musicboxplus.module.radio.RadioPlayer;
import com.huidu.musicboxplus.module.speaker.SpeakerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class GUIActions {
    private static final Logger logger = Logger.getLogger("MusicBox");
    private static Map<String, List<String>> loreCache;
    private static Map<String, ItemStack> itemCache;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    public static SongContainerGUI.SongGUIParams DEFAULT_MODE;
    public static SongContainerGUI.SongGUIParams GET_MODE_MANY;
    public static SongContainerGUI.SongGUIParams GET_MODE_SINGLE;
    public static SongContainerGUI.SongGUIParams SHOP_MODE;

    private static boolean shouldShowInPlaybackMenus(MusicBoxSong song) {
        return song != null && !song.shouldUseVanillaJukeboxPlayback();
    }

    private static <K, V> Map<K, V> createLRUCache(int maxSize) {
        if (maxSize <= 0) {
            maxSize = 200;
        }
        final int finalMaxSize = maxSize;
        return new LinkedHashMap<K, V>(16, 0.75f, true){
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return this.size() > finalMaxSize;
            }
        };
    }
    
    private static void initCaches() {
        MusicBoxConfig config = MusicBox.getInstance().getConfigObject();
        MusicBoxConfig.CacheSetting cacheConfig = config != null ? config.getCache() : null;
        int itemCacheSize = cacheConfig != null ? cacheConfig.getGuiItemCacheSize() : 200;
        int loreCacheSize = cacheConfig != null ? cacheConfig.getGuiLoreCacheSize() : 200;
        loreCache = Collections.synchronizedMap(GUIActions.createLRUCache(loreCacheSize));
        itemCache = Collections.synchronizedMap(GUIActions.createLRUCache(itemCacheSize));
    }

    private GUIActions() {
    }

    public static void init() {
        if (initialized.compareAndSet(false, true)) {
            initCaches();
            // Not reloadGUI(): GUIConfigManager's constructor has just parsed gui-config.yml, and
            // reloading it here parsed the 66 KB file and its bundled twin a second time.
            buildButtons();
        }
    }

    public static void reloadGUI() {
        GUIConfigManager.getInstance().reload();
        buildButtons();
    }

    private static void buildButtons() {
        if (loreCache != null) loreCache.clear();
        if (itemCache != null) itemCache.clear();
        GUIConfigManager.ButtonMappingConfig songListMapping = GUIConfigManager.getInstance().getGUIConfig("song-list").getButtonMapping();
        HashMap<Character, SongContainerGUI.BarButton> buttonMap = new HashMap<Character, SongContainerGUI.BarButton>();
        buttonMap.put(Character.valueOf(songListMapping.getVolume()), ButtonFactory.createVolumeButton());
        buttonMap.put(Character.valueOf(songListMapping.getSpeed()), ButtonFactory.createSpeedButton());
        buttonMap.put(Character.valueOf(songListMapping.getControlPanel()), ButtonFactory.createControlPanelButton());
        buttonMap.put(Character.valueOf(songListMapping.getStop()), ButtonFactory.createStopButton());
        buttonMap.put(Character.valueOf(songListMapping.getPlaylist()), ButtonFactory.createPlaylistButton());
        buttonMap.put(Character.valueOf(songListMapping.getPlayPause()), ButtonFactory.createPlayPauseButton());
        buttonMap.put(Character.valueOf(songListMapping.getRecentSongs()), ButtonFactory.createRecentSongsButton());
        buttonMap.put(Character.valueOf(songListMapping.getPlayMode()), ButtonFactory.createPlayModeButton());
        DEFAULT_MODE = SongContainerGUI.SongGUIParams.builder().onSongLeftClick(GUIActions::playerPlayMusic).onContainerRightClick(GUIActions::playContainer).extraContainerLore(GUIActions::playerPlayAllContainer).buttonMap(buttonMap).songFilter(GUIActions::shouldShowInPlaybackMenus).build();
        buttonMap = new HashMap<Character, SongContainerGUI.BarButton>();
        buttonMap.put(Character.valueOf(songListMapping.getVolume()), ButtonFactory.createVolumeButton());
        buttonMap.put(Character.valueOf(songListMapping.getSpeed()), ButtonFactory.createSpeedButton());
        buttonMap.put(Character.valueOf(songListMapping.getControlPanel()), ButtonFactory.createControlPanelButton());
        buttonMap.put(Character.valueOf(songListMapping.getStop()), ButtonFactory.createStopButton());
        buttonMap.put(Character.valueOf(songListMapping.getPlaylist()), ButtonFactory.createPlaylistButton());
        buttonMap.put(Character.valueOf(songListMapping.getPlayPause()), ButtonFactory.createPlayPauseButton());
        buttonMap.put(Character.valueOf(songListMapping.getRecentSongs()), ButtonFactory.createRecentSongsButton(true, null));
        SHOP_MODE = SongContainerGUI.SongGUIParams.builder().onSongLeftClick(GUIActions::playerBuyMusic).extraSongLore(GUIActions::playerBuySongLore).extraContainerLore(GUIActions::playerBuyAllContainerLore).onContainerRightClick(GUIActions::buyAllContainer).buttonMap(buttonMap).build();
        GET_MODE_SINGLE = SongContainerGUI.SongGUIParams.builder().onSongLeftClick((wrapper, data) -> {
            GUIActions.giveDisc(wrapper, data);
            wrapper.getPlayer().closeInventory();
        }).extraSongLore(GUIActions::playerGetSongLore).build();
        GET_MODE_MANY = SongContainerGUI.SongGUIParams.builder().onSongLeftClick(GUIActions::giveDisc).extraSongLore(GUIActions::playerGetSongLore).onContainerRightClick(GUIActions::getAllContainer).extraContainerLore(GUIActions::playerGetAllContainerLore).build();
    }

    public static ItemStack getStopStack() {
        String cacheKey = "stop_stack";
        return itemCache.computeIfAbsent(cacheKey, k -> {
            GUIConfigManager guiConfig = GUIConfigManager.getInstance();
            ItemStack item = guiConfig.createButtonItem("song-list", "stop");
            if (item == null) {
                item = guiConfig.createButtonItem("main-menu", "stop");
            }
            return item != null ? item : ItemUtils.createStack(Material.BARRIER, Lang.STOP_BUTTON.toString(), Lang.STOP_BUTTON_LORE.toList());
        });
    }

    public static void playerPlayMusic(PlayerWrapper player, SongContainerGUI.SongGUIData<MusicBoxSong> data) {
        if (!MusicBox.getInstance().isPlaybackModuleEnabled()) {
            return;
        }
        if (player == null || data == null || data.getData() == null) {
            logger.warning("Invalid parameters for playerPlayMusic");
            return;
        }
        try {
            // Repaint when playback exists, not one tick later: a song being played for the first
            // time compiles asynchronously, so the old fixed delay repainted while there was still
            // no active player -- and a null play/pause item makes updateSlots delete the button.
            player.play(new SingletonPlayList(data.getData()), (short) -1, data::refreshInventory);
        }
        catch (Exception e) {
            logger.severe("Failed to play music for player: " + e.getMessage());
        }
    }

    public static void playContainer(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<FullSongContainer> data) {
        if (!MusicBox.getInstance().isPlaybackModuleEnabled()) {
            return;
        }
        if (wrapper == null || data == null || data.getData() == null) {
            logger.warning("Invalid parameters for playContainer");
            return;
        }
        if (data.getData().getAllSongs().isEmpty()) {
            return;
        }
        wrapper.play(data.getData(), data::refreshInventory);
    }

    public static List<String> playerPlayAllContainer(SongContainerGUI.SongGUIData<FullSongContainer> data) {
        return GUIConfigManager.getInstance().getShopLoreConfig().getContainerPlayAllLore();
    }

    public static void playerBuyMusic(PlayerWrapper player, SongContainerGUI.SongGUIData<MusicBoxSong> data) {
        GUIActions.playerBuyMusic(player, data.getData());
    }

    public static void playerBuyMusic(PlayerWrapper wrapper, MusicBoxSong musicBoxSong) {
        if (!MusicBox.getInstance().isShopModuleEnabled()) {
            return;
        }
        GUIShopActions.playerBuyMusic(logger, wrapper, musicBoxSong);
    }

    public static List<String> playerBuySongLore(SongContainerGUI.SongGUIData<MusicBoxSong> musicBoxSong) {
        MusicBoxSong song = musicBoxSong != null ? musicBoxSong.getData() : null;
        return GUIShopActions.playerBuySongLore(logger, loreCache, GUIConfigManager.getInstance().getShopLoreConfig(), song);
    }

    public static List<String> playerBuyAllContainerLore(SongContainerGUI.SongGUIData<FullSongContainer> containerData) {
        FullSongContainer container = containerData != null ? containerData.getData() : null;
        return GUIShopActions.playerBuyAllContainerLore(logger, loreCache, container);
    }

    public static void buyAllContainer(PlayerWrapper playerWrapper, SongContainerGUI.SongGUIData<FullSongContainer> container) {
        if (!MusicBox.getInstance().isShopModuleEnabled()) {
            return;
        }
        GUIShopActions.openBuyAllContainerConfirm(logger, playerWrapper, container);
    }

    public static void giveDisc(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<MusicBoxSong> data) {
        if (data != null) {
            GUIActions.giveDisc(wrapper, data.getData());
        }
    }

    public static void giveDisc(PlayerWrapper wrapper, MusicBoxSong song) {
        if (!MusicBox.getInstance().isGiveModuleEnabled()) {
            return;
        }
        if (wrapper == null || song == null) {
            return;
        }
        Player player = wrapper.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack stack = song.getSongStack();
        if (stack != null) {
            Scheduler.entityNow(player, () -> {
                java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                }
                MessageUtils.send(player, Lang.YOU_GET_DISC, "{disc}", song.getName());
            });
        }
    }

    public static List<String> playerGetSongLore(SongContainerGUI.SongGUIData<MusicBoxSong> data) {
        return GUIConfigManager.getInstance().getShopLoreConfig().getGetLore();
    }

    public static void getAllContainer(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<FullSongContainer> data) {
        if (!MusicBox.getInstance().isGiveModuleEnabled()) {
            return;
        }
        if (wrapper == null || data == null || data.getData() == null) {
            return;
        }
        for (MusicBoxSong song : data.getData().getAllSongs()) {
            GUIActions.giveDisc(wrapper, song);
        }
    }

    public static List<String> playerGetAllContainerLore(SongContainerGUI.SongGUIData<FullSongContainer> musicBoxSongContainerSongGUIData) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.GET_CONTAINER_LORE_CLICK_ALL.toString());
        return StringUtils.t(lore);
    }

    public static void openPlaylistListEditor(PlayerWrapper wrapper) {
        if (!MusicBox.getInstance().isPlaylistsModuleEnabled()) {
            return;
        }
        GUIPlaylistActions.openPlaylistListEditor(wrapper);
    }

    public static void openPlaylistEditor(PlayerWrapper wrapper, PlayerPlayListModel model) {
        if (!MusicBox.getInstance().isPlaylistsModuleEnabled()) {
            return;
        }
        GUIPlaylistActions.openPlaylistEditor(wrapper, model);
    }

    public static void openDefaultInventory(PlayerWrapper wrapper) {
        if (!MusicBox.getInstance().isPlaybackModuleEnabled()) {
            return;
        }
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        gui.openPage(0, DEFAULT_MODE);
    }

    public static void openShopInventory(PlayerWrapper wrapper) {
        if (!MusicBox.getInstance().isShopModuleEnabled()) {
            return;
        }
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        gui.openPage(0, SHOP_MODE);
    }

    public static void openGiveInventoryMany(PlayerWrapper wrapper) {
        if (!MusicBox.getInstance().isGiveModuleEnabled()) {
            return;
        }
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        gui.openPage(0, GET_MODE_MANY);
    }

    public static void openGiveInventorySingle(PlayerWrapper wrapper) {
        if (!MusicBox.getInstance().isGiveModuleEnabled()) {
            return;
        }
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        gui.openPage(0, GET_MODE_SINGLE);
    }

    public static void openSignSetupInventory(PlayerWrapper wrapper, final Sign sign) {
        if (!MusicBox.getInstance().isSignsModuleEnabled()) {
            return;
        }
        GUISignActions.openSignSetupInventory(wrapper, sign);
    }

    public static void openPlayListAdder(PlayerWrapper wrapper, PlayListEditorGUI editorGUI) {
        if (!MusicBox.getInstance().isPlaylistsModuleEnabled()) {
            return;
        }
        GUIPlaylistActions.openPlayListAdder(wrapper, editorGUI);
    }

    // The PlayerWrapper behind a PlayerSongPlayer, or null when the player is not one of the
    // known wrapper-backed implementations. Keeps api.player.PlayerSongPlayer free of any
    // dependency on the wrapper type.
    public static PlayerWrapper playerWrapperOf(MusicBoxSongPlayer player) {
        if (player instanceof RadioPlayer radioPlayer) {
            return radioPlayer.getModel().getWrapper();
        }
        if (player instanceof SpeakerPlayer speakerPlayer) {
            return speakerPlayer.getModel().getWrapper();
        }
        return null;
    }

    // Closes every MusicBox GUI a player has open. Runs from the global region thread on
    // reload/shutdown, so each player's inventory work is hopped onto that player's own region.
    public static void closeAllOpen() {
        Set<String> keywords = GUIConfigManager.getInstance() != null ? GUIConfigManager.getInstance().getGUITitleKeywords() : null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            Scheduler.entityNow(player, () -> {
                if (player.getOpenInventory() == null || player.getOpenInventory().getTopInventory() == null) {
                    return;
                }

                try {
                    InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                    if (holder instanceof GUI || holder instanceof SongContainerGUI) {
                        player.closeInventory();
                        return;
                    }
                } catch (Exception e) {
                    DebugLogger.debug("Error checking inventory holder for player " + player.getName() + ": " + e.getMessage());
                }

                if (keywords == null) {
                    return;
                }

                String title = MiniMessageUtils.toLegacyText(player.getOpenInventory().title());
                if (keywords.stream().anyMatch(title::contains)) {
                    player.closeInventory();
                }
            });
        }
    }
}

