package com.huidu.musicboxplus.module.jukebox;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.FaceUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.jukebox.minecraft.IJukebox;
import com.huidu.musicboxplus.module.jukebox.minecraft.JukeboxFactory;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

class JukeboxPlaylistImpl implements IPlayList {
    private final Location jukeboxLoc;
    private MusicBoxSong cachedSong;
    private boolean shuffleMode = false;
    private volatile List<ChestIndex> cachedOrderedSongs = Collections.emptyList();
    private static final Random SHARED_RANDOM = new Random();

    public JukeboxPlaylistImpl(Location jukebox) throws JukeboxPlaylistInitException {
        MusicBoxSong startSongTemp;
        this.jukeboxLoc = jukebox;
        IJukebox jc = this.getCustom();
        if (jc == null) {
            throw new JukeboxPlaylistInitException("Location does not contains jukebox");
        }
        @Nullable Inventory inv = this.getChestInventory();
        // Tracks whether getByIndex already filled the cache, so the chest isn't scanned twice
        boolean cachePopulated = false;
        if (jc.isEmpty()) {
            if (inv != null) {
                ChestIndex s = this.getByIndex(inv, 0);
                cachePopulated = true;
                if (s == null) {
                    startSongTemp = null;
                } else {
                    startSongTemp = s.getSong();
                    inv.setItem(s.getIndex(), null);
                    this.setJukeboxItem(jc, s.getStack(), startSongTemp);
                }
            } else {
                startSongTemp = null;
            }
        } else {
            startSongTemp = MusicBoxSongManager.findByItem(jc.getJukebox()).orElse(null);
            if (startSongTemp == null) {
                jc.eject();
            }
        }
        this.cachedSong = startSongTemp;
        if (startSongTemp == null && inv != null) {
            ChestIndex s = this.getByIndex(inv, 0);
            cachePopulated = true;
            if (s != null) {
                startSongTemp = s.getSong();
                inv.setItem(s.getIndex(), null);
                this.setJukeboxItem(jc, s.getStack(), startSongTemp);
                this.cachedSong = startSongTemp;
            }
        }
        if (inv != null && !cachePopulated) {
            this.refreshOrderedSongsCache(inv);
        }
    }

    private Jukebox getJukebox() {
        BlockState state = this.jukeboxLoc.getBlock().getState();
        if (state instanceof Jukebox jukebox) {
            return jukebox;
        }
        return null;
    }

    @Nullable
    private Inventory getChestInventory() {
        Jukebox box = this.getJukebox();
        if (box == null) {
            return null;
        }
        return this.getChestInventory(box);
    }

    // Takes an already-fetched Jukebox so the adjacent-container lookup doesn't create a
    // second BlockState snapshot.
    @Nullable
    private Inventory getChestInventory(@NotNull Jukebox box) {
        @NotNull Block b = box.getBlock();
        Chest chest = FaceUtils.getRelativeAround(b, Chest.class);
        if (chest != null) {
            return chest.getInventory();
        }
        // No chest adjacent: fall back to the other container types (barrel, shulker box, ...)
        return FaceUtils.getAdjacentInventory(b);
    }

    private void swapItems(Inventory inv, IJukebox cBox, Supplier<ChestIndex> nextItemGetter) {
        if (cBox == null) {
            return;
        }
        ChestIndex nextItem = nextItemGetter.get();
        if (nextItem == null) {
            return;
        }
        inv.setItem(nextItem.getIndex(), null);
        ItemStack currentJukeboxItem = cBox.getJukebox();
        if (currentJukeboxItem != null) {
            inv.setItem(nextItem.getIndex(), currentJukeboxItem);
        }
        this.setJukeboxItem(cBox, nextItem.getStack(), nextItem.getSong());
        this.refreshOrderedSongsCache(inv);
    }

    private void rotateNextSong(Inventory inv, IJukebox cBox) {
        List<ChestIndex> songs = this.getOrderedSongs(inv);
        if (songs.isEmpty()) {
            return;
        }
        ChestIndex nextItem = songs.getFirst();
        ItemStack currentJukeboxItem = cBox.getJukebox();
        for (int i = 1; i < songs.size(); i++) {
            ChestIndex current = songs.get(i);
            ChestIndex previous = songs.get(i - 1);
            inv.setItem(previous.getIndex(), current.getStack());
        }
        ChestIndex last = songs.getLast();
        inv.setItem(last.getIndex(), currentJukeboxItem == null || currentJukeboxItem.isEmpty() ? null : currentJukeboxItem);
        this.setJukeboxItem(cBox, nextItem.getStack(), nextItem.getSong());
        this.refreshOrderedSongsCache(inv);
    }

    private List<ChestIndex> getOrderedSongs(Inventory inventory) {
        List<ChestIndex> songs = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            MusicBoxSong song = MusicBoxSongManager.findPlayableJukeboxSongByItem(item).orElse(null);
            if (song == null) {
                continue;
            }
            songs.add(new ChestIndex(slot, item, song));
        }
        return songs;
    }

    private void refreshOrderedSongsCache(Inventory inventory) {
        this.cachedOrderedSongs = Collections.unmodifiableList(this.getOrderedSongs(inventory));
    }

    // Returns the chest's songs ordered by slot, always rescanning the inventory's current
    // contents.
    //
    // Reusing a non-empty cache is deliberately not an option: the list carries both slot
    // indexes and ItemStack snapshots, and setSong/back write those straight back into chest
    // slots. From a stale list, a disc the player already took gets written back (duplication)
    // and whatever now sits in that slot is overwritten (item loss). Correctness here must not
    // depend on some event firing to keep a cache fresh.
    //
    // The cost is acceptable: only low-frequency write paths (track change, GUI click, redstone
    // pulse) reach here, at most 54 slots, and findPlayableJukeboxSongByItem short-circuits on
    // items without meta.
    private List<ChestIndex> getCachedOrRefreshOrderedSongs(Inventory inventory) {
        this.refreshOrderedSongsCache(inventory);
        return this.cachedOrderedSongs;
    }

    @Override
    public void next() {
        Jukebox box = this.getJukebox();
        if (box == null) {
            this.updateCachedSong();
            return;
        }
        Inventory inv = this.getChestInventory(box);
        if (inv != null) {
            IJukebox cBox = JukeboxFactory.getJukebox(box);
            if (shuffleMode) {
                this.swapItems(inv, cBox, () -> this.getRandomSong(inv));
            } else {
                this.rotateNextSong(inv, cBox);
            }
        }
        this.updateCachedSong();
    }

    private ChestIndex getRandomSong(Inventory inventory) {
        List<ChestIndex> songs = this.getCachedOrRefreshOrderedSongs(inventory);
        if (songs.isEmpty()) {
            return null;
        }
        return songs.get(SHARED_RANDOM.nextInt(songs.size()));
    }

    private void updateCachedSong() {
        IJukebox j = this.getCustom();
        if (j != null) {
            ItemStack item = j.getJukebox();
            if (item != null && !item.isEmpty()) {
                this.cachedSong = MusicBoxSongManager.findByItem(item).orElse(null);
            } else {
                this.cachedSong = null;
            }
        }
    }

    private void back(Inventory inventory, IJukebox cBox) {
        if (cBox == null) {
            return;
        }
        List<ChestIndex> songs = this.getCachedOrRefreshOrderedSongs(inventory);
        if (songs.isEmpty()) {
            return;
        }
        ChestIndex previousItem = songs.getLast();
        ItemStack currentJukeboxItem = cBox.getJukebox();
        invSet(inventory, previousItem.getIndex(), null);
        if (currentJukeboxItem != null && !currentJukeboxItem.isEmpty()) {
            for (int i = songs.size() - 1; i > 0; i--) {
                invSet(inventory, songs.get(i).getIndex(), songs.get(i - 1).getStack());
            }
            invSet(inventory, songs.getFirst().getIndex(), currentJukeboxItem);
        } else {
            for (int i = songs.size() - 1; i > 0; i--) {
                invSet(inventory, songs.get(i).getIndex(), songs.get(i - 1).getStack());
            }
            invSet(inventory, songs.getFirst().getIndex(), null);
        }
        this.setJukeboxItem(cBox, previousItem.getStack(), previousItem.getSong());
        this.refreshOrderedSongsCache(inventory);
    }

    @Override
    public List<MusicBoxSong> getNextSongs(int count) {
        @Nullable Inventory inv = this.getChestInventory();
        if (inv == null) {
            return Collections.emptyList();
        }
        // 一次扫描拿到有序歌曲列表，避免 getByIndex 每次调用都重扫整个容器
        List<ChestIndex> songs = this.getCachedOrRefreshOrderedSongs(inv);
        List<MusicBoxSong> list = new ArrayList<>(Math.min(count, songs.size()));
        int end = Math.min(count, songs.size());
        for (int i = 0; i < end; i++) {
            list.add(songs.get(i).getSong());
        }
        return list;
    }

    @Override
    public List<MusicBoxSong> getPrevSongs(int count) {
        @Nullable Inventory inv = this.getChestInventory();
        if (inv == null) {
            return Collections.emptyList();
        }
        List<MusicBoxSong> list = new ArrayList<>(Math.min(count, inv.getSize()));
        int c = 0;
        for (int i = inv.getSize() - 1; i >= 0 && c < count; --i) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.isEmpty()) {
                continue;
            }
            MusicBoxSong song = MusicBoxSongManager.findPlayableJukeboxSongByItem(item).orElse(null);
            if (song == null) {
                continue;
            }
            list.add(song);
            c++;
        }
        return list;
    }

    @Override
    public boolean hasNext() {
        @Nullable Inventory inv = this.getChestInventory();
        if (inv == null) {
            return false;
        }
        return this.hasNext(inv);
    }

    private ChestIndex getByIndex(Inventory inventory, int index) {
        List<ChestIndex> songs = this.getCachedOrRefreshOrderedSongs(inventory);
        if (index < 0 || index >= songs.size()) {
            return null;
        }
        return songs.get(index);
    }

    private boolean hasNext(Inventory inv) {
        return this.getByIndex(inv, 0) != null;
    }

    @Override
    public boolean hasPrev() {
        @Nullable Inventory inv = this.getChestInventory();
        if (inv == null) {
            return false;
        }
        return this.hasPrev(inv);
    }

    private ChestIndex getLastSong(Inventory inventory) {
        List<ChestIndex> songs = this.getCachedOrRefreshOrderedSongs(inventory);
        return songs.isEmpty() ? null : songs.getLast();
    }

    private boolean hasPrev(Inventory inventory) {
        return this.getLastSong(inventory) != null;
    }

    @Override
    public MusicBoxSong getCurrent() {
        if (this.cachedSong != null) {
            return this.cachedSong;
        }
        IJukebox j = this.getCustom();
        if (j == null) {
            return null;
        }
        ItemStack item = j.getJukebox();
        if (item == null || item.isEmpty()) {
            return null;
        }
        return MusicBoxSongManager.findByItem(item).orElse(null);
    }

    @Override
    public void back(int count) {
        Jukebox box = this.getJukebox();
        if (box == null) {
            return;
        }
        Inventory inv = this.getChestInventory(box);
        if (inv != null) {
            IJukebox cBox = JukeboxFactory.getJukebox(box);
            for (int i = 0; i < count; ++i) {
                this.back(inv, cBox);
            }
        }
    }

    @Override
    public int getSongNum(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
        return -1;
    }

    @Override
    public void setSong(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
        MusicBoxSong current = this.getCurrent();
        if (current == song) {
            return;
        }
        Jukebox box = this.getJukebox();
        if (box == null) {
            return;
        }
        Inventory inv = this.getChestInventory(box);
        if (inv == null) {
            return;
        }
        IJukebox jukebox = JukeboxFactory.getJukebox(box);
        if (jukebox == null) {
            return;
        }

        List<ChestIndex> orderedSongs = new ArrayList<>(this.getCachedOrRefreshOrderedSongs(inv));
        ItemStack currentItem = jukebox.getJukebox();
        ChestIndex currentEntry = current != null && currentItem != null && !currentItem.isEmpty()
            ? new ChestIndex(-1, currentItem, current)
            : null;

        List<ChestIndex> fullQueue = new ArrayList<>();
        if (currentEntry != null) {
            fullQueue.add(currentEntry);
        }
        fullQueue.addAll(orderedSongs);

        int targetIndex = -1;
        for (int i = 0; i < fullQueue.size(); i++) {
            if (fullQueue.get(i).getSong() == song) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return;
        }

        ChestIndex target = fullQueue.get(targetIndex);
        List<ChestIndex> rotatedQueue = new ArrayList<>();
        for (int i = targetIndex + 1; i < fullQueue.size(); i++) {
            rotatedQueue.add(fullQueue.get(i));
        }
        for (int i = 0; i < targetIndex; i++) {
            rotatedQueue.add(fullQueue.get(i));
        }

        for (int i = 0; i < orderedSongs.size(); i++) {
            ItemStack stack = i < rotatedQueue.size() ? rotatedQueue.get(i).getStack() : null;
            invSet(inv, orderedSongs.get(i).getIndex(), stack);
        }

        this.setJukeboxItem(jukebox, target.getStack(), target.getSong());
        this.refreshOrderedSongsCache(inv);
        this.cachedSong = target.getSong();
    }

    private void setJukeboxItem(IJukebox jukebox, ItemStack item, MusicBoxSong song) {
        boolean suppressVanillaPlayback = song == null || !song.shouldUseVanillaJukeboxPlayback();
        jukebox.setJukebox(item, suppressVanillaPlayback);
    }

    @Override
    public void reset() {
        this.updateCachedSong();
    }

    @Override
    public void updatePlaylist() {
        Inventory inventory = this.getChestInventory();
        if (inventory != null) {
            this.refreshOrderedSongsCache(inventory);
        } else {
            this.cachedOrderedSongs = Collections.emptyList();
        }
        this.updateCachedSong();
    }

    public boolean isShuffleMode() {
        return shuffleMode;
    }

    public void setShuffleMode(boolean shuffleMode) {
        this.shuffleMode = shuffleMode;
    }

    private IJukebox getCustom() {
        Jukebox j = this.getJukebox();
        if (j == null) {
            return null;
        }
        return JukeboxFactory.getJukebox(j);
    }

    private void invSet(Inventory inventory, int slot, ItemStack stack) {
        inventory.setItem(slot, stack == null || stack.isEmpty() ? null : stack);
    }

    private static class ChestIndex {
        private final int index;
        private final ItemStack stack;
        private final MusicBoxSong song;

        public int getIndex() {
            return this.index;
        }

        public ItemStack getStack() {
            return this.stack;
        }

        public MusicBoxSong getSong() {
            return this.song;
        }

        public ChestIndex(int index, ItemStack stack, MusicBoxSong song) {
            this.index = index;
            this.stack = stack;
            this.song = song;
        }
    }
}
