package io.konektis.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The runtime source of truth for the effective device/settings config, bridging the yaml file and
 * the database.
 *
 * Boot ([resolve]) picks the source and, in [ConfigSource.database] mode, seeds the DB from the yaml
 * file on first use. [update] validates and persists a new config (database mode only) and publishes
 * it on [configFlow].
 *
 * Phase 2 applies persisted changes on the next restart; the live device graph is still the boot
 * snapshot. A later phase subscribes to [configFlow] to rebuild [io.konektis.devices.World] in place.
 */
class ConfigService(
    private val fileConfig: Config,
    private val store: ConfigStore,
    private val json: Json = DEFAULT_JSON,
) {
    val source: ConfigSource get() = fileConfig.configSource

    private val _configFlow = MutableStateFlow(fileConfig)
    val configFlow: StateFlow<Config> = _configFlow.asStateFlow()

    @Volatile
    var version: Int = 0
        private set

    fun current(): Config = _configFlow.value

    /**
     * Resolve and publish the effective config for this boot:
     * - [ConfigSource.file]: the yaml config, unchanged.
     * - [ConfigSource.database]: the stored document (seeded from the yaml config on first use), with
     *   bootstrap fields always taken from the file ([Config.withBootstrapFrom]).
     */
    fun resolve(): Config {
        val resolved = when (source) {
            ConfigSource.file -> fileConfig
            ConfigSource.database -> resolveFromDatabase()
        }
        _configFlow.value = resolved
        return resolved
    }

    private fun resolveFromDatabase(): Config {
        val stored = store.load()
        return if (stored == null) {
            // First use in database mode: seed the DB from the current yaml config so the operator
            // starts from their existing setup rather than an empty config.
            version = store.save(json.encodeToString(fileConfig))
            fileConfig
        } else {
            version = stored.version
            json.decodeFromString<Config>(stored.json).withBootstrapFrom(fileConfig)
        }
    }

    /**
     * Validate and persist a new config, returning the new effective config. Bootstrap fields in the
     * candidate are ignored (always taken from the file).
     *
     * @throws ConfigReadOnlyException in [ConfigSource.file] mode (the file is the source of truth).
     * @throws ConfigValidationException if the candidate is structurally invalid.
     */
    fun update(candidate: Config): Config {
        if (source == ConfigSource.file) throw ConfigReadOnlyException()
        val effective = candidate.withBootstrapFrom(fileConfig).validatedOrThrow()
        version = store.save(json.encodeToString(effective))
        _configFlow.value = effective
        return effective
    }

    companion object {
        val DEFAULT_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

/** Thrown when a config write is attempted while the file is the source of truth (`configSource: file`). */
class ConfigReadOnlyException :
    Exception("Config is file-sourced (configSource: file) and read-only; set configSource: database to edit at runtime")
