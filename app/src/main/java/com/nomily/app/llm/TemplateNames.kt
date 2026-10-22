package com.nomily.app.llm

import android.content.Context
import com.nomily.app.R
import com.nomily.app.core.llm.SummarizeTemplate

const val PROMPT_MAX_CHARACTERS = 4_000

/**
 * Localization of **category names, template names, descriptions and prompts** for built‑in templates.
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
        "Speech" -> R.string.template_cat_speech
        "Call" -> R.string.template_cat_call
        "Interview" -> R.string.template_cat_interview
        "Medical" -> R.string.template_cat_medical
        "Sales" -> R.string.template_cat_sales
        "Consulting" -> R.string.template_cat_consulting
        "Education" -> R.string.template_cat_education
        "Construction" -> R.string.template_cat_construction
        "IT & Engineering" -> R.string.template_cat_it_engineering
        "Legal" -> R.string.template_cat_legal
        "Real Estate" -> R.string.template_cat_real_estate
        "Finance" -> R.string.template_cat_finance
        "Utility" -> R.string.template_cat_utility
        "Workplace" -> R.string.template_cat_workplace
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
        "Detailed Summary" -> R.string.template_prompt_detailed_summary
        "Meeting Report" -> R.string.template_prompt_meeting_report
        "Key Points, Decisions, Tasks, Deadlines, and Actions" -> R.string.template_prompt_key_points_decisions_tasks_deadlines_and_actions
        "Meeting Minutes+" -> R.string.template_prompt_meeting_minutes
        "Lecture Summary" -> R.string.template_prompt_lecture_summary
        "Keynote Speech Summary" -> R.string.template_prompt_keynote_speech_summary
        "Training Overview" -> R.string.template_prompt_training_overview
        "Phone Discussion Notes" -> R.string.template_prompt_phone_discussion_notes
        "Manager/Employee 1:1 Meeting" -> R.string.template_prompt_manager_employee_1_1_meeting
        "Customer Call Summary/Analysis" -> R.string.template_prompt_customer_call_summary_analysis
        "Business Call" -> R.string.template_prompt_business_call
        "Interview Notes" -> R.string.template_prompt_interview_notes
        "Panel Discussion" -> R.string.template_prompt_panel_discussion
        "Case Record" -> R.string.template_prompt_case_record
        "Psychotherapy Session Record" -> R.string.template_prompt_psychotherapy_session_record
        "Medical Consultation" -> R.string.template_prompt_medical_consultation
        "BANT Customer Analysis" -> R.string.template_prompt_bant_customer_analysis
        "Sales Call / Customer Outreach" -> R.string.template_prompt_sales_call_customer_outreach
        "Sales Discovery Call" -> R.string.template_prompt_sales_discovery_call
        "Client Needs" -> R.string.template_prompt_client_needs
        "Discussion with Expert" -> R.string.template_prompt_discussion_with_expert
        "Senior Management Consultant" -> R.string.template_prompt_senior_management_consultant
        "University Lecture" -> R.string.template_prompt_university_lecture
        "University Course Compilation" -> R.string.template_prompt_university_course_compilation
        "Seminar / Lecture: Detailed Summary and Notes" -> R.string.template_prompt_seminar_lecture_detailed_summary_and_notes
        "Acceptance Checklist" -> R.string.template_prompt_acceptance_checklist
        "Engineering Project Meeting Minutes" -> R.string.template_prompt_engineering_project_meeting_minutes
        "Site Inspection" -> R.string.template_prompt_site_inspection
        "Multi-Participant Project Team Meeting" -> R.string.template_prompt_multi_participant_project_team_meeting
        "IT Knowledge Transfer Meeting" -> R.string.template_prompt_it_knowledge_transfer_meeting
        "Troubleshooting Summary" -> R.string.template_prompt_troubleshooting_summary
        "Court Stenographer" -> R.string.template_prompt_court_stenographer
        "Meeting Secretary (Legal)" -> R.string.template_prompt_meeting_secretary_legal
        "Client Consultation" -> R.string.template_prompt_client_consultation
        "Owners Committee General Meeting" -> R.string.template_prompt_owners_committee_general_meeting
        "Business Meeting / Real Estate Project Template" -> R.string.template_prompt_business_meeting_real_estate_project_template
        "Real Estate Property Acquisition" -> R.string.template_prompt_real_estate_property_acquisition
        "Client Review Summary" -> R.string.template_prompt_client_review_summary
        "Investment Consultation" -> R.string.template_prompt_investment_consultation
        "Stock Market Discussion" -> R.string.template_prompt_stock_market_discussion
        "Listed Company Research" -> R.string.template_prompt_listed_company_research
        "From Chaos to Clarity: Tagged Daily Review" -> R.string.template_prompt_from_chaos_to_clarity_tagged_daily_review
        "Daily Log" -> R.string.template_prompt_daily_log
        "Key Data Points" -> R.string.template_prompt_key_data_points
        "Meeting Highlights" -> R.string.template_prompt_meeting_highlights
        "Work Report Summary" -> R.string.template_prompt_work_report_summary
        "Email Thread Summary" -> R.string.template_prompt_email_thread_summary
        "Long Article to Slide Deck Outline" -> R.string.template_prompt_long_article_to_slide_deck_outline
        "Turning Scattered Notes into a To-Do List" -> R.string.template_prompt_turning_scattered_notes_into_a_to_do_list
        else -> return template.prompt
    }
    // An empty string means this language has no translation (the default `values/` entries of the
    // Vobot library are empty): fall back to the template's own English prompt, like iOS on a key miss.
    return ctx.getString(res).ifEmpty { template.prompt }
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
        "Detailed Summary" -> R.string.template_detailed_summary
        "Meeting Report" -> R.string.template_meeting_report
        "Key Points, Decisions, Tasks, Deadlines, and Actions" -> R.string.template_key_points_decisions_tasks_deadlines_and_actions
        "Meeting Minutes+" -> R.string.template_meeting_minutes
        "Lecture Summary" -> R.string.template_lecture_summary
        "Keynote Speech Summary" -> R.string.template_keynote_speech_summary
        "Training Overview" -> R.string.template_training_overview
        "Phone Discussion Notes" -> R.string.template_phone_discussion_notes
        "Manager/Employee 1:1 Meeting" -> R.string.template_manager_employee_1_1_meeting
        "Customer Call Summary/Analysis" -> R.string.template_customer_call_summary_analysis
        "Business Call" -> R.string.template_business_call
        "Interview Notes" -> R.string.template_interview_notes
        "Panel Discussion" -> R.string.template_panel_discussion
        "Case Record" -> R.string.template_case_record
        "Psychotherapy Session Record" -> R.string.template_psychotherapy_session_record
        "Medical Consultation" -> R.string.template_medical_consultation
        "BANT Customer Analysis" -> R.string.template_bant_customer_analysis
        "Sales Call / Customer Outreach" -> R.string.template_sales_call_customer_outreach
        "Sales Discovery Call" -> R.string.template_sales_discovery_call
        "Client Needs" -> R.string.template_client_needs
        "Discussion with Expert" -> R.string.template_discussion_with_expert
        "Senior Management Consultant" -> R.string.template_senior_management_consultant
        "University Lecture" -> R.string.template_university_lecture
        "University Course Compilation" -> R.string.template_university_course_compilation
        "Seminar / Lecture: Detailed Summary and Notes" -> R.string.template_seminar_lecture_detailed_summary_and_notes
        "Acceptance Checklist" -> R.string.template_acceptance_checklist
        "Engineering Project Meeting Minutes" -> R.string.template_engineering_project_meeting_minutes
        "Site Inspection" -> R.string.template_site_inspection
        "Multi-Participant Project Team Meeting" -> R.string.template_multi_participant_project_team_meeting
        "IT Knowledge Transfer Meeting" -> R.string.template_it_knowledge_transfer_meeting
        "Troubleshooting Summary" -> R.string.template_troubleshooting_summary
        "Court Stenographer" -> R.string.template_court_stenographer
        "Meeting Secretary (Legal)" -> R.string.template_meeting_secretary_legal
        "Client Consultation" -> R.string.template_client_consultation
        "Owners Committee General Meeting" -> R.string.template_owners_committee_general_meeting
        "Business Meeting / Real Estate Project Template" -> R.string.template_business_meeting_real_estate_project_template
        "Real Estate Property Acquisition" -> R.string.template_real_estate_property_acquisition
        "Client Review Summary" -> R.string.template_client_review_summary
        "Investment Consultation" -> R.string.template_investment_consultation
        "Stock Market Discussion" -> R.string.template_stock_market_discussion
        "Listed Company Research" -> R.string.template_listed_company_research
        "From Chaos to Clarity: Tagged Daily Review" -> R.string.template_from_chaos_to_clarity_tagged_daily_review
        "Daily Log" -> R.string.template_daily_log
        "Key Data Points" -> R.string.template_key_data_points
        "Meeting Highlights" -> R.string.template_meeting_highlights
        "Work Report Summary" -> R.string.template_work_report_summary
        "Email Thread Summary" -> R.string.template_email_thread_summary
        "Long Article to Slide Deck Outline" -> R.string.template_long_article_to_slide_deck_outline
        "Turning Scattered Notes into a To-Do List" -> R.string.template_turning_scattered_notes_into_a_to_do_list
        else -> return name
    }
    return ctx.getString(res)
}

/** Second line of a picker row: the localized one-line description when the template has one, otherwise the prompt. */
fun localizedTemplateSubtitle(ctx: Context, template: SummarizeTemplate): String {
    val summary = template.summary ?: return localizedTemplatePrompt(ctx, template)
    if (!template.isBuiltIn) return summary
    val res = when (template.name) {
        "Detailed Summary" -> R.string.template_desc_detailed_summary
        "Meeting Report" -> R.string.template_desc_meeting_report
        "Key Points, Decisions, Tasks, Deadlines, and Actions" -> R.string.template_desc_key_points_decisions_tasks_deadlines_and_actions
        "Meeting Minutes+" -> R.string.template_desc_meeting_minutes
        "Lecture Summary" -> R.string.template_desc_lecture_summary
        "Keynote Speech Summary" -> R.string.template_desc_keynote_speech_summary
        "Training Overview" -> R.string.template_desc_training_overview
        "Phone Discussion Notes" -> R.string.template_desc_phone_discussion_notes
        "Manager/Employee 1:1 Meeting" -> R.string.template_desc_manager_employee_1_1_meeting
        "Customer Call Summary/Analysis" -> R.string.template_desc_customer_call_summary_analysis
        "Business Call" -> R.string.template_desc_business_call
        "Interview Notes" -> R.string.template_desc_interview_notes
        "Panel Discussion" -> R.string.template_desc_panel_discussion
        "Case Record" -> R.string.template_desc_case_record
        "Psychotherapy Session Record" -> R.string.template_desc_psychotherapy_session_record
        "Medical Consultation" -> R.string.template_desc_medical_consultation
        "BANT Customer Analysis" -> R.string.template_desc_bant_customer_analysis
        "Sales Call / Customer Outreach" -> R.string.template_desc_sales_call_customer_outreach
        "Sales Discovery Call" -> R.string.template_desc_sales_discovery_call
        "Client Needs" -> R.string.template_desc_client_needs
        "Discussion with Expert" -> R.string.template_desc_discussion_with_expert
        "Senior Management Consultant" -> R.string.template_desc_senior_management_consultant
        "University Lecture" -> R.string.template_desc_university_lecture
        "University Course Compilation" -> R.string.template_desc_university_course_compilation
        "Seminar / Lecture: Detailed Summary and Notes" -> R.string.template_desc_seminar_lecture_detailed_summary_and_notes
        "Acceptance Checklist" -> R.string.template_desc_acceptance_checklist
        "Engineering Project Meeting Minutes" -> R.string.template_desc_engineering_project_meeting_minutes
        "Site Inspection" -> R.string.template_desc_site_inspection
        "Multi-Participant Project Team Meeting" -> R.string.template_desc_multi_participant_project_team_meeting
        "IT Knowledge Transfer Meeting" -> R.string.template_desc_it_knowledge_transfer_meeting
        "Troubleshooting Summary" -> R.string.template_desc_troubleshooting_summary
        "Court Stenographer" -> R.string.template_desc_court_stenographer
        "Meeting Secretary (Legal)" -> R.string.template_desc_meeting_secretary_legal
        "Client Consultation" -> R.string.template_desc_client_consultation
        "Owners Committee General Meeting" -> R.string.template_desc_owners_committee_general_meeting
        "Business Meeting / Real Estate Project Template" -> R.string.template_desc_business_meeting_real_estate_project_template
        "Real Estate Property Acquisition" -> R.string.template_desc_real_estate_property_acquisition
        "Client Review Summary" -> R.string.template_desc_client_review_summary
        "Investment Consultation" -> R.string.template_desc_investment_consultation
        "Stock Market Discussion" -> R.string.template_desc_stock_market_discussion
        "Listed Company Research" -> R.string.template_desc_listed_company_research
        "From Chaos to Clarity: Tagged Daily Review" -> R.string.template_desc_from_chaos_to_clarity_tagged_daily_review
        "Daily Log" -> R.string.template_desc_daily_log
        "Key Data Points" -> R.string.template_desc_key_data_points
        "Meeting Highlights" -> R.string.template_desc_meeting_highlights
        "Work Report Summary" -> R.string.template_desc_work_report_summary
        "Email Thread Summary" -> R.string.template_desc_email_thread_summary
        "Long Article to Slide Deck Outline" -> R.string.template_desc_long_article_to_slide_deck_outline
        "Turning Scattered Notes into a To-Do List" -> R.string.template_desc_turning_scattered_notes_into_a_to_do_list
        else -> return summary
    }
    return ctx.getString(res)
}
