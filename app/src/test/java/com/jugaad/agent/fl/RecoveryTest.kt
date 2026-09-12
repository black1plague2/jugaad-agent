package com.jugaad.agent.fl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Startup self-healing (v4 plan §4): corrupt JSON is quarantined, valid JSON is left alone, wrong-length weight files are quarantined as stale. */
class RecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun corruptJsonUnderFlIsRenamedAsideAndReportedRepaired() {
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        File(flDir, "node.json").writeText("{ not valid json ][")

        val report = Recovery.repair(filesDir)

        assertTrue(report.repairedFiles.contains("node.json"))
        assertFalse(File(flDir, "node.json").exists())
        assertTrue(flDir.listFiles()!!.any { it.name.startsWith("node.json.corrupt-") })
    }

    @Test
    fun plainTextGarbageInNetworkJsonIsQuarantinedNotAcceptedAsValidJson() {
        // Mirrors the real device scenario: `run-as` writes the literal text "garbage2" into
        // fl/network.json. That single bare word parses "successfully" as a JSON primitive
        // under kotlinx.serialization's lenient JsonElement parsing, so a naive well-formedness
        // check on the parse result alone would wrongly treat it as fine.
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        val networkFile = File(flDir, "network.json")
        networkFile.writeText("garbage2")

        val report = Recovery.repair(filesDir)

        assertTrue(report.repairedFiles.contains("network.json"))
        assertFalse(networkFile.exists())
        assertTrue(flDir.listFiles()!!.any { it.name.startsWith("network.json.corrupt-") })

        // The ServiceLocator loader flow: once the corrupt file is quarantined out of the way,
        // NetworkStateIO.load falls back to (and, once anything saves, recreates) the default.
        val recreated = NetworkStateIO.load(flDir)
        assertEquals(NetworkState.empty().championId, recreated.championId)
    }

    @Test
    fun validJsonUnderFlIsLeftUntouched() {
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        val networkFile = File(flDir, "network.json")
        networkFile.writeText("""{"championId":"base"}""")

        val report = Recovery.repair(filesDir)

        assertTrue(report.repairedFiles.isEmpty())
        assertTrue(networkFile.exists())
        assertEquals("""{"championId":"base"}""", networkFile.readText())
    }

    @Test
    fun corruptJsonUnderAnAssetSubdirIsRepairedToo() {
        val filesDir = tmp.newFolder()
        val assetDir = File(filesDir, "assets/a1").apply { mkdirs() }
        File(assetDir, "calibration.json").writeText("not json at all")
        File(assetDir, "baseline.json").writeText("""{"assetId":"a1"}""") // valid, stays

        val report = Recovery.repair(filesDir)

        assertTrue(report.repairedFiles.contains("calibration.json"))
        assertFalse(File(assetDir, "calibration.json").exists())
        assertTrue(File(assetDir, "baseline.json").exists())
    }

    @Test
    fun weightFileWithWrongLengthIsQuarantinedAsStale() {
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        // No FlVariants weightCount is 1 float (4 bytes).
        File(flDir, "weights_bogus.bin").writeBytes(ByteArray(4))

        val report = Recovery.repair(filesDir)

        assertTrue(report.staleWeights.contains("weights_bogus.bin"))
        assertFalse(File(flDir, "weights_bogus.bin").exists())
        assertTrue(flDir.listFiles()!!.any { it.name == "weights_bogus.bin.stale" })
    }

    @Test
    fun weightFileMatchingAKnownVariantIsLeftAlone() {
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        val centroidBytes = FlVariants.byId("centroid").weightCount * 4
        File(flDir, "weights_centroid.bin").writeBytes(ByteArray(centroidBytes))

        val report = Recovery.repair(filesDir)

        assertTrue(report.staleWeights.isEmpty())
        assertTrue(File(flDir, "weights_centroid.bin").exists())
    }

    @Test
    fun allNaNWeightFileOfCorrectLengthIsQuarantinedAsStale() {
        // Mirrors the real fleet-breaking scenario: FedAvg merged with totalN == 0 before the
        // guard existed, so every float came out NaN but the file length still matches the
        // variant exactly and would otherwise pass the length-only check as healthy.
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        val spec = FlVariants.byId("centroid")
        val nanWeights = FloatArray(spec.weightCount) { Float.NaN }
        File(flDir, "weights_centroid.bin").writeBytes(WeightsCodec.encode(nanWeights))

        val report = Recovery.repair(filesDir)

        assertTrue(report.staleWeights.contains("weights_centroid.bin"))
        assertFalse(File(flDir, "weights_centroid.bin").exists())
        assertTrue(flDir.listFiles()!!.any { it.name == "weights_centroid.bin.stale" })
    }

    @Test
    fun jsonlFilesUnderFlAreNeverTouched() {
        val filesDir = tmp.newFolder()
        val flDir = File(filesDir, "fl").apply { mkdirs() }
        File(flDir, "samples.jsonl").writeText("not json but a jsonl file, left alone")

        val report = Recovery.repair(filesDir)

        assertTrue(report.repairedFiles.isEmpty())
        assertTrue(File(flDir, "samples.jsonl").exists())
    }
}
