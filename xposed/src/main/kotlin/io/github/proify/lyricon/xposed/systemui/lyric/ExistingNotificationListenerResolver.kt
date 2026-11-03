package io.github.proify.lyricon.xposed.systemui.lyric

/** Resolve the listener already owned by SystemUI, including when registration was missed. */
internal object ExistingNotificationListenerResolver {
    fun <T : Any> resolve(application: Any?, listenerType: Class<T>, diagnostic: (String) -> Unit = {}): T? {
        if (application == null) return null
        val initializer = field(application, "mInitializer") ?: field(application, "mSystemUIInitializer")
        val component = field(application, "mSysUIComponent")
            ?: call(application, "getSysUIComponent")
            ?: initializer?.let { call(it, "getSysUIComponent") ?: field(it, "mSysUIComponent") }
            ?: run { diagnostic("listener component missing application=${application.javaClass.name}"); return null }
        diagnostic("listener component class=${component.javaClass.name}")
        val candidates = sequenceOf(
            { call(component, "getNotificationListener") },
            { field(component, "notificationListenerProvider")?.let { call(it, "get") } },
            { field(component, "mNotificationListener")?.let { if (listenerType.isInstance(it)) it else call(it, "get") } }
        )
        return candidates.mapNotNull { it() }.firstOrNull(listenerType::isInstance)?.let(listenerType::cast)
    }

    private fun field(instance: Any, name: String): Any? = hierarchy(instance).mapNotNull { type ->
        runCatching { type.getDeclaredField(name).apply { isAccessible = true }.get(instance) }.getOrNull()
    }.firstOrNull()

    private fun call(instance: Any, name: String): Any? = hierarchy(instance).mapNotNull { type ->
        runCatching { type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance) }.getOrNull()
    }.firstOrNull()

    private fun hierarchy(instance: Any): Sequence<Class<*>> =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
}
