package jdwp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for PacketInputStream and PacketOutputStream
 * that did not fit into JDWPTest
 */
public class PacketStreamTest {

    @Test
    public void testReadingNegativeBytesAsLength() throws IOException {
        var input = new InputStream() {
            int ctr = 0;
            @Override
            public int read() throws IOException {
                if (ctr == 3) {
                    return -43;
                }
                ctr++;
                return 0;
            }
        };
        assertEquals(-43 & 0xff, PacketInputStream.read(new VM(1), input).length());
    }
}
