package com.huidu.musicboxplus.core.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.nbs.NbsCorpus;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// AbstractEnginePlayer.resolveListener: the listener -> Player cache used by the per-tick dispatch.
//
// The dispatch loop used to call Bukkit.getPlayer(uuid) for every listener of every song every tick.
// The range scan that admits a listener already holds the Player, so AbstractEnginePlayer now
// records it and dispatch reads the record back.
//
// The membership set itself keeps UUIDs, because listeners are also admitted by UUID alone while
// offline -- these tests therefore check both halves: a recorded player is returned without asking
// the server, and removing a listener must drop the record so a rejoined player is never answered
// from the previous object.
class AbstractEnginePlayerListenerCacheTest {

    private static final List<AbstractEnginePlayer> CREATED = new ArrayList<>();

    // Each player registers with the shared static clock, so they are unregistered per test rather
    // than left for the whole class. The clock itself is deliberately not shut down: it is a static
    // singleton and stopping it here would silence any test that runs afterwards.
    @AfterEach
    void destroyPlayers() {
        CREATED.forEach(AbstractEnginePlayer::destroy);
        CREATED.clear();
    }

    @Test
    void aRecordedListenerIsReturnedWithoutConsultingTheServer() throws Exception {
        TestEnginePlayer player = newPlayer();
        Player listener = fakePlayer(UUID.randomUUID());

        player.addPlayer(listener);

        // Bukkit.getPlayer would throw here (no server), so reaching the same instance proves the
        // cache was used rather than the global player table.
        assertSame(listener, player.resolveListener(listener.getUniqueId()));
    }

    @Test
    void aRejoiningPlayerIsAnsweredFromTheNewObject() throws Exception {
        TestEnginePlayer player = newPlayer();
        UUID uuid = UUID.randomUUID();
        Player beforeQuit = fakePlayer(uuid);
        player.addPlayer(beforeQuit);

        // Quit and rejoin inside one range-scan interval: the membership entry is still there, so
        // the record has to be refreshed rather than kept (putIfAbsent would keep the dead object).
        Player afterRejoin = fakePlayer(uuid);
        player.addPlayer(afterRejoin);

        assertSame(afterRejoin, player.resolveListener(uuid));
    }

    @Test
    void removingAListenerDropsBothTheMembershipAndTheRecord() throws Exception {
        TestEnginePlayer player = newPlayer();
        Player listener = fakePlayer(UUID.randomUUID());
        player.addPlayer(listener);

        player.removePlayer(listener.getUniqueId());

        assertFalse(player.getPlayers().contains(listener.getUniqueId()), "membership must be gone");
        assertTrue(player.playerList.isEmpty(), "membership map must not keep the listener");
        assertNull(player.cachedListener(listener.getUniqueId()),
                "the record must go too, or a later re-add could be answered from it");
    }

    @Test
    void removingByPlayerObjectDropsTheRecordAsWell() throws Exception {
        TestEnginePlayer player = newPlayer();
        Player listener = fakePlayer(UUID.randomUUID());
        player.addPlayer(listener);

        player.removePlayer(listener);

        assertFalse(player.getPlayers().contains(listener.getUniqueId()));
        assertNull(player.cachedListener(listener.getUniqueId()));
    }

    @Test
    void listenersAdmittedByUuidAloneAreStillMembers() throws Exception {
        TestEnginePlayer player = newPlayer();
        UUID uuid = UUID.randomUUID();

        // The offline path (RangePlayerModel.onPlayerEnterRange(UUID)) has no Player to record; the
        // membership entry must still be created, and resolveListener must fall through to the
        // global lookup for it.
        player.addPlayer(uuid);

        assertTrue(player.getPlayers().contains(uuid));
        assertNull(player.cachedListener(uuid), "no Player object was available to record");
    }

    private static TestEnginePlayer newPlayer() throws Exception {
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            Path file = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().findFirst().orElseThrow();
            TestEnginePlayer player = new TestEnginePlayer(CompiledSong.compile(NbsReader.read(file)));
            CREATED.add(player);
            return player;
        }
    }

    private static Player fakePlayer(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                AbstractEnginePlayerListenerCacheTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return uuid;
                    }
                    if (method.getName().equals("toString")) {
                        return "FakePlayer(" + uuid + ")";
                    }
                    throw new UnsupportedOperationException("FakePlayer does not implement " + method.getName());
                });
    }

    private static final class TestEnginePlayer extends AbstractEnginePlayer {

        TestEnginePlayer(CompiledSong song) {
            super(song);
        }

        Player cachedListener(UUID uuid) {
            return resolvedListeners.get(uuid);
        }

        @Override
        protected Location dispatchLocation() {
            return null;
        }

        // Only reached through the MusicBoxSongPlayer defaults, none of which these tests call.
        @Override
        public com.huidu.musicboxplus.api.player.model.MusicBoxSongPlayerModel getMusicBoxModel() {
            throw new UnsupportedOperationException("not needed for the listener cache");
        }

        @Override
        protected void playTick(Player listener, int tick) {
        }

        @Override
        protected void onSongFinished() {
        }
    }
}
