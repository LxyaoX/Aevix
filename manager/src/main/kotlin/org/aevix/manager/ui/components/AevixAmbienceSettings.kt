package org.aevix.manager.ui.components

import org.aevix.manager.di.ServiceLocator
import org.aevix.ui.ambience.AmbienceSettings

/**
 * Bridges the shared [org.aevix.ui.ambience.AmbientSurface] to Aevix's persisted settings,
 * so the header comes back the size and speed it was left at. LSPatch, which does not persist these,
 * simply lets the surface fall back to its ephemeral default.
 */
object AevixAmbienceSettings : AmbienceSettings {
    private val settings
        get() = ServiceLocator.settings

    override fun scale(key: String) = settings.ambienceScale(key)

    override fun speed(key: String) = settings.ambienceSpeed(key)

    override fun variant(key: String) = settings.ambienceVariant(key)

    override fun setScale(key: String, value: Float) = settings.setAmbienceScale(key, value)

    override fun setSpeed(key: String, value: Float) = settings.setAmbienceSpeed(key, value)

    override fun setVariant(key: String, value: Int) = settings.setAmbienceVariant(key, value)
}
