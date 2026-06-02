package io.github.mayusi.calibratesoc.data.script

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.presets.Preset
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.bootRemindersStore by preferencesDataStore(name = "boot_reminders")

/**
 * Persists a list of preset NAMES the user wants reminders for at
 * boot. Each entry corresponds to a script previously deployed via
 * [AynScriptDeployer.deploy] — the file already exists at
 * /sdcard/CalibrateSoC/<safeName>.sh. The post-boot notification
 * just nudges the user to invoke it via Odin Settings.
 *
 * This is the "persistent without root" path. Truly invisible
 * persistence (script runs automatically) requires root. The best
 * we can do without root is reduce the friction to one tap after
 * each boot.
 */
@Singleton
class BootScriptReminder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun register(preset: Preset) {
        context.bootRemindersStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = current + preset.name
        }
    }

    suspend fun unregister(presetName: String) {
        context.bootRemindersStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) - presetName
        }
    }

    suspend fun all(): Set<String> =
        context.bootRemindersStore.data.first()[KEY] ?: emptySet()

    private companion object {
        val KEY = stringSetPreferencesKey("preset_names")
    }
}
