package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Thronsaal-Stufe je Spielkonto und was sie freischaltet (Autor 25.10., Zweitkonten "Zweitkonto A" / "Zweitkonto B"
 * auf Thronsaal 5, Hauptkonto "Autor" auf 11). Die Stufe steht links im Profil ("Level 5" neben dem Thron) -- der
 * Team-Scan liest sie und merkt sie hier am Spielernamen. Gesperrte Modi startet der Bot nicht, gesperrte Teile
 * (Held, Schirmherren, Sets ...) sieht er sich nicht an. Wechselt das Spiel auf ein Konto mit hoeherer Stufe, gilt dessen.
 * Unbekannte Stufe = nichts gesperrt (wie bisher).
 */
public final class Thronsaal {

    private Thronsaal() { }

    /** Autor 25.10.: ab welchem Thronsaal es was gibt (Held "glaube ich" ab 9). */
    public enum Teil {
        KATHEDRALE(6, "Dark Cathedral", "Dunkle Kathedrale"),
        TOTEMS(6, "totems", "Totems"),
        TITANENTURM(7, "Titan tower", "Titanenturm"),
        SCHIRMHERREN(7, "patrons", "Schirmherrschaften"),
        STIGMAS(7, "titan stigma sets", "Titan-Stigma-Sets"),
        KOLOSSEUM(7, "Colosseum", "Kolosseum"),   // Autor 27.10.: erst ab Thronsaal 7 freigeschaltet (TR5-Konto scrollte sonst vergeblich)
        EDELSTEINBUCHT(8, "Gem Bay", "Edelsteinbucht"),
        VERLIES(9, "Dungeon", "Verlies"),
        HELD(9, "hero", "Held"),
        SETS(11, "sets", "Sets"),
        STEINE(11, "stones", "Steine"),
        GUARDIANS(11, "guardians", "Guardians");

        public final int ab;
        final String en, de;

        Teil(int ab, String en, String de) {
            this.ab = ab;
            this.en = en;
            this.de = de;
        }

        public String text() {
            return Sprache.t(en, de);
        }
    }

    static volatile Path DATEI = Path.of("thronsaal.txt");

    /** Die Stufe des Kontos, das gerade gespielt wird (-1 = unbekannt) -- vom Team-Scan und beim Start eines Modus gesetzt. */
    private static volatile int aktuell = -1;

    public static void aktuellSetzen(int stufe) {
        aktuell = stufe;
    }

    public static int aktuell() {
        return aktuell;
    }

    /**
     * Gibt es diesen Teil im gerade gespielten Konto noch nicht? Unbekannte Stufe = es gibt ihn (wie bisher). Autor 25.10.:
     * auf Thronsaal 5 fand der Bot "Steine" und lauter "Bauarbeiter" (30 000 Leben sind dort normal).
     */
    public static boolean fehltJetzt(Teil t) {
        int s = aktuell;
        return s > 0 && t != null && s < t.ab;
    }

    /** Name ohne Akzente ("Zweitkonto A" -> "leftzweitkonto"): die Texterkennung setzt gern einen dazu. */
    static String schluessel(String name) {
        String ohne = java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return NamensVergleich.form(ohne);
    }

    /** Welcher Teil gehoert zu diesem Modus (null = ab Thronsaal 1 oder unbekannt). */
    public static Teil teilVonModus(String modeId) {
        if (modeId == null) return null;
        return switch (modeId) {
            case "cathedral" -> Teil.KATHEDRALE;
            case "titantower" -> Teil.TITANENTURM;
            case "stigmas" -> Teil.STIGMAS;
            case "colosseum" -> Teil.KOLOSSEUM;
            case "gembay" -> Teil.EDELSTEINBUCHT;
            case "dungeon" -> Teil.VERLIES;
            default -> null;
        };
    }

    /** Die Stufe des Kontos merken (vom Team-Scan). */
    public static synchronized void merken(String name, int stufe) {
        if (name == null || schluessel(name).length() < 2 || stufe < 1 || stufe > 30) return;
        Map<String, Integer> alle = lesen();
        alle.entrySet().removeIf(e -> !e.getKey().equals(STANDARD) && schluessel(e.getKey()).equals(schluessel(name)));
        alle.put(name.strip(), stufe);
        schreiben(alle);
    }

    private static void schreiben(Map<String, Integer> alle) {
        StringBuilder sb = new StringBuilder("# Thronsaal je Spielkonto (Name=Stufe), vom Team-Scan aus dem Profil gelesen. standard= gilt fuer unbekannte Konten (!thronsaal).\n");
        alle.forEach((n, s) -> sb.append(n).append('=').append(s).append('\n'));
        try {
            Files.writeString(DATEI, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Thronsaal", "nicht gespeichert: " + e.getMessage());
        }
    }

    /**
     * Die gemerkte Stufe, -1 = unbekannt. Nur die gleiche Buchstabenfolge ("Zweitkonto A" / "zweitkonto a") -- kein
     * Teilname: "Zweitkonto" passt zu Left UND Zweitkonto B, die verschiedene Stufen haben werden.
     */
    public static synchronized int stufe(String name) {
        Map<String, Integer> alle = lesen();
        int standard = alle.getOrDefault(STANDARD, -1);
        if (name == null || name.isBlank()) return standard;
        String f = schluessel(name);
        for (var e : alle.entrySet()) {
            if (!e.getKey().equals(STANDARD) && schluessel(e.getKey()).equals(f)) return e.getValue();
        }
        // Lesefehler ("-ZweitkontołA" fuer "Zweitkonto A"): ab 8 Zeichen hoechstens 2 anders -- aber nur, wenn genau ein
        // Konto so nah ist ("Left" und "Zweitkonto B" liegen 4 auseinander und werden nie verwechselt).
        if (f.length() < 8) return standard;
        int treffer = -1, anzahl = 0;
        for (var e : alle.entrySet()) {
            if (e.getKey().equals(STANDARD)) continue;
            if (Gegnerbuch.abstand(schluessel(e.getKey()), f, 2) <= 2) {
                treffer = e.getValue();
                anzahl++;
            }
        }
        return anzahl == 1 ? treffer : standard;
    }

    /**
     * Zeile "standard=8" in thronsaal.txt (Nutzer 25.11., !thronsaal 8): gilt fuer jedes Konto, dessen Stufe der Team-Scan noch
     * nicht gelesen hat -- sonst setzte der Motor beim Start eines Modus die Stufe ohne bekannten Kontonamen wieder auf "unbekannt".
     */
    static final String STANDARD = "standard";

    /** Die Stufe von Hand setzen: fuer das Konto, wenn sein Name bekannt ist, sonst als Standard. Gilt sofort. */
    public static synchronized void vonHandSetzen(String name, int stufe) {
        if (stufe < 1 || stufe > 30) return;
        if (name != null && schluessel(name).length() >= 2) merken(name, stufe);
        else merkenRoh(STANDARD, stufe);
        aktuell = stufe;
    }

    private static void merkenRoh(String schluessel, int stufe) {
        Map<String, Integer> alle = lesen();
        alle.put(schluessel, stufe);
        schreiben(alle);
    }

    /** Hat dieses Konto den Teil schon? Unbekannte Stufe = ja (nichts sperren, was man nicht weiss). */
    public static boolean frei(String name, Teil t) {
        int s = stufe(name);
        return s < 0 || t == null || s >= t.ab;
    }

    /** Ist der Modus fuer dieses Konto noch gesperrt? Dann der Satz fuers Protokoll, sonst null. */
    public static String gesperrtGrund(String modeId, String name) {
        // Autor 27.10.: Modus per Datei "<modus>-aus.txt" im Bot-Ordner ganz abschalten -- z. B. titantower-aus.txt bei den
        // Zweitkonten (Zweitkonto A/Drittkonto), deren Titan-Team fehlt oder zu schwach ist. Unabhaengig von der Stufe.
        if (modeId != null && Files.exists(Ordner.bei(modeId + "-aus.txt"))) {
            return Sprache.t("Mode \"" + modeId + "\" is switched off here (file). Skipped.",
                    "Modus \"" + modeId + "\" ist hier ausgeschaltet (Datei). Uebersprungen.");
        }
        Teil t = teilVonModus(modeId);
        if (t == null || frei(name, t)) return null;
        int s = stufe(name);
        return Sprache.t(t.text() + " opens at throne room " + t.ab + " -- \"" + name + "\" is on " + s + ". Skipped.",
                t.text() + " gibt es erst ab Thronsaal " + t.ab + " -- \"" + name + "\" hat " + s + ". Uebersprungen.");
    }

    private static Map<String, Integer> lesen() {
        Map<String, Integer> m = new LinkedHashMap<>();
        try {
            if (!Files.exists(DATEI)) return m;
            for (String z : Dateien.zeilen(DATEI)) {
                if (z.isBlank() || z.startsWith("#") || !z.contains("=")) continue;
                int i = z.lastIndexOf('=');
                try {
                    m.put(z.substring(0, i).strip(), Integer.parseInt(z.substring(i + 1).strip()));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.debug("Thronsaal", "nicht lesbar: " + e.getMessage());
        }
        return m;
    }

    /** "Level 5" / "Level5" / "Lvl 5" aus dem gelesenen Profil-Text; -1 = nicht gefunden. */
    public static int ausText(String text) {
        if (text == null) return -1;
        var m = java.util.regex.Pattern.compile("(?:level|lvl|stufe)\\D{0,3}(\\d{1,2})").matcher(text.toLowerCase(Locale.ROOT));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
