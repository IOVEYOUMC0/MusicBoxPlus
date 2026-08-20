package com.huidu.musicboxplus.core.db.types;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.db.AbstractBase;
import com.huidu.musicboxplus.common.utils.HikariLogConfigurator;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class SQLite extends AbstractBase {
    
    @Override
    public boolean isMySQL() {
        return false;
    }
    
    private final HikariDataSource dataSource;
    private volatile boolean shutdown = false;

    public SQLite(File file) {
        super("SQLite");
        if (!file.exists()) {
            if (!StorageAccess.canWriteTo(file)) {
                throw new IllegalArgumentException(LogLocale.text(MusicBox.getInstance(),
                    "MusicBox data folder is not writable, cannot create SQLite database: " + file.getAbsolutePath(),
                    "MusicBox 数据目录不可写，无法创建 SQLite 数据库: " + file.getAbsolutePath()));
            }
            file.getParentFile().mkdirs();
            try {
                file.createNewFile();
            } catch (Exception e) {
                throw new IllegalArgumentException(LogLocale.text(MusicBox.getInstance(),
                    "Could not create SQLite database file: " + file.getAbsolutePath(),
                    "无法创建 SQLite 数据库文件: " + file.getAbsolutePath()), e);
            }
        }
        
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("SQLite JDBC 驱动不可用!");
        }
        
        this.dataSource = createDataSource(file);
        MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(), "SQLite connection pool initialized (HikariCP)", "SQLite连接池已初始化 (HikariCP)"));
        this.afterInit();
    }
    
    private HikariDataSource createDataSource(File file) {
        HikariLogConfigurator.configure(MusicBox.getInstance());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        // WAL (below) lets SQLite serve concurrent readers alongside a single writer, so a pool of 1
        // needlessly serialized every async read behind every other read/write (head-of-line blocking
        // on join / GUI-open bursts). Allow a few connections so reads parallelize; SQLite's global
        // write lock still serializes writers, and busy_timeout makes a blocked writer WAIT for the
        // lock instead of failing with SQLITE_BUSY.
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setMaxLifetime(1800000);
        config.setConnectionTimeout(30000);
        config.setLeakDetectionThreshold(60000);
        config.setPoolName("MusicBox-SQLite-Pool");

        // As driver properties, not a semicolon-joined connectionInitSql. Hikari hands that string
        // to Statement.execute(), and sqlite-jdbc runs only the FIRST statement in it -- silently,
        // with no error. Measured on sqlite-jdbc 3.49.1: the combined form left synchronous=FULL
        // (an fsync on every write, which is the cost WAL exists to avoid), foreign_keys off, and
        // busy_timeout at the 3000 ms default rather than the 10000 the comment below relies on.
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("busy_timeout", "10000");
        
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        
        return new HikariDataSource(config);
    }

    @Override
    protected Connection acquireConnection() throws SQLException {
        if (shutdown) {
            throw new SQLException("连接池已关闭");
        }
        return dataSource.getConnection();
    }
    
    @Override
    public void shutdown() {
        shutdown = true;
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(), "SQLite connection pool closed", "SQLite连接池已关闭"));
        }
    }
}
