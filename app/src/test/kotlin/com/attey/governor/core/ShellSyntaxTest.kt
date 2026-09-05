package com.attey.governor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellSyntaxTest {

    @Test
    fun singleQuoteIsEncodedAsOneShellArgument() {
        assertEquals("'policy'\"'\"'0'", shellQuote("policy'0"))
    }
}
