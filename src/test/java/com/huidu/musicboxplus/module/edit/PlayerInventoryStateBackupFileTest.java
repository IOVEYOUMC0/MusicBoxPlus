package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The file half of PlayerInventoryState: writing a backup and removing it.
//
// The write moved off the player's region thread because the file write, not the serialization, is
// where the cost is (measured: 0.04-0.31 ms to serialize the three item arrays vs 2.1-18.2 ms to
// write and rename them). That makes the order of "write" and "delete" observable, so these pin what
// each side does to the folder -- including that no .tmp is left behind for a later startup to trip
// over, and that the payload that lands on disk is byte-for-byte the one handed over.
class PlayerInventoryStateBackupFileTest {

    @Test
    void writesThePayloadAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] payload = new byte[]{1, 2, 3, 4, 5};

        PlayerInventoryState.writeBackupFile(dir.toFile(), uuid, payload);

        Path file = dir.resolve(uuid + ".dat");
        assertTrue(Files.exists(file), "the backup must exist after a successful write");
        assertArrayEquals(payload, Files.readAllBytes(file));
        assertFalse(Files.exists(dir.resolve(uuid + ".dat.tmp")),
                "a leftover .tmp would be indistinguishable from a crashed write");
    }

    @Test
    void rewritingReplacesThePreviousBackup(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();

        PlayerInventoryState.writeBackupFile(dir.toFile(), uuid, new byte[]{9, 9, 9});
        PlayerInventoryState.writeBackupFile(dir.toFile(), uuid, new byte[]{1});

        assertArrayEquals(new byte[]{1}, Files.readAllBytes(dir.resolve(uuid + ".dat")));
    }

    @Test
    void deleteRemovesTheBackupAndAnyTemporaryFile(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerInventoryState.writeBackupFile(dir.toFile(), uuid, new byte[]{7});
        // A temp file from a write that was interrupted before its move.
        Files.write(dir.resolve(uuid + ".dat.tmp"), new byte[]{8});

        PlayerInventoryState.deleteBackupFile(dir.toFile(), uuid);

        assertFalse(Files.exists(dir.resolve(uuid + ".dat")), "the backup must be gone");
        assertFalse(Files.exists(dir.resolve(uuid + ".dat.tmp")),
                "a temp file whose backup is gone can never be loaded and must not be kept");
    }

    @Test
    void deleteIsHarmlessWhenNothingWasWritten(@TempDir Path dir) {
        PlayerInventoryState.deleteBackupFile(dir.toFile(), UUID.randomUUID());

        assertTrue(Files.isDirectory(dir), "deleting a backup that does not exist must not fail");
    }

    @Test
    void backupsOfDifferentPlayersDoNotCollide(@TempDir Path dir) throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        PlayerInventoryState.writeBackupFile(dir.toFile(), first, new byte[]{1});
        PlayerInventoryState.writeBackupFile(dir.toFile(), second, new byte[]{2});
        PlayerInventoryState.deleteBackupFile(dir.toFile(), first);

        assertFalse(Files.exists(dir.resolve(first + ".dat")));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(dir.resolve(second + ".dat")));
    }
}
