package tunnel;

import tunnel.synth.program.Program;

/** A sample program for testing the tunnel */
public class EndlessLoop {
    public static void main(String[] args) {
        String s = "isdf";
        int i = 0;
        int j = i + 1;
        i++;
        Program.parse("((= i 0))");
        while (true) {
            i++;
        }
    }
}
