package com.nomily.app.llm

import android.content.Context
import android.util.Log
import com.nomily.app.core.json.JsonParser
import com.nomily.app.core.json.writeJson
import com.nomily.app.core.llm.BUILTIN_SUMMARIZE_TEMPLATES
import com.nomily.app.core.llm.SummarizeTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistence of summary templates.
 *
 * Stored at `filesDir/templates.json`. **When reading back, fill in missing built‑in templates based on (category, name)**:
 * After a user has saved a template, newly added built‑in templates will become visible; the existing file **preserves the user’s copy** (future changes to built‑in wording won’t overwrite the user’s learned muscle memory).
 */
class TemplateStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    private val _templates = MutableStateFlow(load())
    val templates: StateFlow<List<SummarizeTemplate>> = _templates.asStateFlow()

    fun update(transform: (List<SummarizeTemplate>) -> List<SummarizeTemplate>) {
        _templates.value = transform(_templates.value)
        save()
    }

    fun resetToDefaults() {
        _templates.value = BUILTIN_SUMMARIZE_TEMPLATES
        save()
    }

    private fun load(): List<SummarizeTemplate> {
        val saved = runCatching {
            if (!file.isFile) return@runCatching null
            val root = JsonParser(file.readText()).parse() as? List<*> ?: return@runCatching null
            root.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                SummarizeTemplate(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    category = m["category"] as? String ?: return@mapNotNull null,
                    name = m["name"] as? String ?: return@mapNotNull null,
                    prompt = m["prompt"] as? String ?: return@mapNotNull null,
                    isBuiltIn = m["isBuiltIn"] as? Boolean ?: false,
                )
            }.takeIf { it.isNotEmpty() }
        }.onFailure { Log.w(TAG, "Template read failed, falling back to built-in: ${it.message}") }.getOrNull()
            ?: return BUILTIN_SUMMARIZE_TEMPLATES

        val seen = saved.map { it.category to it.name }.toSet()
        val missing = BUILTIN_SUMMARIZE_TEMPLATES.filter { (it.category to it.name) !in seen }
        return if (missing.isEmpty()) saved else (saved + missing).also { persist(it) }
    }

    private fun save() = persist(_templates.value)

    private fun persist(list: List<SummarizeTemplate>) {
        runCatching {
            val json = writeJson(
                list.map {
                    sortedMapOf(
                        "id" to it.id,
                        "category" to it.category,
                        "name" to it.name,
                        "prompt" to it.prompt,
                        "isBuiltIn" to it.isBuiltIn,
                    )
                },
            )
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Template persistence failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "TemplateStore"
        const val FILE_NAME = "templates.json"
    }
}
