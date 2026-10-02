package com.nisaba.domain.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedModelTest {
    @Test fun portraitPageMatchesPanel() = assertEquals(2480f, PageSize.PORTRAIT.h)
}
