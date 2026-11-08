package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.FileUtils;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.core.song.songContainers.types.SubSongContainer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

public class MusicBoxSongContainer implements FullSongContainer {
    private static final int SONG_LOADER_THREADS = 8;
    private final MusicBoxSongContainer parent;
    private volatile List<MusicBoxSongContainer> subContainers;
    private volatile List<MusicBoxSong> songs;
    private volatile List<MusicBoxSong> allSongs;
    private volatile int allSongCount;
    private final String name;
    private final List<String> lore;
    private final int hash;
    private final String path;
    private final Material displayMaterial;
    private final int displayModelData;
    private final String displayItemModel;
    private final String displayCraftEngineItem;
    private final String displayName;
    private final boolean displayGlow;
    private final String displaySkullOwner;
    private final List<String> displayLore;
    private static final ManagedSongLoader SONG_LOADER = new ManagedSongLoader(
            Math.max(2, Math.min(SONG_LOADER_THREADS, Runtime.getRuntime().availableProcessors())),
            1024, 10L, TimeUnit.SECONDS, action -> {
                Thread thread = new Thread(action, "MusicBox-SongLoader");
                thread.setDaemon(true);
                return thread;
            });

    /** Rearm a stopped loader at plugin enable; constructing the worker pool remains lazy. */
    public static void resetLoader() {
        SONG_LOADER.reset();
    }

    public static void shutdownLoader() {
        SONG_LOADER.shutdown();
    }

    public MusicBoxSongContainer(File folder, MusicBoxSongContainer parent) {
        this(folder, parent, true);
    }

    public MusicBoxSongContainer(File folder, MusicBoxSongContainer parent, boolean keepFolderName) {
        if (!folder.isDirectory()) {
            throw new RuntimeException("File is not folder");
        }
        this.parent = parent;
        this.name = keepFolderName ? StringUtils.t(folder.getName()) : "";
        this.path = folder.getAbsolutePath();

        File folderConfigFile = new File(folder, "folder.yml");
        List<String> tempLore = new ArrayList<>();
        Material parsedMaterial = null;
        int parsedModelData = 0;
        String parsedItemModel = "";
        String parsedCraftEngineItem = "";
        String parsedName = null;
        boolean parsedGlow = false;
        String parsedSkullOwner = null;
        List<String> description = new ArrayList<>();
        List<String> infoDescription = new ArrayList<>();
        List<String> configuredLore = new ArrayList<>();

        if (folderConfigFile.isFile()) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(folderConfigFile);

                String itemName = config.getString("display.item", config.getString("display.material", null));
                if (itemName != null && !itemName.isEmpty()) {
                    try {
                        Material m = Material.matchMaterial(itemName);
                        if (m != null && m.isItem()) {
                            parsedMaterial = m;
                        }
                    } catch (Exception ignored) {
                    }
                }
                parsedModelData = config.getInt("display.custom-model-data", config.getInt("display.custom_model_data", 0));
                parsedItemModel = config.getString("display.item-model", config.getString("display.item_model", ""));
                parsedCraftEngineItem = config.getString("display.craft-engine-item", config.getString("display.craft_engine_item", ""));
                if (config.contains("display.name")) {
                    String customName = config.getString("display.name");
                    if (customName != null && !customName.isEmpty()) {
                        parsedName = StringUtils.t(customName);
                    }
                }
                description = readStringList(config, "display.description");
                configuredLore = readStringList(config, "display.lore");
                if (config.contains("display.glow")) {
                    parsedGlow = config.getBoolean("display.glow", false);
                }
                parsedSkullOwner = config.getString("display.skull-owner", config.getString("display.skull_owner"));
                if (parsedSkullOwner == null) {
                    parsedSkullOwner = config.getString("display.skullOwner");
                }
            } catch (Exception ex) {
                MusicBox plugin = MusicBox.getInstance();
                if (plugin != null) {
                    plugin.getLogger().warning("Failed to read folder.yml in " + folder.getPath() + ": " + ex.getMessage());
                }
            }
        }

        if (description.isEmpty() && (configuredLore.isEmpty() || containsDescriptionPlaceholder(configuredLore))) {
            infoDescription = readInfoDescription(folder);
            description = infoDescription;
        }

        // A missing/empty folder.yml description falls back to the legacy info.txt. When the
        // legacy file is the only source, write the equivalent folder.yml once and keep info.txt
        // untouched so existing packs remain recoverable.
        if (!configuredLore.isEmpty()) {
            tempLore = expandDescription(configuredLore, description);
        } else {
            tempLore = new ArrayList<>(description);
        }
        if (tempLore.isEmpty()) {
            tempLore = defaultDisplayLore();
        }
        if (!folderConfigFile.isFile() && !infoDescription.isEmpty()) {
            migrateInfoFile(folderConfigFile, infoDescription);
        }

        this.lore = Collections.unmodifiableList(StringUtils.t(description));
        this.displayMaterial = parsedMaterial;
        this.displayModelData = parsedModelData;
        this.displayItemModel = parsedItemModel;
        this.displayCraftEngineItem = parsedCraftEngineItem;
        this.displayName = parsedName;
        this.displayGlow = parsedGlow;
        this.displaySkullOwner = parsedSkullOwner;
        this.displayLore = Collections.unmodifiableList(StringUtils.t(tempLore));
        this.hash = folder.getPath().hashCode();
        this.songs = Collections.emptyList();
        this.subContainers = Collections.emptyList();
        this.allSongs = Collections.emptyList();
        this.allSongCount = 0;
    }

    public CompletableFuture<Void> loadAsync(File folder) {
        SongDirectoryTraversal.Session traversal;
        try {
            // Pins the root's real path and enforces the depth/directory/song budget for the whole
            // load. A directory link loop or an oversized library now fails the load explicitly
            // instead of recursing until the pool or the heap gives out.
            traversal = SongDirectoryTraversal.open(folder);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        return loadAsync(folder, SONG_LOADER.captureSession(), traversal);
    }

    @SuppressWarnings("deprecation")
    private CompletableFuture<Void> loadAsync(File folder, ManagedSongLoader.Session session,
                                              SongDirectoryTraversal.Session traversal) {
        if (!SONG_LOADER.isActive(session)) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Song loader session is closed"));
        }
        SongDirectoryTraversal.Result listing;
        try {
            listing = traversal.list(folder);
        } catch (IOException e) {
            // A partial library must not be published as if it were complete.
            return CompletableFuture.failedFuture(e);
        }
        List<File> songFiles = listing.songs();
        List<File> folders = listing.directories();
        ArrayList<CompletableFuture<?>> futures = new ArrayList<>();
        List<MusicBoxSong> songsTemp = Collections.synchronizedList(new ArrayList<>(songFiles.size()));
        for (File file : songFiles) {
            futures.add(SONG_LOADER.submit(session, () -> {
                try {
                    MusicBoxSong song = new MusicBoxSong(file, this);
                    songsTemp.add(song);
                } catch (SongNullException e) {
                    MusicBox.getInstance().getLogger().warning("Can't load " + file);
                }
                return null;
            }));
        }
        List<MusicBoxSongContainer> subContainersTemp = Collections.synchronizedList(new ArrayList<>(folders.size()));
        for (File subFolder : folders) {
            CompletableFuture<Void> subFuture = SONG_LOADER.submit(session, () -> {
                MusicBoxSongContainer container = new MusicBoxSongContainer(subFolder, this);
                subContainersTemp.add(container);
                return container;
            }).thenCompose(container -> container.loadAsync(subFolder, session, traversal));
            futures.add(subFuture);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenRun(() -> {
            // Sorting may be expensive: keep it outside the lifecycle monitor. Only publish fields
            // while the captured session is still active, so old completions cannot win after reset.
            List<MusicBoxSong> loadedSongs = Collections.unmodifiableList(
                    sortedBySortKey(new ArrayList<>(songsTemp), MusicBoxSong::getName));
            List<MusicBoxSongContainer> loadedContainers = Collections.unmodifiableList(
                    sortedBySortKey(new ArrayList<>(subContainersTemp), MusicBoxSongContainer::getName));
            List<MusicBoxSong> loadedAllSongs = new ArrayList<>(loadedSongs);
            for (MusicBoxSongContainer container : loadedContainers) {
                loadedAllSongs.addAll(container.getAllSongs());
            }
            List<MusicBoxSong> allLoadedSongs = Collections.unmodifiableList(loadedAllSongs);
            SONG_LOADER.publish(session, () -> {
                this.songs = loadedSongs;
                this.subContainers = loadedContainers;
                this.allSongs = allLoadedSongs;
                this.allSongCount = allLoadedSongs.size();
            });
        });
    }

    public void loadSync(File folder) {
        SongDirectoryTraversal.Session traversal;
        try {
            traversal = SongDirectoryTraversal.open(folder);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot traverse song folder " + folder, e);
        }
        this.loadSync(folder, traversal);
    }

    private void loadSync(File folder, SongDirectoryTraversal.Session traversal) {
        SongDirectoryTraversal.Result listing;
        try {
            listing = traversal.list(folder);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot traverse song folder " + folder, e);
        }
        this.loadSongsSync(listing.songs());
        this.loadSubContainersSync(listing.directories(), traversal);
        this.rebuildAllSongsCache();
    }

    private void loadSubContainersSync(List<File> folders, SongDirectoryTraversal.Session traversal) {
        ArrayList<MusicBoxSongContainer> subContainersTemp = new ArrayList<>(folders.size());
        for (File folder : folders) {
            MusicBoxSongContainer container = new MusicBoxSongContainer(folder, this);
            container.loadSync(folder, traversal);
            subContainersTemp.add(container);
        }
        this.subContainers = Collections.unmodifiableList(
                sortedBySortKey(subContainersTemp, MusicBoxSongContainer::getName));
    }

    @SuppressWarnings("deprecation")
    private void loadSongsSync(List<File> songFiles) {
        ArrayList<MusicBoxSong> songsTemp = new ArrayList<>(songFiles.size());
        for (File file : songFiles) {
            try {
                MusicBoxSong song = new MusicBoxSong(file, this);
                songsTemp.add(song);
            } catch (SongNullException e) {
                MusicBox.getInstance().getLogger().warning("Can't load " + file);
            }
        }
        this.songs = Collections.unmodifiableList(sortedBySortKey(songsTemp, MusicBoxSong::getName));
    }

    private void rebuildAllSongsCache() {
        int totalSize = this.songs.size();
        for (MusicBoxSongContainer subContainer : this.subContainers) {
            totalSize += subContainer.getAllSongCount();
        }
        ArrayList<MusicBoxSong> songsTemp = new ArrayList<>(totalSize);
        songsTemp.addAll(this.songs);
        for (MusicBoxSongContainer subContainer : this.subContainers) {
            songsTemp.addAll(subContainer.getAllSongs());
        }
        this.allSongs = Collections.unmodifiableList(songsTemp);
        this.allSongCount = songsTemp.size();
    }

    // Sort by the plain-text name; replaces the deprecated ChatColor.stripColor (both drop
    // color codes, plain text also handles the MiniMessage tag style).
    private static String sortKey(String name) {
        return MiniMessageUtils.toPlainText(MiniMessageUtils.processComponent(name));
    }

    // Decorate-sort-undecorate. sortKey() MiniMessage-parses the name and serialises a fresh string
    // per call, and a comparator calls it O(n log n) times -- a 279-song folder was ~2200 parses per
    // load, and loadSongsSync/loadSubContainersSync/the async combine all did it. One key per
    // element makes it n.
    private record SortKeyed<T>(String key, T value) {
    }

    private static <T> List<T> sortedBySortKey(List<T> items, java.util.function.Function<T, String> name) {
        List<SortKeyed<T>> keyed = new ArrayList<>(items.size());
        for (T item : items) {
            keyed.add(new SortKeyed<>(sortKey(name.apply(item)), item));
        }
        keyed.sort(Comparator.comparing(SortKeyed::key));
        List<T> sorted = new ArrayList<>(keyed.size());
        for (SortKeyed<T> entry : keyed) {
            sorted.add(entry.value());
        }
        return sorted;
    }

    @Override
    public List<MusicBoxSong> getAllSongs() {
        return this.allSongs;
    }

    @Override
    public int getAllSongCount() {
        return this.allSongCount;
    }

    @Override
    public ItemStack getItemStack() {
        return this.getItemStack(Collections.emptyList());
    }

    @Override
    public ItemStack getItemStack(List<String> extraLines) {
        Material material = this.displayMaterial != null ? this.displayMaterial : Material.CHEST;
        String displayName = resolvePlaceholders(this.displayName != null ? this.displayName : "<gold>" + this.getName() + "</gold>");
        String skullOwner = material == Material.PLAYER_HEAD ? this.displaySkullOwner : null;
        ItemStack chest = ItemUtils.createStack(material, displayName, null, this.displayModelData, this.displayItemModel, this.displayCraftEngineItem);
        ItemMeta meta = chest.getItemMeta();
        if (meta == null) {
            return chest;
        }

        if (material == Material.PLAYER_HEAD) {
            try {
                org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) meta;
                if (skullOwner != null && !skullOwner.isEmpty()) {
                    // UUID first, then a name lookup; setOwningPlayer replaces deprecated setOwner.
                    try {
                        java.util.UUID uuid = java.util.UUID.fromString(skullOwner);
                        skullMeta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(uuid));
                    } catch (IllegalArgumentException ignored) {
                        skullMeta.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(skullOwner));
                    }
                }
                meta = skullMeta;
            } catch (Exception ignored) {
            }
        }

        List<String> baseLore = new ArrayList<>(this.displayLore);
        baseLore.replaceAll(this::resolvePlaceholders);
        List<String> tempLore;
        if (!extraLines.isEmpty()) {
            tempLore = new ArrayList<>(baseLore);
            tempLore.addAll(extraLines);
        } else {
            tempLore = baseLore;
        }
        meta.lore(MiniMessageUtils.processComponents(tempLore));
        chest.setItemMeta(meta);

        if (this.displayGlow) {
            chest = com.huidu.musicboxplus.common.utils.ItemUtils.glow(chest);
        }

        return chest;
    }

    private static List<String> readInfoDescription(File folder) {
        File infoFile = new File(folder, "info.txt");
        if (!infoFile.isFile()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(FileUtils.readFileToList(infoFile));
        } catch (IOException ex) {
            MusicBox plugin = MusicBox.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Failed to read info.txt in " + folder.getPath() + ": " + ex.getMessage());
            }
            return new ArrayList<>();
        }
    }

    private static List<String> readStringList(YamlConfiguration config, String path) {
        if (!config.contains(path)) {
            return new ArrayList<>();
        }
        if (config.isList(path)) {
            return new ArrayList<>(config.getStringList(path));
        }
        String value = config.getString(path);
        return value == null || value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(value));
    }

    private static List<String> expandDescription(List<String> template, List<String> description) {
        List<String> expanded = new ArrayList<>();
        for (String line : template) {
            if (line == null) {
                continue;
            }
            if (line.trim().equals("{description}")) {
                expanded.addAll(description);
            } else if (line.contains("{description}")) {
                if (description.isEmpty()) {
                    String withoutDescription = line.replace("{description}", "");
                    if (!withoutDescription.isEmpty()) {
                        expanded.add(withoutDescription);
                    }
                } else {
                    for (String descriptionLine : description) {
                        expanded.add(line.replace("{description}", descriptionLine));
                    }
                }
            } else {
                expanded.add(line);
            }
        }
        return expanded;
    }

    private static boolean containsDescriptionPlaceholder(List<String> lines) {
        for (String line : lines) {
            if (line != null && line.contains("{description}")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> defaultDisplayLore() {
        return new ArrayList<>(List.of(
                "<gray>Songs: {count}</gray>",
                "<yellow>Click to open</yellow>"
        ));
    }

    private static void migrateInfoFile(File folderConfigFile, List<String> description) {
        if (folderConfigFile.exists() || description.isEmpty() || !StorageAccess.canWriteTo(folderConfigFile)) {
            return;
        }
        YamlConfiguration migrated = new YamlConfiguration();
        migrated.set("display.description", description);
        try {
            migrated.save(folderConfigFile);
            MusicBox plugin = MusicBox.getInstance();
            if (plugin != null) {
                plugin.getLogger().fine("Migrated " + folderConfigFile.getParentFile().getName() + "/info.txt to folder.yml");
            }
        } catch (IOException ex) {
            MusicBox plugin = MusicBox.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Failed to migrate info.txt to " + folderConfigFile.getPath() + ": " + ex.getMessage());
            }
        }
    }

    private String resolvePlaceholders(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("{folder}", this.getName())
                .replace("{count}", String.valueOf(this.getAllSongCount()));
    }

    private String getPath() {
        return this.path;
    }

    public MusicBoxSongContainer findById(int id) {
        if (this.getHash() == id) {
            return this;
        }
        for (MusicBoxSongContainer container : this.subContainers) {
            MusicBoxSongContainer found = container.findById(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public String getNameId() {
        return "CHEST:" + this.getHash();
    }

    @Override
    public List<MusicBoxSongContainer> getSubContainers() {
        return this.subContainers;
    }

    @Override
    public SubSongContainer getParentContainer() {
        return this.getParent();
    }

    public MusicBoxSongContainer getParent() {
        return this.parent;
    }

    @Override
    public List<MusicBoxSong> getSongs() {
        return this.songs;
    }

    @Override
    public String getName() {
        return this.name;
    }

    public List<String> getLore() {
        return this.lore;
    }

    public int getHash() {
        return this.hash;
    }
}
