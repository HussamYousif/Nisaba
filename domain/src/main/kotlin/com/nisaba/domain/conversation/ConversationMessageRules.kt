package com.nisaba.domain.conversation

import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind

/** Whether tapping the failure helps (asking again won't fix a missing key). */
fun retryHelps(error: DomainError): Boolean = error !is DomainError.Auth

/** The notebook's voice when the ink won't flow: one short line, never a stack trace; [opener] = the voice's "Bah." */
fun failureMessage(error: DomainError, opener: String = ""): String =
    if (opener.isBlank()) failureLine(error) else "${opener.trim()} ${failureLine(error)}"

private fun failureLine(error: DomainError): String = when (error) {
    is DomainError.Network -> when (error.kind) {
        NetworkKind.Offline -> "The ink wouldn't flow: no connection"
        NetworkKind.Timeout -> "The ink ran dry: it took too long"
        NetworkKind.RateLimited -> "Too many questions at once; try again in a moment"
        NetworkKind.Server -> "DeepSeek is having trouble"
    }
    DomainError.Auth -> "No working DeepSeek key: add one in Settings"
    DomainError.Quota -> "Out of credit at DeepSeek: top it up, then tap"
    is DomainError.Invalid -> when (error.message) {
        "empty answer" -> "Nothing came back"
        CUT_SHORT -> "The answer was cut short"
        else -> "The request was refused"
    }
    DomainError.NotFound, is DomainError.Storage -> "Something went wrong"
}
