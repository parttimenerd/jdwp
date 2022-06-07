package tunnel.synth;

import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.ClassUnload;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.ParsedPacket;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.ProgramCache;
import tunnel.ProgramCache.Mode;
import tunnel.synth.program.Program;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        var program = "((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\") " +
                "(\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))";
        var cache = new ProgramCache();
        cache.accept(Program.parse(program));
        assertEquals(program, cache.get(events).get().toString());
    }

    @Test
    @SneakyThrows
    public void testLoadAndStore() {
        var cache = new ProgramCache();
        var program = Program.parse("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) " +
                "(\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))");
        cache.accept(program);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        cache.store(s);
        var input = new ByteArrayInputStream(s.toByteArray());
        var newCache = new ProgramCache();
        newCache.load(input);
        assertEquals(cache, newCache);
    }

    @Test
    @SneakyThrows
    public void testLoadAndStoreAndCleanUp() {
        var cache = new ProgramCache();
        var program = Program.parse("((= cause (request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
                "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129))) (= var0 (request Method " +
                "VariableTableWithGeneric (\"methodID\")=(wrap " +
                "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129))) (= var1 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))");
        cache.accept(program);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        cache.store(s);
        var input = new ByteArrayInputStream(s.toByteArray());
        var newCache = new ProgramCache();
        newCache.load(input);
        assertEquals(new ProgramCache(), newCache);
    }

    @Test
    @SneakyThrows
    public void testLoadAndStoreAndCleanUp2() {
        var cache = new ProgramCache();
        var program = Program.parse("((= cause " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\")))" +
                "(= var1 (request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
                "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129))))");
        cache.accept(program);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        cache.store(s);
        var input = new ByteArrayInputStream(s.toByteArray());
        var newCache = new ProgramCache(Mode.LAST, 1);
        newCache.load(input);
        assertEquals(cache.size(), newCache.size());
        var expected = new ProgramCache(Mode.LAST, 1);
        expected.accept(Program.parse("((= cause " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))"));
        assertEquals(expected, newCache);
    }

    @Test
    public void testEquals() {
        var cache = new ProgramCache();
        var program = Program.parse("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) " +
                "(\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))");
        cache.accept(program);
        assertNotEquals(new ProgramCache(), cache);
    }

    @Test
    @SneakyThrows
    public void testLoadAndStoreAndCleanUp3() {
        var cache = new ProgramCache();
        var program = Program.parse("((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" " +
                "\"Breakpoint\") (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"requestID\")=(wrap \"int\" 37)" +
                " (\"events\" 0 \"thread\")=(wrap \"thread\" 1) (\"events\" 0 \"location\" \"declaringType\")=(wrap " +
                "\"class-type\" 1129) (\"events\" 0 \"location\" \"methodRef\")=(wrap \"method\" 105553154998536) " +
                "(\"events\" 0 \"location\" \"codeIndex\")=(wrap \"long\" 2)))\n" +
                "  (= var0 (request Method VariableTableWithGeneric (\"methodID\")=(get cause \"events\" 0 " +
                "\"location\" \"methodRef\") (\"refType\")=(get cause \"events\" 0 \"location\" \"declaringType\")))" +
                "\n" +
                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                "  (= var2 (request ThreadReference Status (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                "  (= var3 (request ThreadReference FrameCount (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                "  (= var4 (request ThreadReference Frames (\"length\")=(get var0 \"argCnt\") (\"startFrame\")=(get " +
                "var0 \"slots\" 0 \"slot\") (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                "  (= var5 (request StackFrame GetValues (\"frame\")=(get var4 \"frames\" 0 \"frameID\") (\"thread\")" +
                "=(get cause \"events\" 0 \"thread\") (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 " +
                "\"slot\")=(get var0 \"slots\" 0 \"slot\") (\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 " +
                "\"slot\")=(get var0 \"argCnt\"))))\n" +
                "\n" +
                "((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" \"VMStart\") " +
                "(\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                "\"thread\")=(wrap \"thread\" 1)))\n" +
                "  (= var0 (request VirtualMachine IDSizes)))");
        cache.accept(program);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        cache.store(s);
        var input = new ByteArrayInputStream(s.toByteArray());
        var newCache = new ProgramCache();
        newCache.load(input);
        assertEquals(cache, newCache);
    }
}
