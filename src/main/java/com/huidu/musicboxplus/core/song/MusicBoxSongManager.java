package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.nbt.ItemNbt;
import com.huidu.musicboxplus.core.song.songContainers.SongContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.factory.FolderContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.factory.ListContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.factory.SingletonContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class MusicBoxSongManager {
    public static final String MASTER_CONTAINER = "MASTER";
    private static final Set<SongContainerFactory<?>> factorySet = new HashSet<SongContainerFactory<?>>();
    private static final SongContainer masterContainer = new MasterContainer();
    // Stable per-music-id adapters so player-music discs resolve to one MusicBoxSong instance.
    private static final Map<java.util.UUID, MusicBoxSong> playerMusicAdapters = new ConcurrentHashMap<>();
    private static volatile List<MusicBoxSong> allSongs;
    private static volatile MusicBoxSongContainer rootContainer;
    private static final AtomicReference<Map<String, MusicBoxSong>> nameToSongRef;
    private static final AtomicReference<Map<String, MusicBoxSong>> lowerCaseNameToSongRef;
    private static final AtomicReference<Map<Integer, MusicBoxSong>> hashToSongRef;
    private static final AtomicReference<Map<String, List<MusicBoxSong>>> searchCacheRef;
    private static final ReentrantReadWriteLock searchCacheLock = new ReentrantReadWriteLock();
    private static final AtomicBoolean isLoading;
    private static final AtomicBoolean isLoaded;
    private static final AtomicReference<Map<String, Set<MusicBoxSong>>> keywordIndexRef;
    private static final ReentrantReadWriteLock keywordIndexLock = new ReentrantReadWriteLock();
    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_KEYWORDS_PER_SONG = 200;
    
    private static int getMaxKeywordLength() {
        return MusicBox.getInstance().getConfigObject().getSearch().getMaxKeywordLength();
    }

    private static int getMaxKeywordIndexSize() {
        return Math.max(1, MusicBox.getInstance().getConfigObject().getPerformance().getMaxKeywordIndexSize());
    }

    public static Optional<SongContainer> getContainerById(String str) {
        int id;
        if (str.equals(MASTER_CONTAINER) || str.equalsIgnoreCase("master") || str.equals(Lang.SIGN_MASTER_TEXT.toString())) {
            return Optional.of(masterContainer);
        }
        String[] split = str.split(":");
        if (split.length != 2) {
            return Optional.empty();
        }
        try {
            id = Integer.parseInt(split[1]);
        }
        catch (Exception ex) {
            return Optional.empty();
        }
        return factorySet.stream().filter(c -> c.getKey().equalsIgnoreCase(split[0])).findFirst().map(f -> f.parseContainer(id));
    }

    private static void maybeConvertMidi(File rootFolder) {
        if (!MusicBox.getInstance().getConfigObject().isConvertMidiToNbs()) {
            return;
        }
        try {
            int converted = PlayerSongServices.convertMidiFolder(rootFolder);
            if (converted > 0) {
                MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(
                        MusicBox.getInstance(),
                        "Auto-converted " + converted + " MIDI file(s) to NBS",
                        "已自动将 " + converted + " 个 MIDI 文件转换为 NBS"));
            }
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().warning("MIDI auto-conversion failed: " + e.getMessage());
        }
    }

    public static void reload(File rootFolder) {
        if (isLoading.get()) {
            MusicBox.getInstance().getLogger().warning(
                    com.huidu.musicboxplus.common.utils.LogLocale.text(
                            MusicBox.getInstance(),
                            "Songs are still loading, please wait...",
                            "歌曲仍在加载中，请稍候..."
                    )
            );
            return;
        }
        isLoading.set(true);
        isLoaded.set(false);
        // Reload rebuilds every MusicBoxSong from disk, so anything already compiled describes a
        // file that may since have changed.
        CompiledSongCache.clear();
        long startTime = System.currentTimeMillis();
        maybeConvertMidi(rootFolder);
        rootContainer = new MusicBoxSongContainer(rootFolder, null, false);
        rootContainer.loadSync(rootFolder);
        allSongs = Collections.unmodifiableList(new ArrayList<MusicBoxSong>(rootContainer.getAllSongs()));
        
        Map<String, MusicBoxSong> newNameToSong = new ConcurrentHashMap<>();
        Map<String, MusicBoxSong> newLowerCaseNameToSong = new ConcurrentHashMap<>();
        Map<Integer, MusicBoxSong> newHashToSong = new ConcurrentHashMap<>();
        
        for (MusicBoxSong song : allSongs) {
            newNameToSong.put(song.getName(), song);
            newLowerCaseNameToSong.put(song.getName().toLowerCase(), song);
            // 路径 hashCode 碰撞会让歌曲静默丢失（findSongByHash 只能恢复一首），检测并告警
            MusicBoxSong previous = newHashToSong.put(song.getHash(), song);
            if (previous != null && previous != song) {
                MusicBox.getInstance().getLogger().warning(
                        "歌曲哈希碰撞: \"" + previous.getName() + "\" 与 \"" + song.getName()
                                + "\" 的路径哈希相同 (" + song.getHash() + ")，后者将无法通过唱片恢复"
                );
            }
        }
        
        nameToSongRef.set(Collections.unmodifiableMap(newNameToSong));
        lowerCaseNameToSongRef.set(Collections.unmodifiableMap(newLowerCaseNameToSong));
        hashToSongRef.set(Collections.unmodifiableMap(newHashToSong));
        
        searchCacheLock.writeLock().lock();
        try {
            resetSearchCache();
        } finally {
            searchCacheLock.writeLock().unlock();
        }
        
        buildKeywordIndex();
        isLoading.set(false);
        isLoaded.set(true);
        long endTime = System.currentTimeMillis();
        MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Loaded " + allSongs.size() + " songs in " + (endTime - startTime) + "ms", "已加载 " + allSongs.size() + " 首歌曲，用时 " + (endTime - startTime) + "ms"));
    }

    public static CompletableFuture<Void> reloadAsync(File rootFolder) {
        if (isLoading.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Songs are already loading"));
        }
        isLoading.set(true);
        isLoaded.set(false);
        // Same as reload(): the async rebuild reads every song from disk, so any compiled song
        // describes a file that may since have changed. Clear now so no stale compiled entry is
        // played while the reload is still in flight.
        CompiledSongCache.clear();
        long startTime = System.currentTimeMillis();
        
        if (!rootFolder.exists()) {
            rootFolder.mkdirs();
            MusicBox.getInstance().getLogger().info(
                    com.huidu.musicboxplus.common.utils.LogLocale.text(
                            MusicBox.getInstance(),
                            "Song folder did not exist and was created automatically: " + rootFolder.getPath(),
                            "歌曲目录不存在，已自动创建: " + rootFolder.getPath()
                    )
            );
        }
        
        rootContainer = new MusicBoxSongContainer(rootFolder, null, false);
        return CompletableFuture.runAsync(() -> maybeConvertMidi(rootFolder))
                .thenCompose(ignored -> rootContainer.loadAsync(rootFolder))
                .thenRun(() -> {
            allSongs = Collections.unmodifiableList(new ArrayList<MusicBoxSong>(rootContainer.getAllSongs()));
            
            Map<String, MusicBoxSong> newNameToSong = new ConcurrentHashMap<>();
            Map<String, MusicBoxSong> newLowerCaseNameToSong = new ConcurrentHashMap<>();
            Map<Integer, MusicBoxSong> newHashToSong = new ConcurrentHashMap<>();
            
            for (MusicBoxSong song : allSongs) {
                newNameToSong.put(song.getName(), song);
                newLowerCaseNameToSong.put(song.getName().toLowerCase(), song);
                // 路径 hashCode 碰撞会让歌曲静默丢失（findSongByHash 只能恢复一首），检测并告警
                MusicBoxSong previous = newHashToSong.put(song.getHash(), song);
                if (previous != null && previous != song) {
                    MusicBox.getInstance().getLogger().warning(
                            "歌曲哈希碰撞: \"" + previous.getName() + "\" 与 \"" + song.getName()
                                    + "\" 的路径哈希相同 (" + song.getHash() + ")，后者将无法通过唱片恢复"
                    );
                }
            }
            
            nameToSongRef.set(Collections.unmodifiableMap(newNameToSong));
            lowerCaseNameToSongRef.set(Collections.unmodifiableMap(newLowerCaseNameToSong));
            hashToSongRef.set(Collections.unmodifiableMap(newHashToSong));
            
            searchCacheLock.writeLock().lock();
            try {
                resetSearchCache();
            } finally {
                searchCacheLock.writeLock().unlock();
            }
            
            buildKeywordIndex();
            isLoading.set(false);
            isLoaded.set(true);
            long endTime = System.currentTimeMillis();
            MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Loaded " + allSongs.size() + " songs asynchronously in " + (endTime - startTime) + "ms", "异步加载 " + allSongs.size() + " 首歌曲，用时 " + (endTime - startTime) + "ms"));
        }).whenComplete((unused, ex) -> {
            if (ex != null) {
                isLoading.set(false);
                isLoaded.set(false);
                Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                MusicBox.getInstance().getLogger().severe(com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Failed to load songs: " + cause.getMessage(), "加载歌曲失败: " + cause.getMessage()));
            }
        });
    }

    public static boolean isLoading() {
        return isLoading.get();
    }

    public static boolean isLoaded() {
        return isLoaded.get();
    }

    public static Optional<MusicBoxSong> findByName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        Map<String, MusicBoxSong> nameMap = nameToSongRef.get();
        Map<String, MusicBoxSong> lowerMap = lowerCaseNameToSongRef.get();
        
        MusicBoxSong song = nameMap.get(name);
        if (song != null) {
            return Optional.of(song);
        }
        String nameWithSpaces = name.replace("_", " ");
        song = nameMap.get(nameWithSpaces);
        if (song != null) {
            return Optional.of(song);
        }
        String nameWithUnderscores = name.replace(" ", "_");
        song = nameMap.get(nameWithUnderscores);
        if (song != null) {
            return Optional.of(song);
        }
        String lowerName = name.toLowerCase();
        song = lowerMap.get(lowerName);
        if (song != null) {
            return Optional.of(song);
        }
        song = lowerMap.get(nameWithSpaces.toLowerCase());
        if (song != null) {
            return Optional.of(song);
        }
        song = lowerMap.get(nameWithUnderscores.toLowerCase());
        if (song != null) {
            return Optional.of(song);
        }
        return Optional.empty();
    }

    public static Optional<MusicBoxSong> findSongByHash(int hash) {
        return Optional.ofNullable(hashToSongRef.get().get(hash));
    }

    public static Optional<MusicBoxSong> findByItem(ItemStack stack) {
        // No item meta -> no PDC -> neither song_hash nor player_music_id can be present. Short-circuit
        // before getItemMeta(), which would clone a full CraftMetaItem for every non-song item --
        // worst case once per chest slot on playlist refresh. The meta is then reused for both
        // lookups, so a song disc is cloned exactly once per scan.
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        int hash = ItemNbt.get(meta, "song_hash");
        if (hash != 0) {
            return MusicBoxSongManager.findSongByHash(hash);
        }
        return findPlayerMusicByItem(meta);
    }

    // Resolves a player-music disc (carrying a player_music_id) to a playable adapter song.
    private static Optional<MusicBoxSong> findPlayerMusicByItem(ItemMeta meta) {
        Optional<PlayerMusicSource> music = PlayerSongServices.findPlayerMusicByDisc(meta);
        if (music.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(getPlayerMusicAdapter(music.get()));
    }

    private static MusicBoxSong getPlayerMusicAdapter(PlayerMusicSource music) {
        java.util.UUID musicId = music.getUniqueId();
        MusicBoxSong cached = playerMusicAdapters.get(musicId);
        if (cached != null) {
            return cached;
        }
        return playerMusicAdapters.computeIfAbsent(musicId, id -> MusicBoxSong.fromPlayerMusic(music));
    }

    // Drops the cached adapter for musicId so the next disc resolution rebuilds it from the current
    // music. Must be called whenever the underlying player music is saved, edited, renamed or
    // deleted, or discs keep playing a stale snapshot and the cache grows unbounded.
    public static void invalidatePlayerMusicAdapter(java.util.UUID musicId) {
        if (musicId != null) {
            playerMusicAdapters.remove(musicId);
        }
    }

    public static Optional<MusicBoxSong> findPlayableJukeboxSongByItem(ItemStack stack) {
        return findByItem(stack).filter(song -> !song.shouldUseVanillaJukeboxPlayback());
    }

    public static List<MusicBoxSong> searchSongs(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        SearchQuery searchQuery = SearchQuery.parse(query);
        String cacheKey = searchQuery.cacheKey();
        
        searchCacheLock.readLock().lock();
        try {
            List<MusicBoxSong> cached = searchCacheRef.get().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        } finally {
            searchCacheLock.readLock().unlock();
        }
        
        List<MusicBoxSong> results = searchWithIndex(searchQuery);
        List<MusicBoxSong> immutableResults = Collections.unmodifiableList(results);
        
        searchCacheLock.writeLock().lock();
        try {
            // Put in place: the backing map is a synchronizedMap(access-order LinkedHashMap) with
            // removeEldestEntry LRU eviction. Copying it on write would be O(n) per miss and would
            // swap in a plain, unbounded map, losing the LRU cap.
            searchCacheRef.get().put(cacheKey, immutableResults);
        } finally {
            searchCacheLock.writeLock().unlock();
        }

        return immutableResults;
    }
    
    private static List<MusicBoxSong> searchWithIndex(SearchQuery searchQuery) {
        String lowerQuery = searchQuery.textQuery();
        if (lowerQuery.isEmpty()) {
            return filterSongs(allSongs, searchQuery);
        }
        if (lowerQuery.length() < MIN_KEYWORD_LENGTH) {
            return searchLinear(searchQuery);
        }

        List<String> queryTokens = tokenizeSearchText(lowerQuery);
        if (queryTokens.isEmpty()) {
            return searchLinear(searchQuery);
        }

        Set<MusicBoxSong> resultSet = null;
        keywordIndexLock.readLock().lock();
        try {
            Map<String, Set<MusicBoxSong>> index = keywordIndexRef.get();
            for (String token : queryTokens) {
                Set<MusicBoxSong> tokenMatches = index.get(token);
                if (tokenMatches == null || tokenMatches.isEmpty()) {
                    return searchLinear(searchQuery);
                }
                if (resultSet == null) {
                    resultSet = new HashSet<>(tokenMatches);
                } else {
                    resultSet.retainAll(tokenMatches);
                }
                if (resultSet.isEmpty()) {
                    return Collections.emptyList();
                }
            }
        } finally {
            keywordIndexLock.readLock().unlock();
        }

        if (resultSet == null || resultSet.isEmpty()) {
            return searchLinear(searchQuery);
        }

        return filterSongs(resultSet, searchQuery);
    }
    
    private static List<MusicBoxSong> searchLinear(SearchQuery searchQuery) {
        if (allSongs == null || allSongs.isEmpty()) {
            return Collections.emptyList();
        }
        return filterSongs(allSongs, searchQuery);
    }

    private static List<MusicBoxSong> filterSongs(Iterable<MusicBoxSong> source, SearchQuery searchQuery) {
        ArrayList<MusicBoxSong> results = new ArrayList<>();
        for (MusicBoxSong song : source) {
            if (!searchQuery.textQuery().isEmpty() && !song.matchesSearch(searchQuery.textQuery())) {
                continue;
            }
            if (!matchesFilters(song, searchQuery)) {
                continue;
            }
            results.add(song);
        }
        return results;
    }

    private static boolean matchesFilters(MusicBoxSong song, SearchQuery query) {
        if (matchesContainsAll(song.getTags(), query.tags())) {
            return false;
        }
        if (matchesContainsAll(Collections.singleton(song.getAuthor()), query.authors())) {
            return false;
        }
        if (matchesContainsAll(Collections.singleton(song.getOriginalAuthor()), query.originalAuthors())) {
            return false;
        }
        String containerName = song.getContainer() != null ? song.getContainer().getName() : "";
        if (matchesContainsAll(Collections.singleton(containerName), query.folders())) {
            return false;
        }
        if (!query.types().isEmpty()) {
            SearchSongType songType = song.shouldUseVanillaJukeboxPlayback() ? SearchSongType.RECORD : SearchSongType.MUSICBOX;
            return query.types().contains(songType);
        }
        return true;
    }

    private static boolean matchesContainsAll(Iterable<String> values, List<String> filters) {
        if (filters.isEmpty()) {
            return false;
        }
        ArrayList<String> normalizedValues = new ArrayList<>();
        for (String value : values) {
            normalizedValues.add(value == null ? "" : value.toLowerCase());
        }
        for (String filter : filters) {
            boolean matched = false;
            for (String value : normalizedValues) {
                if (value.contains(filter)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return true;
            }
        }
        return false;
    }
    
    private static void buildKeywordIndex() {
        Map<String, Set<MusicBoxSong>> newIndex = new HashMap<>();
        
        for (MusicBoxSong song : allSongs) {
            indexSongKeywords(song, song.getName().toLowerCase(), newIndex);
            for (String alias : song.getAliases()) {
                indexSongKeywords(song, alias.toLowerCase(), newIndex);
            }
            for (String tag : song.getTags()) {
                indexSongKeywords(song, tag.toLowerCase(), newIndex);
            }
        }
        
        keywordIndexLock.writeLock().lock();
        try {
            keywordIndexRef.set(Collections.unmodifiableMap(newIndex));
        } finally {
            keywordIndexLock.writeLock().unlock();
        }
    }
    
    private static void indexSongKeywords(MusicBoxSong song, String text, Map<String, Set<MusicBoxSong>> index) {
        if (text == null || text.length() < MIN_KEYWORD_LENGTH) {
            return;
        }

        int keywordCount = 0;
        int maxKeywordIndexSize = getMaxKeywordIndexSize();
        for (String keyword : tokenizeSearchText(text)) {
            if (keywordCount >= MAX_KEYWORDS_PER_SONG || index.size() >= maxKeywordIndexSize) {
                return;
            }
            if (index.computeIfAbsent(keyword, k -> new HashSet<>()).add(song)) {
                keywordCount++;
            }
        }
    }

    private static List<String> tokenizeSearchText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String normalized = text.toLowerCase().replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, Boolean> tokens = new LinkedHashMap<>();
        int maxKeywordLength = Math.max(MIN_KEYWORD_LENGTH, getMaxKeywordLength());
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (token.length() < MIN_KEYWORD_LENGTH) {
                continue;
            }
            if (token.length() > maxKeywordLength) {
                token = token.substring(0, maxKeywordLength);
            }
            tokens.put(token, Boolean.TRUE);
        }

        if (tokens.isEmpty() && normalized.length() >= MIN_KEYWORD_LENGTH) {
            String fallback = normalized.length() > maxKeywordLength ? normalized.substring(0, maxKeywordLength) : normalized;
            tokens.put(fallback, Boolean.TRUE);
        }
        return new ArrayList<>(tokens.keySet());
    }

    public static void clearSearchCache() {
        searchCacheLock.writeLock().lock();
        try {
            resetSearchCache();
        } finally {
            searchCacheLock.writeLock().unlock();
        }
    }

    public static void refreshSearchIndex() {
        searchCacheLock.writeLock().lock();
        try {
            resetSearchCache();
        } finally {
            searchCacheLock.writeLock().unlock();
        }
        buildKeywordIndex();
    }

    private static void resetSearchCache() {
        final int maxSize = getSearchCacheSize();
        searchCacheRef.set(Collections.synchronizedMap(new LinkedHashMap<String, List<MusicBoxSong>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<MusicBoxSong>> eldest) {
                return this.size() > maxSize;
            }
        }));
    }

    public static Optional<MusicBoxSongContainer> findContainerById(int id) {
        return Optional.ofNullable(rootContainer.findById(id));
    }

    private MusicBoxSongManager() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static SongContainer getMasterContainer() {
        return masterContainer;
    }

    public static List<MusicBoxSong> getAllSongs() {
        return allSongs;
    }

    public static MusicBoxSongContainer getRootContainer() {
        return rootContainer;
    }
    
    public static void reconfigureCache(int searchCacheSize) {
        if (searchCacheSize <= 0) {
            searchCacheSize = 100;
        }
        final int maxSize = searchCacheSize;
        searchCacheLock.writeLock().lock();
        try {
            searchCacheRef.set(Collections.synchronizedMap(new LinkedHashMap<String, List<MusicBoxSong>>(16, 0.75f, true){
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MusicBoxSong>> eldest) {
                    return this.size() > maxSize;
                }
            }));
        } finally {
            searchCacheLock.writeLock().unlock();
        }
    }

    static {
        allSongs = new ArrayList<>();
        nameToSongRef = new AtomicReference<>(Collections.emptyMap());
        lowerCaseNameToSongRef = new AtomicReference<>(Collections.emptyMap());
        hashToSongRef = new AtomicReference<>(Collections.emptyMap());
        searchCacheRef = new AtomicReference<>(Collections.synchronizedMap(new LinkedHashMap<String, List<MusicBoxSong>>(16, 0.75f, true){
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<MusicBoxSong>> eldest) {
                return this.size() > 100;
            }
        }));
        keywordIndexRef = new AtomicReference<>(Collections.emptyMap());
        isLoading = new AtomicBoolean(false);
        isLoaded = new AtomicBoolean(false);
        factorySet.add(new FolderContainerFactory());
        factorySet.add(new SingletonContainerFactory());
        factorySet.add(new ListContainerFactory());
    }
    
    public static void initializeCache() {
        CompiledSongCache.setCapacity(getCompiledSongCacheSize());
        int cacheSize = getSearchCacheSize();
        reconfigureCache(cacheSize);
        MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Search cache initialized with size: " + cacheSize, "搜索缓存已初始化，容量: " + cacheSize));
    }
    
    private static int getCompiledSongCacheSize() {
        try {
            return MusicBox.getInstance().getConfigObject().getCache().getCompiledSongCacheSize();
        } catch (Exception e) {
            return 64;
        }
    }

    private static int getSearchCacheSize() {
        try {
            return MusicBox.getInstance().getConfigObject().getCache().getSearchCacheSize();
        } catch (Exception e) {
            return 100;
        }
    }

    private enum SearchSongType {
        MUSICBOX,
        RECORD;

        private static SearchSongType fromToken(String token) {
            return switch (token) {
                case "musicboxplus", "song", "normal", "nbs" -> MUSICBOX;
                case "record", "vanilla", "jukebox", "disc", "disk" -> RECORD;
                default -> null;
            };
        }
    }

    private record SearchQuery(
        String textQuery,
        List<String> tags,
        List<String> authors,
        List<String> originalAuthors,
        List<String> folders,
        Set<SearchSongType> types
    ) {
        private static final Set<String> FILTER_KEYS = Set.of("tag", "author", "original", "folder", "container", "type");

        private static SearchQuery parse(String rawQuery) {
            if (rawQuery == null) {
                return new SearchQuery("", List.of(), List.of(), List.of(), List.of(), Set.of());
            }
            ArrayList<String> textTokens = new ArrayList<>();
            ArrayList<String> tags = new ArrayList<>();
            ArrayList<String> authors = new ArrayList<>();
            ArrayList<String> originalAuthors = new ArrayList<>();
            ArrayList<String> folders = new ArrayList<>();
            LinkedHashSet<SearchSongType> types = new LinkedHashSet<>();

            for (String token : rawQuery.trim().split("\\s+")) {
                if (token.isBlank()) {
                    continue;
                }
                int separator = token.indexOf(':');
                if (separator <= 0 || separator == token.length() - 1) {
                    textTokens.add(token);
                    continue;
                }
                String key = token.substring(0, separator).toLowerCase();
                String value = token.substring(separator + 1).trim().toLowerCase();
                if (value.isEmpty() || !FILTER_KEYS.contains(key)) {
                    textTokens.add(token);
                    continue;
                }
                switch (key) {
                    case "tag" -> tags.add(value);
                    case "author" -> authors.add(value);
                    case "original" -> originalAuthors.add(value);
                    case "folder", "container" -> folders.add(value);
                    case "type" -> {
                        SearchSongType type = SearchSongType.fromToken(value);
                        if (type != null) {
                            types.add(type);
                        } else {
                            textTokens.add(token);
                        }
                    }
                    default -> textTokens.add(token);
                }
            }

            return new SearchQuery(
                String.join(" ", textTokens).trim().toLowerCase(),
                List.copyOf(tags),
                List.copyOf(authors),
                List.copyOf(originalAuthors),
                List.copyOf(folders),
                Set.copyOf(types)
            );
        }

        private String cacheKey() {
            return "text=" + this.textQuery
                + "|tag=" + String.join(",", this.tags)
                + "|author=" + String.join(",", this.authors)
                + "|original=" + String.join(",", this.originalAuthors)
                + "|folder=" + String.join(",", this.folders)
                + "|type=" + this.types.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
        }
    }
    private static class MasterContainer
    implements SongContainer {
        private MasterContainer() {
        }

        @Override
        public String getNameId() {
            return MASTER_CONTAINER;
        }

        @Override
        public List<MusicBoxSong> getSongs() {
            ArrayList<MusicBoxSong> list = new ArrayList<MusicBoxSong>(allSongs);
            Collections.shuffle(list);
            return list;
        }

        @Override
        public List<MusicBoxSong> getSongsShuffle() {
            return this.getSongs();
        }
    }
}
