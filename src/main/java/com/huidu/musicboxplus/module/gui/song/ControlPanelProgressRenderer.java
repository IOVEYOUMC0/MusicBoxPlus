package com.huidu.musicboxplus.module.gui.song;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlaybackContext;
import com.huidu.musicboxplus.module.gui.ButtonFactory;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.PlayerClickAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

final class ControlPanelProgressRenderer {
    private ControlPanelProgressRenderer() {
    }

    static void render(
        GUI gui,
        LayoutParser layoutParser,
        GUIConfigManager configManager,
        MusicBoxSongPlayer musicPlayer,
        PlaybackContext context,
        boolean canSwitch,
        float effectiveSpeed,
        Supplier<ItemStack> unavailableFallback,
        Runnable afterSeek
    ) {
        GUIConfigManager.ProgressBarConfig progressBarConfig = configManager.getProgressBarConfig("control-panel");
        GUIConfigManager.GUIConfig guiConfig = configManager.getGUIConfig("control-panel");
        GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getButtonMapping();
        List<Integer> progressSlots = layoutParser.getSlotsForChar(mapping.getProgress());
        if (progressSlots.isEmpty()) {
            int startCol = Math.max(0, progressBarConfig.getStartCol());
            int endCol = Math.min(9, Math.max(startCol + 1, progressBarConfig.getEndCol()));
            int row = Math.max(0, progressBarConfig.getRow());
            progressSlots = IntStream.range(startCol, endCol).map(col -> row * 9 + col).boxed().toList();
        }

        if (context != null && !context.supportsProgressSeek()) {
            ItemStack fallback = unavailableFallback.get();
            for (int slot : progressSlots) {
                gui.addItem(slot, fallback.clone(), null);
            }
            return;
        }

        int totalSlots = progressSlots.size();
        if (musicPlayer == null || musicPlayer.getMusicBoxSong() == null) {
            ItemStack idleItem = createProgressBarItem(
                progressBarConfig.getMaterialUnplayed(),
                progressBarConfig.getMaterialUnplayedModelData(),
                Lang.REWIND_TO.toString("{percent}", "0", "{time}", "0:00"),
                progressBarConfig.getLore()
            );
            for (int slot : progressSlots) {
                gui.addItem(slot, idleItem.clone(), null);
            }
            return;
        }

        short allTicks = musicPlayer.getMusicBoxSong().getLength();
        short currentTick = musicPlayer.getTick();
        int chunkSizeInt = (int) Math.ceil((double) allTicks / (double) totalSlots);
        double progress = (double) currentTick / (double) allTicks;
        int currentPercent = (int) Math.floor((double) currentTick / (double) allTicks * 100.0);
        String currentTimeStr = StringUtils.toHumanTime((int) Math.floor((float) currentTick / effectiveSpeed));
        String totalTimeStr = StringUtils.toHumanTime((int) Math.floor((float) allTicks / effectiveSpeed));
        String speedStr = ButtonFactory.formatSpeed(effectiveSpeed);
        String currentTickStr = String.valueOf(currentTick);
        String totalTickStr = String.valueOf(allTicks);

        String formatTemplate = progressBarConfig.getFormat();
        List<String> loreTemplate = progressBarConfig.getLore();
        boolean buildLore = canSwitch && loreTemplate != null && !loreTemplate.isEmpty();

        Map<String, String> placeholders = new LinkedHashMap<>(9);
        placeholders.put("{percent}", "");
        placeholders.put("{time}", "");
        placeholders.put("{tick}", "");
        placeholders.put("{current_percent}", String.valueOf(currentPercent));
        placeholders.put("{current_time}", currentTimeStr);
        placeholders.put("{total_time}", totalTimeStr);
        placeholders.put("{current_tick}", currentTickStr);
        placeholders.put("{total_tick}", totalTickStr);
        placeholders.put("{speed}", speedStr);

        for (int i = 0; i < totalSlots; ++i) {
            int slot = progressSlots.get(i);
            short chunkStart = (short) (i * chunkSizeInt);
            double chunkProgress = (double) i / (double) totalSlots;
            double chunkEndProgress = (double) (i + 1) / (double) totalSlots;
            double chunkMidProgress = (chunkProgress + chunkEndProgress) / 2.0;

            Material material;
            int modelData;
            if (progress < chunkProgress) {
                material = progressBarConfig.getMaterialUnplayed();
                modelData = progressBarConfig.getMaterialUnplayedModelData();
            } else if (progress < chunkMidProgress) {
                material = progressBarConfig.getMaterialPlaying();
                modelData = progressBarConfig.getMaterialPlayingModelData();
            } else if (progress < chunkEndProgress) {
                material = progressBarConfig.getMaterialHalfway();
                modelData = progressBarConfig.getMaterialHalfwayModelData();
            } else {
                material = progressBarConfig.getMaterialPlayed();
                modelData = progressBarConfig.getMaterialPlayedModelData();
            }

            int chunkPercent = (int) Math.floor((double) chunkStart / (double) allTicks * 100.0);
            placeholders.put("{percent}", String.valueOf(chunkPercent));
            placeholders.put("{time}", StringUtils.toHumanTime((int) Math.floor((float) chunkStart / effectiveSpeed)));
            placeholders.put("{tick}", String.valueOf(chunkStart));

            String displayName = applyPlaceholders(formatTemplate, placeholders);
            List<String> lore;
            if (buildLore) {
                lore = new java.util.ArrayList<>(loreTemplate.size());
                for (String line : loreTemplate) {
                    lore.add(applyPlaceholders(line, placeholders));
                }
            } else {
                lore = Collections.emptyList();
            }
            ItemStack item = createProgressBarItem(material, modelData, displayName, lore);
            PlayerClickAction action = null;
            if (canSwitch) {
                action = new PlayerClickAction(p -> seek(musicPlayer, chunkStart, afterSeek));
            }
            gui.addItem(slot, item, action);
        }
    }

    private static void seek(MusicBoxSongPlayer musicPlayer, short tick, Runnable afterSeek) {
        if (musicPlayer == null) {
            return;
        }
        musicPlayer.setTick(tick);
        if (afterSeek != null) {
            afterSeek.run();
        }
    }

    private static ItemStack createProgressBarItem(Material material, int modelData, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessageUtils.processComponent(displayName));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(MiniMessageUtils.processComponents(lore));
            }
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String applyPlaceholders(String input, Map<String, String> placeholders) {
        String output = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (output.contains(entry.getKey())) {
                output = output.replace(entry.getKey(), entry.getValue());
            }
        }
        return output;
    }

}
