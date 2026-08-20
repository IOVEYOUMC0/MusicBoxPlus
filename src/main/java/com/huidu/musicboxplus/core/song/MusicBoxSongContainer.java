package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.FileUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.core.song.songContainers.types.SubSongContainer;
import org.bukkit.Material;
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
    private final MusicBoxSongContainer parent;
    private volatile List<MusicBoxSongContainer> subContainers;
    private volatile List<MusicBoxSong> songs;
    private volatile List<MusicBoxSong> allSongs;
    private volatile int allSongCount;
    private final String name;
    private final List<String> lore;
    private final int hash;
    private final String path;
    private final Material customDisplayMaterial;
    private final int customDisplayModelData;
    private final String customDisplayName;
    private final boolean customDisplayGlow;
    private final String customDisplaySkullOwner;
    private static volatile ExecutorService songLoader = createSongLoader();

    private static ExecutorService createSongLoader() {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
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
        String parsedName = null;
        boolean parsedGlow = false;
        String parsedSkullOwner = null;

        if (folderConfigFile.isFile()) {
            try {
                org.bukkit.configuration.file.YamlConfiguration config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(folderConfigFile);

                if (config.contains("display.description")) {
                    String description = config.getString("display.description");
                    if (description != null && !description.isEmpty()) {
                        tempLore.add(StringUtils.t(description));
                    }
                }
                if (config.contains("display.item")) {
                    String itemName = config.getString("display.item");
                    if (itemName != null && !itemName.isEmpty()) {
                        try {
                            Material m = Material.valueOf(itemName.toUpperCase());
                            if (m.isItem()) {
                                parsedMaterial = m;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (config.contains("display.custom_model_data")) {
                    parsedModelData = config.getInt("display.custom_model_data", 0);
                }
                if (config.contains("display.name")) {
                    String customName = config.getString("display.name");
                    if (customName != null && !customName.isEmpty()) {
                        parsedName = StringUtils.t(customName);
                    }
                }
                if (config.contains("display.glow")) {
                    parsedGlow = config.getBoolean("display.glow", false);
                }
                if (config.contains("display.skull_owner")) {
                    parsedSkullOwner = config.getString("display.skull_owner");
                }
            } catch (Exception ex) {
                MusicBox.getInstance().getLogger().warning("Failed to read folder.yml in " + folder.getPath() + ": " + ex.getMessage());
            }
        } else {
            File infoFile = new File(folder, "info.txt");
            if (infoFile.isFile()) {
                try {
                    tempLore = FileUtils.readFileToList(infoFile);
                } catch (IOException ex) {
                    MusicBox.getInstance().getLogger().warning("Failed to read info.txt in " + folder.getPath() + ": " + ex.getMessage());
                }
            }
        }

        this.lore = Collections.unmodifiableList(StringUtils.t(tempLore));
        this.customDisplayMaterial = parsedMaterial;
        this.customDisplayModelData = parsedModelData;
        this.customDisplayName = parsedName;
        this.customDisplayGlow = parsedGlow;
        this.customDisplaySkullOwner = parsedSkullOwner;
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
        GUIConfigManager.FolderItemConfig folderConfig = GUIConfigManager.getInstance().getFolderItemConfig();
        List<String> tempLore;

        Material material = this.customDisplayMaterial != null ? this.customDisplayMaterial : folderConfig.getMaterial();
        int customModelData = this.customDisplayModelData > 0 ? this.customDisplayModelData : folderConfig.getCustomModelData();
        String displayName = this.customDisplayName != null ? this.customDisplayName : folderConfig.getNameFormat().replace("{folder}", this.getName());
        String skullOwner = material == Material.PLAYER_HEAD ? this.customDisplaySkullOwner : null;

        ItemStack chest = new ItemStack(material);
        ItemMeta meta = chest.getItemMeta();
        meta.displayName(MiniMessageUtils.processComponent(displayName));
        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
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

        List<String> configLore = folderConfig.getLoreFormat();
        List<String> baseLore;
        if (configLore != null && !configLore.isEmpty()) {
            // The folder's info.txt / folder.yml description lives in this.lore. Expand a
            // {description} line into those lines; if the template has no {description}
            // placeholder, append the description at the end so it always shows.
            boolean hasDescriptionPlaceholder = false;
            baseLore = new ArrayList<>();
            for (String line : configLore) {
                if (line.contains("{description}")) {
                    hasDescriptionPlaceholder = true;
                    if (this.lore != null) {
                        baseLore.addAll(this.lore);
                    }
                    continue;
                }
                baseLore.add(line.replace("{folder}", this.getName())
                        .replace("{count}", String.valueOf(this.getAllSongCount())));
            }
            if (!hasDescriptionPlaceholder && this.lore != null && !this.lore.isEmpty()) {
                baseLore.addAll(this.lore);
            }
        } else {
            baseLore = new ArrayList<>(this.lore);
        }
        if (!extraLines.isEmpty()) {
            tempLore = new ArrayList<>(baseLore);
            tempLore.addAll(extraLines);
        } else {
            tempLore = baseLore;
        }
        meta.lore(MiniMessageUtils.processComponents(tempLore));
        chest.setItemMeta(meta);

        if (this.customDisplayGlow) {
            chest = com.huidu.musicboxplus.common.utils.ItemUtils.glow(chest);
        }

        return chest;
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
