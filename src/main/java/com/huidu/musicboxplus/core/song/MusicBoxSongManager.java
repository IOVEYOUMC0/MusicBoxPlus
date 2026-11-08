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

public final class MusicBoxSongManager {
    public static final String MASTER_CONTAINER = "MASTER";
    private static final Set<SongContainerFactory<?>> factorySet = new HashSet<SongContainerFactory<?>>();
    private static final SongContainer masterContainer = new MasterContainer();
    // Stable per-music-id adapters so player-music discs resolve to one MusicBoxSong instance.
    private static final Map<java.util.UUID, MusicBoxSong> playerMusicAdapters = new ConcurrentHashMap<>();
    private static final AtomicReference<CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong>> catalogRef =
            new AtomicReference<>(new CatalogSnapshot<>(null, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), 100));
    private static final AtomicBoolean isLoading = new AtomicBoolean(false);
    private static final AtomicBoolean isLoaded = new AtomicBoolean(false);
    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_KEYWORDS_PER_SONG = 200;
    // What separates search tokens: anything that is not a letter or a digit.
    private static final java.util.regex.Pattern TOKEN_SEPARATOR =
            java.util.regex.Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

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

    // Only the asynchronous reload is reachable: every caller (startup, /musicboxplus reload, the
    // web editor's reload) needs the work off the tick thread, and this method's synchronous twin
    // was left behind with no call sites. Loading synchronously would also block a Folia region
    // thread for the whole library scan, so it is deliberately not kept "just in case".
    public static CompletableFuture<Void> reloadAsync(File rootFolder) {
        if (!isLoading.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Songs are already loading"));
        }
        isLoaded.set(false);
        long startTime = System.currentTimeMillis();
        try {
            CompiledSongCache.clear();
            java.util.concurrent.Executor executor =
                    com.huidu.musicboxplus.common.utils.AsyncTaskManager.getInstance().getAsyncExecutor();
            // Folder/config IO and MIDI conversion stay off the calling (possibly region) thread.
            return CompletableFuture.supplyAsync(() -> {
                if (!rootFolder.exists()) {
                    rootFolder.mkdirs();
                    MusicBox.getInstance().getLogger().info(
                            com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(),
                                    "Song folder did not exist and was created automatically: " + rootFolder.getPath(),
                                    "歌曲目录不存在，已自动创建: " + rootFolder.getPath()));
                }
                maybeConvertMidi(rootFolder);
                return new MusicBoxSongContainer(rootFolder, null, false);
            }, executor)
                    .thenComposeAsync(newRoot -> newRoot.loadAsync(rootFolder).thenApply(unused -> newRoot), executor)
                    .thenAcceptAsync(newRoot -> {
                        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = buildCatalog(newRoot);
                        publishCatalog(catalog);
                        isLoaded.set(true);
                        long elapsed = System.currentTimeMillis() - startTime;
                        MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(
                                MusicBox.getInstance(), "Loaded " + catalog.songs.size() + " songs asynchronously in " + elapsed + "ms",
                                "异步加载 " + catalog.songs.size() + " 首歌曲，用时 " + elapsed + "ms"));
                    }, executor).whenComplete((unused, ex) -> {
                        try {
                            if (ex != null) {
                                isLoaded.set(false);
                                Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                                MusicBox.getInstance().getLogger().severe(com.huidu.musicboxplus.common.utils.LogLocale.text(
                                        MusicBox.getInstance(), "Failed to load songs: " + cause.getMessage(), "加载歌曲失败: " + cause.getMessage()));
                            }
                        } finally {
                            isLoading.set(false);
                        }
                    });
        } catch (RuntimeException | Error ex) {
            // Executor lookup/submission can throw before a completion stage exists.
            isLoading.set(false);
            throw ex;
        }
    }

    private static CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> buildCatalog(MusicBoxSongContainer root) {
        List<MusicBoxSong> songs = new ArrayList<>(root.getAllSongs());
        Map<String, MusicBoxSong> names = new HashMap<>();
        Map<String, MusicBoxSong> lowerNames = new HashMap<>();
        Map<Integer, MusicBoxSong> hashes = new HashMap<>();
        for (MusicBoxSong song : songs) {
            names.put(song.getName(), song);
            lowerNames.put(song.getName().toLowerCase(Locale.ROOT), song);
            MusicBoxSong previous = hashes.put(song.getHash(), song);
            if (previous != null && previous != song) {
                MusicBox.getInstance().getLogger().warning(
                        "歌曲哈希碰撞: \"" + previous.getName() + "\" 与 \"" + song.getName()
                                + "\" 的路径哈希相同 (" + song.getHash() + ")，前者将无法通过唱片恢复");
            }
        }
        return new CatalogSnapshot<>(root, songs, names, lowerNames, hashes,
                buildKeywordIndex(songs), catalogRef.get().cacheCapacity);
    }

    private static void publishCatalog(CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog) {
        // Preserve a concurrent cache reconfiguration, without rebuilding the immutable indexes.
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> previous;
        do {
            previous = catalogRef.get();
            if (catalog.cacheCapacity != previous.cacheCapacity) {
                catalog = catalog.withFreshCache(previous.cacheCapacity);
            }
        } while (!catalogRef.compareAndSet(previous, catalog));
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
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
        Map<String, MusicBoxSong> nameMap = catalog.names;
        Map<String, MusicBoxSong> lowerMap = catalog.lowerNames;

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
        String lowerName = name.toLowerCase(Locale.ROOT);
        song = lowerMap.get(lowerName);
        if (song != null) {
            return Optional.of(song);
        }
        song = lowerMap.get(nameWithSpaces.toLowerCase(Locale.ROOT));
        if (song != null) {
            return Optional.of(song);
        }
        song = lowerMap.get(nameWithUnderscores.toLowerCase(Locale.ROOT));
        if (song != null) {
            return Optional.of(song);
        }
        return Optional.empty();
    }

    public static Optional<MusicBoxSong> findSongByHash(int hash) {
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
        return Optional.ofNullable(catalog.hashes.get(hash));
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
        // getOptional, not get: a song hash of 0 is a real hash, and the 0 fallback made such a disc
        // indistinguishable from an untagged one (it fell through to the player-music lookup).
        Integer hash = ItemNbt.getOptional(meta, "song_hash");
        if (hash != null) {
            Optional<MusicBoxSong> song = MusicBoxSongManager.findSongByHash(hash);
            if (song.isPresent()) {
                return song;
            }
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

    public static void clearPlayerMusicAdapters() {
        playerMusicAdapters.clear();
    }

    public static Optional<MusicBoxSong> findPlayableJukeboxSongByItem(ItemStack stack) {
        return findByItem(stack).filter(song -> !song.shouldUseVanillaJukeboxPlayback());
    }

    public static List<MusicBoxSong> searchSongs(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
        SearchQuery searchQuery = SearchQuery.parse(query);
        return catalog.search(searchQuery.cacheKey(), captured -> searchWithIndex(captured, searchQuery));
    }

    private static List<MusicBoxSong> searchWithIndex(
            CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog, SearchQuery searchQuery) {
        String lowerQuery = searchQuery.textQuery();
        if (lowerQuery.isEmpty()) {
            return filterSongs(catalog.songs, searchQuery);
        }
        if (lowerQuery.length() < MIN_KEYWORD_LENGTH) {
            return searchLinear(catalog, searchQuery);
        }

        List<String> queryTokens = tokenizeSearchText(lowerQuery);
        if (queryTokens.isEmpty()) {
            return searchLinear(catalog, searchQuery);
        }

        Set<MusicBoxSong> resultSet = null;
        for (String token : queryTokens) {
            Set<MusicBoxSong> tokenMatches = catalog.keywords.get(token);
            if (tokenMatches == null || tokenMatches.isEmpty()) {
                return searchLinear(catalog, searchQuery);
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
        if (resultSet == null || resultSet.isEmpty()) {
            return searchLinear(catalog, searchQuery);
        }
        return filterSongs(resultSet, searchQuery);
    }

    private static List<MusicBoxSong> searchLinear(
            CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog, SearchQuery searchQuery) {
        return filterSongs(catalog.songs, searchQuery);
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
            normalizedValues.add(value == null ? "" : value.toLowerCase(Locale.ROOT));
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

    private static Map<String, Set<MusicBoxSong>> buildKeywordIndex(List<MusicBoxSong> songs) {
        Map<String, Set<MusicBoxSong>> newIndex = new HashMap<>();
        for (MusicBoxSong song : songs) {
            indexSongKeywords(song, song.getName(), newIndex);
            for (String alias : song.getAliases()) {
                indexSongKeywords(song, alias, newIndex);
            }
            for (String tag : song.getTags()) {
                indexSongKeywords(song, tag, newIndex);
            }
        }
        return newIndex;
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

        String normalized = text.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, Boolean> tokens = new LinkedHashMap<>();
        int maxKeywordLength = Math.max(MIN_KEYWORD_LENGTH, getMaxKeywordLength());
        // Precompiled: String.split(regex) compiles a fresh Pattern per call, and this runs once per
        // song name plus once per alias and per tag on every index rebuild -- tens of thousands of
        // compilations for a large library, each on the main thread during an alias edit.
        for (String token : TOKEN_SEPARATOR.split(normalized)) {
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
        // Replacing the snapshot, rather than clearing a shared map, isolates in-flight searches.
        catalogRef.updateAndGet(catalog -> catalog.withFreshCache(catalog.cacheCapacity));
    }

    public static void refreshSearchIndex() {
        CatalogSnapshot.refreshSameRoot(catalogRef,
                catalog -> catalog.withKeywords(buildKeywordIndex(catalog.songs)));
    }

    public static Optional<MusicBoxSongContainer> findContainerById(int id) {
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
        return catalog.root == null ? Optional.empty() : Optional.ofNullable(catalog.root.findById(id));
    }

    private MusicBoxSongManager() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static SongContainer getMasterContainer() {
        return masterContainer;
    }

    public static List<MusicBoxSong> getAllSongs() {
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
        return catalog.songs;
    }

    public static MusicBoxSongContainer getRootContainer() {
        CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
        return catalog.root;
    }

    public static void reconfigureCache(int searchCacheSize) {
        final int capacity = searchCacheSize > 0 ? searchCacheSize : 100;
        catalogRef.updateAndGet(catalog -> catalog.withFreshCache(capacity));
    }

    static {
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
                String key = token.substring(0, separator).toLowerCase(Locale.ROOT);
                String value = token.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
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
                String.join(" ", textTokens).trim().toLowerCase(Locale.ROOT),
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
            CatalogSnapshot<MusicBoxSongContainer, MusicBoxSong> catalog = catalogRef.get();
            ArrayList<MusicBoxSong> list = new ArrayList<MusicBoxSong>(catalog.songs);
            Collections.shuffle(list);
            return list;
        }

        @Override
        public List<MusicBoxSong> getSongsShuffle() {
            return this.getSongs();
        }
    }
}
