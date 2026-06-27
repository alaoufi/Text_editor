package com.uts.editor.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Crash-safe autosave. Each open editable document gets a draft written
 * atomically to internal storage. On next launch, any drafts found mean the
 * app didn't shut down cleanly, so we offer to restore them.
 */
class RecoveryStore(context: Context) {

    private val dir = File(context.filesDir, "recovery").apply { mkdirs() }

    data class Draft(
        val id: String,
        val displayName: String,
        val uriString: String?,
        val encodingId: String,
        val lineEnding: String,
        val content: String,
    )

    /** Atomically persist a draft (write temp, then rename). */
    fun save(draft: Draft) {
        val meta = JSONObject().apply {
            put("displayName", draft.displayName)
            put("uri", draft.uriString ?: JSONObject.NULL)
            put("encoding", draft.encodingId)
            put("lineEnding", draft.lineEnding)
        }
        writeAtomic(File(dir, "${draft.id}.meta"), meta.toString())
        writeAtomic(File(dir, "${draft.id}.txt"), draft.content)
    }

    fun delete(id: String) {
        File(dir, "$id.meta").delete()
        File(dir, "$id.txt").delete()
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** All drafts currently on disk (recoverable after an unclean exit). */
    fun pending(): List<Draft> {
        val metas = dir.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { metaFile ->
            runCatching {
                val id = metaFile.name.removeSuffix(".meta")
                val contentFile = File(dir, "$id.txt")
                if (!contentFile.exists()) return@runCatching null
                val json = JSONObject(metaFile.readText())
                Draft(
                    id = id,
                    displayName = json.optString("displayName", "untitled.txt"),
                    uriString = if (json.isNull("uri")) null else json.optString("uri"),
                    encodingId = json.optString("encoding", "utf-8"),
                    lineEnding = json.optString("lineEnding", "LF"),
                    content = contentFile.readText(),
                )
            }.getOrNull()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Fallback if rename across same dir somehow fails.
            target.writeText(text)
            tmp.delete()
        }
    }
}
