/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.style

import android.content.SharedPreferences
import io.github.proify.lyricon.common.util.safe

abstract class AbstractStyle {
    fun load(sp: SharedPreferences) {
        onLoad(sp.safe())
    }

    fun write(editor: SharedPreferences.Editor) {
        onWrite(editor)
    }

    protected abstract fun onLoad(preferences: SharedPreferences)
    protected abstract fun onWrite(editor: SharedPreferences.Editor)
}