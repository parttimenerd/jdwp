package jdwp;

import org.junit.jupiter.api.Test;
import tunnel.util.Hashed;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HashedTest {

    @Test
    public void testHash() {
        assertTrue(Hashed.hash(1, 1) != 0);
        assertTrue(Hashed.hash((long)1, 1) != 0);
        assertTrue(Hashed.hash((short) 1, 1) != 0);
    }

    @Test
    public void testHashWithNullBytes() {
        assertNotEquals(Hashed.create("a", (byte)0, 0, 3).hash(),
                Hashed.create("a", (byte)0, 0, 4).hash());
    }

    @Test
    public void testHashWithLongArray() {
        assertNotEquals(Hashed.hash((byte)11, (byte)2, -3755704790172322930L),
                Hashed.hash((byte)11, (byte)3, -3755704790172322930L));
    }

    @Test
    public void testHashWithLongArray2() {
        assertNotEquals(Hashed.hash((short)2817, 1418441614), Hashed.hash((short)2818, 1418441614));
    }

    @Test
    public void testHashWithLongArray3() {
        assertNotEquals(Hashed.hash((short)2817, -3755704790172322930L), Hashed.hash((short)2818, -3755704790172322930L));
    }

    @Test
    public void testHashIntOneLong() {
        assertNotEquals(Hashed.hash(1, 1), Hashed.hash(2, 1));
    }
}