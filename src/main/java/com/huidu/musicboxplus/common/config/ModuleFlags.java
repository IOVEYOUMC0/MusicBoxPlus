package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.MusicBoxConfig;

import java.util.function.Supplier;

// Computes the per-feature flags from the parsed config. MusicBox delegates its
// isXxxModuleEnabled() / usesXxx() methods here so the flag logic lives in one place
// and the plugin class stays a thin facade over it.
public final class ModuleFlags {
    private final Supplier<MusicBoxConfig> configSupplier;

    public ModuleFlags(Supplier<MusicBoxConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    private MusicBoxConfig.ModuleConfig modules() {
        MusicBoxConfig config = configSupplier.get();
        if (config == null || config.getModules() == null) {
            return MusicBoxConfig.createDefault().getModules();
        }
        return config.getModules();
    }

    private MusicBoxConfig.PublishConfig publish() {
        MusicBoxConfig config = configSupplier.get();
        if (config == null || config.getPublishConfig() == null) {
            return MusicBoxConfig.createDefault().getPublishConfig();
        }
        return config.getPublishConfig();
    }

    public boolean isPlaybackModuleEnabled() {
        return modules().isPlayback();
    }

    public boolean isShopModuleEnabled() {
        return modules().isShop();
    }

    public boolean isPlayerMusicModuleEnabled() {
        return modules().isPlayerMusic();
    }

    public boolean isPublishModuleEnabled() {
        return isPlayerMusicModuleEnabled() && modules().isPublish() && publish().isEnable();
    }

    public boolean isPlayerMusicShopModuleEnabled() {
        return isPlayerMusicModuleEnabled() && isShopModuleEnabled();
    }

    public boolean isEditorModuleEnabled() {
        return modules().isEditor();
    }

    public boolean isWebEditorModuleEnabled() {
        return isEditorModuleEnabled() && modules().isWebEditor();
    }

    public boolean isSignsModuleEnabled() {
        return modules().isSigns();
    }

    public boolean isJukeboxModuleEnabled() {
        return modules().isJukeboxes();
    }

    public boolean isPlaylistsModuleEnabled() {
        return modules().isPlaylists();
    }

    public boolean isGiveModuleEnabled() {
        return modules().isGive();
    }

    public boolean isTextPlayerModuleEnabled() {
        return modules().isTextPlayer();
    }

    public boolean isSongTagsModuleEnabled() {
        return modules().isSongTags();
    }

    public boolean usesPlayerMusicLibrary() {
        return isEditorModuleEnabled() || isPlayerMusicShopModuleEnabled() || isPublishModuleEnabled();
    }

    public boolean usesPublishedMusicLibrary() {
        return isPlayerMusicShopModuleEnabled() || isPublishModuleEnabled();
    }

    public boolean usesAnyPlaybackRuntime() {
        return isPlaybackModuleEnabled() || isSignsModuleEnabled() || isJukeboxModuleEnabled() || isTextPlayerModuleEnabled();
    }
}
