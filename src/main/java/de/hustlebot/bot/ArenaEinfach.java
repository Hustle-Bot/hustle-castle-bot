package de.hustlebot.bot;

import de.hustlebot.core.Dateien;
import de.hustlebot.core.Log;
import de.hustlebot.core.NamensVergleich;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Thronsaal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Einfache Arena-Strategie fuer niedrige Thronsaal-Stufen (Nutzer 25.11.: "Auf TS8 interessiert man sich nicht fuer
 * Kampfkraft oder Sets -- durch ein paar Angriffe findet man heraus, ob man den Gegner schafft oder nicht").
 *
 * Gilt vorerst nur fuer Thronsaal 8 ({@link #STUFE}). Statt Kraft, Sets, Items, Schirmherren und Einheiten-Pruefung zaehlt
 * nur die eigene Bilanz gegen den Gegner:
 * <ul>
 *   <li>die letzten {@link #KAEMPFE_FUER_URTEIL} gueltigen Kaempfe: ab {@link #SIEGE_FUER_SCHAFFBAR} Siegen schaffbar, sonst nicht;</li>
 *   <li>weniger Kaempfe = unbekannt, wird getestet;</li>
 *   <li>ein Kampf wird ungueltig, sobald die eigene Kraft um {@link #KRAFT_NEU_AB} (5 %) gestiegen ist -- dann hat sich am
 *       eigenen Aufbau etwas geaendert, der Gegner wird neu bewertet.</li>
 * </ul>
 * Punkte auf TS8 (Nutzer 25.11.): Runde 1 hoechstens 100; danach gegen jemanden unter dir hoechstens 100, ueber dir hoechstens 150.
 *
 * Daten in {@code arena-ts8.txt} im Bot-Ordner (TAB: Schluessel, Name, S/N, eigene Kraft, Zeit). Das Gegnerbuch bleibt unberuehrt.
 * Schalter {@code arena-einfach.txt}: "an" erzwingt die einfache Strategie, "aus" schaltet sie ab; ohne Datei gilt sie genau
 * bei Thronsaal {@link #STUFE}.
 *
 * Alle Regeln sind statische Funktionen ohne Bildschirm (ArenaEinfachTest).
 */
final class ArenaEinfach {

    private ArenaEinfach() { }

    /** Fuer diese Thronsaal-Stufe gilt die einfache Strategie (Nutzer 25.11.: "erstmal nur TR8"). */
    static final int STUFE = 8;
    /** Runde 1 und Gegner unter dir: hoechstens so viele Punkte. */
    static final int UNTEN_MAX = 100;
    /** Gegner ueber dir (ab Runde 2): hoechstens so viele Punkte. */
    static final int OBEN_MAX = 150;
    /** So viele Kaempfe braucht ein Urteil (Nutzer 25.11.: "3 Kaempfe sollten erstmal reichen"). */
    static final int KAEMPFE_FUER_URTEIL = 3;
    /** So viele Siege aus den letzten drei = schaffbar (Nutzer 25.11.: "2 von 3 Siegen = schaffbar"). */
    static final int SIEGE_FUER_SCHAFFBAR = 2;
    /** Eigene Kraft so viel hoeher als beim Kampf = der Kampf zaehlt nicht mehr (Nutzer 25.11.: 5 %). */
    static final double KRAFT_NEU_AB = 1.05;
    /** So viele Kaempfe je Gegner bleiben in der Datei. */
    static final int MERKT_SICH = 10;

    enum Urteil { UNBEKANNT, SCHAFFBAR, NICHT_SCHAFFBAR }

    /** Ein Kampf: gewonnen?, eigene Kraft damals (-1 = unbekannt), Zeit als Text. */
    record Kampf(boolean sieg, long meineKraft, String zeit) { }

    /** Bewertung eines Gegners aus seinen gueltigen Kaempfen. */
    record Bewertung(Urteil urteil, int siege, int kaempfe, double chance) { }

    /** Ein waehlbarer Gegner: Schild-Punkte ("Reward: +150", 0 = nicht gelesen) und seine Bewertung. */
    record Kandidat(String name, long schildPunkte, Bewertung bewertung) { }

    // ------------------------------------------------------------------------------------------------------------------
    //  Schalter
    // ------------------------------------------------------------------------------------------------------------------

    static volatile Path SCHALTER = null;   // null = Ordner.bei("arena-einfach.txt"); Tests setzen eine eigene Datei

    private static volatile boolean aktivGemerkt;
    private static volatile long aktivGelesenUm;

    /** Gilt die einfache Strategie jetzt? Wird bei jedem Bild gefragt -- darum hoechstens alle 5 s neu gelesen. */
    static boolean aktiv() {
        long jetzt = System.nanoTime();
        if (aktivGelesenUm != 0 && jetzt - aktivGelesenUm < 5_000_000_000L) return aktivGemerkt;
        boolean a = aktivLesen();
        if (a != aktivGemerkt || aktivGelesenUm == 0) {
            Log.info("Arena", a ? Sprache.t("Simple arena strategy (throne room " + STUFE + "): only the own record against each opponent counts.",
                            "Einfache Arena-Strategie (Thronsaal " + STUFE + "): es zaehlt nur die eigene Bilanz gegen jeden Gegner.")
                    : Sprache.t("Full arena strategy (power, sets, checks).", "Volle Arena-Strategie (Kraft, Sets, Pruefungen)."));
        }
        aktivGemerkt = a;
        aktivGelesenUm = jetzt;
        return a;
    }

    /** Fuer Tests und nach !thronsaal: sofort neu lesen. */
    static void neuLesen() {
        aktivGelesenUm = 0;
    }

    private static boolean aktivLesen() {
        Path p = SCHALTER != null ? SCHALTER : Ordner.bei("arena-einfach.txt");
        try {
            if (Files.exists(p)) {
                for (String z : Dateien.zeilen(p)) {
                    String w = z.strip().toLowerCase(Locale.ROOT);
                    if (w.isEmpty() || w.startsWith("#")) continue;
                    if (de.hustlebot.core.Schalterdatei.istAn(w)) return true;
                    if (de.hustlebot.core.Schalterdatei.istAus(w)) return false;
                }
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-einfach.txt nicht lesbar: " + e.getMessage());
        }
        return aktivBeiStufe(Thronsaal.aktuell());
    }

    static boolean aktivBeiStufe(int thronsaal) {
        return thronsaal == STUFE;
    }

    // ------------------------------------------------------------------------------------------------------------------
    //  Reine Regeln
    // ------------------------------------------------------------------------------------------------------------------

    /** Was ein Sieg hoechstens bringt: Runde 1 immer bis 100; danach, was im Schild steht, gedeckelt auf 150 (ohne Lesung 100). */
    static int lohn(int runde, long schildPunkte) {
        if (runde <= 1) return schildPunkte > 0 ? (int) Math.min(schildPunkte, UNTEN_MAX) : UNTEN_MAX;
        if (schildPunkte <= 0) return UNTEN_MAX;
        return (int) Math.min(schildPunkte, OBEN_MAX);
    }

    /** Steht der Gegner ueber mir? Erst ab Runde 2 erkennbar: dort gibt er mehr als 100. */
    static boolean ueberMir(int runde, long schildPunkte) {
        return runde >= 2 && schildPunkte > UNTEN_MAX;
    }

    /**
     * Die Kaempfe, die noch zaehlen: die neuesten zuerst, hoechstens {@link #KAEMPFE_FUER_URTEIL}. Ein Kampf zaehlt nicht mehr, wenn
     * die eigene Kraft heute um 5 % ueber der damaligen liegt. Ist eine der beiden Kraefte unbekannt, zaehlt er.
     *
     * @param alle            aelteste zuerst
     * @param meineKraftJetzt -1 = unbekannt
     */
    static List<Kampf> gueltige(List<Kampf> alle, long meineKraftJetzt) {
        List<Kampf> out = new ArrayList<>();
        for (int i = alle.size() - 1; i >= 0 && out.size() < KAEMPFE_FUER_URTEIL; i--) {
            Kampf k = alle.get(i);
            if (veraltet(k.meineKraft(), meineKraftJetzt)) break;   // aelter ist erst recht veraltet
            out.add(k);
        }
        return out;
    }

    static boolean veraltet(long kraftDamals, long kraftJetzt) {
        return kraftDamals > 0 && kraftJetzt > 0 && kraftJetzt >= kraftDamals * KRAFT_NEU_AB;
    }

    /** Urteil aus den gueltigen Kaempfen (neueste zuerst). Chance: Laplace (Siege+1)/(Kaempfe+2) -- ohne Kampf 50 %. */
    static Bewertung bewerten(List<Kampf> gueltig) {
        int n = gueltig.size();
        int siege = (int) gueltig.stream().filter(Kampf::sieg).count();
        Urteil u = n < KAEMPFE_FUER_URTEIL ? Urteil.UNBEKANNT
                : siege >= SIEGE_FUER_SCHAFFBAR ? Urteil.SCHAFFBAR : Urteil.NICHT_SCHAFFBAR;
        return new Bewertung(u, siege, n, (siege + 1.0) / (n + 2.0));
    }

    /** Erwartete Punkte: Chance x Lohn. */
    static double wert(Kandidat k, int runde) {
        return k.bewertung().chance() * lohn(runde, k.schildPunkte());
    }

    /**
     * Die Wahl (v1, Nutzer 25.11. -- die genaue Positionierung kommt spaeter): hoechste erwartete Punkte. Bei Gleichstand der
     * Unbekannte (er muss ohnehin getestet werden; unter Unbekannten der mit weniger Kaempfen), dann der mit mehr Punkten. Nicht-Schaffbare landen von selbst hinten
     * (Chance 20-40 %), werden aber genommen, wenn sonst keiner da ist -- eine Niederlage kostet nichts.
     *
     * @return Index in {@code kandidaten}, -1 bei leerer Liste
     */
    static int wahl(List<Kandidat> kandidaten, int runde) {
        int beste = -1;
        for (int i = 0; i < kandidaten.size(); i++) {
            if (beste < 0 || besser(kandidaten.get(i), kandidaten.get(beste), runde)) beste = i;
        }
        return beste;
    }

    private static boolean besser(Kandidat a, Kandidat b, int runde) {
        double wa = wert(a, runde), wb = wert(b, runde);
        if (Math.abs(wa - wb) > 1e-9) return wa > wb;
        boolean ua = a.bewertung().urteil() == Urteil.UNBEKANNT, ub = b.bewertung().urteil() == Urteil.UNBEKANNT;
        if (ua != ub) return ua;
        if (ua && a.bewertung().kaempfe() != b.bewertung().kaempfe()) return a.bewertung().kaempfe() < b.bewertung().kaempfe();
        return lohn(runde, a.schildPunkte()) > lohn(runde, b.schildPunkte());
    }

    /** Kurztext fuers Protokoll: "schaffbar 2/3" usw. */
    static String text(Bewertung b) {
        String bilanz = b.siege() + "/" + b.kaempfe();
        return switch (b.urteil()) {
            case SCHAFFBAR -> Sprache.t("beatable " + bilanz, "schaffbar " + bilanz);
            case NICHT_SCHAFFBAR -> Sprache.t("not beatable " + bilanz, "nicht schaffbar " + bilanz);
            case UNBEKANNT -> Sprache.t("testing " + bilanz, "wird getestet " + bilanz);
        };
    }

    // ------------------------------------------------------------------------------------------------------------------
    //  Datei arena-ts8.txt
    // ------------------------------------------------------------------------------------------------------------------

    static volatile Path DATEI = null;   // null = Ordner.bei("arena-ts8.txt")

    private static Path datei() {
        return DATEI != null ? DATEI : Ordner.bei("arena-ts8.txt");
    }

    /** Schluessel -> [Anzeigename, Kaempfe (aelteste zuerst)]. */
    private record Eintrag(String name, List<Kampf> kaempfe) { }

    private static Map<String, Eintrag> cache;
    private static Path cacheVon;

    static String schluessel(String name) {
        return NamensVergleich.form(name);
    }

    private static synchronized Map<String, Eintrag> alle() {
        Path p = datei();
        if (cache != null && p.equals(cacheVon)) return cache;
        Map<String, Eintrag> m = new LinkedHashMap<>();
        try {
            if (Files.exists(p)) {
                for (String z : Dateien.zeilen(p)) {
                    if (z.isBlank() || z.startsWith("#")) continue;
                    String[] f = z.split("\t", -1);
                    if (f.length < 5 || f[0].isBlank()) continue;
                    long kraft;
                    try { kraft = Long.parseLong(f[3].strip()); } catch (NumberFormatException e) { kraft = -1; }
                    m.computeIfAbsent(f[0], k -> new Eintrag(f[1], new ArrayList<>()))
                            .kaempfe().add(new Kampf("S".equals(f[2].strip()), kraft, f[4].strip()));
                }
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-ts8.txt nicht lesbar: " + e.getMessage());
        }
        cache = m;
        cacheVon = p;
        return m;
    }

    /** Fuer Tests: Cache verwerfen. */
    static synchronized void vergessen() {
        cache = null;
        cacheVon = null;
    }

    /** Kaempfe gegen diesen Spieler (aelteste zuerst): erst genau, sonst ein eindeutig aehnlicher Name. */
    static synchronized List<Kampf> kaempfe(String name) {
        String k = schluessel(name);
        if (k.isEmpty()) return List.of();
        Map<String, Eintrag> m = alle();
        Eintrag e = m.get(k);
        if (e != null) return List.copyOf(e.kaempfe());
        String treffer = aehnlicherSchluessel(m, name);
        return treffer == null ? List.of() : List.copyOf(m.get(treffer).kaempfe());
    }

    /** Ein bekannter Schluessel mit aehnlichem Namen -- nur, wenn es genau einen gibt (sonst lieber unbekannt). */
    private static String aehnlicherSchluessel(Map<String, Eintrag> m, String name) {
        if (schluessel(name).length() < 4) return null;
        String treffer = null;
        for (var e : m.entrySet()) {
            if (NamensVergleich.gleicherName(e.getValue().name(), name)) {
                if (treffer != null) return null;
                treffer = e.getKey();
            }
        }
        return treffer;
    }

    /** Bewertung eines Gegners heute. */
    static Bewertung bewertung(String name, long meineKraftJetzt) {
        return bewerten(gueltige(kaempfe(name), meineKraftJetzt));
    }

    /** Ein Kampf ist vorbei: eintragen (genau einmal je Kampf -- der Aufrufer sorgt dafuer). */
    static synchronized void eintragen(String name, boolean sieg, long meineKraft) {
        String k = schluessel(name);
        if (k.isEmpty()) return;
        Map<String, Eintrag> m = alle();
        String ziel = m.containsKey(k) ? k : aehnlicherSchluessel(m, name);
        if (ziel == null) ziel = k;
        Eintrag e = m.computeIfAbsent(ziel, x -> new Eintrag(name.strip(), new ArrayList<>()));
        e.kaempfe().add(new Kampf(sieg, meineKraft,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(de.hustlebot.core.Zeitzone.jetzt())));
        while (e.kaempfe().size() > MERKT_SICH) e.kaempfe().remove(0);
        speichern(m);
    }

    private static void speichern(Map<String, Eintrag> m) {
        StringBuilder sb = new StringBuilder("# Arena TS8: Kaempfe je Gegner (Schluessel, Name, S/N, eigene Kraft, Zeit). Vom Bot geschrieben.\n");
        m.forEach((k, e) -> {
            for (Kampf f : e.kaempfe()) {
                sb.append(k).append('\t').append(e.name().replace('\t', ' ')).append('\t').append(f.sieg() ? 'S' : 'N')
                        .append('\t').append(f.meineKraft()).append('\t').append(f.zeit()).append('\n');
            }
        });
        Path p = datei();
        try {
            Path neu = p.resolveSibling(p.getFileName() + ".neu");
            Files.writeString(neu, sb.toString(), StandardCharsets.UTF_8);
            Files.move(neu, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Log.warn("Arena", Sprache.t("arena-ts8.txt could not be saved: " + ex.getMessage(),
                    "arena-ts8.txt liess sich nicht speichern: " + ex.getMessage()));
        }
    }
}
