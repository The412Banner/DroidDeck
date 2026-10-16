package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class VacDiagnosticsTest {
    @Test fun tailReadsOnlyWhatThisSessionAppended() {
        val log = java.io.File.createTempFile("console", ".log")
        try {
            log.writeText("You are in insecure mode.\nConnected to 1.2.3.4\n")
            val armed = log.length()
            log.appendText("Connecting to 5.6.7.8\n")
            assertEquals(listOf("Connecting to 5.6.7.8", ""), VacDiagnostics.tail(log, armed))
        } finally {
            log.delete()
        }
    }

    @Test fun tailReadsAllOfAReplacedFile() {
        val log = java.io.File.createTempFile("console", ".log")
        try {
            log.writeText("new\n")
            assertEquals(listOf("new", ""), VacDiagnostics.tail(log, 1000L))
        } finally {
            log.delete()
        }
    }
}
