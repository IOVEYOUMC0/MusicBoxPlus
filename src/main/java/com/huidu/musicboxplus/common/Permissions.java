package com.huidu.musicboxplus.common;

// Central registry for every permission node the plugin checks. Keeping them in one
// place makes the nodes discoverable, prevents typos, and keeps plugin.yml in sync
// with what the code actually checks.
public final class Permissions {

    public static final String ADMIN = "musicboxplus.admin";
    public static final String TAG = "musicboxplus.tag";
    public static final String AUTOPLAY = "musicboxplus.autoplay";
    public static final String CONTROL = "musicboxplus.control";
    public static final String EDIT = "musicboxplus.edit";
    public static final String EDIT_LIMIT_UNLIMITED = "musicboxplus.edit.limit.unlimited";
    public static final String EDIT_LIMIT_PREFIX = "musicboxplus.edit.limit.";
    public static final String GIVE = "musicboxplus.give";
    public static final String HEAR = "musicboxplus.hear";
    public static final String MUTE = "musicboxplus.mute";
    public static final String PLAY_OTHER = "musicboxplus.play.other";
    public static final String PLAYLIST = "musicboxplus.playlist";
    public static final String PUBLISH_REVIEW = "musicboxplus.publish.review";
    public static final String RELOAD = "musicboxplus.reload";
    public static final String SHOP = "musicboxplus.shop";
    public static final String SHOPMUSIC = "musicboxplus.shopmusic";
    public static final String SIGN = "musicboxplus.sign";
    public static final String SIGN_PROTECT = "musicboxplus.sign.protect";
    public static final String SILENT_OTHER = "musicboxplus.silent.other";
    public static final String SPEAKER = "musicboxplus.speaker";
    public static final String SPEED = "musicboxplus.speed";
    public static final String STOP = "musicboxplus.stop";
    public static final String STOP_OTHERS = "musicboxplus.stop.others";
    public static final String USE = "musicboxplus.use";
    public static final String VOLUME = "musicboxplus.volume";

    private Permissions() {
    }
}