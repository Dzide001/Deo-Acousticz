package com.droidacoustic.pro.scene

import com.droidacoustic.pro.persistence.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only filesystem mechanics are platform-specific; format/recovery policy is shared. */
class LocalProjectStore(directory: File) {
    private val storage = object : ProjectStorage {
        private val root = directory
        override fun read(key: String): String? {
            val file = File(root, key)
            if (!file.exists()) return null
            require(file.length() <= 2L * ProjectArchive.MAX_PROJECT_CHARS) { "Project file too large" }
            return file.readText(Charsets.UTF_8)
        }
        override fun writeAtomic(key: String, contents: String) {
            val file = File(root, key)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create project directory" }
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { output ->
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private val store = ProjectStore(storage)
    fun save(slot: String, json: String, resolve: (String) -> String? = { null }) = synchronized(lock) {
        store.save(slot, json, resolve)
    }
    fun loadPrepared(slot: String): RecoveredProject? = synchronized(lock) { store.load(slot) }
    fun load(slot: String): Pair<String, Boolean>? = synchronized(lock) {
        store.load(slot)?.let { store.portable(it) to it.usedBackup }
    }
    companion object { private val lock = Any() }
}
