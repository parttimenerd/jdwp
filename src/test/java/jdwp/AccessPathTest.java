package jdwp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccessPathTest {

    @Test
    public void testEndsWith() {
        AccessPath path = new AccessPath("a", "b", 1, "c");
        assertTrue(path.endsWith("c"));
        assertFalse(path.endsWith("d"));
        assertTrue(path.endsWith(String.class));
        assertTrue(path.endsWith(Integer.class, String.class));
        assertFalse(path.endsWith(Integer.class, String.class, String.class));
    }
}
