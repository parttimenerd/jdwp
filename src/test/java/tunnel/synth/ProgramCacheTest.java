package tunnel.synth;

import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.ClassUnload;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.ParsedPacket;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import org.junit.jupiter.api.Test;
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
        var cache = new ProgramCache(Mode.LAST, 1);
        Arrays.stream(programs).forEach(p -> cache.accept(Program.parse(p)));
        assertEquals(expectedProgram == null ? null : Program.parse(expectedProgram), cache.get(cause).orElse(null));
    }

    @Test
    public void testSmallEventProgramWithSingleAssignmentIsIncluded() {
        var program = "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))";
        var cache = new ProgramCache(Mode.LAST, 2);
        cache.accept(Program.parse(program));
        assertEquals(1, cache.size());
    }

    @Test
    public void testAccessEventsProgram() {
        var events = new Events(0, wrap((byte)2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        var program = "((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\") " +
                "(\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))";
        var cache = new ProgramCache();
        cache.accept(Program.parse(program));
        assertEquals(program, cache.get(events).get().toString());
    }
}
