package com.huidu.musicboxplus.core.db;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.core.db.types.MySQL;
import com.huidu.musicboxplus.core.db.types.SQLite;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;

import java.io.File;

public class DatabaseLoader {
    private static volatile DatabaseLoader instance;
    private final AbstractBase base;

    public static synchronized void reload() {
        DatabaseLoader newInstance = new DatabaseLoader();
        DatabaseLoader oldInstance = instance;
        instance = newInstance;
        if (oldInstance != null && oldInstance.base != null) {
            oldInstance.base.shutdown();
        }
    }

    public static AbstractBase getBase() {
        DatabaseLoader localInstance = instance;
        if (localInstance == null) {
            throw new IllegalStateException("DatabaseLoader 未初始化，请先调用 reload()");
        }
        return localInstance.base;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    private DatabaseLoader() {
        try {
            MusicBoxConfig config = MusicBox.getInstance().getConfigObject();
            MusicBoxConfig.DatabaseSetting dbConfig = config.getDatabase();
            
            if (dbConfig == null) {
                this.base = createDefaultSQLite();
                return;
            }
            
            String type = dbConfig.getType();
            if (type == null) {
                type = "sqlite";
            }
            
            if ("mysql".equalsIgnoreCase(type)) {
                MusicBoxConfig.MySQLSetting mysqlConfig = dbConfig.getMysql();
                if (mysqlConfig == null) {
                    MusicBox.getInstance().getLogger().warning("MySQL 配置缺失，使用 SQLite 作为默认数据库");
                    this.base = createDefaultSQLite();
                } else {
                    this.base = new MySQL(mysqlConfig);
                    MusicBox.getInstance().getLogger().info("已连接到 MySQL 数据库: " + mysqlConfig.getHost() + ":" + mysqlConfig.getPort() + "/" + mysqlConfig.getDatabase());
                }
            } else {
                this.base = createDefaultSQLite();
            }
        } catch (Exception ex) {
            if (StorageAccess.isPermissionIssue(ex) || isReadOnlyStorageFailure(ex)) {
                throw new RuntimeException(LogLocale.text(MusicBox.getInstance(),
                    "Could not initialize database because the MusicBox data folder is not writable",
                    "由于 MusicBox 数据目录不可写，无法初始化数据库"), ex);
            }
            throw new RuntimeException(LogLocale.text(MusicBox.getInstance(),
                "Could not connect to database",
                "无法连接到数据库"), ex);
        }
    }
    
    private SQLite createDefaultSQLite() {
        File dbFile = new File(MusicBox.getInstance().getDataFolder(), "data.db");
        MusicBox.getInstance().getLogger().info("Using SQLite database file: " + dbFile.getName());
        return new SQLite(dbFile);
    }

    private boolean isReadOnlyStorageFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("not writable")
                    || normalized.contains("read-only")
                    || normalized.contains("cannot create sqlite database")
                    || normalized.contains("could not create sqlite database file")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
    
    public static void shutdown() {
        DatabaseLoader localInstance = instance;
        if (localInstance != null && localInstance.base != null) {
            localInstance.base.shutdown();
        }
        instance = null;
    }
}
