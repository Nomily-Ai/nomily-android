package com.nomily.app.core.llm

/**
 * The four everyday templates modelled on Plaud's own product (iOS `PlaudTemplateLibrary.swift`, prompts copied
 * verbatim): a full automatic report, meeting minutes, extraction notes and a to-do list. They lead
 * [DEFAULT_SUMMARIZE_TEMPLATES], so [AUTO_SUMMARY_TEMPLATE_NAME] is also the template of the automatic summary.
 */
val PLAUD_SUMMARIZE_TEMPLATES: List<SummarizeTemplate> = listOf(
    SummarizeTemplate(
        id = "plaud-0",
        category = "General Summary",
        name = "Quick Summary",
        summary = "Automatic full report: subjects and conclusions, to-dos, follow-ups.",
        prompt = """
Produce the automatic full report — the document this app writes when no template was chosen. This is a FULL report: completeness wins over brevity, every real point the recording makes gets in.

Structure, in this order — drop any section that ends up with no real content:

# [A short title naming what the recording is about]

## [Major subject]
Split the recording into its major subjects, one section per subject — as many sections as the recording actually has. Number the section titles in the speakers' language: 一、二、三… for a Chinese transcript, 1. 2. 3. otherwise.

Under each subject, dense bullets that carry the facts, numbers, names, positions and outcomes as they were said; sub-points may nest one level deeper. A decision or conclusion reached gets its own bullet, stated as the decision, not as the discussion.

## To-dos
One bullet per explicit commitment: task, deadline, owner. Leave owner or date blank when the transcript does not say — never invent them. Omit the section when there are none.

## Needs follow-up
Open questions, unresolved issues, and anything still awaiting confirmation. Omit the section when there are none.

The title and every heading are in the speakers' language. Reply with the report in Markdown and nothing else.
""",
    ),
    SummarizeTemplate(
        id = "plaud-1",
        category = "Meeting Notes",
        name = "Meeting Minutes",
        summary = "Complete, clear meeting minutes with actions and decisions.",
        prompt = """
Generate complete, clear meeting minutes with actions and decisions.

Output exactly these sections — drop any section the transcript gives no real content for:

## Overview
One short paragraph: the meeting's purpose and outcome.

## Attendees
Only people who actually spoke or were named. If unknown, drop the section.

## Discussion & Conclusions
One block per topic, conclusion first, at most three sentences.

## Decisions
One line per decision actually made.

## Action Items
A table: | Action | Owner | Deadline |. Leave a cell blank when the transcript does not say — never invent.

## Open Questions
Only items left unresolved.

Keep the whole output under a third of the transcript's length.
""",
    ),
    SummarizeTemplate(
        id = "plaud-2",
        category = "Meeting Notes",
        name = "Key Points",
        summary = "Extraction notes: key insights, quotes with speakers, numbers and dates.",
        prompt = """
Produce extraction notes: only the content worth keeping, nothing else.

Output exactly these sections — drop any section the transcript gives no real content for:

## Key Insights
At most 6 bullets, one sentence each.

## Quotes
Short verbatim quotes that carry meaning, each with the speaker who said it.

## Numbers & Dates
Every figure, deadline and date mentioned, with its context.

## Risks & Unknowns
Only risks raised or questions left open.

Do not restate the recording's metadata or structure — extract content only.
""",
    ),
    SummarizeTemplate(
        id = "plaud-3",
        category = "Action Items",
        name = "To-Do List",
        summary = "Only explicit to-dos: task, owner, deadline. Honest empty state when none.",
        prompt = """
Extract ONLY explicit to-dos: something someone committed to, was assigned, or that carries a deadline. Ideas, possibilities and "we could maybe" are not to-dos.

Output a table: | To-do | Owner | Deadline |.
Leave a cell blank when the transcript does not say — never invent.
Group by owner when several items share one.
If the transcript contains no to-do at all, output exactly one line saying so — do not pad the section with anything else.
""",
    ),
)

/** Category and name of the template used whenever none was chosen: the automatic summary and "Auto" generation's fallback. */
const val AUTO_SUMMARY_TEMPLATE_CATEGORY = "General Summary"
const val AUTO_SUMMARY_TEMPLATE_NAME = "Quick Summary"
