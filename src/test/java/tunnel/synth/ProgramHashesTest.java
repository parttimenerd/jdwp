package tunnel.synth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ProgramHashesTest {

    @ParameterizedTest
    @CsvSource({" (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\")))," +
            "(= var0 (request ThreadReference FrameCount (\"thread\")=(get cause \"events\" 0 \"thread\"))) ",
            "(= ret (func)),(= ret (func2))"})
    public void testNoCollision(String first, String second) {
        ProgramHashes hashes = new ProgramHashes();
        assertNotEquals(hashes.create(Statement.parse(first)).hash(), hashes.create(Statement.parse(second)).hash());
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret2 func)), ((= ret func))",
            "((= ret2 func) (= y ret2)), ((= ret func) (= x ret))"
    })
    public void testHashesOfLastStatementEqual(String firstProgram, String secondProgram) {
        var first = Program.parse(firstProgram);
        var second = Program.parse(secondProgram);
        assertEquals(first.getHashes().get(first.getBody().getLastStatement()),
                second.getHashes().get(second.getBody().getLastStatement()));
    }
}