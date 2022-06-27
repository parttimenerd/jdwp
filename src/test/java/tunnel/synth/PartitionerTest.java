package tunnel.synth;

import jdwp.*;
import jdwp.EventCmds.Events.VMStart;
import jdwp.JDWP.Error;
import jdwp.Reference.*;
import jdwp.ReferenceTypeCmds.SourceDebugExtensionRequest;
import jdwp.ThreadReferenceCmds.NameReply;
import jdwp.ThreadReferenceCmds.NameRequest;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.IDSizesReply;
import org.junit.jupiter.api.Test;
import tunnel.State;
import tunnel.State.WrappedPacket;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.Partitioner.Timings;
import tunnel.util.Either;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PartitionerTest {

    private static <R> WrappedPacket<R> wp(R packet) {
        return new WrappedPacket<>(packet, 10);
    }

    private static <R extends Reply> WrappedPacket<ReplyOrError<?>> wpr(R packet) {
        return wp(new ReplyOrError<>(packet));
    }


    @Test
    public void testVMStart() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner().addListener(partitions::add);
        var events = new jdwp.EventCmds.Events(0, wrap((byte) 2),
                new ListValue<>(Type.LIST, List.of(new VMStart(wrap(0), new ThreadReference(1)))));
        var sizesRequest = new jdwp.VirtualMachineCmds.IDSizesRequest(154915);
        var sizesReply = new IDSizesReply(154915, wrap(1), wrap(2), wrap(2), wrap(3), wrap(3));
        partitioner.onEvent(events);
        partitioner.onRequest(wp(sizesRequest));
        partitioner.onReply(wp(sizesRequest), wpr(sizesReply));
        partitioner.close();
        assertEquals(List.of(new Partition(Either.right(events), List.of(p(sizesRequest, sizesReply)))), partitions);
    }

    @Test
    public void testLineTableReplies() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner().addListener(partitions::add);
        var state = new State().addListener(partitioner);
        state.addRequest(wp(new jdwp.MethodCmds.LineTableRequest(166112,
                new ClassReference(1055),
                new MethodReference(105553163062272L))));
        state.addRequest(wp(new jdwp.MethodCmds.LineTableRequest(166113,
                new ClassReference(1055),
                new MethodReference(105553163062280L))));
        state.addReply(wp(new ReplyOrError<>(166112,
                new jdwp.MethodCmds.LineTableReply(166112, wrap((long) 0), wrap((long) 4),
                        new ListValue<>(Type.OBJECT)))));
        state.addReply(wp(new ReplyOrError<>(166113,
                new jdwp.MethodCmds.LineTableReply(166113, wrap((long) 0), wrap((long) 4),
                        new ListValue<>(Type.OBJECT)))));
        partitioner.close();
        assertEquals(1, partitions.size());
        var partition = partitions.get(0);
        assertTrue(partition.hasCause());
        assertEquals(2, partition.size());
    }

    @Test
    public void testEventsAsPartitionStart() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner().addListener(partitions::add);
        var state = new State().addListener(partitioner);
        state.addRequest(wp(new jdwp.ThreadReferenceCmds.ResumeRequest(16193, new ThreadReference(1L))));
        state.addReply(wpr(new jdwp.ThreadReferenceCmds.ResumeReply(16193)));
        state.addEvent(wp(new jdwp.EventCmds.Events(5, PrimitiveValue.wrap((byte)2), new ListValue<>(Type.LIST, List.of(new EventCmds.Events.Breakpoint(PrimitiveValue.wrap(50), Reference.thread(10), new Location(Reference.classType(1), Reference.method(1), wrap(1L))))))));
        state.addRequest(wp(new jdwp.ThreadReferenceCmds.FrameCountRequest(16196, new ThreadReference(1L))));
        state.addRequest(wp(new jdwp.ThreadReferenceCmds.NameRequest(16197, new ThreadReference(1L))));
        state.addReply(wpr(new jdwp.ThreadReferenceCmds.FrameCountReply(16196, PrimitiveValue.wrap(1))));
        state.addReply(wpr(new jdwp.ThreadReferenceCmds.NameReply(16197, PrimitiveValue.wrap("main"))));
        partitioner.close();
        assertEquals(2, partitions.size());
        assertTrue(partitions.get(0).hasCause());
        assertTrue(partitions.get(1).hasCause());
        assertEquals(1, partitions.get(0).size());
        assertEquals(2, partitions.get(1).size());
    }

    @Test
    public void testSplitAfterError() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner().addListener(partitions::add);
        var state = new State().addListener(partitioner);
        state.addRequest(wp(new jdwp.ThreadReferenceCmds.ResumeRequest(16193, new ThreadReference(1L))));
        state.addReply(wpr(new jdwp.ThreadReferenceCmds.ResumeReply(16193)));
        state.addRequest(wp(new NameRequest(16197, new ThreadReference(1L))));
        state.addReply(wpr(new NameReply(16197, PrimitiveValue.wrap("main"))));
        // reply like error
        var sdbg = new SourceDebugExtensionRequest(16198, Reference.klass(1));
        state.addRequest(wp(sdbg));
        state.addReply(new WrappedPacket<>(new ReplyOrError<>(sdbg, (short) Error.ABSENT_INFORMATION)));
        assertEquals(0, partitions.size());
        state.addRequest(wp(sdbg));
        state.addReply(new WrappedPacket<>(new ReplyOrError<>(sdbg, (short)Error.VM_DEAD)));
        partitioner.close();
        assertEquals(1, partitions.size());
        assertEquals(2, partitions.get(0).size());
    }

    private void addRequest(State state, int id, long time) {
        state.addRequest(new WrappedPacket<>(new jdwp.VirtualMachineCmds.IDSizesRequest(id), time));
        state.addReply(new WrappedPacket<>(new ReplyOrError<Reply>(
                new IDSizesReply(id, wrap(1), wrap(2), wrap(2), wrap(3), wrap(3))), time));
    }

    @Test
    public void testSplitPartitionAfterTime() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner(new Timings(5, 1)).addListener(partitions::add);
        var state = new State().addListener(partitioner);
        addRequest(state, 10, 100);
        addRequest(state, 11, 110);
        addRequest(state, 12, 120); // average time is 10
        addRequest(state, 13, 171);
        partitioner.close();
        assertEquals(2, partitions.size());
        assertEquals(3, partitions.get(0).size());
        assertEquals(1, partitions.get(1).size());
    }

    @Test
    public void testSplitPartitionAfterIdleTime() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner().addListener(partitions::add);
        var state = new State().addListener(partitioner);
        addRequest(state, 10, 100);
        addRequest(state, 11, 101);
        addRequest(state, 12, 102); // average time is 1
        partitioner.onTick();
        assertEquals(1, partitions.size());
        assertEquals(3, partitions.get(0).size());
    }

    @Test
    public void testSplitPartitionAfterIdleTime2() {
        List<Partition> partitions = new ArrayList<>();
        var partitioner = new Partitioner(new Timings(5, 1, new Clock() {
            @Override
            public ZoneId getZone() {
                return null;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return null;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(110);
            }
        })).addListener(partitions::add);
        var state = new State().addListener(partitioner);
        addRequest(state, 10, 100);
        addRequest(state, 11, 101);
        addRequest(state, 12, 102); // average time is 1
        partitioner.onTick();
        addRequest(state, 13, 120);
        partitioner.close();
        assertEquals(2, partitions.size());
        assertEquals(3, partitions.get(0).size());
    }
}
