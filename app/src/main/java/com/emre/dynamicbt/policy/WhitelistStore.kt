package com.emre.dynamicbt.policy

import android.content.Context
import java.io.File

object WhitelistStore {
    private const val WHITELIST_FILE = "whitelist.conf"
    private const val CONFIG_FILE = "config.ini"

    val DEFAULT_PACKAGES: List<String> = listOf(
        "com.maxmpz.audioplayer",          // Poweramp
        "com.maxmpz.audioplayer.lite",     // Poweramp Lite / Unlocker
        "com.aspiro.tidal",                // Tidal
        "com.apple.android.music",         // Apple Music
        "com.frybits.symfonium",           // Symfonium
        "com.extreamsd.usbaudioplayerpro", // USB Audio Player PRO
        "org.foobar2000.foobar2000",       // foobar2000
    )

    fun parseWhitelist(content: String): Set<String> {
        val result = linkedSetOf<String>()
        for (rawLine in content.lineSequence()) {
            val noComment = rawLine.substringBefore('#').trim()
            if (noComment.isNotEmpty()) {
                result.add(noComment)
            }
        }
        return result
    }

    fun formatWhitelist(packages: Collection<String>): String {
        val header = "# Dynamic BT whitelist (one package name per line)\n"
        val cleaned = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .distinct()
        return if (cleaned.isEmpty()) header else header + cleaned.joinToString("\n") + "\n"
    }

    fun parseConfig(content: String): PolicyConfig {
        var cfg = PolicyConfig()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            cfg = when (key) {
                "enabled" -> cfg.copy(
                    enabled = value == "1" ||
                        value.equals("true", ignoreCase = true) ||
                        value.equals("yes", ignoreCase = true),
                )
                "default_rate" -> cfg.copy(
                    defaultRateHz = value.toIntOrNull()?.takeIf { it > 0 } ?: cfg.defaultRateHz,
                )
                "default_bits" -> cfg.copy(
                    defaultBits = value.toIntOrNull()?.takeIf { it > 0 } ?: cfg.defaultBits,
                )
                "debounce_ms" -> cfg.copy(
                    debounceMs = value.toLongOrNull()?.coerceAtLeast(0L) ?: cfg.debounceMs,
                )
                "apply_cooldown_ms" -> cfg.copy(
                    applyCooldownMs = value.toLongOrNull()?.coerceAtLeast(0L) ?: cfg.applyCooldownMs,
                )
                else -> cfg
            }
        }
        return cfg
    }

    fun formatConfig(config: PolicyConfig): String = buildString {
        append("# Dynamic BT config\n")
        append("enabled=").append(if (config.enabled) 1 else 0).append('\n')
        append("default_rate=").append(config.defaultRateHz).append('\n')
        append("default_bits=").append(config.defaultBits).append('\n')
        append("debounce_ms=").append(config.debounceMs).append('\n')
        append("apply_cooldown_ms=").append(config.applyCooldownMs).append('\n')
    }

    private fun storageDir(context: Context): File {
        val dpContext = context.createDeviceProtectedStorageContext()
        return dpContext.filesDir.apply { mkdirs() }
    }

    fun loadWhitelist(context: Context): Set<String> {
        val file = File(storageDir(context), WHITELIST_FILE)
        if (!file.exists()) {
            val defaults = DEFAULT_PACKAGES.toSet()
            saveWhitelist(context, defaults)
            return defaults
        }
        return parseWhitelist(file.readText(Charsets.UTF_8))
    }

    fun saveWhitelist(context: Context, packages: Collection<String>): Boolean {
        val target = File(storageDir(context), WHITELIST_FILE)
        return writeAtomic(target, formatWhitelist(packages))
    }

    fun loadConfig(context: Context): PolicyConfig {
        val file = File(storageDir(context), CONFIG_FILE)
        if (!file.exists()) {
            val defaults = PolicyConfig()
            saveConfig(context, defaults)
            return defaults
        }
        return parseConfig(file.readText(Charsets.UTF_8))
    }

    fun saveConfig(context: Context, config: PolicyConfig): Boolean {
        val target = File(storageDir(context), CONFIG_FILE)
        return writeAtomic(target, formatConfig(config))
    }

    private fun writeAtomic(target: File, content: String): Boolean {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            tmp.writeText(content, Charsets.UTF_8)
            if (tmp.renameTo(target)) {
                true
            } else {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }
}
