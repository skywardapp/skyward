package dev.fritze.skyward.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon

/**
 * §10.2: "one notification channel per phenomenon (user can silence meteor
 * showers but keep eclipses loud) + one 'app diagnostics' channel. Aurora
 * NOWCAST channel defaults to high importance."
 */
object NotificationChannels {
    const val DIAGNOSTICS_CHANNEL_ID = "diagnostics"
    private const val AURORA_NOWCAST_CHANNEL_ID = "aurora_nowcast"

    fun channelIdFor(occurrence: Occurrence): String {
        val isNowcast = occurrence.phenomenon == Phenomenon.AURORA &&
            (occurrence.payload as? AuroraPayload)?.forecastKind == AuroraForecastKind.NOWCAST
        return if (isNowcast) AURORA_NOWCAST_CHANNEL_ID else phenomenonChannelId(occurrence.phenomenon)
    }

    private fun phenomenonChannelId(phenomenon: Phenomenon) = "phenomenon_${phenomenon.name.lowercase()}"

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val phenomenonChannels = Phenomenon.entries.map { phenomenon ->
            NotificationChannel(phenomenonChannelId(phenomenon), phenomenonLabel(phenomenon), NotificationManager.IMPORTANCE_DEFAULT)
        }
        val auroraNowcast = NotificationChannel(AURORA_NOWCAST_CHANNEL_ID, "Aurora — right now", NotificationManager.IMPORTANCE_HIGH)
        val diagnostics = NotificationChannel(DIAGNOSTICS_CHANNEL_ID, "App diagnostics", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannels(phenomenonChannels + auroraNowcast + diagnostics)
    }

    private fun phenomenonLabel(phenomenon: Phenomenon): String = when (phenomenon) {
        Phenomenon.SOLAR_ECLIPSE -> "Solar eclipses"
        Phenomenon.LUNAR_ECLIPSE -> "Lunar eclipses"
        Phenomenon.AURORA -> "Aurora (planning)"
        Phenomenon.METEOR_SHOWER -> "Meteor showers"
        Phenomenon.COMET -> "Comets"
        Phenomenon.MOON_EVENT -> "Supermoons"
        Phenomenon.CONJUNCTION -> "Conjunctions"
        Phenomenon.TERRESTRIAL -> "Earth events"
    }
}
