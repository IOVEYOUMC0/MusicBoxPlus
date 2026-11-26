package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
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
 * /musicboxplus backup: zip the data folder (config, language files, songs, and the SQLite
 * database) into backups/ and prune old archives.
 *
 * This exists because the only copy of a player's library is the live data folder. Until now the
 * recovery path for a bad edit or a failed upgrade was "restore the server backup, if there is one".
 *
 * The zip is built off the server thread: walking a large songs tree and compressing it is
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
            MessageUtils.send(sender, Lang.BACKUP_RUNNING);
            return;
        }
        MessageUtils.send(sender, Lang.BACKUP_STARTED);

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
                Scheduler.global(() -> MessageUtils.send(sender, Lang.BACKUP_FAILED,
                        "{error}", String.valueOf(failure.getMessage())));
            } finally {
                running.set(false);
            }
        }, () -> {
            running.set(false);
            Scheduler.global(() -> MessageUtils.send(sender, Lang.BACKUP_QUEUE_FULL));
        });
    }

    private void report(CommandSender sender,
                        com.huidu.musicboxplus.core.backup.BackupService.BackupResult result) {
        double megabytes = result.bytes() / (1024.0 * 1024.0);
        MessageUtils.send(sender, Lang.BACKUP_CREATED,
                "{path}", String.valueOf(result.archive()),
                "{size}", String.format(java.util.Locale.ROOT, "%.1f", megabytes),
                "{files}", String.valueOf(result.entries()));
        if (!result.databaseFileIncluded()) {
            MessageUtils.send(sender, Lang.BACKUP_MYSQL_NOTE);
        }
        if (!result.skipped().isEmpty()) {
            MessageUtils.send(sender, Lang.BACKUP_SKIPPED,
                    "{count}", String.valueOf(result.skipped().size()));
        }
        if (result.pruned() > 0) {
            MessageUtils.send(sender, Lang.BACKUP_PRUNED,
                    "{count}", String.valueOf(result.pruned()));
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
