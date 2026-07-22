package org.lstarrocq.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolTest {

    @Test
    void roundTripsConfig() {
        String line = Protocol.configLine(List.of("0", "1"), "alternating");
        assertTrue(Protocol.isConfig(line));
        Protocol.Config cfg = Protocol.parseConfig(line).orElseThrow();
        assertEquals(List.of("0", "1"), cfg.alphabet());
        assertEquals("alternating", cfg.target());
    }

    @Test
    void recognizesDoneAndAck() {
        assertTrue(Protocol.isDone(Protocol.DONE_LINE));
        assertFalse(Protocol.isAck(Protocol.DONE_LINE));
        assertTrue(Protocol.isAck(Protocol.ACK_LINE));
        assertFalse(Protocol.isDone(Protocol.ACK_LINE));
    }

    @Test
    void mqRequestCarriesWord() {
        String line = Protocol.mqRequest(List.of("0", "1", "0"));
        assertTrue(Protocol.isMembershipQuery(line));
        assertEquals(List.of("0", "1", "0"), Protocol.wordOf(line));
    }

    @Test
    void mqRequestHandlesEmptyWord() {
        String line = Protocol.mqRequest(List.of());
        assertEquals(List.of(), Protocol.wordOf(line));
    }

    /** Two states, self-loop on "0"/accepting init, single transition to a rejecting
     * state on "1" -- exercises eqRequest against parseHypothesis's inverse. */
    @Test
    void eqRequestRoundTripsThroughParseHypothesis() {
        Map<Integer, Boolean> accept = Map.of(0, true, 1, false);
        Map<Integer, Map<String, Integer>> trans =
                Map.of(0, Map.of("0", 0, "1", 1), 1, Map.of("0", 1, "1", 1));
        String json =
                Protocol.eqRequest(
                        List.of(0, 1),
                        0,
                        accept::get,
                        (s, sym) -> trans.get(s).get(sym),
                        List.of("0", "1"));
        assertTrue(Protocol.isEq(json));
        Protocol.Hypothesis hyp = Protocol.parseHypothesis(json);
        assertEquals(0, hyp.initialState());
        assertEquals(2, hyp.numStates());
        assertEquals(List.of(0), hyp.acceptingIds());
        assertEquals(4, hyp.transitions().size());
    }

    @Test
    void alphabetArrayParsesInOrder() {
        String json = "{\"type\":\"config\",\"alphabet\":[\"0\",\"1\",\"2\"],\"target\":\"t\"}";
        assertEquals(List.of("0", "1", "2"), Protocol.parseConfig(json).orElseThrow().alphabet());
    }
}
