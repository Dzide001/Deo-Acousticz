package com.droidacoustic.pro.persistence

import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

/** Platform adapter must replace one key atomically or throw, preserving its old contents. */
interface ProjectStorage {
    fun read(key: String): String?
    fun writeAtomic(key: String, contents: String)
}

data class RecoveredProject(val project: OpenedProject, val usedBackup: Boolean)

/** Callers serialize save/load transactions. Assets commit before snapshots; previous remains recoverable. */
class ProjectStore(private val storage: ProjectStorage) {
    fun save(slot: String, scene: String, resolve: (String) -> String? = { null }) {
        require(slot.matches(Regex("[a-zA-Z0-9_-]+")))
        val opened = ProjectArchive.open(scene, resolve)
        val assets = opened.speakers.mapValues { ProjectArchive.asset(it.value) }
        for (asset in assets.values) {
            val key = "assets/${asset.reference.hash}.json"
            if (storage.read(key) != asset.payload) storage.writeAtomic(key, asset.payload)
        }
        val compact = ProjectArchive.encode(opened.sceneJson, opened.references,
            { hash -> assets.values.firstOrNull { it.reference.hash == hash }?.payload }, embed = false)
        // Never promote a damaged primary over the last valid backup.
        val current = storage.read("$slot.current")
        val priorComplete = current?.let { record -> runCatching {
            ProjectArchive.open(unpack(record)) { storage.read("assets/$it.json") }.problems.isEmpty()
        }.getOrDefault(false) } ?: false
        if (current != null && priorComplete) storage.writeAtomic("$slot.previous", current)
        storage.writeAtomic("$slot.current", pack(compact))
    }

    fun load(slot: String): RecoveredProject? {
        require(slot.matches(Regex("[a-zA-Z0-9_-]+")))
        var found = false
        for ((suffix, backup) in listOf("current" to false, "previous" to true)) {
            val read = runCatching { storage.read("$slot.$suffix") }
            if (read.isFailure) { found = true; continue }
            val record = read.getOrNull() ?: continue
            found = true
            val decoded = runCatching { unpack(record) }
            // An older app must not roll back and then autosave over a newer-format project.
            decoded.exceptionOrNull()?.let { if (it is UnsupportedProjectVersion) throw it }
            val scene = decoded.getOrNull() ?: continue
            return RecoveredProject(ProjectArchive.open(scene) { storage.read("assets/$it.json") }, backup)
        }
        check(!found) { "Both project checkpoints are damaged. Existing files have been preserved." }
        return null
    }

    fun portable(recovered: RecoveredProject): String {
        val assets = recovered.project.speakers.values.map(ProjectArchive::asset).associateBy { it.reference.hash }
        return ProjectArchive.encode(recovered.project.sceneJson, recovered.project.references,
            { assets[it]?.payload }, embed = true)
    }

    companion object {
        /** Migration lookup only; old preference values are preserved after file-store adoption. */
        fun legacyKeys(slot: String): List<String> {
            val prefix = when (slot) {
                "recovery" -> "scene_recovery_latest_v"
                "project_A" -> "scene_snapshot_A_v"
                else -> return emptyList()
            }
            return (ProjectArchive.VERSION downTo 1).map { "$prefix$it" }
        }
    }

    private fun pack(scene: String): String = buildJsonObject {
        put("format", 1)
        put("sha256", scene.encodeUtf8().sha256().hex())
        put("scene", scene)
    }.toString()
    private fun unpack(record: String): String {
        val obj = Json.parseToJsonElement(record).jsonObject
        val format = obj["format"]?.jsonPrimitive?.int
        if (format != null && format > 1) throw UnsupportedProjectVersion("Checkpoint requires a newer app (format $format)")
        require(format == 1)
        val scene = obj.getValue("scene").jsonPrimitive.content
        require(obj["sha256"]?.jsonPrimitive?.content == scene.encodeUtf8().sha256().hex())
        ProjectArchive.validateScene(scene)
        return scene
    }
}
