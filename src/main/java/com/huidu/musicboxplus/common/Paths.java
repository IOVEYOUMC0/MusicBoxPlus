package com.huidu.musicboxplus.common;

// Well-known file and directory names inside the plugin data folder. The reload
// command and the reload dispatcher both enumerate these names, so sharing them
// keeps the two sides from drifting apart.
public final class Paths {

    public static final String CONFIG_FILE = "config.yml";
    public static final String SONGS_DIR = "songs";
    public static final String LANG_DIR = "lang";

    private Paths() {
    }
}