package com.huidu.musicboxplus.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// Platform rules that come from Folia itself rather than from this codebase, pinned so a new call
// site cannot quietly reintroduce them. Each was checked against the Folia jar the server runs
// (verified by disassembling the classes, not from documentation):
//
//   * There is no Bukkit scheduler on Folia. CraftServer.isPrimaryThread() is TickThread.isTickThread(),
//     i.e. true on *every* region thread, so the Bukkit scheduler's "main thread" model has no
//     equivalent; this plugin routes everything through common/utils/scheduler/Scheduler.
//   * Entity#teleport throws UnsupportedOperationException("Must use teleportAsync while in region
//     threading") from CraftEntity.teleport0. Only teleportAsync exists.
//   * ALL scoreboard API is listed as broken by Folia's own README (global state it has not
//     implemented), so this plugin must not touch it.
//   * World#spawnParticle sends to the *current region's* players (ServerLevel.sendParticlesSource ->
//     RegionizedWorldData.getLocalPlayers), so a particle has to be spawned from the region that owns
//     its location. The two call sites below each run on that region and are listed so that a third
//     one has to be reviewed for its thread context rather than added silently.
//
// This is a source scan: it cannot prove the thread a call runs on, only that the platform's own
// hard rules are still respected and that the reviewed call sites are still the reviewed ones.
class FoliaApiUsageTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    // Reviewed particle call sites. SpeakerPlayer spawns the note above the listener, from that
    // listener's own dispatch; JukeboxPlayer spawns it at the block, from the block's region timer.
    private static final List<String> REVIEWED_PARTICLE_FILES = List.of(
            "SpeakerPlayer.java", "JukeboxPlayer.java");

    private static List<Path> mainSources() throws Exception {
        try (Stream<Path> stream = Files.walk(MAIN)) {
            return stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void theBukkitSchedulerIsNeverUsed() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path path : mainSources()) {
            String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            for (String banned : List.of("Bukkit.getScheduler(", "BukkitRunnable", "scheduleSyncDelayedTask",
                    "runTaskAsynchronously(", "runTaskTimer(")) {
                if (code.contains(banned)) {
                    offenders.add(path.getFileName() + ": " + banned);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "Folia has no Bukkit scheduler; route through Scheduler.global/region/entity/async");
    }

    @Test
    void entitiesAreOnlyEverTeleportedAsynchronously() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path path : mainSources()) {
            String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            for (String line : code.split("\n")) {
                if (!line.contains(".teleport(")) {
                    continue;
                }
                // The plugin's own helpers are not Bukkit's Entity#teleport: TextDisplayVisual.teleport
                // and IdleTextDisplay.relocate route to teleportAsync internally, and Scheduler.teleport
                // is the async wrapper.
                if (line.contains("visual.teleport(") || line.contains("Scheduler.teleport(")) {
                    continue;
                }
                offenders.add(path.getFileName() + ": " + line.trim());
            }
        }
        assertEquals(List.of(), offenders,
                "Entity#teleport throws on Folia (\"Must use teleportAsync while in region threading\")");
    }

    @Test
    void theScoreboardIsNotTouched() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path path : mainSources()) {
            String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            if (code.contains("getScoreboard") || code.contains("Scoreboard")
                    || code.contains("new Team") || code.contains("org.bukkit.scoreboard")) {
                offenders.add(path.getFileName().toString());
            }
        }
        assertEquals(List.of(), offenders,
                "Folia's README lists ALL scoreboard API as broken (unimplemented global state)");
    }

    @Test
    void particlesComeOnlyFromTheReviewedCallSites() throws Exception {
        List<String> found = new ArrayList<>();
        for (Path path : mainSources()) {
            String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            if (code.contains("spawnParticle(")) {
                found.add(path.getFileName().toString());
            }
        }
        found.sort(String::compareTo);
        List<String> reviewed = new ArrayList<>(REVIEWED_PARTICLE_FILES);
        reviewed.sort(String::compareTo);
        assertEquals(reviewed, found,
                "a new spawnParticle call site must run on the region that owns the location, because "
                        + "Folia delivers a particle to the current region's players only");
    }

    @Test
    void theAuditActuallyScannedTheSources() throws Exception {
        assertTrue(mainSources().size() > 100, "the main source tree moved; this scan checked nothing");
    }

    // Paper's region/entity/async schedulers exist on Folia too, but choosing among them is exactly
    // the decision this plugin centralises in common/utils/scheduler/Scheduler: the facade is the
    // only place that should have to know which thread a piece of work belongs on. Anything else
    // naming the scheduler API directly is a place the Folia audit cannot see.
    //
    // common/stats is the vendored bStats implementation, which is not ours.
    @Test
    void theRegionSchedulerApiIsOnlyReachedThroughTheSchedulerFacade() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path path : mainSources()) {
            String name = path.getFileName().toString();
            String normalised = path.toString().replace('\\', '/');
            if (name.equals("Scheduler.java") || normalised.contains("/common/stats/")) {
                continue;
            }
            String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
            for (String banned : List.of("getRegionScheduler()", "getGlobalRegionScheduler()",
                    "getAsyncScheduler()", ".getScheduler()")) {
                if (code.contains(banned)) {
                    offenders.add(name + ": " + banned);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "route scheduling through Scheduler.global/region/entity/async so the Folia audit "
                        + "sees every call site");
    }

    // The plugin.yml flag is what makes Folia load the plugin at all: without it the server refuses
    // the plugin outright, so the whole Scheduler facade would be unreachable.
    @Test
    void thePluginDeclaresFoliaSupport() throws Exception {
        String pluginYml = Files.readString(
                Path.of("src", "main", "resources", "plugin.yml"), StandardCharsets.UTF_8);

        assertTrue(pluginYml.contains("folia-supported: true"),
                "plugin.yml must keep 'folia-supported: true'");
        assertTrue(pluginYml.contains("api-version:"), "plugin.yml must declare api-version");
    }
}
