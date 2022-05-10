package tunnel;

import jdwp.*;
import jdwp.EventCmds.Events;
import jdwp.VirtualMachineCmds.VersionReply;
import jdwp.VirtualMachineCmds.VersionRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tunnel.State.CollectingListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests the state without opening real ports */
public class StateTest {

    static abstract class CollectedPacketsTest {
        State state = new State();
        CollectingListener collectingListener = new CollectingListener();
        {
            state.addListener(collectingListener);
        }
        List<Request<?>> requests = new ArrayList<>();
        List<ReplyOrError<?>> replies = new ArrayList<>();
        List<Events> events = new ArrayList<>();

        @BeforeAll
        public abstract void init() throws IOException;

        Request<?> addRequest(Request<?> request) throws IOException {
            state.readRequest(request.toPacket(state.vm()).toInputStream(state.vm()));
            requests.add(request);
            return request;
        }

        Reply addReply(Reply reply) throws IOException {
            state.readReply(reply.toPacket(state.vm()).toInputStream(state.vm()));
            replies.add(new ReplyOrError<>(reply));
            return reply;
        }

        @Test
        public void checkCollectedPackets() {
            assertEquals(requests, collectingListener.getRequests());
            assertEquals(replies, collectingListener.getReplies());
            assertEquals(events, collectingListener.getEvents());
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class BasicRequestAndReplyTest extends CollectedPacketsTest {

        @Override
        @BeforeAll
        public void init() throws IOException {
            addRequest(new VersionRequest(10));
            addReply(new VersionReply(10, wrap("a"), wrap(1), wrap(1), wrap("b"), wrap("c")));
        }
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class MissingRequestForReplyTest extends CollectedPacketsTest {

        @Override
        @Test
        public void init() throws IOException {
            addRequest(new VersionRequest(1));
            assertThrows(NoSuchElementException.class, () -> addReply(new VersionReply(10, wrap("a"), wrap(1), wrap(1), wrap("b"), wrap("c"))));
        }
    }
}
