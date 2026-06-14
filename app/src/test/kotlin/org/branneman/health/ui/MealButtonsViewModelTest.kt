package org.branneman.health.ui

import org.branneman.health.aMealTemplate
import org.branneman.health.db.entities.MealTemplateEntity
import org.junit.Test
import kotlin.test.assertEquals

class MealButtonsViewModelTest {

    @Test fun `reindexed assigns sequential sortOrder starting at 0`() {
        val list = listOf(
            aMealTemplate(name = "A", sortOrder = 5),
            aMealTemplate(name = "B", sortOrder = 2),
            aMealTemplate(name = "C", sortOrder = 9),
        )
        val result = list.reindexed()
        assertEquals(0, result[0].sortOrder)
        assertEquals(1, result[1].sortOrder)
        assertEquals(2, result[2].sortOrder)
        assertEquals("A", result[0].name)
    }

    @Test fun `reindexed on empty list returns empty`() {
        assertEquals(emptyList(), emptyList<MealTemplateEntity>().reindexed())
    }
}
