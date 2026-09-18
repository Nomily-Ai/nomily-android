package com.nomily.app.llm

import android.content.Context
import com.nomily.app.R
import com.nomily.app.core.llm.SummarizeTemplate

const val PROMPT_MAX_CHARACTERS = 4_000

/**
 * Localization of **category and template names** for built‑in templates (the prompt body remains in English; only the user‑facing names are translated).
 *
 * Custom templates use the user‑provided name and are not looked up in the table.
 */
fun localizedTemplateCategory(ctx: Context, category: String, isBuiltIn: Boolean): String {
    if (!isBuiltIn) return category
    val res = when (category) {
        "Meeting Notes" -> R.string.template_cat_meeting_notes
        "Action Items" -> R.string.template_cat_action_items
        "General Summary" -> R.string.template_cat_general_summary
        "Professional" -> R.string.template_cat_professional
        "Academic" -> R.string.template_cat_academic
        "Creative" -> R.string.template_cat_creative
        else -> return category
    }
    return ctx.getString(res)
}

fun localizedTemplatePrompt(ctx: Context, template: SummarizeTemplate): String {
    if (!template.isBuiltIn) return template.prompt
    val res = when (template.name) {
        "Formal Meeting Minutes" -> R.string.template_prompt_formal_minutes
        "Structured Minutes" -> R.string.template_prompt_structured_minutes
        "Executive Brief" -> R.string.template_prompt_executive_brief
        "Key Takeaways" -> R.string.template_prompt_key_takeaways
        "Task Extraction" -> R.string.template_prompt_task_extraction
        "Decision Log" -> R.string.template_prompt_decision_log
        "Concise Summary" -> R.string.template_prompt_concise_summary
        "Detailed Notes" -> R.string.template_prompt_detailed_notes
        "Q&A Format" -> R.string.template_prompt_qa_format
        "Client Meeting Recap" -> R.string.template_prompt_client_meeting_recap
        "1-on-1 Summary" -> R.string.template_prompt_one_on_one_summary
        "Status Update" -> R.string.template_prompt_status_update
        "Lecture Notes" -> R.string.template_prompt_lecture_notes
        "Research Discussion" -> R.string.template_prompt_research_discussion
        "Brainstorm Synthesis" -> R.string.template_prompt_brainstorm_synthesis
        "Interview Summary" -> R.string.template_prompt_interview_summary
        else -> return template.prompt
    }
    return ctx.getString(res)
}

fun localizedTemplateName(ctx: Context, name: String, isBuiltIn: Boolean): String {
    if (!isBuiltIn) return name
    val res = when (name) {
        "Formal Meeting Minutes" -> R.string.template_formal_minutes
        "Structured Minutes" -> R.string.template_structured_minutes
        "Executive Brief" -> R.string.template_executive_brief
        "Key Takeaways" -> R.string.template_key_takeaways
        "Task Extraction" -> R.string.template_task_extraction
        "Decision Log" -> R.string.template_decision_log
        "Concise Summary" -> R.string.template_concise_summary
        "Detailed Notes" -> R.string.template_detailed_notes
        "Q&A Format" -> R.string.template_qa_format
        "Client Meeting Recap" -> R.string.template_client_meeting_recap
        "1-on-1 Summary" -> R.string.template_one_on_one_summary
        "Status Update" -> R.string.template_status_update
        "Lecture Notes" -> R.string.template_lecture_notes
        "Research Discussion" -> R.string.template_research_discussion
        "Brainstorm Synthesis" -> R.string.template_brainstorm_synthesis
        "Interview Summary" -> R.string.template_interview_summary
        else -> return name
    }
    return ctx.getString(res)
}
