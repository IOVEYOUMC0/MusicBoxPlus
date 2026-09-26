package com.huidu.musicboxplus.core.nbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Points a stopwatch at every place the plugin touches the disk so a claimed
// hotspot has to survive measurement instead of opinion:
//   - readMetadata: library load, full read plus header-only parse
//   - read:         playback compile cold path, full read plus full parse
//   - scan + metadata: the loadSync/loadAsync shape over a whole library
//   - write:        song save from the editor / player music creation
//
// No assertions on timings: a shared machine is too noisy to gate on, and the
// point is "where does the time go", not "enforce a threshold". It prints a
// breakdown and fails only on real breakage.
class FileIoHotspotTest {

    private static final int WARMUP = 3;
    private static final int ITERATIONS = 5;

    @TempDir
    Path tempDir;

    @FunctionalInterface
    private interface IoTask {
        void run() throws Exception;
    }

    // Best wall time over N iterations, in microseconds. Min-of-N filters
    // scheduler noise and GC pauses better than the average does.
    private static long bestUs(int iterations, IoTask task) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            task.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best / 1000;
    }

    @Test
    void readBreakdownPerFile() throws Exception {
        // NbsCorpus.collect returns root-relative paths; resolve back to real files.
        List<Path> files = NbsCorpus.collect(NbsCorpus.BUNDLED).stream()
                .map(NbsCorpus.BUNDLED::resolve)
                .toList();
        long sumIo = 0;
        long sumMetadata = 0;
        long sumFull = 0;
        int v012 = 0;
        int v3Plus = 0;

        System.out.println("=== per-file read breakdown (min of " + ITERATIONS + ") ===");
        System.out.printf("%-34s %8s %4s %12s %12s %12s%n",
                "file", "bytes", "ver", "readAllBytes", "readMetadata", "read(full)");
        for (Path file : files) {
            for (int i = 0; i < WARMUP; i++) {
                NbsReader.readMetadata(file);
                NbsReader.read(file);
            }
            long io = bestUs(ITERATIONS, () -> Files.readAllBytes(file));
            long metadata = bestUs(ITERATIONS, () -> NbsReader.readMetadata(file));
            long full = bestUs(ITERATIONS, () -> NbsReader.read(file));
            int version = NbsReader.readMetadata(file).version();
            if (version <= 2) {
                v012++;
            } else {
                v3Plus++;
            }
            sumIo += io;
            sumMetadata += metadata;
            sumFull += full;
            System.out.printf("%-34s %8d %4d %12d %12d %12d%n",
                    file.getFileName(), Files.size(file), version, io, metadata, full);
        }
        System.out.printf("totals: readAllBytes=%dus metadata=%dus full=%dus; "
                + "v0-2 files=%d, v3+ files=%d%n", sumIo, sumMetadata, sumFull, v012, v3Plus);
        System.out.println("metadata skips the note graph; the (full - metadata) gap is the "
                + "note parsing the library load already avoids. v0-2 files still walk notes "
                + "to derive the length, so their gap is near zero.");
    }

    @Test
    void simulatedLibraryLoad() throws Exception {
        Path lib = tempDir.resolve("lib");
        List<Path> corpus = NbsCorpus.collect(NbsCorpus.BUNDLED);
        List<Path> allFiles = new ArrayList<>();
        for (int copy = 0; copy < 5; copy++) {
            for (Path rel : corpus) {
                Path dst = lib.resolve("copy" + copy).resolve(rel.getFileName().toString());
                Files.createDirectories(dst.getParent());
                Files.copy(NbsCorpus.BUNDLED.resolve(rel), dst);
                allFiles.add(dst);
            }
        }
        int n = allFiles.size();

        for (int i = 0; i < WARMUP; i++) {
            loadSequentially(lib);
        }
        long sequential = bestUs(ITERATIONS, () -> loadSequentially(lib));
        long parallel = bestUs(ITERATIONS, () -> loadInParallel(allFiles));
        System.out.println("=== simulated " + n + "-file library ===");
        System.out.printf("sequential scan+metadata: %d us total, %d us/file%n",
                sequential, sequential / n);
        System.out.printf("parallel   scan+metadata: %d us total, x%.1f speedup%n",
                parallel, (double) sequential / Math.max(1, parallel));
    }

    // Walks a directory tree and reads every .nbs header, mirroring the work
    // loadSync/loadAsync does per file.
    private static void loadSequentially(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".nbs")).toList()) {
                NbsReader.readMetadata(p);
            }
        }
    }

    private static void loadInParallel(List<Path> files) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, cores));
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        NbsReader.readMetadata(file);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void realLibraryLoadIfPresent() throws Exception {
        Path root = NbsCorpus.extendedRoot();
        if (root == null) {
            System.out.println("extended corpus not present; skipped");
            return;
        }
        List<Path> files = NbsCorpus.collect(root).stream()
                .map(root::resolve)
                .toList();
        for (int i = 0; i < WARMUP; i++) {
            loadSequentially(root);
        }
        long sequential = bestUs(ITERATIONS, () -> loadSequentially(root));
        long parallel = bestUs(ITERATIONS, () -> loadInParallel(files));
        System.out.println("=== real library " + root + ": " + files.size() + " files ===");
        System.out.printf("sequential: %d us total (%d us/file), parallel: %d us (x%.1f)%n",
                sequential, files.isEmpty() ? 0 : sequential / files.size(),
                parallel, (double) sequential / Math.max(1, parallel));
    }

    @Test
    void writeHotspot() throws Exception {
        List<Path> files = NbsCorpus.collect(NbsCorpus.BUNDLED).stream()
                .map(NbsCorpus.BUNDLED::resolve)
                .toList();
        Path outDir = tempDir.resolve("written");
        Files.createDirectories(outDir);
        long total = 0;
        System.out.println("=== per-file write ===");
        for (Path file : files) {
            RawNbsSong song = NbsReader.read(file);
            Path out = outDir.resolve(file.getFileName().toString());
            long us = bestUs(ITERATIONS, () -> NbsWriter.write(song, out));
            total += us;
            System.out.printf("%-34s %8d %12d%n", file.getFileName(), Files.size(file), us);
        }
        System.out.printf("write totals: %d files, %d us, %d us/file%n",
                files.size(), total, total / files.size());
    }
}
