package com.nisaba.domain.conversation

import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationMessageRulesTest {
    @Test fun everyErrorHasAShortHumanLine() {
        val all = listOf(
            DomainError.Auth, DomainError.Quota, DomainError.NotFound, DomainError.Storage("x"), DomainError.Invalid("bad"),
            DomainError.Invalid("empty answer"), *NetworkKind.entries.map { DomainError.Network(it) }.toTypedArray(),
        )
        for (e in all) failureMessage(e).let { assertTrue(it.isNotBlank() && it.length < 60, "$e -> $it") }
        assertEquals("The key was rejected: check Settings", failureMessage(DomainError.Auth))
        assertEquals("Nothing came back", failureMessage(DomainError.Invalid("empty answer")))
    }
}
