package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.huidu.musicboxplus.common.config.HotbarButtonConfig;
import com.huidu.musicboxplus.common.config.InstrumentSelectConfig;

final class MusicEditInstrumentMenuRenderer {
    private MusicEditInstrumentMenuRenderer() {
    }

    static void renderInstrumentSelect(
        Player player,
        InstrumentSelectConfig instrumentConfig,
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
        int pageSize = MusicEditInstrumentPages.pageSize(instrumentsPerPage, instrumentSlots.size());
        boolean needPagination = MusicEditInstrumentPages.needsPagination(totalInstruments, pageSize);

        int noteInfoSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "note-info");
        if (noteInfoSlot >= 0 && selectedNote != null) {
            HotbarButtonConfig noteInfoConfig = instrumentConfig.getButton("note-info");
            if (noteInfoConfig != null) {
                List<String> noteInfoLore = new ArrayList<>();
                for (String line : noteInfoConfig.getLore()) {
                    noteInfoLore.add(line.replace("{note}", MusicNote.getNoteName(selectedNote.getPitch()))
                        .replace("{tick}", String.valueOf(selectedNote.getTick()))
                        .replace("{count}", String.valueOf(selectedNote.getInstrumentCount())));
                }
                ItemStack noteInfo = ItemUtils.createStack(noteInfoConfig.getMaterial(), noteInfoConfig.getName(), noteInfoLore, noteInfoConfig.getCustomModelData(), noteInfoConfig.getItemModel(), noteInfoConfig.getCraftEngineItem());
                player.getInventory().setItem(noteInfoSlot, noteInfo);
            }
        }

        if (needPagination) {
            renderPageButton(player, instrumentConfig, "prev-page", instrumentPageOffset, MusicEditInstrumentPages.totalPages(totalInstruments, pageSize));
        }

        int playSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "play-preview");
        if (playSlot >= 0) {
            HotbarButtonConfig playConfig = instrumentConfig.getButton("play-preview");
            if (playConfig != null) {
                ItemStack playButton = playConfig.createItem();
                player.getInventory().setItem(playSlot, playButton);
            }
        }

        if (needPagination) {
            renderPageButton(player, instrumentConfig, "next-page", instrumentPageOffset, MusicEditInstrumentPages.totalPages(totalInstruments, pageSize));
        }

        int backSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "back");
        if (backSlot >= 0) {
            HotbarButtonConfig backConfig = instrumentConfig.getButton("back");
            if (backConfig != null) {
                ItemStack backButton = backConfig.createItem();
                player.getInventory().setItem(backSlot, backButton);
            }
        }

        MusicEditInstrumentPages.Page page =
                MusicEditInstrumentPages.page(totalInstruments, pageSize, instrumentPageOffset);
        int startIndex = page.startIndex();
        int endIndex = page.endIndex();
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
        InstrumentSelectConfig instrumentConfig,
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
        int pageSize = MusicEditInstrumentPages.pageSize(instrumentsPerPage, instrumentSlots.size());
        boolean needPagination = MusicEditInstrumentPages.needsPagination(instruments.length, pageSize);

        int backSlot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, "back");
        if (backSlot < 0) {
            backSlot = 0;
        }
        HotbarButtonConfig backConfig = instrumentConfig.getButton("back");
        if (backConfig != null) {
            ItemStack backButton = backConfig.createItem();
            player.getInventory().setItem(backSlot, backButton);
        }

        if (needPagination) {
            renderPageButton(player, instrumentConfig, "prev-page", instrumentPageOffset,
                    MusicEditInstrumentPages.totalPages(instruments.length, pageSize));
            renderPageButton(player, instrumentConfig, "next-page", instrumentPageOffset,
                    MusicEditInstrumentPages.totalPages(instruments.length, pageSize));
        }

        MusicEditInstrumentPages.Page page =
                MusicEditInstrumentPages.page(instruments.length, pageSize, instrumentPageOffset);
        int startIndex = page.startIndex();
        int endIndex = page.endIndex();
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



    static List<Integer> getPlayerInventorySlotsForInstrumentChar(InstrumentSelectConfig instrumentConfig, char c) {
        List<Integer> mappedSlots = new ArrayList<>();
        for (int layoutSlot : instrumentConfig.getSlotsForChar(c)) {
            int playerSlot = MusicEditInstrumentPages.layoutSlotToPlayerSlot(layoutSlot);
            if (playerSlot >= 0) {
                mappedSlots.add(playerSlot);
            }
        }
        return mappedSlots;
    }

    static int getPlayerInventorySlotForInstrumentButton(InstrumentSelectConfig instrumentConfig, String buttonType) {
        return MusicEditInstrumentPages.layoutSlotToPlayerSlot(instrumentConfig.getSlotForButton(buttonType));
    }

    private static void renderPageButton(Player player, InstrumentSelectConfig instrumentConfig, String key, int pageOffset, int totalPages) {
        int slot = getPlayerInventorySlotForInstrumentButton(instrumentConfig, key);
        if (slot < 0) {
            return;
        }
        HotbarButtonConfig config = instrumentConfig.getButton(key);
        if (config == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        for (String line : config.getLore()) {
            lore.add(line.replace("{page}", String.valueOf(pageOffset + 1)).replace("{totalPages}", String.valueOf(totalPages)));
        }
        ItemStack button = ItemUtils.createStack(config.getMaterial(), config.getName(), lore, config.getCustomModelData(), config.getItemModel(), config.getCraftEngineItem());
        player.getInventory().setItem(slot, button);
    }

}
