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
    private static volatile ExecutorService songLoader = createSongLoader();

    private static ExecutorService createSongLoader() {
        int threads = Math.max(2, Math.min(SONG_LOADER_THREADS, Runtime.getRuntime().availableProcessors()));
        // Unbounded queue, no rejection policy. The task count is bounded by the number of song
        // files, so the queue cannot grow without limit, and the bounded queue it replaces filled
        // immediately (the submission loop is pure in-memory while each worker does a whole-file
        // read) -- at which point CallerRunsPolicy ran one full song load, plus any MIDI conversion,
        // on whichever thread submitted it. That submitter is the CompletableFuture continuation,
        // i.e. a ForkJoinPool.commonPool thread shared with the rest of the JVM.
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "MusicBox-SongLoader");
            t.setDaemon(true);
            return t;
        });
    }

    private static ExecutorService getSongLoader() {
        ExecutorService loader = songLoader;
        if (loader == null || loader.isShutdown() || loader.isTerminated()) {
            synchronized (MusicBoxSongContainer.class) {
                loader = songLoader;
                if (loader == null || loader.isShutdown() || loader.isTerminated()) {
                    songLoader = loader = createSongLoader();
                }
            }
        }
        return loader;
    }

    public static void shutdownLoader() {
        ExecutorService loader = songLoader;
        if (loader == null) {
            return;
        }
        loader.shutdown();
        try {
            if (!loader.awaitTermination(10L, TimeUnit.SECONDS)) {
                loader.shutdownNow();
            }
        } catch (InterruptedException e) {
            loader.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            synchronized (MusicBoxSongContainer.class) {
                if (songLoader == loader) {
                    songLoader = null;
                }
            }
        }
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

    @SuppressWarnings({"deprecation", "rawtypes", "unchecked"})
    public CompletableFuture<Void> loadAsync(File folder) {
        ExecutorService loader = getSongLoader();
        File[] songFiles = folder.listFiles(f -> f.getName().endsWith(".nbs"));
        File[] folders = folder.listFiles(File::isDirectory);
        if (songFiles == null) {
            songFiles = new File[]{};
        }
        if (folders == null) {
            folders = new File[]{};
        }
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
        List songsTemp = Collections.synchronizedList(new ArrayList(songFiles.length));
        for (File file : songFiles) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    MusicBoxSong song = new MusicBoxSong(file, this);
                    songsTemp.add(song);
                } catch (SongNullException e) {
                    MusicBox.getInstance().getLogger().warning("Can't load " + file);
                }
            }, loader));
        }
        List subContainersTemp = Collections.synchronizedList(new ArrayList(folders.length));
        ArrayList<CompletionStage> subFolderFutures = new ArrayList<>();
        for (File subFolder : folders) {
            CompletionStage subFuture = CompletableFuture.supplyAsync(() -> {
                MusicBoxSongContainer container = new MusicBoxSongContainer(subFolder, this);
                subContainersTemp.add(container);
                return container;
            }, loader).thenCompose(container -> container.loadAsync(subFolder));
            subFolderFutures.add(subFuture);
        }
        return ((CompletableFuture) CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenCombine(CompletableFuture.allOf(subFolderFutures.toArray(new CompletableFuture[0])), (a, b) -> null))
            .thenRun(() -> {
                ArrayList<MusicBoxSong> sortedSongs = new ArrayList<>(songsTemp);
                sortedSongs.sort(Comparator.comparing(song -> sortKey(song.getName())));
                this.songs = Collections.unmodifiableList(sortedSongs);

                ArrayList<MusicBoxSongContainer> sortedContainers = new ArrayList<>(subContainersTemp);
                sortedContainers.sort(Comparator.comparing(container -> sortKey(container.getName())));
                this.subContainers = Collections.unmodifiableList(sortedContainers);
                this.rebuildAllSongsCache();
            });
    }

    public void loadSync(File folder) {
        File[] songFiles = folder.listFiles(f -> f.getName().endsWith(".nbs"));
        File[] folders = folder.listFiles(File::isDirectory);
        if (songFiles == null) {
            songFiles = new File[]{};
        }
        if (folders == null) {
            folders = new File[]{};
        }
        this.loadSongsSync(songFiles);
        this.loadSubContainersSync(folders);
        this.rebuildAllSongsCache();
    }

    private void loadSubContainersSync(File[] folders) {
        ArrayList<MusicBoxSongContainer> subContainersTemp = new ArrayList<>(folders.length);
        for (File folder : folders) {
            MusicBoxSongContainer container = new MusicBoxSongContainer(folder, this);
            container.loadSync(folder);
            subContainersTemp.add(container);
        }
        subContainersTemp.sort(Comparator.comparing(container -> sortKey(container.getName())));
        this.subContainers = Collections.unmodifiableList(subContainersTemp);
    }

    @SuppressWarnings("deprecation")
    private void loadSongsSync(File[] songFiles) {
        ArrayList<MusicBoxSong> songsTemp = new ArrayList<>(songFiles.length);
        for (File file : songFiles) {
            try {
                MusicBoxSong song = new MusicBoxSong(file, this);
                songsTemp.add(song);
            } catch (SongNullException e) {
                MusicBox.getInstance().getLogger().warning("Can't load " + file);
            }
        }
        songsTemp.sort(Comparator.comparing(song -> sortKey(song.getName())));
        this.songs = Collections.unmodifiableList(songsTemp);
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
