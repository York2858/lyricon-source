package io.github.proify.lyricon.xposed.systemui.lyric

import org.junit.Assert.*
import org.junit.Test

class ExistingNotificationListenerResolverTest {
    private class Listener
    private class Provider(private val listener: Any) {
        fun get(): Any = listener
    }
    private class Component(listener: Any) {
        private val notificationListenerProvider = Provider(listener)
    }
    private open class ApplicationBase(component: Any?) {
        var mSysUIComponent: Any? = component
    }
    private class OemApplication(component: Any?) : ApplicationBase(component)
    private class Initializer(private val component: Any) {
        fun getSysUIComponent(): Any = component
    }
    private class ApplicationWithInitializer(component: Any) {
        private val mInitializer = Initializer(component)
    }
    private class ApplicationWithGetter(private val component: Any) {
        fun getSysUIComponent(): Any = component
    }
    private class ComponentWithGetter(private val listener: Listener) {
        fun getNotificationListener(): Listener = listener
    }
    private class ComponentWithLazy(listener: Any) {
        private val mNotificationListener = Provider(listener)
    }

    @Test fun alreadyRegisteredOemListenerIsRecoveredWithoutAnyCallback() {
        val listener = Listener()
        val app = OemApplication(Component(listener))
        assertSame(listener, ExistingNotificationListenerResolver.resolve(app, Listener::class.java))
        assertSame(listener, ExistingNotificationListenerResolver.resolve(app, Listener::class.java))
    }

    @Test fun componentCanBeReachedThroughInitializer() {
        val listener = Listener()
        assertSame(listener, ExistingNotificationListenerResolver.resolve(
            ApplicationWithInitializer(Component(listener)), Listener::class.java))
    }

    @Test fun olderComponentGettersAreSupported() {
        val listener = Listener()
        assertSame(listener, ExistingNotificationListenerResolver.resolve(
            ApplicationWithGetter(ComponentWithGetter(listener)), Listener::class.java))
    }

    @Test fun legacyLazyListenerIsSupported() {
        val listener = Listener()
        assertSame(listener, ExistingNotificationListenerResolver.resolve(
            OemApplication(ComponentWithLazy(listener)), Listener::class.java))
    }

    @Test fun startupBeforeComponentExistsDoesNotPermanentlyCacheFailure() {
        val listener = Listener()
        val app = OemApplication(null)
        assertNull(ExistingNotificationListenerResolver.resolve(app, Listener::class.java))
        app.mSysUIComponent = Component(listener)
        assertSame(listener, ExistingNotificationListenerResolver.resolve(app, Listener::class.java))
    }

    @Test fun missingApplicationOrProviderDoesNotCreateAnotherService() {
        assertNull(ExistingNotificationListenerResolver.resolve(null, Listener::class.java))
        assertNull(ExistingNotificationListenerResolver.resolve(OemApplication(Any()), Listener::class.java))
        assertNull(ExistingNotificationListenerResolver.resolve(OemApplication(Component(Any())), Listener::class.java))
    }
}
