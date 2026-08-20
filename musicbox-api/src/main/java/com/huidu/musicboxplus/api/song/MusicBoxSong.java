package com.huidu.musicboxplus.api.song;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Read-only view of a song handed to third-party plugins through api.event and the
// player interfaces. The playable arrangement (getCompiledSong on the core class) is
// deliberately not exposed here; rendering the item stack is, since it needs no
// internal types. The concrete implementation lives in core.song.
public interface MusicBoxSong {

    String getName();

    String getAuthor();

    String getOriginalAuthor();

    int getDuration();

    short getLength();

    String getLengthFormatted();

    int getHash();

    Set<String> getAliases();

    Set<String> getTags();

    List<String> getAliasList();

    List<String> getTagList();

    File getFile();

    Map<String, String> getHoverMap();

    boolean matchesSearch(String query);

    String getJukeboxPlayable();

    String getResolvedJukeboxPlayable();

    boolean shouldUseVanillaJukeboxPlayback();

    ItemStack getSongStack();

    ItemStack getSongStack(Material material);

    ItemStack getSongStack(Material material, List<String> extraLines, boolean glow);

    ItemStack getSongStack(Material material, String itemName, List<String> extraLines, boolean glow);
}
