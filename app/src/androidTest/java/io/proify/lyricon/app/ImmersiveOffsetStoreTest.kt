package io.github.proify.lyricon.app.activity.lyric.immersive

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ImmersiveOffsetStoreTest {
    @Test fun changingTracksAndRecreatingTheStoreKeepsUnsynchronizedOffsets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "offset-test-" + UUID.randomUUID()
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val store = ImmersiveOffsetStore(preferences)
            store.set("song-a", -931)
            assertEquals(0, store.offset("song-b"))
            store.set("song-b", 240)
            assertEquals(-931, store.offset("song-a"))
            assertTrue(store.pending("song-a"))
            val reopened = ImmersiveOffsetStore(preferences)
            assertEquals(-931, reopened.offset("song-a"))
            assertEquals(240, reopened.offset("song-b"))
            assertEquals(0, reopened.offset("unadjusted"))
            assertEquals(320, reopened.offset("database-only", 320))
            reopened.markSynced("song-a", -800)
            assertTrue(reopened.pending("song-a"))
            reopened.markSynced("song-a", -931)
            assertFalse(reopened.pending("song-a"))
            assertTrue(reopened.pending("song-b"))
        } finally { context.deleteSharedPreferences(name) }
    }
}
