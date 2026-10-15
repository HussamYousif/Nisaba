package com.nisaba.persona

import com.nisaba.domain.persona.VoiceDraft

/**
 * "A voice of your own": [open] = the dialog is up; [draft] = what the model made of [description] (not kept yet);
 * [kept] = the id of a voice just kept, for the picker that asked to select it.
 */
data class OwnVoiceUiState(
    val open: Boolean = false,
    val description: String = "",
    val drafting: Boolean = false,
    val draft: VoiceDraft? = null,
    val error: String? = null,
    val kept: String? = null,
)
