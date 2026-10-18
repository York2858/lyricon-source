package io.github.proify.lyricon.xposed.systemui.ai.translate

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Test

class TranslationMusicLocatorTest {
    @Test fun findsExactParentAndPreservesActualAudioName() {
        val result = TranslationMusicLocator.folderForPath(
            "/storage/emulated/0/Download/MiShare/02. WINDY SUMMER.m4a", null)!!
        assertEquals("Download/MiShare", result.folderName)
        assertEquals("02. WINDY SUMMER.m4a", result.audioFileName)
        assertEquals("primary:Download/MiShare", DocumentsContract.getTreeDocumentId(Uri.parse(result.folderUri)))
    }
    @Test fun supportsSdCardAndUnicodeFolders() {
        val result = TranslationMusicLocator.folderForPath("/storage/AB12-CD34/音乐/杏里/02.m4a", "02.m4a")!!
        assertEquals("AB12-CD34:音乐/杏里", DocumentsContract.getTreeDocumentId(Uri.parse(result.folderUri)))
    }
    @Test fun doesNotInventASelectableFolderForPrivateOrUnsafePaths() {
        listOf("/data/user/0/player/cache/song.mp3", "/storage/emulated/0/Android/data/player/song.mp3",
            "/storage/emulated/0/Music/../secret/song.mp3", "/storage/emulated/0/song.mp3", "https://example/song.mp3")
            .forEach { assertNull(it, TranslationMusicLocator.folderForPath(it, null)) }
    }
}
