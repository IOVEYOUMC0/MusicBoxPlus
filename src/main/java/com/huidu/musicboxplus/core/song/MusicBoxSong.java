package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Paths;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.*;
import com.huidu.musicboxplus.common.utils.nbt.ItemNbt;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.*;

public class MusicBoxSong implements com.huidu.musicboxplus.api.song.MusicBoxSong {
    private final File file;
    private final String name;
    private final Map<String, String> hoverMap = new HashMap<String, String>();
    private final MusicBoxSongContainer container;
    private final short length;
    private final float speed;
    private final int duration;
    private final int hash;
    private final Set<String> aliases = new HashSet<String>();
    private final Set<String> tags = new HashSet<String>();
    private String jukeboxPlayable;
    private int customModelData = 0;
    private String customMaterial;
    private String itemModel;
    private String craftEngineItem;
    // Base lore lines (name/author/length + aliases/tags), resolved once per song/config pair.
    // The replacement pass and String.join are pure string work that the renderer would
    // otherwise repeat for every slot on every GUI rebuild; invalidated when aliases/tags
    // change or the song-item config instance is swapped by a reload.
    private volatile List<String> cachedBaseLore;
    private volatile GUIConfigManager.SongItemConfig cachedLoreConfig;
    // Non-null when this song is backed by in-memory player-created music instead of an NBS file.
    private final PlayerMusicSource playerMusic;

    // Path under the songs root, with a stable separator so Windows and Linux agree.
    private static String relativeSongPath(File songFile) {
        File root = new File(MusicBox.getInstance().getDataFolder(), Paths.SONGS_DIR);
        try {
            return root.toPath().toAbsolutePath().normalize()
                    .relativize(songFile.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
        } catch (Exception e) {
            // Outside the songs root (or an unrelated drive): fall back to the file name, which is
            // still stable across a folder move.
            return songFile.getName();
        }
    }

    MusicBoxSong(File songFile, MusicBoxSongContainer container) throws SongNullException {
        this.playerMusic = null;
        this.file = songFile;
        this.container = container;
        // Hashed on the path RELATIVE to the songs folder, not the full path. The full path
        // contains the plugin's data-folder name, so renaming the plugin -- or moving the songs
        // folder -- changed every hash, and the hash is the identity stored in every disc's NBT
        // and in the playlist/sign/recent-song tables. Relative, it survives both.
        this.hash = relativeSongPath(songFile).hashCode();
        RawNbsSong song;
        try {
            // 只读头部元数据（标题/长度/速度/作者），跳过音符解析，大幅加快曲库加载
            song = NbsReader.readMetadata(this.file.toPath());
        } catch (IOException e) {
            throw new SongNullException("Song can't be loaded: " + e.getMessage());
        }
        this.name = StringUtils.t(StringUtils.getOrEmpty(song.title(), () -> FileUtils.getFilename(this.file.getName())));
        this.length = (short) Math.max(1, song.lengthTicks());
        this.speed = song.ticksPerSecond();
        this.duration = this.speed == 0.0f ? 0 : (int)Math.floor((float)this.length / this.speed);
        String time = StringUtils.toHumanTime(this.duration);
        this.hoverMap.put("{length}", time);
        this.hoverMap.put("{author}", song.author());
        this.hoverMap.put("{original_author}", song.originalAuthor());
        this.hoverMap.put("{name}", this.getName());
    }

    // Adapter constructor for player-created music. No NBS file is involved; the playable
    // arrangement is compiled lazily on the first getCompiledSong() call, so cheap presence
    // checks don't pay the conversion cost.
    private MusicBoxSong(PlayerMusicSource music) {
        this.playerMusic = music;
        this.file = null;
        this.container = null;
        this.hash = ("player_music:" + music.getUniqueId()).hashCode();
        this.name = StringUtils.t(StringUtils.getOrEmpty(music.getName(), () -> music.getUniqueId().toString()));
        this.speed = Math.max(0.1f, music.getBpm() * Math.max(1, music.getBeatSubdivision()) / 60.0f);
        this.length = (short) Math.max(1, music.getMaxTick() + 1);
        this.duration = this.speed == 0.0f ? 0 : (int) Math.floor((float) this.length / this.speed);
        this.hoverMap.put("{length}", StringUtils.toHumanTime(this.duration));
        this.hoverMap.put("{author}", music.getAuthor());
        this.hoverMap.put("{original_author}", music.getAuthor());
        this.hoverMap.put("{name}", this.name);
    }

    public static MusicBoxSong fromPlayerMusic(PlayerMusicSource music) {
        if (music == null) {
            return null;
        }
        return new MusicBoxSong(music);
    }

    public void addAlias(String alias) {
        if (alias != null && !alias.trim().isEmpty()) {
            this.aliases.add(alias.trim().toLowerCase());
            this.invalidateBaseLore();
        }
    }

    public void addAliases(List<String> aliasList) {
        if (aliasList != null) {
            for (String alias : aliasList) {
                this.addAlias(alias);
            }
        }
    }

    public void addTag(String tag) {
        if (tag != null && !tag.trim().isEmpty()) {
            this.tags.add(tag.trim().toLowerCase());
            this.invalidateBaseLore();
        }
    }

    public void removeAlias(String alias) {
        if (alias != null) {
            this.aliases.remove(alias.trim().toLowerCase());
            this.invalidateBaseLore();
        }
    }

    public void removeTag(String tag) {
        if (tag != null) {
            this.tags.remove(tag.trim().toLowerCase());
            this.invalidateBaseLore();
        }
    }

    public void addTags(List<String> tagList) {
        if (tagList != null) {
            for (String tag : tagList) {
                this.addTag(tag);
            }
        }
    }

    public void resetAliasMetadata() {
        this.aliases.clear();
        this.tags.clear();
        this.jukeboxPlayable = null;
        this.customModelData = 0;
        this.customMaterial = null;
        this.itemModel = null;
        this.craftEngineItem = null;
        this.invalidateBaseLore();
    }

    private void invalidateBaseLore() {
        this.cachedBaseLore = null;
        this.cachedLoreConfig = null;
    }

    // Base lore lines without per-render extraLines, cached per (song, config) pair. Rebuilding
    // them is string-only work (placeholder replacement + alias/tag joins) that the renderer
    // repeats for every slot; aliases/tags mutate only through the methods above and the config
    // instance is swapped on reload, so both are cheap to detect.
    private List<String> getBaseLoreLines(GUIConfigManager.SongItemConfig songItemConfig) {
        List<String> cached = this.cachedBaseLore;
        if (cached != null && this.cachedLoreConfig == songItemConfig) {
            return cached;
        }
        List<String> loreFormat = songItemConfig.getLoreFormat();
        List<String> list;
        if (loreFormat != null && !loreFormat.isEmpty()) {
            list = ArrayUtils.replaceOrRemove(new ArrayList<>(loreFormat), this.hoverMap);
        } else {
            list = ArrayUtils.replaceOrRemove(Arrays.asList("<gray>作者：<white>{author}", "<gray>原作者：<white>{original_author}", "<gray>时长：<white>{length}"), this.hoverMap);
        }
        if (!this.aliases.isEmpty()) {
            String aliasesFormat = songItemConfig.getAliasesFormat();
            if (aliasesFormat != null && !aliasesFormat.isEmpty()) {
                list.add(aliasesFormat.replace("{aliases}", String.join(", ", this.aliases)));
            } else {
                list.add("<gray>别名：<white>" + String.join(", ", this.aliases));
            }
        }
        if (!this.tags.isEmpty()) {
            String tagsFormat = songItemConfig.getTagsFormat();
            if (tagsFormat != null && !tagsFormat.isEmpty()) {
                list.add(tagsFormat.replace("{tags}", String.join(", ", this.tags)));
            } else {
                list.add("<gray>标签：<aqua>" + String.join(", ", this.tags));
            }
        }
        this.cachedBaseLore = list;
        this.cachedLoreConfig = songItemConfig;
        return list;
    }

    public List<String> getAliasList() {
        return new ArrayList<String>(this.aliases);
    }

    public List<String> getTagList() {
        return new ArrayList<String>(this.tags);
    }

    public boolean matchesSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            return false;
        }
        String lowerQuery = query.toLowerCase().trim();
        if (this.name.toLowerCase().contains(lowerQuery)) {
            return true;
        }
        for (String alias : this.aliases) {
            if (!alias.contains(lowerQuery)) continue;
            return true;
        }
        for (String tag : this.tags) {
            if (!tag.contains(lowerQuery)) continue;
            return true;
        }
        return false;
    }

    public int getDuration() {
        return this.duration;
    }

    public String getAuthor() {
        return this.hoverMap.getOrDefault("{author}", Lang.UNKNOWN.toString());
    }

    public String getOriginalAuthor() {
        return this.hoverMap.getOrDefault("{original_author}", Lang.UNKNOWN.toString());
    }

    public String getLengthFormatted() {
        return this.hoverMap.getOrDefault("{length}", "0:00");
    }

    public ItemStack getSongStack(Material material) {
        return this.getSongStack(material, Collections.emptyList(), false);
    }

    public ItemStack getSongStack(Material material, List<String> extraLines, boolean glow) {
        GUIConfigManager.SongItemConfig songItemConfig = GUIConfigManager.getInstance().getSongItemConfig();
        String itemName = songItemConfig.getNameFormat().replace("{song}", this.getName());
        return this.getSongStack(material, itemName, extraLines, glow);
    }

    public ItemStack getSongStack(Material material, String itemName, List<String> extraLines, boolean glow) {
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        GUIConfigManager.SongItemConfig songItemConfig = guiConfig.getSongItemConfig();
        Material finalMaterial = material;
        if (this.customMaterial != null && !this.customMaterial.isEmpty()) {
            Material customMat = Material.matchMaterial(this.customMaterial);
            if (customMat != null) {
                finalMaterial = customMat;
            }
        }
        ItemStack craftEngineStack = CraftEngineItemHelper.buildItem(this.craftEngineItem);
        boolean usingCraftEngineItem = craftEngineStack != null;
        ItemStack stack = usingCraftEngineItem ? craftEngineStack : new ItemStack(finalMaterial);
        stack.setAmount(1);
        // Build every meta change into one meta and set it once. getItemMeta/setItemMeta deep
        // copies the whole CraftMetaItem, so the old four round-trips (name/lore, item model,
        // NBT, glow) were four copies per rendered slot.
        ItemMeta meta = Objects.requireNonNull(stack).getItemMeta();
        Objects.requireNonNull(meta).displayName(MiniMessageUtils.processComponent(itemName));
        List<String> list;
        if (extraLines == null || extraLines.isEmpty()) {
            list = this.getBaseLoreLines(songItemConfig);
        } else {
            list = new ArrayList<>(this.getBaseLoreLines(songItemConfig));
            list.addAll(extraLines);
        }
        meta.lore(MiniMessageUtils.processComponents(list));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        if (!usingCraftEngineItem) {
            if (this.customModelData > 0) {
                meta.setCustomModelData(this.customModelData);
            } else if (songItemConfig.getCustomModelData() > 0) {
                meta.setCustomModelData(songItemConfig.getCustomModelData());
            }
            ItemModelHelper.applyItemModel(meta, this.resolveItemModel(songItemConfig));
        }
        ItemNbt.set(meta, "song_hash", this.getHash());
        if (glow) {
            ItemUtils.applyGlow(meta);
        }
        stack.setItemMeta(meta);
        String resolvedJukeboxPlayable = this.getResolvedJukeboxPlayable();
        if (resolvedJukeboxPlayable != null) {
            stack = JukeboxPlayableHelper.setJukeboxPlayable(stack, resolvedJukeboxPlayable);
        }
        return stack;
    }

    // Playback arrangement, built once and shared by every playback speed.
    //
    // Speed lives on the playback cursor, not in the arrangement, so unlike the speed-adjusted
    private volatile SoftReference<Compiled> compiled;

    // The arrangement and the resource-pack substitutions it was built under, as one value.
    // Two separate fields could be read torn -- new overrides against the previous arrangement --
    // which is exactly the arrangement built for the wrong sound set. One reference, one read.
    private record Compiled(CompiledSong song, Map<Integer, String> overrides) {
    }

    private static Compiled matching(SoftReference<Compiled> ref, Map<Integer, String> overrides) {
        Compiled held = ref == null ? null : ref.get();
        return held != null && overrides.equals(held.overrides()) ? held : null;
    }

    // Whether getCompiledSong() would return without reading the file.
    //
    // Deliberately NOT synchronized: this is the probe PlaybackSetup uses on a region thread to
    // decide whether it must hand the work to the async pool. Sharing getCompiledSong's monitor
    // made it block for the entire read-and-parse whenever another thread was inside it -- up to
    // 16 ms on the largest song, on the very thread the probe exists to protect.
    public boolean isCompiled() {
        Map<Integer, String> overrides = PlayerSongServices.buildSoundOverrides();
        return matching(this.compiled, overrides) != null
                || CompiledSongCache.get(this.hash, overrides) != null;
    }

    // Still synchronized: two threads racing a cold song should parse it once, not twice.
    public synchronized CompiledSong getCompiledSong() throws SongNullException {
        Map<Integer, String> overrides = PlayerSongServices.buildSoundOverrides();
        Compiled held = matching(this.compiled, overrides);
        if (held != null) {
            return held.song();
        }
        CompiledSong compiled;
        // The soft reference above may have been cleared by any collection since the last play.
        // Rebuilding costs a full re-read and re-parse of the file on the caller's thread, which
        // is the thread handling the interaction that started the song, so a shared strong cache
        // of what is actually in rotation stands in front of it.
        compiled = CompiledSongCache.get(this.hash, overrides);
        if (compiled != null) {
            this.compiled = new SoftReference<>(new Compiled(compiled, overrides));
            return compiled;
        }

        if (this.file != null) {
            try {
                compiled = CompiledSong.compile(NbsReader.read(this.file.toPath()), overrides);
            } catch (IOException e) {
                throw new SongNullException("Song can't be loaded: " + e.getMessage());
            }
        } else {
            compiled = PlayerSongServices.compilePlayerMusic(this.playerMusic, overrides);
        }
        if (compiled == null) {
            throw new SongNullException("Song can't be loaded");
        }
        this.compiled = new SoftReference<>(new Compiled(compiled, overrides));
        CompiledSongCache.put(this.hash, overrides, compiled);
        return compiled;
    }

    public ItemStack getSongStack() {
        GUIConfigManager.SongItemConfig songItemConfig = GUIConfigManager.getInstance().getSongItemConfig();
        Material material;
        if (songItemConfig.isCustomEnabled()) {
            material = songItemConfig.getCustomMaterial();
        } else if (songItemConfig.isUseRandomDisc()) {
            List<Material> availableDiscs = songItemConfig.getAvailableDiscs();
            if (availableDiscs != null && !availableDiscs.isEmpty()) {
                material = com.huidu.musicboxplus.common.utils.ArrayUtils.getRandom(availableDiscs);
            } else {
                material = BukkitUtils.getRandomDisc();
            }
        } else {
            material = songItemConfig.getCustomMaterial();
        }
        return this.getSongStack(material);
    }

    public File getFile() {
        return this.file;
    }

    public String getName() {
        return this.name;
    }

    public Map<String, String> getHoverMap() {
        return this.hoverMap;
    }

    public MusicBoxSongContainer getContainer() {
        return this.container;
    }

    public short getLength() {
        return this.length;
    }

    public float getSpeed() {
        return this.speed;
    }

    public int getHash() {
        return this.hash;
    }

    public Set<String> getAliases() {
        return this.aliases;
    }

    public Set<String> getTags() {
        return this.tags;
    }

    public String getJukeboxPlayable() {
        return this.jukeboxPlayable;
    }

    public String getResolvedJukeboxPlayable() {
        MusicBox plugin = MusicBox.getInstance();
        if (plugin == null || plugin.getConfigObject() == null || plugin.getConfigObject().getCustomRecords() == null) {
            return null;
        }
        if (!plugin.getConfigObject().getCustomRecords().isEnabled()) {
            return null;
        }
        if (this.jukeboxPlayable == null || this.jukeboxPlayable.trim().isEmpty()) {
            return null;
        }
        String value = this.jukeboxPlayable.trim();
        if (value.contains(":")) {
            return value;
        }
        String namespace = plugin.getConfigObject().getCustomRecords().getDefaultNamespace();
        if (namespace == null || namespace.trim().isEmpty()) {
            namespace = "musicboxplus";
        }
        return namespace.trim().toLowerCase() + ":" + value.toLowerCase();
    }

    public boolean shouldUseVanillaJukeboxPlayback() {
        MusicBox plugin = MusicBox.getInstance();
        return this.getResolvedJukeboxPlayable() != null
            && plugin != null
            && plugin.getConfigObject() != null
            && plugin.getConfigObject().getCustomRecords() != null
            && plugin.getConfigObject().getCustomRecords().isVanillaJukeboxPlayback();
    }

    public void setJukeboxPlayable(String jukeboxPlayable) {
        this.jukeboxPlayable = jukeboxPlayable;
    }

    public int getCustomModelData() {
        return this.customModelData;
    }

    public void setCustomModelData(int customModelData) {
        this.customModelData = customModelData;
    }

    public String getCustomMaterial() {
        return this.customMaterial;
    }

    public void setCustomMaterial(String customMaterial) {
        this.customMaterial = customMaterial;
    }

    public String getItemModel() {
        return this.itemModel;
    }

    public void setItemModel(String itemModel) {
        this.itemModel = itemModel;
    }

    public String getCraftEngineItem() {
        return this.craftEngineItem;
    }

    public void setCraftEngineItem(String craftEngineItem) {
        this.craftEngineItem = craftEngineItem;
    }

    private String resolveItemModel(GUIConfigManager.SongItemConfig songItemConfig) {
        if (this.itemModel != null && !this.itemModel.trim().isEmpty()) {
            return this.itemModel;
        }
        String configuredItemModel = songItemConfig.getItemModel();
        return configuredItemModel == null || configuredItemModel.trim().isEmpty() ? null : configuredItemModel;
    }

}
