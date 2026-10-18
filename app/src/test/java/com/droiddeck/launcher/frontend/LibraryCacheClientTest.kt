package com.droiddeck.launcher.frontend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryCacheClientTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val games = listOf(Library.SteamGame(440, "Team Fortress 2", null, "Steam library"))

    @Test fun aCacheFromBeforeTheClientWasRecordedIsArm64s() {
        LibraryCache.save(app, games)
        assertFalse(LibraryCache.listedX64(app))
    }

    @Test fun theListsClientIsKeptUntilAListForAnotherIsSaved() {
        LibraryCache.save(app, games, x64 = true)
        assertTrue(LibraryCache.listedX64(app))
        LibraryCache.save(app, emptyList())
        assertTrue(LibraryCache.listedX64(app))
        LibraryCache.save(app, games, x64 = false)
        assertFalse(LibraryCache.listedX64(app))
    }
}
