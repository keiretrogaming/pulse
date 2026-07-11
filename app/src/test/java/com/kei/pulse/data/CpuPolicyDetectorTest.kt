package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuPolicyDetectorTest {

    @Test
    fun `parses compact sysfs cpu list ranges`() {
        val detector = CpuPolicyDetector(
            privilegedReader = FakePrivilegedSysfsReader(emptyMap()),
        )

        assertEquals(listOf(0, 1, 2, 3, 6, 8, 9), detector.parseCpuIds("0-3,6,8-9"))
    }

    @Test
    fun `detects and sorts policies from sysfs`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf(
                "/sys/devices/system/cpu/cpufreq/policy6",
                "/sys/devices/system/cpu/cpufreq/policy0",
            ),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "3532800",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "998400",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "998400 1785600 2227200 2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/affected_cpus" to "0 1 2 3 4 5",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "3072000",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq" to "4320000",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq" to "1075200",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies" to "1075200 1958400 2246400 3072000",
                "/sys/devices/system/cpu/cpufreq/policy6/affected_cpus" to "6 7",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies()

        assertEquals(listOf(0, 6), result.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result.first().cpuIds)
        assertEquals(listOf(6, 7), result.last().cpuIds)
        assertEquals(listOf(998400, 1785600, 2227200, 2745600), result.first().supportedFrequencies)
        assertEquals(3072000, result.last().selectableMaxFreq)
        assertEquals(4320000, result.last().observedMaxFreq)
    }

    @Test
    fun `keeps hidden max values out of selectable frequencies`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy3"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq" to "2841600",
                "/sys/devices/system/cpu/cpufreq/policy3/cpuinfo_max_freq" to "2956800",
                "/sys/devices/system/cpu/cpufreq/policy3/scaling_min_freq" to "710400",
                "/sys/devices/system/cpu/cpufreq/policy3/scaling_available_frequencies" to "710400 940800 1209600 1420800 1785600 2150400",
                "/sys/devices/system/cpu/cpufreq/policy3/stats/time_in_state" to "710400 1\n2956800 5\n",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies().single()

        assertEquals(
            listOf(710400, 940800, 1209600, 1420800, 1785600, 2150400),
            result.supportedFrequencies,
        )
        assertEquals(2_150_400, result.selectableMaxFreq)
        assertEquals(2_956_800, result.observedMaxFreq)
        assertEquals(2_841_600, result.currentMaxFreq)
    }

    @Test
    fun `falls back when scaling_available_frequencies is missing`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy2"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy2/scaling_max_freq" to "2100000",
                "/sys/devices/system/cpu/cpufreq/policy2/cpuinfo_max_freq" to "2500000",
                "/sys/devices/system/cpu/cpufreq/policy2/scaling_min_freq" to "800000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies().single()

        assertEquals(listOf(800000, 2100000, 2500000), result.supportedFrequencies)
    }

    @Test
    fun `keeps policy when scaling max is missing but other fields exist`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "614400",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "614400 902400 1209600 1593600 2016000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies().single()

        assertEquals(0, result.id)
        assertEquals(2_016_000, result.currentMaxFreq)
        assertEquals(2_016_000, result.selectableMaxFreq)
        assertEquals(listOf(614400, 902400, 1209600, 1593600, 2016000), result.supportedFrequencies)
    }

    @Test
    fun `uses privileged reader for protected scaling max and min values`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = emptyMap(),
        )
        val privilegedReader = FakePrivilegedSysfsReader(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "307200 614400 902400 1209600 1459200 2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "1459200",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies().single()

        assertEquals(2_016_000, result.currentMaxFreq)
        assertEquals(1_459_200, result.minFreq)
        assertEquals(listOf(307200, 614400, 902400, 1209600, 1459200, 2016000), result.supportedFrequencies)
    }

    @Test
    fun `keeps lower supported steps even when scaling min is raised`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy6"),
            files = emptyMap(),
        )
        val privilegedReader = FakePrivilegedSysfsReader(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies" to "1017600 1209600 1401600 1689600 1958400 2246400 2438400",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "2246400",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq" to "1958400",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq" to "4320000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies().single()

        assertEquals(1_958_400, result.minFreq)
        assertEquals(
            listOf(1017600, 1209600, 1401600, 1689600, 1958400, 2246400, 2438400),
            result.supportedFrequencies,
        )
        assertEquals(2_438_400, result.selectableMaxFreq)
        assertEquals(4_320_000, result.observedMaxFreq)
    }

    @Test
    fun `does not fall back to normal reads when privileged reader is configured`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy0"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "307200 614400 902400 1209600 1459200 2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2016000",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq" to "1459200",
            ),
        )
        val privilegedReader = FakePrivilegedSysfsReader(emptyMap())

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = privilegedReader,
        )

        val result = detector.detectPolicies()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores malformed policy directories`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf(
                "/sys/devices/system/cpu/cpufreq/policyX",
                "/sys/devices/system/cpu/cpufreq/policy1",
            ),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq" to "1500000",
                "/sys/devices/system/cpu/cpufreq/policy1/cpuinfo_max_freq" to "2000000",
                "/sys/devices/system/cpu/cpufreq/policy1/scaling_min_freq" to "500000",
            ),
        )

        val detector = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = FakePrivilegedSysfsReader(fileSystem.files),
        )

        val result = detector.detectPolicies()

        assertEquals(1, result.size)
        assertTrue(result.all { it.id == 1 })
    }

    private class FakeSysfsFileSystem(
        private val directories: List<String>,
        val files: Map<String, String>,
    ) : SysfsFileSystem {
        override fun listPolicyDirectories(root: String): List<String> = directories
    }

    private class FakePrivilegedSysfsReader(
        private val files: Map<String, String>,
    ) : PrivilegedSysfsReader {
        override fun readText(path: String): String? = files[path]
    }
}
