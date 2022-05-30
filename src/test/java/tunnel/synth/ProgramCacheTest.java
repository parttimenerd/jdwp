package tunnel.synth;

import jdwp.EventRequestCmds.SetRequest;
import jdwp.ParsedPacket;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.ProgramCache;
import tunnel.ProgramCache.Mode;
import tunnel.synth.program.Program;

import java.util.Arrays;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProgramCacheTest {

    private static Object[][] lastModeTestSource() {
        return new Object[][] {
                {new String[] {
                    "((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)))"
                }, new IDSizesRequest(10), "((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)))"},
                {new String[] {
                        "((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)))"
                }, new SetRequest(10, wrap((byte)1), wrap((byte)2), new ListValue<>(Type.OBJECT)), null}
        };
    }

    @ParameterizedTest
    @MethodSource("lastModeTestSource")
    public void testLastMode(String[] programs, ParsedPacket cause, String expectedProgram) {
        var cache = new ProgramCache(Mode.LAST);
        Arrays.stream(programs).forEach(p -> cache.add(Program.parse(p)));
        assertEquals(expectedProgram == null ? null : Program.parse(expectedProgram), cache.get(cause).orElse(null));
    }
}
