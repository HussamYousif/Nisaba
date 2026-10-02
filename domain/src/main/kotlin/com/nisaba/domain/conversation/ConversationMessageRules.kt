package com.nisaba.domain.conversation

import com.nisaba.domain.shared.DomainError
import com.nisaba.domain.shared.NetworkKind

/** The notebook's voice when the ink won't flow: one short line, never a stack trace. */
fun failureMessage(error: DomainError): String = when (error) {
    is DomainError.Network -> when (error.kind) {
        NetworkKind.Offline -> "The ink wouldn't flow: no connection"
        NetworkKind.Timeout -> "The ink ran dry: it took too long"
        NetworkKind.RateLimited -> "Too many questions at once; try again in a moment"
        NetworkKind.Server -> "The oracle is having trouble"
    }
    DomainError.Auth -> "The key was rejected: check Settings"
    DomainError.Quota -> "Out of credit at DeepSeek"
    is DomainError.Invalid -> if (error.message == "empty answer") "Nothing came back" else "The request was refused"
    DomainError.NotFound, is DomainError.Storage -> "Something went wrong"
}
