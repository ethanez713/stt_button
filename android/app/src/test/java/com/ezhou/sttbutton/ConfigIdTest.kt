package com.ezhou.sttbutton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfigIdTest {
    private val config = """{"take_cap_s":300}""".toByteArray()
    private val models = listOf("models/a" to 100L, "models/b" to 200L)

    @Test
    fun given_the_same_inputs_when_named_then_the_id_is_twelve_hex_characters_and_stable() {
        val id = ConfigId.of(config, models)

        assertEquals(id, ConfigId.of(config, models))
        assertEquals(12, id.length)
        assertEquals(id, id.filter { it in "0123456789abcdef" })
    }

    @Test
    fun given_a_changed_config_when_named_then_the_id_changes() {
        assertNotEquals(ConfigId.of(config, models), ConfigId.of("""{"take_cap_s":240}""".toByteArray(), models))
    }

    @Test
    fun given_a_model_file_of_another_length_when_named_then_the_id_changes() {
        assertNotEquals(ConfigId.of(config, models), ConfigId.of(config, listOf("models/a" to 101L, "models/b" to 200L)))
    }

    @Test
    fun given_the_files_listed_in_another_order_when_named_then_the_id_is_the_same() {
        assertEquals(ConfigId.of(config, models), ConfigId.of(config, models.reversed()))
    }
}
