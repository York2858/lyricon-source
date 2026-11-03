package io.github.proify.lyricon.app.bridge

object ImmersiveAppearanceContract {
    const val PREFERENCES = "immersive_background"
    const val CUSTOM_FILE = "immersive-background.png"
    const val CUSTOM_VERSION = "custom_background_version"
    val KEYS = setOf("background_mode", "flow_enabled", "apple_flow_blur", "apple_flow_speed", "beautiful_speed",
        "beautiful_blur", "beautiful_brightness", "custom_background_uri", "custom_opacity", "custom_dim")
}
