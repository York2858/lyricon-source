package io.github.proify.lyricon.app.bridge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.MediaStore

/** Query content-URI handlers, including editors without a launcher activity. */
object AudioOpenApps {
    fun query(packageManager: PackageManager): List<ResolveInfo> =
        listOf("audio/*", "audio/mpeg", "audio/mp4", "audio/flac", "audio/ogg", "audio/x-wav")
            .flatMap { mime ->
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mime
                    ), PackageManager.MATCH_DEFAULT_ONLY
                )
            }
            .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    fun component(info: ResolveInfo): ComponentName =
        ComponentName(info.activityInfo.packageName, info.activityInfo.name)
}
