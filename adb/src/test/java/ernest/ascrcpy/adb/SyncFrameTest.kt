package ernest.ascrcpy.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SyncFrameTest {
    private class Recorder : AdbChannel {
        val writes = mutableListOf<ByteArray>()
        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { writes += buffer.copyOfRange(offset, offset + length) }
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("unexpected read")
        override fun close() {}
    }
    @Test fun headerAndPayloadUseOneSendWindowWithoutChangingWireBytes() = runBlocking {
        val channel = Recorder()
        val payload = ByteArray(65528) { it.toByte() }
        channel.writeSync("DATA", payload)
        assertEquals(1, channel.writes.size)
        val frame = ByteBuffer.wrap(channel.writes.single()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("DATA", ByteArray(4).also(frame::get).toString(Charsets.US_ASCII))
        assertEquals(payload.size, frame.int)
        assertArrayEquals(payload, ByteArray(payload.size).also(frame::get))
        assertEquals(65536, frame.capacity())
    }
    @Test fun doneEncodesTimestampInHeaderWithoutAppendingAPayload() = runBlocking {
        val channel = Recorder()
        channel.writeSync("DONE", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1700000000).array(), false)
        assertEquals(8, channel.writes.single().size)
        assertEquals(1700000000, ByteBuffer.wrap(channel.writes.single(),4,4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}
