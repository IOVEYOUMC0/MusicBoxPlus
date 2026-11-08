package com.huidu.musicboxplus.common.config;

import java.util.*;

public class LoopModeConfig {
    String off = "<red>Off";
    String single = "<yellow>Single";
    String all = "<green>All";
    String toggled = "<gray>Loop mode: {mode}";
    List<String> lore = new ArrayList<String>();

    public String getOff() {
        return this.off;
    }

    public String getSingle() {
        return this.single;
    }

    public String getAll() {
        return this.all;
    }

    public String getToggled() {
        return this.toggled;
    }

    public List<String> getLore() {
        return this.lore;
    }
}
