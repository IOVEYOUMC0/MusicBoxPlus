package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MusicEditInstrumentMenuRenderer {
    private MusicEditInstrumentMenuRenderer() {
    }

    static void renderInstrumentSelect(
        Player player,
        GUIConfigManager.InstrumentSelectConfig instrumentConfig,
        MusicNote selectedNote,
        int instrumentPageOffset,
        int instrumentsPerPage
    ) {
        player.getInventory().clear();

        MusicNote.NoteInstrument[] instruments = MusicNote.NoteInstrument.getAvailableValues();
        int totalInstruments = instruments.length;
        List<Integer> instrumentSlots = getPlayerInventorySlotsForInstrumentChar(
            instrumentConfig,
            instrumentConfig.getButtonMapping().getOrDefault("instrument", 'I')
        );
        int pageSize = Math.max(1, Math.min(instrumentsPerPage, instrumentSlots.size()));
        boolean needPagination = totalInstruments > pageSize;

        int noteInfoSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "note-info");
        if (noteInfoSlot >= 0 && selectedNote != null) {
            GUIConfigManager.HotbarButtonConfig noteInfoConfig = instrumentConfig.getButton("note-info");
            if (noteInfoConfig != null) {
                List<String> noteInfoLore = new ArrayList<>();
                for (String line : noteInfoConfig.getLore()) {
                    noteInfoLore.add(line.replace("{note}", MusicNote.getNoteName(selectedNote.getPitch()))
                        .replace("{tick}", String.valueOf(selectedNote.getTick()))
                        .replace("{count}", String.valueOf(selectedNote.getInstrumentCount())));
                }
                ItemStack noteInfo = ItemUtils.createStack(noteInfoConfig.getMaterial(), noteInfoConfig.getName(), noteInfoLore, noteInfoConfig.getCustomModelData());
                player.getInventory().setItem(noteInfoSlot, noteInfo);
            }
        }

        if (needPagination) {
            renderPageButton(player, instrumentConfig, "prev-page", instrumentPageOffset, getTotalInstrumentPages(pageSize));
        }

        int playSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "play-preview");
        if (playSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig playConfig = instrumentConfig.getButton("play-preview");
            if (playConfig != null) {
                ItemStack playButton = playConfig.createItem();
                player.getInventory().setItem(playSlot, playButton);
            }
        }

        if (needPagination) {
            renderPageButton(player, instrumentConfig, "next-page", instrumentPageOffset, getTotalInstrumentPages(pageSize));
        }

        int backSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "back");
        if (backSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig backConfig = instrumentConfig.getButton("back");
            if (backConfig != null) {
                ItemStack backButton = backConfig.createItem();
                player.getInventory().setItem(backSlot, backButton);
            }
        }

        int startIndex = needPagination ? instrumentPageOffset * pageSize : 0;
        int endIndex = needPagination ? Math.min(startIndex + pageSize, totalInstruments) : totalInstruments;
        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= instrumentSlots.size()) {
                break;
            }

            int slot = instrumentSlots.get(slotIndex);
            MusicNote.NoteInstrument instrument = instruments[i];
            boolean hasInstrument = selectedNote != null && selectedNote.getInstruments().contains(instrument);

            ItemStack item = ItemUtils.createStack(
                instrument.getMaterial(),
                (hasInstrument ? "<green>" : "<gray>") + instrument.getDisplayName(),
                    Collections.singletonList(hasInstrument ? Lang.INSTRUMENT_SELECT_SELECTED.toString() : Lang.INSTRUMENT_SELECT_UNSELECTED.toString()),
                0
            );
            if (hasInstrument) {
                item = ItemUtils.glow(item);
            }
            player.getInventory().setItem(slot, item);
        }

    }

    static void renderCurrentInstrumentSelect(
        Player player,
        GUIConfigManager.InstrumentSelectConfig instrumentConfig,
        MusicNote.NoteInstrument currentInstrument,
        int instrumentPageOffset,
        int instrumentsPerPage
    ) {
        player.getInventory().clear();

        MusicNote.NoteInstrument[] instruments = MusicNote.NoteInstrument.getAvailableValues();
        List<Integer> instrumentSlots = getPlayerInventorySlotsForInstrumentChar(
            instrumentConfig,
            instrumentConfig.getButtonMapping().getOrDefault("instrument", 'I')
        );
        int pageSize = Math.max(1, Math.min(instrumentsPerPage, instrumentSlots.size()));
        boolean needPagination = instruments.length > pageSize;

        int backSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "back");
        if (backSlot < 0) {
            backSlot = 0;
        }
        GUIConfigManager.HotbarButtonConfig backConfig = instrumentConfig.getButton("back");
        if (backConfig != null) {
            ItemStack backButton = backConfig.createItem();
            player.getInventory().setItem(backSlot, backButton);
        }

        if (needPagination) {
            renderPageButton(player, instrumentConfig, "prev-page", instrumentPageOffset, getTotalInstrumentPages(pageSize));
            renderPageButton(player, instrumentConfig, "next-page", instrumentPageOffset, getTotalInstrumentPages(pageSize));
        }

        int startIndex = needPagination ? instrumentPageOffset * pageSize : 0;
        int endIndex = needPagination ? Math.min(startIndex + pageSize, instruments.length) : instruments.length;
        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= instrumentSlots.size()) {
                break;
            }
            int slot = instrumentSlots.get(slotIndex);
            MusicNote.NoteInstrument instrument = instruments[i];
            boolean isSelected = instrument == currentInstrument;
            List<String> lore = new ArrayList<>();
            lore.add(isSelected ? Lang.INSTRUMENT_SELECT_SELECTED.toString() : Lang.INSTRUMENT_SELECT_UNSELECTED.toString());

            ItemStack item = ItemUtils.createStack(
                instrument.getMaterial(),
                (isSelected ? "<green>" : "<gray>") + instrument.getDisplayName(),
                lore,
                0
            );
            if (isSelected) {
                item = ItemUtils.glow(item);
            }
            player.getInventory().setItem(slot, item);
        }

    }

    static int getTotalInstrumentPages(int instrumentsPerPage) {
        return (int) Math.ceil((double) MusicNote.NoteInstrument.getAvailableCount() / Math.max(1, instrumentsPerPage));
    }

    static List<Integer> getPlayerInventorySlotsForInstrumentChar(GUIConfigManager.InstrumentSelectConfig instrumentConfig, char c) {
        List<Integer> mappedSlots = new ArrayList<>();
        for (int layoutSlot : instrumentConfig.getSlotsForChar(c)) {
            int playerSlot = mapInstrumentLayoutSlotToPlayerSlot(layoutSlot);
            if (playerSlot >= 0) {
                mappedSlots.add(playerSlot);
            }
        }
        return mappedSlots;
    }

    static int getPlayerInventorySlotForInstrumentButton(GUIConfigManager.InstrumentSelectConfig instrumentConfig, String buttonType) {
        return mapInstrumentLayoutSlotToPlayerSlot(instrumentConfig.getSlotForButton(buttonType));
    }

    private static void renderPageButton(Player player, GUIConfigManager.InstrumentSelectConfig instrumentConfig, String key, int pageOffset, int totalPages) {
        int slot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, key);
        if (slot < 0) {
            return;
        }
        GUIConfigManager.HotbarButtonConfig config = instrumentConfig.getButton(key);
        if (config == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        for (String line : config.getLore()) {
            lore.add(line.replace("{page}", String.valueOf(pageOffset + 1)).replace("{totalPages}", String.valueOf(totalPages)));
        }
        ItemStack button = ItemUtils.createStack(config.getMaterial(), config.getName(), lore, config.getCustomModelData());
        player.getInventory().setItem(slot, button);
    }

    private static int mapInstrumentLayoutSlotToPlayerSlot(int layoutSlot) {
        if (layoutSlot < 0) {
            return -1;
        }
        int row = layoutSlot / 9;
        int col = layoutSlot % 9;
        if (row >= 0 && row < 3) {
            return 9 + row * 9 + col;
        }
        if (row == 3) {
            return col;
        }
        return -1;
    }
}
