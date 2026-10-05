package com.nisaba.infra.db

import com.nisaba.domain.memory.Fact
import com.nisaba.domain.memory.FactCategory
import com.nisaba.domain.memory.FactId
import com.nisaba.domain.memory.NewFact
import com.nisaba.domain.shared.ExchangeId

fun NewFact.toDto() = FactDto(category = category.key, text = text, sourceExchangeId = sourceExchangeId?.value, createdAt = createdAtMillis)

fun FactDto.toModel() = Fact(FactId(id), FactCategory.of(category), text, sourceExchangeId?.let(::ExchangeId), createdAt)
