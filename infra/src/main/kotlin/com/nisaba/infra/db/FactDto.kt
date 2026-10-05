package com.nisaba.infra.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Long-term memory (DESIGN §4.5). No foreign key: facts outlive the notebook and exchange they came from. */
@Entity(tableName = "facts")
data class FactDto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val text: String,
    val sourceExchangeId: String?,
    val createdAt: Long,
)
