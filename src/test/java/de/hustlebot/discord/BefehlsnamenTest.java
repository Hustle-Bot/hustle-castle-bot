package de.hustlebot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Befehlsnamen (Fassung 3.1, Autor 11.10.): der erste Name jeder Gruppe ist englisch, die deutschen
 * Schreibweisen bleiben gueltig und landen still beim englischen Namen.
 */
class BefehlsnamenTest {

    /** Die englischen Namen, die DiscordBridge.befehl als "case" kennt -- genau einer je Gruppe. */
    private static final Set<String> ENGLISCH = Set.of(
            "!help", "!start", "!stop", "!castle", "!pause", "!resume", "!status", "!after", "!clanwar",
            "!settings", "!screenshot", "!cleaner", "!stats", "!log", "!off", "!specs", "!live",
            "!mute", "!unmute", "!update", "!shortcut", "!clear", "!queue", "!end", "!english", "!text",
            "!deutsch", "!pc", "!titan", "!dig", "!stigmas", "!arenareserve", "!arenareport",
            "!notes", "!note", "!list", "!emergency",
            // 16.10. aus dem Hauptbot-Steuerprogramm
            "!restart", "!timezone", "!autostart",
            // 21.10. aus dem Hauptbot
            "!watch", "!tower", "!clanstore", "!game", "!event", "!arena",
            // 22.10. aus dem Hauptbot
            "!friends",
            // 22.10. abends aus dem Hauptbot
            "!gembay", "!portal", "!foodchests",
            // 24.10.
            "!glyphs", "!glyph",
            // 25.11.
            "!throneroom", "!arenacathedral");

    @Test
    void ersterNameJederGruppeIstEnglisch() {
        for (List<String> gruppe : Befehlsnamen.BEFEHLE) {
            assertTrue(ENGLISCH.contains(gruppe.get(0)), "nicht englisch vorn: " + gruppe);
        }
        assertEquals(ENGLISCH.size(), Befehlsnamen.BEFEHLE.size(), "jede Gruppe genau einmal");
    }

    @Test
    void deutscheSchreibweisenBleibenGueltig() {
        assertEquals("!help", Befehlsnamen.kanonisch("!hilfe"));
        assertEquals("!stop", Befehlsnamen.kanonisch("!Stopp"));
        assertEquals("!castle", Befehlsnamen.kanonisch("!burg"));
        assertEquals("!castle", Befehlsnamen.kanonisch("!home"));
        assertEquals("!resume", Befehlsnamen.kanonisch("!weiter"));
        assertEquals("!resume", Befehlsnamen.kanonisch("!weitermachen"));
        assertEquals("!settings", Befehlsnamen.kanonisch("!einstellungen"));
        assertEquals("!screenshot", Befehlsnamen.kanonisch("!bild"));
        assertEquals("!cleaner", Befehlsnamen.kanonisch("!reiniger"));
        assertEquals("!log", Befehlsnamen.kanonisch("!protokoll"));
        assertEquals("!off", Befehlsnamen.kanonisch("!feierabend"));
        assertEquals("!shortcut", Befehlsnamen.kanonisch("!kürzel"));
        assertEquals("!end", Befehlsnamen.kanonisch("!ende"));
        assertEquals("!dig", Befehlsnamen.kanonisch("!graben"));
        assertEquals("!arenareport", Befehlsnamen.kanonisch("!arenabericht"));
        assertEquals("!notes", Befehlsnamen.kanonisch("!notizen"));
        assertEquals("!note", Befehlsnamen.kanonisch("!notiz"));
        assertEquals("!list", Befehlsnamen.kanonisch("!liste"));
        assertEquals("!emergency", Befehlsnamen.kanonisch("!notaus"));
        assertEquals("!emergency", Befehlsnamen.kanonisch("!not-aus"));
        assertEquals("!deutsch", Befehlsnamen.kanonisch("!german"));
        assertEquals("!english", Befehlsnamen.kanonisch("!englisch"));
        assertEquals("!watch", Befehlsnamen.kanonisch("!wach"));
        assertEquals("!watch", Befehlsnamen.kanonisch("!keepawake"));
        assertEquals("!tower", Befehlsnamen.kanonisch("!turm"));
        assertEquals("!tower", Befehlsnamen.kanonisch("!titanturm"));
        assertEquals("!clanstore", Befehlsnamen.kanonisch("!clanladen"));
        assertEquals("!clanstore", Befehlsnamen.kanonisch("!clanshop"));
        assertEquals("!game", Befehlsnamen.kanonisch("!spiel"));
        assertEquals("!game", Befehlsnamen.kanonisch("!hc"));
        assertEquals("!arena", Befehlsnamen.kanonisch("!gegner"));
        assertEquals("!arena", Befehlsnamen.kanonisch("!opponent"));
    }

    /** "!power" und "!gam" duerfen nie ohne Nachfrage den Titanen-Turm bzw. das Spiel starten. */
    @Test
    void tippfehlerBeiModusStartsFragenNach() {
        Befehlsnamen.Treffer g = Befehlsnamen.suchen("!gam");
        assertTrue(g != null && g.befehl().equals("!game") && g.fragen());
        Befehlsnamen.Treffer p = Befehlsnamen.suchen("!power");
        assertTrue(p != null && p.befehl().equals("!tower") && p.fragen());
    }

    @Test
    void englischeSchreibweisenBleibenSieSelbst() {
        for (String name : ENGLISCH) {
            assertEquals(name, Befehlsnamen.kanonisch(name));
        }
        assertEquals("!resume", Befehlsnamen.kanonisch("!continue"));
        assertEquals("!off", Befehlsnamen.kanonisch("!shutdown"));
    }

    @Test
    void unbekanntesBleibtUnbekannt() {
        assertEquals("!foo", Befehlsnamen.kanonisch("!foo"));
        assertEquals(null, Befehlsnamen.kanonisch(null));
    }

    @Test
    void gefaehrlicheBefehleSindErsteNamen() {
        for (String g : Befehlsnamen.GEFAEHRLICH) {
            assertTrue(Befehlsnamen.BEFEHLE.stream().anyMatch(gruppe -> gruppe.get(0).equals(g)),
                    g + " ist kein erster Name einer Gruppe");
        }
    }
}
