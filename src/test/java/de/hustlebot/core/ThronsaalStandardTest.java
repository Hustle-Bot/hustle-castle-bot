package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** !thronsaal (Nutzer 25.11.): Stufe von Hand, auch ohne bekannten Kontonamen. */
class ThronsaalStandardTest {

    private final Path vorher = Thronsaal.DATEI;

    @AfterEach
    void zurueck() {
        Thronsaal.DATEI = vorher;
        Thronsaal.aktuellSetzen(-1);
    }

    @Test
    void ohneKontonameGiltDerStandard(@TempDir Path dir) {
        Thronsaal.DATEI = dir.resolve("thronsaal.txt");
        assertEquals(-1, Thronsaal.stufe(""));
        Thronsaal.vonHandSetzen("", 8);
        assertEquals(8, Thronsaal.aktuell());
        assertEquals(8, Thronsaal.stufe(""));
        assertEquals(8, Thronsaal.stufe("Irgendwer"));   // unbekanntes Konto -> Standard
        // Verlies gibt es auf TS8 nicht
        assertNotNull(Thronsaal.gesperrtGrund("dungeon", "Irgendwer"));
        assertNull(Thronsaal.gesperrtGrund("cathedral", "Irgendwer"));
    }

    @Test
    void kontoGehtVorStandard(@TempDir Path dir) {
        Thronsaal.DATEI = dir.resolve("thronsaal.txt");
        Thronsaal.vonHandSetzen("", 8);
        Thronsaal.vonHandSetzen("Hauptkonto", 11);
        assertEquals(11, Thronsaal.stufe("Hauptkonto"));
        assertEquals(8, Thronsaal.stufe("Zweitkonto"));
        Thronsaal.merken("Hauptkonto", 12);
        assertEquals(12, Thronsaal.stufe("Hauptkonto"));
        assertEquals(8, Thronsaal.stufe(""));   // Standard bleibt beim Merken erhalten
    }
}
