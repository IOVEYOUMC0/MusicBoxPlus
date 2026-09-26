package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

// Builds the ItemStacks shown in the editor's note grid (filled notes, multi-instrument cells,
// empty slots) from a note plus the current view flags. Pure rendering: it reads the editor
// config and pitch bounds but mutates no editor state.
final class MusicEditNoteRenderer {
    private final GUIConfigManager.MusicEditorConfig config;
    private final int maxPitch;
    private final int defaultMaxPitch;

    MusicEditNoteRenderer(GUIConfigManager.MusicEditorConfig config, int maxPitch, int defaultMaxPitch) {
        this.config = config;
        this.maxPitch = maxPitch;
        this.defaultMaxPitch = defaultMaxPitch;
    }

    ItemStack createEditAreaItem(MusicNote note, int pitch, int tick, boolean isCurrentPlayingTick, boolean isCurrentPlayingNote, boolean isSelected) {
        String noteName = MusicNote.getNoteName(pitch);
        boolean isExtendedOctave = pitch > defaultMaxPitch;
        boolean canEdit = maxPitch > defaultMaxPitch;

        if (note != null && note.getInstrumentCount() > 0) {
            if (note.getInstrumentCount() == 1) {
                return createSingleInstrumentNoteItem(note, noteName, tick, isExtendedOctave, canEdit, isCurrentPlayingNote, isSelected);
            } else {
                return createMultiInstrumentNoteItem(note, noteName, tick, isExtendedOctave, canEdit, isCurrentPlayingNote, isSelected);
            }
        } else {
            return createEmptySlotItem(noteName, tick, isExtendedOctave, canEdit, isCurrentPlayingTick, isSelected);
        }
    }

    private ItemStack createSingleInstrumentNoteItem(MusicNote note, String noteName, int tick, boolean isExtendedOctave, boolean canEdit, boolean isCurrentPlayingNote, boolean isSelected) {
        MusicNote.NoteInstrument instrument = note.getInstruments().get(0);
        GUIConfigManager.EditorItemConfig baseConfig = isSelected ? config.getFilledNoteSelected() : config.getFilledNote();
        Material material = resolveNoteItemMaterial(baseConfig, instrument);
        String name = buildNoteName(baseConfig.getName(), noteName, isExtendedOctave, isCurrentPlayingNote);
        List<String> lore = buildNoteLore(baseConfig.getLore(), noteName, tick, instrument.getDisplayName(), isExtendedOctave, canEdit, isCurrentPlayingNote);

        if (isSelected) {
            addSelectionLore(lore);
        }

        ItemStack item = ItemUtils.createStack(material, name, lore, baseConfig.getCustomModelData());
        if (isCurrentPlayingNote || isSelected) {
            item = addGlow(item);
        }
        return item;
    }

    private ItemStack createMultiInstrumentNoteItem(MusicNote note, String noteName, int tick, boolean isExtendedOctave, boolean canEdit, boolean isCurrentPlayingNote, boolean isSelected) {
        GUIConfigManager.EditorItemConfig baseConfig = isSelected ? config.getMultiInstrumentSelected() : config.getMultiInstrument();
        String name = baseConfig.getName().replace("{count}", String.valueOf(note.getInstrumentCount()));
        if (isExtendedOctave) {
            name = config.getWarningPrefix() + name;
        }
        if (isCurrentPlayingNote) {
            name = config.getPlayingPrefix() + name;
        }
        if (isSelected) {
            name = config.getSelectedPrefix() + name;
        }

        List<String> lore = new ArrayList<>();
        for (String line : baseConfig.getLore()) {
            if (line.contains("{instruments}")) {
                for (MusicNote.NoteInstrument inst : note.getInstruments()) {
                    lore.add(config.getMultiInstrumentListFormat().replace("{instrument}", inst.getDisplayName()));
                }
            } else {
                lore.add(line.replace("{note}", noteName).replace("{tick}", String.valueOf(tick)));
            }
        }
        addExtendedOctaveLore(lore, isExtendedOctave, canEdit);
        addNowPlayingLore(lore, isCurrentPlayingNote);

        if (isSelected) {
            addSelectionLore(lore);
        }

        Material material = resolveNoteItemMaterial(baseConfig, note.getInstruments().get(0));
        ItemStack item = ItemUtils.createStack(material, name, lore, baseConfig.getCustomModelData());
        if (isCurrentPlayingNote || isSelected) {
            item = addGlow(item);
        }
        return item;
    }

    private Material resolveNoteItemMaterial(GUIConfigManager.EditorItemConfig itemConfig, MusicNote.NoteInstrument instrument) {
        if (itemConfig.isUseInstrumentMaterial() && instrument != null) {
            return instrument.getMaterial();
        }
        return itemConfig.getMaterial();
    }

    private ItemStack createEmptySlotItem(String noteName, int tick, boolean isExtendedOctave, boolean canEdit, boolean isCurrentPlayingTick, boolean isSelected) {
        GUIConfigManager.EditorItemConfig baseConfig = isSelected ? config.getEmptySlotSelected() : config.getEmptySlot();
        String name = baseConfig.getName().replace("{note}", noteName);
        Material material;

        if (isCurrentPlayingTick) {
            baseConfig = config.getPlayingEmptySlot();
            name = baseConfig.getName().replace("{note}", noteName);
            material = baseConfig.getMaterial();
        } else if (isExtendedOctave && !canEdit) {
            name = "<dark_gray>" + noteName;
            material = Material.RED_STAINED_GLASS_PANE;
        } else {
            material = baseConfig.getMaterial();
        }

        if (isCurrentPlayingTick) {
            name = config.getPlayingPrefix() + name;
        }
        if (isSelected) {
            name = config.getSelectedPrefix() + name;
        }

        List<String> lore = new ArrayList<>();
        for (String line : baseConfig.getLore()) {
            lore.add(line.replace("{note}", noteName).replace("{tick}", String.valueOf(tick)));
        }
        if (isExtendedOctave && !canEdit) {
            lore.add(config.getExtendedOctaveAreaWarning());
            lore.add(config.getEnableOctaveHint());
            lore.add("<gray>启用扩展八度后才能在此添加音符</gray>");
        }
        addNowPlayingLore(lore, isCurrentPlayingTick);

        if (isSelected) {
            addSelectionLore(lore);
        }

        return ItemUtils.createStack(material, name, lore, baseConfig.getCustomModelData());
    }

    private void addSelectionLore(List<String> lore) {
        lore.add("");
        lore.add("<aqua>已选中</aqua>");
        lore.add("<gray>普通点击取消选择</gray>");
        lore.add("<gray>Shift+点击扩展范围</gray>");
    }

    private String buildNoteName(String template, String noteName, boolean isExtendedOctave, boolean isCurrentPlayingNote) {
        String name = template.replace("{note}", noteName);
        if (isExtendedOctave) {
            name = config.getWarningPrefix() + name;
        }
        if (isCurrentPlayingNote) {
            name = config.getPlayingPrefix() + name;
        }
        return name;
    }

    private List<String> buildNoteLore(List<String> template, String noteName, int tick, String instrumentName, boolean isExtendedOctave, boolean canEdit, boolean isCurrentPlayingNote) {
        List<String> lore = new ArrayList<>();
        for (String line : template) {
            lore.add(line.replace("{note}", noteName)
                    .replace("{instrument}", instrumentName)
                    .replace("{tick}", String.valueOf(tick)));
        }
        addExtendedOctaveLore(lore, isExtendedOctave, canEdit);
        addNowPlayingLore(lore, isCurrentPlayingNote);
        return lore;
    }

    private void addExtendedOctaveLore(List<String> lore, boolean isExtendedOctave, boolean canEdit) {
        if (isExtendedOctave) {
            lore.add(config.getExtendedOctaveWarning());
            if (!canEdit) {
                lore.add(config.getCanOnlyDeleteHint());
            }
        }
    }

    private void addNowPlayingLore(List<String> lore, boolean isNowPlaying) {
        if (isNowPlaying) {
            lore.add(config.getNowPlayingText());
        }
    }

    private ItemStack addGlow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.LURE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }
}
