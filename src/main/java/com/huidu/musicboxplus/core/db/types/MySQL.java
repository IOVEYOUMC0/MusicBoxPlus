package com.huidu.musicboxplus.core.db.types;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.core.db.AbstractBase;
import com.huidu.musicboxplus.common.utils.HikariLogConfigurator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class MySQL extends AbstractBase {
    private final HikariDataSource dataSource;
    private final String tablePrefix;
    
    @Override
    public boolean isMySQL() {
        return true;
    }

    public MySQL(MusicBoxConfig.MySQLSetting config) {
        super("MySQL");
        this.tablePrefix = config.getTablePrefix() != null ? config.getTablePrefix() : "";
        
        HikariLogConfigurator.configure(MusicBox.getInstance());
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase() + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC");
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setIdleTimeout(300000);
        hikariConfig.setConnectionTimeout(20000);
        hikariConfig.setMaxLifetime(1200000);
        hikariConfig.setPoolName("MusicBox-MySQL-Pool");
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setLeakDetectionThreshold(60000);
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        
        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            this.afterInit();
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().severe("MySQL 连接失败: " + e.getMessage());
            throw new RuntimeException("无法连接到 MySQL 数据库", e);
        }
    }

    @Override
    protected Connection acquireConnection() throws SQLException {
        return this.dataSource.getConnection();
    }
    
    @Override
    protected String getTablePrefix() {
        return this.tablePrefix;
    }
    
    @Override
    public void shutdown() {
        if (this.dataSource != null && !this.dataSource.isClosed()) {
            this.dataSource.close();
            MusicBox.getInstance().getLogger().info("MySQL 连接池已关闭");
        }
    }
}
