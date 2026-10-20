package io.github.proify.lyricon.xposed.systemui.lyric

import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.SimpleActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in read-only diagnosis of the connected player's supplied lyric fields. */
class CurrentLyricDataProbe {
    @Test fun inspectSuppliedFields() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectCurrentLyrics") == "true")
        val ready = CountDownLatch(1)
        var snapshot: Song? = null
        var romaEnabled: Boolean? = null
        var translationEnabled: Boolean? = null
        var provider: ProviderInfo? = null
        val subscriber = LyriconFactory.createSubscriber(instrumentation.targetContext)
        val listener = object : SimpleActivePlayerListener {
            override fun onActiveProviderChanged(providerInfo: ProviderInfo?) { provider = providerInfo }
            override fun onSongChanged(song: Song?) { if (!song?.lyrics.isNullOrEmpty()) { snapshot = song; ready.countDown() } }
            override fun onDisplayRomaChanged(isDisplayRoma: Boolean) { romaEnabled = isDisplayRoma }
            override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) { translationEnabled = isDisplayTranslation }
        }
        try {
            subscriber.subscribeActivePlayer(listener)
            subscriber.register()
            val received = ready.await(15, TimeUnit.SECONDS)
            val lines = snapshot?.lyrics.orEmpty()
            val result = buildString {
                appendLine("received=$received lines=${lines.size} romaFlag=$romaEnabled translationFlag=$translationEnabled")
                appendLine("provider=${provider?.providerPackageName} player=${provider?.playerPackageName}")
                appendLine("roma=${lines.count { !it.roma.isNullOrBlank() }} translation=${lines.count { !it.translation.isNullOrBlank() }} secondary=${lines.count { !it.secondary.isNullOrBlank() }}")
                lines.filter { row -> row.text.orEmpty().any { it.code in 0x3040..0x30FF } }.take(2).forEach {
                    appendLine("sampleOriginal=${it.text?.take(20)} sampleTranslation=${it.translation?.take(60)}")
                }
                lines.filter { !it.roma.isNullOrBlank() || !it.translation.isNullOrBlank() || !it.secondary.isNullOrBlank() }.take(10).forEach {
                    appendLine("time=${it.begin} originalLength=${it.text?.length ?: 0} romaLength=${it.roma?.length ?: 0} translationLength=${it.translation?.length ?: 0} secondaryLength=${it.secondary?.length ?: 0}")
                }
            }
            File(instrumentation.targetContext.cacheDir, "current-lyric-fields.txt").writeText(result)
            result.lineSequence().filter { it.isNotBlank() }.forEach { println("LYRIC_PROBE_$it") }
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
                File(it, "current-lyric-fields.txt").apply { parentFile?.mkdirs() }.writeText(result)
            }
        } finally { subscriber.destroy() }
    }
}
