package com.nisaba.infra.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A voice of the person's own (DESIGN §5.3a). [ink] = its answer ink (ARGB), null = the answer ink in Settings. */
@Entity(tableName = "own_voices")
data class OwnVoiceDto(
    @PrimaryKey val id: String,
    val name: String,
    val tagline: String,
    val sample: String,
    val voice: String,
    val words: Int,
    val temperature: Double,
    val ink: Int?,
    val hint: String,
    val oops: String,
    val createdAt: Long,
)
