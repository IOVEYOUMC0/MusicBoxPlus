package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class MusicEditorConfig {
    String title = "<gold>Music Editor<dark_gray> - <yellow>{name}";
    String layout = "EEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nUDLIRXBTM\nYQWJKXZCX\nXXXXXPXSX\nXXXHXXXXX";
    char editAreaChar = 'E';
    char emptyChar = 'X';
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    final EditorItemConfig emptySlot = new EditorItemConfig(Material.GRAY_STAINED_GLASS_PANE, "<gray>Empty");
    final EditorItemConfig emptySlotSelected = new EditorItemConfig(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Selected <gray>Empty");
    final EditorItemConfig playingEmptySlot = new EditorItemConfig(Material.GREEN_STAINED_GLASS_PANE, "<green>Playing");
    final EditorItemConfig filledNote = new EditorItemConfig(Material.NOTE_BLOCK, "<green>{note}");
    final EditorItemConfig filledNoteSelected = new EditorItemConfig(Material.NOTE_BLOCK, "<aqua>Selected <green>{note}");
    final EditorItemConfig multiInstrument = new EditorItemConfig(Material.NOTE_BLOCK, "<yellow>Multiple Instruments <gray>({count})");
    final EditorItemConfig multiInstrumentSelected = new EditorItemConfig(Material.NOTE_BLOCK, "<aqua>Selected <yellow>Multiple Instruments <gray>({count})");
    String multiInstrumentListFormat = "<gray>- <green>{instrument}";
    String playingPrefix = "<yellow>▶ ";
    String warningPrefix = "<red>! ";
    String selectedPrefix = "<aqua>» ";
    String extendedOctaveWarning = "<red>Some notes are outside the playable octave range";
    String extendedOctaveAreaWarning = "<red>Selection contains notes outside the playable octave range";
    String enableOctaveHint = "<gray>Enable 10-octave mode in config to use higher notes";
    String canOnlyDeleteHint = "<gray>Only deletion is available in this area";
    String nowPlayingText = "<yellow>Now Playing";

    public MusicEditorConfig() {
        filledNote.useInstrumentMaterial = true;
        filledNoteSelected.useInstrumentMaterial = true;
        multiInstrument.useInstrumentMaterial = true;
        multiInstrumentSelected.useInstrumentMaterial = true;

        buttonMapping.put("edit-area", 'E');
        buttonMapping.put("empty", 'X');
        buttonMapping.put("pitch-up", 'U');
        buttonMapping.put("pitch-down", 'D');
        buttonMapping.put("tick-left", 'L');
        buttonMapping.put("tick-right", 'R');
        buttonMapping.put("instrument", 'I');
        buttonMapping.put("play", 'P');
        buttonMapping.put("save", 'S');
        buttonMapping.put("exit", 'H');
        buttonMapping.put("bpm-up", 'B');
        buttonMapping.put("bpm-down", 'T');
        buttonMapping.put("time-signature", 'M');
        buttonMapping.put("settings", 'G');
        buttonMapping.put("clear-all", 'C');
        buttonMapping.put("info-name", 'N');
        buttonMapping.put("info-notes", 'O');
        buttonMapping.put("info-bpm", 'K');
        buttonMapping.put("info-time", 'V');
        buttonMapping.put("confirm-save", 'W');
        buttonMapping.put("confirm-exit", 'Z');
        buttonMapping.put("cancel", 'A');
        buttonMapping.put("undo", 'Y');
        buttonMapping.put("redo", 'Z');
        buttonMapping.put("copy", 'Q');
        buttonMapping.put("paste", 'J');
        buttonMapping.put("delete-selection", 'K');

        buttons.put("pitch-up", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Pitch +"));
        buttons.put("pitch-down", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Pitch -"));
        buttons.put("tick-left", new HotbarButtonConfig(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Previous Tick"));
        buttons.put("tick-right", new HotbarButtonConfig(Material.BLUE_STAINED_GLASS_PANE, "<blue>Next Tick"));
        buttons.put("instrument", new HotbarButtonConfig(Material.NOTE_BLOCK, "<aqua>Instrument"));
        buttons.put("play", new HotbarButtonConfig(Material.JUKEBOX, "<green>Play"));
        buttons.put("play-stop", new HotbarButtonConfig(Material.BARRIER, "<red>Stop Playback"));
        buttons.put("save", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<green>Save"));
        buttons.put("exit", new HotbarButtonConfig(Material.BARRIER, "<red>Exit"));
        buttons.put("empty", new HotbarButtonConfig(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Empty"));
        buttons.put("bpm-up", new HotbarButtonConfig(Material.LIME_DYE, "<green>BPM +5"));
        buttons.put("bpm-down", new HotbarButtonConfig(Material.RED_DYE, "<red>BPM -5"));
        buttons.put("time-signature", new HotbarButtonConfig(Material.CLOCK, "<aqua>Time Signature"));
        buttons.put("settings", new HotbarButtonConfig(Material.COMPASS, "<aqua>Editor Settings"));
        buttons.put("clear-all", new HotbarButtonConfig(Material.TNT, "<red>Clear Notes"));
        buttons.put("info-name", new HotbarButtonConfig(Material.NAME_TAG, "<gold>Music Name"));
        buttons.put("info-notes", new HotbarButtonConfig(Material.NOTE_BLOCK, "<yellow>Notes"));
        buttons.put("info-bpm", new HotbarButtonConfig(Material.REPEATER, "<gold>BPM"));
        buttons.put("info-time", new HotbarButtonConfig(Material.CLOCK, "<aqua>Length"));
        buttons.put("confirm-save", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Save"));
        buttons.put("confirm-exit", new HotbarButtonConfig(Material.RED_WOOL, "<red>Confirm Exit"));
        buttons.put("cancel", new HotbarButtonConfig(Material.BARRIER, "<yellow>Cancel"));
        buttons.put("undo", new HotbarButtonConfig(Material.ARMOR_STAND, "<yellow>Undo"));
        buttons.put("redo", new HotbarButtonConfig(Material.END_CRYSTAL, "<aqua>Redo"));
        buttons.put("copy", new HotbarButtonConfig(Material.WRITTEN_BOOK, "<green>Copy"));
        buttons.put("paste", new HotbarButtonConfig(Material.BOOK, "<gold>Paste"));
        buttons.put("delete-selection", new HotbarButtonConfig(Material.TNT, "<red>Delete Selection"));
    }

    public String getTitle() { return this.title; }
    public int getRows() { return 6; }
    public String getLayout() { return this.layout; }
    public char getEditAreaChar() { return this.editAreaChar; }
    public char getEmptyChar() { return this.emptyChar; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public Map<String, HotbarButtonConfig> getButtons() { return this.buttons; }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : GUIConfigManager.getSlotForChar(layout, c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
    public HotbarButtonConfig getButton(String buttonType) { return buttons.get(buttonType); }
    public boolean isChestSlot(int slot) { return slot >= 0 && slot < 54; }
    public boolean isInventorySlot(int slot) { return slot >= 54 && slot < 90; }
    public int toInventorySlot(int layoutSlot) { return layoutSlot >= 54 ? layoutSlot - 54 : -1; }
    public EditorItemConfig getEmptySlot() { return this.emptySlot; }
    public EditorItemConfig getEmptySlotSelected() { return this.emptySlotSelected; }
    public EditorItemConfig getPlayingEmptySlot() { return this.playingEmptySlot; }
    public EditorItemConfig getFilledNote() { return this.filledNote; }
    public EditorItemConfig getFilledNoteSelected() { return this.filledNoteSelected; }
    public EditorItemConfig getMultiInstrument() { return this.multiInstrument; }
    public EditorItemConfig getMultiInstrumentSelected() { return this.multiInstrumentSelected; }
    public String getMultiInstrumentListFormat() { return this.multiInstrumentListFormat; }
    public String getPlayingPrefix() { return this.playingPrefix; }
    public String getWarningPrefix() { return this.warningPrefix; }
    public String getSelectedPrefix() { return this.selectedPrefix; }
    public String getExtendedOctaveWarning() { return this.extendedOctaveWarning; }
    public String getExtendedOctaveAreaWarning() { return this.extendedOctaveAreaWarning; }
    public String getEnableOctaveHint() { return this.enableOctaveHint; }
    public String getCanOnlyDeleteHint() { return this.canOnlyDeleteHint; }
    public String getNowPlayingText() { return this.nowPlayingText; }
}
