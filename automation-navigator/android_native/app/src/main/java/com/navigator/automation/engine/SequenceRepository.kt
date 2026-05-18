package com.navigator.automation.engine

import android.content.Context
import org.json.JSONObject
import java.io.File

object SequenceRepository {

    private fun dir(context: Context): File =
        File(context.filesDir, "sequences").also { it.mkdirs() }

    fun list(context: Context): List<String> =
        dir(context).listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun load(context: Context, name: String): Sequence? = runCatching {
        val text = File(dir(context), "$name.json").readText()
        Sequence.fromJson(JSONObject(text))
    }.getOrNull()

    fun save(context: Context, sequence: Sequence) {
        File(dir(context), "${sequence.name}.json")
            .writeText(sequence.toJson().toString(2))
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "$name.json").delete()
    }

    fun rename(context: Context, oldName: String, newName: String) {
        val old = File(dir(context), "$oldName.json")
        if (old.exists()) {
            val seq = load(context, oldName) ?: return
            save(context, seq.copy(name = newName))
            old.delete()
        }
    }
}
