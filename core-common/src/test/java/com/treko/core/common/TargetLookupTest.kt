package com.treko.core.common

import com.treko.core.common.model.TaskItem
import com.treko.core.common.util.TargetLookup
import com.treko.core.common.util.failureMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetLookupTest {

    private val tasks = listOf(
        TaskItem(1, "Comprar leche", false),
        TaskItem(2, "Comprar pan", false),
        TaskItem(3, "Llamar a mamá", false),
        TaskItem(4, "Pan", false)
    )

    private fun find(id: Int?, title: String?) =
        TargetLookup.find(tasks, id, title, { it.id }, { it.title })

    @Test
    fun blankTitleWithoutIdSelectsNothing() {
        assertEquals(TargetLookup.MissingQuery, find(null, ""))
        assertEquals(TargetLookup.MissingQuery, find(null, "   "))
        assertEquals(TargetLookup.MissingQuery, find(-1, null))
    }

    @Test
    fun idWinsOverTitle() {
        assertEquals(TargetLookup.Found(tasks[2]), find(3, "leche"))
        assertTrue(find(99, "leche") is TargetLookup.NotFound)
    }

    @Test
    fun exactMatchBeatsPartial() {
        // "pan" coincide parcialmente con "Comprar pan" y exactamente con "Pan".
        assertEquals(TargetLookup.Found(tasks[3]), find(null, "pan"))
    }

    @Test
    fun uniquePartialMatchIsAccepted() {
        assertEquals(TargetLookup.Found(tasks[0]), find(null, "leche"))
    }

    @Test
    fun matchingIgnoresAccentsAndCase() {
        assertEquals(TargetLookup.Found(tasks[2]), find(null, "LLAMAR A MAMA"))
    }

    @Test
    fun severalPartialMatchesAreAmbiguous() {
        val result = find(null, "comprar")
        assertTrue(result is TargetLookup.Ambiguous)
        assertEquals(listOf(1, 2), (result as TargetLookup.Ambiguous).candidates.map { it.id })
    }

    @Test
    fun noMatchIsNotFound() {
        assertEquals(TargetLookup.NotFound("gimnasio"), find(null, "gimnasio"))
    }

    @Test
    fun failureMessagesNeverClaimAChange() {
        val describe: (TaskItem) -> String = { "#${it.id} ${it.title}" }
        assertNull(find(1, null).failureMessage("task", tasks, describe))
        listOf(find(null, ""), find(null, "comprar"), find(null, "gimnasio")).forEach {
            val msg = it.failureMessage("task", tasks, describe)!!
            assertTrue(msg, msg.contains("nothing was changed", ignoreCase = true))
        }
        assertTrue(find(null, "").failureMessage("task", tasks, describe)!!.contains("#3 Llamar a mamá"))
    }
}
