package tunnel;

import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.Request;
import tunnel.synth.program.AST.PacketCall;
import tunnel.synth.program.AST.RequestCall;
import tunnel.synth.program.Program;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Similar to {@link tunnel.synth.ProgramCollection} with the aim to cache the most recent program for a given
 * cause
 */
public class ProgramCache implements Consumer<Program> {

    public static enum Mode {
        /** always use the last cached program */
        LAST  // we currently only support this mode, it is the simplest
        // a possible mode would be to merge the last 5 cached programs
        // or to look for other events with nearby locations for events
    }

    private final Mode mode;
    private final Map<PacketCall, Program> causeToProgram;

    /** minimal number assignment in a program to be added */
    private final int minSize = 2;

    public ProgramCache(Mode mode) {
        this.mode = mode;
        this.causeToProgram = new HashMap<>();
        assert mode == Mode.LAST;
    }

    @Override
    public void accept(Program program) {
        if (program.getNumberOfAssignments() >= minSize) {
            add(program);
        }
    }

    public void add(Program program) {
        assert program.getFirstCallAssignment() != null;
        var expression = program.getFirstCallAssignment().getExpression();
        assert expression instanceof PacketCall;
        causeToProgram.put((PacketCall) expression, program);
    }

    private Optional<Program> get(PacketCall packetCall) {
        return Optional.ofNullable(causeToProgram.get(packetCall));
    }

    public Optional<Program> get(Request<?> request) {
        return get(RequestCall.create(request));
    }

    public Optional<Program> get(Events events) {
        return get(RequestCall.create(events));
    }

    public Optional<Program> get(ParsedPacket packet) {
        if (packet instanceof Events) {
            return get((Events) packet);
        }
        if (packet instanceof Request<?>) {
            return get((Request<?>) packet);
        }
        return Optional.empty();
    }
}
