package com.memfault.usagereporter

import android.os.ParcelFileDescriptor
import com.memfault.bort.shared.CommandRunnerOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

val TRUE_COMMAND = listOf("true")
val ECHO_COMMAND = listOf("echo", "hello")
val NON_EXISTENT_COMMAND = listOf("fOoBAr12345")
val SLEEP_COMMAND = listOf("sleep", "100")
val YES_COMMAND = listOf("yes")

class CommandRunnerTest {
    lateinit var outputStreamFactoryMock: (ParcelFileDescriptor) -> OutputStream
    lateinit var outputStreamMock: ByteArrayOutputStream
    lateinit var reportResultMock: CommandRunnerReportResult

    @BeforeEach
    fun setUp() {
        outputStreamMock = spyk(ByteArrayOutputStream(1024))
        outputStreamFactoryMock = mockk()
        reportResultMock = mockk(name = "reportResult", relaxed = true)
        every { outputStreamFactoryMock(any()) } answers { outputStreamMock }
    }

    @Test
    fun nullOutFd() {
        val cmd = CommandRunner(
            TRUE_COMMAND,
            CommandRunnerOptions(outFd = null),
            reportResultMock,
            outputStreamFactoryMock
        ).apply { run() }
        assertEquals(cmd.process, null)
        verify(exactly = 1) { reportResultMock(null, false) }
        verify(exactly = 0) { outputStreamFactoryMock.invoke(any()) }
    }

    @Test
    fun happyPath() {
        val outFd: ParcelFileDescriptor = mockk()
        val cmd = CommandRunner(
            ECHO_COMMAND,
            CommandRunnerOptions(outFd = outFd),
            reportResultMock,
            outputStreamFactoryMock
        ).apply { run() }
        assertNotNull(cmd.process)
        verify(exactly = 1) { outputStreamFactoryMock.invoke(outFd) }
        verify(exactly = 1) { outputStreamMock.close() }
        verify(exactly = 1) { reportResultMock(0, false) }
        assertEquals("hello\n", outputStreamMock.toString("utf8"))
    }

    @Test
    fun badCommand() {
        val outFd: ParcelFileDescriptor = mockk()
        val cmd = CommandRunner(
            NON_EXISTENT_COMMAND,
            CommandRunnerOptions(outFd = outFd),
            reportResultMock,
            outputStreamFactoryMock
        ).apply { run() }
        verify(exactly = 1) { outputStreamMock.close() }
        verify(exactly = 1) { reportResultMock(null, false) }
        assertEquals(cmd.process, null)
        assertEquals("", outputStreamMock.toString("utf8"))
    }

    @Test
    fun exceptionDuringCopy() {
        outputStreamMock = mockk()
        every { outputStreamMock.write(any<ByteArray>()) } throws IOException()

        val outFd: ParcelFileDescriptor = mockk()
        val cmd = CommandRunner(
            YES_COMMAND,
            CommandRunnerOptions(outFd = outFd),
            reportResultMock,
            outputStreamFactoryMock
        ).apply { run() }
        assertNotNull(cmd.process)
        cmd.process?.let {
            assert(!it.isAlive)
        }
        verify(exactly = 1) { reportResultMock(any(), false) }
        verify(exactly = 1) { outputStreamMock.close() }
    }

    @Test
    fun interruptedDueToTimeout() {
        val executor = TimeoutThreadPoolExecutor(1)
        val outFd: ParcelFileDescriptor = mockk()
        val cmd = CommandRunner(
            SLEEP_COMMAND,
            CommandRunnerOptions(outFd = outFd),
            reportResultMock,
            outputStreamFactoryMock
        )
        val future = executor.submitWithTimeout(cmd, 10.milliseconds)
        assertThrows<CancellationException> {
            future.get()
        }
        verify(exactly = 1, timeout = 1000) { reportResultMock(143, true) }
        assertEquals(true, cmd.didTimeout)
    }
}
