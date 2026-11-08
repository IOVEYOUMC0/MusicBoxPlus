package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class TextPlayerEditConfig {
    String title = "<gold>文字播放器</gold> <dark_gray>-</dark_gray> <yellow>{name}</yellow>";
    String layout = "AE+-XIFOH\nXNSPTMQCY\nXGXXBUXXD";
    int rangeStep = 4;
    double heightStep = 0.25;
    double positionStep = 0.25;
    int positionShiftMultiplier = 10;
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

    public TextPlayerEditConfig() {
        buttonMapping.put("info", 'I');
        buttonMapping.put("toggle-name", 'N');
        buttonMapping.put("toggle-song", 'S');
        buttonMapping.put("toggle-progress", 'P');
        buttonMapping.put("toggle-time", 'T');
        buttonMapping.put("choose-song", 'Q');
        buttonMapping.put("choose-playlist", 'Y');
        buttonMapping.put("control", 'C');
        buttonMapping.put("move-to-me", 'M');
        buttonMapping.put("adjust-y", 'U');
        buttonMapping.put("adjust-x", 'A');
        buttonMapping.put("adjust-z", 'E');
        buttonMapping.put("close", 'B');
        buttonMapping.put("delete", 'D');
        buttonMapping.put("range-up", '+');
        buttonMapping.put("range-down", '-');
        buttonMapping.put("toggle-billboard", 'F');
        buttonMapping.put("set-facing", 'O');
        buttonMapping.put("toggle-double-sided", 'H');
        buttonMapping.put("toggle-public-edit", 'G');
        buttons.put("info", new HotbarButtonConfig(Material.PAPER, "<yellow>{name}", Arrays.asList(
            "<gray>当前歌曲: <white>{song}",
            "<gray>右键浮动文字打开控制面板",
            "<gray>潜行右键打开此编辑菜单"
        ), 0, false));
        buttons.put("toggle-name", new HotbarButtonConfig(Material.NAME_TAG, "<yellow>名称: {status}", List.of("<gray>点击切换显示名称"), 0, false));
        buttons.put("toggle-song", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>歌曲: {status}", List.of("<gray>点击切换显示歌曲"), 0, false));
        buttons.put("toggle-progress", new HotbarButtonConfig(Material.COMPARATOR, "<yellow>进度: {status}", List.of("<gray>点击切换显示进度"), 0, false));
        buttons.put("toggle-time", new HotbarButtonConfig(Material.CLOCK, "<yellow>时间: {status}", List.of("<gray>点击切换显示时间"), 0, false));
        buttons.put("choose-song", new HotbarButtonConfig(Material.JUKEBOX, "<green>选择歌曲", List.of("<gray>打开歌曲选择器"), 0, false));
        buttons.put("choose-playlist", new HotbarButtonConfig(Material.CHEST, "<green>选择播放列表", List.of("<gray>从保存的播放列表中选择"), 0, false));
        buttons.put("control", new HotbarButtonConfig(Material.REDSTONE_TORCH, "<gold>控制面板", List.of("<gray>打开播放控制"), 0, false));
        buttons.put("move-to-me", new HotbarButtonConfig(Material.ENDER_PEARL, "<green>移动到我的位置", List.of("<gray>把浮动文字移动到你当前所在的位置"), 0, false));
        buttons.put("adjust-y", new HotbarButtonConfig(Material.COMPASS, "<yellow>Y 轴调整", List.of("<gray>左键: Y -", "<gray>右键: Y +", "<gray>Shift: {multiplier} 倍步长"), 0, false));
        buttons.put("adjust-x", new HotbarButtonConfig(Material.COMPASS, "<yellow>X 轴调整", List.of("<gray>左键: X -", "<gray>右键: X +", "<gray>Shift: {multiplier} 倍步长"), 0, false));
        buttons.put("adjust-z", new HotbarButtonConfig(Material.COMPASS, "<yellow>Z 轴调整", List.of("<gray>左键: Z -", "<gray>右键: Z +", "<gray>Shift: {multiplier} 倍步长"), 0, false));
        buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>关闭", List.of("<gray>关闭此菜单"), 0, false));
        buttons.put("delete", new HotbarButtonConfig(Material.TNT, "<red>删除", List.of("<gray>删除此文字播放器"), 0, false));
        buttons.put("range-up", new HotbarButtonConfig(Material.LIME_DYE, "<green>增大范围", List.of("<gray>增大播放范围"), 0, false));
        buttons.put("range-down", new HotbarButtonConfig(Material.GRAY_DYE, "<red>减小范围", List.of("<gray>减小播放范围"), 0, false));
        buttons.put("toggle-billboard", new HotbarButtonConfig(Material.ITEM_FRAME, "<yellow>固定朝向: {status}", List.of("<gray>启用后文字朝向固定"), 0, false));
        buttons.put("set-facing", new HotbarButtonConfig(Material.COMPASS, "<aqua>设置朝向", List.of("<gray>固定朝向并正面朝向你"), 0, false));
        buttons.put("toggle-double-sided", new HotbarButtonConfig(Material.PAINTING, "<yellow>双面显示: {status}", List.of("<gray>固定朝向时在背面显示同样文字"), 0, false));
        buttons.put("toggle-public-edit", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<yellow>允许无权限编辑: {status}", List.of("<gray>允许无权限玩家编辑外观和歌曲"), 0, false));
        buttons.put("edit-display", new HotbarButtonConfig(Material.PAPER, "<aqua>编辑显示", List.of("<gray>编辑浮动文字显示行"), 0, false));
    }

    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public int getRangeStep() { return this.rangeStep; }
    public double getHeightStep() { return this.heightStep; }
    public double getPositionStep() { return this.positionStep; }
    public int getPositionShiftMultiplier() { return this.positionShiftMultiplier; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
