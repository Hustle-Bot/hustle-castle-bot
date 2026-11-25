package de.hustlebot.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tippfehler in Befehlen erkennen (aus dem Hauptbot, 28.09.).
 *
 * Gemessen wird, wie viele Buchstaben fehlen, zu viel sind, falsch sind oder
 * vertauscht wurden (Damerau-Levenshtein). Grossschreibung, Umlaute (ue/ü)
 * und Bindestriche spielen keine Rolle.
 *
 * Regeln:
 *  - Eindeutig und harmlos: wird direkt ausgefuehrt ("Verstanden als ...").
 *  - Zwei Moeglichkeiten -- oder ein Befehl, der etwas abschaltet (!aus,
 *    !stop, !notaus ...): der Bot schlaegt nur vor. Ein Tippfehler darf nie
 *    den PC ausschalten.
 *  - Sehr kurze Woerter (1-2 Buchstaben) nur, wenn sie genau passen.
 *
 * ACHTUNG: Die erste Schreibweise jeder Gruppe muss in DiscordBridge.befehl
 * als "case" stehen.
 *
 * Seit 11.10. (Hauptbot, Autor: "jeden Befehl im Discord auf Englisch, auf Deutsch und mit verschiedenen
 * Schreibweisen"): jede Schreibweise wird schon vor der Verteilung still zum ersten Namen der Gruppe
 * ({@link #kanonisch}). Gruppen fuer Befehle, die nur der Hauptbot hat (!gembay, !freunde, !beute, !tickets ...),
 * fehlen hier absichtlich -- sonst wuerden sie auf einen unbekannten Befehl umgestellt. Seit 21.10. gibt es aus dem
 * Hauptbot auch !watch, !tower, !clanstore, !game, !event und !arena.
 *
 * Fassung 3.1 (Autor 11.10. abends, "das ganze Programm auf Englisch, mit allen Commands"): der ERSTE Name
 * jeder Gruppe ist englisch -- er steht in der Hilfe vorn und in den Vorschlaegen bei Tippfehlern. Die
 * deutschen Schreibweisen bleiben alle gueltig (BefehlsnamenTest prueft beides).
 */
public final class Befehlsnamen {

    private Befehlsnamen() {}

    /** Je Befehl alle Schreibweisen; die erste (englisch) ist der Name, den befehl() kennt. */
    static final List<List<String>> BEFEHLE = List.of(
            List.of("!help", "!hilfe", "!befehle", "!commands"),
            List.of("!start", "!starte", "!starten", "!begin", "!beginne", "!los"),
            List.of("!stop", "!stopp", "!halt", "!anhalten"),
            List.of("!castle", "!home", "!burg"),
            List.of("!pause", "!pausieren", "!pausiere"),
            List.of("!resume", "!continue", "!weiter", "!fortsetzen", "!fortfahren", "!weitermachen"),
            List.of("!status", "!state", "!stand", "!zustand"),
            // 16.10.: Gruppen fuer die Knoepfe aus dem Hauptbot-Steuerprogramm (Rechte und Rueckfrage greifen so
            // fuer jede Schreibweise).
            List.of("!restart", "!neustart"),
            List.of("!timezone", "!zeitzone"),
            List.of("!autostart"),
            List.of("!after", "!danach", "!dann"),
            List.of("!clanwar", "!clankrieg", "!krieg"),
            List.of("!settings", "!options", "!setting", "!config", "!einstellungen", "!einstellung", "!aufstellung",
                    "!optionen", "!konfig"),
            List.of("!screenshot", "!screen", "!pic", "!photo", "!sceeenshot", "!bild", "!foto", "!bildschirmfoto"),
            List.of("!cleaner", "!reiniger"),
            // Autor 22.10. abends: aus dem Hauptbot uebernommen.
            List.of("!gembay", "!edelsteinbucht", "!gembucht"),   // nicht "!gem": Tippfehler fuer "!game"
            List.of("!portal"),
            List.of("!foodchests", "!nahrungskisten"),
            List.of("!stats", "!statistik"),
            List.of("!log", "!report", "!bericht", "!protokoll"),
            List.of("!off", "!shutdown", "!aus", "!feierabend", "!ausschalten", "!herunterfahren"),
            List.of("!specs", "!hardware", "!werte", "!rechnerwerte"),
            List.of("!live", "!liveview", "!livebild"),
            List.of("!mute", "!stumm"),
            List.of("!unmute", "!laut"),
            List.of("!update", "!updates", "!changelog", "!whatsnew", "!neu", "!neuerungen", "!neuigkeiten"),
            List.of("!shortcut", "!shortcuts", "!kuerzel"),
            List.of("!clear", "!leeren", "!aufraeumen", "!putzen"),   // kein "!clean": zu nah an !cleaner (Freunde 11.10.)
            List.of("!queue", "!wq", "!warteschlange", "!kette"),
            List.of("!end", "!ende", "!schluss"),
            List.of("!english", "!englisch", "!en"),
            List.of("!text", "!plain", "!nurtext"),
            List.of("!deutsch", "!german", "!de"),
            List.of("!pc", "!rechner"),
            List.of("!titan", "!excavation", "!grabung"),      // Titan-Grabung (Hauptbot 04.10., Freunde 10.10.)
            List.of("!dig", "!digging", "!graben", "!titangraben", "!titanstart", "!ausgraben"), // nur den Titan-Ablauf starten
            List.of("!stigmas", "!stigma", "!stigmata", "!stigmen"),   // !stigmas loeschen/leveln/einstellen (Hauptbot 10./11.10.)
            // Arena (Hauptbot, Freunde 3.0)
            List.of("!arenareserve", "!reserve", "!arenatickets", "!ticketreserve"),
            List.of("!throneroom", "!thronsaal", "!ts"),   // Nutzer 25.11.
            List.of("!arenacathedral", "!arenakathedrale"),   // Nutzer 25.11.
            List.of("!arenareport", "!arenastats", "!arenabericht", "!arenastatistik"),
            List.of("!notes", "!notizen", "!notizblock"),
            List.of("!note", "!notiz"),
            List.of("!list", "!liste"),
            List.of("!emergency", "!kill", "!notaus", "!not-aus"),
            // Autor 21.10. (aus dem Hauptbot): Mitschauen -- alle N Minuten ein Bild vom Spiel.
            List.of("!watch", "!wach", "!wache", "!wachbleiben", "!awake", "!keepawake"),
            // Autor 21.10. (aus dem Hauptbot): Titanen-Turm, Clan-Laden und das Spiel selbst von Hand starten.
            List.of("!tower", "!turm", "!titanturm", "!titantower"),
            // Autor 22.10. (aus dem Hauptbot): Freunden Ressourcen senden von Hand -- sonst nach dem Turm um 02:00 und 12:00.
            List.of("!friends", "!freunde", "!geschenke", "!sendfriends", "!freundesenden"),
            List.of("!clanstore", "!clanladen", "!clanshop"),
            List.of("!game", "!spiel", "!hc"),
            // Autor 21.10. (aus dem Hauptbot): "!event ranking" -- Bild der Event-Rangliste.
            List.of("!event"),
            // Autor 21.10. (aus dem Hauptbot): Arena-Gegner per Name auf eine Liste ("!arena Rina safe").
            List.of("!arena", "!opponent", "!gegner", "!arenaopponent", "!arenagegner"),
            // Autor 24.10.: eigene Namens-Schrift der Arena -- unbeschriftete Zeichen als Bild / ein Zeichen beschriften
            List.of("!glyphs", "!glyphen"),
            List.of("!glyph", "!glyphe"));

    /**
     * Diese Befehle werden bei einem Tippfehler NIE ohne Nachfrage ausgefuehrt (erste Schreibweise der Gruppe).
     * "!stigmas" seit 10.10.: "!stigmas loeschen" zerlegt Stigmas -- nicht rueckgaengig zu machen.
     * "!dig" (vorher "!graben") seit 11.10. (Hauptbot-Pruefung: mit den neuen Namen zogen Tippfehler Modus-Starts an,
     * "!dug" -> Graben).
     */
    static final Set<String> GEFAEHRLICH = Set.of(
            "!off", "!stop", "!emergency", "!end", "!pc", "!clear", "!pause", "!stigmas", "!dig",
            // 21.10.: starten einen Modus bzw. das Spiel -- ein Tippfehler ("!power" -> "!tower", "!gem" -> "!game") fragt erst nach.
            "!tower", "!game",
            // 22.10.: aus dem Hauptbot uebernommene Modi
            "!gembay", "!portal", "!foodchests");

    /**
     * Eine bekannte Schreibweise (auch mit Umlaut, Bindestrich oder Grossbuchstaben) wird zu dem Befehl, den die
     * Befehlsverteilung kennt: "!stopp" -> "!stop", "!shutdown" -> "!off" (Hauptbot 11.10.). Alles andere bleibt.
     */
    public static String kanonisch(String verb) {
        if (verb == null) return null;
        String n = normal(verb);
        for (List<String> g : BEFEHLE) {
            for (String a : g) {
                if (normal(a).equals(n)) return g.get(0);
            }
        }
        return verb;
    }

    /** Alle Schreibweisen aller Befehle (fuer die Kuerzel-Pruefung: ein Kuerzel darf keine davon ueberdecken). */
    static Set<String> alleSchreibweisen() {
        Set<String> s = new java.util.HashSet<>();
        for (List<String> g : BEFEHLE) s.addAll(g);
        return s;
    }

    /**
     * Ein Wort hinter einem Befehl (z. B. "!stigmas einstellen"), mit Tippfehlern: gibt die erste Schreibweise der
     * passenden Gruppe zurueck -- oder null, wenn nichts eindeutig passt. Kurze Woerter (unter 4) nur genau.
     */
    static String wahl(String wort, List<List<String>> gruppen) {
        if (wort == null) return null;
        String n = normal(wort);
        if (n.isEmpty()) return null;
        for (List<String> g : gruppen) {
            for (String a : g) {
                if (normal(a).equals(n)) return g.get(0);
            }
        }
        if (n.length() < 4) return null;
        int grenze = n.length() <= 5 ? 1 : n.length() <= 9 ? 2 : 3;
        String bester = null;
        int besteDistanz = Integer.MAX_VALUE;
        boolean doppelt = false;
        for (List<String> g : gruppen) {
            int d = Integer.MAX_VALUE;
            for (String a : g) {
                if (normal(a).length() < 4) continue;
                d = Math.min(d, abstand(n, normal(a)));
            }
            if (d > grenze) continue;
            if (d < besteDistanz) {
                besteDistanz = d;
                bester = g.get(0);
                doppelt = false;
            } else if (d == besteDistanz) {
                doppelt = true;
            }
        }
        return bester != null && !doppelt ? bester : null;
    }

    record Treffer(String befehl, boolean fragen, List<String> vorschlaege) {}

    /**
     * Was mit einem unbekannten Befehl gemeint sein koennte (nur im
     * default-Zweig aufgerufen).
     *
     * @return null = nichts Aehnliches; sonst ein eindeutiger Befehl
     *         (fragen=false) oder Vorschlaege
     */
    static Treffer suchen(String verb) {
        String n = normal(verb);
        if (n.length() <= 1) return null;
        // Eine bekannte Schreibweise, die befehl() nicht kennt ("!stopp", "!hilfe").
        for (List<String> g : BEFEHLE) {
            for (String a : g) {
                if (normal(a).equals(n)) return new Treffer(g.get(0), false, List.of(g.get(0)));
            }
        }
        int laenge = n.length() - (n.startsWith("!") ? 1 : 0);
        if (laenge <= 2) return null;
        int grenze = laenge <= 4 ? 1 : laenge <= 8 ? 2 : 3;

        List<int[]> alle = new ArrayList<>();   // {Gruppe, Distanz}
        for (int i = 0; i < BEFEHLE.size(); i++) {
            int d = Integer.MAX_VALUE;
            for (String a : BEFEHLE.get(i)) d = Math.min(d, abstand(n, normal(a)));
            if (d <= grenze) alle.add(new int[]{i, d});
        }
        if (alle.isEmpty()) return null;
        alle.sort((x, y) -> Integer.compare(x[1], y[1]));
        List<String> beste = new ArrayList<>();
        for (int[] e : alle) {
            if (beste.size() < 3) beste.add(BEFEHLE.get(e[0]).get(0));
        }
        boolean eindeutig = alle.size() == 1 || alle.get(0)[1] < alle.get(1)[1];
        String erster = beste.get(0);
        boolean fragen = !eindeutig || GEFAEHRLICH.contains(erster);
        return new Treffer(erster, fragen, fragen && eindeutig ? List.of(erster) : beste);
    }

    /** Kleinbuchstaben, Umlaute ausgeschrieben, nur Buchstaben/Ziffern und "!". */
    static String normal(String s) {
        String t = s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '!') b.append(c);
        }
        return b.toString();
    }

    /** Damerau-Levenshtein (mit Vertauschen zweier Nachbarn). */
    static int abstand(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int kosten = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + kosten);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
