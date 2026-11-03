package io.proify.lyricon

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.app.bridge.FavoriteClient
import io.github.proify.lyricon.app.bridge.FavoriteTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FavoriteColdStartTest {
    @Test fun directoryLookupAndReportingWakeForceStoppedProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val info = context.packageManager.getApplicationInfo("io.github.proify.lyricon.localprovider", 0)
        val loader = dalvik.system.PathClassLoader(info.sourceDir, context.classLoader)
        val runtime = loader.loadClass("io.github.proify.lyricon.localprovider.data.RuntimeBridge")
        val instance = runtime.getField("INSTANCE").get(null)
        runtime.methods.single { it.name == "initialize" }.invoke(instance, context, {})
        runtime.getMethod("setTrackMetadata", String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType)
            .invoke(instance, "Cold recovery fixture", "Fixture artist", "", 6000L)
        stopProvider()
        val lookup = runtime.methods.single { it.name == "find" }
        val value = kotlin.coroutines.suspendCoroutine<Any?> { continuation ->
            val result = lookup.invoke(instance, "Cold recovery fixture", "Fixture artist", null, continuation)
            if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resumeWith(Result.success(result))
        }
        assertNotNull("directory lyrics must be read after the module process was stopped", value)
        val song = (value as Pair<*, *>).first!!
        assertTrue(song.toString().contains("Automatic directory recovery"))
        stopProvider()
        runtime.methods.single { it.name == "report" }.invoke(instance, song, "目录歌词", null, "cold-recovery-report", false)
        kotlinx.coroutines.delay(4000)
    }

    @Test fun playerCapturesFavoritesAndRatingsWithoutWakingProvider() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val info = context.packageManager.getApplicationInfo("io.github.proify.lyricon.localprovider", 0)
        val loader = dalvik.system.PathClassLoader(info.sourceDir, context.classLoader)
        val runtime = loader.loadClass("io.github.proify.lyricon.localprovider.data.RuntimeBridge")
        val instance = runtime.getField("INSTANCE").get(null)
        runtime.methods.single { it.name == "initialize" }.invoke(instance, context, {})
        runtime.getMethod("setTrackMetadata", String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType)
            .invoke(instance, "Player storage fixture", "Fixture artist", "", 30000L)
        val reporter = loader.loadClass("io.github.proify.lyricon.localprovider.xposed.PreferenceReporter")
        val reporterInstance = reporter.getField("INSTANCE").get(null)
        val prefs = context.getSharedPreferences("local_provider_preference_outbox", 0)
        val old = prefs.all.toMap()
        val session = android.media.session.MediaSession(context, "Preference hook fixture")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) { done.set(true) }
        }
        context.registerReceiver(receiver, android.content.IntentFilter("lyricon.test.FINISH_PLAYER_TEST"), android.content.Context.RECEIVER_EXPORTED)
        suspend fun send(rating: android.media.Rating, expectedRating: Int? = null) {
            val value = android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, "player-journal-fixture")
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Player storage fixture")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Fixture artist")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 30000)
                .putRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING, rating).build()
            val before = prefs.all.keys.count { it.startsWith("event:") }
            reporter.methods.single { it.name == "metadata" }.invoke(reporterInstance, context, session, value)
            val deadline = System.currentTimeMillis() + 5000
            while (prefs.all.keys.count { it.startsWith("event:") } == before) {
                check(System.currentTimeMillis() < deadline); kotlinx.coroutines.delay(20)
            }
            expectedRating?.let { expected -> assertTrue(prefs.all.filterKeys { it.startsWith("rating_state:") }.values.contains(expected)) }
        }
        try {
            prefs.edit().clear().commit()
            stopProvider()
            send(android.media.Rating.newHeartRating(false))
            send(android.media.Rating.newStarRating(android.media.Rating.RATING_5_STARS, 3f), 60)
            send(android.media.Rating.newStarRating(android.media.Rating.RATING_5_STARS, 4f), 80)
            send(android.media.Rating.newUnratedRating(android.media.Rating.RATING_5_STARS), 0)
            val track = FavoriteTrack(context.packageName, "player-journal-fixture", "Player storage fixture", "Fixture artist", "", 30000)
            val started = android.os.SystemClock.elapsedRealtime()
            assertTrue(FavoriteClient.toggle(context, track).getBoolean("favorite"))
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertTrue("favorite should finish without waiting for module startup: ${elapsed}ms", elapsed < 1000)
            assertFalse(FavoriteClient.toggle(context, track).getBoolean("favorite"))
            assertTrue(FavoriteClient.toggle(context, track).getBoolean("favorite"))
            val current = FavoriteClient.request(context, track)
            assertEquals(0, current.getInt("rating", -1))
            assertEquals(7, prefs.all.keys.count { it.startsWith("event:") })
            val pid = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pidof io.github.proify.lyricon.localprovider")).use { it.readBytes().toString(Charsets.UTF_8).trim() }
            assertEquals("module must stay stopped during favorites and rating capture", "", pid)
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("fixture", "READY_FOR_COLLECTION"); putLong("favorite_latency_ms", elapsed) })
            val deadline = System.currentTimeMillis() + 90000
            while (!done.get()) { check(System.currentTimeMillis() < deadline); kotlinx.coroutines.delay(100) }
        } finally {
            context.unregisterReceiver(receiver)
            session.release()
            prefs.edit().clear().apply { old.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
            } } }.commit()
        }
    }
    private fun stopProvider() {
        val command = "am force-stop io.github.proify.lyricon.localprovider"
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

}
