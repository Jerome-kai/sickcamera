package io.github.jeromekai.latent.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolchainTest {
    @Test
    fun compilesAndRuns() {
        assertEquals("core", Toolchain.ok())
    }
}
