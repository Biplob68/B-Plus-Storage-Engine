package database.engine.bplus.util;

import database.engine.bplus.util.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytesTest {

    @Test
    void compareTreatsBytesAsUnsigned() {
        // 0x80 is -128 signed; unsigned it must sort above 0x7F
        assertThat(Bytes.compare(new byte[] {(byte) 0x80}, new byte[] {0x7F})).isPositive();
        assertThat(Bytes.compare(new byte[] {(byte) 0xFF}, new byte[] {0x00})).isPositive();
    }

    @Test
    void shorterPrefixSortsFirst() {
        assertThat(Bytes.compare(new byte[] {1, 2}, new byte[] {1, 2, 0})).isNegative();
        assertThat(Bytes.compare(new byte[] {}, new byte[] {0})).isNegative();
        assertThat(Bytes.compare(new byte[] {1, 2}, new byte[] {1, 2})).isZero();
    }

    @Test
    void bufferCompareMatchesArrayCompare() {
        byte[] stored = {(byte) 0x80, 0x01, 0x02};
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.put(5, stored);

        assertThat(Bytes.compare(buf, 5, stored.length, stored)).isZero();
        assertThat(Bytes.compare(buf, 5, stored.length, new byte[] {0x7F})).isPositive();
        assertThat(Bytes.compare(buf, 5, stored.length, new byte[] {(byte) 0x80, 0x01, 0x02, 0x00})).isNegative();
        assertThat(Bytes.compare(buf, 5, 0, new byte[] {})).isZero();
    }

    @Test
    void varIntRoundTripsAtBoundaries() {
        int[] values = {0, 1, 127, 128, 300, 16_383, 16_384, (1 << 21) - 1, 1 << 21, (1 << 28) - 1,
                1 << 28, Integer.MAX_VALUE};
        ByteBuffer buf = ByteBuffer.allocate(32);

        for (int value : values) {
            assertThat(Bytes.putVarInt(buf, 4, value)).as("width of %d", value).isEqualTo(Bytes.varIntSize(value));
            assertThat(Bytes.getVarInt(buf, 4)).as("round trip of %d", value).isEqualTo(value);
        }
    }

    @Test
    void varIntSizeGrowsEverySevenBits() {
        assertThat(Bytes.varIntSize(0)).isEqualTo(1);
        assertThat(Bytes.varIntSize(127)).isEqualTo(1);
        assertThat(Bytes.varIntSize(128)).isEqualTo(2);
        assertThat(Bytes.varIntSize(16_383)).isEqualTo(2);
        assertThat(Bytes.varIntSize(16_384)).isEqualTo(3);
        assertThat(Bytes.varIntSize((1 << 21) - 1)).isEqualTo(3);
        assertThat(Bytes.varIntSize(1 << 21)).isEqualTo(4);
        assertThat(Bytes.varIntSize(1 << 28)).isEqualTo(5);
    }

    @Test
    void canonicalEncodingLetsVarIntSizeWalkAChainOfFields() {
        ByteBuffer buf = ByteBuffer.allocate(32);
        int[] values = {0, 200, 70_000, 5};

        int written = 0;
        for (int value : values) {
            written += Bytes.putVarInt(buf, written, value);
        }
        int walk = 0;
        for (int value : values) {
            int decoded = Bytes.getVarInt(buf, walk);
            assertThat(decoded).isEqualTo(value);
            walk += Bytes.varIntSize(decoded);
        }
        assertThat(walk).isEqualTo(written);
    }

    @Test
    void rejectsNegativeValues() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        assertThatThrownBy(() -> Bytes.varIntSize(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bytes.putVarInt(buf, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedVarInt() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        for (int i = 0; i < 6; i++) {
            buf.put(i, (byte) 0xFF); // continuation bit set forever
        }
        assertThatThrownBy(() -> Bytes.getVarInt(buf, 0)).isInstanceOf(IllegalStateException.class);
    }
}
