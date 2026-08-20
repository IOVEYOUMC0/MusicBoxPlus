package com.huidu.musicboxplus.common.utils.cache;

public interface CacheCleaner {
    void clearCache();

    String getCacheName();

    default String getCacheStats() {
        return getCacheName() + ": 统计信息未实现";
    }
}