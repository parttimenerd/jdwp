package tunnel.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.AST.SwitchStatement;
import tunnel.synth.program.Program;

import static org.junit.jupiter.api.Assertions.*;

public class ProgramHashesTest {

    @ParameterizedTest
    @CsvSource({
            "(= var1 (request ThreadReference Name (\"thread\")=(get 1 \"events\" 0 \"thread\")))," +
            "(= var0 (request ThreadReference FrameCount (\"thread\")=(get 1 \"events\" 0 \"thread\"))) ",
            "(= ret (func)),(= ret (func2))"
    })
    public void testNoCollision(String first, String second) {
        ProgramHashes hashes = new ProgramHashes();
        assertNotEquals(hashes.create(Statement.parse(first)).hash(), hashes.create(Statement.parse(second)).hash());
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret2 1)), ((= ret 1))",
            "((= ret2 1) (= y ret2)), ((= ret 1) (= x ret))",
            "((switch 1 (case \"a\"))),((switch 1 (case \"b\")))"
    })
    public void testHashesOfLastStatementEqual(String firstProgram, String secondProgram) {
        var first = Program.parse(firstProgram);
        var second = Program.parse(secondProgram);
        assertEquals(first.getHashes().get(first.getBody().getLastStatement()),
                second.getHashes().get(second.getBody().getLastStatement()));
    }

    @Test
    public void testHashesShouldContainSwitchSelf() {
        var program = Program.parse("((= x 1) (switch x (case 'a') (case 'b')))");
        var switchStatement = (SwitchStatement)program.getBody().getLastStatement();
        var caseStatement = switchStatement.getCases().get(0);
        var caseStatement2 = switchStatement.getCases().get(1);
        assertTrue(program.getHashes().contains(switchStatement));
        assertTrue(switchStatement.getHashes().contains(switchStatement));
        assertTrue(caseStatement.getHashes().contains(caseStatement));
        assertTrue(caseStatement2.getHashes().contains(caseStatement2));
    }
}