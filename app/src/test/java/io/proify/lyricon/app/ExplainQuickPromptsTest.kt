package io.proify.lyricon.app

import io.github.proify.lyricon.app.ai.explain.*
import org.junit.Assert.*
import org.junit.Test

class ExplainQuickPromptsTest {
    @Test fun groupsMatchTheExistingContextualSuggestions() {
        assertEquals(QuickPromptGroup.LINE, quickPromptGroup(ExplainRange.LINE, ExplainAngle.IMAGERY))
        assertEquals(QuickPromptGroup.SONG, quickPromptGroup(ExplainRange.SONG, ExplainAngle.OVERVIEW))
        assertEquals(QuickPromptGroup.LANGUAGE, quickPromptGroup(ExplainRange.LINE, ExplainAngle.LANGUAGE))
        assertEquals(QuickPromptGroup.LANGUAGE, quickPromptGroup(ExplainRange.SONG, ExplainAngle.LANGUAGE))
        QuickPromptGroup.entries.forEach { assertEquals(3, defaultQuickPrompts(it).size) }
    }
    @Test fun savingTrimsBlankRowsAndRemovesDuplicateQuestionsInOrder() {
        assertEquals(listOf("解释一下", "有什么双关？"),
            cleanQuickPrompts(listOf(" 解释一下 ", "", " ", "有什么双关？", "解释一下")))
        assertTrue(cleanQuickPrompts(listOf("", " ")).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun tooManyQuestionsAreRejected() {
        cleanQuickPrompts((0..ExplainQuickPrompts.MAX_COUNT).map { "问题 $it" })
    }
    @Test(expected = IllegalArgumentException::class) fun excessivelyLongQuestionsAreRejected() {
        cleanQuickPrompts(listOf("问".repeat(ExplainQuickPrompts.MAX_LENGTH + 1)))
    }
    @Test(expected = IllegalArgumentException::class) fun multipleLinesCannotBecomeOneShortcut() {
        cleanQuickPrompts(listOf("第一行\n第二行"))
    }
}
