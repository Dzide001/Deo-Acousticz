package com.droidacoustic.pro.persistence

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.ClfData
import com.droidacoustic.pro.scene.ClfParser
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

class UnsupportedProjectVersion(message: String) : IllegalArgumentException(message)

data class SpeakerAssetRef(val hash: String?, val version: Int = 1)
data class AssetProblem(val speakerId: String, val reason: String)
data class SpeakerAsset(val reference: SpeakerAssetRef, val payload: String)
data class OpenedProject(
    val sceneJson: String,
    val references: Map<String, SpeakerAssetRef>,
    val speakers: Map<String, ClfData>,
    val problems: List<AssetProblem>,
    val assets: Map<String, SpeakerAsset>
)

/** Immutable versions retained for undo; encoding large polar grids only on model changes. */
class SpeakerAssetCache {
    private val current = mutableMapOf<String, Pair<ClfData, SpeakerAsset>>()
    private val versions = mutableMapOf<String, String>()
    fun capture(data: ClfData): SpeakerAsset {
        current[data.speakerId]?.takeIf { it.first === data }?.let { return it.second }
        val asset = ProjectArchive.asset(data)
        current[data.speakerId] = data to asset
        versions[asset.reference.hash!!] = asset.payload
        return asset
    }
    fun seed(data: ClfData, asset: SpeakerAsset) {
        current[data.speakerId] = data to asset
        versions[asset.reference.hash!!] = asset.payload
    }
    fun resolve(hash: String): String? = versions[hash]
    fun snapshot(): Map<String, String> = versions.toMap()
}

/** Portable scene with a manifest of exact decoded-speaker revisions. No filesystem or Android APIs. */
object ProjectArchive {
    const val VERSION = 31
    const val MAX_PROJECT_CHARS = 128 * 1024 * 1024
    private val hashPattern = Regex("[0-9a-f]{64}")
    fun asset(data: ClfData): SpeakerAsset {
        val payload = ClfParser.toJson(data)
        return SpeakerAsset(SpeakerAssetRef(("speaker-data-v1\n" + payload).encodeUtf8().sha256().hex()), payload)
    }

    fun requireRecoveryMatch(reference: SpeakerAssetRef?, data: ClfData) {
        if (reference?.hash != null) require(reference == asset(data).reference) {
            "Imported speaker data differs from the revision required by this project"
        }
    }

    fun encode(sceneJson: String, refs: Map<String, SpeakerAssetRef>, payload: (String) -> String?, embed: Boolean): String {
        val root = Json.parseToJsonElement(sceneJson).jsonObject.toMutableMap()
        root["version"] = JsonPrimitive(VERSION)
        root["speakerAssetManifest"] = buildJsonObject {
            refs.toList().sortedBy { it.first }.forEach { (id, ref) ->
                putJsonObject(id) {
                    put("version", ref.version)
                    ref.hash?.let { put("sha256", it) }
                }
            }
        }
        root.remove("clfRegistry")
        if (embed) root["clfRegistry"] = buildJsonObject {
            refs.forEach { (id, ref) -> ref.hash?.let(payload)?.let { put(id, Json.parseToJsonElement(it)) } }
        }
        return JsonObject(root).toString()
    }

    fun open(json: String, resolve: (String) -> String? = { null }): OpenedProject {
        val root = validateScene(json)
        val embedded = root["clfRegistry"]?.jsonObject.orEmpty()
        val manifest = root["speakerAssetManifest"]?.jsonObject
        val refs = mutableMapOf<String, SpeakerAssetRef>()
        val data = mutableMapOf<String, ClfData>()
        val problems = mutableListOf<AssetProblem>()
        val assets = mutableMapOf<String, SpeakerAsset>()
        val required = (root["speakers"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val s = e.jsonObject
            if (s["requiresMeasuredData"]?.jsonPrimitive?.booleanOrNull == true)
                s["presetId"]?.jsonPrimitive?.content else null
        }
        val ids = (manifest?.keys.orEmpty() + embedded.keys + required).toSet()
        for (id in ids) {
            val entry = manifest?.get(id)
            val ref = if (entry != null) runCatching {
                val r = entry.jsonObject
                SpeakerAssetRef(r["sha256"]?.jsonPrimitive?.content, r.getValue("version").jsonPrimitive.int)
            }.getOrNull() else null
            refs[id] = ref ?: SpeakerAssetRef(null)
            if (entry != null && (ref == null || ref.version != 1 || ref.hash?.matches(hashPattern) == false)) {
                problems += AssetProblem(id, "unsupported or invalid asset reference")
                continue
            }
            // In a manifested project, an absent digest cannot be repaired by model name alone.
            if (manifest != null && ref?.hash == null) {
                problems += AssetProblem(id, "missing exact asset reference")
                continue
            }
            val candidates = listOfNotNull(embedded[id]?.toString(), ref?.hash?.let { runCatching { resolve(it) }.getOrNull() })
            var recovered: ClfData? = null
            for (candidate in candidates) {
                val decoded = ClfParser.parseJson(candidate) ?: continue
                if (decoded.speakerId != id) continue
                val actual = asset(decoded)
                if (ref?.hash != null && ref.hash != actual.reference.hash) continue
                refs[id] = actual.reference
                assets[id] = actual
                recovered = decoded
                break
            }
            if (recovered == null) problems += AssetProblem(id,
                if (candidates.isEmpty()) "missing speaker asset" else "corrupt or mismatched speaker asset")
            else data[id] = recovered
        }
        // The scene adapter never needs to parse the large embedded polar data again.
        val scene = JsonObject(root.filterKeys { it != "clfRegistry" }).toString()
        return OpenedProject(scene, refs.toMap(), data.toMap(), problems, assets.toMap())
    }

    /** Preflight before any scene mutation. Invalid speaker assets are recoverable; invalid scenes are not. */
    fun validateScene(json: String): JsonObject {
        require(json.length <= MAX_PROJECT_CHARS) { "Project exceeds 128 MiB" }
        val root = Json.parseToJsonElement(json).jsonObject
        require(root["schema"]?.jsonPrimitive?.content?.let { it == "droidacoustic.scene" } != false) { "Not a scene project" }
        val version = root["version"]?.jsonPrimitive?.int ?: 1
        if (version > VERSION) throw UnsupportedProjectVersion("Project requires a newer app (scene version $version)")
        require(version >= 1) { "Invalid scene version $version" }
        root["comparisonSourceSpectrum"]?.let {com.droidacoustic.pro.acoustics.BroadbandSourceSpectrum.parse(it.jsonObject).fractions(com.droidacoustic.pro.acoustics.OctaveWeighting.bands)}
        com.droidacoustic.pro.acoustics.BroadbandDrive.fromScene(root)
        root["comparisonWorkspace"]?.let { com.droidacoustic.pro.acoustics.ComparisonWorkspace.parse(it.toString()) }
        val objectArrays = setOf("speakers", "speakerLibrary", "speakerSources", "speakerModelPackages", "dsp", "audience", "audienceAreas", "blocks", "vertices", "holes", "exclusions", "elevationPoints", "breaklines")
        val objects = setOf("listener", "venueGeometry", "roomMaterials", "eqBands")
        val numbers = setOf("x", "y", "z", "speakerId", "sourceId", "heightM", "earHeightM", "sensitivity", "gainDb", "delayMs", "selectedBandHz", "temperatureC", "humidityPct", "signalLevelDbu", "signalBandwidthOct", "arrayElements", "arraySpacingM", "arrayAimDeg", "panDeg", "arraySteerDeg", "arrayEdgeTaperDb", "offsetM")
        fun check(element: JsonElement, depth: Int = 0) {
            require(depth < 32) { "Project nesting exceeds limit" }
            when (element) {
                is JsonArray -> element.forEach { check(it, depth + 1) }
                is JsonObject -> element.forEach { (key, value) ->
                    if (key in objectArrays) require(value is JsonArray && value.all { it is JsonObject }) { "Invalid $key array" }
                    if (key in objects) require(value is JsonObject) { "Invalid $key object" }
                    if (key in numbers && !(key == "sourceId" && value == JsonNull))
                        require(value is JsonPrimitive && !value.isString && value.doubleOrNull?.toFloat()?.isFinite() == true) { "Invalid numeric $key" }
                    check(value, depth + 1)
                }
                is JsonPrimitive -> if (!element.isString && element != JsonNull && element.booleanOrNull == null)
                    require(element.doubleOrNull?.toFloat()?.isFinite() == true) { "Non-finite project value" }
            }
        }
        // Check root field types as well without inspecting potentially corrupt asset payloads.
        check(JsonObject(root.filterKeys { it !in setOf("clfRegistry", "speakerAssetManifest") }))
        for (name in listOf("speakers", "dsp", "audience", "audienceAreas", "speakerSources")) {
            val idKey = if (name == "dsp") "speakerId" else "id"
            val ids = root[name]?.jsonArray.orEmpty().mapNotNull { it.jsonObject[idKey]?.jsonPrimitive?.int }
            require(ids.distinct().size == ids.size) { "Duplicate $name identifiers" }
        }
        root["dsp"]?.jsonArray.orEmpty().forEach {row ->
            row.jsonObject["filters"]?.let {com.droidacoustic.pro.acoustics.DspFilterChain.fromJsonString(it.toString())}
        }
        root["venueGeometry"]?.jsonObject?.let {VenueBoundaryJson.validate(it,version>=17,version>=18)}
        root["audienceAreas"]?.jsonArray.orEmpty().forEach { element ->
            val area=element.jsonObject
            fun ring(obj: JsonObject): List<PlanPoint> = obj.getValue("vertices").jsonArray.map {
                val point=it.jsonObject
                PlanPoint(point.getValue("x").jsonPrimitive.double,point.getValue("z").jsonPrimitive.double)
            }
            fun rings(key: String) = area[key]?.jsonArray.orEmpty().map { ring(it.jsonObject) }
            val holes=rings("holes"); val exclusions=rings("exclusions")
            val offsets=area["vertexOffsetsM"]?.let { value ->
                require(value is JsonArray) { "Invalid vertex elevation array" }
                value.map { require(it is JsonPrimitive && !it.isString) { "Invalid vertex elevation" }; it.float }
            }.orEmpty()
            val elevationPoints=area["elevationPoints"]?.jsonArray.orEmpty().map { value -> val p=value.jsonObject
                AudienceElevationPoint(p.getValue("x").jsonPrimitive.double,p.getValue("z").jsonPrimitive.double,
                    p.getValue("offsetM").jsonPrimitive.float) }
            val breaklines=area["breaklines"]?.jsonArray.orEmpty().map { value ->
                fun ref(key: String): AudienceVertexRef {
                    val endpoint=value.jsonObject.getValue(key).jsonObject
                    val index=endpoint.getValue("index").jsonPrimitive
                    require(!index.isString && index.intOrNull!=null) { "Invalid breakline index" }
                    val kind=endpoint.getValue("kind").jsonPrimitive
                    require(kind.isString) { "Invalid breakline endpoint kind" }
                    return AudienceVertexRef(AudienceVertexKind.valueOf(kind.content),index.int)
                }
                AudienceBreakline(ref("start"),ref("end"))
            }
            val kind=area["geometryType"]?.jsonPrimitive?.content
            require(kind==null || kind=="AUTHORED" || kind=="IMPORTED_MESH") { "Unknown audience geometry type" }
            require(kind!="IMPORTED_MESH" || area["audienceMesh"] is JsonObject) { "Missing imported audience mesh" }
            require(kind!="AUTHORED" || area["audienceMesh"]==null) { "Conflicting audience geometry type" }
            val mesh=area["audienceMesh"]?.let { AudienceMeshJson.decode(it.toString()) }
            if (mesh!=null || holes.isNotEmpty() || exclusions.isNotEmpty() || offsets.isNotEmpty() || elevationPoints.isNotEmpty() || breaklines.isNotEmpty())
                AudienceSurface(0,"Audience","AUDIENCE_SEATED",if (mesh!=null) emptyList() else ring(area),holes=holes,exclusions=exclusions,
                    vertexOffsetsM=offsets,elevationPoints=elevationPoints,breaklines=breaklines,importedMesh=mesh).validateGeometry()
        }
        for (name in listOf("clfRegistry", "speakerAssetManifest")) require(root[name] == null || root[name] is JsonObject) { "Invalid $name" }
        return root
    }
}
