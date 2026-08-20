package com.huidu.musicboxplus.common.utils.cache;

public interface ExpiringCacheCleaner
extends CacheCleaner {
    public void cleanupExpired();
}

