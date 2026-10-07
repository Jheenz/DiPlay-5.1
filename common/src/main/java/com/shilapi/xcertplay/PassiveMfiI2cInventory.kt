package com.shilapi.xcertplay

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale

internal class PassiveMfiI2cInventory(
    private val files: PassiveI2cInventoryFiles = AndroidPassiveI2cInventoryFiles,
) {
    data class NodeStat(
        val exists: Boolean?,
        val type: String?,
        val mode: String?,
        val uid: Int?,
        val gid: Int?,
        val size: Long?,
        val canRead: Boolean?,
        val canWrite: Boolean?,
        val error: String? = null,
    )

    data class TextRead(val text: String?, val error: String? = null)

    fun scan(): String = buildString {
        appendLine("Phase 3C.3C — Passive MFi/I2C inventory")
        appendLine("Read-only filesystem/sysfs inspection only; no device nodes opened; no ioctl or I2C transactions.")
        appendLine("Generic I2C addresses are not identified as MFi without independent identifying metadata.")

        appendLine()
        appendLine("[/dev/i2c-* nodes]")
        try {
            val nodes = files.list(DEV_ROOT)
                .filter { NODE_NAME.matches(File(it).name) }
                .sorted()
            if (nodes.isEmpty()) appendLine("No matching entries visible.")
            for (path in nodes) {
                val stat = files.stat(path)
                appendLine(
                    "path=$path exists=${stat.exists.reportValue()} type=${stat.type ?: "unknown"} " +
                        "canRead=${stat.canRead.reportValue()} canWrite=${stat.canWrite.reportValue()} " +
                        "mode=${stat.mode ?: "unknown"} uid=${stat.uid ?: "unknown"} " +
                        "gid=${stat.gid ?: "unknown"} size=${stat.size ?: "unknown"}",
                )
                stat.error?.let { appendLine("ERROR $path stat: $it") }
                appendNameKeywordFlags(path)
                if (stat.type == "symlink") {
                    val target = files.readLink(path)
                    target.text?.let {
                        appendLine("  symlink-target=$it")
                        appendKeywordFlags(it)
                    }
                    target.error?.let { appendLine("ERROR $path symlink: $it") }
                }
            }
        } catch (error: Exception) {
            appendLine("ERROR listing $DEV_ROOT: ${error.describe()}")
        }

        appendLine()
        appendLine("[/sys/class/i2c-dev buses]")
        try {
            val buses = files.list(CLASS_ROOT)
                .filter { BUS_NAME.matches(File(it).name) }
                .sorted()
            if (buses.isEmpty()) appendLine("No matching buses visible.")
            for (path in buses) {
                appendLine("bus=${File(path).name.removePrefix("i2c-")} path=$path")
                appendSysfsEntry(path, listOf("name", "uevent", "device/name", "device/uevent"))
            }
        } catch (error: Exception) {
            appendLine("ERROR listing $CLASS_ROOT: ${error.describe()}")
        }

        appendLine()
        appendLine("[/sys/bus/i2c/devices clients and buses]")
        try {
            val devices = files.list(BUS_ROOT)
                .filter { BUS_NAME.matches(File(it).name) || CLIENT_NAME.matches(File(it).name) }
                .sorted()
            if (devices.isEmpty()) appendLine("No matching bus/client entries visible.")
            for (path in devices) {
                val name = File(path).name
                val address = CLIENT_NAME.matchEntire(name)?.groupValues?.get(2)
                val bus = when {
                    address != null -> CLIENT_NAME.matchEntire(name)?.groupValues?.get(1)
                    BUS_NAME.matches(name) -> name.removePrefix("i2c-")
                    else -> null
                }
                appendLine(
                    "entry=$name bus=${bus ?: "unknown"} " +
                        "registeredAddress=${address?.let { "0x${it.trimStart('0').ifEmpty { "0" }}" } ?: "none"} path=$path",
                )
                appendSysfsEntry(path, listOf("name", "modalias", "uevent"))
                appendLink(path, "driver")
                if (address != null) appendLine("address-is-mfi-evidence=false")
            }
        } catch (error: Exception) {
            appendLine("ERROR listing $BUS_ROOT: ${error.describe()}")
        }

        appendLine()
        appendLine("[Authentication-related names]")
        appendLine("Only names and bounded ordinary text attributes from the I2C entries above are considered.")
        appendLine("Keywords: ${KEYWORDS.joinToString(", ")}")
    }

    private fun StringBuilder.appendSysfsEntry(path: String, attributes: List<String>) {
        val stat = files.stat(path)
        appendLine(
            "entry-metadata path=$path exists=${stat.exists.reportValue()} " +
                "type=${stat.type ?: "unknown"} mode=${stat.mode ?: "unknown"}",
        )
        stat.error?.let { appendLine("ERROR $path stat: $it") }
        appendNameKeywordFlags(path)
        appendPathLink(path)
        for (attribute in attributes) {
            val attributePath = "$path/$attribute"
            val resolved = files.canonicalPath(attributePath)
            val resolvedPath = resolved.text
            if (resolvedPath == null) {
                resolved.error?.let { appendLine("ERROR $attributePath path: $it") }
                continue
            }
            if (!isInsideSysfs(resolvedPath)) {
                appendLine("ERROR $attributePath skipped: resolved outside /sys")
                continue
            }
            val attributeStat = files.stat(resolvedPath)
            if (attributeStat.exists != true || attributeStat.type != "regular-file" ||
                attributeStat.canRead != true
            ) {
                appendLine(
                    "ERROR $attributePath skipped: not a confirmed readable regular file " +
                        "(exists=${attributeStat.exists.reportValue()}, type=${attributeStat.type ?: "unknown"}, " +
                        "canRead=${attributeStat.canRead.reportValue()})",
                )
                attributeStat.error?.let { appendLine("ERROR $resolvedPath stat: $it") }
                continue
            }
            val value = files.readText(resolvedPath, MAX_ATTRIBUTE_BYTES)
            if (value.text != null) {
                val text = value.text.trim().replace('\n', ';').replace('\r', ' ')
                if (text.isNotEmpty()) {
                    appendLine("  $attribute=$text")
                    val matches = matchingKeywords("$attribute $text")
                    if (matches.isNotEmpty()) appendLine("  authentication-related-name=${matches.joinToString(",")}")
                }
            }
            value.error?.let { appendLine("ERROR $attributePath read: $it") }
        }
    }

    private fun StringBuilder.appendLink(parent: String, child: String) {
        val path = "$parent/$child"
        val link = files.readLink(path)
        val target = link.text
        if (target != null) {
            val resolved = if (target.startsWith("/")) target else File(parent, target).path
            appendLine("  $child-link=$target")
            appendKeywordFlags(target)
            val canonical = files.canonicalPath(resolved)
            if (canonical.text != null && isInsideSysfs(canonical.text)) {
                appendLine("  $child-resolved=${canonical.text}")
            } else if (canonical.error != null) {
                appendLine("ERROR $path resolve: ${canonical.error}")
            } else {
                appendLine("ERROR $path skipped: resolved outside /sys")
            }
        }
        link.error?.let { appendLine("ERROR $path symlink: $it") }
    }

    private fun StringBuilder.appendPathLink(path: String) {
        val result = files.readLink(path)
        result.text?.let { target ->
            appendLine("  entry-link=$target")
            appendKeywordFlags(target)
            val parent = File(path).parent ?: "/sys"
            val resolved = if (target.startsWith("/")) target else File(parent, target).path
            val canonical = files.canonicalPath(resolved)
            if (canonical.text != null && isInsideSysfs(canonical.text)) {
                appendLine("  entry-resolved=${canonical.text}")
            } else if (canonical.error != null) {
                appendLine("ERROR $path resolve: ${canonical.error}")
            } else {
                appendLine("ERROR $path skipped: resolved outside /sys")
            }
        }
        result.error?.let { appendLine("ERROR $path symlink: $it") }
    }

    private fun StringBuilder.appendNameKeywordFlags(path: String) {
        appendKeywordFlags(File(path).name)
    }

    private fun StringBuilder.appendKeywordFlags(value: String) {
        val matches = matchingKeywords(value)
        if (matches.isNotEmpty()) appendLine("  authentication-related-name=${matches.joinToString(",")}")
    }

    private fun Boolean?.reportValue(): String = when (this) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }

    private fun Exception.describe(): String =
        "${javaClass.simpleName}${message?.let { ": $it" }.orEmpty()}"

    companion object {
        private const val DEV_ROOT = "/dev"
        private const val CLASS_ROOT = "/sys/class/i2c-dev"
        private const val BUS_ROOT = "/sys/bus/i2c/devices"
        private const val MAX_ATTRIBUTE_BYTES = 8 * 1024
        private val NODE_NAME = Regex("i2c-[0-9]+")
        private val BUS_NAME = Regex("i2c-[0-9]+")
        private val CLIENT_NAME = Regex("([0-9]+)-([0-9a-fA-F]{4})")
        private val KEYWORDS = listOf("mfi", "apple", "auth", "authentication", "coprocessor", "iap", "ipod")

        fun matchingKeywords(value: String): List<String> {
            val normalized = value.lowercase(Locale.ROOT)
            return KEYWORDS.filter(normalized::contains)
        }

        private fun isInsideSysfs(path: String): Boolean =
            path == "/sys" || path.startsWith("/sys/")
    }
}

internal interface PassiveI2cInventoryFiles {
    fun list(path: String): List<String>
    fun stat(path: String): PassiveMfiI2cInventory.NodeStat
    fun readLink(path: String): PassiveMfiI2cInventory.TextRead
    fun canonicalPath(path: String): PassiveMfiI2cInventory.TextRead
    fun readText(path: String, maximumBytes: Int): PassiveMfiI2cInventory.TextRead
}

internal object AndroidPassiveI2cInventoryFiles : PassiveI2cInventoryFiles {
    override fun list(path: String): List<String> {
        val entries = File(path).listFiles()
            ?: throw IOException("directory is missing or unreadable")
        return entries.map(File::getPath)
    }

    override fun stat(path: String): PassiveMfiI2cInventory.NodeStat {
        return try {
            val stat = Os.lstat(path)
            val readAccess = access(path, OsConstants.R_OK)
            val writeAccess = access(path, OsConstants.W_OK)
            PassiveMfiI2cInventory.NodeStat(
                exists = true,
                type = fileType(stat.st_mode),
                mode = "%04o".format(Locale.ROOT, stat.st_mode and 0x0fff),
                uid = stat.st_uid,
                gid = stat.st_gid,
                size = stat.st_size,
                canRead = readAccess.first,
                canWrite = writeAccess.first,
                error = listOfNotNull(readAccess.second, writeAccess.second).joinToString("; ").ifEmpty { null },
            )
        } catch (error: android.system.ErrnoException) {
            PassiveMfiI2cInventory.NodeStat(
                exists = if (error.errno == OsConstants.ENOENT) false else null,
                type = null,
                mode = null,
                uid = null,
                gid = null,
                size = null,
                canRead = null,
                canWrite = null,
                error = "${error.javaClass.simpleName}: ${error.message}",
            )
        } catch (error: SecurityException) {
            PassiveMfiI2cInventory.NodeStat(
                exists = null,
                type = null,
                mode = null,
                uid = null,
                gid = null,
                size = null,
                canRead = null,
                canWrite = null,
                error = "${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun access(path: String, mode: Int): Pair<Boolean?, String?> = try {
        Os.access(path, mode) to null
    } catch (error: android.system.ErrnoException) {
        null to "${error.javaClass.simpleName}: ${error.message}"
    } catch (error: SecurityException) {
        null to "${error.javaClass.simpleName}: ${error.message}"
    }

    override fun readLink(path: String): PassiveMfiI2cInventory.TextRead = try {
        PassiveMfiI2cInventory.TextRead(Os.readlink(path))
    } catch (error: android.system.ErrnoException) {
        if (error.errno == OsConstants.EINVAL || error.errno == OsConstants.ENOENT) {
            PassiveMfiI2cInventory.TextRead(null)
        } else PassiveMfiI2cInventory.TextRead(null, "${error.javaClass.simpleName}: ${error.message}")
    } catch (error: SecurityException) {
        PassiveMfiI2cInventory.TextRead(null, "${error.javaClass.simpleName}: ${error.message}")
    }

    override fun canonicalPath(path: String): PassiveMfiI2cInventory.TextRead = try {
        PassiveMfiI2cInventory.TextRead(File(path).canonicalPath)
    } catch (error: IOException) {
        PassiveMfiI2cInventory.TextRead(null, "${error.javaClass.simpleName}: ${error.message}")
    } catch (error: SecurityException) {
        PassiveMfiI2cInventory.TextRead(null, "${error.javaClass.simpleName}: ${error.message}")
    }

    override fun readText(path: String, maximumBytes: Int): PassiveMfiI2cInventory.TextRead = try {
        val bytes = FileInputStream(path).use { input ->
            val output = ByteArray(maximumBytes)
            var count = 0
            while (count < output.size) {
                val read = input.read(output, count, output.size - count)
                if (read < 0) break
                count += read
            }
            output.copyOf(count)
        }
        PassiveMfiI2cInventory.TextRead(String(bytes, Charset.forName("UTF-8")))
    } catch (error: IOException) {
        PassiveMfiI2cInventory.TextRead(null, "${error.javaClass.simpleName}: ${error.message}")
    } catch (error: SecurityException) {
        PassiveMfiI2cInventory.TextRead(null, "${error.javaClass.simpleName}: ${error.message}")
    }

    private fun fileType(mode: Int): String = when (mode and OsConstants.S_IFMT) {
        OsConstants.S_IFCHR -> "character-device"
        OsConstants.S_IFBLK -> "block-device"
        OsConstants.S_IFDIR -> "directory"
        OsConstants.S_IFREG -> "regular-file"
        OsConstants.S_IFLNK -> "symlink"
        OsConstants.S_IFSOCK -> "socket"
        OsConstants.S_IFIFO -> "fifo"
        else -> "unknown"
    }
}
