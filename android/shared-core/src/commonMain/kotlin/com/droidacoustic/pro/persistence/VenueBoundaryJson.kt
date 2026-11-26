package com.droidacoustic.pro.persistence

import com.droidacoustic.pro.geometry.*
import com.droidacoustic.pro.scene.*
import kotlinx.serialization.json.*

/** Shared strict preflight. Old projects migrate to opaque boundaries, never inferred reflectors. */
object VenueBoundaryJson {
    fun role(value: JsonElement?,required: Boolean=false): AcousticBoundaryRole {
        if (value==null) {
            require(!required) {"Missing acoustic boundary role"}
            return AcousticBoundaryRole.OCCLUDING
        }
        require(value is JsonPrimitive && value.isString) {"Invalid acoustic boundary role"}
        return AcousticBoundaryRole.valueOf(value.content)
    }
    fun validate(venue: JsonObject,requireRoles: Boolean=false,requireMaterials: Boolean=false) {
        fun number(o: JsonObject,key: String,default: Float): Float {
            val value=o[key] ?: return default
            require(value is JsonPrimitive && !value.isString && value.floatOrNull?.isFinite()==true) {"Invalid $key"}
            return value.float
        }
        fun material(value: JsonElement?) {
            if(value==null) {require(!requireMaterials) {"Missing boundary material"}; return}
            BoundaryMaterial.fromJson(value)
        }
        material(venue["stageBoundaryMaterial"])
        val stageRole=role(venue["stageBoundaryRole"],requireRoles)
        VenueBoundaryGeometry.stage(VenueGeometry(stageCenterX=number(venue,"stageCenterX",0f),
            stageCenterZ=number(venue,"stageCenterZ",-10f),stageWidthM=number(venue,"stageWidthM",10f),
            stageDepthM=number(venue,"stageDepthM",4f),stageHeightM=number(venue,"stageHeightM",1f),
            stageSlopeDeg=number(venue,"stageSlopeDeg",0f),stageBoundaryRole=stageRole))
        val blocks=venue["blocks"] ?: return
        require(blocks is JsonArray && blocks.size<=4096) {"Invalid or excessive venue blocks"}
        val ids=mutableSetOf<Int>()
        blocks.forEachIndexed {index,value ->
            require(value is JsonObject) {"Invalid venue block"}
            material(value["boundaryMaterial"])
            val id=value["id"]?.let {require(it is JsonPrimitive && !it.isString && it.intOrNull!=null) {"Invalid block ID"}; it.int} ?: index
            require(ids.add(id)) {"Duplicate block ID"}
            VenueBoundaryGeometry.block(VenueBlock(id,centerX=number(value,"centerX",0f),centerZ=number(value,"centerZ",0f),
                widthM=number(value,"widthM",6f),depthM=number(value,"depthM",3f),heightM=number(value,"heightM",1f),
                blockHeightM=number(value,"blockHeightM",1f),slopeDeg=number(value,"slopeDeg",0f),rotationDeg=number(value,"rotationDeg",0f),
                boundaryRole=role(value["boundaryRole"],requireRoles)))
        }
    }
    fun validatePackage(json: String) {
        val root=Json.parseToJsonElement(json).jsonObject
        val version=root["version"]?.jsonPrimitive?.int ?: 1
        require(version in 1..7) {"Unsupported venue version"}
        require(root["schema"]?.jsonPrimitive?.content?.let {it=="droidacoustic.venue"}!=false) {"Not a venue package"}
        validate((root["venueGeometry"] ?: root).jsonObject,version>=2,version>=3)
    }
}
