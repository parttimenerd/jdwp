package tunnel.agent;

import org.junit.jupiter.api.Test;

public class TunnelStartingAgentTest {

    @Test
    public void testParseMultiple() {
        TunnelStartingAgent.prepareArguments("verbose=debug,logger,mode=server:address=5015,verbose=debug,logger,mode=client", "6101");
    }
}
