package com.droidacoustic.pro.persistence

import com.droidacoustic.pro.acoustics.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProjectPersistenceTest {
    @Test fun unassignedReferenceSourceSurvivesCheckpointRecovery() {
        val references=com.droidacoustic.pro.alignment.AlignmentReferences.empty().add(
            com.droidacoustic.pro.alignment.AlignmentReceiver("Unassigned",com.droidacoustic.pro.geometry.BoundaryPoint(2.0,8.0,-4.0)),null)
        val scene="""{"version":30,"speakers":[],"alignmentReferences":${references.toJsonString()}}"""
        val store=ProjectStore(MemoryStorage())
        store.save("recovery",scene)
        val reopened=store.load("recovery")!!
        assertFalse(reopened.usedBackup)
        val root=Json.parseToJsonElement(reopened.project.sceneJson).jsonObject
        assertEquals(references.toJsonString(),root.getValue("alignmentReferences").toString())
        assertFailsWith<IllegalArgumentException> {ProjectArchive.validateScene("""{"speakers":[{"x":null}]}""")}
        assertFailsWith<IllegalArgumentException> {ProjectArchive.validateScene("""{"sourceId":"invalid"}""")}
    }
    private fun speaker(level: Float = 91f) = ClfData("fixture", "Test", "Box",
        listOf(ClfPolarPattern(1000, listOf(0f), listOf(-90f, 90f), arrayOf(floatArrayOf(0f, -6f)))),
        mapOf(1000 to level), MeasurementGrid.OCTAVE,
        ReferenceCalibration(2.0, DriveUnit.VOLTS_RMS, 2.83, "Fixture calibration"))
    private fun scene(x: Int = 1) = """{"schema":"droidacoustic.scene","version":8,"speakers":[{"id":0,"x":$x,"presetId":"fixture","requiresMeasuredData":true}]}"""
    private fun portable(x: Int = 1, data: ClfData = speaker()): String {
        val a = ProjectArchive.asset(data)
        return ProjectArchive.encode(scene(x), mapOf("fixture" to a.reference), { a.payload }, true)
    }
    private class MemoryStorage : ProjectStorage {
        val files = mutableMapOf<String, String>()
        var failKey: String? = null
        var writes = 0
        override fun read(key: String) = files[key]
        override fun writeAtomic(key: String, contents: String) {
            if (key == failKey) error("Disk full")
            files[key] = contents
            writes++
        }
    }
    @Test fun portableProjectRestoresIdenticalDataWithoutExternalState() {
        val opened = ProjectArchive.open(portable())
        assertEquals(speaker(), opened.speakers["fixture"])
        assertTrue(opened.problems.isEmpty())
        assertEquals(ProjectArchive.asset(speaker()).reference, opened.references["fixture"])
    }
    @Test fun hashesAreStableAcrossJsonObjectOrderAndCalibrationIsCovered() {
        val a = ProjectArchive.asset(speaker())
        val json = Json.parseToJsonElement(a.payload).jsonObject
        val reversed = JsonObject(json.entries.reversed().associate { it.toPair() })
        assertEquals(a.reference, ProjectArchive.asset(ClfParser.parseJson(reversed.toString())!!).reference)
        assertNotEquals(a.reference, ProjectArchive.asset(speaker().copy(calibration = ReferenceCalibration())).reference)
        assertNotEquals(a.reference, ProjectArchive.asset(speaker(92f)).reference)
    }
    @Test fun absentAssetsRemainPinnedAndUnavailableOnResave() {
        val a = ProjectArchive.asset(speaker())
        val compact = ProjectArchive.encode(scene(), mapOf("fixture" to a.reference), { null }, false)
        val opened = ProjectArchive.open(compact)
        assertTrue(opened.speakers.isEmpty())
        assertEquals("missing speaker asset", opened.problems.single().reason)
        val resaved = ProjectArchive.encode(opened.sceneJson, opened.references, { null }, true)
        assertEquals(a.reference, ProjectArchive.open(resaved).references["fixture"])
    }
    @Test fun wrongRevisionAndIdentityNeverResolveByModelName() {
        val a = ProjectArchive.asset(speaker())
        val compact = ProjectArchive.encode(scene(), mapOf("fixture" to a.reference), { null }, false)
        for (data in listOf(speaker(105f), speaker().copy(speakerId = "other"))) {
            val opened = ProjectArchive.open(compact) { ClfParser.toJson(data) }
            assertTrue(opened.speakers.isEmpty())
            assertTrue(opened.problems.single().reason.contains("mismatched"))
        }
    }
    @Test fun corruptEmbeddedDataCanRecoverOnlyFromExactExternalAsset() {
        val a = ProjectArchive.asset(speaker())
        val root = Json.parseToJsonElement(portable()).jsonObject.toMutableMap()
        root["clfRegistry"] = buildJsonObject { putJsonObject("fixture") { put("broken", true) } }
        val broken = JsonObject(root).toString()
        assertTrue(ProjectArchive.open(broken).speakers.isEmpty())
        assertEquals(speaker(), ProjectArchive.open(broken) { a.payload }.speakers["fixture"])
    }
    @Test fun legacyEmbeddedDataMigratesAndUnknownVersionsFailBeforeApply() {
        val legacy = """{"version":7,"speakers":[{"id":0,"presetId":"fixture"}],"clfRegistry":{"fixture":${ClfParser.toJson(speaker())}}}"""
        val opened = ProjectArchive.open(legacy)
        assertEquals(speaker(), opened.speakers["fixture"])
        assertNotNull(opened.references["fixture"]?.hash)
        assertFails { ProjectArchive.open("""{"version":999,"speakers":[]}""") }
        assertFails { ProjectArchive.open("""{"version":8,"speakers":"broken"}""") }
        assertFails { ProjectArchive.open("""{"version":8,"speakers":[{"id":0,"x":"oops"}]}""") }
        assertFails { ProjectArchive.open("""{"schema":"droidacoustic.venue","version":1}""") }
    }
    @Test fun freshStoreReopensCompactSnapshotAndDeduplicatesAssets() {
        val storage = MemoryStorage()
        ProjectStore(storage).save("recovery", portable())
        val writes = storage.writes
        ProjectStore(storage).save("recovery", portable(2))
        assertEquals(writes + 2, storage.writes) // backup + current, no duplicate asset
        val reopened = ProjectStore(storage).load("recovery")!!
        assertEquals(speaker(), reopened.project.speakers["fixture"])
        assertFalse(reopened.usedBackup)
        assertFalse(storage.files.getValue("recovery.current").contains("patterns"))
    }
    @Test fun interruptedSnapshotWritePreservesPreviousSceneAndData() {
        val storage = MemoryStorage()
        val store = ProjectStore(storage)
        store.save("recovery", portable())
        storage.failKey = "recovery.current"
        assertFails { store.save("recovery", portable(2, speaker(99f))) }
        val reopened = ProjectStore(storage).load("recovery")!!
        assertEquals(speaker(), reopened.project.speakers["fixture"])
        assertTrue(reopened.project.sceneJson.contains("\"x\":1"))
    }
    @Test fun assetWriteFailureCannotPublishSnapshot() {
        val storage = MemoryStorage()
        val store = ProjectStore(storage)
        store.save("recovery", portable())
        storage.failKey = "assets/${ProjectArchive.asset(speaker(99f)).reference.hash}.json"
        assertFails { store.save("recovery", portable(2, speaker(99f))) }
        assertEquals(speaker(), store.load("recovery")!!.project.speakers["fixture"])
    }
    @Test fun corruptPrimaryFallsBackWithoutOverwritingLastValidBackup() {
        val storage = MemoryStorage()
        val store = ProjectStore(storage)
        store.save("recovery", portable())
        store.save("recovery", portable(2))
        storage.files["recovery.current"] = "truncated"
        assertTrue(store.load("recovery")!!.usedBackup)
        val backup = storage.files["recovery.previous"]
        store.save("recovery", portable(3))
        assertEquals(backup, storage.files["recovery.previous"])
        storage.files["recovery.current"] = "truncated"
        storage.files["recovery.previous"] = "also truncated"
        assertFails { store.load("recovery") }
    }
    @Test fun missingAssetKeepsLatestSceneRatherThanRollingBackGeometry() {
        val storage = MemoryStorage()
        val store = ProjectStore(storage)
        store.save("recovery", portable())
        store.save("recovery", portable(2))
        storage.files.keys.filter { it.startsWith("assets/") }.forEach { storage.files.remove(it) }
        val restored = store.load("recovery")!!
        assertFalse(restored.usedBackup)
        assertTrue(restored.project.sceneJson.contains("\"x\":2"))
        assertTrue(restored.project.speakers.isEmpty())
        assertTrue(restored.project.problems.isNotEmpty())
    }
    @Test fun incompletePrimaryDoesNotReplaceCompleteBackupDuringSave() {
        val storage = MemoryStorage()
        val store = ProjectStore(storage)
        store.save("recovery", portable())
        store.save("recovery", portable(2, speaker(99f)))
        val backup = storage.files["recovery.previous"]
        val missing = ProjectArchive.asset(speaker(99f)).reference
        storage.files.remove("assets/${missing.hash}.json")
        val latest = ProjectArchive.encode(scene(3), mapOf("fixture" to missing), { null }, false)
        store.save("recovery", latest)
        assertEquals(backup, storage.files["recovery.previous"])
        assertTrue(store.load("recovery")!!.project.sceneJson.contains("\"x\":3"))
        storage.files["recovery.current"] = "broken"
        assertEquals(speaker(), store.load("recovery")!!.project.speakers["fixture"])
    }
    @Test fun migrationKeysIncludeOlderSchemaSlotsInNewestFirstOrder() {
        assertEquals("scene_recovery_latest_v${ProjectArchive.VERSION}", ProjectStore.legacyKeys("recovery").first())
        assertTrue("scene_recovery_latest_v8" in ProjectStore.legacyKeys("recovery"))
        assertTrue("scene_recovery_latest_v7" in ProjectStore.legacyKeys("recovery"))
        assertTrue("scene_snapshot_A_v1" in ProjectStore.legacyKeys("project_A"))
        assertTrue(ProjectStore.legacyKeys("unknown").isEmpty())
    }
    @Test fun futureAssetFormatRemainsUnavailableAndIsPreservedOnResave() {
        val ref = ProjectArchive.asset(speaker()).reference.copy(version = 2)
        val json = ProjectArchive.encode(scene(), mapOf("fixture" to ref), { ClfParser.toJson(speaker()) }, true)
        val opened = ProjectArchive.open(json)
        assertTrue(opened.speakers.isEmpty())
        assertEquals(ref, opened.references["fixture"])
        assertTrue(opened.problems.single().reason.contains("unsupported"))
        assertEquals(ref, ProjectArchive.open(ProjectArchive.encode(opened.sceneJson,
            opened.references, { null }, true)).references["fixture"])
    }

    @Test fun newerCheckpointIsNotSilentlyRolledBackToOlderBackup() {
        val storage = MemoryStorage()
        val store = ProjectStore(storage)
        store.save("recovery", portable())
        store.save("recovery", portable(2))
        storage.files["recovery.current"] = storage.files.getValue("recovery.current").replace("\"format\":1", "\"format\":2")
        val before = storage.files.toMap()
        assertFailsWith<UnsupportedProjectVersion> { store.load("recovery") }
        assertEquals(before, storage.files)
    }

}
