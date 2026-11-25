package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Kathedrale in der Arena-Wartezeit (Nutzer 25.11.): wann, wie lange, und die Rundenuhr danach. */
class ArenaKathedraleAusflugTest {

    @Test
    void nurNachStage1Bis4MitSchalter() {
        assertTrue(ArenaMode.kathedraleErlaubt(true, 1, false, true, false, false));
        assertTrue(ArenaMode.kathedraleErlaubt(true, 4, false, true, false, false));
        assertFalse(ArenaMode.kathedraleErlaubt(true, 5, false, true, false, false));    // danach endet das Turnier
        assertFalse(ArenaMode.kathedraleErlaubt(false, 2, false, true, false, false));   // Schalter aus
        assertFalse(ArenaMode.kathedraleErlaubt(true, 2, true, true, false, false));     // Kathedrale leer
        assertFalse(ArenaMode.kathedraleErlaubt(true, 2, false, false, false, false));   // unter Thronsaal 6
        assertFalse(ArenaMode.kathedraleErlaubt(true, 2, false, true, true, false));     // Event-Kreislauf
        assertFalse(ArenaMode.kathedraleErlaubt(true, 2, false, true, false, true));     // Nachspiel
    }

    @Test
    void fristIstEineMinuteUeberzeitMinusRueckweg() {
        assertEquals(60_000L, ArenaMode.AUSFLUG_UEBERZEIT_MS);
        assertEquals(1_000_000L + 60_000L - 20_000L, ArenaMode.kathedraleFrist(1_000_000L));
    }

    @Test
    void verspaeteterRundenwechselNimmtDasBekannteRundenende() {
        long jetzt = 10_000_000L;
        assertEquals(jetzt - 50_000, ArenaMode.rundeBegannUm(jetzt - 50_000, jetzt));
        assertEquals(jetzt, ArenaMode.rundeBegannUm(0, jetzt));                // unbekannt
        assertEquals(jetzt, ArenaMode.rundeBegannUm(jetzt + 5_000, jetzt));    // liegt in der Zukunft
        assertEquals(jetzt, ArenaMode.rundeBegannUm(jetzt - 200_000, jetzt));  // zu alt, verlesen
    }
}
