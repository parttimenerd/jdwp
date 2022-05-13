package tunnel.synth;

import jdwp.EventCmds.Events.VMStart;
import jdwp.Reference.ClassReference;
import jdwp.Reference.MethodReference;
import jdwp.Reference.ThreadReference;
import jdwp.ReplyOrError;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.IDSizesReply;
import org.junit.jupiter.api.Test;
import tunnel.State;
import tunnel.synth.Partitioner;
import tunnel.synth.Partitioner.Partition;
import tunnel.State.WrappedPacket;

import java.util.ArrayList;
import java.util.List;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class PartitionerTest {

    private static <R> WrappedPacket<R> wp(R packet) {
        return new WrappedPacket<>(packet, 10);
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
        partitioner.onRequest(sizesRequest);
        partitioner.onReply(sizesRequest, sizesReply);
        partitioner.close();
        assertEquals(List.of(new Partition(List.of(p(sizesRequest, sizesReply)))), partitions);
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
        assertFalse(partition.hasCause());
        assertEquals(2, partition.size());
    }
}
