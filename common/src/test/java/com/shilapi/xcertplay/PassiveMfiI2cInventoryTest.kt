package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveMfiI2cInventoryTest {
    @Test
    fun inspectsOnlyApprovedDirectoriesAndNeverReadsDeviceNodes() {
        val files = FakeFiles()
        val report = PassiveMfiI2cInventory(files).scan()

        assertTrue(report.contains("path=/dev/i2c-0 exists=yes type=character-device canRead=no canWrite=no"))
        assertTrue(report.contains("bus=2 path=/sys/class/i2c-dev/i2c-2"))
        assertTrue(report.contains("registeredAddress=0x10"))
        assertTrue(report.contains("registeredAddress=0x3a"))
        assertTrue(report.contains("address-is-mfi-evidence=false"))
        assertTrue(report.contains("name=generic"))
        assertTrue(report.contains("authentication-related-name=apple"))

        assertEquals(
            setOf("/dev", "/sys/class/i2c-dev", "/sys/bus/i2c/devices"),
            files.listedDirectories.toSet(),
        )
        assertTrue(files.deviceNodeReadAttempts.isEmpty())
        assertTrue(files.operations.all { operation ->
            operation.startsWith("list:") || operation.startsWith("stat:") ||
                operation.startsWith("readlink:") || operation.startsWith("canonical:") ||
                operation.startsWith("readtext:")
        })
        assertFalse(files.operations.any { it.contains("ioctl", ignoreCase = true) })
        assertFalse(files.operations.any { it.contains("I2cTransport") || it.contains("MfiDeviceScanner") })
    }

    @Test
    fun reportsDirectoryPermissionErrorsInsteadOfClaimingEmptyInventory() {
        val files = FakeFiles(failListing = "/sys/bus/i2c/devices")

        val report = PassiveMfiI2cInventory(files).scan()

        assertTrue(report.contains("ERROR listing /sys/bus/i2c/devices: SecurityException: permission denied"))
    }

    @Test
    fun refusesToReadMetadataWhoseResolvedPathEscapesSysfs() {
        val files = FakeFiles(outsidePath = "/sys/bus/i2c/devices/2-003a/name")

        val report = PassiveMfiI2cInventory(files).scan()

        assertTrue(report.contains("ERROR /sys/bus/i2c/devices/2-003a/name skipped: resolved outside /sys"))
        assertFalse(files.operations.contains("readtext:/dev/private"))
    }

    private class FakeFiles(
        private val failListing: String? = null,
        private val outsidePath: String? = null,
    ) : PassiveI2cInventoryFiles {
        val operations = mutableListOf<String>()
        val listedDirectories = mutableListOf<String>()
        val deviceNodeReadAttempts = mutableListOf<String>()

        override fun list(path: String): List<String> {
            operations += "list:$path"
            listedDirectories += path
            if (path == failListing) throw SecurityException("permission denied")
            return when (path) {
                "/dev" -> listOf("/dev/i2c-0", "/dev/i2c-10", "/dev/ttyUSB0")
                "/sys/class/i2c-dev" -> listOf("/sys/class/i2c-dev/i2c-2")
                "/sys/bus/i2c/devices" -> listOf(
                    "/sys/bus/i2c/devices/i2c-2",
                    "/sys/bus/i2c/devices/2-0010",
                    "/sys/bus/i2c/devices/2-003a",
                    "/sys/bus/i2c/devices/unrelated",
                )
                else -> error("Unapproved directory listing: $path")
            }
        }

        override fun stat(path: String): PassiveMfiI2cInventory.NodeStat {
            operations += "stat:$path"
            val type = when (path) {
                "/dev/i2c-0" -> "character-device"
                "/dev/i2c-10" -> "symlink"
                else -> if (path.substringAfterLast('/') in setOf("name", "modalias", "uevent")) {
                    "regular-file"
                } else "directory"
            }
            return PassiveMfiI2cInventory.NodeStat(
                exists = true,
                type = type,
                mode = if (type == "character-device") "0600" else "0755",
                uid = 0,
                gid = 0,
                size = 0,
                canRead = type != "character-device",
                canWrite = false,
            )
        }

        override fun readLink(path: String): PassiveMfiI2cInventory.TextRead {
            operations += "readlink:$path"
            return if (path == "/sys/bus/i2c/devices/2-003a/uevent") {
                PassiveMfiI2cInventory.TextRead(null)
            } else PassiveMfiI2cInventory.TextRead(null)
        }

        override fun canonicalPath(path: String): PassiveMfiI2cInventory.TextRead {
            operations += "canonical:$path"
            if (path == outsidePath) return PassiveMfiI2cInventory.TextRead("/dev/private")
            if (!path.startsWith("/sys/")) error("Canonicalized path outside sysfs: $path")
            return PassiveMfiI2cInventory.TextRead(path)
        }

        override fun readText(path: String, maximumBytes: Int): PassiveMfiI2cInventory.TextRead {
            operations += "readtext:$path"
            if (path.startsWith("/dev/")) {
                deviceNodeReadAttempts += path
                error("Device nodes must never be read")
            }
            assertTrue(maximumBytes <= 8 * 1024)
            val value = when (path) {
                "/sys/bus/i2c/devices/2-0010/name" -> "generic\n"
                "/sys/bus/i2c/devices/2-003a/name" -> "AppleAuth candidate\n"
                "/sys/bus/i2c/devices/2-0010/modalias" -> "i2c:generic\n"
                "/sys/bus/i2c/devices/2-0010/uevent" -> "MODALIAS=i2c:generic\n"
                else -> ""
            }
            return PassiveMfiI2cInventory.TextRead(value)
        }
    }
}
