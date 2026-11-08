package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /musicboxplus backup}: zip the data folder (config, language files, songs, and the SQLite
 * database) into {@code backups/} and prune old archives.
 *
 * <p>This exists because the only copy of a player's library is the live data folder. Until now the
 * recovery path for a bad edit or a failed upgrade was "restore the server backup, if there is one".
 *
 * <p>The zip is built off the server thread: walking a large songs tree and compressing it is
 * seconds of I/O, and on Folia blocking a region thread for that long stalls every player in it.
 */
public class BackupExecutor implements SubCommand {

    /** Keeps the directory from growing without bound; arbitrary but generous for a private server. */
    private static final int KEEP = 10;

    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    public void execute(CommandSender sender, String[] args) {
        MusicBox plugin = MusicBox.getInstance();
        if (!running.compareAndSet(false, true)) {
            MessageUtils.send(sender, LogLocale.text(plugin,
                    "A backup is already running.", "已有备份正在进行中。"));
            return;
        }
        MessageUtils.send(sender, LogLocale.text(plugin,
                "Creating backup, this may take a moment...", "正在创建备份，请稍候..."));

        AsyncTaskManager.runAsync(() -> {
            try {
                boolean mysql = DatabaseLoader.isInitialized() && DatabaseLoader.getBase().isMySQL();
                boolean checkpointed = !mysql
                        && DatabaseLoader.isInitialized()
                        && DatabaseLoader.getBase().checkpointForBackup();
                if (!mysql && !checkpointed) {
                    // Not fatal, but the archive may then be missing the newest writes, and saying
                    // "backup created" without that caveat is how people end up with a useless file.
                    plugin.getLogger().warning(LogLocale.text(plugin,
                            "Backup created without a SQLite WAL checkpoint; recent writes may be missing",
                            "备份未完成 SQLite WAL checkpoint，最近写入可能缺失"));
                }
                File dataFolder = plugin.getDataFolder();
                File backupDir = new File(dataFolder, "backups");
                var result = com.huidu.musicboxplus.core.backup.BackupService.create(
                        dataFolder, backupDir, mysql, KEEP);
                Scheduler.global(() -> report(sender, result));
            } catch (Exception failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Backup failed", failure);
                Scheduler.global(() -> MessageUtils.send(sender, LogLocale.text(plugin,
                        "Backup failed: " + failure.getMessage(),
                        "备份失败: " + failure.getMessage())));
            } finally {
                running.set(false);
            }
        }, () -> {
            running.set(false);
            Scheduler.global(() -> MessageUtils.send(sender, LogLocale.text(plugin,
                    "Backup was rejected because the async queue is full.", "异步队列已满，备份未执行。")));
        });
    }

    private void report(CommandSender sender,
                        com.huidu.musicboxplus.core.backup.BackupService.BackupResult result) {
        MusicBox plugin = MusicBox.getInstance();
        double megabytes = result.bytes() / (1024.0 * 1024.0);
        MessageUtils.send(sender, LogLocale.text(plugin,
                "Backup created: " + result.archive() + " ("
                        + String.format(java.util.Locale.ROOT, "%.1f", megabytes) + " MB, "
                        + result.entries() + " files)",
                "备份已创建: " + result.archive() + "（"
                        + String.format(java.util.Locale.ROOT, "%.1f", megabytes) + " MB，"
                        + result.entries() + " 个文件）"));
        if (!result.databaseFileIncluded()) {
            MessageUtils.send(sender, LogLocale.text(plugin,
                    "The database is MySQL, so its contents are not in the zip; dump it with mysqldump.",
                    "数据库为 MySQL，其内容不在压缩包内；请使用 mysqldump 备份。"));
        }
        if (!result.skipped().isEmpty()) {
            MessageUtils.send(sender, LogLocale.text(plugin,
                    "Skipped " + result.skipped().size() + " oversized file(s).",
                    "已跳过 " + result.skipped().size() + " 个超大文件。"));
        }
        if (result.pruned() > 0) {
            MessageUtils.send(sender, LogLocale.text(plugin,
                    "Removed " + result.pruned() + " old backup(s).",
                    "已清理 " + result.pruned() + " 个旧备份。"));
        }
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.RELOAD);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
