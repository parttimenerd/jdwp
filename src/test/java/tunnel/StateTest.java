package tunnel;

import com.google.common.base.Strings;
import jdwp.EventCmds.Events;
import jdwp.Reply;
import jdwp.ReplyOrError;
import jdwp.Request;
import jdwp.VirtualMachineCmds.VersionReply;
import jdwp.VirtualMachineCmds.VersionRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tunnel.Listener.CollectingListener;
import tunnel.State.Formatter;
import tunnel.State.NoSuchRequestException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the state without opening real ports */
public class StateTest {

    static abstract class CollectedPacketsTest {
        final State state = new State();
        final CollectingListener collectingListener = new CollectingListener();
        {
            state.addListener(collectingListener);
        }
        final List<Request<?>> requests = new ArrayList<>();
        final List<ReplyOrError<?>> replies = new ArrayList<>();
        final List<Events> events = new ArrayList<>();

        @BeforeAll
        public abstract void init() throws IOException;

        Request<?> addRequest(Request<?> request) throws IOException {
            state.readRequest(request.toPacket(state.vm()).toInputStream(state.vm()));
            requests.add(request);
            return request;
        }

        Reply addReply(Reply reply) throws IOException {
            state.readReply(reply.toPacket(state.vm()).toInputStream(state.vm()), null, null);
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
            assertThrows(NoSuchRequestException.class, () -> addReply(new VersionReply(10, wrap("a"), wrap(1), wrap(1), wrap("b"), wrap("c"))));
        }
    }

    @Test
    public void testFormatterWithLongLines() {
        IntStream.range(0, 500).mapToObj(i -> Strings.repeat("a", i)).forEach(s -> {
            assertTrue(Formatter.cut(s).length() <= Formatter.MAX_LENGTH);
        });
    }
}
