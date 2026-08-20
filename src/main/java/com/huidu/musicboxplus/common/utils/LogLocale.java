package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;

public final class LogLocale {
    private LogLocale() {
    }

    public static boolean useChinese(MusicBox plugin) {
        if (plugin == null) {
            return false;
        }

        MusicBoxConfig config = plugin.getConfigObject();
        if (config == null) {
            return false;
        }

        String language = config.getLanguage();
        if (language == null) {
            return false;
        }

        String normalized = language.trim().toLowerCase()
                .replace('-', '_')
                .replace('\\', '/');

        return normalized.equals("zh")
                || normalized.equals("zh_cn")
                || normalized.equals("zhcn")
                || normalized.equals("zh_tw")
                || normalized.equals("zhtw")
                || normalized.equals("zh_hk")
                || normalized.equals("zhhk")
                || normalized.endsWith("language_zh_cn.yml")
                || normalized.endsWith("language_zh_tw.yml")
                || normalized.startsWith("zh_");
    }

    public static String text(MusicBox plugin, String english, String chinese) {
        return useChinese(plugin) ? chinese : english;
    }
}
