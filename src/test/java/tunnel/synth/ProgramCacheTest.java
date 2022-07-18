package tunnel.synth;

import jdwp.EventCmds;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.ClassUnload;
import jdwp.ReplyOrError;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import tunnel.ProgramCache;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Program;
import tunnel.util.Either;
import tunnel.util.Util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.Reference.thread;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.*;

public class ProgramCacheTest {

    @Test
    public void testSmallEventProgramWithSingleAssignmentIsIncluded() {
        var program = "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\") " +
                "(\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))";
        var cache = new ProgramCache(1, 10, false);
        cache.accept(Program.parse(program));
        assertEquals(1, cache.size());
    }

    @Test
    public void testAccessEventsProgram() {
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        var program = "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                "\"kind\")=(wrap \"string\" \"ClassUnload\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) " +
                "(\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 (request VirtualMachine " +
                "ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))";
        var cache = new ProgramCache(1, 10, false);
        cache.accept(Program.parse(program));
        assertEquals(program, cache.get(events).get().toString());
    }

    @Test
    @SneakyThrows
    public void testLoadAndStore() {
        var cache = new ProgramCache(0, 10, false);
        var program = Program.parse("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) " +
                "(\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\")" +
                "(\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))");
        cache.accept(program);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        cache.store(s, 0);
        var input = new ByteArrayInputStream(s.toByteArray());
        var newCache = new ProgramCache(0, 10);
        newCache.load(input);
        assertEquals(cache, newCache);
    }

    @Test
    @SneakyThrows
    public void testLoadAndStoreAndCleanUp() {
        var cache = new ProgramCache(1, 10, false);
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
        var cache = new ProgramCache(1, 10, false);
        var program = Program.parse("((= cause " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\")))" +
                "(= var1 (request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
                "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129))))");
        cache.accept(program);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        cache.store(s, 0);
        var input = new ByteArrayInputStream(s.toByteArray());
        var newCache = new ProgramCache(1);
        newCache.load(input);
        assertEquals(cache.size(), newCache.size());
        var expected = new ProgramCache(1, 10, false);
        expected.accept(Program.parse("((= cause " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))"));
        assertEquals(expected, newCache);
    }

    @Test
    public void testEquals() {
        var cache = new ProgramCache(1, 10, false);
        var program = Program.parse("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) " +
                "(\"events\" 0 " +
                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\") " +
                "(\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 " +
                "(request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))");
        cache.accept(program);
        assertNotEquals(new ProgramCache(), cache);
    }

    @Test
    @SneakyThrows
    public void testLoadAndStoreAndCleanUp3() {
        var cache = new ProgramCache(1, 10, false);
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

    @Test
    public void testGetSimilar() {
        var cache = new ProgramCache(1, 10, false);
        var program = Program.parse("((= cause " +
                "(request EventRequest Set (\"eventKind\")=( wrap \"byte\" 8) (\"suspendPolicy\")=(wrap \"byte\" 0)))" +
                "(= var0 (request EventRequest Set (\"eventKind\")=( wrap \"byte\" 8) (\"suspendPolicy\")=(wrap " +
                "\"byte\" 0))))");
        cache.accept(program);
        var program2 = Program.parse("((= cause " +
                "(request EventRequest Set (\"eventKind\")=( wrap \"byte\" 9) (\"suspendPolicy\")=(wrap \"byte\" 0)))" +
                "(= var0 (request EventRequest Set (\"eventKind\")=( wrap \"byte\" 9) (\"suspendPolicy\")=(wrap " +
                "\"byte\" 0))))");
        assertEquals(program2, cache.get(program2.getCause()).get());
    }

    @Test
    public void testGetForMultipleEvents() {
        Function<List<Integer>, Events> eventsCreator = threadIds -> new jdwp.EventCmds.Events(0, wrap((byte) 2),
                new ListValue<>(Type.OBJECT,
                threadIds.stream().map(id -> new EventCmds.Events.VMStart(wrap(0), thread(id))).collect(Collectors.toList())
        ));
        Function<List<Integer>, Partition> creator =
                threadIds -> new Partition(Either.right(eventsCreator.apply(threadIds)),
                Util.combine(List.of(
                                p(new jdwp.VirtualMachineCmds.IDSizesRequest(1582),
                                        new ReplyOrError<>(1582, new jdwp.VirtualMachineCmds.IDSizesReply(1582,
                                                wrap(8), wrap(8), wrap(8), wrap(8), wrap(8))))),
                        threadIds.stream().map(id -> p(new jdwp.ThreadReferenceCmds.NameRequest(2000 + id, thread(id)),
                                new ReplyOrError<>(2000 + id, new jdwp.ThreadReferenceCmds.NameReply(2000 + id, wrap(
                                        "Thread" + id))))).collect(Collectors.toList())));
        var partition = creator.apply(List.of(1, 2, 3));
        var cache = new ProgramCache(1, 10, false);
        cache.accept(partition);
        assertEquals(3, cache.size());
        var program = cache.get(eventsCreator.apply(List.of(2))).get();
        assertTrue(program.hasSingleCauseOrNull());
        assertEquals("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")" +
                "=(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                "\"thread\")=(wrap \"thread\" 2)))\n" +
                "  (= var0 (request VirtualMachine IDSizes))\n" +
                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\"))))",
                program.toPrettyString());
        var program2 = cache.get(eventsCreator.apply(List.of(1, 2))).get();
        assertEquals(3, cache.size()); // the cache size does not change
        // idea: the resulting program consists of the first event and its program,
        // followed by all other events (sans overlap)
        assertEquals("(\n" +
                "  (switch 0\n" +
                "    (default\n" +
                "      (= cause (object (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")=(wrap " +
                "\"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"thread\")=" +
                "(wrap \"thread\" 1)))\n" +
                "      (= var0 (request VirtualMachine IDSizes))\n" +
                "      (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\")))))\n" +
                "  (switch 1\n" +
                "    (default\n" +
                "      (= cause (object (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")=(wrap " +
                "\"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"thread\")=" +
                "(wrap \"thread\" 2)))\n" +
                "      (= var0 (request VirtualMachine IDSizes))\n" +
                "      (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\"))))))", program2.toPrettyString());
    }
}
