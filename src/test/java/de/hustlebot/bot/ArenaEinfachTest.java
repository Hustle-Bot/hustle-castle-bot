package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.hustlebot.bot.ArenaEinfach.Bewertung;
import de.hustlebot.bot.ArenaEinfach.Kampf;
import de.hustlebot.bot.ArenaEinfach.Kandidat;
import de.hustlebot.bot.ArenaEinfach.Urteil;

/** TS8-Arena (Nutzer 25.11.): Bilanz statt Kraft, 2 von 3 = schaffbar, 5 % mehr eigene Kraft = neu bewerten. */
class ArenaEinfachTest {

    private static Kampf s(long kraft) { return new Kampf(true, kraft, ""); }
    private static Kampf n(long kraft) { return new Kampf(false, kraft, ""); }

    @AfterEach
    void aufraeumen() {
        ArenaEinfach.DATEI = null;
        ArenaEinfach.SCHALTER = null;
        ArenaEinfach.vergessen();
        ArenaEinfach.neuLesen();
    }

    @Test
    void punkteAufTs8() {
        assertEquals(100, ArenaEinfach.lohn(1, 150));   // Runde 1 hoechstens 100
        assertEquals(100, ArenaEinfach.lohn(1, 0));
        assertEquals(100, ArenaEinfach.lohn(2, 100));   // unter mir
        assertEquals(150, ArenaEinfach.lohn(3, 150));   // ueber mir
        assertEquals(150, ArenaEinfach.lohn(3, 225));   // verlesen -> gedeckelt
        assertEquals(100, ArenaEinfach.lohn(4, 0));     // nicht gelesen
        assertFalse(ArenaEinfach.ueberMir(1, 150));
        assertTrue(ArenaEinfach.ueberMir(2, 150));
        assertFalse(ArenaEinfach.ueberMir(2, 100));
    }

    @Test
    void zweiVonDreiIstSchaffbar() {
        assertEquals(Urteil.SCHAFFBAR, ArenaEinfach.bewerten(List.of(s(1), n(1), s(1))).urteil());
        assertEquals(Urteil.SCHAFFBAR, ArenaEinfach.bewerten(List.of(s(1), s(1), s(1))).urteil());
        assertEquals(Urteil.NICHT_SCHAFFBAR, ArenaEinfach.bewerten(List.of(n(1), n(1), s(1))).urteil());
        assertEquals(Urteil.UNBEKANNT, ArenaEinfach.bewerten(List.of(s(1), s(1))).urteil());
        assertEquals(Urteil.UNBEKANNT, ArenaEinfach.bewerten(List.of()).urteil());
        assertEquals(0.5, ArenaEinfach.bewerten(List.of()).chance(), 1e-9);
    }

    @Test
    void nurDieNeuestenDreiZaehlen() {
        // aelteste zuerst: drei alte Niederlagen, dann drei Siege
        List<Kampf> alle = List.of(n(-1), n(-1), n(-1), s(-1), s(-1), s(-1));
        Bewertung b = ArenaEinfach.bewerten(ArenaEinfach.gueltige(alle, -1));
        assertEquals(Urteil.SCHAFFBAR, b.urteil());
        assertEquals(3, b.siege());
    }

    @Test
    void fuenfProzentMehrKraftMachtAlteKaempfeUngueltig() {
        List<Kampf> alle = List.of(n(1_000_000), n(1_000_000), n(1_000_000));
        // 4 % mehr: zaehlt noch
        assertEquals(Urteil.NICHT_SCHAFFBAR, ArenaEinfach.bewerten(ArenaEinfach.gueltige(alle, 1_040_000)).urteil());
        // 5 % mehr: neu bewerten
        assertEquals(Urteil.UNBEKANNT, ArenaEinfach.bewerten(ArenaEinfach.gueltige(alle, 1_050_000)).urteil());
        // eigene Kraft unbekannt: zaehlt
        assertEquals(Urteil.NICHT_SCHAFFBAR, ArenaEinfach.bewerten(ArenaEinfach.gueltige(alle, -1)).urteil());
    }

    @Test
    void nachDemKraftsprungZaehlenNurDieNeuenKaempfe() {
        List<Kampf> alle = List.of(n(1_000_000), n(1_000_000), s(1_100_000), s(1_100_000));
        Bewertung b = ArenaEinfach.bewerten(ArenaEinfach.gueltige(alle, 1_100_000));
        assertEquals(2, b.kaempfe());
        assertEquals(Urteil.UNBEKANNT, b.urteil());
    }

    private static Kandidat k(String name, long punkte, List<Kampf> kaempfe) {
        return new Kandidat(name, punkte, ArenaEinfach.bewerten(kaempfe));
    }

    @Test
    void schaffbarUeberMirSchlaegtSchaffbarUnterMir() {
        List<Kandidat> k = List.of(
                k("unten", 100, List.of(s(1), s(1), s(1))),
                k("oben", 150, List.of(s(1), s(1), n(1))));
        assertEquals(1, ArenaEinfach.wahl(k, 3));
    }

    @Test
    void runde1NimmtDieHoechsteChance() {
        List<Kandidat> k = List.of(
                k("neu", 150, List.of()),
                k("sicher", 150, List.of(s(1), s(1), s(1))),
                k("stark", 150, List.of(n(1), n(1), n(1))));
        assertEquals(1, ArenaEinfach.wahl(k, 1));
    }

    @Test
    void nichtSchaffbarNurWennSonstNichtsDa() {
        assertEquals(0, ArenaEinfach.wahl(List.of(k("stark", 150, List.of(n(1), n(1), n(1)))), 2));
        List<Kandidat> k = List.of(
                k("stark", 150, List.of(n(1), n(1), n(1))),
                k("neu", 100, List.of()));
        assertEquals(1, ArenaEinfach.wahl(k, 2));
        assertEquals(-1, ArenaEinfach.wahl(List.of(), 2));
    }

    @Test
    void gleichstandNimmtDenUnbekannten() {
        // beide 50 %: einer mit 1:1, einer ohne Kampf
        List<Kandidat> k = List.of(
                k("einmal", 100, List.of(s(1), n(1))),
                k("neu", 100, List.of()));
        assertEquals(1, ArenaEinfach.wahl(k, 2));
    }

    @Test
    void dateiMerktKaempfeUndErkenntVerleseneNamen(@TempDir Path dir) throws Exception {
        ArenaEinfach.DATEI = dir.resolve("arena-ts8.txt");
        ArenaEinfach.vergessen();
        ArenaEinfach.eintragen("Koroleva", true, 1_000_000);
        ArenaEinfach.eintragen("Koroleva", false, 1_000_000);
        ArenaEinfach.eintragen("Korolewa", true, 1_000_000);   // verlesen -> derselbe
        ArenaEinfach.vergessen();                                // neu aus der Datei
        Bewertung b = ArenaEinfach.bewertung("Koroleva", 1_000_000);
        assertEquals(3, b.kaempfe());
        assertEquals(Urteil.SCHAFFBAR, b.urteil());
        assertTrue(Files.readString(ArenaEinfach.DATEI).contains("koroleva\tKoroleva\tS\t1000000\t"));
    }

    @Test
    void hoechstensZehnKaempfeJeGegner(@TempDir Path dir) {
        ArenaEinfach.DATEI = dir.resolve("arena-ts8.txt");
        ArenaEinfach.vergessen();
        for (int i = 0; i < 15; i++) ArenaEinfach.eintragen("Hobbit", i % 2 == 0, 1);
        assertEquals(ArenaEinfach.MERKT_SICH, ArenaEinfach.kaempfe("Hobbit").size());
    }

    @Test
    void schalterUndStufe(@TempDir Path dir) throws Exception {
        assertTrue(ArenaEinfach.aktivBeiStufe(8));
        assertFalse(ArenaEinfach.aktivBeiStufe(11));
        assertFalse(ArenaEinfach.aktivBeiStufe(-1));
        ArenaEinfach.SCHALTER = dir.resolve("arena-einfach.txt");
        Files.writeString(ArenaEinfach.SCHALTER, "# test\nan\n");
        ArenaEinfach.neuLesen();
        assertTrue(ArenaEinfach.aktiv());
        Files.writeString(ArenaEinfach.SCHALTER, "aus\n");
        ArenaEinfach.neuLesen();
        assertFalse(ArenaEinfach.aktiv());
    }
}
