package com.nomily.app.core.llm

/**
 * Picks the template that best matches a transcript, so "Auto" generation needs no manual choice (iOS
 * `AutoTemplatePicker`).
 *
 * Local cue words rather than a model call: a model would cost a round-trip before the summary even starts, can
 * fail on a flaky channel and can't explain an odd pick. A transcript that keeps saying 面试 / 候选人 is an interview;
 * this answers that offline and deterministically. Without a clear signal the automatic report
 * ([AUTO_SUMMARY_TEMPLATE_NAME]) wins — never a bad fit, just a general one.
 */
object AutoTemplatePicker {

    /** [weight] is what one keyword hit counts: meetings and interviews are strong cues, "to-do" turns up in any meeting. */
    private class Rule(val category: String, val keywords: List<String>, val weight: Int)

    private val rules = listOf(
        Rule("Meeting Notes", listOf("会议", "开会", "议程", "参会", "例会", "评审会", "周会", "讨论会", "meeting", "standup", "sync up", "agenda"), 3),
        Rule("Interview", listOf("面试", "招聘", "候选人", "简历", "入职", "interview", "candidate", "resume"), 4),
        Rule("Sales", listOf("客户", "报价", "合同", "签单", "销售", "商机", "续费", "成交", "pipeline", "quotation", "deal", "renewal"), 3),
        Rule("Medical", listOf("患者", "症状", "诊断", "处方", "就诊", "病历", "用药", "patient", "symptom", "diagnosis", "prescription"), 4),
        Rule("Academic", listOf("课堂", "讲座", "课程", "论文", "实验", "同学", "老师", "lecture", "seminar", "paper", "coursework"), 3),
        Rule("Finance", listOf("财报", "营收", "毛利", "估值", "股票", "投资", "融资", "revenue", "earnings", "valuation", "investor"), 3),
        Rule("Professional", listOf("绩效", "一对一", "项目进度", "里程碑", "复盘", "汇报", "one-on-one", "1:1", "milestone", "retrospective", "status update"), 3),
        Rule("Action Items", listOf("待办", "行动项", "截止", "任务清单", "负责人", "to-do", "todo", "action item", "deadline", "owner"), 1),
        Rule("Creative", listOf("头脑风暴", "创意", "灵感", "点子", "brainstorm", "idea"), 2),
    )

    /** Below this a category is noise: a single stray 会议 in an hour of therapy notes is not a meeting. */
    private const val MIN_SCORE = 3

    /** One template for [transcript]; null only when [templates] is empty. */
    fun pick(transcript: String, templates: List<SummarizeTemplate>): SummarizeTemplate? {
        if (templates.isEmpty()) return null
        val text = transcript.lowercase()
        val scores = rules.associate { rule -> rule.category to rule.keywords.sumOf { occurrences(it, text) } * rule.weight }
        val best = scores.filterValues { it >= MIN_SCORE }.maxByOrNull { it.value }?.key
        return best?.let { c -> templates.firstOrNull { it.category == c && it.isBuiltIn } } ?: autoDefault(templates)
    }

    /** The template used when nothing was chosen; the first template when the store no longer has it. */
    fun autoDefault(templates: List<SummarizeTemplate>): SummarizeTemplate? =
        templates.firstOrNull { it.category == AUTO_SUMMARY_TEMPLATE_CATEGORY && it.name == AUTO_SUMMARY_TEMPLATE_NAME && it.isBuiltIn }
            ?: templates.firstOrNull()

    private fun occurrences(needle: String, haystack: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }
}
