package de.hustlebot.bot;

import de.hustlebot.core.ArenaExtras;
import de.hustlebot.core.ArenaSperrBilder;
import de.hustlebot.core.Gegnerbuch;
import de.hustlebot.core.Glyphenschrift;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Arena (im Spiel "Tournament").
 *
 * FREUNDE-FASSUNG (uebernommen 11.10. aus dem Hauptbot, Stand Commit 6c46376, noch ohne Helden): gleicher
 * Ablauf. Anders als bei Autor: der eigene Name im Kampfprotokoll kommt vom eigenen Schild (ar_me, jeder nimmt
 * ihn selbst auf) statt fest "autor"; die Gegner-Listen (ar_block_, ar_ungern_, ar_sicher_, ar_machbar_,
 * ar_clan_) bringt jeder selbst mit bzw. lernt der Bot; die Discord-Zeilen sind zweisprachig.
 *
 * ------------------------------------------------------------------
 *  Der Ablauf
 * ------------------------------------------------------------------
 *
 *  Weltkarte -> Arena-Insel -> "Participate" (kostet ein Ticket)
 *  -> "Deploy the best" -> "Confirm"
 *  -> Warteschlange, bis zehn Spieler gefunden sind
 *  -> Pyramide: fuenf Runden zu je hoechstens zwei Minuten
 *  -> "Tournament ended" -> "Claim reward"
 *
 *  Ein Durchlauf ist ein ganzes Turnier. "!start arena 5" sind also fuenf
 *  Turniere, rund eine Stunde.
 *
 * ------------------------------------------------------------------
 *  Wie Punkte entstehen -- daran haengt die ganze Taktik
 * ------------------------------------------------------------------
 *
 *  Ein Sieg gegen jemanden UNTER einem bringt 70 bis 150 Punkte, gegen
 *  jemanden UEBER einem 105 bis 225. Eine Niederlage bringt null.
 *
 *  Wieviel genau, entscheidet die Kampfdauer: bis zehn Sekunden gibt es
 *  die volle Punktzahl, danach faellt sie ab. Dreizehn Sekunden waren
 *  bei Autor nur noch 147 statt 150.
 *
 *  AUSNAHME RUNDE 1: da gibt jeder Gegner hoechstens 150, ob er nun
 *  ueber oder unter einem steht. In der ersten Runde ist die Punktzahl
 *  also kein Unterscheidungsmerkmal -- es zaehlt allein, wen man am
 *  sichersten schlaegt. Autors Rat dafuer: die mit "Not in clan".
 *
 *  Autors eigenes Muster: viermal nach unten, einmal nach oben.
 *  4 x 150 + 225 = 825. Der Turniersieger in seinem letzten Durchgang
 *  hatte 822.
 *
 * ------------------------------------------------------------------
 *  Jeder Gegner nur EINMAL
 * ------------------------------------------------------------------
 *
 *  Autor: "ich kann jede person immer nur einmal angreifen pro turnier,
 *  egal ob ich gegen sie verloren oder gewonnen habe."
 *
 *  Neun Gegner, fuenf Angriffe. Wer Runde fuer Runde nur den besten
 *  nimmt, verschenkt Punkte -- der beste von heute waere naechste Runde
 *  vielleicht 225 wert gewesen statt 150. Deshalb gehoeren die Bots mit
 *  "Not in clan" in Runde 1: die bleiben unten und waeren spaeter nie
 *  225 wert.
 *
 *  Das Spiel fuehrt selbst Buch. Wo im Schild sonst "Reward: + 150"
 *  steht, steht bei einem schon ausgekaempften Gegner "Victory!" oder
 *  "Defeat" -- Autors eigenes Ergebnis gegen genau den.
 *
 *  Der Bot sucht deshalb NUR nach "Reward:". Das ist kein Versehen: Wo
 *  das Wort fehlt, ist der Gegner bereits verbraucht und kommt als Ziel
 *  ohnehin nicht mehr in Frage. Die Liste der gefundenen Schilder ist
 *  damit genau die Liste der noch moeglichen Gegner.
 *
 *  (Hier stand einmal, es wuerden alle drei Woerter gesucht und erst
 *  ihre Summe ergebe die Pyramide. Das war nie so gebaut. Ein Kommentar,
 *  der etwas anderes behauptet als der Code tut, schickt den naechsten
 *  Leser in die falsche Richtung -- und in diesem Projekt sind die
 *  Kommentare Autors einziger Zugang zum Verhalten des Bots.)
 *
 * ------------------------------------------------------------------
 *  Kampfstaerke sagt WENIGER, als man denkt
 * ------------------------------------------------------------------
 *
 *  Hier stand einmal "unter 22 Mio sicher, ueber 34 Finger weg". Die
 *  zweite Haelfte ist falsch. Autor: "es ist groesstenteils abhaengig
 *  von den sets des mitspielers. ich besiege manchmal auch gegner die
 *  30-70 mio power haben. eine richtige power grenze wird es nie geben."
 *
 *  Verlaesslich ist nur die untere Grenze. Darueber ist die Zahl ein
 *  Merkmal unter mehreren, und ein schwaches.
 *
 *  Und der Platz in der Pyramide sagt gar nichts: "unter mir heisst
 *  nicht gleich schlecht. kann auch einfach ein gegner sein der grade
 *  afk ist und nicht angegriffen hat." Der Platz bestimmt die PUNKTE,
 *  nie die Siegchance.
 *
 * ------------------------------------------------------------------
 *  In jeder Runde wird angegriffen
 * ------------------------------------------------------------------
 *
 *  Eine Niederlage bringt null -- genau wie nicht antreten. Ein Angriff
 *  kostet also nichts. Autor: "er soll aber in jeder runde IMMER einen
 *  angriff taetigen. auch wenn die chancen extrem klein sind. sag
 *  niemals nie."
 *
 *  Eine Runde verstreichen zu lassen ist damit ein Fehler, keine
 *  Vorsicht. Nur die harte Sperrliste greift ohne Ausnahme.
 */
public final class ArenaMode implements GameMode {

    /**
     * Autor 19.10.: ueber 100 Berichte "kein Fortschritt seit 20 s" in einer Nacht -- in der Arena wartet der Bot normal
     * bis zu 2 Minuten aufs Rundenende ("8 of 9 have attacked", "Victory"). Bericht erst nach 3 Minuten.
     */
    @Override
    public long haengerBerichtNachS() {
        return 180;
    }

    /** Warum die Arena aufhoerte, wenn es an der Bezahlung lag (steht dann bei "Done" mit dabei). */
    @Override
    public String endeHinweis() {
        return kasse.endeGrund();
    }

    // ---- Hinweg --------------------------------------------------------
    private static final String T_NODE        = "ar_node";
    private static final String T_MAP_BUTTON  = "map_button";
    private static final String T_MAP_ALT     = "map_button_alt";
    private static final String T_GO_TO_MAP   = "go_to_map";
    /** Der gruene "Home" -- bringt aus fast jedem Bildschirm zurueck in die Burg. */
    private static final String T_HOME_BUTTON = "home_button";
    /** Rote X, um ein offenes Fenster loszuwerden. Je Bereich ein eigenes Motiv. */
    private static final String T_X_ALLGEMEIN = "close_x";
    private static final String T_X_VERLIES   = "dg_close_x";
    private static final String T_X_KATHEDRALE = "dc_close_x";

    // ---- Turnierfenster -------------------------------------------------
    private static final String T_TOURNAMENT  = "ar_tournament";
    private static final String T_TICKETS_OFF = "ar_for_tickets_off";
    private static final String T_PARTICIPATE = "ar_participate";
    /** Letzter Klick auf die Arena-Insel -- danach 2 s Ruhe (08.10.). */
    private long inselGeklicktUm;
    private static final String T_TICKET_ICON = "ar_ticket_icon";
    /** Reiter "for food" AUSGEWAEHLT (hellblau) -- so ist ar_for_food aufgenommen. */
    private static final String T_FOR_FOOD     = "ar_for_food";
    /** Reiter "for food" NICHT ausgewaehlt (grau) -- zum Umschalten auf Aepfel. */
    private static final String T_FOR_FOOD_OFF = "ar_for_food_off";

    /**
     * Womit bezahlt wird (Teil A, 28.09.): Tickets, Nahrung, erst Tickets dann
     * Nahrung, oder zusaetzlich Nahrungskisten -- siehe core.Bezahlung. Loest
     * das alte mitEssen ab ("!start arena 10 food" geht weiter).
     */
    private final Bezahlkasse kasse = new Bezahlkasse("arena", T_FOR_FOOD, T_FOR_FOOD_OFF,
            T_TICKETS_OFF, "ar_apfel_knopf", T_NODE, T_CLOSE_X);
    /** Participate gedrueckt, aber kein Turnier kam zustande (Aepfel alle?). */
    private int fehlstarts = 0;
    /** Seit dem letzten Participate etwas vom Turnier gesehen (Warteschlange, Pyramide ...)? */
    private boolean turnierGesehen = true;

    private static final String T_BADGE_ICON  = "ar_badge_icon";
    /** Das rote X oben rechts -- der Weg aus dem Turnierfenster zurueck in die Burg. */
    private static final String T_CLOSE_X     = "ar_close_x";

    // ---- Truppen --------------------------------------------------------
    private static final String T_SQUAD_TITLE = "ar_squad_title";
    private static final String T_DEPLOY_BEST = "ar_deploy_best";
    /** "Previous Squad" -- nur gebraucht, wenn Autor es fuer die Arena so eingestellt hat. */
    private static final String T_PREV_SQUAD  = "ar_previous_squad";
    private static final String T_CONFIRM     = "ar_confirm";

    // ---- Warteschlange ---------------------------------------------------
    private static final String T_SEARCHING   = "ar_searching";

    // ---- Pyramide --------------------------------------------------------
    private static final String T_SELECT      = "ar_select_opponent";
    private static final String T_NEXT_STAGE  = "ar_next_stage";
    private static final String T_REWARD      = "ar_reward";
    private static final String T_BAN_VICTORY = "ar_banner_victory";
    private static final String T_BAN_DEFEAT  = "ar_banner_defeat";
    private static final String T_NOT_IN_CLAN = "ar_not_in_clan";
    private static final String T_CHECK       = "ar_check";
    private static final String T_ME          = "ar_me";
    private static final String T_STAGE_LABEL = "ar_stage_label";
    private static final String T_CLOCK       = "ar_clock";

    // ---- Kampfprotokoll oben links ----------------------------------------
    private static final String T_LOG_OPEN    = "ar_log_open";
    private static final String T_LOG_CLOSE   = "ar_log_close";
    private static final String T_LOG_DEFEAT  = "ar_log_defeat";
    /** Die Ueberschrift des Protokolls -- alles darunter gehoert dazu. */
    private static final String T_LOG_TITLE   = "ar_log_title";

    /**
     * Die beiden Pfeile mitten in einer Protokollzeile.
     *
     * Eine Zeile lautet "Angreifer -> Verteidiger", und der Pfeil steht
     * dazwischen. Er leistet zwei Dinge auf einmal:
     *
     *  ER TEILT DIE ZEILE. Vorher wurde bei 45 Prozent der Protokollbreite
     *  geschnitten -- geraten, nicht gemessen. Bei einem langen Namen lag
     *  der Schnitt mitten im Namen.
     *
     *  ER SAGT DAS ERGEBNIS. Gruen oder rot. Autor auf die Frage, aus
     *  wessen Sicht: "immer aus deiner Sicht" -- gruen heisst also, dass
     *  ER den Kampf gewonnen hat, gleich auf welcher Seite er steht.
     *
     * Beide sind freiwillig. Fehlen sie oder sehen sie im Spiel anders aus
     * als vermutet, faellt der Bot auf den alten Weg zurueck; die
     * Verbrauchtliste steht auch ohne sie, denn dafuer zaehlt nur, auf
     * welcher SEITE Autor steht -- nicht, wie es ausging.
     */
    private static final String T_LOG_WON     = "ar_log_won";
    private static final String T_LOG_LOST    = "ar_log_lost";

    /**
     * Wie breit das aufgeklappte Kampfprotokoll ist, als Anteil des Bildes.
     *
     * Gemessen an Autors Bildschirmfoto: die Liste reicht von links bis
     * etwa 37 Prozent. Rechts davon liegt schon die Pyramide.
     */
    private static final double PROTOKOLL_BREITE = 0.37;

    /**
     * Wie weit ueber dem Wort "DEFEAT" die beiden Namen stehen, als Anteil
     * der Bildhoehe.
     *
     * Eine Zeile im Protokoll hat zwei Teile: oben "Angreifer -> Verteidiger",
     * darunter "+ 0  DEFEAT". Gemessen sind es rund 28 Bildpunkte bei 770,
     * also gut drei Prozent.
     */
    private static final double NAMEN_UEBER_ERGEBNIS = 0.036;

    /**
     * So viele Protokollzeilen liest der Bot hoechstens je Runde.
     *
     * Jede Zeile kostet einen eigenen Lesevorgang. Gemessen sind das rund
     * 335 Millisekunden -- bei acht Zeilen also knapp drei Sekunden, einmal
     * je Runde. Bei zwei Minuten Rundenzeit ist das zu verkraften; ohne
     * Grenze waere es das bei einem langen Protokoll nicht mehr.
     */
    private static final int MAX_PROTOKOLLZEILEN = 8;

    /** Der gruene "Home" oben rechts -- nur fuer den Notausstieg. */
    private static final String T_HOME        = "ar_home";

    // ---- Vergleichsfenster ------------------------------------------------
    private static final String T_YOUR_SQUAD  = "ar_your_squad";
    private static final String T_QUICK       = "ar_quick_attack";
    private static final String T_NEXT_ARROW  = "ar_next_arrow";
    private static final String T_POWER       = "ar_power_swords";

    // ---- Ergebnis ---------------------------------------------------------
    private static final String T_VICTORY     = "ar_victory";
    private static final String T_DEFEAT      = "ar_defeat";
    private static final String T_POINTS      = "ar_points_label";
    private static final String T_TIME        = "ar_battle_time";
    private static final String T_HOME_RESULT = "ar_home_result";

    // ---- Turnierende -------------------------------------------------------
    private static final String T_ENDED       = "ar_ended";
    private static final String T_POSITION    = "ar_position";
    private static final String T_CLAIM       = "ar_claim";

    // ---- Sperrlisten --------------------------------------------------------
    private static final String T_BLOCK_PREFIX  = "ar_block_";
    private static final String T_UNGERN_PREFIX = "ar_ungern_";
    /** Autor, 27.09.: Gegner, gegen die er sicher gewinnt -- 30 Punkte weniger. */
    private static final String T_SICHER_PREFIX = "ar_sicher_";
    /** Autor, 01.10.: "Leute aufnehmen, die machbar sind -- die sollen -10 Punkte bekommen". */
    private static final String T_MACHBAR_PREFIX = "ar_machbar_";
    /** Autor 31.10.: zwischen machbar und sicher, z. B. FLIMSY: immer gewonnen, aber gute Gegenstaende. Halbe Punkte, 225er erst ab Stage 4. */
    private static final String T_FAST_PREFIX = de.hustlebot.core.ArenaListenNetz.FAST;
    static final int FAST_PUNKTE = 27;
    static final double FAST_MINDESTENS = 0.85;
    /** Autor 31.10.: zwischen meiden und niemals. Vorerst nicht angreifen, nach PAUSE_TAGE gilt er als "meiden": "niemals" bleibt fuer immer, Pause nicht. */
    private static final String T_PAUSE_PREFIX = de.hustlebot.core.ArenaListenNetz.PAUSE;
    static final int PAUSE_TAGE = 14;
    /** Clan-Liste (Autor, 01.10.): Clan-Namen aus dem Schild; wer darin ist, bekommt +8. */
    private static final String T_CLAN_PREFIX = "ar_clan_";
    private static final int CLAN_PUNKTE = 8;
    /**
     * Fingerabdruck (Autor, 01.10.): Spieler, deren Name nicht lesbar ist (Schmuckschrift wie
     * JINXED MURENA), bekommen automatisch ein Namensbild ar_name_Bild-MMdd-HHmmss in
     * gelernt\arena und heissen fortan "Bild-MMdd-HHmmss" -- ueber das Bild werden sie beim
     * naechsten Turnier wiedererkannt und haben einen festen Eintrag im Notizblock.
     */
    private static final String T_NAME_PREFIX = "ar_name_";
    private static final double NAME_SCHWELLE = 0.80;

    /**
     * Wie viele Sekunden vor Rundenende spaetestens zugeschlagen wird.
     *
     * Autors beste Regel ist, spaet anzugreifen -- dann steht im Protokoll,
     * wer gegen ihn verloren hat. Wer aber zu lange wartet, bekommt null.
     * Dreissig Sekunden hat er selbst als passend bezeichnet. Seit 30.09.
     * (Autor: "in den letzten 20 Sekunden einen Gegner aussuchen und
     * angreifen") 25 s -- die Wartezeit davor gehoert dem Durchschauen.
     */
    private static final long SPAETESTENS_BEI_SEKUNDEN = 25;   // 01.10., 02:16 (Autor: "Angriffszeit reduzieren"): 25 s -- der Angriff selbst braucht ~8 s

    /** Ben 21.10.: wann die laufende Runde laut Uhr endet (beim eigenen Angriff gemerkt), 0 = unbekannt. */
    private long rundenendeErwartet;
    /** Seit wann der Bot in dieser Runde zum Angriff ansetzt (Protokoll lesen, waehlen, Vergleichsfenster), 0 = noch nicht. */
    private long angriffsphaseSeit;
    /**
     * Wie lange das im Schnitt dauert (Ben 21.10.: Median 10 s, bis 25 s -- auf langsamen PCs reichte die Reserve von 25 s
     * nicht, die Runde verstrich). Startwert 8 s.
     */
    private long vorarbeitSchnittMs = 8_000;

    /** Die letzten Vorarbeiten (Millisekunden bis zum Angriffsklick), neueste hinten -- fuer den Sicherheitsabstand. */
    private final java.util.ArrayDeque<Long> vorarbeitLetzte = new java.util.ArrayDeque<>();
    static final int VORARBEIT_MERKEN = 5;
    /** Mindestabstand zum Stage-Ende, den auch die schlechteste der letzten Vorarbeiten noch einhalten soll. */
    static final long ABSTAND_ENDE_S = 10;
    static final long SPAETESTENS_HOECHSTENS_S = 50;

    /** Ab so viel Restzeit wird angegriffen: 25 s, auf langsamen PCs entsprechend frueher (hoechstens 45 s), dazu der Sicherheitsabstand. */
    long spaetestensS() {
        return spaetestensSekunden(vorarbeitSchnittMs, new ArrayList<>(vorarbeitLetzte));
    }

    /**
     * Autor 01.11.: "in Stage 2 griff er erst in den letzten 6 Sekunden an, auf einem langsameren Rechner ginge das schief." Gemessen (Lauf 13:52): Vorarbeit
     * 23, 21, 27, 19, 24 s bei einem Schnitt von 13 bis 19 s -- der Schnitt allein liess nur 5 bis 14 s Abstand. Jetzt zaehlt die SCHLECHTESTE der letzten
     * 5 Vorarbeiten plus 10 s (Klick spaetestens 10 s vor Ende, auch wenn die naechste Vorarbeit so lang wird wie die schlechteste); die alte Regel
     * (Schnitt) bleibt als Untergrenze. "Spaet angreifen" bleibt die Absicht: die Grenze liegt bei hoechstens 50 s von 120.
     */
    static long spaetestensSekunden(long schnittMs, List<Long> letzteMs) {
        long alt = Math.min(45, SPAETESTENS_BEI_SEKUNDEN + Math.max(0, schnittMs / 1000 - 10));
        long schlechteste = 0;
        for (long l : letzteMs) schlechteste = Math.max(schlechteste, l);
        long mitAbstand = schlechteste > 0 ? schlechteste / 1000 + ABSTAND_ENDE_S : 0;
        return Math.min(SPAETESTENS_HOECHSTENS_S, Math.max(alt, mitAbstand));
    }

    /** Unter dieser Kampfstaerke gewinnt Autor nach eigener Aussage so gut wie immer. */
    private static final long SICHER_UNTER = 22_000_000L;

    /**
     * So oft blaettert der Bot hoechstens durch die Gegner, bevor er
     * zuschlaegt.
     *
     * Neun Gegner, und jeder Blick kostet einen Klick plus eine Sekunde
     * Lesen. Bei zwei Minuten Rundenzeit ist das machbar, aber es darf
     * nicht endlos gehen -- sonst dreht sich der Bot im Kreis, waehrend
     * die Uhr ablaeuft. Genau diese Sorte Schleife hat im Verlies schon
     * einmal eine Nacht gekostet.
     */
    private static final int MAX_DURCHBLAETTERN = 9;

    /**
     * Wie hoch das Schild ueber dem Wort "Reward:" reicht, in Bildpunkten.
     *
     * Darin stehen Name, Clan und Punktzahl. Gelesen wird der ganze Block
     * auf einmal, nicht in Streifen geschnitten -- die Texterkennung gibt
     * die Zeilen ohnehin einzeln zurueck, und das ist stabiler, als
     * Pixelabstaende zu raten.
     */
    private static final int SCHILD_HOEHE = 80;

    /** Ein Schild in der Pyramide. */
    private record Schild(int x, int y, int breite, String name, String clan,
                          boolean botClan, long punkte, boolean gesperrt, boolean ungern,
                          boolean sicher, boolean machbar, boolean clanListe, boolean autoListe,
                          boolean fastSicher, boolean pause) {

        /** Ohne die beiden Zwischenstufen. "pause" heisst zugleich gesperrt: nicht angreifen. */
        Schild(int x, int y, int breite, String name, String clan, boolean botClan, long punkte, boolean gesperrt, boolean ungern,
               boolean sicher, boolean machbar, boolean clanListe, boolean autoListe) {
            this(x, y, breite, name, clan, botClan, punkte, gesperrt, ungern, sicher, machbar, clanListe, autoListe, false, false);
        }

        String wer() {
            if (name.isBlank()) return Sprache.t("(name unreadable)", "(Name unlesbar)");
            return clan.isBlank() ? name : name + " / " + clan;
        }
    }

    /** Der Notizblock ueber alle bisherigen Gegner. Ueberlebt jeden Neustart. */
    private final Gegnerbuch buch = Gegnerbuch.gemeinsam();   // derselbe wie fuer !notiz

    // ---- Sets und Artefakte der Gegner (Autor, 24.09.) ------------------
    /** Sieht sich die Einheiten eines Gegners an -- siehe ArenaPruefer. */
    private final ArenaPruefer pruefer = new ArenaPruefer();

    // --- Burgen in der Wartezeit (Autor 25.10., Zweitkonto TR5) ----------------------------------------------------
    // "Der Bot soll Burgen auch in den Wartezeiten zwischen den Arena-Stages angreifen und rechtzeitig zur naechsten
    // Stage zurueck sein." Nach dem eigenen Angriff: Turnierfenster zu (Weltkarte), Burgen bis kurz vor der naechsten
    // Stage, Home -> Burg -> Weltkarte -> Arena. Nur mit Schalter burgen-plan.txt (= nur beim Zweitkonto-Bot).
    private final BurgenMode burgen = new BurgenMode();
    private boolean burgenAusflug;
    /** Seit wann der Bot vom Ausflug zurueck ist (0 = nicht) -- dann ist die Burg kein Rauswurf, sondern der Rueckweg. */
    private long burgenRueckwegSeit;
    /** So viel Zeit bis zur naechsten Stage muss nach dem eigenen Angriff mindestens bleiben (eine Burg samt Rueckweg). */
    static final long BURGEN_MIN_REST_MS = 75_000;

    // --- Kathedrale in der Wartezeit (Nutzer 25.11.) ---------------------------------------------------------------------
    // "Arena und Kathedrale parallel": nach dem eigenen Angriff (Stage 1-4) Turnierfenster zu, Kathedrale spielen, rechtzeitig
    // zurueck -- gebaut wie der Burgen-Ausflug. Die Kathedrale darf man mitten im Lauf verlassen (Home), die Reise bleibt offen;
    // Kaempfe dort dauern unter 5 s (Nutzer 25.11.). Schalter arena-kathedrale.txt / !arenacathedral, Standard aus.
    // ACHTUNG Autor 11.10.: "Niemals waehrend dem Warten auf Arena [Kathedrale spielen]" -- gemeint war die WARTESCHLANGE
    // (Turnierbeginn unbekannt). Hier nur nach dem eigenen Angriff, wo das Rundenende bekannt ist.
    public static final de.hustlebot.core.Schalterdatei KATHEDRALE_AUSFLUG =
            new de.hustlebot.core.Schalterdatei(de.hustlebot.core.Ordner.bei("arena-kathedrale.txt"), "Kathedrale in der Arena-Wartezeit", false);
    private final CathedralMode kathedrale = new CathedralMode();
    /** Eigener Zaehler je Ausflug (frische Leerlauf-Uhr): Kathedralen-Runs zaehlen nicht als Turniere. */
    private RunTracker kathedraleRuns = new RunTracker(1_000_000);
    /** Kathedralen-Runs seit dem Start der Arena (fuer das Protokoll). */
    private int kathedraleRunsGesamt;
    private boolean kathedraleAusflug;
    /** Bis wann die Kathedrale gespielt wird; danach Rueckweg (Home -> Map -> Arena). */
    private long kathedraleBis;
    /** Die Kathedrale ist fuer diesen Start fertig (keine Schluessel o. ae.) -- keine Ausfluege mehr. */
    private boolean kathedraleLeer;
    /** Mindestens so viel Zeit bis zum Rundenende nach dem eigenen Angriff (Hinweg + ein paar Kaempfe). */
    static final long KATHEDRALE_MIN_REST_MS = 30_000;
    /** So viel vor dem Ende der Ueberzeit (AUSFLUG_UEBERZEIT_MS) hoert die Kathedrale auf -- Zeit fuer den Rueckweg. */
    static final long KATHEDRALE_RUECKWEG_MS = 20_000;
    /** Was in diesem Turnier geprueft wurde, nach Vergleichsform des Namens. */
    private final java.util.Map<String, ArenaPruefer.Ergebnis> pruefungen = new java.util.HashMap<>();
    /**
     * Welcher Schluessel zu welcher Kampfstaerke gehoert (dieses Turnier). Liest die Texterkennung
     * denselben Gegner zweimal verschieden ("g 27_p" und "Lisi", 01.10., beide 24.332.344), waere
     * er sonst zweimal angesehen worden -- 16 s umsonst. Die Staerke ist auf die Einheit genau,
     * zwei Gegner mit derselben Zahl gibt es praktisch nie.
     */
    private final java.util.Map<Long, String> schluesselNachStaerke = new java.util.HashMap<>();
    /** Bei wem es in diesem Turnier schon versucht wurde (auch erfolglos). */
    private final java.util.Set<String> pruefVersucht = new java.util.HashSet<>();
    /** Der zuletzt im Vergleichsfenster gesehene Gegner (fuer den Rundlauf). */
    private String letzterGesehen = "";
    /**
     * Ab so wenig Restzeit wird keine Einheiten-Pruefung mehr gemacht bzw. eine
     * laufende abgebrochen -- zurueck zur Pyramide, gleich wird angegriffen.
     * (Bis 30.09.: 15 s, da wurde nur direkt vor dem Angriff geprueft.)
     */
    private static final long ANGRIFF_RESERVE_S = SPAETESTENS_BEI_SEKUNDEN + 3;
    /** So lange dauert eine Einheiten-Pruefung ungefaehr (6 Einheiten a ~3 s). */
    private static final long PRUEFUNG_BRAUCHT_S = 21;   // gemessen 01.10.: 16 s je Gegner, + Schirmherrschaften/Pals
    /**
     * Idee 18 (B14, 22.10.): so lange dauert eine volle Pruefung bei DIESEM Spieler-PC wirklich -- gleitender Schnitt
     * aus den letzten Pruefungen, Start mit PRUEFUNG_BRAUCHT_S. Ein schneller PC schafft so in Runde 1 mehr Gegner.
     */
    private double pruefungSchnittS = PRUEFUNG_BRAUCHT_S;
    /** Wann die laufende volle Pruefung begonnen hat, 0 = keine. */
    private long pruefungBegonnenUm;

    /** Zeitbedarf einer vollen Pruefung: gemessener Schnitt + 3 s Luft, zwischen 10 und 30 s. */
    private long pruefungBrauchtS() {
        return Math.max(10, Math.min(30, Math.round(pruefungSchnittS) + 3));
    }
    /** Nur Schirmherrschaften und Pals nachholen: zwei kleine Fenster. */
    private static final long EXTRAS_BRAUCHT_S = 6;
    /** Eine Pruefung aus dem Notizblock gilt so viele Tage. */
    private static final int PRUEFUNG_GILT_TAGE = 7;   // Autor, 30.09.: nach 7 Tagen neu ansehen (vorher 14)

    /**
     * Wer gerade angegriffen wird.
     *
     * Der Ergebnisbildschirm zeigt den Namen des Gegners nicht mehr an --
     * ohne diesen Merker liesse sich hinterher nicht eintragen, GEGEN WEN
     * gewonnen oder verloren wurde, und der Notizblock bliebe leer.
     */
    private Schild imKampfGegen;
    /** Runde:Gegner, bei denen schon einmal wegen viel hoeherer Kraft umgekehrt wurde (22.10. nachts). */
    private final java.util.Set<String> zuStarkZurueck = new java.util.HashSet<>();
    /** Autor 24.10.: "Runde:Kraft" -- ein gesperrter Gegner (an der Kraft im Vergleichsfenster erkannt) nur einmal je Runde zurueck. */
    private final java.util.Set<String> gesperrtZurueck = new java.util.HashSet<>();
    /**
     * Autor 24.10. abends: Pyramiden-Namen (Schluessel), die sich im Vergleichsfenster als gesperrt herausgestellt haben
     * (z. B. am Aussehen erkannt) -- in diesem Turnier nicht noch einmal waehlen, auch wenn ihre Kraft unbekannt ist.
     */
    private final java.util.Set<String> imFensterGesperrt = new java.util.HashSet<>();

    // ---- Listen-Netz (Autor 28.10., nur mit arena-regeln-neu.txt "an") ----------------------------------------
    /** Die Spielernamen der Listenbilder je Liste (Vorsilbe), alle 30 s neu aus den Vorlagen gelesen. */
    private java.util.Map<String, List<String>> listenNamenNetz = java.util.Map.of();
    private long listenNamenStand;
    /** "Name|Liste", je Turnier einmal ins Protokoll. */
    private final java.util.Set<String> netzGemeldet = new java.util.HashSet<>();

    private java.util.Map<String, List<String>> listenNamenFrisch(BotContext ctx) {
        long jetzt = System.currentTimeMillis();
        if (listenNamenStand == 0 || jetzt - listenNamenStand > 30_000) {
            // 29.10.: dazu die PaddleOCR-Lesung jedes Listenbildes (Dateinamen sind oft alte Lesefehler; bei Zierschrift
            // liest PaddleOCR Liste und Schild gleich -- siehe ArenaListenNetz.mitLesungen).
            listenNamenNetz = de.hustlebot.core.ArenaListenNetz.mitLesungen(
                    de.hustlebot.core.ArenaListenNetz.listenNamen(ctx.matcher().names()),
                    de.hustlebot.core.ArenaVorlagenDoppelt.listenBilder(ctx.matcher().templateRoot()));
            listenNamenStand = jetzt;
        }
        return listenNamenNetz;
    }

    /** Autor 01.11.: die Namen, die in diesem Turnier als "niemals" gelesen wurden (ein Bruchstueck davon bleibt gesperrt, siehe niemalsTeilVon). */
    private final java.util.Set<String> niemalsGelesen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Ist der Name nur der hintere Teil eines gesperrten Namens? @return der volle Name, sonst null */
    private String niemalsTeilVon(String name, BotContext ctx) {
        List<String> namen = new ArrayList<>(niemalsGelesen);
        if (de.hustlebot.core.ArenaRegelnNeu.an()) {
            List<String> l = listenNamenFrisch(ctx).get(de.hustlebot.core.ArenaListenNetz.NIEMALS);
            if (l != null) namen.addAll(l);
        }
        return de.hustlebot.core.ArenaListenNetz.teilVon(namen, name);
    }

    /** Die Kraefte, die der Notizblock zu Spielern mit diesem Bildnamen kennt (unscharf nach Name). */
    private List<Long> notizbuchKraefte(String bildName) {
        List<Long> k = new ArrayList<>();
        for (Gegnerbuch.Notiz n : buch.alle()) {
            if (n.staerke() > 0 && de.hustlebot.core.ArenaListenNetz.treffer(List.of(bildName), n.name(), 4) != null) {
                k.add(n.staerke());
            }
        }
        return k;
    }

    /** Die Kraft dieses Spielers, wenn sie bekannt ist (Erkundung dieses Turniers, sonst Notizblock), sonst -1. */
    private long kraftBekannt(String name, String clan) {
        Long erkundet = staerken.get(namensSchluessel(name));
        if (erkundet != null && erkundet > 0) return erkundet;
        return buch.nachschlagen(name, clan).map(Gegnerbuch.Notiz::staerke).filter(v -> v > 0).orElse(-1L);
    }

    /**
     * Autor 28.10.: zu welcher Liste gehoert dieser Name am Namen eines Listenbildes? Strengste Liste zuerst
     * (niemals > meiden > machbar > sicher); die Kraft muss zum Notizblock passen (+-1,5 %), wo beide bekannt sind.
     * @return die Vorsilbe der Liste ({@link de.hustlebot.core.ArenaListenNetz#NIEMALS} usw.), sonst null
     */
    private String listenNetz(String name, String clan, BotContext ctx) {
        var treffer = de.hustlebot.core.ArenaListenNetz.treffer(listenNamenFrisch(ctx), name);
        if (treffer.isEmpty()) return null;
        long seine = kraftBekannt(name, clan);
        for (var t : treffer) {
            if (!de.hustlebot.core.ArenaListenNetz.kraftVertraeglich(seine, notizbuchKraefte(t.name()))) continue;
            if (netzGemeldet.add(namensSchluessel(name) + "|" + t.liste())) {
                String listeEn = switch (t.liste()) {
                    case de.hustlebot.core.ArenaListenNetz.NIEMALS -> "never";
                    case de.hustlebot.core.ArenaListenNetz.MEIDEN -> "avoid";
                    case de.hustlebot.core.ArenaListenNetz.MACHBAR -> "doable";
                    case de.hustlebot.core.ArenaListenNetz.FAST -> "almost safe";
                    case de.hustlebot.core.ArenaListenNetz.PAUSE -> "pause";
                    default -> "safe";
                };
                String listeDe = switch (t.liste()) {
                    case de.hustlebot.core.ArenaListenNetz.NIEMALS -> "niemals";
                    case de.hustlebot.core.ArenaListenNetz.MEIDEN -> "meiden";
                    case de.hustlebot.core.ArenaListenNetz.MACHBAR -> "machbar";
                    case de.hustlebot.core.ArenaListenNetz.FAST -> "fast sicher";
                    case de.hustlebot.core.ArenaListenNetz.PAUSE -> "Pause";
                    default -> "sicher";
                };
                Log.info("Arena", Sprache.t(name + ": on the \"" + listeEn + "\" list by the name of its picture (" + t.name()
                                + ") -- the picture itself did not match.",
                        name + ": steht auf \"" + listeDe + "\" -- am Namen des Bildes (" + t.name() + ") erkannt, das Bild selbst traf nicht."));
            }
            return t.liste();
        }
        return null;
    }

    /**
     * Autor 28.10.: Sperrgrund fuer das Vergleichsfenster mit dem Sperr-Netz -- die Sperrdatei (Kraft, Name, Clan, Sets, Einheiten,
     * Aussehen, Fingerabdruck), sonst der Name eines "niemals"-Bildes (Kraft muss passen), sonst der Notizblock (0 Siege aus 3 und mehr,
     * wie dreimalVerloren). null = nicht gesperrt.
     */
    private String blockGrundNeu(String name, String clan, long kraft, String sets, String einheiten,
                                 ArenaSperrBilder.Merkmale aussehen, String fingerabdruck, BotContext ctx) {
        String g = de.hustlebot.core.ArenaSperre.sperrgrund(name, clan, kraft, sets, einheiten, aussehen, fingerabdruck);
        if (g != null) return g;
        if (name == null || name.isBlank()) return null;
        var treffer = de.hustlebot.core.ArenaListenNetz.treffer(listenNamenFrisch(ctx), name);
        for (var t : treffer) {
            if (!t.liste().equals(de.hustlebot.core.ArenaListenNetz.NIEMALS)) continue;
            if (de.hustlebot.core.ArenaListenNetz.kraftVertraeglich(kraft, notizbuchKraefte(t.name()))) {
                return Sprache.t("name of the \"never\" picture " + t.name(), "Name des niemals-Bildes " + t.name());
            }
        }
        int[] b = buch.bilanzNachName(name, kraft, fingerabdruck);
        if (b[0] == 0 && b[1] >= de.hustlebot.core.ArenaSperre.NIEDERLAGEN_BIS_SPERRE) {
            return Sprache.t(b[1] + " losses, no win (notebook)", b[1] + " Niederlagen, kein Sieg (Notizblock)");
        }
        return null;
    }

    /** Aussehen des Gegners beim Klick auf "Quick Attack" -- kommt mit dem Ergebnis in die Sperrliste (Autor 24.10.). */
    private ArenaSperrBilder.Merkmale kampfAussehen;

    /**
     * Wer in dieser Runde laut Kampfprotokoll gegen Autor verloren hat.
     *
     * Autors beste Regel: "wenn ich sehe das ein spieler gegen mich
     * verloren hat, greife ich ihn natuerlich auch eher an, weil die
     * chance hoch ist das ich wieder gewinne."
     */
    private final java.util.Set<String> gegenMichVerloren = new java.util.HashSet<>();
    /** Wer mich (Verteidigung) diese Runde BESIEGT hat -- +30 Punkte (Autor 08.10.; 25.09. +20). */
    private final java.util.Set<String> gegenMichGewonnen = new java.util.HashSet<>();

    /**
     * Wen Autor in DIESEM Turnier schon angegriffen hat -- in Vergleichsform.
     *
     * ------------------------------------------------------------------
     *  Warum es das braucht
     * ------------------------------------------------------------------
     *
     * Autors Regel: "ich kann jede person immer nur einmal angreifen pro
     * turnier, egal ob ich gegen sie verloren oder gewonnen habe."
     *
     * Bisher verliess sich der Bot allein darauf, dass ein verbrauchter
     * Gegner in der Pyramide kein "Reward:" mehr traegt. Das stimmt auch --
     * aber es ist EINE Quelle, und sie haengt an einer Texterkennung, die
     * ein Wort auch mal ueberliest.
     *
     * Der Anlass war das Kampfprotokoll. Autor: "wenn du im battle log
     * einen spieler siehst der gegen mich verloren hat, soll der bot bitte
     * auch checken ob ich diesen spieler schon angegriffen hatte." Genau
     * der Fall: das Protokoll zeigt jemanden, der an Autor gescheitert ist,
     * der Bot haelt ihn fuer die sichere Bank -- und Autor hat ihn selbst
     * schon abgeraeumt. Der Bonus zeigt dann auf ein Ziel, das es nicht
     * mehr gibt.
     *
     * ------------------------------------------------------------------
     *  Zwei Quellen, und die zweite ist die wichtigere
     * ------------------------------------------------------------------
     *
     * WAS DER BOT SELBST TAT. Beim Schnellangriff wird der Name vermerkt,
     * der im Vergleichsfenster steht.
     *
     * WAS IM PROTOKOLL STEHT. Jede Zeile, in der Autor LINKS steht, ist ein
     * Angriff von ihm. Das faengt auch die Angriffe, die er von Hand
     * gemacht hat, bevor der Bot uebernahm -- und die kann der Bot sonst
     * unmoeglich kennen.
     *
     * Fuer diese Liste ist der Ausgang gleichgueltig. Angriff ist Angriff.
     *
     * Sie gilt fuer das ganze Turnier, nicht fuer eine Runde -- deshalb
     * wird sie in neuesTurnier() geleert und nicht in neueRunde().
     */
    private final java.util.Set<String> schonAngegriffen = new java.util.HashSet<>();

    /**
     * Live 29.10. 21:20: Runde 1 mit 15 s Rest "Quick attack" -- die Runde lief ab, kein Kampf ("Round missed"). Der
     * Gegner (ionette, 95 %) stand trotzdem als verbraucht da und fehlte in Runde 5 als sicherer Sieg. Darum merkt
     * sich "Eigener Angriff", wen er eingetragen hat, bis ein Ergebnis kommt; meldet das Spiel die Runde als
     * verpasst, wird der Eintrag zurueckgenommen.
     */
    private String angriffOhneErgebnis = null;
    private int angriffOhneErgebnisRunde = -1;

    /** Damit das Protokoll nur einmal je Runde aufgeklappt wird. */
    private int protokollFuerRunde = -1;

    /** Ab dieser Kraft wird ein Gegner ohne Clan komplett angesehen (Hauptbot 59b667a, Autor 13.10.). */
    static final long OHNE_CLAN_TEAM_AB = 50_000_000L;

    /** Je Turnier und Name nur einmal in die Verlierer-Liste (das Protokoll wird je Runde neu gelesen). */
    private final java.util.Set<String> verliererVermerkt = new java.util.HashSet<>();

    /** Punkt 14: in arena-verlierer.txt eintragen; true = Name war dort noch nie. */
    private boolean merkeVerlierer(String name, boolean angriff) {
        String k = namensSchluessel(name) + (angriff ? "<" : ">");
        if (k.length() <= 1 || !verliererVermerkt.add(k)) return false;
        return de.hustlebot.core.ArenaVerlierer.vermerken(name, angriff);
    }

    /** Die im Vergleichsfenster abgelesene Staerke des Gegners, fuer den Notizblock. */
    private long letzteGegnerstaerke = -1;

    /**
     * Steht gerade "Next stage in:" -- der eigene Angriff ist durch.
     *
     * Der Wechsel dieser Anzeige zurueck zu "Select the next opponent" ist
     * das verlaessliche Zeichen fuer eine neue Runde.
     */
    private boolean zwischenDenRunden;

    /** Damit derselbe Kampfausgang nur einmal im Protokoll steht. */
    private boolean ergebnisGemeldet;

    /**
     * Ein Kampfausgang, der noch nicht eingetragen ist, weil die Zaehler auf
     * dem Ergebnisbild noch hochlaufen. ergebnisSeit 0 = nichts offen.
     */
    /** Fuer die kurze Discord-Zeile nach jedem Turnier (Autor, 01.10.): Dauer, Wartezeit, Platz. */
    private long turnierPyramideUm, turnierWartezeitMs;
    /** Autor 24.10.: wann die Warteschlange zuletzt im Bild war -- misst, wie lange der Bot die Pyramide danach nicht sah. */
    private long warteschlangeLetztesBildUm;
    /** Schalter "!arenabericht an|aus". */
    public static final de.hustlebot.core.Schalterdatei BERICHT =
            new de.hustlebot.core.Schalterdatei(java.nio.file.Path.of("arenabericht.txt"), "Arena-Bericht nach jedem Turnier", true);
    /** Wann das Kampfprotokoll zuletzt gelesen wurde (seit 01.10. auch waehrend des Wartens). */
    private long protokollGelesenUm;
    /** Pruefer 29.10.: wann das Protokoll aufgeklappt wurde (0 = nicht gemessen) und wie lange das Lesen im Schnitt dauert. */
    private long protokollAufgeklapptUm;
    private long protokollDauerMs = 8_000;
    /** Schilder (x,y), bei denen in dieser Runde schon kein Fingerabdruck angelegt wurde. */
    private final java.util.Set<String> fingerabdruckAbgelehnt = new java.util.HashSet<>();
    /**
     * Nach einem schnellen Sieg das Namensbild des Gegners fuer die Listen aufnehmen (Autor,
     * 01.10.): unter 7 s -> ar_sicher (-40), 7-14 s -> ar_machbar (-15), laenger -> nichts;
     * nach der dritten Niederlage ar_ungern (+15). Aufgenommen wird in der Pyramide derselben
     * Runde (das Schild steht dort noch an derselben Stelle), gespeichert in gelernt\arena.
     */
    private Schild vorlageFuer;
    private String vorlageArt;
    private int vorlageDauer, vorlageRunde;
    private long vorlageSeit;

    /**
     * Autor 28.10.: bei JEDEM Angriff vorher das Namensschild aus der noch ruhigen Pyramide ausschneiden. Nach dem
     * Kampf hat sich die Pyramide oft umgeordnet ("Schild nicht mehr an derselben Stelle lesbar") -- kommt der
     * Gegner dann auf eine Liste, nimmt der Bot dieses Bild. Review 28.10.: nur im Speicher und nur fuer GENAU
     * diesen Angriff (Name + Clan) -- ein altes Bild eines anderen Spielers darf nie auf eine Liste kommen.
     */
    private String vorAngriffFuer;
    private BufferedImage vorAngriffAusschnitt;

    private void namensbildVorAngriff(Schild s, BotContext ctx) {
        vorAngriffFuer = null;
        vorAngriffAusschnitt = null;
        if (!de.hustlebot.core.ArenaSchalter.an()) return;   // Autor 28.10.: erst nur in seinem Bot
        if (s.name().isBlank() || s.botClan()) return;   // ohne Namen keine Zuordnung; "Not in clan" kommt auf keine Liste
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int oben = s.y() - SCHILD_HOEHE;
        java.awt.Rectangle kasten = textKasten(bild, s.x() - (int) (ctx.width() * 0.046), oben + 12,
                s.x() + (int) (ctx.width() * 0.070), oben + 33);
        if (kasten == null) return;
        // Kopie -- das Bild des Bots wird beim naechsten Foto ueberschrieben.
        BufferedImage teil = bild.getSubimage(kasten.x, kasten.y, kasten.width, kasten.height);
        BufferedImage kopie = new BufferedImage(teil.getWidth(), teil.getHeight(), BufferedImage.TYPE_INT_RGB);
        kopie.getGraphics().drawImage(teil, 0, 0, null);
        vorAngriffFuer = vorAngriffSchluessel(s);
        vorAngriffAusschnitt = kopie;
        angegriffenSpeichern(s, kopie);
    }

    // --- Namensbild JEDES angegriffenen Spielers (Autor 29.10.) ---------------------------------------------
    /**
     * Autor 29.10.: "Wenn der Bot einen Spieler angreift, auch wenn er ihn zu keiner Liste hinzufuegt, soll er immer ein
     * Bild von dem Namen machen -- dass wenn ich im Nachhinein sage, er soll den Spieler aufnehmen, er das Namensbild
     * sofort bereit hat." Ordner arena-angegriffen (NICHT unter templates -- dort wuerde jedes Bild als Vorlage geladen),
     * je Spieler eine Datei (die neueste gilt), dazu liste.txt "Name | Clan | Datei | Zeit".
     */
    private static final java.nio.file.Path ANGEGRIFFEN = java.nio.file.Path.of("arena-angegriffen");
    private long angegriffenGeprueftUm;

    private void angegriffenSpeichern(Schild s, BufferedImage bild) {
        try {
            String kurz = s.name().replaceAll("[^\\p{L}\\p{N}]", "");
            if (kurz.isEmpty()) return;
            String clanKurz = s.clan().replaceAll("[^\\p{L}\\p{N}]", "");
            String datei = kurz + (clanKurz.isEmpty() ? "" : "_" + clanKurz) + ".png";
            java.nio.file.Files.createDirectories(ANGEGRIFFEN);
            javax.imageio.ImageIO.write(bild, "png", ANGEGRIFFEN.resolve(datei).toFile());
            java.nio.file.Path liste = ANGEGRIFFEN.resolve("liste.txt");
            java.util.List<String> zeilen = new ArrayList<>();
            if (java.nio.file.Files.exists(liste)) {
                for (String z : java.nio.file.Files.readAllLines(liste, java.nio.charset.StandardCharsets.UTF_8)) {
                    String[] t = z.split("\\|");
                    if (t.length >= 3 && t[2].strip().equals(datei)) continue;   // aelterer Eintrag derselben Datei
                    zeilen.add(z);
                }
            }
            zeilen.add(s.name().strip() + " | " + s.clan().strip() + " | " + datei + " | "
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            java.nio.file.Files.write(liste, zeilen, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Arena", "Namensbild des Angegriffenen nicht gespeichert: " + e.getMessage());
        }
    }

    /**
     * Steht ein Spieler in arena-aufnehmen.txt ("!arena Name niemals"), fuer den schon ein Namensbild von einem
     * Angriff da ist: sofort in die Liste legen, nicht erst auf die naechste Begegnung warten. Hoechstens alle 10 s.
     */
    private void aufZurufAusGespeichert(BotContext ctx) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - angegriffenGeprueftUm < 10_000) return;
        angegriffenGeprueftUm = jetzt;
        java.util.List<String[]> wuensche = aufnehmenLaden();
        java.nio.file.Path liste = ANGEGRIFFEN.resolve("liste.txt");
        if (wuensche.isEmpty() || !java.nio.file.Files.exists(liste)) return;
        try {
            java.util.List<String> eintraege = java.nio.file.Files.readAllLines(liste, java.nio.charset.StandardCharsets.UTF_8);
            for (String[] w : wuensche) {
                String art = listenArt(w[1]);
                if (art == null) continue;
                for (int i = eintraege.size() - 1; i >= 0; i--) {   // neueste zuerst
                    String[] t = eintraege.get(i).split("\\|");
                    if (t.length < 3 || !de.hustlebot.core.NamensVergleich.gleicherName(w[0], t[0].strip())) continue;
                    java.nio.file.Path quelle = ANGEGRIFFEN.resolve(t[2].strip());
                    if (!java.nio.file.Files.exists(quelle)) continue;
                    String kurz = t[0].strip().replaceAll("[^\\p{L}\\p{N}]", "");
                    String stempel = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmm"));
                    java.nio.file.Path ziel = de.hustlebot.core.ArenaVorlagenDoppelt.ordnerFuer(ctx.matcher().templateRoot(), art)
                            .resolve(art + kurz + "_" + stempel + ".png");
                    for (var alt : de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(w[0], ctx.matcher().templateRoot())) {
                        java.nio.file.Path weg = de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(de.hustlebot.core.Ordner.basis(), alt.datei());
                        java.nio.file.Files.createDirectories(weg.getParent());
                        java.nio.file.Files.move(alt.datei(), weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    java.nio.file.Files.createDirectories(ziel.getParent());
                    java.nio.file.Files.copy(quelle, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    aufnehmenStreichen(w[0]);
                    ctx.matcher().vorlageNachladen(ziel);
                    Log.info("Arena", Sprache.t("Recorded on request from the picture of the last attack: " + ziel.getFileName()
                                    + " -- " + t[0].strip() + " is now on \"" + w[1] + "\".",
                            "Auf Zuruf aufgenommen (Bild vom letzten Angriff): " + ziel.getFileName()
                                    + " -- " + t[0].strip() + " steht ab jetzt auf \"" + w[1] + "\"."));
                    break;
                }
            }
        } catch (Exception e) {
            Log.debug("Arena", "Aufnehmen aus gespeicherten Namensbildern: " + e.getMessage());
        }
    }

    /** "sicher"/"safe" ... -> Listen-Praefix; null = unbekanntes Wort. */
    private static String listenArt(String wort) {
        return wort.startsWith("fast") || wort.startsWith("almost") ? T_FAST_PREFIX
                : wort.startsWith("pause") ? T_PAUSE_PREFIX
                : wort.startsWith("sicher") || wort.startsWith("safe") ? T_SICHER_PREFIX
                : wort.startsWith("meiden") || wort.startsWith("ungern") || wort.startsWith("avoid") ? T_UNGERN_PREFIX
                : wort.startsWith("machbar") || wort.startsWith("doable") ? T_MACHBAR_PREFIX
                : wort.startsWith("niemals") || wort.startsWith("never") || wort.startsWith("block") ? T_BLOCK_PREFIX : null;
    }

    /** Die Zeile dieses Spielers aus arena-aufnehmen.txt streichen. */
    private void aufnehmenStreichen(String name) throws java.io.IOException {
        java.util.List<String> rest = new ArrayList<>();
        for (String z : java.nio.file.Files.readAllLines(AUFNEHMEN_DATEI, java.nio.charset.StandardCharsets.UTF_8)) {
            if (z.contains("|") && de.hustlebot.core.NamensVergleich.gleicherName(z.split("\\|", 2)[0].strip(), name)) continue;
            rest.add(z);
        }
        java.nio.file.Files.write(AUFNEHMEN_DATEI, rest, java.nio.charset.StandardCharsets.UTF_8);
        aufnehmenListe = null;
    }

    private String vorAngriffSchluessel(Schild s) {
        return namensSchluessel(s.name()) + "|" + s.clan().strip().toLowerCase();
    }

    /** Je Runde und Spieler nur einmal versuchen (auto-Bilder). */
    private final java.util.Set<String> autoBildVersucht = new java.util.HashSet<>();

    /**
     * Autor 28.10.: Gegner, die der Bot SELBST einstuft -- aus dem Notizblock (2 von 3 gewonnen: sicher/machbar) oder
     * gesperrt (3 Niederlagen ohne Sieg) --, bekommen ein echtes Namensbild in Autors Listen-Ordner, damit er alle auf
     * einen Blick sieht. Nur wenn der Spieler noch auf KEINER Liste steht (Autors eigene Wahl geht immer vor) und Autor
     * ein solches Bild nicht schon einmal geloescht hat. Endung "_auto_MMdd-HHmm" (ArenaListenOrdner).
     */
    private void autoListenBilder(List<Schild> schilder, BotContext ctx, int runde, BufferedImage bild) {
        if (!de.hustlebot.core.ArenaSchalter.an() || bild == null) return;   // Autor 28.10.: erst nur in seinem Bot
        for (Schild s : schilder) {
            if (s.name().isBlank() || s.botClan() || s.gesperrt() || s.ungern()) continue;
            // "niemals" nur aus der reinen Bilanz (Review 28.10.): nicht aus einer Vermutung wie gleicher Kraft.
            int[] b = gesamtBilanz(s);
            String liste = s.autoListe() && s.sicher() ? T_SICHER_PREFIX
                    : s.autoListe() && s.fastSicher() ? T_FAST_PREFIX
                    : s.autoListe() && s.machbar() ? T_MACHBAR_PREFIX
                    : b[0] == 0 && b[1] >= 3 ? T_BLOCK_PREFIX : null;
            if (liste == null) continue;
            if (!autoBildVersucht.add(runde + "|" + namensSchluessel(s.name()) + "|" + liste)) continue;
            java.nio.file.Path wurzel = ctx.matcher().templateRoot();
            if (!de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(s.name(), wurzel).isEmpty()) continue;
            if (de.hustlebot.core.ArenaListenOrdner.vonAutorEntfernt(s.name())) continue;
            int oben = s.y() - SCHILD_HOEHE;
            java.awt.Rectangle kasten = textKasten(bild, s.x() - (int) (ctx.width() * 0.046), oben + 12,
                    s.x() + (int) (ctx.width() * 0.070), oben + 33);
            if (kasten == null) continue;
            BufferedImage ausschnitt = bild.getSubimage(kasten.x, kasten.y, kasten.width, kasten.height);
            // Dasselbe Bild schon in einer Liste (anders gelesener Name)? Dann nicht noch einmal.
            if (de.hustlebot.core.ArenaVorlagenDoppelt.finde(ausschnitt, wurzel, null).isPresent()) continue;
            java.nio.file.Path ziel = de.hustlebot.core.ArenaVorlagenDoppelt.ordnerFuer(wurzel, liste)
                    .resolve(de.hustlebot.core.ArenaListenOrdner.autoDateiname(liste, s.name(), null));
            try {
                java.nio.file.Files.createDirectories(ziel.getParent());
                javax.imageio.ImageIO.write(ausschnitt, "png", ziel.toFile());
                de.hustlebot.core.ArenaListenOrdner.autoBildGemerkt(ziel);
                ctx.matcher().vorlageNachladen(ziel);
                String wort = liste.equals(T_SICHER_PREFIX) ? Sprache.t("safe", "sicher")
                        : liste.equals(T_FAST_PREFIX) ? Sprache.t("almost safe", "fast sicher")
                        : liste.equals(T_MACHBAR_PREFIX) ? Sprache.t("doable", "machbar") : Sprache.t("never", "niemals");
                Log.info("Arena", Sprache.t("Name image for " + s.wer() + " put into your list \"" + wort
                                + "\" (the bot rated this player itself): " + ziel.getFileName(),
                        "Namensbild von " + s.wer() + " in deine Liste \"" + wort
                                + "\" gelegt (vom Bot selbst eingestuft): " + ziel.getFileName()));
            } catch (Exception e) {
                Log.debug("Arena", "Auto-Namensbild nicht gespeichert: " + e.getMessage());
            }
        }
    }

    /** Das vor DIESEM Angriff gemerkte Namensschild, wenn es zu diesem Gegner gehoert; sonst null. */
    private BufferedImage vorAngriffBild(Schild s) {
        return s != null && vorAngriffAusschnitt != null && vorAngriffSchluessel(s).equals(vorAngriffFuer)
                ? vorAngriffAusschnitt : null;
    }
    private long ergebnisSeit;
    private boolean ergebnisSieg;
    private long ergebnisPunkte = -1, ergebnisDauer = -1, ergebnisStaerke = -1;
    /** Was der Bot beim Angriff geschaetzt hat -- fuer arena-lernen.txt (08.10.). */
    private double kampfChance = -1;
    private Integer kampfPunkte;
    private int kampfReferenz = Integer.MIN_VALUE;
    private boolean kampfTop3;
    /** Auswertung 30.10.: Rest-Sekunden beim Angriff und Platz des Gegners in der Pyramide, -1 = unbekannt. */
    private long kampfRest = -1;
    private int kampfPlatz = -1;

    /**
     * Wen der Bot in diesem Turnier schon im Vergleichsfenster gesehen hat.
     *
     * Taucht ein Name zum zweiten Mal auf, ist der Rundlauf einmal herum --
     * dann sind alle erkundet.
     */
    private final java.util.Set<String> erkundet = new java.util.LinkedHashSet<>();

    /** Kampfstaerke je Gegner, einmal gelesen und das ganze Turnier gueltig. */
    private final java.util.Map<String, Long> staerken = new java.util.HashMap<>();
    /** Abweichende Kraft-Lesung, die noch auf eine zweite gleiche wartet (Pruefung 29.10.). */
    private final java.util.Map<String, Long> kraftKandidaten = new java.util.HashMap<>();

    /**
     * Autor 01.11.: "my 4055264" statt 42055264 (Ziffer verloren). Das Vergleichsfenster las die eigene Kraft in allen Laeufen so, meineStaerke wurde
     * 4,05 Mio, und "Kraft ... passt nicht zur eigenen" verwarf 49 + 59 Gegner-Kraefte -- dazu die 15/16 "power not readable". Eine eigene Kraft, die um
     * mehr als das Doppelte von der bekannten abweicht (Truppenfenster, arena-ich-team.txt), ist ein Lesefehler: bekannte Kraft gilt.
     * @return die gelesene Kraft, wenn sie glaubhaft ist; sonst -1
     */
    static long eigeneKraftPruefen(long gelesen, long bekannt) {
        if (gelesen <= 0) return -1;
        if (bekannt <= 0) return gelesen;
        return gelesen >= bekannt / 2 && gelesen <= bekannt * 2 ? gelesen : -1;
    }

    /** Wo die gekreuzten Schwerter im vorigen Bild des Vergleichsfensters lagen (Autor 01.11.: das Fenster gleitet beim Oeffnen herein). */
    private int fensterSchwerterX = -1000, fensterSchwerterY = -1000;
    private long fensterSchwerterUm;
    /** So viele Bildpunkte darf sich das Fenster zwischen zwei Bildern verschieben und gilt trotzdem als fertig geoeffnet. */
    static final int FENSTER_RUHE_PUNKTE = 1;
    /** Das vorige Bild darf nicht aelter sein (ms): sonst war es ein anderes Fenster. */
    static final long FENSTER_RUHE_MS = 2_000;

    /**
     * Steht das Vergleichsfenster still? Die gekreuzten Schwerter liegen im selben Punkt (hoechstens {@link #FENSTER_RUHE_PUNKTE} daneben) wie im Bild davor, und das ist nicht
     * laenger als {@link #FENSTER_RUHE_MS} her. Beim Hereingleiten liegen sie in jedem Bild woanders. Das erste Bild eines Fensters gilt nie als fertig.
     */
    static boolean fensterStill(int x, int y, int vorherX, int vorherY, long seitMs) {
        return seitMs >= 0 && seitMs <= FENSTER_RUHE_MS && Math.abs(x - vorherX) <= FENSTER_RUHE_PUNKTE && Math.abs(y - vorherY) <= FENSTER_RUHE_PUNKTE;
    }

    /** Verlesene eigene Kraefte, je Wert nur einmal ins Protokoll. */
    private final java.util.Set<Long> eigeneKraftVerlesen = new java.util.HashSet<>();

    /** Kraft im Vergleichsfenster glaubhaft? 7-9 Stellen und (wenn die eigene bekannt ist) 0,2- bis 3-mal so viel. */
    static boolean kraftPlausibel(long seine, long meine) {
        if (seine < 1_000_000L || seine > 999_999_999L) return false;
        return meine <= 0 || (seine >= meine / 5 && seine <= meine * 3);
    }

    /** Weicht die Lesung > 5 % vom Bekannten ab, zaehlt sie erst, wenn sie zweimal (bis 0,5 % gleich) kommt. */
    boolean kraftBestaetigt(String schluessel, long seine, long vorher) {
        if (Math.abs(seine - vorher) <= vorher * 0.05) { kraftKandidaten.remove(schluessel); return true; }
        Long kandidat = kraftKandidaten.put(schluessel, seine);
        if (kandidat != null && Math.abs(kandidat - seine) <= seine * 0.005) { kraftKandidaten.remove(schluessel); return true; }
        return false;
    }
    /**
     * Idee 2/5 (22.10.): Schild-Name -> Name im Vergleichsfenster, wenn beide verschieden gelesen wurden (ueber den Platz
     * verknuepft). Arena 1, 14:17: юра und RedWineClub standen als "Team unbekannt" da, ihre Pruefung lag unter
     * "'\"Benbli n TO" und "2811".
     */
    private final java.util.Map<String, String> schildAlias = new java.util.HashMap<>();
    /** Gesperrter Gegner im Vergleichsfenster, dessen X noch nicht ging (Code-Review 26.10.). */
    private String sperreOffen;

    // --- Durchschauen in der Wartezeit jeder Stage (Autor, 30.09.) -------------
    /** Ins Vergleichsfenster gegangen, um durchzuschauen -- NICHT zum Angreifen. */
    private boolean nurErkunden;
    /** Alle Gegner dieses Turniers durchgeschaut (Kraft; Sets/Artefakte, wo noetig). */
    private boolean allesErkundet;
    /** Im laufenden Rundgang gesehene Gegner; taucht einer wieder auf, ist man einmal herum. */
    private final java.util.Set<String> rundgang = new java.util.HashSet<>();
    /** Wann erkundungsSchritt zuletzt lief -- die Pyramide dazwischen ist nur das Umblaettern. */
    private long letzterErkundungsBlick;
    /** Wann "Quick Attack" gedrueckt wurde; 0, sobald der Ergebnisbildschirm da war. */
    private long angriffGeklicktUm;
    /** Wann in dieser Stage angegriffen wurde -- bleibt nach dem Ergebnis stehen (Bericht #H77K, 22.10.). */
    private long eigenerAngriffUm;
    /** Seit wann im Angriffszweig kein orangener "Quick Attack" zu sehen ist (0 = ist da). */
    private long quickFehltSeit;
    /** Diese Stage ist schon gespielt (graue Knoepfe) -- bis zur naechsten Runde nur warten. */
    private boolean rundeGespielt;
    /**
     * Wann die letzte Einheiten-Pruefung zu Ende ging. Das Bild direkt danach zeigt ihr
     * Fenster noch beim Zugehen -- ein zweiter Klick auf das X traf das Vergleichsfenster
     * (01.10., 00:46), und die Pyramide hielt das Durchschauen fuer beendet.
     */
    private long pruefungFertigUm;
    /** Graue Knoepfe ohne eigenen Angriff in dieser Runde: der Gegner war schon verbraucht. */
    private int grauInRunde;
    /** Schilder (x, y), die in dieser Runde zu grauen Knoepfen fuehrten -- nicht noch einmal waehlen. */
    private final List<int[]> graueSchilder = new ArrayList<>();
    /** Warteschlange: so viele von 10 Spielern sind schon da (Autor, 01.10.). -1 = nicht gelesen. */
    private int gefundenZuletzt = -1;
    private long gefundenGelesenUm;
    /**
     * Wann zuletzt auf den Blaetterpfeil geklickt wurde, 0 = kein Klick offen. Das Bild danach
     * zeigt oft noch den alten Gegner (01.10., 01:11 und 01:12): der Bot klickte ein zweites
     * Mal (Platzzaehlung um eins daneben) und begann sogar eine Einheiten-Pruefung unter dem
     * alten Namen, waehrend das Fenster schon den naechsten zeigte -- FLIMSY wurde zweimal
     * angesehen, einmal als "Xuyc". Jetzt wird erst weitergemacht, wenn ein ANDERER dasteht.
     */
    private long blaetterKlickUm;
    /** Seit wann weder Name noch Kraft lesbar sind (0 = lesbar). Kurz warten statt ueberspringen. */
    private long unlesbarSeit;
    /** In welcher Runde zuletzt alles durchgeschaut war (0 = noch nie in diesem Turnier). */
    private int allesErkundetRunde;
    private int rundgangBlaetter;
    /**
     * Autor 17.10. ("in der letzten Runde schaut er sich jeden Gegner 20 Mal an, aber immer nur das Oberflaechliche"):
     * gemessen 140 Mal "Next opponent" in Runde 5 ohne eine einzige Einheiten-Pruefung -- er hoerte erst auf, wenn
     * alle durchgeschaut waren, und das wurde in der Endrunde nie wahr. Stand der Pruefungen am Anfang des Rundgangs:
     * bringt ein ganzer Rundgang nichts Neues, ist fuer diese Runde Schluss mit Blaettern.
     */
    private int pruefStandBeiRundgang = -1;
    /** Gegner ohne Clan (werden nicht geprueft, zaehlen beim Rundgang als erledigt). */
    private final java.util.Set<String> ohneClanGesehen = new java.util.HashSet<>();

    /** Autors eigene Kampfstaerke, im Vergleichsfenster abgelesen. */
    private long meineStaerke = -1;

    /** Damit die Angriffsmeldung nicht hundertfach im Protokoll steht. */
    private long letzteAngriffsmeldung;

    /**
     * Wie oft hoechstens weitergeblaettert wird, wenn nur noch gesperrte
     * Gegner kommen.
     *
     * Ohne diese zweite Grenze hatte der Fall "gesperrt UND alle schon
     * gesehen" gar keinen Ausgang: beide Zweige griffen nicht, und der Bot
     * blaetterte bis zum Rundenende im Kreis.
     */
    private static final int MAX_BLAETTERN_HART = 2 * MAX_DURCHBLAETTERN;

    /**
     * So lange gilt die Warteschlange als Fortschritt (kein Haenger-Alarm), danach
     * greift der Wachhund. Autor, 11.10. 15:00: "Wenn die Wartezeit einer Arena auf
     * ueber 3 min steigt, bitte keinen Cathedral-Run machen. Niemals waehrend dem
     * Warten auf Arena, einfach warten." -- die Pause mit dem Kathedralen-Run
     * dazwischen (30.09.) ist weg; vorher 7 Minuten.
     */
    private static final long WARTESCHLANGE_GEDULD_MS = 30 * 60_000L;

    /** Wann zuletzt "Map" gedrueckt wurde -- der zweite Klick traefe auf der Karte "Home". */
    private long karteGeklicktUm;

    // --- Einschuebe warten auf das Turnierende (Autor, 11.10. 15:00) ------------------
    /**
     * Der Motor hat einen Wechsel angemeldet (Clan-Krieg, Turm, Leuchtturm, Laden,
     * Freunde, !start, Spiel-Neustart). Autor: "Dann muss er erst die Arena beenden."
     * Das laufende Turnier wird zu Ende gespielt, ein neues faengt nicht an.
     */
    private volatile boolean wechselErbeten;
    /** Turnierfenster zu (oder gar nicht offen) -- jetzt darf der Motor wechseln. */
    private volatile boolean wechselBereit;
    /**
     * Nachspiel des Motors (11.10.): Oben stand der Turnier-Balken, obwohl ein anderer
     * Modus dran war. Nur das laufende Turnier zu Ende spielen, kein neues anfangen.
     */
    private volatile boolean nurZuEndeSpielen;
    /** Der Abschlussbildschirm ("Tournament ended") steht noch -- noch nicht draussen. */
    private boolean imAbschluss;

    /** Vom Motor: ein Einschub wartet (true) bzw. ist zurueckgenommen (false). */
    public void wechselErbeten(boolean an) {
        wechselErbeten = an;
        if (!an) wechselBereit = false;
    }

    /**
     * Nachspiel: das Turnier ist durch (Abschluss abgehakt oder Turnierfenster zu) -- jetzt nur
     * noch zurueck in die Burg, dort uebernimmt der eigentliche Modus (Pruefung 11.10.: vorher
     * blieb das Spiel auf der Weltkarte stehen, und die Nahrungskisten des anderen Modus
     * warteten vergeblich auf die Burg).
     */
    private boolean nachspielHeimweg;
    /** Bilder auf dem Heimweg, damit er nicht endlos dauert. */
    private int heimwegBilder;
    /** So viele Bilder darf der Heimweg brauchen, dann gilt das Nachspiel trotzdem als fertig. */
    static final int HEIMWEG_BILDER_MAX = 30;

    /** Vom Motor: nur das laufende Turnier zu Ende spielen (Nachspiel), kein neues. */
    public void nurZuEndeSpielen(boolean an) {
        nurZuEndeSpielen = an;
        nachspielHeimweg = false;
        heimwegBilder = 0;
    }

    /** Was auf dem Heimweg nach dem Nachspiel zu tun ist. */
    enum Heimweg {
        /** In der Burg -- das Nachspiel ist fertig. */
        FERTIG,
        /** Auf der Weltkarte: "Home" unten links. */
        WELTKARTE_HEIM,
        /** Woanders: der gruene "Home"-Knopf. */
        HOME_KNOPF,
        /** Zu lange unterwegs -- aufhoeren, der eigentliche Modus findet den Weg selbst. */
        AUFGEBEN
    }

    /** Die reine Entscheidung fuer den Heimweg (getrennt, damit sie sich pruefen laesst). */
    static Heimweg heimwegSchritt(boolean inBurg, boolean aufWeltkarte, int bilder) {
        if (inBurg) return Heimweg.FERTIG;
        if (bilder > HEIMWEG_BILDER_MAX) return Heimweg.AUFGEBEN;
        if (aufWeltkarte) return Heimweg.WELTKARTE_HEIM;
        return Heimweg.HOME_KNOPF;
    }

    /** Nach dem Nachspiel zurueck in die Burg; nie wieder auf die Arena-Insel klicken. */
    private Outcome nachspielHeimweg(Mat screen, BotContext ctx) {
        boolean inBurg = ctx.visible(screen, T_MAP_BUTTON) || ctx.visibleIfKnown(screen, T_MAP_ALT);
        switch (heimwegSchritt(inBurg, Weltkarte.erkannt(screen, ctx), ++heimwegBilder)) {
            case FERTIG -> {
                Log.info("Arena", Sprache.t("Follow-up done -- the game is back in the castle.",
                        "Nachspiel fertig -- das Spiel steht wieder in der Burg."));
                return Outcome.FINISHED;
            }
            case WELTKARTE_HEIM -> {
                Weltkarte.heim(screen, ctx, Sprache.t("Arena (follow-up done)", "Arena (Nachspiel fertig)"));
                return Outcome.ACTED;
            }
            case HOME_KNOPF -> {
                if (ctx.clickIfVisible(screen, T_HOME_BUTTON, Sprache.t("Back to the castle (follow-up done)", "Zurueck in die Burg (Nachspiel fertig)"))) {
                    return Outcome.ACTED;
                }
                return Outcome.UNKNOWN;
            }
            default -> {
                warnung(Sprache.t("Follow-up: not back in the castle after " + heimwegBilder + " pictures -- "
                        + "the actual mode carries on from here.",
                        "Nachspiel: nach " + heimwegBilder + " Bildern nicht zurueck in der Burg -- "
                        + "der eigentliche Modus macht von hier aus weiter."));
                return Outcome.FINISHED;
            }
        }
    }

    /** Laeuft gerade ein Turnier (begonnen, nicht beendet -- auch in der Wartezeit)? */
    public boolean turnierLaeuft() {
        return imTurnier;
    }

    /** Draussen erst, wenn kein Turnier laeuft und der Abschlussbildschirm zu ist. */
    @Override
    public boolean draussen() {
        return !imTurnier && !imAbschluss;
    }

    /** Der Motor darf sofort wechseln: Einschub erbeten, kein Turnier, Fenster zu. */
    @Override
    public boolean wechselJetzt() {
        return wechselErbeten && !imTurnier && wechselBereit;
    }

    /**
     * Nach so langer Zeit in einer Runde wird auf jeden Fall angegriffen.
     *
     * Eine Runde dauert hoechstens zwei Minuten. Nach neunzig Sekunden
     * bleiben also rund dreissig -- genau der Sicherheitsabstand, den Autor
     * genannt hat.
     *
     * Diese Uhr laeuft im Bot selbst und braucht keine Texterkennung. Sie
     * ist der Ausgang, der immer da ist: vorher gab es nur zwei, und beide
     * konnten ausfallen. Neun Haekchen kommen nie zusammen, wenn ein Gegner
     * abwesend ist -- und genau das passiert laut Autor regelmaessig ("kann
     * auch einfach ein gegner sein der grade afk ist"). Blieb dann auch noch
     * die Uhr unlesbar, hat der Bot alle fuenf Runden verstreichen lassen.
     */
    private static final long RUNDE_SPAETESTENS_MS = 90_000L;

    /** Sperrzeit nach dem Klick auf "Participate", damit kein zweites Ticket draufgeht. */
    private static final long PARTICIPATE_SPERRE_MS = 8_000L;

    /** So lange sind die gelesenen Schilder samt ihrer Bildpunkte brauchbar. */
    private static final long SCHILDER_HALTBAR_MS = 5_000L;

    /**
     * Fruehestens nach dieser Zeit wird "Deploy the best" erneut gedrueckt.
     *
     * Der Knopf bleibt nach dem Klick stehen -- ohne diese Sperre druckt
     * der Bot ihn endlos.
     */
    private static final long BESTE_ERNEUT_MS = 5_000L;

    /**
     * So viel Zeit muss die Runde noch haben, damit weiter erkundet wird.
     *
     * Ein Durchgang kostet zwei Texterkennungen, also gut zwei Sekunden.
     * Wer bei zwanzig Sekunden Restzeit noch anfaengt zu blaettern, greift
     * gar nicht mehr an -- und eine verpasste Runde ist teurer als jede
     * Kenntnis ueber den Gegner.
     */
    private long erkundenBisS() {
        return reserveS() + 5;
    }

    /**
     * Autor 22.10.: Nach dem eigenen Angriff wird die Restzeit der Stage weiter zum Durchschauen genutzt -- angegriffen
     * werden muss ja nicht mehr. Frei bleibt nur so viel, dass die Fenster vor dem Stagewechsel wieder zu sind.
     */
    private static final long NACH_ANGRIFF_RESERVE_S = 8;

    /** Wie viel Restzeit frei bleiben muss: vor dem eigenen Angriff fuer den Angriff, danach nur zum Schliessen. */
    private long reserveS() {
        return rundeGespielt ? NACH_ANGRIFF_RESERVE_S : ANGRIFF_RESERVE_S;
    }

    /** Zahlen werden hoechstens so oft neu vorgelesen. */
    private static final long LESEPAUSE_MS = 3_000;

    private int turniere;
    /** Einzelne Kaempfe und ihre Turnierpunkte (Autor, 25.09.: !stats ausbauen). */
    private int kaempfeSieg, kaempfeNiederlage, punkteKaempfe;
    private long punkteSumme;
    /** Zuletzt abgelesener Ticketstand, -1 = noch nie. */
    private volatile long ticketsZuletzt = -1, ticketsZuletztUm;
    /** Ticket-Lesung, die von der letzten um mehr als 5 abweicht und auf eine Bestaetigung wartet (Pruefung 29.10.). */
    private volatile long ticketKandidat = -1;

    /**
     * Ticket-Reserve (Autor, 01.10., Idee 8): so viele Tickets bleiben liegen -- faellt der
     * Stand darauf, hoert der Bot nach dem laufenden Turnier auf. 0 = alle verbrauchen.
     * Gesetzt mit "!arenareserve 10", steht in arena-reserve.txt.
     */
    public static final java.nio.file.Path RESERVE_DATEI = java.nio.file.Path.of("arena-reserve.txt");

    public static int ticketReserve() {
        try {
            if (!java.nio.file.Files.exists(RESERVE_DATEI)) return 0;
            for (String z : java.nio.file.Files.readAllLines(RESERVE_DATEI)) {
                String t = z.strip();
                if (!t.isEmpty() && !t.startsWith("#")) return Math.max(0, Integer.parseInt(t));
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-reserve.txt nicht lesbar: " + e.getMessage());
        }
        return 0;
    }

    public static void setTicketReserve(int n) {
        try {
            java.nio.file.Files.writeString(RESERVE_DATEI, Sprache.t("# Arena: this many tickets are kept back (!arenareserve)\n",
                    "# Arena: so viele Tickets bleiben liegen (!arenareserve)\n")
                    + Math.max(0, n) + "\n");
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("arena-reserve.txt not written: ", "arena-reserve.txt nicht geschrieben: ") + e.getMessage());
        }
    }
    private final int[] platzierungen = new int[11];

    /** Was in diesem Turnier passiert ist. */
    private int rundeZuletzt;
    private int angriffeGemacht;
    private int durchgeblaettert;

    /** Merker fuer den Abschlussbildschirm -- je eine Bedeutung, nicht zwei. */
    private boolean platzEingetragen;
    private boolean belohnungGeholt;
    /** 28.10. (Berichte #YMS7/#LHD9): Claim-Klicks auf diesem Abschluss -- liegt "Victory!" darueber, wirken sie nie. */
    private int claimKlicks;
    private boolean turnierGezaehlt;
    private int endeBilder;

    /**
     * Laeuft gerade ein Turnier?
     *
     * Solange das gilt, wird nie zur Weltkarte geklickt. Ein unbekanntes
     * Bild mitten im Turnier ist ein Grund zum Warten, nicht zum Weglaufen
     * -- wer die Arena verlaesst, verliert das Ticket und die restlichen
     * Runden.
     */
    private boolean imTurnier;

    private int unbekannteBilder;
    /** Wie oft die Weltkarte auf der Suche nach der Arena-Insel schon geschoben wurde. */
    private int kartenZuege;
    /** Idee 3: seit wann ein fremdes Bild im Turnier steht, wann zuletzt eins kam, wann zuletzt das X getippt wurde. */
    private long unbekanntSeit, letztesUnbekanntUm, unbekanntXUm;
    private long letzterBericht;
    /** Eigene Uhr fuer die Wartemeldungen, damit sie sich nicht gegenseitig unterdruecken. */
    private long letzteWartemeldung;
    private String warteOrt = "";
    private long warteSeit, warteZuletzt;

    /**
     * Eigene Uhr fuer die Meldung "kein angreifbarer Gegner".
     *
     * Vorher teilte sie sich letzteWartemeldung mit warteMit(). Wer zuerst
     * schrieb, brachte den anderen zum Schweigen -- und beide sind genau
     * dafuer da, ein Haengen zu erklaeren. Im Fehlerbericht stand dann
     * wieder eine Luecke statt eines Vorlagennamens.
     */
    private long letzteSchildmeldung;
    /**
     * Wann zuletzt ein Schild angeklickt wurde. Das Vergleichsfenster braucht ein paar Bilder,
     * bis es erkannt wird -- solange klickte der Bot immer wieder auf dieselbe Stelle (01.10.,
     * 01:19: zwoelfmal in vier Sekunden), zuletzt in das schon offene Fenster hinein.
     */
    private long schildGeklicktUm;
    /** Wann zuletzt ein versehentlich geoeffnetes Gegenstandsfenster geschlossen wurde. */
    private long fehlklickZuUm;

    /** Ein Schild der Pyramide anklicken und dem Fenster Zeit zum Aufgehen lassen. */
    private void schildOeffnen(BotContext ctx, Schild s) {
        schildGeklicktUm = System.currentTimeMillis();
        ctx.input().click(s.x(), s.y() - SCHILD_HOEHE / 2);
        ctx.sleepUntilChanged(20, 1500);
    }
    private boolean ticketsAlle;

    /** Wie oft hintereinander null Tickets abgelesen wurden. */
    private int nullMessungen;

    /** Wann die Warteschlange begonnen hat, und wann zuletzt Fortschritt gemeldet wurde. */
    private long warteschlangeSeit;
    private long letzteFortschrittsmeldung;

    /** Wie viele Haekchen zuletzt zu sehen waren -- echter Fortschritt in der Runde. */
    private int zuletztHaekchen;

    /** Zwischengespeicherte Zahlen, damit die Texterkennung nicht bei jedem Bild laeuft. */
    private long rundeGelesenAt;
    private long restzeitWert = -1;
    private long restzeitGelesenAt;
    private long letzterUhrversuch;

    /** Die gelesenen Schilder, einmal je Runde -- aber nicht laenger als ein paar Sekunden. */
    private List<Schild> schilderCache = List.of();
    private int schilderCacheRunde = -1;
    private long schilderCacheAt;

    /**
     * Autor 01.11. (Pruefung 3.137): Ergebnis von schilderLesen, wenn etwas die Schilder verdeckt (Protokoll offen, Banner "Tournament phase N has
     * begun"): leer, und an DIESER Liste (Identitaet, nicht Inhalt) erkennt der Aufrufer, dass nichts gelesen wurde statt dass kein Gegner da ist.
     */
    private static final List<Schild> VERDECKT = java.util.Collections.unmodifiableList(new ArrayList<>());
    /** Wann zuletzt das Banner "Tournament phase N has begun" im Bild stand. */
    private volatile long bannerGesehenUm;
    private volatile String verdecktGemeldet = "";

    /**
     * Verdeckt gerade etwas die Schilder? "Battle-Log" (aufgeklappt, liegt ueber den linken Schildern: Xenos wurde "enos", traf das Listenbild "enes" =
     * sicher, und die Schrift "enos" wurde als neue Glyphe gelernt), "Banner" ("Tournament phase N has begun", Tabelle beim Rundenstart komplett "?") oder
     * null. Gepruefte Bild ist das, das gelesen wird (bei den Lese-Faeden das eingefrorene).
     */
    private String schilderVerdecktDurch(Mat screen, BotContext ctx) {
        boolean log = ctx.visibleIfKnown(screen, T_LOG_CLOSE);
        long jetzt = System.currentTimeMillis();
        if (ArenaVerdeckt.banner(ctx.frame())) bannerGesehenUm = jetzt;
        if (!ArenaVerdeckt.verdeckt(log, bannerGesehenUm, jetzt)) return null;
        return log ? "Battle-Log" : "Banner";
    }

    /** Wann die laufende Runde begonnen hat -- die eigene Uhr, ohne Texterkennung. */
    private long rundeSeit;

    /** Wann zuletzt "Participate" gedrueckt wurde. */
    private long participateAt;

    /** Wann zuletzt "Deploy the best" gedrueckt wurde. */
    private long besteGeklicktAt;

    /** Wann Tickets und Abzeichen zuletzt vorgelesen wurden. */
    private long zaehlerGelesenAt;

    /** Wie oft der Notausstieg schon versucht wurde. */
    private int notausstiege;

    @Override
    public String id() {
        return "arena";
    }

    @Override
    public String displayName() {
        return "Arena";
    }

    @Override
    public List<String> requiredTemplates() {
        return List.of(
                T_NODE, T_TOURNAMENT, T_PARTICIPATE, T_TICKET_ICON, T_CLOSE_X,
                T_SQUAD_TITLE, T_DEPLOY_BEST, T_CONFIRM,
                T_SEARCHING,
                T_SELECT, T_NEXT_STAGE, T_REWARD, T_ME, T_CLOCK,
                T_YOUR_SQUAD, T_QUICK, T_NEXT_ARROW,
                // Sieg UND Niederlage sind Pflicht. Beide tragen den
                // Ergebnisbildschirm; wird die Niederlage nicht erkannt,
                // erkennt step() gar nichts mehr und der Bot wuerde -- ohne
                // die Sperre imTurnier -- mitten im Turnier zur Weltkarte
                // klicken und das Ticket wegwerfen.
                T_VICTORY, T_DEFEAT, T_HOME_RESULT,
                T_ENDED, T_CLAIM);
    }

    /**
     * FREUNDE-FASSUNG (11.10.): Die Diagnose-Fotos (arena-protokoll-, arena-ich-, steine-, set-tiefer-) sammelten
     * sich beim Freund ueber Wochen an. Vor jedem neuen Foto bleiben nur die neuesten (behalten - 1) dieser Sorte.
     */
    static void fotosBegrenzen(java.nio.file.Path ordner, String vorsilbe, int behalten) {
        if (!java.nio.file.Files.isDirectory(ordner)) return;
        try (var s = java.nio.file.Files.list(ordner)) {
            var alte = s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(vorsilbe) && n.endsWith(".png");
                    })
                    .sorted(java.util.Comparator.comparingLong((java.nio.file.Path p) -> p.toFile().lastModified()).reversed())
                    .skip(Math.max(0, behalten - 1))
                    .toList();
            for (var p : alte) java.nio.file.Files.deleteIfExists(p);
        } catch (Exception e) {
            Log.debug("Arena", "Alte Fotos (" + vorsilbe + ") nicht aufgeraeumt: " + e.getMessage());
        }
    }

    /** FREUNDE-FASSUNG: das Bild des eigenen Namens -- nimmt jeder selbst auf (Capture-Images.exe, Selbstpruefung). */
    public static String bildEigenerName() {
        return T_ME;
    }

    public static List<String> optionalTemplates() {
        return List.of(
                T_PREV_SQUAD,
                T_TICKETS_OFF, T_BADGE_ICON,
                T_BAN_VICTORY, T_BAN_DEFEAT, T_NOT_IN_CLAN, T_CHECK,
                T_STAGE_LABEL, T_POWER, T_POINTS, T_TIME, T_POSITION,
                // Das Kampfprotokoll. Ohne diese Vorlagen spielt der Bot
                // weiter, nur ohne den Hinweis, wer schon an ihm
                // gescheitert ist und wen Autor selbst schon verbraucht hat.
                T_LOG_OPEN, T_LOG_CLOSE, T_LOG_DEFEAT, T_LOG_WON, T_LOG_LOST,
                T_LOG_TITLE);
    }

    @Override
    public void reset() {
        langerLaufGescannt = false;   // je Start ein voller Eigen-Scan bei langen Laeufen (Autor 03.11.)
        // Der Lese-Faden des Beobachters schreibt in dieselben Felder wie der Bot: erst fertig werden lassen (Pruefung 30.10.).
        beobPyramide = false;
        long leserBis = System.currentTimeMillis() + 15_000;
        while (beobLiest.get() && System.currentTimeMillis() < leserBis) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        beobStand = null;
        beobLetzteSchilder = null;
        de.hustlebot.schicht.ArenaStand.stageFertig(false);
        pruefer.tempoSetzen(tempo);   // Autor 24.10.: Arena-Tempo auch fuer das doppelte Gegenlesen
        burgenAusflug = false;
        burgenRueckwegSeit = 0;
        kathedraleAusflug = false;
        kathedraleLeer = false;
        kathedraleRunsGesamt = 0;
        java.util.Arrays.fill(platzierungen, 0);
        wechselErbeten = false;
        wechselBereit = false;
        nurZuEndeSpielen = false;
        nachspielHeimweg = false;
        heimwegBilder = 0;
        imAbschluss = false;
        kaempfeSieg = 0; kaempfeNiederlage = 0; punkteSumme = 0; punkteKaempfe = 0;
        turniere = 0;
        neuesTurnier();
        unbekannteBilder = 0;
        ticketsAlle = false;
        nullMessungen = 0;
        participateAt = 0;
        besteGeklicktAt = 0;
        notausstiege = 0;
        fehlstarts = 0;
        kasse.auftragBeginnt();
        Log.info("Arena", Sprache.t("Mode reset.", "Modus zurueckgesetzt."));
    }

    /**
     * Platz jedes Gegners in Stage 1 (1 = Thron, 10 = unten rechts), nach Pokalen (Autor, 01.10.):
     * "unten rechts sitzen Spieler mit absichtlich wenig Pokalen -- in Stage 1 uninteressant".
     * Stage 1 sieht sich nur die Plaetze 4-9 an (seit 22.10. mit 9) und greift einen davon an; Stage 2 die uebrigen
     * (1-3, 10), ueber den Namen, weil sich die Plaetze bis dahin verschoben haben.
     */
    private final Map<String, Integer> platzInStage1 = new java.util.HashMap<>();
    // Autor 22.10.: "Meistens ist der Schwaechste in Runde 1 auf Platz 8-9, diese soll er sich besonders gut anschauen;
    // in Runde 2 und 3 ist er meistens bei Platz 9-10. Er soll schon die Leichtesten angreifen, die Plaetze sind nur eine
    // Richtlinie." Darum gehoert Platz 9 jetzt zu Stage 1, und das Durchschauen faengt bei diesen Plaetzen an.
    private static final int STAGE1_VON = 4, STAGE1_BIS = 9;
    /** Stage 1, knapp unter 150 Punkte: Ziel-Kraft und Mindestchance (Autor 01.11.). */
    static final double STAGE1_KNAPP_ZIEL = 0.77, STAGE1_KNAPP_CHANCE = 0.85;

    /** Gegner, gegen den der Kampf voraussichtlich gut 10 Sekunden dauert und trotzdem sicher ist: Kraft 0,7-0,9 der eigenen. */
    static boolean stage1Knapp(double kraftVerhaeltnis) {
        return kraftVerhaeltnis >= 0.70 && kraftVerhaeltnis < 0.90;
    }

    /** Stage 2 und 3: bisher nur Siege mit vollen 150 Punkten, also noch keiner darunter? */
    static boolean nochNichtUnter150(int runde, long punkteBisher, boolean punkteFehlen) {
        return runde >= 2 && runde <= 3 && !punkteFehlen && punkteBisher == 150L * (runde - 1);
    }

    /** Steht er auf Autors Listen, kann er spaeter 225 Punkte bringen. */
    static boolean fuerSpaeterAufheben(boolean sicher, boolean fastSicher, boolean machbar) {
        return sicher || fastSicher || machbar;
    }

    /** Der sichere Gegner, gegen den der Kampf am ehesten knapp ueber 10 Sekunden dauert (145-149 Punkte). */
    private Optional<Schild> knappZiel(List<Schild> kandidaten) {
        // Autor 01.11. (Arena 17:18, 817 Punkte, Platz 4): "wieso hat er diesen Enigma-Typen angegriffen?" Spieler auf seinen Listen
        // (sicher, fast sicher, machbar) sind die spaeteren 225er -- nicht in Stage 1-3 fuer einen 150er verbrauchen.
        List<Schild> sicher = kandidaten.stream()
                .filter(s -> !fuerSpaeterAufheben(s.sicher(), s.fastSicher(), s.machbar()))
                .filter(s -> bekannteStaerke(s) > 0 && bekannteStaerke(s) < meineStaerke * 0.9)
                .filter(s -> siegchance(s) >= STAGE1_KNAPP_CHANCE && !angstgegner(s))
                .sorted(java.util.Comparator.comparingLong(this::bekannteStaerke))
                .toList();
        Optional<Schild> imBereich = sicher.stream()
                .filter(s -> stage1Knapp(bekannteStaerke(s) / (double) meineStaerke))
                .min(java.util.Comparator.comparingDouble(s -> Math.abs(bekannteStaerke(s) / (double) meineStaerke - STAGE1_KNAPP_ZIEL)));
        if (imBereich.isPresent()) return imBereich;
        // Autor 01.11.: "wenn er eigentlich den schwaechsten Typen angreifen will, soll er den zweitschlechtesten angreifen."
        return sicher.size() >= 2 ? Optional.of(sicher.get(1)) : Optional.empty();
    }
    /** Wo das Durchschauen anfaengt (Autor 22.10.): Runde 1 bei Platz 8, Runde 2-3 bei Platz 9. */
    private static final int ZUERST_RUNDE1 = 8, ZUERST_RUNDE23 = 9;
    /** Aufgehobene Gegner ab Stage 4: mindestens diese Siegchance (Autor, 11.10.). */
    private static final double LETZTER_PLATZ_CHANCE = 0.92;
    /**
     * Hoechstens so viel Siegchance gegen einen Angstgegner (Idee A01, 22.10.): hat der Bot gegen ihn schon verloren und
     * nicht oefter gewonnen als verloren, gewann er danach nur 4 von 15 Kaempfen (27 %), 12 davon in Runde 4/5.
     * Autor 29.10. (Arena 15:57, Myth Gods nach EINER Niederlage von 82 auf 40 % gedeckelt, stattdessen den staerkeren
     * Твой Успех angegriffen und verloren): "nur wenn man zweimal gegen einen Spieler verloren hat, und dann auf
     * maximal 60 %".
     */
    static final double ANGSTGEGNER_DECKEL = 0.60;
    /**
     * Boden fuer "hat dich diese Runde angegriffen und verloren" (Autor 08.10.: 90 %). Idee 14 (22.10., Autor: "alle 20
     * Ideen rein"): gegen solche Spieler gewann der Bot nur 15 von 19 (79 %), in Runde 4/5 nur 4 von 8 -- jetzt 80 %.
     */
    static final double VERLOREN_GEGEN_MICH_BODEN = 0.80;
    /**
     * Platz des Gegners, der gerade im Vergleichsfenster steht -- aus der Blaetterfolge: Der Pfeil
     * geht die Plaetze der Reihe nach durch (Run 1 am 01.10.: 8, 9, 10, 1, 2, 3 ...), das eigene
     * Schild wird uebersprungen. So kennt der Bot den Platz auch dann, wenn der Name auf dem
     * kleinen Schild der Pyramide nicht lesbar war (4 von 9 im ersten Run). -1 = unbekannt.
     */
    private int blaetterPlatz = -1;
    /** Bildgroesse beim letzten Lesen der Pyramide -- fuer die Lage der Schilder ohne ctx. */
    private int breiteZuletzt = 1159, hoeheZuletzt = 770;
    /** Der eigene Platz in der Pyramide (Stage 1), 0 = nicht erkannt. */
    private int meinPlatz = 0;
    /** Der eigene Platz in der Pyramide, in jeder Runde neu (16.10., fuer die Endrunde); 0 = unbekannt. */
    private int aktuellerPlatz = 0;
    /** In welcher Runde der Platz schon ins Protokoll kam (einmal je Runde, nach dem Banner). */
    private int platzGemeldetRunde = -1;
    /** Die letzte Runde eines Turniers. */
    private static final int LETZTE_RUNDE = 5;
    /** Bis zu diesem Platz lohnt das 225er-Wagnis in der letzten Runde (Lauf 5, 16.10.). */
    private static final int WAGNIS_BIS_PLATZ = 5;
    /** Platz und 225er-Auswahl der letzten Runde schon ins Protokoll geschrieben? (je Runde neu) */
    private boolean endrundeGemeldet;

    private int naechsterPlatz(int platz) {
        int p = platz % 10 + 1;
        if (p == meinPlatz) p = p % 10 + 1;
        return p;
    }

    /** Der Schluessel eines Fingerabdruck-Spielers ("bild..."), der in Stage 1 an diesem Platz stand. */
    private String fingerabdruckAmPlatz(int platz) {
        for (var e : platzInStage1.entrySet()) {
            if (e.getValue() == platz && e.getKey().startsWith("bild")) return e.getKey();
        }
        return null;
    }

    private boolean gruenInStage1(String schluessel) {
        Integer platz = platzInStage1.get(schluessel);
        return platz != null && platz >= STAGE1_VON && platz <= STAGE1_BIS;
    }

    /**
     * Platz aus der Lage des Schilds: vier Reihen (Thron; 2-3; 4-6; 7-10), innerhalb der Reihe
     * von links nach rechts. Gemessen an Autors Bild vom 01.10. ("Reward:"-Zeile: Reihen bei
     * 33/51/68/85 % der Hoehe, Spalten bei 20/40/60/80 % der Breite).
     */
    static int platzAusLage(int x, int y, int breite, int hoehe) {
        double fy = y / (double) hoehe, fx = x / (double) breite;
        if (fy < 0.43) return 1;
        if (fy < 0.60) return fx < 0.50 ? 2 : 3;
        if (fy < 0.77) return fx < 0.40 ? 4 : fx < 0.60 ? 5 : 6;
        return fx < 0.30 ? 7 : fx < 0.50 ? 8 : fx < 0.70 ? 9 : 10;
    }

    /**
     * Namen, die in der Pyramide "Not in clan" hatten (02.10.). Im Vergleichsfenster steht bei
     * ihnen oft gar kein Clan -- dann landeten sie mit "Clan: ?" im Notizblock (20 von 83 Eintraegen).
     */
    private final java.util.Set<String> botsInPyramide = java.util.concurrent.ConcurrentHashMap.newKeySet();   // 30.10.: auch der Lese-Faden des Beobachters

    private boolean botLautPyramide(String name) {
        if (name == null || name.isBlank()) return false;
        for (String b : botsInPyramide) {
            if (de.hustlebot.core.NamensVergleich.gleicherName(b, name)) return true;
        }
        return false;
    }

    // --- Pal-Rundgang (Autor, 02.10.) ---------------------------------------------------
    /** Wessen Pals gerade angesehen werden (aus der Pyramide einzeln angeklickt). */
    private String palBesuchFuer;
    private long palBesuchSeit;
    /** In diesem Turnier schon fuer die Pals angeklickt (oder dabei angesehen). */
    private final java.util.Set<String> palVersucht = new java.util.HashSet<>();

    /** Den naechsten Gegner, dessen Pals fehlen, einzeln anklicken -- beim Blaettern fehlt die Pfote. */
    private Outcome palBesuchStarten(Mat screen, BotContext ctx) {
        if (ArenaEinfach.aktiv()) return null;   // TS8: keine Pals
        if (!ArenaExtras.pals() || !ctx.matcher().has("ar_pal_knopf")) return null;
        for (Schild s : schilderLesen(screen, ctx)) {
            if (s.name().isBlank() || s.botClan() || s.gesperrt()) continue;
            String k = namensSchluessel(s.name());
            if (k.isEmpty() || !palVersucht.add(k)) continue;
            var n = buch.nachschlagen(s.name(), s.clan());
            if (n.isEmpty() || n.get().palsBekannt()) continue;
            palBesuchFuer = k;
            palBesuchSeit = System.currentTimeMillis();
            imKampfGegen = null;   // Review 02.10.: sonst gilt das Fenster als Angriffsfenster
            Log.info("Arena", Sprache.t("Looking at the pals of " + s.wer() + " -- opened one by one (the paw is missing when paging).",
                    "Pals von " + s.wer() + " ansehen -- einzeln angeklickt (beim Blaettern fehlt die Pfote)."));
            schildOeffnen(ctx, s);
            return Outcome.ACTED;
        }
        return null;
    }

    /** Die Turnierpunkte dieses Turniers (Autor, 02.10.: "meine Endpunkte in der Nachricht"). */
    private long turnierEndpunkte;
    /** Ein Sieg ohne lesbare Punktzahl -- die Summe ist dann mindestens so viel. */
    private boolean turnierPunkteFehlen;

    private void neuesTurnier() {
        if (de.hustlebot.core.ArenaSchalter.an()) de.hustlebot.core.KommentarPakete.arenaZuEnde();   // Autor 30.10.: je Arena ein Paket
        leiterTest = true;   // Autor 22.10. (spaeter am Abend): immer, nicht nur jedes zweite Turnier
        leiterAngewendet = false;
        turnierEndpunkte = 0;
        turnierPunkteFehlen = false;
        protokollFotos = 0;
        staerkerAlsIch = 0;
        wenigSchwache = false;
        top3Gemeldet = false;
        botsInPyramide.clear();
        palVersucht.clear();
        palBesuchFuer = null;
        kastenCache.clear();
        turnierPyramideUm = 0;
        turnierWartezeitMs = 0;
        warteschlangeLetztesBildUm = 0;
        allesErkundetRunde = 0;
        platzInStage1.clear();
        schluesselNachStaerke.clear();
        gesperrtZurueck.clear();
        imFensterGesperrt.clear();
        netzGemeldet.clear();   // Listen-Netz (Autor 28.10.): je Turnier neu melden
        zuStarkZurueck.clear();   // Code-Pruefung 22.10.: "Runde:Gegner" gilt je Turnier -- sonst griff die Umkehr im naechsten nie
        vollProtokollRunde = -1;
        vollProtokollWische = 0;
        protokollStufeRunde = -1;
        protokollHochRunde = -1;
        rundeZuletzt = 0;
        angriffeGemacht = 0;
        durchgeblaettert = 0;
        platzEingetragen = false;
        belohnungGeholt = false;
        claimKlicks = 0;
        turnierGezaehlt = false;
        endeBilder = 0;
        zuletztHaekchen = -1;
        warteschlangeSeit = 0;
        gefundenZuletzt = -1;
        restzeitWert = -1;
        restzeitGelesenAt = 0;
        rundeGelesenAt = 0;
        zaehlerGelesenAt = 0;
        schilderCache = List.of();
        schilderCacheRunde = -1;
        schilderCacheAt = 0;
        // Das Truppenfenster kommt je Turnier genau einmal -- die Sperre
        // fuer "Deploy the best" faengt also mit jedem Turnier neu an.
        besteGeklicktAt = 0;
        offenesErgebnisEintragen();   // nichts aus dem alten Turnier verlieren
        imKampfGegen = null;
        letzteGegnerstaerke = -1;
        zwischenDenRunden = false;
        erkundet.clear();
        namensKarte.clear();
        staerken.clear();
        herumRunde = -1;          // "schwaechste zuerst" (03.11.): im neuen Turnier erst wieder alle Kraefte lesen
        reihenfolgeRunde = -1;
        kraftKandidaten.clear();
        schildAlias.clear();
        namensbildVon.clear();
        pruefungen.clear();
        pruefVersucht.clear();
        geprueftInRunde.clear();
        aussehenInRunde.clear();
        unvollstaendigGeprueft.clear();
        neuAnsehen.clear();
        sicherNamen.clear();
        niemalsGelesen.clear();
        nachgeprueft.clear();
        aktuellerPlatz = 0;
        platzGemeldetRunde = -1;
        eigenesTeamGeprueft = false;
        fremdeKaempfe.clear();
        verlorenGegen.clear();
        bonusGemeldet.clear();
        deckelGemeldet.clear();
        muehsamGegen.clear();
        letzterGesehen = "";
        pruefer.abbrechen(Sprache.t("new tournament", "neues Turnier"));
        schnellstartGemacht = false;
        anzeigeNamen.clear();
        zuletztGewaehlt = "";
        // Verbraucht ist verbraucht -- aber nur fuer DIESES Turnier. Im
        // naechsten darf jeder wieder angegriffen werden, deshalb steht das
        // Leeren hier und nicht in neueRunde().
        schonAngegriffen.clear();
        angriffOhneErgebnis = null;
        verliererVermerkt.clear();
        autoGemeldet.clear();
        tabelle = null;
        tabelleRunde = -1;

        // DIE TURNIERSPERRE MUSS HIER AUCH FALLEN.
        //
        // imTurnier verhindert, dass der Bot mitten in einem Turnier zur
        // Weltkarte klickt und das Ticket wegwirft. Zurueckgesetzt wurde die
        // Sperre bisher nur beim ordentlichen Turnierende und beim
        // Notausstieg -- nicht aber, wenn Autor !stop drueckt und danach
        // !start arena.
        //
        // Dann blieb sie auf "ja, im Turnier" stehen, obwohl das Spiel
        // laengst wieder in der Burg war. Der Bot lief in hinweg() jedes Mal
        // in den Wartezweig, klickte NIE auf die Weltkarte und stand eine
        // Viertelstunde, bis der Totmann-Schalter ihn abschaltete.
        //
        // neuesTurnier() laeuft beim Klick auf "Participate" VOR dem Setzen
        // der Sperre -- hier zu loeschen kostet also nichts.
        imTurnier = false;
        imAbschluss = false;   // Pruefung 11.10.: ein neues Turnier faengt nie auf dem Abschlussbildschirm an
        nurErkunden = false;
        allesErkundet = false;
        rundgang.clear();
        rundgangBlaetter = 0;
        ohneClanGesehen.clear();
        neueRunde();
    }

    /** Setzt alles zurueck, was nur fuer eine einzelne Runde gilt. */
    /** Letzter Kontext aus step() -- fuer die Fotoserie an Stellen ohne eigenen Kontext. */
    private BotContext fotoKontext;
    private int fotosDiesesLaufs;

    /**
     * Fotoserie (Autor, 11.10.: "ueberwach jede Runde mit einer Fotoserie und werte sie am Ende aus"). Nur, wenn im
     * Bot-Ordner die Datei arena-fotos.txt liegt: ein Bild bei neuer Runde, jeder neuen Wahl, vor dem Angriff, beim
     * Ergebnis und am Turnierende, nach logs/arena-fotos/<Datum>/. Hoechstens 400 je Programmlauf.
     */
    private void fotoSerie(String anlass) {
        if (BEOB_LESER.equals(Thread.currentThread().getName())) return;   // Pruefung 30.10.: der Beobachter rechnet nur fuer die Anzeige, kein Lern-Foto
        BotContext ctx = fotoKontext;
        if (ctx == null || fotosDiesesLaufs >= 400 || !java.nio.file.Files.exists(java.nio.file.Path.of("arena-fotos.txt"))) return;
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return;
        fotosDiesesLaufs++;
        try {
            java.time.LocalDateTime jetzt = java.time.LocalDateTime.now();
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "arena-fotos", jetzt.toLocalDate().toString());
            java.nio.file.Files.createDirectories(ordner);
            String datei = jetzt.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss-SSS"))
                    + "-t" + (anlass.startsWith("ende") ? turniere : turniere + 1) + "-r" + rundeZuletzt + "-" + anlass + ".jpg";
            halbAlsJpeg(bild, ordner.resolve(datei));   // 01.11.: nie mehr das ganze Bild als PNG
        } catch (Exception e) {
            Log.debug("Arena", "Fotoserie: Bild nicht gespeichert: " + e.getMessage());
        }
    }

    private void neueRunde() {
        eigenerAngriffUm = 0;
        durchgeblaettert = 0;
        geprueftInRunde.clear();
        aussehenInRunde.clear();   // Schnell-Blick je Stage (Autor 22.10.)
        neuAnsehen.clear();   // je Stage wieder mindestens fuenf (11.10.)
        // Stage 2 sieht sich die uebrigen an (01.10.). Seit 08.10. (Autor: "in Stage 3 bis 5 kann er
        // sich die Gegner auch nochmal genauer durchschauen, einfach die Zeit nutzen") wird in jeder
        // Runde geblaettert -- wer noch fehlt oder Luecken hatte, wird dabei (erneut) angesehen.
        allesErkundet = false;
        rundeGespielt = false;
        long endeVorher = rundenendeErwartet;
        rundenendeErwartet = 0;
        angriffsphaseSeit = 0;
        quickFehltSeit = 0;
        grauInRunde = 0;
        graueSchilder.clear();
        fingerabdruckAbgelehnt.clear();
        fensterPlatzCache.clear();   // die Plaetze aendern sich von Runde zu Runde
        fensterPlatzFehl.clear();
        // Das Kampfprotokoll gilt immer nur fuer die laufende Runde.
        gegenMichVerloren.clear();
        gegenMichGewonnen.clear();
        protokollFuerRunde = -1;
        offenesErgebnisEintragen();   // nichts aus der alten Runde verlieren
        ergebnisGemeldet = false;
        zuletztHaekchen = -1;
        rundeSeit = rundeBegannUm(endeVorher, System.currentTimeMillis());
        uhrVergessen();
    }

    /**
     * Wann die neue Runde wirklich begann. Kommt der Bot erst spaet von einem Ausflug zurueck (Burgen, Kathedrale), merkt er den
     * Rundenwechsel verspaetet -- die eigene Rundenuhr (RUNDE_SPAETESTENS_MS) liefe dann zu spaet. War das Ende der alten Runde
     * bekannt und liegt es hoechstens zwei Minuten zurueck, gilt es als Beginn der neuen (Nutzer 25.11., Kathedralen-Ausflug).
     */
    static long rundeBegannUm(long endeVorher, long jetzt) {
        return endeVorher > 0 && endeVorher < jetzt && jetzt - endeVorher <= 120_000 ? endeVorher : jetzt;
    }

    // ==================================================================
    //  Der Hauptschritt
    // ==================================================================

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        fotoKontext = ctx;
        schichtNurBeiPyramide(screen, ctx);

        // Das Kauf-Fenster kommt direkt nach Participate -- da ist imTurnier
        // schon gesetzt. Deshalb IMMER nachsehen (nur kurz nach Participate).
        Outcome kauf = kasse.kaufFensterAbwehren(screen, ctx);
        if (kauf != null) return kauf;

        // Bezahlen (Teil A): laeuft ein Gang zur Burg oder durch die Tasche,
        // gehoert ihm der Schritt. Nie waehrend eines Turniers.
        if (!imTurnier) {
            Outcome kassenGang = kasse.schritt(screen, ctx);
            if (kassenGang != null) return kassenGang;
        }

        // TEMPO (Lauf 11, 02.10.): Die seltenen Stoerfenster unten kosteten je ~35 ms im ganzen Bild --
        // bei jedem Foto, zusammen rund 270 s von 438 s Turnier (Lauf 1). Jetzt hoechstens alle
        // STOER_BLICK_MS, und sofort, sobald ein Bild nicht erkannt wurde.
        long jetztStoer = System.currentTimeMillis();
        boolean stoerBlick = unbekannteBilder > 0 || jetztStoer - stoerBlickUm >= STOER_BLICK_MS;
        if (stoerBlick) stoerBlickUm = jetztStoer;

        // AFK-Rauswurf ("are you still here?"): der gruene Update-Knopf laedt
        // das Spiel neu und bringt zurueck zur Burg. Hat Vorrang.
        if (stoerBlick && Stoerfenster.updateDruecken(screen, ctx, "Arena")) {
            // Danach steht das Spiel in der Burg, ein Turnier ist weg (30.09.).
            imTurnier = false;
            imAbschluss = false;
            ctx.sleep(20000);
            return Outcome.ACTED;
        }

        // "Did you fall asleep, my lord? You missed the previous turn" (Autor, 30.09.):
        // gruener "Return" -- danach geht es in der Pyramide normal weiter.
        if (stoerBlick && ctx.clickIfVisibleIfKnown(screen, "ar_return", Sprache.t("Round missed -- Return", "Runde verpasst -- Return"))) {
            verpassterAngriffZurueck();
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        if (stoerBlick) {
            switch (Stoerfenster.anderesGeraet(screen, ctx, "Arena")) {
                case ANHALTEN -> { return Outcome.PAUSE; }
                case GEKLICKT -> { return Outcome.ACTED; }
                case NICHTS   -> { }
            }
            if (Stoerfenster.belohnungWegklicken(screen, ctx, "Arena")) {
                return Outcome.ACTED;
            }
        }

        // Burgen in der Wartezeit (Autor 25.10.): waehrend des Ausflugs gehoert jeder Schritt dem Burgen-Modus.
        if (burgenAusflug) {
            Outcome o = burgenSchritt(screen, ctx, runs);
            if (o != null) return o;
        }
        if (kathedraleAusflug) {
            Outcome o = kathedraleSchritt(screen, ctx, runs);
            if (o != null) return o;
        }

        // Eigener Wachhund.
        //
        // Hier stand vorher gar keiner. In den Kommentaren war die Rede
        // davon, "die Leerlaufuhr" wuerde nach fuenf Minuten anschlagen --
        // das stimmt aber nur fuer das Verlies. Nur DungeonMode fragt
        // runs.isIdleTooLong() ab; Arena und Clan-Krieg taten es nie. Der
        // einzige Rueckhalt war der Totmann-Schalter im Motor nach fuenfzehn
        // Minuten, und der haelt den GANZEN Bot mit einem Fehler an. Bei
        // "!start arena 20" waeren damit auch die neunzehn folgenden
        // Turniere weg gewesen.
        //
        // Jetzt steigt der Modus selbst aus dem haengenden Turnier aus und
        // faengt das naechste an. Ein verlorenes Ticket ist aergerlich, eine
        // verlorene Nacht ist schlimmer.
        if (runs.isIdleTooLong()) {
            return notausstieg(screen, ctx, runs);
        }

        // Von hinten nach vorn geprueft: der spaeteste Bildschirm zuerst.
        // Das Siegesband liegt ueber der Pyramide, das Turnierende ueber
        // allem. Wer von vorn prueft, klickt in die verdeckte Pyramide.
        // Der Rundenwechsel wird bei jedem Bild gemerkt, nicht nur in der
        // Pyramide -- sonst geht er verloren, waehrend der Bot im
        // Vergleichsfenster steht.
        rundenwechselMerken(screen, ctx);

        // Laeuft gerade eine Einheiten-Pruefung (Sets/Artefakte), hat sie
        // Vorrang -- ihre Fenster liegen ueber dem Vergleichsfenster. Endet
        // das Turnier oder die Zeit wird knapp, wird sie abgebrochen.
        if (pruefer.laeuft()) {
            // Waehrend der Pruefung (12-16 s) sieht vergleich() das Fenster nicht -- ohne
            // diese Zeile hielt die Pyramide das Durchschauen danach fuer beendet und der
            // Bot griff den naechstbesten an (Autor, 01.10.: "nicht schon beim zweiten
            // Spieler angreifen").
            if (nurErkunden) letzterErkundungsBlick = System.currentTimeMillis();
            long rest = restSekunden();
            String abgebrochen = null;
            if (ctx.visible(screen, T_ENDED) || (!rundeGespielt && ctx.visible(screen, T_NEXT_STAGE))) {
                abgebrochen = pruefer.abbrechen(Sprache.t("the round is over", "die Runde ist vorbei"));
            } else if (rest >= 0 && rest < (vorAngriffBlick ? VOR_ANGRIFF_BIS_S : reserveS())) {
                abgebrochen = pruefer.abbrechen(rundeGespielt
                        ? Sprache.t("only " + rest + " s left -- the stage is ending", "nur noch " + rest + " s -- die Stage endet gleich")
                        : Sprache.t("only " + rest + " s left -- attacking now", "nur noch " + rest + " s -- jetzt wird angegriffen"));
            } else if (rest < 0 && System.currentTimeMillis() - rundeSeit > RUNDE_SPAETESTENS_MS - 5_000) {
                abgebrochen = pruefer.abbrechen(Sprache.t("the round is running out -- attacking now", "die Runde laeuft ab -- jetzt wird angegriffen"));
            } else if (pruefer.fristUm()) {
                // Autor, 15./16.10.: hoechstens 25 s je Gegner. Alle Einheiten gelesen -> Ergebnis behalten, sonst
                // abbrechen. Nicht in pruefVersucht freigeben: wer 25 s nicht schafft, schafft sie in der naechsten
                // Stage auch nicht -- er kommt erst in Stage 4/5 als Luecke noch einmal dran.
                if (pruefer.einheitenFertig()) pruefer.fristFertig();
                else pruefer.abbrechen(Sprache.t("25 s up -- next opponent", "25 s um -- naechster Gegner"));
            }
            // In der naechsten Stage noch einmal versuchen (30.09.).
            if (abgebrochen != null) pruefVersucht.remove(namensSchluessel(abgebrochen));
            if (pruefer.laeuft()) {
                Outcome o = pruefer.schritt(screen, ctx, ctx.find(screen, T_POWER).orElse(null));
                // Berichte #SMLG/#WU5B/#HY54/#HA7U/#AU6C/#HSHF (24.10.): die Pruefung eines Gegners
                // dauert bis 25 s (Waffe ansehen, naechste Einheit ...) ohne Meldung -- "No progress"
                // nach 20 s, obwohl der Bot klickte. Jeder Klick der Pruefung zaehlt als Fortschritt.
                if (o == Outcome.ACTED) runs.progress(Sprache.t("Scouting units", "Einheiten ansehen"));
                if (o != null) return o;
            }
            pruefungVerbuchen();
            pruefungFertigUm = System.currentTimeMillis();
            if (nurErkunden) letzterErkundungsBlick = pruefungFertigUm;
            // Dieses Bild stammt noch aus der Pruefung -- nichts weiter damit anfangen.
            return Outcome.WAITING;
        }
        // Ein Einheitenfenster offen, aber keine Pruefung (abgebrochen)? Zu. Nicht direkt
        // nach einer Pruefung: da geht das Fenster gerade von selbst zu, und das X sitzt
        // an derselben Stelle wie das des Vergleichsfensters.
        if (stoerBlick && System.currentTimeMillis() - pruefungFertigUm > 1500
                && ctx.matcher().has("ar_dwellers") && ctx.visible(screen, "ar_dwellers")
                && ctx.clickIfVisibleIfKnown(screen, "ar_dweller_close", Sprache.t("Close the unit window", "Einheitenfenster schliessen"))) {
            return Outcome.ACTED;
        }
        // Ein Gegenstandsfenster offen, aber keine Pruefung? Ein Fehlklick (01.10., 01:19: der
        // Bot klickte ins schon offene Vergleichsfenster und oeffnete eine Ruestung). Zu.
        // NUR wenn auch der Fensterrahmen (ar_item_fenster) da ist: das X allein passte in der
        // Pyramide zu 0,99 auf ein Turmdach, und der Bot klickte 250-mal dorthin (01:24).
        // Und hoechstens alle 3 s, damit so etwas nie wieder alles andere blockiert.
        if (stoerBlick && System.currentTimeMillis() - fehlklickZuUm > 3000
                && ctx.matcher().has("ar_item_fenster") && kastenSichtbar(screen, ctx, "ar_item_fenster")
                && ctx.clickIfVisibleIfKnown(screen, "ar_item_zu", Sprache.t("Close the item window (misclick)", "Gegenstandsfenster schliessen (Fehlklick)"))) {
            fehlklickZuUm = System.currentTimeMillis();
            return Outcome.ACTED;
        }

        // Ein Pal- oder Schirmherrschaften-Fenster ohne laufende Pruefung (Review 02.10.): zu.
        if (stoerBlick && System.currentTimeMillis() - pruefungFertigUm > 1000 && System.currentTimeMillis() - kleinesZuUm > 1500
                && ArenaPruefer.kleinesFensterZu(screen, ctx)) {
            kleinesZuUm = System.currentTimeMillis();
            return Outcome.ACTED;
        }

        if (ctx.visible(screen, T_ENDED))       return turnierEnde(screen, ctx, runs);
        if (ctx.visible(screen, T_VICTORY)
         || kastenSichtbar(screen, ctx, T_DEFEAT)) return ergebnis(screen, ctx, runs);
        // Vergleichsfenster: "Your squad:" ALLEIN reicht nicht.
        //
        // Damit hat sich der Bot in Runde 4 aufgehaengt. Die Zeile war zu
        // sehen, der Angriffsknopf aber nicht -- also lief vergleich()
        // immer wieder durch, schrieb "Angriff: nach 9 Blicken" ins
        // Protokoll (119 Mal) und kam nie zum Klicken. Die Runde verstrich
        // ungenutzt.
        //
        // Ein Fenster erkennt man an dem, was man darin BRAUCHT. Ohne
        // Angriffsknopf und ohne Staerkezeile ist es nicht das
        // Vergleichsfenster, was auch immer sonst zu sehen ist.
        if (ctx.visible(screen, T_YOUR_SQUAD)
                && (ctx.visible(screen, T_QUICK) || ctx.visibleIfKnown(screen, T_POWER))) {
            return vergleich(screen, ctx, runs);
        }
        if (ctx.visible(screen, T_SEARCHING))   return warteschlange(screen, ctx, runs);
        if (ctx.visible(screen, T_SQUAD_TITLE)) return truppe(screen, ctx);
        if (ctx.visible(screen, T_TOURNAMENT))  return anmelden(screen, ctx, runs);
        if (ctx.visible(screen, T_SELECT)
         || ctx.visible(screen, T_NEXT_STAGE))  return pyramide(screen, ctx, runs);

        return hinweg(screen, ctx);
    }

    // ------------------------------------------------------------------
    //  Einheiten-Pruefung: wann, und was damit passiert
    // ------------------------------------------------------------------

    /** Restzeit der Runde in Sekunden, -1 wenn unbekannt. */
    private long restSekunden() {
        if (restzeitWert < 0) return -1;
        return Math.max(0, restzeitWert - (System.currentTimeMillis() - restzeitGelesenAt) / 1000);
    }

    /**
     * Soll dieser Gegner jetzt geprueft werden? Nur wenn: in diesem Turnier
     * noch nicht versucht, keine frische Pruefung im Notizblock, kein Bot ohne
     * Clan (die sind ohnehin schwach und kommen nie wieder), die Vorlagen und
     * Stellen dafuer sind da -- und die Zeit reicht mit 15 s Reserve.
     */
    /**
     * Autor 22.10. abends: "Myth Gods und JINXED MURENA mindestens viermal angeschaut, obwohl sie auf der Blockieren-Liste
     * sind -- der Bot greift sie sowieso nicht an." Gesperrte (Sperrliste nach Kraft/Name/Clan/Sets/Einheiten, oder 3
     * Niederlagen ohne Sieg im Notizblock) werden weder geprueft noch per Schnell-Blick angesehen.
     */
    /**
     * Autor 30.10.: "Ein Spieler auf der Liste, von mir selber eingespeichert, aber der Bot kennt das Team noch nicht. In Stage 3 bis 5 hat er die ganze
     * Zeit der Welt: dann soll er diese Spieler selber aufnehmen." Das Ansehen ist nur ein Blick, angegriffen wird er dadurch nicht: die Liste gilt weiter.
     */
    static boolean listeAufnehmen(boolean regelnNeu, int runde, boolean teamBekannt) {
        return regelnNeu && runde >= 3 && !teamBekannt;
    }

    private boolean listeAufnehmen(String name, String clan) {
        return listeAufnehmen(de.hustlebot.core.ArenaRegelnNeu.an(), rundeZuletzt,
                buch.nachschlagen(name, clan).filter(n -> n.setPunkte() >= 0).isPresent());
    }

    private boolean gesperrtNichtAnsehen(String name, String clan, String schluessel) {
        if (listeAufnehmen(name, clan)) return false;
        Long kraftHier = staerken.get(schluessel);
        long kraft = kraftHier != null ? kraftHier : 0;
        // Autor 24.10.: dazu der Fingerabdruck des Namensbildes (Glyphenschrift) -- erkennt ihn auch unter anderem Namen.
        String fp = fingerabdruckZu(name);
        String sperre = de.hustlebot.core.ArenaSperre.sperrgrund(name, clan, kraft,
                buch.nachschlagen(name, clan).map(Gegnerbuch.Notiz::sets).orElse(""), einheitenVon.getOrDefault(schluessel, ""),
                null, fp);
        int[] bilanz = buch.bilanzNachName(name, kraft, fp);
        if (sperre == null && !(bilanz[0] == 0 && bilanz[1] >= 3)) return false;
        if (dreimalGemeldet.add(schluessel)) {
            Log.info("Arena", Sprache.t(name + ": blocked -- not looked at (" + (sperre != null ? sperre : bilanz[1] + " losses, no win") + ").",
                    name + ": gesperrt -- wird nicht angesehen (" + (sperre != null ? sperre : bilanz[1] + " Niederlagen, kein Sieg") + ")."));
        }
        return true;
    }

    /** Gilt der Schluessel oder sein Gegenstueck (Alias Schild <-> Fenster, in beiden Richtungen) als gesehen? */
    private boolean dieselbeLesart(java.util.function.Predicate<String> gesehen, String schluessel) {
        return dieselbeLesart(gesehen, schildAlias, schluessel);
    }

    static boolean dieselbeLesart(java.util.function.Predicate<String> gesehen, java.util.Map<String, String> alias, String schluessel) {
        if (gesehen.test(schluessel)) return true;
        String a = alias.get(schluessel);
        if (a != null && gesehen.test(a)) return true;
        for (var e : alias.entrySet()) if (e.getValue().equals(schluessel) && gesehen.test(e.getKey())) return true;
        return false;
    }

    private boolean brauchtPruefung(BotContext ctx, String name, String clan, String schluessel,
                                    boolean ohneClan) {
        if (schluessel.isEmpty() || ohneClan) return false;
        if (ArenaEinfach.aktiv()) return false;   // TS8 (Nutzer 25.11.): keine Einheiten-Pruefung, nur die Bilanz zaehlt
        // Hauptbot 11.10. (Stage 5): Spieler, die schon angegriffen wurden, muss der Bot nicht mehr
        // anschauen -- sie sind in diesem Turnier verbraucht, die Pruefzeit gehoert den anderen.
        if (schonAngegriffen.contains(schluessel) || schonAngegriffen.contains(namensSchluessel(name))) return false;
        if (gesperrtNichtAnsehen(name, clan, schluessel)) return false;
        // Autor, 08.10. (Lauf 2: eine Minute uebrig, fuenf Gegner nicht angesehen): "Wenn noch Zeit
        // uebrig ist, wieso nicht?" Darum:
        //  - Stage 1 hoert nicht mehr nach den Plaetzen 4-8 auf -- die Blaetterfolge beginnt ohnehin
        //    bei Platz 4, danach kommen 9, 10, 1, 2, 3 dran, solange die Zeit reicht;
        //  - wer im Notizblock schon steht, wird trotzdem frisch angesehen (Teams aendern sich);
        //  - ab Stage 3 bekommt ein Gegner, dessen erste Pruefung Luecken hatte, einen zweiten Blick.
        //  - Autor, 11.10.: "in jeder Runde, egal ob die Gegner schon erkannt sind oder nicht, mindestens fuenf
        //    Gegner einmal komplett neu anschauen" -- je Stage wird jeder hoechstens einmal angesehen, Bekannte
        //    noch einmal, bis fuenf in dieser Stage angesehen sind (die Werte werden dabei nachgeprueft).
        //  - Autor, 16.10. (ersetzt die "fuenf je Stage"): "In der zweiten Runde soll er sich ausschliesslich Gegner
        //    anschauen, die noch nicht angeschaut wurden. Genau das gleiche in der dritten Runde." Wie im Hauptbot:
        //    wer in diesem Turnier schon angesehen wurde, kommt nur in Stage 4/5 noch einmal dran, und nur, wenn
        //    die erste Pruefung Luecken hatte. (Probelauf 16.10.: Copper und MastëŕSyncro je dreimal angesehen.)
        // Autor 01.11.: derselbe Spieler unter zwei Lesarten (Schild "屈機黑雞", Fenster "Cthulhu's Re") gilt als ein Eintrag: der Pruefschluessel
        // folgt dem Alias Schild <-> Fenster (schildAlias), sonst wurde er in jeder Stage noch einmal angesehen.
        if (dieselbeLesart(geprueftInRunde::contains, schluessel)) return false;
        // Autor 22.10.: der Schnell-Blick fand ein anderes Team -> in dieser Stage neu ansehen, auch wenn er in diesem
        // Turnier schon angesehen wurde. Wer "sicher" ist und dessen Aussehen bekannt ist, wird nicht neu angesehen.
        boolean anders = neuAnsehen.contains(schluessel);
        if (!anders && sicherNamen.contains(schluessel) && ArenaAussehen.bekannt(schluessel) && !listeAufnehmen(name, clan)) return false;
        if (!anders && (dieselbeLesart(pruefungen::containsKey, schluessel) || dieselbeLesart(pruefVersucht::contains, schluessel))) {
            // Idee 18 (B17, 22.10.): in Runde 2-3 auch schon -- aber nur nach dem eigenen Angriff, wenn die Zeit uebrig ist.
            boolean luecke = (rundeZuletzt >= 4 || (rundeZuletzt >= 2 && rundeGespielt))
                    && !nachgeprueft.contains(schluessel) && lueckenhaft(schluessel);
            if (!luecke) return false;
        }
        if (!ArenaPruefer.moeglich(ctx)) return false;
        long rest = restSekunden();
        if (rest < 0) {   // Uhr nicht gelesen: nach der eigenen Rundenuhr (30.09.)
            return System.currentTimeMillis() - rundeSeit
                    < RUNDE_SPAETESTENS_MS - (pruefungBrauchtS() + 8) * 1000;
        }
        return rest >= reserveS() + pruefungBrauchtS();
    }

    /** Die in DIESER Stage schon angesehenen Gegner -- je Stage neu (11.10.). */
    private final java.util.Set<String> geprueftInRunde = new java.util.HashSet<>();

    /** Gegner, die in diesem Turnier schon ein zweites Mal angesehen wurden (08.10.). */
    private final java.util.Set<String> nachgeprueft = new java.util.HashSet<>();
    /** Das eigene Team ist in diesem Turnier schon angesehen (oder versucht) -- 08.10. */
    private boolean eigenesTeamGeprueft;
    /** Autor 03.11.: in einem langen Lauf schon einmal voll gescannt (je Start einmal). */
    private boolean langerLaufGescannt;

    /** Mehr als 25 Arenen bestellt, oder die Stoppzeit liegt 3 Stunden und mehr in der Zukunft (Autor 03.11.). */
    static boolean langerLauf(int zielRuns, long endeUm, long jetzt) {
        return zielRuns > 25 || (endeUm > jetzt && endeUm - jetzt >= 3 * 3_600_000L);
    }
    /** Deine Reihe im Vergleichsfenster beim Start des letzten eigenen Scans (Autor 22.10.). */
    private Mat reiheBeimScan;
    private static final String REIHE_DATEI = "arena-ich-reihe.png";
    private static final double REIHE_GLEICH_AB = 0.85;
    /** Autor 24.10.: gleiche Kraft (+-1 %) und die Reihe mindestens so aehnlich = dasselbe Team. */
    static final double REIHE_AEHNLICH_AB = 0.70;
    /** Portraets deiner Einheiten beim letzten vollen Scan (wie die Bilder der Sperrliste, ArenaSperrBilder). */
    private static final String REIHE_BILDER_DATEI = "arena-ich-reihe-bilder.png";
    /** Deine Kraft aus dem Vergleichsfenster beim letzten Scan, dazu Name und Kraft jeder Einheit (Autor 24.10.). */
    private static final String TEAM_DATEI = "arena-ich-team.txt";
    /** Unter diesem Schluessel liegt das Aussehen deiner Einheiten fuer den Schnell-Blick (arena-aussehen\). */
    static final String EIGEN_SCHLUESSEL = "~eigenes-team~";
    /** Portraets deiner Reihe und deine Kraft beim Start des laufenden eigenen Scans. */
    private ArenaSperrBilder.Merkmale reiheBilderBeimScan;
    private long kraftBeimScan = -1;
    /** Name und Kraft je Einheit aus dem eben fertigen vollen Scan (fuer arena-ich-team.txt), null = keiner. */
    private String eigeneEinheitenZeilen;
    /** Der kurze Blick fand eine Aenderung: beim naechsten Bild gleich voll scannen (Autor 24.10.). */
    private boolean eigenesTeamVoll;

    /** Deine Kraft beim letzten Scan (arena-ich-team.txt), -1 = keine gemerkt. */
    private static long eigeneKraftGemerkt() {
        try {
            java.nio.file.Path d = java.nio.file.Path.of(TEAM_DATEI);
            if (!java.nio.file.Files.exists(d)) return -1;
            for (String z : java.nio.file.Files.readAllLines(d, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] t = z.split("\t");
                if (t.length >= 2 && t[0].strip().equals("kraft")) return Long.parseLong(t[1].strip());
            }
        } catch (Exception e) {
            Log.debug("Arena", TEAM_DATEI + " nicht lesbar: " + e.getMessage());
        }
        return -1;
    }

    /**
     * arena-ich-team.txt schreiben: deine Kraft (aus dem Vergleichsfenster) und je Einheit Name und Kraft. einheiten = null:
     * die Einheiten-Zeilen der alten Datei bleiben (nach einem kurzen Blick, der nichts Neues fand).
     */
    private static void teamDateiSchreiben(long kraft, String einheiten) {
        try {
            java.nio.file.Path d = java.nio.file.Path.of(TEAM_DATEI);
            String alt = "";
            if (einheiten == null && java.nio.file.Files.exists(d)) {
                StringBuilder b = new StringBuilder();
                for (String z : java.nio.file.Files.readAllLines(d, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!z.isBlank() && Character.isDigit(z.charAt(0))) b.append(z).append(System.lineSeparator());
                }
                alt = b.toString();
            }
            String inhalt = Sprache.t("# Own team at the last arena scan -- power from the comparison window, then per unit: number, name, power, builder.",
                    "# Eigenes Team beim letzten Arena-Scan -- Kraft aus dem Vergleichsfenster, dann je Einheit: Nummer, Name, Kraft, Bauarbeiter.")
                    + System.lineSeparator() + "kraft\t" + kraft + System.lineSeparator()
                    + "stand\t" + java.time.LocalDateTime.now().withNano(0) + System.lineSeparator()
                    + (einheiten == null ? alt : einheiten);
            java.nio.file.Path neu = d.resolveSibling(d.getFileName() + ".neu");
            java.nio.file.Files.writeString(neu, inhalt, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(neu, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.debug("Arena", TEAM_DATEI + " nicht geschrieben: " + e.getMessage());
        }
    }

    /** "1 TAB Name TAB Kraft TAB bau/-" je Einheit aus dem Aussehen des vollen Scans und den gelesenen Namen. */
    static String einheitenZeilen(List<ArenaAussehen.Einheit> aussehen, String namen) {
        String[] n = namen == null || namen.isBlank() ? new String[0] : namen.split("[|]");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < aussehen.size(); i++) {
            ArenaAussehen.Einheit e = aussehen.get(i);
            b.append(i + 1).append('\t').append(i < n.length ? n[i].replace('\t', ' ').strip() : "").append('\t')
                    .append(e.kraft()).append('\t').append(e.bauarbeiter() ? "bau" : "-").append(System.lineSeparator());
        }
        return b.toString();
    }

    /** Held + Einheiten deiner Reihe: 355 links/rechts der Schwerter, 180 bis 50 darueber (Foto Arena 15.10.). */
    private static Mat eigeneReihe(Mat screen, BotContext ctx, Matcher.Hit schwerter) {
        double f = ctx.width() / 1159.0;
        int x = Math.max(0, schwerter.centerX() - (int) Math.round(355 * f));
        int y = Math.max(0, schwerter.centerY() - (int) Math.round(180 * f));
        int w = Math.min((int) Math.round(710 * f), screen.cols() - x);
        int h = Math.min((int) Math.round(130 * f), screen.rows() - y);
        if (w < 50 || h < 30) return null;
        Mat grau = new Mat();
        org.opencv.imgproc.Imgproc.cvtColor(screen.submat(new org.opencv.core.Rect(x, y, w, h)), grau,
                screen.channels() == 4 ? org.opencv.imgproc.Imgproc.COLOR_BGRA2GRAY : org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
        return grau;
    }

    /** Aehnlichkeit mit der gespeicherten Reihe (0..1), -1 = keine gespeichert. Kleine Verschiebung erlaubt. */
    private static double reiheVergleichen(Mat neu) {
        if (neu == null || !java.nio.file.Files.exists(java.nio.file.Path.of(REIHE_DATEI))) return -1;
        Mat alt = org.opencv.imgcodecs.Imgcodecs.imread(REIHE_DATEI, org.opencv.imgcodecs.Imgcodecs.IMREAD_GRAYSCALE);
        try {
            if (alt.empty() || Math.abs(alt.cols() - neu.cols()) > 4 || Math.abs(alt.rows() - neu.rows()) > 4) return -1;
            int rand = 8;
            if (alt.cols() <= 2 * rand || alt.rows() <= 2 * rand) return -1;
            Mat kern = alt.submat(new org.opencv.core.Rect(rand, rand, alt.cols() - 2 * rand, alt.rows() - 2 * rand));
            return reiheAehnlichkeit(neu, kern);
        } finally {
            alt.release();
        }
    }

    /**
     * Autor 01.11. (Pruefung 3.137): "line-up 67 %" um 14:58:57, "100 %" um 15:06:38, obwohl die Reihe in beiden Bildern gleich ist. Das Vergleichsfenster steht in zwei
     * Groessen da: in 84 von 95 Bildern der Fotoserie um etwa 2 % kleiner als beim gespeicherten Bild (Schwerter bei y 355 statt 354) -- dort gab die Suche nur 0,67 (bei 0,98-facher
     * Groesse des gespeicherten Bildes 0,96). Darum wird das gespeicherte Bild in kleinen Groessenstufen (0,96-1,03) gesucht, die beste gilt. Eine andere Reihe bleibt dabei weit
     * unten (die Stufen ueberbruecken nur die Fenstergroesse, nicht den Inhalt).
     */
    static double reiheAehnlichkeit(Mat neu, Mat kern) {
        double beste = -1;
        for (double f : REIHE_STUFEN) {
            Mat k = kern;
            if (Math.abs(f - 1.0) > 0.001) {
                k = new Mat();
                org.opencv.imgproc.Imgproc.resize(kern, k, new org.opencv.core.Size(Math.round(kern.cols() * f), Math.round(kern.rows() * f)), 0, 0, org.opencv.imgproc.Imgproc.INTER_AREA);
            }
            try {
                if (k.cols() > neu.cols() || k.rows() > neu.rows() || k.cols() < 20 || k.rows() < 20) continue;
                Mat erg = new Mat();
                org.opencv.imgproc.Imgproc.matchTemplate(neu, k, erg, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
                beste = Math.max(beste, org.opencv.core.Core.minMaxLoc(erg).maxVal);
                erg.release();
            } finally {
                if (k != kern) k.release();
            }
        }
        return beste;
    }

    /** Groessenstufen des gespeicherten Reihen-Bildes (1,0 zuerst: ist es genau gleich, ist die Suche sofort bei 1,00). */
    static final double[] REIHE_STUFEN = {1.0, 0.99, 0.98, 0.97, 0.96, 1.01, 1.02, 1.03};

    /** Nach einem vollen eigenen Scan die Reihe ablegen -- daran wird die naechste Arena gemessen. */
    /**
     * So viele Einheiten muss ein Scan deines Teams gesehen haben, damit er als Messlatte und Vergleich gilt (Autor 24.10.:
     * 1 Einheit gesehen -> Messlatte 8 statt 86). 5, oder weniger, wenn dein Team zuletzt wirklich kleiner war.
     */
    private static int eigeneMindestEinheiten() {
        // Code-Review 26.10. (TR5, 3 Einheiten): meineEinheiten ist 6, bis ein eigener Scan angenommen wurde -- der wurde
        // aber gerade wegen "zu wenig Einheiten" nie angenommen. Die Zahl aus dem Kasernen-Scan bricht den Kreis.
        int kaserne = MeinTeam.einheitenAnzahl();
        return Math.max(1, Math.min(5, kaserne > 0 ? Math.min(kaserne, Gegnerbuch.meineEinheiten()) : Gegnerbuch.meineEinheiten()));
    }

    private void eigenesTeamMerken() {
        // Autor 24.10.: dazu die Portraets deiner Einheiten, deine Kraft und je Einheit Name und Kraft.
        eigeneReiheMerken(eigeneEinheitenZeilen);
        eigeneEinheitenZeilen = null;
    }

    /**
     * Reihe, Portraets und Kraft vom Start dieses eigenen Scans als neuen Vergleich ablegen. einheiten = Zeilen fuer
     * arena-ich-team.txt (null = die alten Einheiten-Zeilen bleiben, z. B. nach einem kurzen Blick ohne Aenderung).
     */
    private void eigeneReiheMerken(String einheiten) {
        if (reiheBeimScan != null) {
            org.opencv.imgcodecs.Imgcodecs.imwrite(REIHE_DATEI, reiheBeimScan);
            reiheBeimScan.release();
            reiheBeimScan = null;
        }
        if (reiheBilderBeimScan != null && !reiheBilderBeimScan.leer()) {
            ArenaSperrBilder.speichern(java.nio.file.Path.of(REIHE_BILDER_DATEI), reiheBilderBeimScan);
        }
        reiheBilderBeimScan = null;
        if (kraftBeimScan > 0 || einheiten != null) teamDateiSchreiben(kraftBeimScan > 0 ? kraftBeimScan : eigeneKraftGemerkt(), einheiten);
        kraftBeimScan = -1;
    }

    /** Fremde Kaempfe aus dem Kampfprotokoll, "verlierer>sieger" (Vergleichsformen), je Turnier. */
    private final java.util.Set<String> fremdeKaempfe = new java.util.HashSet<>();
    /** Je Spieler: gegen wen er in diesem Turnier verloren hat (laut Kampfprotokoll). */
    private final java.util.Map<String, java.util.Set<String>> verlorenGegen = new java.util.HashMap<>();
    /** Fuer wen der Bonus schon gemeldet wurde (sonst je Bild eine Zeile). */
    private final java.util.Set<String> bonusGemeldet = new java.util.HashSet<>();
    /** Fuer wen der Angstgegner-Deckel schon gemeldet wurde (einmal je Turnier). */
    private final java.util.Set<String> deckelGemeldet = new java.util.HashSet<>();
    /** Je Angreifer: gegen wen er nur muehsam (<= 130 Punkte) gewonnen hat (08.10.). */
    private final java.util.Map<String, java.util.Set<String>> muehsamGegen = new java.util.HashMap<>();

    /**
     * Steht hier der eigene Name (im Kampfprotokoll gelb)? FREUNDE-FASSUNG (11.10.): Im Hauptbot
     * stand hier fest "autor". Hier gilt der Name, der am eigenen Schild (ar_me) gelesen wurde --
     * oder, falls vorhanden, die erste Zeile von arena-name.txt im Bot-Ordner.
     */
    private static boolean istAutor(String name) {
        String ich = eigenerName();
        if (ich.isEmpty()) return false;
        String form = de.hustlebot.core.NamensVergleich.form(name);
        return form.equals(ich) || de.hustlebot.core.NamensVergleich.gleicheForm(form, ich, 0.85);
    }

    /** FREUNDE-FASSUNG: der eigene Spielername (Vergleichsform), "" = noch unbekannt. */
    private static volatile String eigenerNameGelesen = "";
    private static int eigenerNameVersuche;
    private static final java.nio.file.Path NAME_DATEI = java.nio.file.Path.of("arena-name.txt");

    private static String eigenerName() {
        try {
            if (java.nio.file.Files.exists(NAME_DATEI)) {
                for (String z : java.nio.file.Files.readAllLines(NAME_DATEI, java.nio.charset.StandardCharsets.UTF_8)) {
                    String f = de.hustlebot.core.NamensVergleich.form(z);
                    if (!z.strip().startsWith("#") && f.length() >= 2) return f;
                }
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-name.txt nicht lesbar: " + e.getMessage());
        }
        return eigenerNameGelesen;
    }

    /** FREUNDE-FASSUNG: den eigenen Namen einmal am eigenen Schild (Treffer von ar_me) ablesen. */
    private static void eigenenNamenLesen(BufferedImage bild, Matcher.Hit ich) {
        if (bild == null || ich == null || !eigenerNameGelesen.isEmpty()) return;
        if (++eigenerNameVersuche > 3) return;   // Lesezeit sparen: hoechstens dreimal je Programmlauf
        String text = ArenaLeser.liesName(bild, Math.max(0, ich.x() - 6), Math.max(0, ich.y() - 4),
                ich.width() + 12, ich.height() + 8);
        String f = de.hustlebot.core.NamensVergleich.form(text);
        if (f.length() < 2) {
            // Dritter Fehlversuch: einmal sagen, wie es ohne Lesen geht (sonst fiel die Auswertung still aus).
            if (eigenerNameVersuche == 3 && eigenerName().isEmpty()) {
                Log.hinweis("Arena", Sprache.t(
                        "The bot could not read your own name on your shield. Without it, the battle log does not "
                        + "know which fights were yours. Fix: write your game name into the first line of "
                        + "arena-name.txt in the bot folder (a new text file next to the bot).",
                        "Den eigenen Namen am Schild konnte der Bot nicht lesen. Ohne ihn weiss er im Kampfprotokoll "
                        + "nicht, welche Kaempfe deine waren. Abhilfe: deinen Spielnamen in die erste Zeile von "
                        + "arena-name.txt im Bot-Ordner schreiben (neue Textdatei neben dem Bot)."));
            }
            return;
        }
        eigenerNameGelesen = f;
        Log.info("Arena", Sprache.t("Own name read from the shield: \"" + text.strip() + "\" (for the battle log).",
                "Eigener Name am Schild gelesen: \"" + text.strip() + "\" (fuer das Kampfprotokoll)."));
    }

    /**
     * Die Siegchance -- mit Autors Regel vom 08.10. obendrauf: Ein Gegner, gegen den die Chance
     * niedrig ist (oder der als "ungern"/"niemals" aufgenommen ist), hat in diesem Turnier laut
     * Kampfprotokoll gegen einen LEICHTEN Gegner verloren (Bot, "sicher" bzw. mindestens 10 Punkte
     * unter der Messlatte) -- dann +10 %. "Ein starker Typ, der gegen einen Freien verliert, ist
     * vielleicht doch nicht so stark."
     */
    private double siegchance(Schild s) {
        double chance = opAbzug(s, deckeln(s, siegchanceOhneDeckel(s)));
        if (de.hustlebot.schicht.ArenaStand.an()) schichtChance.put(schichtSchluessel(s), chance);
        return chance;
    }

    // --- Arena-Schicht (Autor 28.10., nur mit arena-schicht.txt "an") --------------------------------------------
    // Was die Rechnung je Schild ergab, damit die Schicht es zeigen kann, ohne selbst noch einmal zu rechnen.
    // Schluessel ist die Lage des Schilds (auf 8 Punkte gerundet) -- der Name ist oft unlesbar, die Lage steht
    // innerhalb einer Runde still.
    private final java.util.Map<String, Double> schichtChance = new java.util.HashMap<>();
    /** Die Chance aus wahlChance() -- damit das Seitenfenster dieselbe Zahl zeigt, nach der gewaehlt wird. */
    private final java.util.Map<String, Double> schichtWahl = new java.util.HashMap<>();
    private final java.util.Map<String, String> schichtWoher = new java.util.HashMap<>();
    /** Schilder, deren Chance nur der Platzhalter 50 % "unbekannt" ist (siegchanceRoh): die Anzeige zeigt "?". */
    private final java.util.Set<String> schichtUngerechnet = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int schichtRunde = -1;
    private long schichtGemeldetUm = 0;

    private String schichtSchluessel(Schild s) {
        // Lage UND Name (Review 28.10.): ordnet sich die Pyramide innerhalb einer Runde um, steht sonst B neben A's Chance.
        return (s.x() / 8) + ":" + (s.y() / 8) + ":" + namensSchluessel(s.name());
    }

    /**
     * Legt fuer die Arena-Schicht ab, was der Bot gerade von der Pyramide weiss: je Schild Lage, Name, Liste,
     * Siegchance mit Begruendung und ob es das Ziel ist. Hoechstens alle 1,5 s; ohne Schalter gar nicht.
     */
    private void schichtMelden(Mat screen, BotContext ctx, List<Schild> schilder, int runde, Optional<Schild> ziel) {
        schichtMelden(screen, ctx, schilder, runde, ziel, false);
    }

    /** @param beobachter Autor spielt selbst: wer ein "Reward:"-Schild hat, ist in diesem Turnier noch nicht angegriffen -- egal, was der Bot im letzten Turnier angriff. */
    private void schichtMelden(Mat screen, BotContext ctx, List<Schild> schilder, int runde, Optional<Schild> ziel, boolean beobachter) {
        if (!de.hustlebot.schicht.ArenaStand.an()) return;
        if (de.hustlebot.schicht.ArenaStand.chatGeradeOffen()) return;   // Autor 29.10.: nicht ueber den Chat legen
        if (runde != schichtRunde) {
            // Neue Runde: die Pyramide hat sich umgeordnet, alte Lagen gehoeren jetzt anderen Spielern.
            schichtChance.clear();
            schichtWoher.clear();
            schichtPlatzStufe.clear();
            schichtWahl.clear();
            schichtRunde = runde;
        }
        long jetzt = System.currentTimeMillis();
        if (jetzt - schichtGemeldetUm < 1500) return;
        schichtGemeldetUm = jetzt;
        List<de.hustlebot.schicht.ArenaStand.Gegner> gegner = new ArrayList<>();
        for (Schild s : schilder) {
            // Autor 29.10. abends ("die Informationen im Seitenfenster muessen stimmen"): wer diese Arena schon angegriffen
            // wurde, steht ohne Rechnung da (Runde 5: ionette mit 95 % angezeigt, war aber verbraucht).
            if (!beobachter && schonAngegriffen.contains(namensSchluessel(s.name()))) continue;
            String k = schichtSchluessel(s);
            // In Runde 1 rechnet die Wahl nur fuer Gegner mit bekannter Kraft -- die uebrigen einmal je Runde nachholen.
            if (!schichtChance.containsKey(k)) siegchance(s);
            // Die Chance, nach der der Bot gerade entscheidet (ab Runde 2 mit Platz-Regeln), sonst die Siegchance.
            Double c = schichtWahl.containsKey(k) ? schichtWahl.get(k) : schichtChance.get(k);
            String stufe = s.pause() && s.gesperrt() ? "pause" : s.gesperrt() ? "niemals" : s.ungern() ? "meiden" : s.sicher() ? "sicher"
                    : s.fastSicher() ? "fastsicher"
                    : s.machbar() ? "machbar" : "";
            boolean istZiel = ziel.isPresent() && ziel.get().x() == s.x() && ziel.get().y() == s.y();
            int platzHier = platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt);
            // Autor 01.11.: nur der Platzhalter 50 % (nichts ueber ihn bekannt, noch nicht angesehen) -> "?" statt einer Zahl; hat die Wahl ihn schon
            // gerechnet (schichtWahl), gilt deren Zahl.
            boolean ungerechnet = schichtUngerechnet.contains(k) && !schichtWahl.containsKey(k);
            int prozent = anzeigeProzent(ungerechnet, c);
            gegner.add(new de.hustlebot.schicht.ArenaStand.Gegner(s.x(), s.y(),
                    platzHier, s.name(), s.clan(), s.botClan(), stufe,
                    s.clanListe(), s.autoListe(), s.punkte(), prozent,
                    ungerechnet ? Sprache.t("Not looked at yet -- no number yet", "Noch nicht angesehen -- noch keine Zahl") : schichtWoher.getOrDefault(k, ""),
                    listenText(s), istZiel, steckbrief(s, prozent, platzHier)));
        }
        schichtLetzterStand = new de.hustlebot.schicht.ArenaStand.Stand(
                jetzt, runde, breiteZuletzt, hoeheZuletzt, gegner, schichtPlaetze(screen, ctx, gegner));
        de.hustlebot.schicht.ArenaStand.setzen(schichtLetzterStand);
    }

    /** Die Zahl fuer die Anzeige: -1 (= "?") fuer den Platzhalter 50 % "unbekannt" und fuer eine fehlende Rechnung, sonst gerundet in Prozent. */
    static int anzeigeProzent(boolean ungerechnet, Double chance) {
        return ungerechnet || chance == null ? -1 : (int) Math.round(chance * 100);
    }

    /** Was das Seitenfenster ueber diesen Spieler schreibt (ArenaSteckbrief). Nur Anzeige: ein Fehler hier stoert nichts. */
    private String steckbrief(Schild s, int chance, int platz) {
        try {
            boolean mitName = !s.name().isBlank();
            var notiz = mitName ? buch.nachschlagen(s.name(), s.clan()) : Optional.<Gegnerbuch.Notiz>empty();
            int[] bilanz = mitName ? gesamtBilanz(s) : new int[]{0, 0};
            String verlauf = notiz.map(Gegnerbuch.Notiz::verlauf).orElse("");
            List<Integer> dauern = notiz.map(n -> n.letzteDauern(Gegnerbuch.MERKT_SICH)).orElse(List.of());
            String zuletzt = notiz.map(Gegnerbuch.Notiz::zuletzt).orElse("");
            List<Long> punkte = notiz.map(n -> punkteListe(n.punkte())).orElse(List.of());
            int schnitt = notiz.map(Gegnerbuch.Notiz::mittlerePunkte).orElse(-1);
            int siege = bilanz[0], kaempfe = bilanz[1];
            // Noch nicht im Notizblock des Bots, aber von Autor selbst gespielt: sein eigener Notizblock.
            var selbst = notiz.isEmpty() && mitName && de.hustlebot.core.ArenaRegelnNeu.an() ? de.hustlebot.core.SelbstKaempfe.finde(s.name()) : null;
            if (selbst != null && !selbst.kaempfe().isEmpty()) {
                verlauf = selbst.ergebnisse();
                siege += selbst.siege();
                kaempfe += selbst.kaempfe().size();
                dauern = selbst.kaempfe().stream().map(de.hustlebot.core.SelbstKaempfe.Kampf::dauerS).filter(d -> d > 0).toList();
                punkte = selbst.kaempfe().stream().filter(de.hustlebot.core.SelbstKaempfe.Kampf::sieg)
                        .map(de.hustlebot.core.SelbstKaempfe.Kampf::punkte).filter(p -> p > 0).toList();
                zuletzt = selbst.kaempfe().get(selbst.kaempfe().size() - 1).um();
                schnitt = punkte.isEmpty() ? -1 : (int) Math.round(punkte.stream().mapToLong(Long::longValue).sum() / (double) selbst.kaempfe().size());
            }
            Integer p = punkteJetzt.get(namensSchluessel(s.name()));
            String teile = notiz.filter(n -> n.setPunkte() >= 0).map(ArenaMode::teamTeile).orElse("");
            return ArenaSteckbrief.text(new ArenaSteckbrief.Werte(verlauf, siege, kaempfe, chance, dauern, zuletzt, punkte, schnitt, s.punkte(),
                    arenaPunkteAuf(platz), bekannteStaerke(s), meineStaerke, p == null ? ArenaSteckbrief.PUNKTE_UNBEKANNT : p, teile));
        } catch (RuntimeException e) {
            Log.debug("Arena", "Steckbrief: " + e);
            return "";
        }
    }

    private static List<Long> punkteListe(String kette) {
        List<Long> l = new ArrayList<>();
        if (kette == null || kette.isBlank()) return l;
        for (String t : kette.trim().split("\\s+")) {
            try {
                l.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) { }
        }
        return l;
    }

    private static String teamTeile(Gegnerbuch.Notiz n) {
        int fehlend = n.einheiten() > 0 ? Math.max(0, Gegnerbuch.meineEinheiten() - n.einheiten()) : 0;
        return ArenaSteckbrief.teamTeile(n.setPunkte(), 3 * Math.max(0, n.artefakte()), 5 * Math.max(0, n.bauarbeiter()),
                Gegnerbuch.JE_FEHLENDE_EINHEIT * fehlend, Gegnerbuch.steinPunkte(n.gelbeSteine(), n.roteSteine()),
                Gegnerbuch.schirmPunkte(n.schirmAnzahl(), n.guteSchirme(), n.guteStigmas()), Gegnerbuch.palPunkte(n.gutePals()));
    }

    /** Die Turnierpunkte auf diesem Platz, -1 = nicht gelesen. */
    private long arenaPunkteAuf(int platz) {
        long[] t = tabelle;
        return t != null && tabelleRunde == rundeZuletzt && platz > 0 && platz < t.length ? t[platz] : -1;
    }

    /** So viel verschieben die Arena-Punkte eines "Not in clan" hoechstens, in Log-Chancen (Autor 30.10.: "nur ganz kleine Unterschiede"). */
    static final double OHNE_CLAN_LOGIT = 1.0;

    /**
     * Autor 30.10.: "Bei denen, die not in clan sind und die der Bot nicht kennt: je mehr Punkte sie haben, desto niedriger die Siegchance, nur ganz kleine
     * Unterschiede; je weniger Punkte in den hoeheren Stages, desto hoeher." Eine feine Reihenfolge unter den Bots: aus 95 % werden 92 bis 97 %.
     * Gegen "Not in clan" steht es 171 von 171 (Auswertung 30.10.), das aendert also keine Chance grundsaetzlich.
     * @param arenaPunkte seine Turnierpunkte jetzt, -1 = nicht gelesen
     */
    static double ohneClanNachPunkten(double chance, int runde, long arenaPunkte) {
        if (runde < 2 || arenaPunkte < 0 || chance <= 0 || chance >= 1) return chance;
        double anteil = Math.min(1.0, arenaPunkte / (225.0 * (runde - 1)));   // 0 = alles verloren, 1 = jedes Mal 225 geholt
        double logit = Math.log(chance / (1 - chance)) + (0.5 - anteil) * OHNE_CLAN_LOGIT;
        return Math.min(ZIELART_HOECHSTENS, 1 / (1 + Math.exp(-logit)));
    }

    /** Was schichtMelden zuletzt gebaut hat; bleibt null, wenn es vorher abbrach (Chat offen). */
    private volatile de.hustlebot.schicht.ArenaStand.Stand schichtLetzterStand;

    /**
     * Autor 29.10. (zugeschaut, Stage 3): "Bei Stage 1 bis 5, immer wenn die Pyramide zu sehen ist, soll auch die Schicht
     * zu sehen sein. Ich sehe da gar nichts." Ursache: schichtMelden lief nur direkt vor der Zielwahl -- beim Erkunden,
     * beim Warten auf den spaeten Angriff und nach dem eigenen Angriff ("Next stage in") kam nichts, nach 3 s
     * (ArenaStand.HALTBAR_MS) blendete die Schicht sich aus. Jetzt bei JEDEM Pyramiden-Bild: alle 2 s den Stand dieser
     * Runde weiter gelten lassen, alle 15 s die Schilder neu lesen (Namen/Listen/besiegt koennen sich aendern).
     */
    private long schichtWarteUm, schichtBerechnetUm;

    /**
     * Autor 29.10. abends: "Der unsichtbare Screen soll wirklich nur offen sein, wenn die Pyramide offen ist -- nicht
     * wenn der Bot auf einen Gegner drueckt oder wenn ich den Chat oeffne." Bisher blieb der letzte Stand nach dem
     * Verlassen der Pyramide noch bis zu 3 s stehen (ArenaStand.HALTBAR_MS), und der Chat wurde nur an einem Bild
     * erkannt. Jetzt in JEDEM Bild: keine Pyramide, Battle-Log offen oder Chat offen -> Schicht sofort weg.
     */
    private void schichtNurBeiPyramide(Mat screen, BotContext ctx) {
        if (!de.hustlebot.schicht.ArenaStand.an() || de.hustlebot.schicht.ArenaStand.aktuell() == null) return;
        boolean pyramide = ctx.visible(screen, T_SELECT) || ctx.visible(screen, T_NEXT_STAGE);
        // Autor 30.10.: auch Vergleichsfenster und Sieges-/Verlustfenster: dahinter kann die Pyramide noch erkannt werden.
        if (!pyramide || ctx.visibleIfKnown(screen, T_LOG_CLOSE) || ctx.visible(screen, T_YOUR_SQUAD) || ergebnisFensterOffen(screen, ctx)) {
            de.hustlebot.schicht.ArenaStand.setzen(null);
            return;
        }
        chatOffenFuerSchicht(screen, ctx);
        schichtBefehleAusfuehren(ctx);
    }

    /** "Tournament points scored" und "Battle time" gibt es nur im Sieges-/Verlustfenster, nicht auf den Schildern der Pyramide. */
    private static boolean ergebnisFensterOffen(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, T_POINTS) || ctx.visibleIfKnown(screen, T_TIME);
    }

    // ---- Autors Tasten an der Schicht (Autor 30.10.: "Wie markier ich Spieler fuer die Listen?") ---------------------------
    // Die Tasten legten bisher nur Befehle ab, gespeichert wurde nichts. Jetzt schneidet der Arena-Modus das Namensschild aus dem
    // aktuellen Bild und legt es als AUTORS Listenbild ab (ohne Bot-Endung: der Bot ersetzt es nie). Alte Listenbilder desselben
    // Spielers kommen nach templates-aussortiert, geloescht wird nichts. Die Clan-Taste fehlt noch.

    /** So alt darf ein Tastendruck sein: danach kann schon ein anderer Spieler an der Stelle stehen. */
    private static final long TASTE_HALTBAR_MS = 15_000;

    private void schichtBefehleAusfuehren(BotContext ctx) {
        for (var b : de.hustlebot.schicht.ArenaStand.befehleNehmen()) {
            try {
                switch (b.art()) {
                    case LISTE -> listeAusTaste(ctx, b);
                    case ZURUECK -> {
                        if (!b.clanWert()) listeAusTaste(ctx, b);
                    }
                    case CLAN -> Log.info("Arena", Sprache.t("Layer: the clan key is not built yet -- nothing saved.",
                            "Schicht: die Clan-Taste ist noch nicht gebaut -- nichts gespeichert."));
                    default -> { }   // Zustimmen, Ablehnen, Kommentar schreibt die Schicht selbst auf
                }
            } catch (Exception e) {
                Log.warn("Arena", Sprache.t("Layer: key could not be saved: ", "Schicht: Taste nicht gespeichert: ") + e);
            }
        }
    }

    private void listeAusTaste(BotContext ctx, de.hustlebot.schicht.ArenaStand.Befehl b) throws java.io.IOException {
        BufferedImage bild = ctx.frame();
        int w = ctx.width(), h = ctx.height(), p = b.platz();
        if (bild == null || p < 1 || p > 10) return;
        if (System.currentTimeMillis() - b.um() > TASTE_HALTBAR_MS) {
            Log.info("Arena", Sprache.t("Layer: key for place " + p + " came too late -- nothing saved.",
                    "Schicht: Taste fuer Platz " + p + " kam zu spaet -- nichts gespeichert."));
            return;
        }
        String art = switch (b.wert()) {
            case "sicher" -> T_SICHER_PREFIX;
            case "machbar" -> T_MACHBAR_PREFIX;
            case "fastsicher" -> T_FAST_PREFIX;
            case "pause" -> T_PAUSE_PREFIX;
            case "meiden" -> T_UNGERN_PREFIX;
            case "niemals" -> T_BLOCK_PREFIX;
            default -> null;   // "neutral": von allen Listen
        };
        BufferedImage kasten = namensKasten(bild, p);
        if (kasten == null) {
            Log.info("Arena", Sprache.t("Layer: no name found at place " + p + " -- nothing saved.",
                    "Schicht: auf Platz " + p + " kein Name gefunden -- nichts gespeichert."));
            return;
        }
        String gelesen = beobKastenLesen(kasten);
        if (!b.text().isBlank() && !gelesen.isBlank() && !de.hustlebot.core.NamensVergleich.gleicherName(b.text(), gelesen)) {
            // Die Pyramide hat sich gerade umgeordnet: lieber nichts als der falsche Spieler auf der Liste.
            Log.info("Arena", Sprache.t("Layer: place " + p + " now shows " + gelesen + " instead of " + b.text() + " -- nothing saved.",
                    "Schicht: auf Platz " + p + " steht jetzt " + gelesen + " statt " + b.text() + " -- nichts gespeichert."));
            return;
        }
        String name = b.text().isBlank() ? gelesen : b.text();
        java.nio.file.Path wurzel = ctx.matcher().templateRoot();
        String kurz = name.replaceAll("[^\\p{L}\\p{N}]", "");
        if (kurz.isEmpty()) kurz = "gegner" + (b.um() % 100_000);
        java.nio.file.Path ziel = art == null ? null : de.hustlebot.core.ArenaVorlagenDoppelt.ordnerFuer(wurzel, art)
                .resolve(art + kurz + ".png").toAbsolutePath().normalize();
        // Erst nach dem Bild, dann nach dem Namen suchen (wie namensbildSpeichern): ein falsch gelesener Name liesse sonst das alte Bild liegen.
        java.util.Set<java.nio.file.Path> alte = new java.util.LinkedHashSet<>();
        de.hustlebot.core.ArenaVorlagenDoppelt.finde(kasten, wurzel, null).ifPresent(t -> alte.add(t.datei()));
        for (var t : de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(name, wurzel)) alte.add(t.datei());
        for (java.nio.file.Path altesBild : alte) {
            // Wieder "niemals": das alte Sperrbild bleibt dazu liegen (Niemals-Liste dauerhaft, jedes Bild erkennt ihn etwas anders).
            if (T_BLOCK_PREFIX.equals(art) && altesBild.getFileName().toString().startsWith(T_BLOCK_PREFIX)
                    && !altesBild.toAbsolutePath().normalize().equals(ziel)) continue;
            java.nio.file.Path weg = de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(de.hustlebot.core.Ordner.basis(), altesBild);
            java.nio.file.Files.createDirectories(weg.getParent());
            java.nio.file.Files.move(altesBild, weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (ziel != null) {
            java.nio.file.Files.createDirectories(ziel.getParent());
            javax.imageio.ImageIO.write(kasten, "png", ziel.toFile());
        }
        ctx.matcher().loadAll();
        schilderCacheAt = 0;
        beobNeuBewerten(p, b.wert());   // Autor 30.10.: "instant checken und vielleicht seine Entscheidung ueberdenken"
        if (de.hustlebot.core.SelbstKaempfe.finde(name) != null) de.hustlebot.core.SelbstKaempfe.eingetragen(name, b.wert());
        Log.info("Arena", Sprache.t("Layer: your key -- " + (name.isBlank() ? "place " + p : name) + " is now on \"" + b.wert() + "\".",
                "Schicht: deine Taste -- " + (name.isBlank() ? "Platz " + p : name) + " steht jetzt auf \"" + b.wert() + "\"."));
    }

    /** Bilder, an denen der offene Spiel-Chat zu erkennen ist (Pfeil am Rand, Eingabefeld, Senden-Knopf). */
    private static final String[] CHAT_OFFEN = {FreundesChat.T_SCHLIESSEN, "chat_offen", "chat_schreiben", "chat_senden"};

    /** Ist der Spiel-Chat offen? Dann Schicht weg und keine Tasten schlucken (Autor 29.10.). */
    private boolean chatOffenFuerSchicht(Mat screen, BotContext ctx) {
        boolean offen = false;
        for (String t : CHAT_OFFEN) if (ctx.visibleIfKnown(screen, t)) { offen = true; break; }
        if (!offen) return false;
        de.hustlebot.schicht.ArenaStand.chatOffen();
        return true;
    }

    /** Die Schilder der letzten Lesung fuer die Schicht, dazu die Stage -- zum Neurechnen ohne neues Lesen. */
    private volatile List<Schild> schichtLetzteSchilder = List.of();
    private volatile int schichtLetzteRunde = -1;
    /** Autor 01.11.: der Bot hat ein Team angesehen oder eine Kraft gelesen -- die Chancen im Seitenfenster sofort neu rechnen. */
    private volatile boolean schichtStandVeraltet;

    private void schichtImWarten(Mat screen, BotContext ctx) {
        if (!de.hustlebot.schicht.ArenaStand.an()) return;
        if (chatOffenFuerSchicht(screen, ctx)) return;
        // Messung 30.10.: 37 von 379 Lesungen liefen bei offenem Protokoll, ein Schild fehlte, und das galt 5 s lang.
        if (ctx.visibleIfKnown(screen, T_LOG_CLOSE)) return;
        long jetzt = System.currentTimeMillis();
        // Autor 01.11.: "Sobald der Bot das Team angesehen hat, soll das Seitenfenster sofort die echte Chance zeigen." Die Schilder stehen noch (gleiche Stage):
        // nur die Chancen neu rechnen (siegchance liest Pruefung und Kraft frisch), ohne die Namen noch einmal zu lesen.
        if (schichtStandVeraltet && !schichtLetzteSchilder.isEmpty() && schichtLetzteRunde == rundeZuletzt) {
            schichtStandVeraltet = false;
            schichtChance.clear();
            schichtWoher.clear();
            schichtWahl.clear();
            schichtUngerechnet.clear();
            schichtGemeldetUm = 0;
            try {
                schichtMelden(screen, ctx, schichtLetzteSchilder, rundeZuletzt, Optional.empty());
                schichtWarteUm = jetzt;
                return;
            } catch (RuntimeException e) {
                Log.debug("Arena-Schicht", "Chancen nach dem Ansehen nicht neu gerechnet: " + e);
            }
        }
        if (jetzt - schichtWarteUm < 2000) return;
        schichtWarteUm = jetzt;
        if (jetzt - schichtBerechnetUm < 15_000 && de.hustlebot.schicht.ArenaStand.auffrischen(rundeZuletzt)) return;
        try {
            List<Schild> schilder = schilderLesen(screen, ctx);
            if (schilder.isEmpty()) return;
            schichtBerechnetUm = jetzt;
            schichtLetzteSchilder = schilder;
            schichtLetzteRunde = rundeZuletzt;
            schichtGemeldetUm = 0;   // sofort melden, nicht erst nach 1,5 s
            schichtMelden(screen, ctx, schilder, rundeZuletzt, Optional.empty());
        } catch (Exception e) {
            Log.debug("Arena-Schicht", "Pyramide im Warten nicht gemeldet: " + e.getMessage());
        }
    }

    /** Liste je Platz ohne "Reward:" (schon besiegt) -- je Runde einmal per Bild bestimmt. */
    private final java.util.Map<Integer, String> schichtPlatzStufe = new java.util.HashMap<>();

    /**
     * Autor 28.10.: ALLE Gegner bekommen einen Rahmen, auch die schon besiegten ("Victory!" statt "Reward:") --
     * unbekannte grau. Die zehn Plaetze stehen fest (ArenaTabelle); das eigene Schild faellt weg (es hat schon den
     * gelben Rand des Spiels). Wo ein angreifbares Schild liegt, gilt dessen Liste (auch die Auto-Liste); sonst wird
     * im Schild nach Autors Listenbildern gesucht.
     */
    private List<de.hustlebot.schicht.ArenaStand.Platz> schichtPlaetze(Mat screen, BotContext ctx,
            List<de.hustlebot.schicht.ArenaStand.Gegner> gegner) {
        return plaetzeNachBild(screen, ctx, aktuellerPlatz, breiteZuletzt, hoeheZuletzt, gegner, schichtPlatzStufe);
    }

    /** Die neun Gegner-Plaetze mit ihrer Liste; merker haelt die per Bild bestimmten Listen (je Runde). */
    private List<de.hustlebot.schicht.ArenaStand.Platz> plaetzeNachBild(Mat screen, BotContext ctx, int eigenerPlatz,
            int w, int h, List<de.hustlebot.schicht.ArenaStand.Gegner> gegner, java.util.Map<Integer, String> merker) {
        List<de.hustlebot.schicht.ArenaStand.Platz> l = new ArrayList<>();
        int b = (int) Math.round(w * 0.183);
        // Autor 01.11.: "already fought" stand bei Plaetzen, die noch "Reward:" tragen -- die Anzeige hielt jeden Platz ohne Gegner-Eintrag fuer besiegt, auch wenn
        // die Lesung gerade nur 1 von 9 Schildern fand (Bild im Wechsel). Jetzt zaehlt nur, was das Bild zeigt: "Reward:" fehlt dort.
        boolean[] ohneGegner = new boolean[11];
        double[] rewardWert = new double[11];
        for (int p = 1; p <= 10; p++) {
            if (p == eigenerPlatz) continue;
            int mx = (int) Math.round(ArenaTabelle.X[p] * w), my = (int) Math.round(ArenaTabelle.Y[p] * h);
            String stufe = null;
            for (var g : gegner) if (g.platz() == p) stufe = g.stufe();
            if (stufe == null) {
                ohneGegner[p] = true;
                rewardWert[p] = rewardWertAmPlatz(ctx.matcher(), screen, p);
                stufe = merker.computeIfAbsent(p, q -> listeImSchild(screen, ctx,
                        new org.opencv.core.Rect(mx - b / 2, my - 55, b, 126)));
            }
            l.add(new de.hustlebot.schicht.ArenaStand.Platz(p, mx, my, stufe));
        }
        boolean[] besiegt = besiegtNachBild(ohneGegner, rewardWert, Math.max(1, Math.max(rundeZuletzt, beobRundeZuletzt)));
        for (int i = 0; i < l.size(); i++) {
            var x = l.get(i);
            l.set(i, new de.hustlebot.schicht.ArenaStand.Platz(x.platz(), x.x(), x.y(), x.stufe(), besiegt[x.platz()]));
        }
        return l;
    }

    /** Ab diesem Wert gilt "Reward:" an einem Platz als sichtbar (wie PLATZ_ERSATZ_SCHWELLE), darunter fehlt es; dazwischen ist es unsicher. */
    static final double REWARD_WEG = 0.50;

    /** Der beste Wert einer "Reward:"-Vorlage an der festen Stelle dieses Platzes (platzErsatz: besiegte Schilder kamen dort auf hoechstens 0,28). */
    private static double rewardWertAmPlatz(Matcher m, Mat screen, int platz) {
        double fy = screen.rows() / 770.0;
        int cx = (int) Math.round(ArenaTabelle.X[platz] * screen.cols());
        int cy = (int) Math.round(ArenaTabelle.Y[platz] * screen.rows() + 29 * fy);
        org.opencv.core.Rect bereich = new org.opencv.core.Rect(cx - 80, cy - (int) Math.round(30 * fy), 160, (int) Math.round(60 * fy));
        double best = 0;
        for (String t : m.serie(T_REWARD)) {
            best = Math.max(best, m.findImBereich(screen, t, bereich, 0.0).map(Matcher.Hit::score).orElse(0.0));
        }
        return best;
    }

    /**
     * Welche Plaetze ohne angreifbaren Gegner sind wirklich besiegt? Nur die, an denen "Reward:" am Bild fehlt (Wert unter REWARD_WEG) -- und nur, wenn
     * es nicht mehr sind, als in dieser Stage gekaempft sein koennen (ein Kampf je Stage, also hoechstens "Stage" Stueck): sonst ist die Lesung
     * unvollstaendig (Bild im Wechsel, Fenster davor) und keiner gilt als besiegt ("wird gelesen").
     */
    static boolean[] besiegtNachBild(boolean[] ohneGegner, double[] rewardWert, int hoechstens) {
        boolean[] aus = new boolean[ohneGegner.length];
        int zahl = 0;
        for (int p = 0; p < ohneGegner.length; p++) {
            if (ohneGegner[p] && rewardWert[p] < REWARD_WEG) {
                aus[p] = true;
                zahl++;
            }
        }
        if (zahl > hoechstens) java.util.Arrays.fill(aus, false);
        return aus;
    }

    /** Welche Liste zeigt das Namensbild in diesem Schild? "" = keine. Niemals vor meiden vor sicher vor machbar. */
    private String listeImSchild(Mat screen, BotContext ctx, org.opencv.core.Rect schild) {
        org.opencv.core.Rect r = new org.opencv.core.Rect(Math.max(0, schild.x), Math.max(0, schild.y),
                Math.min(schild.width, screen.width() - Math.max(0, schild.x)),
                Math.min(schild.height, screen.height() - Math.max(0, schild.y)));
        if (r.width <= 0 || r.height <= 0) return "";
        String[][] reihe = {{T_BLOCK_PREFIX, "niemals"}, {T_PAUSE_PREFIX, "pause"}, {T_UNGERN_PREFIX, "meiden"}, {T_SICHER_PREFIX, "sicher"},
                {T_FAST_PREFIX, "fastsicher"}, {T_MACHBAR_PREFIX, "machbar"}};
        for (String[] e : reihe) {
            for (String name : ctx.matcher().names()) {
                // dieselbe Schwelle wie trefferMitPrefix (findAll)
                if (name.startsWith(e[0]) && ctx.matcher().findImBereich(screen, name, r, Matcher.DEFAULT_THRESHOLD).isPresent()) {
                    return e[1];
                }
            }
            leserMachtPlatz();
        }
        return "";
    }

    /** OP-Items des Gegners bei seiner letzten Pruefung -- nur auf Thronsaal 5 (Autor 25.10.), sonst 0. */
    private int opItemsBei(Schild s) {
        if (de.hustlebot.core.Thronsaal.aktuell() != ArenaItems.OP_THRONSAAL || s.name().isBlank()) return 0;
        int op = ArenaItems.opBei(namensSchluessel(s.name()));
        // Code-Review 26.10.: gemerkt wird unter dem Namen aus dem Vergleichsfenster -- das Schild liest ihn oft anders.
        String alias = schildAlias.get(namensSchluessel(s.name()));
        if (op == 0 && alias != null && !alias.isBlank()) op = ArenaItems.opBei(alias);
        return op;
    }

    /**
     * Autor 25.10. (TR5): "manche Items, die, wenn der Bot erkennt, dass dein Gegner so ein Item hat, wo unglaublich OP
     * ist, dass er den Gegner gar nicht mehr angreifen soll" -- ausser du hast selbst so eins (dann opAbzug).
     */
    private boolean opGesperrt(Schild s) {
        int op = opItemsBei(s);
        if (op <= 0 || ArenaItems.eigeneOp()) return false;
        if (opGemeldet.add(namensSchluessel(s.name()))) {
            Log.info("Arena", Sprache.t(s.wer() + " has " + op + " incredibly OP item(s) -- not attacked (you have none).",
                    s.wer() + " hat " + op + " unglaublich OP-Item(s) -- wird nicht angegriffen (du hast keins)."));
        }
        return true;
    }

    /** Autor 25.10.: "Wenn ich auch eins von diesen Items habe ... soll sich die Siegchance deutlich verschlechtern" -- minus 30 %. */
    private double opAbzug(Schild s, double chance) {
        int op = opItemsBei(s);
        if (op <= 0 || !ArenaItems.eigeneOp()) return chance;
        double neu = Math.max(0.0, chance - ArenaItems.OP_ABZUG);
        if (opGemeldet.add(namensSchluessel(s.name()) + "~")) {
            Log.info("Arena", Sprache.t(s.wer() + " has an incredibly OP item, you too -- win chance " + Math.round(chance * 100) + " -> "
                            + Math.round(neu * 100) + " %.",
                    s.wer() + " hat ein unglaublich OP-Item, du auch -- Siegchance " + Math.round(chance * 100) + " -> "
                            + Math.round(neu * 100) + " %."));
        }
        return neu;
    }

    /** Milde Stufe vor dem Angstgegner: so viel weniger und hoechstens so viel (Autor 29.10.). */
    static final double EINMAL_VERLOREN_ABZUG = 0.15, EINMAL_VERLOREN_HOECHSTENS = 0.75;

    /**
     * Autor 29.10.: "eine zweite Regel, die greift, wenn gegen einen Spieler schon mal verloren wurde -- wie eine
     * Angstgegner-Regel, nur nicht so streng" (Myth Gods: eine Niederlage, 82 % -> damals hart 40 %). Genau eine
     * Niederlage und noch kein Sieg gegen ihn: 15 Prozentpunkte weniger, hoechstens 75 %. Ab zwei Niederlagen gilt der
     * Angstgegner (hoechstens 60 %), wer schon einmal besiegt wurde, bekommt keinen Abzug.
     */
    private double einmalVerloren(Schild s, double chance, boolean abziehen) {
        if (s.sicher() && !s.autoListe()) return chance;   // Autors eigenes "sicher" gilt
        int[] b = gesamtBilanz(s);
        if (b[0] != 0 || b[1] - b[0] != 1) return chance;
        double neu = Math.max(0, Math.min(EINMAL_VERLOREN_HOECHSTENS, chance - (abziehen ? EINMAL_VERLOREN_ABZUG : 0)));
        if (neu < chance && deckelGemeldet.add(namensSchluessel(s.name()) + "#1")) {
            Log.info("Arena", Sprache.t(s.wer() + " beat the bot once before (no win yet) -- win chance "
                            + Math.round(chance * 100) + " -> " + Math.round(neu * 100) + " %.",
                    s.wer() + " hat den Bot schon einmal besiegt (noch kein Sieg gegen ihn) -- Siegchance "
                            + Math.round(chance * 100) + " -> " + Math.round(neu * 100) + " %."));
        }
        return neu;
    }

    /** Einmal je Gegner gemeldet (OP-Sperre "name", OP-Abzug "name~"). */
    private final java.util.Set<String> opGemeldet = new java.util.HashSet<>();

    /**
     * Angstgegner (Idee A01, 22.10.): gegen ihn schon verloren und nicht oefter gewonnen als verloren -- ueber alle
     * Schreibweisen. Autors eigenes "sicher" gilt weiter (seine Entscheidung).
     */
    private boolean angstgegner(Schild s) {
        if (s.sicher() && !s.autoListe()) return false;
        int[] b = gesamtBilanz(s);
        int niederlagen = b[1] - b[0];
        return niederlagen >= 2 && b[0] <= niederlagen;   // Autor 29.10.: erst ab zwei Niederlagen
    }

    /**
     * Idee A01 (22.10.), seit Autor 29.10.: gegen einen Angstgegner (ab zwei Niederlagen) hoechstens 60 %. Auch kein Boden (Platz 10, aufgehoben, "hat gegen dich verloren") hebt ihn wieder darueber (A17).
     */
    private double deckeln(Schild s, double chance) {
        return deckeln(s, chance, true);
    }

    /** @param abziehen false = "einmal verloren" nur als Obergrenze (die 15 Punkte sind schon abgezogen) */
    private double deckeln(Schild s, double chance, boolean abziehen) {
        chance = einmalVerloren(s, chance, abziehen);
        if (chance <= ANGSTGEGNER_DECKEL || !angstgegner(s)) return chance;
        int[] b = gesamtBilanz(s);
        double neu = Math.min(ANGSTGEGNER_DECKEL, chance);   // Autor 29.10.: hoechstens 60 %
        if (deckelGemeldet.add(namensSchluessel(s.name()))) {
            Log.info("Arena", Sprache.t(s.wer() + " has beaten the bot before (" + b[0] + " wins in " + b[1] + " fights) -- win chance "
                            + Math.round(chance * 100) + " -> " + Math.round(neu * 100) + " %, no 225 against him.",
                    s.wer() + " hat den Bot schon besiegt (" + b[0] + " Siege in " + b[1] + " Kaempfen) -- Siegchance "
                            + Math.round(chance * 100) + " -> " + Math.round(neu * 100) + " %, kein 225er gegen ihn."));
        }
        return neu;
    }

    private double siegchanceOhneDeckel(Schild s) {
        double chance = ideenAnpassen(s, siegchanceRoh(s));
        // Ganz unbekannt (Team nie geprueft UND Kraft nicht gelesen): hoechstens 60 % -- der Ersatzwert sagt
        // nichts ueber DIESEN Gegner (08.10.: Сибиряк auf Platz 1 bekam so 85 % und wurde angegriffen).
        // Ein bekannter Gegner mit guter Chance geht dadurch immer vor.
        if (bekannteStaerke(s) <= 0 && setUndArtefaktPunkte(s.name(), s.clan()) == null && !s.sicher() && !s.botClan()) {
            chance = Math.min(chance, 0.60);
        }
        // Beide Regeln gelten nur fuer Gegner, gegen die es schwer aussieht (oder "ungern"/"niemals").
        if (!(chance < 0.60 || s.ungern() || s.gesperrt())) return chance;
        String k = namensSchluessel(s.name());
        int referenz = referenzPunkte();
        // +10 %: er hat in diesem Turnier gegen einen LEICHTEN verloren (Bot oder 10+ unter der Messlatte).
        for (String sieger : verlorenGegen.getOrDefault(k, java.util.Set.of())) {
            Integer p = punkteJetzt.get(sieger);
            boolean leicht = ohneClanGesehen.contains(sieger)
                    || (p != null && referenz != Integer.MIN_VALUE && p - referenz <= -10);
            if (!leicht) continue;
            if (bonusGemeldet.add(k)) {
                Log.info("Arena", Sprache.t(s.wer() + " lost to an easy opponent in this tournament (" + sieger
                        + ") -- win chance +10 % (" + Math.round(chance * 100) + " -> "
                        + Math.round(Math.min(0.95, chance + 0.10) * 100) + " %).",
                        s.wer() + " hat in diesem Turnier gegen einen leichten Gegner verloren (" + sieger
                        + ") -- Siegchance +10 % (" + Math.round(chance * 100) + " -> "
                        + Math.round(Math.min(0.95, chance + 0.10) * 100) + " %)."));
            }
            return Math.min(0.95, chance + 0.10);
        }
        // +3 %: er hat als Angreifer gegen einen MITTLEREN/SCHWACHEN nur 130 Punkte oder weniger geholt
        // (langer Kampf) -- Autor, 08.10.
        for (String gegner : muehsamGegen.getOrDefault(k, java.util.Set.of())) {
            Integer p = punkteJetzt.get(gegner);
            boolean nichtStark = ohneClanGesehen.contains(gegner)
                    || (p != null && referenz != Integer.MIN_VALUE && p - referenz <= 10);
            if (!nichtStark) continue;
            if (bonusGemeldet.add(k + "~")) {
                Log.info("Arena", Sprache.t(s.wer() + " only barely beat " + gegner + " (medium/weak) (<= 130 points)"
                        + " -- win chance +3 % (" + Math.round(chance * 100) + " -> " + Math.round(Math.min(0.95, chance + 0.03) * 100) + " %).",
                        s.wer() + " hat gegen " + gegner + " (mittel/schwach) nur muehsam gewonnen (<= 130 Punkte)"
                        + " -- Siegchance +3 % (" + Math.round(chance * 100) + " -> " + Math.round(Math.min(0.95, chance + 0.03) * 100) + " %)."));
            }
            return Math.min(0.95, chance + 0.03);
        }
        return chance;
    }

    /** Reicht die Zeit dieser Runde noch fuer eine volle Pruefung (und danach den Angriff)? */
    private boolean zeitFuerPruefung() {
        long rest = restSekunden();
        if (rest < 0) {
            return System.currentTimeMillis() - rundeSeit
                    < RUNDE_SPAETESTENS_MS - (pruefungBrauchtS() + 8) * 1000;
        }
        return rest >= reserveS() + pruefungBrauchtS();
    }

    /**
     * Hatte die Pruefung Luecken? Weniger als 6 Einheiten gesehen oder mindestens zwei Sets nicht
     * erkannt -- dann lohnt ab Stage 3 ein zweiter, genauerer Blick (Autor, 08.10.).
     */
    private boolean lueckenhaft(String schluessel) {
        ArenaPruefer.Ergebnis e = pruefungen.get(schluessel);
        if (e == null) return pruefVersucht.contains(schluessel);   // versucht, aber nichts herausgekommen
        if (e.einheiten() < 6) return true;
        int fragezeichen = 0;
        for (String s : e.sets().split(",")) if (s.strip().equals("?")) fragezeichen++;
        return fragezeichen >= 2;
    }

    private void pruefungVerbuchen() {
        if (pruefer.warSchnellblick()) {
            schnellblickVerbuchen();
            return;
        }
        // Autor 22.10.: das Aussehen der eben geprueften Einheiten ablegen (fuer den Schnell-Blick der naechsten Runden).
        List<ArenaAussehen.Einheit> aussehen = pruefer.aussehenAbholen();
        List<java.awt.image.BufferedImage> held = pruefer.heldBilderAbholen();
        String heldText = pruefer.heldTextJetzt();
        Optional<ArenaPruefer.Ergebnis> erg = pruefer.abholen();
        // Pruefer 29.10.: eine abgebrochene Pruefung (G4nawa: 4 von 6, Fenster ging nicht zu) legt kein Aussehen ab -- sonst
        // galt Einheit 5 bei jedem Schnell-Blick als "anders" und das Team wurde jede Runde komplett neu angesehen.
        boolean vollstaendig = pruefer.letzteVollstaendig();
        if (!vollstaendig) erg.ifPresent(e -> pruefAbbrueche.merge(namensSchluessel(e.name()), 1, Integer::sum));
        erg.ifPresent(e -> {
            if (!e.eigenes() && !e.nurExtras() && e.einheiten() > 0 && !aussehen.isEmpty()) {
                if (vollstaendig) {
                    ArenaAussehen.speichern(namensSchluessel(e.name()), aussehen, held, heldText);
                    unvollstaendigGeprueft.remove(namensSchluessel(e.name()));
                } else {
                    unvollstaendigGeprueft.add(namensSchluessel(e.name()));
                    Log.info("Arena", Sprache.t(e.name() + ": check incomplete -- appearance and unit count are not saved, "
                                    + "no quick look for this opponent in this tournament.",
                            e.name() + ": Pruefung unvollstaendig -- Aussehen und Einheitenzahl werden nicht gemerkt, "
                                    + "kein Schnell-Blick mehr fuer diesen Gegner in diesem Turnier."));
                }
            }
            // Autor 24.10.: auch DEIN Team ablegen -- fuer den kurzen Blick in der naechsten Arena (statt vollem Scan).
            if (e.eigenes() && vollstaendig && !e.nurExtras() && e.einheiten() >= eigeneMindestEinheiten() && !aussehen.isEmpty()) {
                ArenaAussehen.speichern(EIGEN_SCHLUESSEL, aussehen, held, heldText);
                String namen = pruefer.einheitenNamenText();
                eigeneEinheitenZeilen = einheitenZeilen(aussehen, namen);
                Log.info("Arena", Sprache.t("Own team saved: " + aussehen.size() + " units" + (namen.isBlank() ? "" : " (" + namen.replace("|", ", ") + ")")
                                + " -- next time a quick look is enough.",
                        "Eigenes Team gemerkt: " + aussehen.size() + " Einheiten" + (namen.isBlank() ? "" : " (" + namen.replace("|", ", ") + ")")
                                + " -- beim naechsten Mal reicht ein kurzer Blick."));
            }
        });
        ArenaAussehen.freigebenAlle(aussehen);
        erg.map(e -> vollstaendig ? e : ohneEinheitenzahl(e)).ifPresent(this::pruefungVerbuchenMit);
    }

    /** Gegner, deren volle Pruefung in diesem Turnier abbrach (Pruefer 29.10.) -- fuer sie kein Schnell-Blick mehr. */
    private final java.util.Set<String> unvollstaendigGeprueft = new java.util.HashSet<>();

    /**
     * Pruefer 29.10.: eine abgebrochene Pruefung sagt nichts ueber die Zahl der Einheiten. Mit "4" rechnete teamPunkte()
     * +12 je "fehlender" Einheit -- jetzt -1 (unbekannt): keine Strafe, der Notizblock behaelt die alte Zahl, und
     * lueckenhaft() sorgt ab Stage 3 fuer einen zweiten Versuch. Eigenes Team: -1 heisst "Messlatte bleibt".
     */
    static ArenaPruefer.Ergebnis ohneEinheitenzahl(ArenaPruefer.Ergebnis e) {
        if (e.nurExtras() || e.einheiten() <= 0) return e;   // keine Einheit gesehen: bleibt verworfen wie bisher
        return new ArenaPruefer.Ergebnis(e.name(), e.clan(), -1, e.setPunkte(), e.sets(), e.guteArtefakte(),
                e.bauarbeiter(), e.gelbeSteine(), e.roteSteine(), e.schirmAnzahl(), e.guteSchirme(), e.guteStigmas(),
                e.gutePals(), false, e.eigenes());
    }

    /**
     * Schnell-Blick fertig (Autor 22.10.): gleich -> die bekannten Werte gelten, in dieser Stage nicht noch einmal
     * ansehen; anders -> in dieser Stage komplett neu ansehen (auch wenn er in diesem Turnier schon angesehen wurde).
     */
    private void schnellblickVerbuchen() {
        if (pruefer.warEigenesTeam()) {
            // Autor 24.10.: kurzer Blick auf DEIN Team -- gleich: kein voller Scan; anders: jetzt voll scannen.
            Boolean eigenAnders = pruefer.aussehenErgebnis();
            if (eigenAnders == null) {
                Log.info("Arena", Sprache.t("Own team: quick look gave no result -- the benchmark stays (" + MeinTeam.punkte() + ").",
                        "Eigenes Team: kurzer Blick ohne Ergebnis -- die Messlatte bleibt (" + MeinTeam.punkte() + ")."));
            } else if (!eigenAnders) {
                Log.info("Arena", Sprache.t("Own team: quick look -- all units as at the last scan, no full scan, the benchmark stays ("
                                + MeinTeam.punkte() + ").",
                        "Eigenes Team: kurz geprueft -- alle Einheiten wie beim letzten Scan, kein voller Scan, die Messlatte bleibt ("
                                + MeinTeam.punkte() + ")."));
                eigeneReiheMerken(null);   // Reihe, Portraets und Kraft von jetzt gelten als Vergleich fuer die naechste Arena
            } else {
                Log.info("Arena", Sprache.t("Own team: quick look found a change -- full scan now.",
                        "Eigenes Team: beim kurzen Blick hat sich etwas geaendert -- jetzt voller Scan."));
                eigenesTeamVoll = true;
                eigenesTeamGeprueft = false;
            }
            return;
        }
        Boolean anders = pruefer.aussehenErgebnis();
        String k = schnellblickFuer;
        schnellblickFuer = null;
        boolean vorAngriff = vorAngriffBlick;
        vorAngriffBlick = false;
        if (anders == null || k == null) return;
        if (anders) {
            if (vorAngriff) {
                warnung(Sprache.t("Before the attack: the team looks different from the saved one -- no time left for a "
                                + "full check, attacking anyway. The fight result goes into the notebook.",
                        "Vor dem Angriff: das Team sieht anders aus als gespeichert -- keine Zeit mehr fuer eine volle "
                                + "Pruefung, es wird trotzdem angegriffen. Das Kampfergebnis kommt in den Notizblock."));
            }
            neuAnsehen.add(k);
        } else {
            geprueftInRunde.add(k);
            pruefVersucht.add(k);
        }
    }

    /** Wessen Aussehen gerade verglichen wird (Schluessel), null = keiner. */
    private String schnellblickFuer;
    /** Schnell-Blick in DIESER Stage schon gemacht (je Stage neu). */
    private final java.util.Set<String> aussehenInRunde = new java.util.HashSet<>();
    /** Schnell-Blick fand ein anderes Team: in dieser Stage komplett neu ansehen. */
    private final java.util.Set<String> neuAnsehen = new java.util.HashSet<>();
    /** Gegner, die auf "sicher" stehen (Liste oder automatisch) -- sie werden nicht neu angesehen (Autor 22.10.). */
    private final java.util.Set<String> sicherNamen = new java.util.HashSet<>();

    /** Laeuft gerade der Schnell-Blick direkt vor dem Angriff (dann darf er bis 8 s vor Schluss dauern)? */
    private boolean vorAngriffBlick;
    /** Ab so viel Restzeit wird vor dem Angriff noch kurz geschaut; der Blick bricht bei VOR_ANGRIFF_BIS_S ab. */
    private static final long VOR_ANGRIFF_AB_S = 14, VOR_ANGRIFF_BIS_S = 8;

    /**
     * Autor 22.10.: "Vor jedem Angriff soll der Bot einmal kurz, obwohl er einen Spieler schon kennt, das Team anschauen
     * und vergleichen, ob es das gleiche Team ist -- anhand der Skins, Power, Leben, Name, Talent und Guardian jeder
     * Einheit." Nur wenn das Ziel in dieser Stage weder kurz noch ganz angesehen wurde und sein Aussehen gemerkt ist.
     */
    private boolean vorAngriffBlickNoetig(String schluessel) {
        if (ArenaEinfach.aktiv()) return false;   // TS8: kein Schnell-Blick
        if (schluessel.isEmpty() || aussehenInRunde.contains(schluessel) || geprueftInRunde.contains(schluessel)) return false;
        if (!ArenaAussehen.bekannt(schluessel)) return false;
        long rest = restSekunden();
        return rest >= VOR_ANGRIFF_AB_S;
    }

    /** So viele Sekunden braucht ein Schnell-Blick hoechstens (Einheiten durchklicken, ohne Sets). */
    private static final int SCHNELLBLICK_BRAUCHT_S = 8;

    /**
     * Schnell-Blick jetzt? Nur bei Gegnern mit abgelegtem Aussehen, nicht "sicher", in dieser Stage noch nicht
     * angesehen -- und wenn die Zeit reicht.
     */
    private boolean brauchtSchnellblick(String schluessel, String name, String clan, boolean ohneClan) {
        if (ArenaEinfach.aktiv()) return false;   // TS8: kein Schnell-Blick
        if (schluessel.isEmpty() || ohneClan || sicherNamen.contains(schluessel)) return false;
        if (gesperrtNichtAnsehen(name, clan, schluessel)) return false;
        // Nur wer im Notizblock gepruefte Werte hat -- sonst haette "gleich" nichts, was weiter gelten koennte
        // (Autor 22.10.: Notizblock-Werte zurueckgesetzt, alle neu ansehen).
        if (buch.nachschlagen(name, clan).map(n -> n.setPunkte() < 0).orElse(true)) return false;
        if (schonAngegriffen.contains(schluessel) || aussehenInRunde.contains(schluessel) || geprueftInRunde.contains(schluessel)) return false;
        if (unvollstaendigGeprueft.contains(schluessel)) return false;   // Pruefer 29.10.: siehe pruefungVerbuchen()
        if (!ArenaAussehen.bekannt(schluessel)) return false;
        long rest = restSekunden();
        if (rest < 0) return System.currentTimeMillis() - rundeSeit < RUNDE_SPAETESTENS_MS - (SCHNELLBLICK_BRAUCHT_S + 8) * 1000;
        return rest >= reserveS() + SCHNELLBLICK_BRAUCHT_S;
    }

    private void pruefungVerbuchenMit(ArenaPruefer.Ergebnis e) {
        // Idee 18 (B14): Dauer einer fertigen vollen Pruefung (mindestens 5 Einheiten gelesen) in den Schnitt.
        if (pruefungBegonnenUm > 0 && !e.eigenes() && !e.nurExtras() && e.einheiten() >= 5) {
            long s = (System.currentTimeMillis() - pruefungBegonnenUm) / 1000;
            if (s >= 3 && s <= 60) {
                pruefungSchnittS = (pruefungSchnittS * 3 + s) / 4;
                Log.debug("Arena", "Pruefung " + e.name() + ": " + s + " s (Schnitt " + Math.round(pruefungSchnittS)
                        + " s, eingeplant werden " + pruefungBrauchtS() + " s).");
            }
        }
        pruefungBegonnenUm = 0;
        {
            if (e.eigenes()) {
                // Autors Team: nicht in den Notizblock, sondern als Messlatte (arena-ich.txt, +15).
                if (e.einheiten() < 0) {   // Pruefer 29.10.: abgebrochen (ohneEinheitenzahl)
                    Log.info("Arena", Sprache.t("Own team: check incomplete -- the benchmark stays as it is.",
                            "Eigenes Team: Pruefung unvollstaendig -- die Messlatte bleibt, wie sie ist."));
                    return;
                }
                if (e.einheiten() <= 0) {
                    Log.info("Arena", Sprache.t("Own team: no unit looked at -- the benchmark stays as it is.",
                            "Eigenes Team: keine Einheit angesehen -- die Messlatte bleibt, wie sie ist."));
                    return;
                }
                // Autor 24.10.: Scan mit 1 Einheit (Fenster kam nicht zurueck) wurde als Messlatte 8 statt 86 gespeichert.
                if (e.einheiten() < eigeneMindestEinheiten()) {
                    Log.warn("Arena", Sprache.t("Own team: only " + e.einheiten() + " units looked at -- too few, the benchmark stays ("
                                    + MeinTeam.punkte() + ").",
                            "Eigenes Team: nur " + e.einheiten() + " Einheiten angesehen -- zu wenig, die Messlatte bleibt ("
                                    + MeinTeam.punkte() + ")."));
                    return;
                }
                // Autor 22.10.: Gegner mit weniger Einheiten als du bekommen +12 je Einheit -- dafuer deine Zahl merken.
                Gegnerbuch.setMeineEinheiten(e.einheiten());
                int extrasPunkte = extras(e, null).punkte();
                // Idee 7 (22.10.): Schirmherrschaften im Vergleichsfenster nicht gelesen -> die aus dem letzten Team-Scan.
                int scanSchirm = e.schirmAnzahl() < 0 ? MeinTeam.schirmPunkteAusScan() : -1;
                if (scanSchirm >= 0) extrasPunkte += scanSchirm - Gegnerbuch.schirmPunkte(3, 0, 0);
                int team = einheitenPunkte(e.setPunkte(), e.guteArtefakte(), e.bauarbeiter(), e.einheiten())
                        + Gegnerbuch.steinPunkte(e.gelbeSteine(), e.roteSteine())
                        + extrasPunkte;
                MeinTeam.speichern(team, Sprache.t("Sets " + e.setPunkte() + " [" + e.sets() + "], good artifacts "
                        + e.guteArtefakte() + ", stones " + e.gelbeSteine() + " yellow / " + e.roteSteine() + " red"
                        + ", patronages " + e.schirmAnzahl() + " (" + e.guteSchirme() + " good, "
                        + e.guteStigmas() + " stigma), good pals " + e.gutePals() + ", units " + e.einheiten(),
                        "Sets " + e.setPunkte() + " [" + e.sets() + "], gute Artefakte "
                        + e.guteArtefakte() + ", Steine " + e.gelbeSteine() + " gelb / " + e.roteSteine() + " rot"
                        + ", Schirmherrschaften " + e.schirmAnzahl() + " (" + e.guteSchirme() + " gute, "
                        + e.guteStigmas() + " Stigma), gute Pals " + e.gutePals() + ", Einheiten " + e.einheiten()),
                        e.sets() != null && e.sets().contains("Held:"));
                eigenesTeamMerken();
                return;
            }
            if (e.nurExtras()) {   // nur Schirmherrschaften/Pals nachgeholt (01.10.)
                buch.merkeExtras(e.name(), e.clan(), e.schirmAnzahl(), e.guteSchirme(), e.guteStigmas(), e.gutePals());
                listeSchreiben();
                return;
            }
            if (e.einheiten() == 0) return;   // -1 = abgebrochen, Sets gelten (ohneEinheitenzahl)
            pruefungen.put(namensSchluessel(e.name()), e);
            schichtStandVeraltet = true;   // Autor 01.11.: Team angesehen -- Seitenfenster rechnet sofort neu (schichtImWarten)
            String namenDerEinheiten = pruefer.einheitenNamenText();
            if (!namenDerEinheiten.isBlank()) {
                einheitenVon.put(namensSchluessel(e.name()), namenDerEinheiten);
                Log.info("Arena", Sprache.t("Unit names of " + e.name() + ": " + namenDerEinheiten.replace("|", ", "),
                        "Einheiten-Namen von " + e.name() + ": " + namenDerEinheiten.replace("|", ", ")));
            }
            buch.merkePruefung(e.name(), e.clan(), e.setPunkte(), e.sets(), e.guteArtefakte(),
                    e.einheiten(), e.bauarbeiter(), e.gelbeSteine(), e.roteSteine(),
                    e.schirmAnzahl(), e.guteSchirme(), e.guteStigmas(), e.gutePals());
            // Autor 25.10. (TR5): OP-Items. Code-Review 26.10.: ein unvollstaendiger Scan loescht ein frueher gesehenes nicht.
            if (pruefer.opItems() > 0 || pruefer.letzteVollstaendig()) ArenaItems.opMerken(namensSchluessel(e.name()), pruefer.opItems());
            listeSchreiben();
        }
    }

    /**
     * Frisch geprueft, aber Schirmherrschaften oder Pals fehlen noch (die gibt es erst seit 01.10.)?
     * Dann nur die nachholen -- zwei kleine Fenster statt 20 s Einheiten.
     */
    private boolean brauchtExtras(String name, String clan, String schluessel, boolean ohneClan, boolean pfote) {
        if (ArenaEinfach.aktiv()) return false;   // TS8: keine Schirmherren/Pals
        if (schluessel.isEmpty() || ohneClan || pruefVersucht.contains(schluessel)) return false;
        var notiz = buch.nachschlagen(name, clan);
        if (notiz.isEmpty() || !notiz.get().geprueftSeit(PRUEFUNG_GILT_TAGE)) return false;
        if (!schirmFehlt(notiz.get()) && !(palsFehlt(notiz.get()) && pfote)) return false;
        long rest = restSekunden();
        if (rest < 0) {
            return System.currentTimeMillis() - rundeSeit
                    < RUNDE_SPAETESTENS_MS - (EXTRAS_BRAUCHT_S + 8) * 1000;
        }
        return rest >= reserveS() + EXTRAS_BRAUCHT_S;
    }

    private static boolean schirmFehlt(Gegnerbuch.Notiz n) {
        return (ArenaExtras.schirm() || ArenaExtras.stigma()) && !n.schirmBekannt();
    }

    private static boolean palsFehlt(Gegnerbuch.Notiz n) {
        return ArenaExtras.pals() && !n.palsBekannt();
    }

    /**
     * Merkt sich den Rundenwechsel -- egal, auf welchem Bildschirm.
     *
     * Der Wechsel von "Next stage in:" zurueck zu "Select the next
     * opponent" wurde nur in pyramide() ausgewertet. Bleibt der Bot ueber
     * das Rundenende hinweg im Vergleichsfenster -- beim Erkunden oder
     * beim Durchblaettern der Sperrliste --, kommt pyramide() nie dran, und
     * der Zaehler bleibt stehen. Fuer den Rest des Turniers gaelte dann
     * weiter "Runde 1": alle Gegner pauschal 150 Punkte statt 225 fuer die
     * ueber Autor, und die Erkundung finge in jeder Runde von vorn an.
     *
     * Beide Ueberschriften stehen oben rechts und sind auch dann zu sehen,
     * wenn ein Fenster davorliegt. Deshalb wird hier bei JEDEM Bild
     * nachgesehen, nicht nur in der Pyramide.
     */
    private void rundenwechselMerken(Mat screen, BotContext ctx) {
        // Autor 01.11.: das Banner "Tournament phase N has begun" liegt ~1 s ueber den oberen Schildern -- solange darf nichts gelesen werden.
        if (ArenaVerdeckt.banner(ctx.frame())) bannerGesehenUm = System.currentTimeMillis();
        if (ctx.visible(screen, T_NEXT_STAGE)) {
            zwischenDenRunden = true;
            return;
        }
        if (zwischenDenRunden && ctx.visible(screen, T_SELECT)) {
            zwischenDenRunden = false;
            if (rundeZuletzt < 5) rundeZuletzt++;
            neueRunde();
            // Autor 01.11. (Pruefung 3.137): die Pyramide ordnet sich mit dem Rundenstart um, der Platz von eben gilt nicht mehr (Runde 3 stand "1" statt 3, Runde 4
            // "3" statt 4), und waehrend das Banner "Tournament phase N has begun" liegt, ist der eigene Schild nicht zu lesen. Der Platz gilt als unbekannt, bis die
            // Schilder nach dem Banner gelesen sind (schilderLesen meldet ihn dann einmal).
            aktuellerPlatz = 0;
            Log.info("Arena", Sprache.t("New round: " + rundeZuletzt + " of 5.", "Neue Runde: " + rundeZuletzt + " von 5."));
            endrundeGemeldet = false;
            fotoSerie("runde");
        }
    }

    /**
     * Raus aus einem haengenden Turnier.
     *
     * Der Reihe nach werden die Wege probiert, die es gibt: der gruene
     * "Home" oben rechts in der Pyramide, der "Home" auf dem
     * Ergebnisbildschirm, das rote X des Turnierfensters. Nach drei
     * Versuchen ohne Wirkung gibt der Modus auf -- dann ist etwas kaputt,
     * das ein Mensch ansehen muss.
     */
    private Outcome notausstieg(Mat screen, BotContext ctx, RunTracker runs) {
        notausstiege++;
        Log.alarm("Arena", Sprache.t("No progress for " + runs.idleSeconds() + " seconds. "
                + "Attempt " + notausstiege + " of 3 to leave the tournament.",
                "Seit " + runs.idleSeconds() + " Sekunden kein Fortschritt. "
                + "Versuch " + notausstiege + " von 3, aus dem Turnier auszusteigen."));

        // Damit der naechste Versuch nicht sofort wieder anschlaegt.
        runs.progress(Sprache.t("Emergency exit " + notausstiege, "Notausstieg " + notausstiege));

        if (notausstiege > 3) {
            Log.alarm("Arena", Sprache.t("Leaving did not help three times. The mode stops. "
                    + "Please look at the log and a screenshot.",
                    "Der Ausstieg hat dreimal nicht geholfen. Der Modus haelt an. "
                    + "Bitte das Protokoll und ein Bildschirmfoto ansehen."));
            return Outcome.FATAL;
        }

        imTurnier = false;
        imAbschluss = false;
        neuesTurnier();

        if (ctx.clickIfVisible(screen, T_HOME_RESULT, Sprache.t("Emergency exit: Home", "Notausstieg: Home"))
         || ctx.clickIfVisible(screen, T_HOME, Sprache.t("Emergency exit: Home", "Notausstieg: Home"))
         || ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Emergency exit: close window", "Notausstieg: Fenster zu"))) {
            return Outcome.ACTED;
        }
        return Outcome.WAITING;
    }

    // ==================================================================
    //  1. Hinweg
    // ==================================================================

    /** Burgen faellig, und nach dieser Stage kommt noch eine (nach Stage 5 endet das Turnier -- dann nicht weg)? */
    private boolean burgenWunsch(int runde) {
        return runde >= 1 && runde < 5 && de.hustlebot.core.Burgenplan.faellig() && burgenVorlagenDa();
    }

    private boolean burgenVorlagenDa() {
        if (fotoKontext == null) return false;
        for (String t : burgen.requiredTemplates()) if (!fotoKontext.matcher().has(t)) return false;
        return fotoKontext.matcher().has(T_CLOSE_X);
    }

    /**
     * So lange darf ein Ausflug in der Wartezeit hoechstens ueber das erwartete Rundenende gehen (Nutzer 25.11.: "40 s sind
     * ein bisschen zu wenig -- eine Minute"; vorher 40 s). Der eigene Angriff der naechsten Runde kommt ohnehin spaet
     * (spaetestensS vor Rundenende), eine Minute nach Rundenbeginn bleibt also Zeit.
     */
    static final long AUSFLUG_UEBERZEIT_MS = 60_000L;

    /** Nach dem eigenen Angriff ("Next stage in"): reicht die Zeit, Turnierfenster zu und zu den Burgen. */
    private Outcome burgenAusflugStarten(Mat screen, BotContext ctx) {
        if (!rundeGespielt || rundenendeErwartet <= 0 || !burgenWunsch(rundeZuletzt)) return null;
        // Code-Review 26.10.: erst, wenn das Kampfergebnis gelesen ist -- sonst fehlten Sieg/Niederlage in Notizblock,
        // Sperrliste und Lernen (rundeGespielt steht schon beim Klick auf Quick Attack).
        if (imKampfGegen != null || ergebnisSeit != 0 || System.currentTimeMillis() - angriffGeklicktUm < 15_000) return null;
        long rest = rundenendeErwartet - System.currentTimeMillis();
        if (rest < BURGEN_MIN_REST_MS) return null;
        if (!ctx.clickIfVisibleIfKnown(screen, T_CLOSE_X, Sprache.t("Close the tournament window -- castles in the waiting time ("
                        + rest / 1000 + " s until the next stage)",
                "Turnierfenster zu -- Burgen in der Wartezeit (" + rest / 1000 + " s bis zur naechsten Stage)"))) {
            return null;
        }
        burgen.reset();
        burgen.fristSetzen(rundenendeErwartet);
        burgenAusflug = true;
        ctx.sleepUntilChanged(2000);
        return Outcome.ACTED;
    }

    /** Ein Schritt des Ausflugs; null = vorbei, die Arena macht mit diesem Bild weiter. */
    private Outcome burgenSchritt(Mat screen, BotContext ctx, RunTracker runs) {
        Outcome o = burgen.step(screen, ctx, runs);
        // Sicherung: AUSFLUG_UEBERZEIT_MS ueber der Frist ist der Ausflug auf jeden Fall vorbei (die Arena findet von ueberall zurueck).
        boolean zuLang = System.currentTimeMillis() > rundenendeErwartet + AUSFLUG_UEBERZEIT_MS;
        if (o != Outcome.FINISHED && !zuLang) return o == Outcome.UNKNOWN ? Outcome.WAITING : o;
        burgenAusflug = false;
        burgenRueckwegSeit = System.currentTimeMillis();
        if (burgen.alleErledigt()) de.hustlebot.core.Burgenplan.angestossen();
        Log.info("Arena", Sprache.t("Castles done" + (burgen.alleErledigt() ? " (no enemies left)" : " for now") + " -- back to the tournament.",
                "Burgen " + (burgen.alleErledigt() ? "fertig (keine Gegner mehr)" : "fuer diese Wartezeit fertig") + " -- zurueck zum Turnier."));
        return Outcome.ACTED;
    }

    /**
     * Kathedrale in dieser Wartezeit? Schalter an, Stage 1-4 (nach Stage 5 endet das Turnier), Kathedrale auf diesem Thronsaal
     * vorhanden, nicht schon leer, nicht im Event-Kreislauf (der setzt beim Start der Kathedrale ein Event in Gang).
     */
    static boolean kathedraleErlaubt(boolean schalter, int runde, boolean leer, boolean thronsaalFrei, boolean eventKreislauf, boolean nurZuEnde) {
        return schalter && runde >= 1 && runde < LETZTE_RUNDE && !leer && thronsaalFrei && !eventKreislauf && !nurZuEnde;
    }

    /** Bis wann gespielt werden darf: Rundenende + Ueberzeit - Rueckweg. */
    static long kathedraleFrist(long rundenende) {
        return rundenende + AUSFLUG_UEBERZEIT_MS - KATHEDRALE_RUECKWEG_MS;
    }

    /** Nach dem eigenen Angriff ("Next stage in"): Turnierfenster zu, Kathedrale bis kurz vor Ende der Ueberzeit. */
    private Outcome kathedraleAusflugStarten(Mat screen, BotContext ctx) {
        if (!rundeGespielt || rundenendeErwartet <= 0) return null;
        if (!kathedraleErlaubt(KATHEDRALE_AUSFLUG.lesen(), rundeZuletzt, kathedraleLeer,
                !de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.KATHEDRALE),
                de.hustlebot.core.Eventautomatik.istAn(), nurZuEndeSpielen)) return null;
        // Wie beim Burgen-Ausflug: erst, wenn das Kampfergebnis gelesen ist.
        if (imKampfGegen != null || ergebnisSeit != 0 || System.currentTimeMillis() - angriffGeklicktUm < 15_000) return null;
        long rest = rundenendeErwartet - System.currentTimeMillis();
        if (rest < KATHEDRALE_MIN_REST_MS) return null;
        long bis = kathedraleFrist(rundenendeErwartet);
        if (!ctx.clickIfVisibleIfKnown(screen, T_CLOSE_X, Sprache.t("Close the tournament window -- cathedral in the waiting time ("
                        + (bis - System.currentTimeMillis()) / 1000 + " s)",
                "Turnierfenster zu -- Kathedrale in der Wartezeit (" + (bis - System.currentTimeMillis()) / 1000 + " s)"))) {
            return null;
        }
        // Jeder Ausflug beginnt frisch: die Kathedrale liest Etage und Lage selbst vom Bildschirm (Sockel), eine offene
        // Reise wird von der Etagenkarte aus weitergespielt.
        kathedrale.reset();
        kathedraleRuns = new RunTracker(1_000_000);
        kathedraleAusflug = true;
        kathedraleBis = bis;
        ctx.sleepUntilChanged(2000);
        return Outcome.ACTED;
    }

    /** Ein Schritt der Kathedrale; null = Ausflug vorbei, die Arena macht mit diesem Bild weiter (Rueckweg in hinweg). */
    private Outcome kathedraleSchritt(Mat screen, BotContext ctx, RunTracker runs) {
        boolean zeitUm = System.currentTimeMillis() >= kathedraleBis;
        Outcome o = zeitUm ? null : kathedrale.step(screen, ctx, kathedraleRuns);
        if (o == Outcome.PAUSE) return o;   // anderes Geraet: der Mensch ist dran
        if (o == Outcome.ACTED) runs.progress(Sprache.t("Cathedral in the arena waiting time", "Kathedrale in der Arena-Wartezeit"));
        if (o != null && o != Outcome.FINISHED && o != Outcome.FATAL) return o == Outcome.UNKNOWN ? Outcome.WAITING : o;
        kathedraleAusflug = false;
        kathedraleRunsGesamt += kathedraleRuns.completedRuns();
        burgenRueckwegSeit = System.currentTimeMillis();   // derselbe Rueckweg wie nach den Burgen
        if (o == Outcome.FINISHED || o == Outcome.FATAL) {
            kathedraleLeer = true;
            String grund = kathedrale.endeHinweis();
            Log.info("Arena", Sprache.t("Cathedral finished for this start" + (grund != null ? " (" + grund + ")" : "") + " -- no more cathedral in the waiting time.",
                    "Kathedrale fuer diesen Start fertig" + (grund != null ? " (" + grund + ")" : "") + " -- keine Kathedrale mehr in der Wartezeit."));
        } else {
            Log.info("Arena", Sprache.t("Cathedral paused -- back to the tournament (" + kathedraleRunsGesamt + " cathedral run(s) so far).",
                    "Kathedrale unterbrochen -- zurueck zum Turnier (bisher " + kathedraleRunsGesamt + " Kathedralen-Run(s))."));
        }
        return Outcome.ACTED;
    }

    private Outcome hinweg(Mat screen, BotContext ctx) {
        // Autor 25.10.: zurueck vom Burgen-Ausflug -- Burg -> Weltkarte -> Arena; das Turnier laeuft weiter (die Burg ist
        // hier KEIN Rauswurf). Hoechstens eine Minute, danach gilt wieder alles wie sonst.
        if (burgenRueckwegSeit > 0 && imTurnier) {
            if (System.currentTimeMillis() - burgenRueckwegSeit > 60_000) {
                burgenRueckwegSeit = 0;
            } else {
                if (System.currentTimeMillis() - inselGeklicktUm < 2000) return Outcome.WAITING;
                for (String node : ctx.matcher().serie(T_NODE)) {
                    if (ctx.clickIfVisible(screen, node, Sprache.t("Back to the arena (tournament running)", "Zurueck zur Arena (Turnier laeuft)"))) {
                        inselGeklicktUm = System.currentTimeMillis();
                        return Outcome.ACTED;
                    }
                }
                // Aus der Kathedrale (Nutzer 25.11.): Etagenkarte -> "Home" unten links (wie BotEngine.heimGehen), sonst der gruene
                // Home. Die Reise bleibt dabei offen ("End journey" ruehrt der Bot hier nicht an).
                if (System.currentTimeMillis() - karteGeklicktUm >= 3000 && ctx.visibleIfKnown(screen, "dc_floor_hint")) {
                    Weltkarte.heim(screen, ctx, Sprache.t("Home (out of the cathedral, back to the arena)", "Heim (aus der Kathedrale, zurueck zur Arena)"));
                    karteGeklicktUm = System.currentTimeMillis();
                    ctx.sleepUntilChanged(2000);
                    return Outcome.ACTED;
                }
                if (ctx.clickIfVisible(screen, T_HOME_BUTTON, Sprache.t("Home (back to the arena)", "Heim (zurueck zur Arena)"))) {
                    ctx.sleepUntilChanged(2000);
                    return Outcome.ACTED;
                }
                if (System.currentTimeMillis() - karteGeklicktUm >= 3000
                        && (ctx.clickIfVisible(screen, T_MAP_BUTTON, Sprache.t("To the world map (back to the arena)", "Zur Weltkarte (zurueck zur Arena)"))
                         || ctx.clickIfVisibleIfKnown(screen, T_MAP_ALT, Sprache.t("To the world map (back to the arena)", "Zur Weltkarte (zurueck zur Arena)")))) {
                    karteGeklicktUm = System.currentTimeMillis();
                    ctx.sleepUntilChanged(2000);
                    return Outcome.ACTED;
                }
                return Outcome.WAITING;
            }
        }
        // Nachspiel fertig (Pruefung 11.10.): nur noch heim in die Burg, nicht wieder zur Arena.
        if (nurZuEndeSpielen && nachspielHeimweg) {
            return nachspielHeimweg(screen, ctx);
        }
        // Einschub wartet und kein Turnier laeuft (11.10.): nicht wieder zur Arena, der Motor wechselt gleich.
        if (wechselErbeten && !imTurnier) {
            wechselBereit = true;
            return Outcome.WAITING;
        }
        // Aus dem Spiel geworfen (30.09., 00:29: nach 5 min Warteschlange "Are you still
        // here?", Update, Burg): Die Burg gibt es mitten im Turnier nicht -- das Turnier
        // ist weg, also von der Burg aus wieder in die Arena statt hier zu warten.
        if (imTurnier && (ctx.visible(screen, T_MAP_BUTTON) || ctx.visibleIfKnown(screen, T_MAP_ALT))) {
            warnung(Sprache.t("The game is in the castle although a tournament was running -- probably "
                    + "kicked out. Starting over towards the arena.",
                    "Das Spiel steht in der Burg, obwohl ein Turnier lief -- vermutlich "
                    + "rausgeworfen. Es geht von vorn in die Arena."));
            imTurnier = false;
            neuesTurnier();
        }
        // Alle Motive der Serie (02.10.: die Karte lag anders, das Schild halb unter "Home" --
        // ar_node passte nur zu 0,72; ar_node_2 ist nur das Wort "Arena").
        // 08.10. (Autor, Nahrungs-Arena): der zweite Klick auf die Insel kam 0,6 s nach dem ersten -- das
        // Turnierfenster war schon offen, und der Klick traf "Participate" (mit TICKETS), bevor auf
        // "For food" umgestellt war. Danach landete der Umschalt-Klick im Truppenbildschirm (Heldenfenster).
        // Jetzt nach einem Insel-Klick 2 s lang nicht noch einmal.
        if (System.currentTimeMillis() - inselGeklicktUm < 2000) return Outcome.WAITING;
        for (String node : ctx.matcher().serie(T_NODE)) {
            if (ctx.clickIfVisible(screen, node, Sprache.t("Arena on the world map", "Arena auf der Weltkarte"))) {
                inselGeklicktUm = System.currentTimeMillis();
                unbekannteBilder = 0;
                kartenZuege = 0;
                return Outcome.ACTED;
            }
        }

        // Mitten im Turnier wird NICHT zur Weltkarte geklickt.
        //
        // Frueher stand hier nur der Klick auf map_button. Wurde einmal ein
        // Bildschirm nicht erkannt -- etwa das Niederlagenband --, lief der
        // Bot bis hierher durch und verliess die laufende Arena. Ticket weg,
        // restliche Runden weg. Ein unbekanntes Bild im Turnier ist ein
        // Grund zum Warten, nicht zum Weglaufen.
        if (imTurnier) {
            unbekannteBilder++;
            // Nach dem letzten eigenen Angriff wartet das Spiel nur noch aufs Turnierende ("The
            // tournament ends in") -- das ist kein Grund zur Warnung.
            boolean nurNochEnde = rundeZuletzt >= 5 && rundeGespielt;
            // Idee 3: ein fremdes Bild im Turnier nach 10 s mit Foto und rotem X oben rechts wegtippen (nie "Home"),
            // hoechstens alle 4 s -- eine Runde dauert hoechstens 2 min (Hauptbot: 7 von 75 Runden ohne Angriff).
            long jetztU = System.currentTimeMillis();
            if (jetztU - letztesUnbekanntUm > 3000) unbekanntSeit = jetztU;
            letztesUnbekanntUm = jetztU;
            if (!nurNochEnde && jetztU - unbekanntSeit > 10_000 && jetztU - unbekanntXUm > 4000) {
                unbekanntXUm = jetztU;
                fotoSerie("unbekannt");
                if (!ctx.clickIfVisibleIfKnown(screen, T_CLOSE_X, Sprache.t("Close unknown window in the tournament (red X)",
                        "Fremdes Fenster im Turnier schliessen (rotes X)"))) {
                    int x = (int) Math.round(1120 * ctx.width() / 1159.0), y = (int) Math.round(35 * ctx.height() / 770.0);
                    Log.action("Klick", Sprache.t("Unknown window in the tournament: red X top right", "Fremdes Fenster im Turnier: rotes X oben rechts")
                            + "  [" + x + "," + y + "]");
                    ctx.input().click(x, y, 2);
                }
                warnung(Sprache.t("Unknown picture in the tournament for " + (jetztU - unbekanntSeit) / 1000
                        + " s -- tapped the red X so that no round is lost.",
                        "Seit " + (jetztU - unbekanntSeit) / 1000 + " s ein fremdes Bild im Turnier -- rotes X getippt, "
                        + "damit keine Runde verloren geht."));
                return Outcome.ACTED;
            }
            if (unbekannteBilder % 20 == 0 && !nurNochEnde) {
                warnung(Sprache.t("Unknown picture during the tournament (" + unbekannteBilder
                        + "). Waiting instead of clicking to the world map -- otherwise "
                        + "the ticket would be lost.",
                        "Unbekanntes Bild im laufenden Turnier (" + unbekannteBilder
                        + "). Es wird gewartet statt zur Weltkarte zu klicken -- sonst waere "
                        + "das Ticket verloren."));
            }
            return Outcome.UNKNOWN;
        }

        // Von der Burg auf die Weltkarte. NUR EINMAL, dann warten (30.09., 01:37): Der
        // Knopf war im naechsten Bild noch da, der zweite Klick kam an, als die Karte
        // schon offen war -- und traf dort "Home". Burg -> Karte -> Burg, 474 Mal.
        if (System.currentTimeMillis() - karteGeklicktUm < 3000) {
            return Outcome.WAITING;
        }
        if (ctx.clickIfVisible(screen, T_MAP_BUTTON, Sprache.t("To the world map", "Zur Weltkarte"))
         || ctx.clickIfVisibleIfKnown(screen, T_MAP_ALT, Sprache.t("To the world map", "Zur Weltkarte"))
         || ctx.clickIfVisible(screen, T_GO_TO_MAP, Sprache.t("To the world map", "Zur Weltkarte"))) {
            unbekannteBilder = 0;
            karteGeklicktUm = System.currentTimeMillis();
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }

        // Woanders im Spiel: erst einmal zurueck in die Burg.
        //
        // Frueher gab der Modus hier auf. Autor hat "!start arena" getippt,
        // waehrend das Spiel in der Kathedrale stand -- und bekam nach
        // dreissig Bildern "weder die Weltkarte noch das Turnierfenster".
        // Von dort kommt man aber problemlos zur Arena, man muss nur erst
        // heraus.
        //
        // Der gruene "Home" bringt aus fast jedem Bildschirm zurueck. Steht
        // ein Fenster im Weg, hilft eines der roten X. Beides sind Wege, die
        // nichts kaputtmachen: ein laufender Kathedralen-Durchlauf wird
        // dabei NICHT abgebrochen -- dafuer waere "End journey" noetig, und
        // den ruehrt der Bot hier nicht an.
        if (ctx.clickIfVisible(screen, T_HOME_BUTTON, Sprache.t("Back to the castle", "Zurueck in die Burg"))) {
            unbekannteBilder = 0;
            return Outcome.ACTED;
        }
        if (unbekannteBilder >= 4
                && (ctx.clickIfVisibleIfKnown(screen, T_X_VERLIES, Sprache.t("Close window", "Fenster schliessen"))
                 || ctx.clickIfVisibleIfKnown(screen, T_X_KATHEDRALE, Sprache.t("Close window", "Fenster schliessen"))
                 || ctx.clickIfVisibleIfKnown(screen, T_X_ALLGEMEIN, Sprache.t("Close window", "Fenster schliessen")))) {
            unbekannteBilder = 0;
            return Outcome.ACTED;
        }

        // Auf der Weltkarte (nach Leuchtturm/Kolosseum, Autor 26.09.), die
        // Arena-Insel aber nicht im Bild (oben haette sie sonst geklickt):
        // mit "Home" in die Burg, von dort der gewohnte Weg.
        if (unbekannteBilder >= 1 && Weltkarte.erkannt(screen, ctx)) {
            // 22.10. nachts: nach dem Verlies merkte sich das Spiel den linken Kartenausschnitt (Verlies, Leuchtturm) --
            // "Home" fuehrte in die Burg, "Map" wieder auf denselben Ausschnitt, ohne Arena: 2 Minuten hin und her.
            // Jetzt erst die Karte schieben, bis die Arena-Insel auftaucht; erst danach wie bisher ueber die Burg.
            if (kartenZuege < Weltkarte.ZUEGE) {
                Weltkarte.schieben(ctx, kartenZuege++, "Arena");
                unbekannteBilder = 0;
                return Outcome.ACTED;
            }
            kartenZuege = 0;
            Weltkarte.heim(screen, ctx, "Arena");
            unbekannteBilder = 0;
            return Outcome.ACTED;
        }
        unbekannteBilder++;
        if (Stoerfenster.notausgang(screen, ctx, "Arena", unbekannteBilder)) {
            return Outcome.ACTED;
        }
        if (unbekannteBilder >= 40) {
            Log.alarm("Arena", Sprache.t("For " + unbekannteBilder + " pictures the bot has not found the way "
                    + "to the arena. It looked for: the arena island (ar_node), the way to the world map "
                    + "(map_button, go_to_map), the way into the castle (home_button) and the red X. "
                    + "Please look at a screenshot -- probably a screen the bot does not know is in the way.",
                    "Seit " + unbekannteBilder + " Bildern findet der Bot den Weg "
                    + "zur Arena nicht. Gesucht wurden: die Arena-Insel (ar_node), der Weg "
                    + "zur Weltkarte (map_button, go_to_map), der Weg in die Burg "
                    + "(home_button) und die roten X. Bitte ein Bildschirmfoto ansehen -- "
                    + "vermutlich steht ein Bildschirm im Weg, den der Bot nicht kennt."));
            return Outcome.FATAL;
        }
        return Outcome.UNKNOWN;
    }

    // ==================================================================
    //  2. Anmelden
    // ==================================================================

    private Outcome anmelden(Mat screen, BotContext ctx, RunTracker runs) {
        unbekannteBilder = 0;
        BufferedImage bild = ctx.frame();

        // Die Zaehler stehen NUR hier. Wer sie jetzt nicht liest, liest sie
        // gar nicht.
        // Gerade erst auf "Participate" gedrueckt? Dann Finger weg.
        //
        // Ohne diese Sperre kostete ein Turnier zwei Tickets: nach dem Klick
        // braucht das Truppenfenster einen Moment, das Turnierfenster ist so
        // lange noch zu sehen -- und step() waehlt dann wieder anmelden(),
        // wo als Erstes wieder Participate gedrueckt wird.
        long seitParticipate = System.currentTimeMillis() - participateAt;
        if (participateAt > 0 && seitParticipate < PARTICIPATE_SPERRE_MS) {
            return Outcome.WAITING;
        }

        // Zurueck im Turnierfenster heisst: das letzte Turnier ist vorbei.
        imTurnier = false;
        imAbschluss = false;

        // Mit Aepfeln: Kam nach "Participate" gar kein Turnier zustande
        // (keine Warteschlange, keine Pyramide), reichen vermutlich die Aepfel
        // nicht. Zweimal -- dann Schluss. Nachgekauft wird NIE.
        if (kasse.mitNahrung() && participateAt > 0 && !turnierGesehen) {
            participateAt = 0;
            if (++fehlstarts >= 2) {
                Log.info("Arena", Sprache.t("Pressed \"Participate\" twice, but no tournament -- "
                        + "probably out of apples. Done for today after " + turniere + " tournaments.",
                        "Zweimal \"Participate\" gedrueckt, aber kein Turnier -- "
                        + "vermutlich keine Aepfel mehr. Feierabend nach " + turniere + " Turnieren."));
                ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tournament window", "Turnierfenster schliessen"));
                return Outcome.FINISHED;
            }
        }

        // Zahlen hoechstens alle drei Sekunden vorlesen. Jedes Ablesen ist
        // ein eigener PowerShell-Vorgang von rund einer Sekunde, und das
        // Turnierfenster steht mehrere Bilder lang.
        long jetzt = System.currentTimeMillis();
        boolean darfLesen = jetzt - zaehlerGelesenAt >= LESEPAUSE_MS;
        if (darfLesen) zaehlerGelesenAt = jetzt;

        if (darfLesen && !kasse.mitNahrung()) ctx.find(screen, T_TICKET_ICON).ifPresent(h -> {
            OptionalLong t = ArenaLeser.zahlRechtsVon(bild, h, (int) (ctx.width() * 0.10));
            // Pruefung 29.10.: "71" / "171" statt 1078 / 1177 gelesen (3 von 53) -- mit einer Ticket-Reserve haette das die
            // Turniere beendet. Ein Sprung um mehr als 5 zaehlt erst, wenn dieselbe Zahl zweimal hintereinander kommt.
            if (t.isPresent() && t.getAsLong() > 0 && ticketsZuletzt > 0 && Math.abs(t.getAsLong() - ticketsZuletzt) > 5
                    && t.getAsLong() != ticketKandidat) {
                Log.debug("Arena", "Tickets " + t.getAsLong() + " statt bisher " + ticketsZuletzt + " -- erst nach einer zweiten gleichen Lesung.");
                ticketKandidat = t.getAsLong();
                return;
            }
            ticketKandidat = -1;
            if (t.isPresent()) {
                ctx.laufstatistik().recordTickets(t.getAsLong());
                ticketsZuletzt = t.getAsLong();
                ticketsZuletztUm = System.currentTimeMillis();
                // Eine einzelne Null beendet nicht die Sitzung.
                //
                // Die Texterkennung verliest sich: ein Fenster im Aufbau,
                // ein Hinweisfeld darueber, und aus 47 wird 0. Frueher hat
                // der Bot daraufhin sofort FINISHED gemeldet und bei
                // "!start arena 20" nach dem ersten Turnier aufgehoert,
                // obwohl 46 Tickets dalagen. Jetzt muessen es zwei
                // Messungen hintereinander sein.
                nullMessungen = (t.getAsLong() <= 0) ? nullMessungen + 1 : 0;
                ticketsAlle = nullMessungen >= 2;
            }
        });
        // Ticket-Reserve erreicht (Idee 8): nicht mehr teilnehmen. Nur bei echtem Ticketstand
        // (> 0 gelesen) -- eine verlesene Null regelt oben die Zweifach-Messung.
        int reserve = ticketReserve();
        if (reserve > 0 && !kasse.mitNahrung() && ticketsZuletzt > 0 && ticketsZuletzt <= reserve
                && System.currentTimeMillis() - ticketsZuletztUm < 10_000) {
            Log.info("Arena", Sprache.t("Ticket reserve reached: " + ticketsZuletzt + " tickets left, reserve "
                    + reserve + " -- done for today after " + turniere + " tournaments.",
                    "Ticket-Reserve erreicht: " + ticketsZuletzt + " Tickets uebrig, Reserve "
                    + reserve + " -- Feierabend nach " + turniere + " Turnieren."));
            Log.hinweis("Arena", Sprache.t("Ticket reserve reached (" + ticketsZuletzt + " left) -- no more tournaments.",
                    "Ticket-Reserve erreicht (" + ticketsZuletzt + " uebrig) -- keine weiteren Turniere."));
            ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tournament window", "Turnierfenster schliessen"));
            return Outcome.FINISHED;
        }
        if (darfLesen) ctx.find(screen, T_BADGE_ICON).ifPresent(h -> {
            OptionalLong b = ArenaLeser.zahlRechtsVon(bild, h, (int) (ctx.width() * 0.14));
            b.ifPresent(v -> ctx.laufstatistik().recordBadges(v));
        });

        if (ticketsAlle && !kasse.mitNahrung()) {
            // Teil A: bei "erst Tickets, dann Nahrung" (und "alles") geht es
            // mit Nahrung weiter statt Feierabend.
            if (kasse.ticketsAlle()) {
                ticketsAlle = false;
                nullMessungen = 0;
                fehlstarts = 0;
                return Outcome.ACTED;
            }
            Log.info("Arena", Sprache.t("No tickets left (read zero twice). Done for today after "
                    + turniere + " tournaments.",
                    "Keine Tickets mehr (zweimal null gelesen). Feierabend nach "
                    + turniere + " Turnieren."));
            // Das Turnierfenster bleibt sonst offen stehen. Zumachen, damit
            // das Spiel wieder in der Burg steht.
            ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tournament window", "Turnierfenster schliessen"));
            return Outcome.FINISHED;
        }

        // Reiter "for food"/"for tickets", Preis, Limit und Vorrat (Teil A).
        // Reicht die Nahrung nicht sicher, wird Participate NICHT gedrueckt.
        // Nachspiel des Motors (11.10.): Nur das laufende Turnier zu Ende spielen -- steht hier
        // wieder "Participate", laeuft keins mehr. Fenster zu, fertig.
        if (nurZuEndeSpielen) {
            Log.info("Arena", Sprache.t("No tournament open any more -- closing the tournament window.",
                    "Kein Turnier mehr offen -- das Turnierfenster geht zu."));
            // Pruefung 11.10.: danach noch heim in die Burg (hinweg), erst dort ist das Nachspiel fertig.
            nachspielHeimweg = true;
            if (ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tournament window (follow-up done)", "Turnierfenster schliessen (Nachspiel fertig)"))) {
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            return Outcome.FINISHED;
        }
        // Einschub wartet (Autor, 11.10. 15:00: "Dann muss er erst die Arena beenden"): das Turnier
        // ist zu Ende -- kein neues anfangen, Turnierfenster zu, dann wechselt der Motor.
        if (wechselErbeten) {
            if (!wechselBereit) {
                wechselBereit = true;
                runs.progress(Sprache.t("Tournament over -- the queued mode takes its turn", "Turnier zu Ende -- der Einschub kommt dran"));
                if (ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tournament window (a mode is waiting)", "Turnierfenster schliessen (Einschub wartet)"))) {
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
            }
            return Outcome.WAITING;
        }
        // Die Kasse (Umschalten auf "For food", Preis lesen) nur, solange "Participate" zu sehen ist --
        // 08.10.: auf dem Truppenbildschirm passte "for food" noch, der Klick oeffnete das Heldenfenster.
        if (kasse.mitNahrung() && ctx.matcher().findOberteil(screen, T_PARTICIPATE, 0.55, Matcher.DEFAULT_THRESHOLD).isEmpty()) {
            return warteMit(ctx, screen, Sprache.t("Tournament window (Participate)", "Turnierfenster (Participate)"), T_PARTICIPATE, T_TICKETS_OFF);
        }
        Outcome bezahlt = kasse.vorTeilnahme(screen, ctx);
        if (bezahlt != null) return bezahlt;

        // Nur den oberen Teil des Knopfes vergleichen -- das Wort
        // "Participate". Darunter steht der Preis, und der ist beim Spielen
        // fuer Tickets ein anderer als beim Spielen fuer Essen. Die Vorlage
        // ist in der Essens-Fassung aufgenommen ("Apfel 90 000"), also haette
        // sie nie gepasst.
        var teilnahme = ctx.matcher().findOberteil(
                screen, T_PARTICIPATE, 0.55, Matcher.DEFAULT_THRESHOLD);
        if (teilnahme.isPresent()) {
            Matcher.Hit h = teilnahme.get();
            Log.action("Klick", Sprache.t("Join the tournament (Participate)  ["
                    + T_PARTICIPATE + " at " + h.centerX() + "," + h.centerY()
                    + ", match " + String.format("%.3f", h.score()) + "]",
                    "Am Turnier teilnehmen (Participate)  ["
                    + T_PARTICIPATE + " bei " + h.centerX() + "," + h.centerY()
                    + ", Uebereinstimmung " + String.format("%.3f", h.score()) + "]"));
            ctx.input().click(h);
            kasse.teilgenommen();
            neuesTurnier();
            imTurnier = true;
            // Idee B44 (Helfer 22.10.): die Uhr fuer "fremdes Bild" neu starten -- sonst lief sie vom letzten fremden Bild
            // weiter, und schon das Ladebild 0,3 s nach dem Klick loeste das rote X aus (11 von 132 Starts, je ~8 s).
            unbekanntSeit = 0;
            letztesUnbekanntUm = 0;
            participateAt = System.currentTimeMillis();
            turnierGesehen = false;
            runs.progress(Sprache.t("Tournament started", "Turnier begonnen"));
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }

        // Hier stand nur "return Outcome.WAITING" -- ohne ein Wort.
        //
        // Genau das ist Autor passiert: der Bot fand die Arena, las den
        // Stand der Abzeichen ("Badge of Courage, Ausgangsstand: 27.072")
        // und tat dann nichts mehr. Im Protokoll war danach nichts, weil
        // dieser Zweig stumm ist. Ein Bot, der wartet, muss sagen worauf --
        // sonst sieht Stillstand aus wie Absturz, und niemand weiss, welche
        // Vorlage schuld ist.
        return warteMit(ctx, screen, Sprache.t("Tournament window", "Turnierfenster"), T_PARTICIPATE, T_TICKETS_OFF);
    }

    /**
     * Wartet und sagt alle fuenf Sekunden, worauf.
     *
     * Zaehlt auf, welche der erwarteten Vorlagen gerade NICHT zu sehen sind.
     * Damit steht im naechsten Fehlerbericht der Name der Vorlage, die neu
     * aufgenommen werden muss -- statt einer Luecke im Protokoll.
     */
    private Outcome warteMit(BotContext ctx, Mat screen, String wo, String... erwartet) {
        long jetzt = System.currentTimeMillis();
        // Erst melden, wenn es hier wirklich 3 s lang nicht weitergeht -- ein Fenster, das
        // gerade aufgeht, ist kein Fehler (01.10.: "es geht nicht weiter" vor jedem Angriff).
        if (!wo.equals(warteOrt) || jetzt - warteZuletzt > 2_000) {
            warteOrt = wo;
            warteSeit = jetzt;
        }
        warteZuletzt = jetzt;
        if (jetzt - warteSeit < 3_000) return Outcome.WAITING;
        if (jetzt - letzteWartemeldung < 5_000) return Outcome.WAITING;
        letzteWartemeldung = jetzt;

        StringBuilder fehlt = new StringBuilder();
        for (String name : erwartet) {
            if (!ctx.visibleIfKnown(screen, name)) {
                if (fehlt.length() > 0) fehlt.append(", ");
                fehlt.append(name);
                if (!ctx.matcher().has(name)) fehlt.append(Sprache.t(" (template missing entirely)", " (Vorlage fehlt ganz)"));
            }
        }

        if (fehlt.length() == 0) {
            Log.debug("Arena", wo + ": alles da, aber noch nichts geklickt.");
        } else {
            warnung(Sprache.t(wo + ": stuck. Not visible: " + fehlt
                    + ". Please check with !screenshot and, if needed, record the template again with "
                    + "Capture-Images.exe.",
                    wo + ": es geht nicht weiter. Nicht zu sehen: " + fehlt
                    + ". Bitte mit !screenshot nachsehen und die Vorlage notfalls mit "
                    + "Capture-Images.exe neu aufnehmen."));
        }
        return Outcome.WAITING;
    }

    // ==================================================================
    //  3. Truppe stellen
    // ==================================================================

    /**
     * Truppe stellen: einmal "Deploy the best", dann bestaetigen.
     *
     * DER KNOPF BLEIBT NACH DEM KLICK STEHEN. Das ist der ganze Punkt
     * dieser Methode. Frueher stand hier schlicht "erst deploy_best, sonst
     * confirm" -- und weil deploy_best danach weiter zu sehen ist, hat der
     * Bot ihn im Halbsekundentakt immer wieder gedrueckt und ist nie zum
     * Bestaetigen gekommen. Im Protokoll standen dutzende identische
     * Klicks hintereinander:
     *
     *     22:01:28.389  Klick: Beste Truppe aufstellen  [0,999]
     *     22:01:28.928  Klick: Beste Truppe aufstellen  [0,999]
     *     22:01:29.471  Klick: Beste Truppe aufstellen  [0,999]
     *
     * Von aussen sah das aus wie ein langsamer Bot -- in Wahrheit war es
     * eine Schleife, die das Spielfenster im Dauerfeuer beschaeftigt hat.
     *
     * Jetzt wird nach jedem Klick gewartet, bis sich das Bild wirklich
     * aendert, und "Deploy the best" fruehestens nach fuenf Sekunden noch
     * einmal gedrueckt -- falls der erste Klick nicht angekommen ist.
     */
    private Outcome truppe(Mat screen, BotContext ctx) {
        turnierGesehen = true;   // fuer die Aepfel-Pruefung in anmelden()
        unbekannteBilder = 0;
        long jetzt = System.currentTimeMillis();

        // Welcher Knopf: Autors Einstellung fuer die Arena (!settings arena ...).
        boolean previous = Aufstellung.istPrevious("arena");
        String knopf = previous ? T_PREV_SQUAD : T_DEPLOY_BEST;
        String grund = previous ? Sprache.t("Squad from last time (Previous Squad)", "Truppe vom letzten Mal (Previous Squad)")
                                : Sprache.t("Deploy the best squad (Deploy the best)", "Beste Truppe aufstellen (Deploy the best)");

        if (jetzt - besteGeklicktAt > BESTE_ERNEUT_MS
                && ctx.clickIfVisible(screen, knopf, grund)) {
            besteGeklicktAt = jetzt;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (ctx.visible(screen, T_CONFIRM)) {
            // Die eigene Kampfkraft steht hier schon (Autor, 01.10.: "die aendert sich waehrend
            // der Wartezeit und in der Arena nicht") -- oben unter dem Titel, neben den
            // Schwertern. So kennt der Bot sie fuer die Punkte, bevor er den ersten Gegner sieht.
            eigeneKraftLesen(screen, ctx);
            if (ctx.clickIfVisible(screen, T_CONFIRM, Sprache.t("Confirm the squad (Confirm)", "Truppe bestaetigen (Confirm)"))) {
                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }
        }
        return warteMit(ctx, screen, Sprache.t("Squad window", "Truppenfenster"), knopf, T_CONFIRM);
    }

    /** Liest die Kampfkraft der Truppe aus dem Truppenfenster (Zeile unter dem Titel). */
    private void eigeneKraftLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int x, y, b, h;
        Optional<Matcher.Hit> schwerter = ctx.find(screen, T_POWER);
        Optional<Matcher.Hit> titel = ctx.find(screen, T_SQUAD_TITLE);
        if (schwerter.isPresent()) {
            Matcher.Hit sw = schwerter.get();
            x = sw.x() + sw.width();
            y = sw.y() - 4;
            b = (int) (ctx.width() * 0.22);
            h = sw.height() + 8;
        } else if (titel.isPresent()) {
            Matcher.Hit t = titel.get();
            x = t.centerX() - (int) (ctx.width() * 0.14);
            y = t.y() + t.height() - 6;
            b = (int) (ctx.width() * 0.28);
            h = (int) (ctx.height() * 0.07);
        } else {
            return;
        }
        List<Long> zahlen = ArenaLeser.zahlenIn(bild, x, y, b, h).stream()
                .filter(z -> z > 1_000_000 && z < 1_000_000_000L).toList();
        if (zahlen.isEmpty()) {
            Log.debug("Arena", "Eigene Kampfkraft im Truppenfenster nicht lesbar (Ausschnitt "
                    + x + "," + y + " " + b + "x" + h + ").");
            return;
        }
        long kraft = zahlen.get(0);
        if (kraft != meineStaerke) {
            Log.info("Arena", Sprache.t("Own power according to the squad window: " + String.format("%,d", kraft) + ".",
                    "Eigene Kampfkraft laut Truppenfenster: " + String.format("%,d", kraft) + "."));
        }
        meineStaerke = kraft;
        Gegnerbuch.setMeineKraft(kraft);   // fuer den Kraft-Hinweis im Notizblock (08.10.)
    }

    // ==================================================================
    //  4. Warteschlange
    // ==================================================================

    /**
     * Warten, bis zehn Spieler gefunden sind.
     *
     * Nichts mitzaehlen, nichts klicken -- das Spiel schaltet von selbst
     * weiter. Das kann zehn Sekunden dauern oder fuenf Minuten.
     *
     * WARUM HIER NICHT BEI JEDEM BILD "FORTSCHRITT" GEMELDET WIRD. Genau
     * das stand hier zuerst, und es hat den Wachhund stillgelegt:
     * progress() stellt die Leerlaufuhr zurueck, und bei einem Aufruf alle
     * 450 ms kann sie nie ablaufen. Waere die Warteschlange eingefroren,
     * haette der Bot bis zum Morgen davorgestanden. Genau dieser Fehler hat
     * im Verlies die Schleife ueber 223 Durchgaenge erst unsichtbar
     * gemacht.
     *
     * Jetzt wird nur waehrend der Geduldsgrenze (WARTESCHLANGE_GEDULD_MS)
     * Fortschritt gemeldet, und auch da nur alle dreissig Sekunden. Danach
     * laeuft die Leerlaufuhr an und schlaegt nach den ueblichen fuenf
     * Minuten an. Autor, 11.10.: in der Warteschlange wird einfach gewartet,
     * kein Kathedralen-Run mehr dazwischen.
     */
    private Outcome warteschlange(Mat screen, BotContext ctx, RunTracker runs) {
        turnierGesehen = true;   // fuer die Aepfel-Pruefung in anmelden()
        imTurnier = true;        // 11.10.: auch nach einem Spiel-Neustart mitten im Turnier (Einschuebe warten)
        unbekannteBilder = 0;
        long jetzt = System.currentTimeMillis();
        if (warteschlangeSeit == 0) warteschlangeSeit = jetzt;
        // Die Rundenuhr des Bots faengt erst mit der Pyramide an. Vorher lief sie ab
        // "Participate" -- nach 70 s Warteschlange hielt der Bot Runde 1 fuer fast vorbei,
        // sah sich keine Einheiten an und griff mit 73 s auf der Uhr an (01.10., 01:12).
        rundeSeit = jetzt;
        warteschlangeLetztesBildUm = jetzt;

        if (jetzt - letzterBericht > 20_000) {
            letzterBericht = jetzt;
            gefundeneLesen(screen, ctx);
            Log.info("Arena", Sprache.t("Queue: still looking for players ("
                    + ((jetzt - warteschlangeSeit) / 1000) + " s"
                    + (gefundenZuletzt >= 0 ? ", " + gefundenZuletzt + " of 10 players found" : "") + ").",
                    "Warteschlange: es werden noch Mitspieler gesucht ("
                    + ((jetzt - warteschlangeSeit) / 1000) + " s"
                    + (gefundenZuletzt >= 0 ? ", " + gefundenZuletzt + " von 10 Spielern da" : "") + ")."));
        }

        // Autor, 11.10. 15:00: "Niemals waehrend dem Warten auf Arena [Kathedrale spielen], einfach warten."
        // Die Pause mit dem Kathedralen-Run dazwischen (30.09.) ist weg. Damit die lange Wartezeit
        // nicht als Haenger gilt (Haenger-Alarm nach 2 min), zaehlt sie alle 30 s als Fortschritt --
        // bis zur Geduldsgrenze, danach greift der Wachhund wie frueher.
        boolean nochGeduld = jetzt - warteschlangeSeit < WARTESCHLANGE_GEDULD_MS;
        if (nochGeduld && jetzt - letzteFortschrittsmeldung > 30_000) {
            letzteFortschrittsmeldung = jetzt;
            runs.progress(Sprache.t("Queue (" + ((jetzt - warteschlangeSeit) / 1000) + " s)", "Warteschlange (" + ((jetzt - warteschlangeSeit) / 1000) + " s)"));
        }
        return Outcome.WAITING;
    }

    /** Liest "Players found: X/10" unter "Looking for opponents..." (Autor, 01.10.). */
    private void gefundeneLesen(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> suche = ctx.find(screen, T_SEARCHING);
        BufferedImage bild = ctx.frame();
        if (suche.isEmpty() || bild == null) return;
        Matcher.Hit s = suche.get();
        int x = s.x() - (int) (ctx.width() * 0.06);
        int y = s.y() + s.height() + (int) (ctx.height() * 0.02);
        // Dreifach vergroessert: in Originalgroesse las die Erkennung nur "Players found:".
        String text = ArenaLeser.liesTextGross(bild, x, y, s.width() + (int) (ctx.width() * 0.12),
                (int) (ctx.height() * 0.08));
        // Die Texterkennung setzt den Schraegstrich gern falsch ("21/0" statt "2/10") und die
        // Zahl auch mal vor den Text: nur die Ziffern zaehlen, hinten steht immer die 10.
        String ziffern = (text == null ? "" : text).replaceAll("[^0-9]", "");
        if (ziffern.length() >= 3 && ziffern.endsWith("10")) {
            String vorn = ziffern.substring(0, ziffern.length() - 2);
            try {
                int da = Integer.parseInt(vorn);
                if (da >= 0 && da <= 10) {
                    gefundenZuletzt = da;
                    gefundenGelesenUm = System.currentTimeMillis();
                    return;
                }
            } catch (NumberFormatException ignored) {
                // zu lang -- verlesen
            }
        }
        Log.debug("Arena", "Spielerzahl der Warteschlange nicht lesbar: \"" + text + "\"");
    }

    // ==================================================================
    //  5. Die Pyramide
    // ==================================================================

    private Outcome pyramide(Mat screen, BotContext ctx, RunTracker runs) {
        turnierGesehen = true;   // fuer die Aepfel-Pruefung in anmelden()
        unbekannteBilder = 0;
        // Die Pyramide ist da: das Turnier laeuft (11.10.: auch nach einem Spiel-Neustart mitten
        // im Turnier -- solange das gilt, warten Einschuebe und Spiel-Neustart).
        imTurnier = true;
        imAbschluss = false;
        durchgeblaettert = 0;
        // Pruefer 29.10.: erst die Uhr vom frischen Bild, dann die Schicht -- und die nicht mehr kurz vor dem eigenen Angriff.
        long restVorSchicht = restzeitLesen(screen, ctx);
        // Autor 01.11.: "Seitenfenster: Stage 1/5, ich bin in Stage 2." Das Lesen der Schilder unten dauert Sekunden, und bis dahin nannte die Anzeige die alte
        // Stage. Die Stage am Bild ist billig zu lesen und aendert am Rundenstand des Bots nichts (beobRundeLesen) -- sofort melden.
        if (de.hustlebot.schicht.ArenaStand.an()) {
            de.hustlebot.schicht.ArenaStand.rundeGelesen(Math.max(beobRundeLesen(screen, ctx), rundeZuletzt));
        }
        if (schichtLesenErlaubt(rundeGespielt, restVorSchicht, System.currentTimeMillis() - rundeSeit, spaetestensS())) {
            schichtImWarten(screen, ctx);
        }
        if (de.hustlebot.core.ArenaSchalter.an()) aufZurufAusGespeichert(ctx);

        // "Next stage in:" heisst: der eigene Angriff ist durch. Nichts zu
        // tun. Den Rundenwechsel hat rundenwechselMerken() oben in step()
        // schon vermerkt -- der laeuft bei jedem Bild, damit er auch dann
        // greift, wenn der Bot gerade im Vergleichsfenster steht.
        //
        // Kein progress() hier: eine Runde dauert hoechstens zwei Minuten.
        // Bleibt dieser Bildschirm laenger stehen, IST etwas kaputt --
        // dann soll die Leerlaufuhr laufen, damit der Notausstieg greift.
        burgenRueckwegSeit = 0;   // wieder im Turnier
        if (ctx.visible(screen, T_NEXT_STAGE)) {
            // Das Namensbild nach dem Kampf auch hier noch aufnehmen -- die Runde ist oft
            // schon um, wenn der Bot vom Ergebnis zurueckkommt (Review 01.10.).
            if (vorlageFuer != null) namensbildSpeichern(screen, ctx);
            Outcome b = burgenAusflugStarten(screen, ctx);
            if (b != null) return b;
            Outcome k = kathedraleAusflugStarten(screen, ctx);
            if (k != null) return k;
            Outcome n = nachAngriffDurchschauen(screen, ctx);
            return n != null ? n : Outcome.WAITING;
        }

        // Die Stage-Nummer VOR den Wartegruenden lesen. Am 01.10. (01:14) wechselte die Runde,
        // waehrend der Bot auf dem Ergebnisbildschirm stand: "Next stage in" war nie zu sehen,
        // rundeGespielt blieb wahr, und der Bot sah zwei Minuten lang der Uhr zu, bis Autor
        // eingriff. Die Zahl oben rechts ("Stage: 3/5") sagt es auch so.
        int runde = rundeLesen(screen, ctx);
        if (turnierPyramideUm == 0) {
            turnierPyramideUm = System.currentTimeMillis();
            turnierWartezeitMs = warteschlangeSeit > 0 ? turnierPyramideUm - warteschlangeSeit : 0;
            // Autor 24.10.: Messung fuer Stage 1. Die Uhr zeigt beim ersten Blick auf die Pyramide schon 115-118 s
            // (Protokoll 24.10. nachts: 118/118/118/117/115). Diese Zeile sagt, ob der Bot die Pyramide spaet sah (Wert gross:
            // zwischen Warteschlange und Pyramide lag ein Bild, das keiner der Faelle erkannte) oder das Spiel die Uhr
            // schon laufen liess (Wert klein: das Spiel braucht 2-5 s, bis die Pyramide steht).
            if (warteschlangeLetztesBildUm > 0) {
                Log.debug("Arena", "Pyramide zum ersten Mal gesehen: " + (turnierPyramideUm - warteschlangeLetztesBildUm)
                        + " ms nach dem letzten Warteschlangen-Bild.");
            }
        }
        if (vorlageFuer != null) namensbildSpeichern(screen, ctx);
        // Ben 21.10.: Die Runde ist laut Uhr vorbei und oben steht wieder "Select the next opponent" (nicht "Next stage
        // in") -- dann laeuft die naechste, auch wenn die Stage-Zahl nicht gelesen wurde. Vorher wartete der Bot bis zu
        // 130 s nach dem eigenen Angriff und verpasste die Runde.
        if (rundeGespielt && rundenendeErwartet > 0 && System.currentTimeMillis() > rundenendeErwartet + 5_000
                && ctx.visible(screen, T_SELECT) && rundeZuletzt < 5) {
            warnung(Sprache.t("Round over by the clock and \"Select the next opponent\" is back -- new round.",
                    "Runde laut Uhr vorbei und \"Select the next opponent\" steht wieder da -- neue Runde."));
            rundeZuletzt++;
            neueRunde();
            runde = rundeZuletzt;
        }
        // Bericht #H77K (Karla, 22.10.): ergebnis() setzt angriffGeklicktUm auf 0 -- nach jedem Kampf griff diese
        // Regel darum nie, der Bot sah 3 Minuten der Pyramide in Runde 5 zu. Jetzt die eigene Uhr der Stage.
        if (rundeGespielt && eigenerAngriffUm > 0
                && System.currentTimeMillis() - eigenerAngriffUm > 130_000) {
            // Laenger als eine Runde seit dem eigenen Angriff -- die naechste laeuft laengst,
            // auch wenn weder "Next stage in" noch die Zahl gelesen wurden.
            warnung(Sprache.t("No round change detected for over two minutes -- assuming a "
                    + "new round.",
                    "Seit ueber zwei Minuten kein Rundenwechsel erkannt -- es wird von einer "
                    + "neuen Runde ausgegangen."));
            if (rundeZuletzt < 5) rundeZuletzt++;
            neueRunde();
            runde = rundeZuletzt;
        }

        // Nach "Quick Attack" erst den Ergebnisbildschirm abwarten (30.09., 01:07: 0,7 s
        // nach dem Klick waehlte der Bot in der Pyramide schon den naechsten Gegner, der
        // Sieg wurde dem falschen zugeschrieben).
        if (angriffGeklicktUm > 0 && System.currentTimeMillis() - angriffGeklicktUm < 15_000) {
            return Outcome.WAITING;
        }
        // Diese Stage ist schon gespielt (graue Knoepfe): bis zur naechsten Runde nur warten.
        if (rundeGespielt) {
            Outcome n = nachAngriffDurchschauen(screen, ctx);
            return n != null ? n : Outcome.WAITING;
        }
        // Gerade ein Schild angeklickt: dem Vergleichsfenster bis zu 3 s geben, nicht nachklicken.
        if (System.currentTimeMillis() - schildGeklicktUm < 3000) {
            return Outcome.WAITING;
        }

        long restSekunden = restzeitLesen(screen, ctx);
        long inDerRunde = System.currentTimeMillis() - rundeSeit;

        // Spaet angreifen, aber nicht zu spaet. Erst wenn alle anderen ihr
        // Haekchen haben, steht im Protokoll, wer gegen Autor verloren hat
        // -- und das ist der beste Hinweis, den es gibt.
        int haekchen = ctx.matcher().findAll(screen, T_CHECK).size();
        boolean alleFertig = haekchen >= 9;

        // Drei Ausgaenge, und der dritte haengt an nichts.
        //
        // Vorher gab es nur zwei: neun Haekchen oder die abgelesene Uhr.
        // Beide koennen ausfallen. Ist ein Gegner abwesend, kommen nie neun
        // Haekchen zusammen -- Autor: "kann auch einfach ein gegner sein der
        // grade afk ist und nicht angegriffen hat". Und ar_clock ist eine
        // freiwillige Vorlage, die per Texterkennung gelesen wird. Fiel
        // beides aus, hat der Bot alle fuenf Runden verstreichen lassen:
        // null Punkte, Ticket verbraucht.
        //
        // Die eigene Uhr laeuft im Bot und kann nicht ausfallen.
        boolean zeitKnapp = (restSekunden >= 0 && restSekunden <= spaetestensS())
                || inDerRunde >= RUNDE_SPAETESTENS_MS;

        // Die Wartezeit zum Durchschauen nutzen (Autor, 30.09.): Kraft, Sets, Artefakte,
        // Bauarbeiter aller Gegner -- dann in den letzten SPAETESTENS_BEI_SEKUNDEN waehlen.
        boolean erkundenZeit = restSekunden >= 0 ? restSekunden > erkundenBisS()
                : inDerRunde < RUNDE_SPAETESTENS_MS - 8_000;
        if (nurErkunden) {
            // Beim Umblaettern im Vergleichsfenster ist kurz die Pyramide zu sehen (01:03,
            // 30.09.: viermal neu gestartet, dabei Klicks ins offene Fenster). Erst wenn
            // das Fenster laenger als 3 s nicht mehr da war, ist es wirklich zu.
            if (System.currentTimeMillis() - letzterErkundungsBlick < 3000) return Outcome.WAITING;
            nurErkunden = false;
            Log.info("Arena", Sprache.t("Comparison window not seen for 3 s -- scouting "
                    + (allesErkundet ? "is done." : "continues from the pyramid."),
                    "Vergleichsfenster seit 3 s nicht mehr zu sehen -- das Durchschauen "
                    + (allesErkundet ? "ist fertig." : "wird von der Pyramide aus fortgesetzt.")));
        }
        // Autor, 08.10.: "Das kannst du selber machen in der naechsten Arena, in der ersten Runde,
        // bevor du alle anderen anschaust" -- einmal das eigene Schild oeffnen und fotografieren,
        // damit Claude das Fenster kennt und das Ansehen des eigenen Teams bauen kann.
        // (08.10., 18:46: ein Klick aufs eigene Schild oeffnet nichts -- Autor: "Mein Team steht oben
        // im Vergleichsfenster, der Gegner unten." Das eigene Team wird darum dort angesehen.)
        if (!allesErkundet && erkundenZeit && !zeitKnapp) {
            Outcome e = erkundungStarten(screen, ctx, runde, restSekunden);
            if (e != null) return e;
        }
        // Pals (Autor, 02.10.): nach dem Durchschauen jeden einzeln anklicken, dessen Pals noch fehlen.
        // Review 02.10.: die Zeit fuer den Besuch selbst einrechnen, sonst faellt der Abbruch
        // mitten ins offene Pal-Fenster.
        boolean palZeit = restSekunden >= 0 ? restSekunden > erkundenBisS() + EXTRAS_BRAUCHT_S
                : inDerRunde < RUNDE_SPAETESTENS_MS - 8_000 - EXTRAS_BRAUCHT_S * 1000;
        if (allesErkundet && palZeit && !zeitKnapp && !nurErkunden) {
            Outcome p = palBesuchStarten(screen, ctx);
            if (p != null) return p;
        }
        // Alle neun haben angegriffen: das Protokoll ist vollstaendig -- dann darf es
        // frueher losgehen, aber erst nach dem Durchschauen.
        // Autor 25.10. (TR5): sind Burgen faellig, erst den eigenen Angriff (nach dem Durchschauen), dann die Wartezeit fuer
        // die Burgen -- nicht bis zum Schluss der Stage warten.
        boolean jetztAngreifen = zeitKnapp || ((alleFertig || burgenWunsch(runde)) && (allesErkundet || !erkundenZeit));

        if (!jetztAngreifen) {
            // Das Kampfprotokoll schon waehrend des Wartens lesen (Autor, 01.10.), alle 20 s --
            // beim Waehlen ist es dann frisch und kostet keine 2 s mehr.
            // Pruefer 29.10.: nur, wenn das Lesen nicht in die Angriffszeit hineinreicht.
            boolean reichtNoch = restSekunden < 0 || restSekunden - (protokollDauerMs + 999) / 1000 >= spaetestensS();
            if (reichtNoch && (protokollFuerRunde != runde || System.currentTimeMillis() - protokollGelesenUm > 20_000)) {
                protokollFuerRunde = -1;
                Outcome p = protokollLesen(screen, ctx, runde);
                if (p != null) return p;
            }
            if (System.currentTimeMillis() - letzterBericht > 15_000) {
                letzterBericht = System.currentTimeMillis();
                Log.info("Arena", Sprache.t("Round " + runde + ": " + haekchen + " of 9 opponents have "
                        + "attacked, " + (restSekunden < 0 ? "?" : restSekunden)
                        + " s left on the clock, " + (inDerRunde / 1000) + " s into the round. "
                        + "Attacking late -- that brings more points.",
                        "Runde " + runde + ": " + haekchen + " von 9 Gegnern haben "
                        + "angegriffen, noch " + (restSekunden < 0 ? "?" : restSekunden)
                        + " s laut Uhr, " + (inDerRunde / 1000) + " s in der Runde. "
                        + "Es wird spaet angegriffen -- das bringt mehr Punkte."));
            }
            // Fortschritt nur melden, wenn wirklich einer dazugekommen ist.
            // Ein Aufruf bei jedem Bild wuerde die Leerlaufuhr dauerhaft
            // zurueckstellen und den Wachhund abschalten.
            if (haekchen > zuletztHaekchen) {
                zuletztHaekchen = haekchen;
                runs.progress(Sprache.t(haekchen + " of 9 have attacked", haekchen + " von 9 haben angegriffen"));
            }
            return Outcome.WAITING;
        }

        if (angriffsphaseSeit == 0) angriffsphaseSeit = System.currentTimeMillis();
        // Bevor gewaehlt wird: einmal je Runde ins Kampfprotokoll sehen.
        //
        // Genau dafuer wird ueberhaupt so lange gewartet. Steht dort, dass
        // jemand gegen Autor angetreten und verloren hat, ist das der beste
        // Hinweis, den es gibt -- besser als jede Schaetzung aus der
        // Kampfstaerke.
        // Ben 21.10.: unter 15 s Rest NICHT mehr lesen (das kostete auf langsamen PCs bis zu 25 s, die Runde verstrich)
        // -- dann gilt, was schon gelesen ist.
        // Pruefer 29.10.: 15 s + gemessene Lesedauer, in Runde 1 gar nicht (siehe protokollVorAngriff).
        boolean protokollZeit = protokollVorAngriff(runde, restSekunden, protokollDauerMs);
        if (protokollZeit && (protokollFuerRunde != runde || System.currentTimeMillis() - protokollGelesenUm > 8_000)) {
            protokollFuerRunde = -1;
            Outcome p = protokollLesen(screen, ctx, runde);
            if (p != null) return p;
        } else if (!ctx.visibleIfKnown(screen, T_LOG_CLOSE)) {
            protokollZu.istZu();
        } else {
            // Halb gelesen, aber keine Zeit mehr, oder das Zuklappen von eben wirkt noch nicht: zuklappen, damit es keine
            // Schilder verdeckt -- bis hierher wurden sonst Schilder bei offenem Protokoll gelesen (Messung 30.10.: 10 %).
            long jetzt = System.currentTimeMillis();
            ProtokollZu.Was was = protokollZu.naechstes(runde, jetzt, de.hustlebot.core.Rechnertempo.mal(ProtokollZu.WARTEN_MS));
            if (was == ProtokollZu.Was.WARTEN) return Outcome.WAITING;
            if (was == ProtokollZu.Was.KLICKEN
                    && ctx.clickIfVisibleIfKnown(screen, T_LOG_CLOSE, Sprache.t("Close the battle log", "Kampfprotokoll zuklappen"))) {
                protokollZu.geklickt(jetzt);
                protokollAufgeklapptUm = 0;
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            // Geht nicht zu: nicht die Runde verklicken, sondern mit den Gegnern weitermachen, die zu sehen sind.
        }

        List<Schild> schilder = schilderLesen(screen, ctx);
        if (schilder == VERDECKT) return Outcome.WAITING;   // Protokoll offen oder Banner: erst lesen, wenn die Schilder frei sind (Autor 01.11.)
        bericht(schilder, runde, restSekunden, haekchen);

        if (schilder.isEmpty()) {
            // Nicht nur "nichts gefunden" melden, sondern WIE KNAPP es war.
            //
            // Beim ersten Versuch stand hier achtmal derselbe Satz, und
            // niemand konnte daran sehen, ob die Vorlage nur ein bisschen
            // danebenliegt oder vom voellig falschen Bildschirm stammt. Sie
            // stammte vom falschen: ar_reward war aus dem Vergleichsfenster
            // geschnitten, auf tuerkisem Grund -- in der Pyramide ist der
            // Grund hellblau.
            //
            // Mit der besten Uebereinstimmung im Protokoll ist der
            // Unterschied sofort zu sehen: knapp unter 0,84 heisst
            // nachjustieren, 0,4 heisst falscher Bildschirm.
            if (System.currentTimeMillis() - letzteSchildmeldung > 10_000) {
                letzteSchildmeldung = System.currentTimeMillis();
                var bester = ctx.matcher().find(screen, T_REWARD, 0.30);
                warnung(Sprache.t("No attackable opponent can be seen in the pyramid. "
                        + "The bot looks for the word \"Reward:\" (template ar_reward). "
                        + (bester.isPresent()
                            ? "Best match only "
                              + String.format("%.2f", bester.get().score())
                              + " at " + bester.get().centerX() + ","
                              + bester.get().centerY()
                              + " -- 0.84 is needed."
                            : "Not even close to being found.")
                        + " The template must come from a SHIELD IN THE PYRAMID "
                        + "(light blue background), not from the comparison window.",
                        "In der Pyramide ist kein angreifbarer Gegner zu erkennen. "
                        + "Gesucht wird das Wort \"Reward:\" (Vorlage ar_reward). "
                        + (bester.isPresent()
                            ? "Beste Uebereinstimmung nur "
                              + String.format("%.2f", bester.get().score())
                              + " bei " + bester.get().centerX() + ","
                              + bester.get().centerY()
                              + " -- gebraucht werden 0,84."
                            : "Nicht einmal annaehernd zu finden.")
                        + " Die Vorlage muss aus einem SCHILD DER PYRAMIDE stammen "
                        + "(hellblauer Grund), nicht aus dem Vergleichsfenster."));
            }
            return Outcome.WAITING;
        }

        Optional<Schild> ziel = zielWaehlen(schilder, runde);
        schichtMelden(screen, ctx, schilder, runde, ziel);   // Arena-Schicht (Autor 28.10.): nur mit Schalter
        if (ziel.isEmpty()) {
            // Kein progress(): das ist ein Stillstand, kein Fortschritt. Der
            // Wachhund soll anschlagen duerfen, damit Autor erfaehrt, dass
            // seine Sperrliste zu weit greift.
            warnung(Sprache.t("All remaining opponents are on the hard block list. "
                    + "This round stays empty -- that is how you set it. If this "
                    + "happens often, one of them belongs in ar_ungern_ instead of ar_block_.",
                    "Alle uebrigen Gegner stehen auf der harten Sperrliste. "
                    + "Diese Runde bleibt leer -- so hast du es festgelegt. Wenn das "
                    + "oefter vorkommt, gehoert einer davon in ar_ungern_ statt ar_block_."));
            return Outcome.WAITING;
        }

        Schild s = ziel.get();
        offenesErgebnisEintragen();   // ein offener alter Kampf zuerst
        imKampfGegen = s;
        kampfRest = restSekunden();
        kampfPlatz = platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt);
        namensbildVorAngriff(s, ctx);   // Autor 28.10.: Namensschild merken, bevor sich die Pyramide umordnet
        de.hustlebot.schicht.ArenaStand.setzen(null);   // Autor 29.10.: beim Gegner-Oeffnen keine Schicht
        Log.action("Klick", Sprache.t("Open opponent " + s.wer() + "  ["
                + s.punkte() + " points"
                + (s.botClan() ? ", Not in clan" : "")
                + (s.ungern() ? ", avoid" : "")
                + (s.sicher() ? ", safe win" : "")
                + (s.machbar() ? ", doable" : "")
                + ", win chance " + Math.round(wahlChance(s, rundeZuletzt) * 100) + " %]",
                "Gegner " + s.wer() + " oeffnen  ["
                + s.punkte() + " Punkte"
                + (s.botClan() ? ", Not in clan" : "")
                + (s.ungern() ? ", ungern" : "")
                + (s.sicher() ? ", sicherer Sieg" : "")
                + (s.machbar() ? ", machbar" : "")
                + ", Siegchance " + Math.round(wahlChance(s, rundeZuletzt) * 100) + " %]"));
        BufferedImage vorKlick = ctx.frame();   // die Pyramide, wie sie vor dem Klick aussah
        schildOeffnen(ctx, s);
        // Autor 28.10.: selbst eingestufte Gegner als Bild in seine Listen -- erst NACH dem Klick, damit der Angriff
        // nicht wartet (Review 28.10.), aber aus dem Bild von VOR dem Klick.
        autoListenBilder(schilder, ctx, runde, vorKlick);
        return Outcome.ACTED;
    }

    /**
     * Klappt das Kampfprotokoll auf, liest es und macht es wieder zu.
     *
     * WAS DARIN STEHT. Jede Zeile hat zwei Teile: oben "Angreifer ->
     * Verteidiger", darunter das Ergebnis aus Sicht des ANGREIFERS.
     * "DEFEAT" heisst also: der Angreifer ist gescheitert.
     *
     * WAS DEN BOT INTERESSIERT. Nur die Zeilen, in denen Autor rechts
     * steht -- also verteidigt hat -- UND das Ergebnis DEFEAT lautet. Dann
     * hat der Angreifer gerade eben gegen ihn verloren, und genau den
     * greift Autor am liebsten an.
     *
     * WIE GESUCHT WIRD. Nicht mit Texterkennung ueber die ganze Liste --
     * das waere ein Klumpen Text, aus dem man Zeilen raten muesste.
     * Stattdessen liefern die Vorlagen die Anker: jedes "DEFEAT" markiert
     * eine Zeile, "ar_me" markiert Autors Namen. Liegt beides
     * uebereinander und Autor rechts, wird nur noch der linke Teil dieser
     * einen Zeile vorgelesen -- der Name des Angreifers.
     *
     * @return ein Ergebnis, wenn ein Schritt getan wurde; null, wenn der
     *         Aufrufer weitermachen soll
     */
    /** Wann zuletzt ein uebrig gebliebenes kleines Fenster geschlossen wurde. */
    private long kleinesZuUm;

    /** Tempo (Lauf 11): so oft werden die seltenen Stoerfenster angesehen. */
    private static final long STOER_BLICK_MS = 1200;
    private long stoerBlickUm;

    /** Aufklapp-Klicks aufs Kampfprotokoll ohne Wirkung, je Runde. */
    private int protokollKlickRunde = -1, protokollFehlklicks;
    /** Fotos vom offenen Kampfprotokoll ohne eine einzige Autor-Zeile, je Turnier (08.10.). */
    private int protokollFotos;

    /** Eigenes Schild fotografiert (08.10.): einmal je Programmlauf reicht, bis das Ansehen gebaut ist. */
    private static volatile boolean eigenesSchildFotografiert;
    private long eigenesSchildGeklicktUm;

    /**
     * Klickt in Runde 1 einmal das eigene Schild in der Pyramide an, wartet, legt ein Foto nach
     * logs\arena-ich-HHmmss.png und schliesst das Fenster wieder. Liefert null, wenn nichts (mehr)
     * zu tun ist -- dann geht es normal mit dem Durchschauen weiter.
     */
    private Outcome eigenesSchildFotografieren(Mat screen, BotContext ctx) {
        if (eigenesSchildFotografiert || !MeinTeam.faellig()) return null;
        long jetzt = System.currentTimeMillis();
        if (eigenesSchildGeklicktUm == 0) {
            var ich = ctx.matcher().find(screen, T_ME);
            if (ich.isEmpty()) return null;   // ohne eigenes Schild kein Foto -- nicht blockieren
            ctx.klick(ich.get(), Sprache.t("Open own shield (photo)", "Eigenes Schild oeffnen (Foto)"));
            eigenesSchildGeklicktUm = jetzt;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (jetzt - eigenesSchildGeklicktUm < 1200) return Outcome.WAITING;
        eigenesSchildFotografiert = true;
        try {
            if (de.hustlebot.core.Beleg.an()) fotosBegrenzen(java.nio.file.Path.of("logs"), "arena-ich-", 5);
            java.nio.file.Path ziel = java.nio.file.Path.of("logs", "arena-ich-"
                    + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            // 01.11.: das Foto nur noch zur gezielten Fehlersuche; Oeffnen und Schliessen bleiben, wie sie waren.
            if (ctx.frame() != null && de.hustlebot.core.Beleg.schreiben(ctx.frame(), "png", ziel.toFile())) {
                Log.info("Arena", Sprache.t("Own shield opened -- photo " + ziel.getFileName()
                        + " (to check how your own team was rated).",
                        "Eigenes Schild geoeffnet -- Foto " + ziel.getFileName()
                        + " (zum Nachsehen, wie das eigene Team bewertet wurde)."));
            }
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Photo of own shield not saved: ", "Foto vom eigenen Schild nicht gespeichert: ") + e.getMessage());
        }
        // Wieder zu: das Fenster hat dasselbe X wie das Vergleichsfenster, sonst das rote X.
        if (!ctx.clickIfVisibleIfKnown(screen, "ar_dweller_close", Sprache.t("Close own window", "Eigenes Fenster schliessen"))
                && !ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close own window (X)", "Eigenes Fenster schliessen (X)"))) {
            Log.info("Arena", Sprache.t("No close button found for the own window -- the pop-up watcher takes over.",
                    "Kein Schliessen-Knopf fuer das eigene Fenster gefunden -- das Stoerfenster-Auge uebernimmt."));
        }
        ctx.sleepUntilChanged(1200);
        return Outcome.ACTED;
    }

    private int protokollFotoRunde = -1;
    /** Stage 5: Kampfprotokoll ganz durchgescrollt (Runde), Wischer dafuer, fremde Kaempfe vor dem Lesen. */
    private int vollProtokollRunde = -1, vollProtokollWische = 0, kaempfeVorLesen = 0;
    /** Autor 03.11.: oben im Protokoll die Stage der laufenden Runde? Wann zuletzt nachgesehen, wie oft hochgewischt. */
    private int protokollStufeRunde = -1, protokollHochRunde = -1, protokollHochVersuche = 0;

    /** Die Stage-Ueberschrift ganz oben im offenen Kampfprotokoll ("Stage 2", auch "Stage: 2"), -1 wenn keine gelesen wurde. */
    static int protokollStufeOben(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)st[a@]ge\\s*:?\\s*([1-5])").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
    private static final int VOLL_PROTOKOLL_WISCHE = 6;

    /** "Name 1 S / 3 N; ..." -- Turnier-Form je Spieler aus dem Kampfprotokoll (Siege und Niederlagen gegen andere). */
    private String turnierFormText() {
        java.util.Map<String, int[]> form = new java.util.TreeMap<>();
        for (var e : verlorenGegen.entrySet()) {
            form.computeIfAbsent(e.getKey(), x -> new int[2])[1] += e.getValue().size();
            for (String sieger : e.getValue()) form.computeIfAbsent(sieger, x -> new int[2])[0]++;
        }
        StringBuilder b = new StringBuilder();
        for (var e : form.entrySet()) {
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append(' ').append(e.getValue()[0]).append(" S / ").append(e.getValue()[1]).append(" N");
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    /** Schluessel eines fremden Kampfes im Kampfprotokoll: Angreifer, Verteidiger und wer gewann -- derselbe Kampf in jeder Lesung gleich, die Gegenrichtung ein anderer. */
    static String fremderKampfSchluessel(String angreifer, String verteidiger, boolean angreiferGewann) {
        return angreifer + ">" + verteidiger + (angreiferGewann ? "+" : "-");
    }

    private Outcome protokollLesen(Mat screen, BotContext ctx, int runde) {
        // Zugeklappt? Dann aufklappen und beim naechsten Bild lesen.
        if (!ctx.visibleIfKnown(screen, T_LOG_CLOSE)) {
            if (protokollKlickRunde != runde) {
                protokollKlickRunde = runde;
                protokollFehlklicks = 0;
            }
            // Lauf 7 (01.10.): 148 Klicks auf "aufklappen", weil ein Fenster ("Did you fall
            // asleep?") davor lag. Nach drei Klicks ohne Wirkung ist fuer diese Runde Schluss.
            if (protokollFehlklicks >= 3) {
                Log.info("Arena", Sprache.t("Battle log does not open -- doing without it this round.",
                        "Kampfprotokoll geht nicht auf -- in dieser Runde ohne."));
                protokollAufgeklapptUm = 0;
                protokollFuerRunde = runde;
                protokollGelesenUm = System.currentTimeMillis();
                return null;
            }
            if (ctx.clickIfVisibleIfKnown(screen, T_LOG_OPEN, Sprache.t("Open the battle log", "Kampfprotokoll aufklappen"))) {
                protokollFehlklicks++;
                protokollAufgeklapptUm = System.currentTimeMillis();
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            // Keine Vorlagen dafuer -- dann eben ohne. Der Bot spielt
            // weiter, nur ohne diesen Hinweis.
            protokollFuerRunde = runde;
            protokollGelesenUm = System.currentTimeMillis();
            return null;
        }

        protokollFehlklicks = 0;   // es ist offen
        int breite = (int) (ctx.width() * PROTOKOLL_BREITE);
        int abstand = (int) (ctx.height() * NAMEN_UEBER_ERGEBNIS);

        // Die Pfeile einmal einsammeln, gruen und rot getrennt.
        List<Pfeil> pfeile = new java.util.ArrayList<>();
        for (Matcher.Hit h : findAllWennBekannt(screen, ctx, T_LOG_WON)) {
            if (h.centerX() <= breite) pfeile.add(new Pfeil(h, true));
        }
        for (Matcher.Hit h : findAllWennBekannt(screen, ctx, T_LOG_LOST)) {
            if (h.centerX() <= breite) pfeile.add(new Pfeil(h, false));
        }

        // WO FAENGT DAS PROTOKOLL AN?
        //
        // Bisher wurde nur die Breite begrenzt: alles links von 37 Prozent
        // galt als Protokoll. Das reicht nicht. Angesetzt wird jetzt an
        // Autors Namen -- und der steht auch auf seinem EIGENEN Schild in
        // der Pyramide. Liegt das weit genug links, waere es als
        // Protokollzeile gelesen worden, und was da an Text daneben steht,
        // waere als Gegnername in der Verbrauchtliste gelandet.
        //
        // ar_log_title ist die Ueberschrift des Protokolls. Alles darunter
        // gehoert dazu, alles darueber nicht. Fehlt die Vorlage, bleibt es
        // bei der alten, groberen Grenze.
        int obenGrenze = 0;
        var titel = ctx.matcher().has(T_LOG_TITLE)
                ? ctx.matcher().find(screen, T_LOG_TITLE)
                : java.util.Optional.<Matcher.Hit>empty();
        if (titel.isPresent()) {
            obenGrenze = titel.get().y() + titel.get().height();
        }

        // Autor 03.11.: "In Stage 3 den Battlelog von Stage 3 anschauen, in Stage 5 den von Stage 5 -- und nicht bei Stage 5 den
        // von Stage 1." Oben im offenen Protokoll steht die neueste Stage. Steht dort eine ZWEI oder mehr zu alte, ist es nach
        // unten gerollt (das Spiel merkt sich die Stelle): erst ganz hochwischen, dann lesen. Eine zu alt ist normal -- an 22
        // Fotos vom 01.11. stand oben die vorige Stage, solange in der neuen noch niemand gekaempft hatte. Je Oeffnen einmal
        // nachsehen, hoechstens zweimal je Runde wischen -- nicht mitten im gewollten Durchsehen in Stage 5.
        boolean mittenImDurchsehen = vollProtokollWische > 0 && vollProtokollRunde != runde;
        if (titel.isPresent() && runde >= 2 && !mittenImDurchsehen && (protokollAufgeklapptUm > 0 || protokollStufeRunde != runde)
                && ctx.frame() != null) {
            int oben = protokollStufeOben(ArenaLeser.liesText(ctx.frame(), 0, obenGrenze, breite, (int) (ctx.height() * 0.05)));
            protokollStufeRunde = runde;
            if (protokollHochRunde != runde) {
                protokollHochRunde = runde;
                protokollHochVersuche = 0;
            }
            if (oben > 0 && oben < runde - 1 && protokollHochVersuche < 2) {
                protokollHochVersuche++;
                int x = Math.max(10, breite / 2);
                for (int i = 0; i < 4; i++) {
                    ctx.input().dragSchnell(x, (int) (ctx.height() * 0.30), x, (int) (ctx.height() * 0.90));
                    ctx.sleep(150);
                }
                ctx.sleepUntilChanged(700);
                Log.info("Arena", Sprache.t("Battle log showed Stage " + oben + " at the top in round " + runde
                                + " -- swiped back up to the newest fights.",
                        "Kampfprotokoll zeigte oben Stage " + oben + " in Runde " + runde + " -- wieder hoch zu den neuesten Kaempfen gewischt."));
                return Outcome.ACTED;   // offen lassen, im naechsten Bild lesen
            }
        }

        int neuVerbraucht = 0;
        int neuBeute = 0;
        int gelesen = 0;
        if (vollProtokollWische == 0) kaempfeVorLesen = fremdeKaempfe.size();

        // ANGESETZT WIRD AN AUTORS NAMEN, nicht am Ergebniswort.
        //
        // Frueher lief die Schleife ueber jedes "DEFEAT" im Protokoll und
        // suchte dann, ob Autor in derselben Zeile steht. Das war
        // umstaendlich und -- seit Autors Antwort -- womoeglich verkehrt
        // herum: er sagt, das Ergebnis stehe IMMER aus seiner Sicht. Dann
        // hiesse "DEFEAT" in einer Zeile, in der er verteidigt hat, dass ER
        // verloren hat -- also genau das Gegenteil dessen, was der alte
        // Code daraus gemacht hat.
        //
        // Ueber ar_me zu gehen loest das nebenbei mit: es liefert genau die
        // Zeilen, die Autor betreffen. Und er hat gesagt, im Protokoll
        // stehen auch fremde Kaempfe -- bei denen ein Pfeil "aus seiner
        // Sicht" gar nichts bedeuten kann, weil er nicht dabei ist. Die
        // fallen so von selbst heraus.
        // 08.10. NEU GELESEN (Foto arena-protokoll-190124.png): Jede Zeile ist "Angreifer -> Verteidiger",
        // der Pfeil in der Mitte zeigt das Ergebnis AUS SICHT DES ANGREIFERS (gruen = Angreifer hat
        // gewonnen, darunter VICTORY; rot = verloren, DEFEAT). Bisher wurde an Autors Namen (ar_me)
        // angesetzt -- der sieht im Protokoll anders aus als auf dem Schild, es gab seit 01.10. keinen
        // einzigen Treffer. Jetzt wird an JEDEM Pfeil angesetzt und beide Namen werden gelesen:
        // Autors eigene Kaempfe wie bisher, dazu alle fremden (Autor, 08.10.: "gegen wen die anderen
        // gewonnen und verloren haben und dir das merken").
        for (Pfeil pf : pfeile) {
            Matcher.Hit p = pf.hit();
            if (p.centerY() < obenGrenze) continue;      // steht ueber dem Protokoll
            if (gelesen >= MAX_PROTOKOLLZEILEN) break;   // jede Zeile kostet Lesezeit
            int h = Math.max(18, p.height());
            int y = p.centerY() - h / 2 - 2;
            String links = ArenaLeser.liesName(ctx.frame(), 0, y, Math.max(1, p.x() - 4), h + 4).strip();
            String rechts = ArenaLeser.liesName(ctx.frame(), p.x() + p.width() + 4, y,
                    Math.max(1, breite - p.x() - p.width() - 4), h + 4).strip();
            gelesen++;
            if (links.isBlank() || rechts.isBlank()) continue;
            boolean ichAngreifer = istAutor(links), ichVerteidiger = istAutor(rechts);
            boolean angreiferGewann = pf.gewonnen();

            if (ichAngreifer) {
                // AUTOR HAT ANGEGRIFFEN: der Verteidiger ist verbraucht, egal wie es ausging.
                // Punkt 14 (14.10.): hat Autor gewonnen, steht der Gegner fuer immer in arena-verlierer.txt.
                if (angreiferGewann && merkeVerlierer(rechts, false)) {
                    Log.info("Arena", Sprache.t("Battle log: " + rechts + " defeated by you -- now on the loser list for good.",
                            "Kampfprotokoll: " + rechts + " von dir besiegt -- steht jetzt fuer immer in der Verlierer-Liste."));
                }
                if (merkeVerbraucht(rechts, "Kampfprotokoll")) neuVerbraucht++;
                continue;
            }
            if (ichVerteidiger) {
                String schluessel = namensSchluessel(links);
                if (schluessel.isEmpty()) continue;
                if (angreiferGewann) {
                    if (gegenMichGewonnen.add(schluessel)) {
                        Log.info("Arena", Sprache.t("Battle log: " + links + " beat you -- +30 points.",
                                "Kampfprotokoll: " + links + " hat gegen dich gewonnen -- +30 Punkte."));
                    }
                    continue;
                }
                // Punkt 14 (14.10.): er hat Autor angegriffen und verloren -> fuer immer merken.
                if (merkeVerlierer(links, true)) {
                    Log.info("Arena", Sprache.t("Battle log: " + links + " is now on the loser list for good.",
                            "Kampfprotokoll: " + links + " steht jetzt fuer immer in der Verlierer-Liste."));
                }
                if (schonAngegriffen.contains(schluessel)) continue;   // schon verbraucht: kein Bonus
                if (gegenMichVerloren.add(schluessel)) {
                    neuBeute++;
                    Log.info("Arena", Sprache.t("Battle log: " + links
                            + " lost to you -- -20 points, the bot prefers to attack them.",
                            "Kampfprotokoll: " + links
                            + " hat gegen dich verloren -- -20 Punkte, den greift der Bot bevorzugt an."));
                }
                continue;
            }
            // Ein fremder Kampf: wer hat gegen wen gewonnen?
            String sieger = angreiferGewann ? links : rechts, verlierer = angreiferGewann ? rechts : links;
            // Pruefung 01.11.: "Denis -> Kreyn +150 VICTORY" und "Kreyn -> Denis +0 DEFEAT" sind ZWEI Kaempfe mit demselben Sieger und Verlierer; der Schluessel
            // "verlierer>sieger" machte aus der zweiten Zeile einen schon bekannten Kampf, sie fehlte im Protokoll. Der Angreifer gehoert in den Schluessel.
            String k = fremderKampfSchluessel(namensSchluessel(links), namensSchluessel(rechts), angreiferGewann);
            if (fremdeKaempfe.add(k)) {
                verlorenGegen.computeIfAbsent(namensSchluessel(verlierer), x -> new java.util.LinkedHashSet<>())
                        .add(namensSchluessel(sieger));
                // Autor, 08.10.: unter dem Angreifer steht, was er bekam (+150/+225 = unter 10 s, weniger =
                // langer Kampf, +0 = verloren). Ein langer Sieg heisst: der Verteidiger hat sich lange
                // gewehrt, aber "trotzdem" verloren -- das wird gemerkt (+3 % Siegchance gegen ihn).
                long erhalten = -1;
                if (angreiferGewann) {
                    String zahl = ArenaLeser.liesText(ctx.frame(), 0, p.centerY() + h / 2 + 4,
                            Math.max(1, p.x() - 4), h + 8).replaceAll("[^0-9]", "");
                    if (!zahl.isEmpty() && zahl.length() <= 3) erhalten = Long.parseLong(zahl);
                    // Autor, 08.10. (genauer): "Wenn ein starker Gegner einen mittleren/schwachen angreift und
                    // nur 130 Punkte oder weniger holt" -- er hat sich schwer getan, also +3 % gegen IHN
                    // (den Angreifer). Ob der Verteidiger mittel/schwach war, entscheidet siegchance().
                    if (erhalten > 0 && erhalten <= 130) {
                        muehsamGegen.computeIfAbsent(namensSchluessel(sieger), x -> new java.util.LinkedHashSet<>())
                                .add(namensSchluessel(verlierer));
                    }
                }
                Log.debug("Arena", "Kampfprotokoll: " + sieger + " schlug " + verlierer
                        + (angreiferGewann ? " (als Angreifer, +" + (erhalten < 0 ? "?" : erhalten) + ")" : " (als Verteidiger)") + ".");
            }
        }

        // 08.10.: Seit dem 01.10. hat diese Lesung in keinem Protokoll je eine Zeile mit Autor
        // gefunden (0 Treffer in allen Logs), obwohl Gegner ihn laut eigener Aussage angriffen
        // und verloren. Ohne eine einzige Autor-Zeile: Foto fuer Claude, hoechstens zwei je Turnier.
        if (gelesen == 0 && runde >= 2 && !pfeile.isEmpty() && protokollFotos < 2 && ctx.frame() != null) {   // in Runde 1 ist es noch leer
            protokollFotos++;
            if (de.hustlebot.core.Beleg.an()) fotosBegrenzen(java.nio.file.Path.of("logs"), "arena-protokoll-", 10);
            java.nio.file.Path ziel = java.nio.file.Path.of("logs", "arena-protokoll-"
                    + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            try {
                boolean gespeichert = de.hustlebot.core.Beleg.schreiben(ctx.frame(), "png", ziel.toFile());
                Log.info("Arena", Sprache.t("Battle log open, but no line readable" + (gespeichert ? " -- photo " + ziel.getFileName() : "")
                        + " (arrows seen: " + pfeile.size() + ", title "
                        + (titel.isPresent() ? "yes" : "no") + ").",
                        "Kampfprotokoll offen, aber keine Zeile lesbar" + (gespeichert ? " -- Foto " + ziel.getFileName() : "")
                        + " (Pfeile gesehen: " + pfeile.size() + ", Titel "
                        + (titel.isPresent() ? "ja" : "nein") + ")."));
            } catch (Exception e) {
                Log.debug("Arena", "Protokoll-Foto nicht gespeichert: " + e.getMessage());
            }
        }
        if (neuBeute == 0) {
            Log.info("Arena", Sprache.t("Battle log read, round " + runde
                    + ": nobody attacked you and failed.",
                    "Kampfprotokoll gelesen, Runde " + runde
                    + ": niemand ist gegen dich angetreten und gescheitert."));
        }
        if (neuVerbraucht > 0) {
            Log.info("Arena", Sprache.t("Battle log: " + neuVerbraucht + " more opponents are "
                    + "already used up this arena (" + schonAngegriffen.size()
                    + " in total). They are no longer possible targets.",
                    "Kampfprotokoll: " + neuVerbraucht + " weitere Gegner sind "
                    + "diese Arena schon verbraucht (insgesamt " + schonAngegriffen.size()
                    + "). Die kommen als Ziel nicht mehr in Frage."));
        }

        // Autor 22.10. abends: "In der 5. Stage das komplette Battle-Log einmal von oben bis unten durchscrollen -- von jedem
        // Gegner unter mir anschauen, gegen wen er verloren und gewonnen hat und wie viele Kaempfe er gemacht hat." Einmal je
        // Turnier in Stage 5 (wenn noch mindestens 30 s Zeit sind): nach unten wischen und weiterlesen, bis nichts Neues kommt.
        int neueKaempfe = fremdeKaempfe.size() - kaempfeVorLesen;
        if (runde >= LETZTE_RUNDE && vollProtokollRunde != runde) {
            long rest = restSekunden();
            boolean weiter = vollProtokollWische == 0 || neueKaempfe > 0;
            if (weiter && vollProtokollWische < VOLL_PROTOKOLL_WISCHE && (rest < 0 || rest >= 30)) {
                vollProtokollWische++;
                int x = Math.max(10, breite / 2);
                ctx.input().dragLangsam(x, (int) (ctx.height() * 0.85), x, (int) (ctx.height() * 0.42));
                ctx.sleepUntilChanged(700);
                kaempfeVorLesen = fremdeKaempfe.size();
                return Outcome.ACTED;   // offen lassen, im naechsten Bild weiterlesen
            }
            vollProtokollRunde = runde;
            Log.info("Arena", Sprache.t("Battle log scrolled through (" + vollProtokollWische + " swipes, " + fremdeKaempfe.size()
                    + " fights of others known): " + turnierFormText(),
                    "Kampfprotokoll ganz durchgesehen (" + vollProtokollWische + "x gewischt, " + fremdeKaempfe.size()
                    + " fremde Kaempfe bekannt): " + turnierFormText()));
            // Autor 03.11.: "Im Battlelog war nur Stage 1 und 2 abgebildet -- fuer die anderen Stages muesste er hochscrollen." Das
            // Spiel merkt sich die Stelle auch nach dem Zuklappen; beim Warten aufs spaete Angreifen sah der Bot so nur alte Kaempfe
            // und nie einen neuen. Darum wieder ganz nach oben (schnelle Wischer rollen weiter, einer mehr als nach unten).
            if (vollProtokollWische > 0) {
                int x = Math.max(10, breite / 2);
                for (int i = 0; i <= vollProtokollWische; i++) {
                    ctx.input().dragSchnell(x, (int) (ctx.height() * 0.30), x, (int) (ctx.height() * 0.90));
                    ctx.sleep(150);
                }
                ctx.sleepUntilChanged(700);
                Log.debug("Arena", "Kampfprotokoll wieder nach oben gewischt (" + (vollProtokollWische + 1) + "x) -- die neuesten Kaempfe stehen oben.");
            }
        }
        protokollFuerRunde = runde;
        protokollGelesenUm = System.currentTimeMillis();
        if (protokollAufgeklapptUm > 0) {
            long dauer = protokollGelesenUm - protokollAufgeklapptUm;
            protokollAufgeklapptUm = 0;
            if (dauer > 0 && dauer < 60_000) protokollDauerMs = (protokollDauerMs + dauer) / 2;
        }
        // Autor, 11.10.: "Der Bot kann den Battle-Log offen lassen, wenn er keinen Gegner ueberdeckt." Erst
        // nachsehen, WAS er ueberdeckt -- ein Foto je Runde vom offenen Protokoll.
        if (protokollFotoRunde != runde) {
            protokollFotoRunde = runde;
            fotoSerie("protokoll-offen");
        }
        if (ctx.clickIfVisibleIfKnown(screen, T_LOG_CLOSE, Sprache.t("Close the battle log", "Kampfprotokoll zuklappen"))) {
            protokollZu.naechstes(runde, System.currentTimeMillis(), 0);
            protokollZu.geklickt(System.currentTimeMillis());
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        return null;
    }

    /**
     * Nacht 30.10.: 102 Klicks auf "zuklappen" in 32 s, einer alle 0,28 s, bis die Runde um war -- 15 solche Serien, 5 verpasste
     * Runden, 3 von 4 Turnieren ohne Platz. Gekommen ist das nur aus dem Zweig "keine Zeit mehr", der in jedem Bild neu klickt:
     * sleepUntilChanged kehrt sofort zurueck, weil das Zuklappen selbst das Bild aendert, und der naechste Klick trifft mitten in
     * die Bewegung. Jetzt: nach einem Klick erst warten, hoechstens drei Klicks je Runde, danach mit offenem Protokoll weiter.
     */
    static final class ProtokollZu {
        static final int MAX_KLICKS = 3;
        static final long WARTEN_MS = 1500;

        enum Was { KLICKEN, WARTEN, AUFGEBEN }

        private int runde = -1, klicks;
        private long klickUm;

        Was naechstes(int runde, long jetzt, long wartenMs) {
            if (runde != this.runde) {
                this.runde = runde;
                klicks = 0;
                klickUm = 0;
            }
            if (jetzt - klickUm < wartenMs) return Was.WARTEN;
            return klicks >= MAX_KLICKS ? Was.AUFGEBEN : Was.KLICKEN;
        }

        void geklickt(long jetzt) {
            klicks++;
            klickUm = jetzt;
        }

        /** Es ist zu: Das Lesen alle 8 s klappt es je Runde mehrmals auf und zu, das sind keine Fehlklicks. */
        void istZu() {
            klicks = 0;
        }
    }

    private final ProtokollZu protokollZu = new ProtokollZu();

    /** Abgebrochene Einheiten-Pruefungen je Spieler (Namensschluessel), seit der Bot laeuft. */
    private final Map<String, Integer> pruefAbbrueche = new java.util.HashMap<>();
    private static final int PRUEF_ABBRUECHE_MAX = 2;

    /** Ein Pfeil in einer Protokollzeile, samt dem, was seine Farbe sagt. */
    private record Pfeil(Matcher.Hit hit, boolean gewonnen) {
    }

    /** Der Pfeil, der am ehesten zu dieser Zeile gehoert -- oder null. */
    private static Pfeil pfeilInZeile(List<Pfeil> pfeile, int zeileY, int abstand) {
        Pfeil beste = null;
        int naechste = Integer.MAX_VALUE;
        for (Pfeil p : pfeile) {
            int weg = Math.abs(p.hit().centerY() - zeileY);
            if (weg < abstand && weg < naechste) {
                naechste = weg;
                beste = p;
            }
        }
        return beste;
    }

    /**
     * Steht dieses Wort in derselben Protokollzeile?
     *
     * Das Ergebniswort steht eine Zeilenhoehe UNTER den beiden Namen -- so
     * war es schon im alten Code angesetzt, und daran hat sich nichts
     * geaendert. Gebraucht wird das nur noch als Rueckfallweg, wenn die
     * Pfeilvorlagen nichts finden.
     */
    private static boolean wortInZeile(BotContext ctx, Mat screen, String vorlage,
                                       int nameY, int abstand, int breite) {
        for (Matcher.Hit h : findAllWennBekannt(screen, ctx, vorlage)) {
            if (h.centerX() > breite) continue;
            if (Math.abs(h.centerY() - (nameY + abstand)) < abstand) return true;
        }
        return false;
    }

    /** "Round missed": wer in der verpassten Runde nur angeklickt, aber nicht bekaempft wurde, bleibt waehlbar. */
    private void verpassterAngriffZurueck() {
        String k = angriffOhneErgebnis;
        angriffOhneErgebnis = null;
        if (k == null || angriffOhneErgebnisRunde < rundeZuletzt - 1) return;
        if (schonAngegriffen.remove(k)) {
            Log.info("Arena", Sprache.t("Round missed: the attack on " + k + " never happened -- that opponent can still be chosen.",
                    "Runde verpasst: der Angriff auf " + k + " fand nie statt -- der Gegner bleibt waehlbar."));
        }
    }

    /**
     * Traegt einen Gegner als verbraucht ein.
     *
     * @return true, wenn er vorher noch nicht darin stand
     */
    private boolean merkeVerbraucht(String name, String woher) {
        String schluessel = namensSchluessel(name);
        if (schluessel.isEmpty()) return false;
        if (!schonAngegriffen.add(schluessel)) return false;

        // WICHTIG: auch von der Bevorzugtliste streichen.
        //
        // Die Reihenfolge im Protokoll steht nicht fest. Steht die Zeile
        // "er hat gegen mich verloren" weiter oben als die Zeile "ich habe
        // ihn angegriffen", war der Bonus schon vergeben, bevor sich
        // herausstellte, dass der Gegner verbraucht ist. Die Pruefung beim
        // Eintragen allein reicht deshalb nicht -- es muss auch rueckwaerts
        // gehen.
        gegenMichVerloren.remove(schluessel);
        Log.debug("Arena", woher + ": " + name.strip()
                + " ist diese Arena schon verbraucht.");
        return true;
    }

    /**
     * Autor 24.10. (Ideenliste A01): Niederlage-Banner und Gegenstandsfenster nur in ihrem festen Kasten suchen (siehe
     * {@link ArenaKaesten}) statt in jedem Bild im ganzen Fenster -- {@code ar_defeat} kostete 24 ms je Bild, 2000 Mal in
     * 13 Minuten Arena (49 s). Ohne Kasten fuer diese Vorlage, bei kleinem Bild und alle paar Fehlschlaege einmal
     * gilt die bisherige Suche ({@link BotContext#visible}).
     */
    private static boolean kastenSichtbar(Mat screen, BotContext ctx, String name) {
        return ArenaKaesten.sichtbar(ctx.matcher(), screen, name, Matcher.DEFAULT_THRESHOLD, () -> ctx.visible(screen, name));
    }

    /** findAll, aber ohne Laerm, wenn es die Vorlage gar nicht gibt. */
    private static List<Matcher.Hit> findAllWennBekannt(Mat screen, BotContext ctx,
                                                        String name) {
        return ctx.matcher().has(name)
                ? ctx.matcher().findAll(screen, name)
                : List.of();
    }

    /**
     * Sucht alle Schilder, gegen die noch nicht angetreten wurde.
     *
     * Das Wort "Reward:" steht nur in genau diesen. Wo Autor schon
     * gekaempft hat, steht dort "Victory!" oder "Defeat" -- die sind
     * damit vom Tisch, denn jeder Gegner geht nur einmal.
     */
    private List<Schild> schilderLesen(Mat screen, BotContext ctx) {
        // Einmal je Runde reicht.
        //
        // Jedes Schild kostet einen eigenen PowerShell-Vorgang von rund
        // einer Sekunde, dazu je Sperrnamen einen vollstaendigen
        // Bildvergleich. Bei zehn Schildern und fuenfzehn Sperrnamen waeren
        // das ueber zehn Sekunden -- pro Bild. Zugeschlagen wird aber erst
        // dreissig Sekunden vor Rundenende; ein zweiter Durchgang haette
        // die Runde gekostet.
        //
        // Innerhalb einer Runde aendert sich an Namen und Punktzahlen der
        // Gegner ohnehin nichts, was die Wahl beeinflusst.
        // Der Zwischenspeicher gilt nur wenige Sekunden, nicht die ganze
        // Runde. Er enthaelt BILDPUNKTE, und die Pyramide ordnet sich um,
        // sobald jemand einen Kampf gewinnt. Ein Klick auf einen mehrere
        // Minuten alten Punkt trifft dann ein anderes Schild -- im
        // schlimmsten Fall einen gesperrten Gegner.
        long jetzt = System.currentTimeMillis();
        if (schilderCacheRunde == rundeZuletzt
                && !schilderCache.isEmpty()
                && jetzt - schilderCacheAt < SCHILDER_HALTBAR_MS) {
            return schilderCache;
        }

        // Autor 01.11. (Pruefung 3.137): bei offenem Protokoll oder mit dem Banner "Tournament phase N has begun" im Bild wird NICHTS gelesen -- weder Namen
        // noch Listen noch Tabelle, und nichts gelernt. Am 15:05:44 verdeckte das Protokoll das X von "Xenos" (gesperrt), der Bot las "enos", traf das
        // Listenbild "enes" (sicher, 0,86) und lernte "enos" als neue Glyphe. Der Aufrufer wartet, bis die Schilder frei sind.
        String verdeckt = schilderVerdecktDurch(screen, ctx);
        if (verdeckt != null) {
            if (!verdeckt.equals(verdecktGemeldet)) {
                verdecktGemeldet = verdeckt;
                Log.debug("Arena", "Schilder verdeckt (" + verdeckt + ") -- nichts gelesen, bis sie wieder frei sind.");
            }
            return VERDECKT;
        }
        verdecktGemeldet = "";

        List<Schild> liste = new ArrayList<>();
        BufferedImage bild = ctx.frame();

        List<Matcher.Hit> eigene = ctx.matcher().findAll(screen, T_ME);
        if (!eigene.isEmpty()) eigenenNamenLesen(bild, eigene.get(0));   // Freunde: statt fest "autor"
        if (rundeZuletzt <= 1 && !eigene.isEmpty()) {
            Matcher.Hit ich = eigene.get(0);
            meinPlatz = platzAusLage(ich.centerX(), ich.y() + SCHILD_HOEHE, ctx.width(), ctx.height());
        }
        // 16.10.: der aktuelle Platz in JEDER Runde -- fuer die Endrunde. (21.10.: die Pyramide ist NICHT nach Punkten
        // geordnet -- Autors Bild vom 15.10.: Platz 2 hatte 600, Platz 3 hatte 812. Der echte Rang kommt aus tabelleLesen.)
        if (!eigene.isEmpty()) {
            Matcher.Hit ich = eigene.get(0);
            aktuellerPlatz = platzAusLage(ich.centerX(), ich.y() + SCHILD_HOEHE, ctx.width(), ctx.height());
            if (rundeZuletzt >= 2 && platzGemeldetRunde != rundeZuletzt && aktuellerPlatz > 0) {   // einmal je Runde, erst nach dem Banner (Autor 01.11.)
                platzGemeldetRunde = rundeZuletzt;
                Log.info("Arena", Sprache.t("Round " + rundeZuletzt + ": I am at place " + aktuellerPlatz + ".", "Runde " + rundeZuletzt + ": ich auf Platz " + aktuellerPlatz + "."));
            }
        }
        tabelleLesen(screen, ctx, bild);
        List<Matcher.Hit> bots   = ctx.matcher().findAll(screen, T_NOT_IN_CLAN);
        // Messung 01.11.: die Listenbilder (226 bei Autor) kosteten im ganzen Bild je ~33 ms, zusammen 6-9 s je Lesung -- so lange stand der Bot nach
        // jedem Sieg und beim ersten Blick auf die Pyramide. Gebraucht wird je Bild nur, ob es AUF EINEM SCHILD liegt (inFeld unten). Darum erst die
        // "Reward:"-Schilder suchen und die Listenbilder nur in deren Feldern: dieselbe Frage, dieselben Werte (findImBereich), ein Bruchteil der Flaeche.
        int schildBreite = (int) (ctx.width() * 0.14);
        List<Matcher.Hit> rewards = rewardTreffer(screen, ctx);
        List<org.opencv.core.Rect> schildFelder = schildFelder(rewards, eigene, schildBreite);
        java.util.Map<String, int[]> groessen = ctx.matcher().sizes();
        List<Matcher.Hit> sperr  = trefferInFeldern(ctx.matcher(), screen, T_BLOCK_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        List<Matcher.Hit> ungern = trefferInFeldern(ctx.matcher(), screen, T_UNGERN_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        List<Matcher.Hit> sicher = trefferInFeldern(ctx.matcher(), screen, T_SICHER_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        List<Matcher.Hit> machbar = trefferInFeldern(ctx.matcher(), screen, T_MACHBAR_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        List<Matcher.Hit> fastHits = trefferInFeldern(ctx.matcher(), screen, T_FAST_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        List<Matcher.Hit> pauseHits = trefferInFeldern(ctx.matcher(), screen, T_PAUSE_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        List<Matcher.Hit> clanTreffer = trefferInFeldern(ctx.matcher(), screen, T_CLAN_PREFIX, schildFelder, groessen, Matcher.DEFAULT_THRESHOLD);
        if (de.hustlebot.core.ArenaRegelnNeu.an()) listenNamenFrisch(ctx);   // Listen-Netz (Autor 28.10.): Bildnamen aktuell halten
        // Fingerabdruecke: alle ar_name_-Bilder mit ihrem Treffer (lockerere Schwelle, der
        // Grund des Schilds wechselt mit dem Platz). Auch sie zaehlen nur auf einem Schild (Schleife unten).
        Map<Matcher.Hit, String> namensbilder = new LinkedHashMap<>();
        for (Matcher.Hit h : trefferInFeldern(ctx.matcher(), screen, T_NAME_PREFIX, schildFelder, groessen, NAME_SCHWELLE)) {
            namensbilder.put(h, de.hustlebot.core.Namensbilder.name(h.name().substring(T_NAME_PREFIX.length())));   // lesbarer Name (11.10.)
        }
        boolean neueBilder = false;

        // Der Ausschnitt richtet sich nach dem SCHILD, nicht nach der
        // Vorlage.
        //
        // Vorher war er so breit wie das gefundene Wort "Reward:" plus
        // sechzehn Punkte. Beim neu aufgenommenen ar_reward ist das Wort
        // enger zugeschnitten -- und damit war der Ausschnitt schmaler als
        // das Schild. Im Protokoll stand deshalb "Not in cl" statt "Not in
        // clan" und einmal sogar der Clan als Name, weil die Namenszeile
        // ganz herausfiel.
        //
        // Ein Schild ist rund vierzehn Prozent der Fensterbreite. Das gilt
        // unabhaengig davon, wie eng die Vorlage geschnitten wurde.
        breiteZuletzt = ctx.width();
        hoeheZuletzt = ctx.height();
        // 29.10. (nur mit arena-regeln-neu.txt "an"): Autors Listenbilder ueber die WEISSE SCHRIFT im Namensfeld
        // vergleichen (ListenBildVergleich) -- an 1315 echten Schildern 67/67 Auftritte erkannt, 0 falsche; der farbige
        // Vergleich oben schaffte 56/67 (Xenos 7/11: Hintergrund des Schilds wechselt). Die reinen Anzeige-Bilder
        // ("_auto") zaehlen nicht.
        List<java.nio.file.Path> weissListe = de.hustlebot.core.ArenaRegelnNeu.an()
                ? de.hustlebot.core.ArenaVorlagenDoppelt.listenBilder(ctx.matcher().templateRoot()).stream()
                        .filter(p -> !de.hustlebot.core.ArenaListenOrdner.istAuto(p.getFileName().toString())).toList()
                : List.of();
        for (Matcher.Hit r : rewards) {
            int b = schildBreite;
            int x = r.centerX() - b / 2;
            int y = r.y() - SCHILD_HOEHE;

            // Das eigene Schild ist kein Gegner.
            if (inFeld(eigene, x, y, b, SCHILD_HOEHE)) continue;

            // Im Schild stehen untereinander: Name, Clan, Punkte. Die
            // Texterkennung gibt sie als eigene Zeilen zurueck -- das ist
            // stabiler, als Pixelabstaende zu raten.
            // Mit kyrillischem Nachlesen (01.10.) -- dieselbe Lesart wie im Vergleichsfenster,
            // sonst passen die Namen der Pyramide nicht zu denen im Notizblock.
            // Autor 30.10.: "Die Namen ein bisschen schneller." Beim Zuschauen wird dasselbe Namensbild nicht in jeder Stage neu gelesen.
            String beobFp = beobNamensbild(bild, ctx, r.centerX(), r.y());
            String[] gemerkt = beobFp == null ? null : beobNamen.get(beobFp);
            if (gemerkt != null) beobNamenTreffer++;
            List<String> zeilen = gemerkt != null ? List.of() : ArenaLeser.liesNamenZeilen(bild, x, y, b, SCHILD_HOEHE);
            String name, clan;
            boolean nameAusBand = false;
            if (gemerkt != null) {
                name = gemerkt[0];
                clan = gemerkt[1];
            } else {
                // Autor 01.11. (Pruefung 3.137): Name und Clan nach LAGE. Die Zeilen der Texterkennung ueber das ganze Schild haben keine Lage und lassen oft
                // eine weg ("~ЦИРИ~ / Myth Gods / 375" kam als ["Myth Gods", "375"]: der Clan rutschte auf Platz 1 und galt als Name). Darum werden das Clan-
                // band (die Zeile unter dem Namen) und, wo die Zeilen nicht eindeutig sind, das Namensband (der weisse Namenskasten) getrennt gelesen.
                // Steht die erste Zeile im Clanband, ist sie der Clan und der Name unlesbar -- der Clan rueckt nie nach oben.
                String clanBand = inFeld(bots, x, y, b, SCHILD_HOEHE) ? null : clanImBand(bild, ctx, r.centerX(), r.y());
                ArenaSchildLage.Ergebnis lage = ArenaSchildLage.teilen(zeilen, () -> nameImKasten(bild, ctx, r.centerX(), r.y()), clanBand);
                name = lage.name();
                clan = lage.clan();
                nameAusBand = lage.nameAusBand();
            }

            // 29.10. (nur mit arena-regeln-neu.txt "an"): den NAMEN aus seinem Kasten mit PaddleOCR lesen -- an 40 von Hand
            // geprueften Schildern 29 richtig statt 19 (ArenaLeser.nameImKastenPaddle). Der Clan bleibt aus der alten Lesung.
            if (gemerkt == null && de.hustlebot.core.ArenaRegelnNeu.an()) {
                int oben = r.y() - SCHILD_HOEHE;
                java.awt.Rectangle kasten = textKasten(bild, r.centerX() - (int) (ctx.width() * 0.046), oben + 12,
                        r.centerX() + (int) (ctx.width() * 0.070), oben + 33);
                String kastenName = ArenaLeser.nameImKastenPaddle(bild, kasten);
                if (!kastenName.isBlank() && !unleserlich(kastenName)
                        && !de.hustlebot.core.NamensVergleich.keinName(kastenName)
                        && !de.hustlebot.core.NamensVergleich.gleicherName(kastenName, name)) {
                    Log.debug("Arena", "Schild bei " + r.centerX() + "," + r.y() + ": Name aus dem Kasten \"" + kastenName
                            + "\" statt \"" + name + "\".");
                    if (!clan.isBlank() && de.hustlebot.core.NamensVergleich.gleicherName(kastenName, clan)) clan = "";
                    name = kastenName;
                    nameAusBand = true;
                }
            }

            // Rutscht die Clanzeile nach oben (Name in Zierschrift nicht
            // gelesen), stand bisher "Not in Clan" oder "Not" als NAME da und
            // die Punktezahl "286." als Clan (Liste 25.09., 05:32). Dann ist
            // der Name eben unbekannt -- besser als ein falscher Gegner.
            if (de.hustlebot.core.NamensVergleich.keinName(clan)) clan = "";
            // Pruefung 31.10.: "герцогиня | 218", "Инквизитор | 66O": ohne Clan steht die Punktezahl in der zweiten Zeile. Das ist kein Clan.
            if (de.hustlebot.core.ArenaRegelnNeu.an() && pokalZeile(clan)) clan = "";
            if (de.hustlebot.core.NamensVergleich.keinName(name)) {
                if (clan.isEmpty() && !name.isBlank()
                        && !de.hustlebot.core.NamensVergleich.form(name).chars()
                                .allMatch(Character::isDigit)) {
                    clan = name;   // "Not in clan" gehoert in die Clanzeile
                }
                name = "";
            }
            if (beobFp != null && gemerkt == null && !name.isBlank()) {
                if (beobNamen.size() > 300) beobNamen.clear();
                beobNamen.put(beobFp, new String[]{name, clan});
            }

            // Autor 24.10.: eigene Schrifterkennung -- das Namensbild des Schilds zerlegen (Fingerabdruck, ~1-2 ms, keine
            // Texterkennung) und aus einer sauberen Windows-Lesung die Buchstaben lernen. Konnte Windows den Namen nicht
            // lesen, kennt der Notizblock den Spieler aber am Namensbild, gilt der Name aus dem Notizblock.
            boolean botSchild = inFeld(bots, x, y, b, SCHILD_HOEHE) && !de.hustlebot.core.NamensVergleich.siehtMenschlichAus(name);
            Glyphenschrift.Ergebnis namensbild = botSchild ? null : namensbildErkennen(bild, ctx, r.centerX(), r.y(), name);
            if (namensbild != null) {
                String[] nc = buch.wiedererkennen(name, clan, -1, namensbild);
                // Autor 01.11.: steht im Notizblock unter diesem Namensbild ein Eintrag, dessen NAME der Clan ist ("V Myth Gods"), wird er nicht uebernommen.
                if (unleserlich(name) && nc[0] != null && !nc[0].isBlank() && !nc[0].equals(name)
                        && !de.hustlebot.core.Gegnerbuch.nameIstClan(nc[0], nc[1] == null ? "" : nc[1]) && !buch.nurAlsClanBekannt(nc[0])) {
                    Log.info("Arena", Sprache.t("Shield at " + r.centerX() + "," + r.y() + ": \"" + name + "\" not readable -- it is "
                                    + nc[0] + " (same name picture as in the notebook).",
                            "Schild bei " + r.centerX() + "," + r.y() + ": \"" + name + "\" nicht lesbar -- es ist " + nc[0]
                                    + " (gleiches Namensbild wie im Notizblock)."));
                    name = nc[0];
                    if (clan.isBlank() || unleserlich(clan)) clan = nc[1] == null ? "" : nc[1];
                }
            }

            // Autor 01.11.: die CLAN-ZEILE ist nie ein Name. Steht als "Name" der Clan (oder ein Stueck davon) da -- der Name selbst war unlesbar (Zierschrift,
            // chinesisch) --, gilt der Name als unlesbar; das Namensbild (Fingerabdruck) bleibt die Identitaet. Vorher kam "Myth Gods" (Clan von ~ЦИРИ~) als Name
            // durch und bekam die Bilanz seines Notizblock-Eintrags (9 von 10): Stage 4 angegriffen, verloren.
            if (!nameAusBand && !name.isBlank() && nameIstClanZeile(name, clan)) {   // aus dem Namensband = echter Name (FLIMSY), nur der Clan fehlt
                Log.debug("Arena", "Schild bei " + r.centerX() + "," + r.y() + ": \"" + name + "\" ist die Clan-Zeile, kein Name -- Name gilt als unlesbar.");
                if (clan.isBlank()) clan = name;
                name = "";
            }
            // Fingerabdruck: ein bekanntes Namensbild im Schild schlaegt die Texterkennung;
            // ein unleserlicher Name ohne bekanntes Bild bekommt jetzt eins.
            String bildName = null;
            for (var e : namensbilder.entrySet()) {
                Matcher.Hit h = e.getKey();
                if (h.centerX() >= x && h.centerX() <= x + b && h.centerY() >= y && h.centerY() <= y + SCHILD_HOEHE) {
                    bildName = e.getValue();
                    break;
                }
            }
            if (bildName != null) {
                // Autor 01.11.: "Bild-1024-032130" ist nur ein Platzhalter. Liest der Bot einen glaubhaften Namen ("元素神": kein Clan, kein Bruchstueck, keine
                // Fehlform), gilt er -- im Protokoll, im Seitenfenster und im Notizblock; das Namensbild bleibt der Anker (Bilanz, Sperren).
                if (bildName.startsWith("Bild-") && platzhalterErsetzbar(name, clan)) {
                    if (de.hustlebot.core.Namensbilder.zuordnen(bildName, name)) {
                        Log.info("Arena", Sprache.t("Name picture " + bildName + " is called \"" + name + "\" from now on.",
                                "Namensbild " + bildName + " heisst ab jetzt \"" + name + "\"."));
                    }
                } else {
                    if (!name.equals(bildName)) Log.debug("Arena", "Schild bei " + r.centerX() + "," + r.y()
                            + ": Namensbild " + bildName + " erkannt (gelesen: \"" + name + "\").");
                    name = bildName;
                }
            } else if (unleserlich(name) && rundeZuletzt <= 1
                    && !inFeld(bots, x, y, b, SCHILD_HOEHE)              // "Not in clan" kommt nie wieder
                    && fingerabdruckAbgelehnt.add(r.centerX() / 20 + "," + r.y() / 20)) {   // je Schild ein Versuch
                String neu = fingerabdruckAnlegen(bild, ctx, r.centerX(), r.y(), name);
                if (neu != null) {
                    name = neu;
                    neueBilder = true;
                }
            }

            // Auf Zuruf (Autor, 02.10.: "Flimsy in ar_machbar"): Name aus arena-aufnehmen.txt.
            if (!name.isBlank() && aufZurufAufnehmen(bild, ctx, r, name)) neueBilder = true;

            // "+225" / "+150" ist weisse Schrift mit dunklem Rand: die normale Lesung fand sie auf KEINEM
            // Schild -- jeder Gegner galt als 150, 225er-Ziele wurden nie bevorzugt (02.10., Laeufe 1-8).
            // Mit der Weiss-Lesung (wie bei der Kampfzeit) gelesen: 8 von 8 Schilden richtig.
            int hoeheZahl = (int) (ctx.height() * 0.05);
            // Beim Zuschauen: dieselben weissen Ziffern an derselben Stelle sind dieselbe Belohnung.
            long lohnHash = beobFp == null ? 0 : weissHash(bild, r.x() - r.width() / 4, r.y() + r.height(), r.width() + r.width() / 2, hoeheZahl);
            Long lohnGemerkt = lohnHash == 0 ? null : beobBelohnung.get(lohnHash);
            OptionalLong weiss = lohnGemerkt != null ? OptionalLong.empty() : ArenaLeser.zahlHinterWort(bild, r.x() - r.width() / 4, r.y() + r.height(),
                    r.width() + r.width() / 2, hoeheZahl, "Zahl");
            boolean weissGut = weiss.isPresent() && weiss.getAsLong() >= 50 && weiss.getAsLong() <= 300;
            long punkte = lohnGemerkt != null ? lohnGemerkt : weissGut
                    ? weiss.getAsLong()
                    : ArenaLeser.zahlUnter(bild, r, hoeheZahl).orElse(150);
            if (lohnHash != 0 && lohnGemerkt == null && weissGut) {
                if (beobBelohnung.size() > 300) beobBelohnung.clear();
                beobBelohnung.put(lohnHash, punkte);
            }

            boolean listeSperr = inFeld(sperr, x, y, b, SCHILD_HOEHE), listeUngern = inFeld(ungern, x, y, b, SCHILD_HOEHE);
            boolean listeSicher = inFeld(sicher, x, y, b, SCHILD_HOEHE), listeMachbar = inFeld(machbar, x, y, b, SCHILD_HOEHE);
            boolean listeFast = inFeld(fastHits, x, y, b, SCHILD_HOEHE), listePause = inFeld(pauseHits, x, y, b, SCHILD_HOEHE);
            if (!weissListe.isEmpty() && bild != null) {
                java.awt.Rectangle nameKasten = new java.awt.Rectangle(r.centerX() - (int) (ctx.width() * 0.046),
                        r.y() - SCHILD_HOEHE + 12, (int) (ctx.width() * 0.046) + (int) (ctx.width() * 0.070), 21);
                for (var t : de.hustlebot.core.ListenBildVergleich.alleTreffer(bild, nameKasten, weissListe)) {
                    // Autor 01.11.: "wither" traf das Listenbild "wicho" (sicher, 0,88): lesbar verschiedene Namen -- ein Bild, das weniger streng macht, gilt dann nicht.
                    if (de.hustlebot.core.ArenaListenNetz.fremderName(t.liste(), t.name(), name)) {
                        Log.info("Arena", Sprache.t("Shield at " + r.centerX() + "," + r.y() + " (\"" + name + "\"): list picture " + t.datei().getFileName()
                                        + " (" + String.format(java.util.Locale.ROOT, "%.2f", t.wert()) + ") ignored -- the name there is \"" + t.name() + "\", a different player.",
                                "Schild bei " + r.centerX() + "," + r.y() + " (\"" + name + "\"): Listenbild " + t.datei().getFileName()
                                        + " (" + String.format(java.util.Locale.ROOT, "%.2f", t.wert()) + ") nicht beachtet -- dort steht \"" + t.name() + "\", ein anderer Spieler."));
                        continue;
                    }
                    boolean neu = switch (t.liste()) {
                        case de.hustlebot.core.ArenaListenNetz.NIEMALS -> !listeSperr && (listeSperr = true);
                        case de.hustlebot.core.ArenaListenNetz.MEIDEN -> !listeUngern && (listeUngern = true);
                        case de.hustlebot.core.ArenaListenNetz.MACHBAR -> !listeMachbar && (listeMachbar = true);
                        case de.hustlebot.core.ArenaListenNetz.SICHER -> !listeSicher && (listeSicher = true);
                        case de.hustlebot.core.ArenaListenNetz.FAST -> !listeFast && (listeFast = true);
                        case de.hustlebot.core.ArenaListenNetz.PAUSE -> !listePause && (listePause = true);
                        default -> false;
                    };
                    if (neu) Log.info("Arena", Sprache.t("Shield at " + r.centerX() + "," + r.y() + " (\"" + name + "\"): list picture "
                                    + t.datei().getFileName() + " recognised by its white letters (" + String.format(java.util.Locale.ROOT, "%.2f", t.wert()) + ").",
                            "Schild bei " + r.centerX() + "," + r.y() + " (\"" + name + "\"): Listenbild " + t.datei().getFileName()
                                    + " an der weissen Schrift erkannt (" + String.format(java.util.Locale.ROOT, "%.2f", t.wert()) + ")."));
                }
                // Zwei Listen zugleich: die strengere gilt (niemals > Pause > meiden > machbar > fast sicher > sicher).
                if (listeSperr || listePause || listeUngern) { listeSicher = false; listeMachbar = false; listeFast = false; }
                if (listeSperr) { listeUngern = false; listePause = false; }
                if (listePause) listeUngern = false;
                if (listeMachbar) { listeSicher = false; listeFast = false; }
                if (listeFast) listeSicher = false;
            }
            // Listen-Netz (Autor 28.10., nur mit arena-regeln-neu.txt "an"): trifft das Namensbild nicht, die Liste am Namen
            // des Bildes erkennen (Fehlquote der Bilder in den Logs: niemals 26 %, machbar 14 %, sicher 8 %). Ein Bild-Treffer
            // geht vor; bei einem Namen auf zwei Listen gilt die strengere (niemals > meiden > machbar > sicher).
            if (!listeSperr && !listeUngern && !listeSicher && !listeMachbar && !listeFast && !listePause && !name.isBlank()
                    && de.hustlebot.core.ArenaRegelnNeu.an()) {
                String netzListe = listenNetz(name, clan, ctx);
                if (netzListe != null) {
                    listeSperr = netzListe.equals(de.hustlebot.core.ArenaListenNetz.NIEMALS);
                    listeUngern = netzListe.equals(de.hustlebot.core.ArenaListenNetz.MEIDEN);
                    listeMachbar = netzListe.equals(de.hustlebot.core.ArenaListenNetz.MACHBAR);
                    listeSicher = netzListe.equals(de.hustlebot.core.ArenaListenNetz.SICHER);
                    listeFast = netzListe.equals(de.hustlebot.core.ArenaListenNetz.FAST);
                    listePause = netzListe.equals(de.hustlebot.core.ArenaListenNetz.PAUSE);
                }
            }
            // Autor 01.11. (Pruefung 3.137): ein Spieler, der auf "niemals" steht, wird durch eine Teil-Lesung nie "sicher" -- ist der gelesene Name nur der
            // hintere Teil eines gesperrten Namens ("enos" von "Xenos"), ist es dieser Spieler. Gesperrt sind die Namen der "niemals"-Bilder und alle, die in
            // diesem Turnier als gesperrt gelesen wurden.
            if (!listeSperr && !name.isBlank()) {
                String voll = niemalsTeilVon(name, ctx);
                if (voll != null) {
                    Log.info("Arena", Sprache.t("Shield at " + r.centerX() + "," + r.y() + ": \"" + name + "\" is only the end of \"" + voll
                                    + "\" (never) -- stays \"never\".",
                            "Schild bei " + r.centerX() + "," + r.y() + ": \"" + name + "\" ist nur das Ende von \"" + voll
                                    + "\" (niemals) -- bleibt \"niemals\"."));
                    name = voll;
                    listeSperr = true;
                    listeUngern = listeSicher = listeMachbar = listeFast = listePause = false;
                }
            }
            if (listeSperr && !name.isBlank()) niemalsGelesen.add(name);
            boolean pauseAbgelaufen = listePause && pauseVorbei(name, ctx);
            if (pauseAbgelaufen) listeUngern = true;
            Schild schild = new Schild(
                    r.centerX(), r.y(), r.width(),
                    name, clan,
                    inFeld(bots,   x, y, b, SCHILD_HOEHE) && !de.hustlebot.core.NamensVergleich.siehtMenschlichAus(name),   // Zahlen/Zeichen = echter Spieler (08.10.)
                    punkte,
                    listeSperr || (listePause && !pauseAbgelaufen),
                    listeUngern,
                    listeSicher && !abgestuft(name),
                    listeMachbar || (listeSicher && abgestuft(name)),
                    inFeld(clanTreffer, x, y, b, SCHILD_HOEHE), false, listeFast, listePause);
            Schild mitListe = mitAutoListe(schild);
            liste.add(mitListe);
            // Autor 01.11.: das Lesen dauert 5-6 s, der Stand gilt nur 5 s (ArenaStand.HALTBAR_MS) -- die Rahmen gingen mitten im Lesen aus. Der Bot-Faden klickt
            // waehrenddessen nichts; nach jedem Schild den Stand dieser Stage frisch halten (andere Stage: wird nicht erneuert, er verfaellt wie bisher).
            if (!BEOB_LESER.equals(Thread.currentThread().getName()) && de.hustlebot.schicht.ArenaStand.an()) {
                de.hustlebot.schicht.ArenaStand.auffrischen(rundeZuletzt);
            }
            if (mitListe.sicher() && !mitListe.name().isBlank()) sicherNamen.add(namensSchluessel(mitListe.name()));
            if (namensbild != null && !namensbild.leer() && !mitListe.name().isBlank()) {
                namensbildVon.put(namensSchluessel(mitListe.name()), namensbild);
            }
        }
        if (neueBilder) ctx.matcher().loadAll();   // die neuen Fingerabdruecke gleich kennen

        // Stage 1: die Plaetze merken (Autor, 01.10.). Spaeter verschiebt sich die Pyramide,
        // der Bot findet die Gegner dann ueber den Namen wieder.
        if (rundeZuletzt <= 1 && platzInStage1.isEmpty() && liste.size() >= 6) {
            StringBuilder b = new StringBuilder();
            for (Schild s : liste) {
                if (s.name().isBlank()) continue;
                int platz = platzAusLage(s.x(), s.y(), ctx.width(), ctx.height());
                platzInStage1.put(namensSchluessel(s.name()), platz);
                b.append(platz).append(' ').append(s.name()).append(", ");
            }
            if (!platzInStage1.isEmpty()) {
                Log.info("Arena", Sprache.t("Places in Stage 1 (by trophies): " + b.substring(0, b.length() - 2)
                        + (meinPlatz > 0 ? "; I am at place " + meinPlatz : "; own place not recognised")
                        + ". Stage 1 looks at places " + STAGE1_VON + "-" + STAGE1_BIS
                        + ", the others come in Stage 2. Extra points: " + ArenaExtras.text() + ".",
                        "Plaetze in Stage 1 (nach Pokalen): " + b.substring(0, b.length() - 2)
                        + (meinPlatz > 0 ? "; ich auf Platz " + meinPlatz : "; eigener Platz nicht erkannt")
                        + ". Stage 1 sieht sich die Plaetze " + STAGE1_VON + "-" + STAGE1_BIS
                        + " an, die uebrigen kommen in Stage 2. Zusatzpunkte: " + ArenaExtras.text() + "."));
            }
        }

        // Unveraenderlich speichern und herausgeben. Heute aendert kein
        // Aufrufer die Liste, aber nichts sagt das zu -- und wer spaeter
        // gesperrte Gegner herausnimmt statt sie zu filtern, wuerde sonst
        // den Zwischenspeicher beschaedigen.
        schilderCache = List.copyOf(liste);
        for (Schild s : liste) if (s.botClan() && !s.name().isBlank()) botsInPyramide.add(s.name());
        schilderCacheRunde = rundeZuletzt;
        // Messung 30.10.: Bei offenem Protokoll fehlen Schilder -- so ein Bild gilt nicht 5 s lang.
        schilderCacheAt = ctx.visibleIfKnown(screen, T_LOG_CLOSE) ? 0 : System.currentTimeMillis();
        return schilderCache;
    }

    /**
     * Waehlt den Gegner.
     *
     * Runde 1: jeder gibt hoechstens 150, die Punktzahl entscheidet also
     * nichts. Genommen wird, wer am sichersten faellt -- die Bots mit
     * "Not in clan". Die bleiben ohnehin unten und waeren spaeter nie 225
     * wert; sie jetzt zu verbrauchen kostet nichts.
     *
     * Ab Runde 2 zaehlt die Punktzahl mit. Ein Bot, der 225 gibt, ist das
     * Beste, was es gibt.
     *
     * Die harte Sperrliste greift immer, auch als letzter Ausweg. Die
     * weiche nur, wenn sonst nichts mehr da ist.
     */
    /** Gegen diesen Namen mindestens 3 Niederlagen und kein Sieg (alle Clan-Schreibweisen zusammen)? */
    /**
     * Bilanz {Siege, Kaempfe} gegen diesen Gegner ueber alle Schreibweisen im Notizblock. Hat der Bot ihn in diesem
     * Turnier schon an der Kampfkraft erkannt ("same power as ..."), zaehlt auch der Name, unter dem er dort steht.
     */
    private int[] gesamtBilanz(Schild s) {
        Long kraftGelesen = staerken.get(namensSchluessel(s.name()));
        long kraft = kraftGelesen != null ? kraftGelesen : 0;
        // Autor 24.10.: auch die Notizen mit demselben Namensbild (Fingerabdruck der Glyphenschrift) zaehlen.
        int[] b = buch.bilanzNachName(s.name(), kraft, fingerabdruckZu(s.name()));
        String bekannt = namensKarte.get(vergleichsform(s.name()));
        if (bekannt != null) {
            int strich = bekannt.indexOf('|');
            int[] b2 = buch.bilanzNachName(strich > 0 ? bekannt.substring(0, strich) : bekannt, kraft);
            if (b2[1] > b[1]) b = b2;
        }
        return b;
    }

    // ---- Top-3-Rechnung (Autor 21.10.) ---------------------------------------------------------------------
    /** Turnierpunkte je Platz (1..10) dieser Runde, -1 = nicht gelesen; null = noch nicht gelesen. */
    private long[] tabelle;
    /** Gruener Haken je Platz: hat in dieser Runde schon angegriffen. */
    private final boolean[] haken = new boolean[11];
    private int tabelleRunde = -1;
    private long tabelleUm;

    /** Liest die Turnierpunkte aller Plaetze (hoechstens alle 4 s) -- ab Stage 4, dort zaehlt die Rechnung. */
    private void tabelleLesen(Mat screen, BotContext ctx, BufferedImage bild) {
        if (rundeZuletzt < ArenaTabelle.RECHNUNG_AB || bild == null) return;
        long jetzt = System.currentTimeMillis();
        if (tabelleRunde == rundeZuletzt && jetzt - tabelleUm < 4_000) return;
        // eigene (gelbe) Zahl nur mit der Texterkennung lesen, wenn die eigene Summe unsicher ist
        long[] neu = ArenaTabelle.punkteLesen(bild, turnierPunkteFehlen ? aktuellerPlatz : 0);
        // Autor 22.10. (Turnier 22:19, Platz 4): 50 s vor der Wahl war die Tabelle vollstaendig, beim letzten Lesen fehlten
        // drei Plaetze -- die Top-3-Rechnung lief nicht, der Bot nahm einen staerkeren Gegner (18 s, 141 Punkte) statt eines
        // schwachen (Platz 3). Innerhalb einer Runde bleibt die Reihenfolge, und Punkte steigen nur: Unlesbares wird aus
        // den frueheren Lesungen derselben Runde ergaenzt, ein Haken bleibt stehen.
        boolean selbeRunde = tabelle != null && neu != null && tabelleRunde == rundeZuletzt && neu.length == tabelle.length;
        if (selbeRunde) {
            for (int i = 0; i < neu.length; i++) neu[i] = Math.max(neu[i], tabelle[i]);
        } else {
            java.util.Arrays.fill(haken, false);
        }
        tabelle = neu;
        for (Matcher.Hit h : ctx.matcher().findAll(screen, T_CHECK)) {
            int platz = ArenaTabelle.platzBei(h.centerX() / (double) ctx.width(), h.centerY() / (double) ctx.height());
            if (platz > 0) haken[platz] = true;
        }
        // Autor 22.10.: "Wie viele Arena-Punkte hatten die Gegner am Ende, wie viele haben mir zu Platz 1 gefehlt?" -- die
        // Tabelle je Runde ins Protokoll (einmal je Runde, danach nur bei Aenderung).
        if (tabelle != null) {
            String text = tabelleText();
            if (!text.equals(tabelleGemeldet)) {
                tabelleGemeldet = text;
                Log.info("Arena", Sprache.t("Table round " + rundeZuletzt + " (tournament points per place, I am at place "
                        + aktuellerPlatz + "): " + text, "Tabelle Runde " + rundeZuletzt + " (Turnierpunkte je Platz, ich auf Platz "
                        + aktuellerPlatz + "): " + text));
            }
        }
        tabelleRunde = rundeZuletzt;
        tabelleUm = jetzt;
    }

    private String tabelleGemeldet = "";

    /** "1: 812, 2: 760 ✓, ..." -- ✓ = hat in dieser Runde schon angegriffen, ? = nicht gelesen. */
    private String tabelleText() {
        StringBuilder b = new StringBuilder();
        for (int p = 1; p < tabelle.length && p <= 10; p++) {
            if (b.length() > 0) b.append(", ");
            b.append(p).append(": ").append(tabelle[p] >= 0 ? String.valueOf(tabelle[p]) : "?");
            if (p < haken.length && haken[p]) b.append(" ✓");
        }
        return b.toString();
    }

    /**
     * Die Wahl nach der Top-3-Rechnung, oder leer (Punkte nicht genug gelesen, oder alle Wahlen gleich gut -- dann
     * entscheidet die normale Wahl).
     */
    /**
     * Je anderer Platz der Tabelle (Reihenfolge wie endpunkteAndere): hatte dieser Spieler in Runde 1 mehr Pokale als du
     * (stand er in der Pyramide von Stage 1 ueber dir)? Unbekannt = true -- wie bisher zaehlt ein Gleichstand dann gegen dich.
     */
    private boolean[] mehrPokaleAndere() {
        List<Boolean> l = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            if (i == aktuellerPlatz || tabelle == null || tabelle[i] < 0) continue;
            Integer stage1 = null;
            for (Schild s : schilderCache) {
                if (s.name().isBlank() || platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt) != i) continue;
                stage1 = platzInStage1.get(namensSchluessel(s.name()));
                break;
            }
            // Autor 03.11.: "Ab 2300 Pokalen bin ich bei Gleichstand meistens der, der weiter oben ist; 2000 oder weniger ist wenig."
            // Die Reihenfolge in Stage 1 IST die Pokal-Reihenfolge genau dieser Gegner. Fehlt einer dort (Name nicht wiedergefunden),
            // zaehlt Autors eigener Platz: obere Haelfte = viele Pokale, dann geht ein Gleichstand vermutlich an ihn.
            if (stage1 != null && meinPlatz > 0) l.add(stage1 < meinPlatz);
            else l.add(meinPlatz <= 0 || meinPlatz > 5);
        }
        boolean[] b = new boolean[l.size()];
        for (int i = 0; i < b.length; i++) b[i] = l.get(i);
        return b;
    }

    /**
     * Autor 30.10.: "Ich will die Top-1-Quote erhoehen." Mit zwei 225er-Siegen wurde es 4 von 7 Mal Platz 1, mit hoechstens einem
     * 1 von 11. 225er ab 90 % Chance: 12 von 12 gewonnen, und ein Sieg bringt rund 63 Punkte mehr als ein 150er. Darum in den
     * Runden 3 und 4 der 225er, wenn seine Chance ab 90 % liegt und hoechstens 8 Prozentpunkte unter dem gewaehlten 150er.
     * Stichprobe klein -- nur mit arena-regeln-neu.txt "an". Die letzte Runde bleibt bei der genauen Rechnung.
     */
    static boolean lieber225er(double chance150, double chance225) {
        return chance225 >= 0.90 && chance225 >= chance150 - 0.08;
    }

    private Schild lieberSicherer225er(List<Schild> auswahl, int runde, Schild gewaehlt) {
        if (!de.hustlebot.core.ArenaRegelnNeu.an() || runde < 3 || runde >= LETZTE_RUNDE || gewaehlt.punkte() > 150) return gewaehlt;
        double cGewaehlt = wahlChance(gewaehlt, runde);
        Schild besser = null;
        double cBesser = 0;
        for (Schild s : auswahl) {
            double c = wahlChance(s, runde);
            if (s.punkte() > 150 && lieber225er(cGewaehlt, c) && c > cBesser) {
                besser = s;
                cBesser = c;
            }
        }
        if (besser == null) return gewaehlt;
        Log.info("Arena", Sprache.t("Top-1 rule (round " + runde + "): " + besser.wer() + " (" + besser.punkte() + ", " + Math.round(cBesser * 100)
                        + " %) instead of " + gewaehlt.wer() + " (" + gewaehlt.punkte() + ", " + Math.round(cGewaehlt * 100) + " %).",
                "Top-1-Regel (Runde " + runde + "): " + besser.wer() + " (" + besser.punkte() + ", " + Math.round(cBesser * 100)
                        + " %) statt " + gewaehlt.wer() + " (" + gewaehlt.punkte() + ", " + Math.round(cGewaehlt * 100) + " %)."));
        return besser;
    }

    /** So viele fruehere Kaempfe braucht die Rechnung, und so viele zaehlen hoechstens (Autor: "mit den letzten Angriffen"). */
    static final int LETZTE_KAEMPFE_AB = 3, LETZTE_KAEMPFE_BIS = 10;

    /**
     * Was ein Sieg gegen diesen Spieler bringen kann: je einer seiner letzten Kaempfe, mit dessen Dauer auf den jetzigen Lohn
     * umgerechnet (dieselbe Dauer gibt beim 150er und beim 225er verschiedene Punkte). Weniger als {@link #LETZTE_KAEMPFE_AB}
     * Kaempfe bekannt oder Schalter aus: leer, dann gilt wie bisher der eine Schaetzwert aus punkteNachDauer.
     */
    private double[] moeglichePunkte(Schild s) {
        if (!de.hustlebot.core.ArenaRegelnNeu.an()) return new double[0];
        List<Integer> dauern = buch.nachschlagen(s.name(), s.clan()).map(n -> n.letzteDauern(LETZTE_KAEMPFE_BIS)).orElse(List.of());
        if (dauern.size() < LETZTE_KAEMPFE_AB) dauern = aehnlicheDauern(s);
        if (dauern.size() < LETZTE_KAEMPFE_AB) return new double[0];
        double[] p = new double[dauern.size()];
        for (int i = 0; i < p.length; i++) {
            double lohn = punkteVorDauer(s, s.punkte());
            p[i] = lohn - dauerAbzug(lohn, dauern.get(i));
        }
        return p;
    }

    private List<String> lernZeilen = List.of();
    private long lernGelesenUm;

    /** Kampfdauern frueherer Siege gegen Gegner mit aehnlicher Siegchance (ArenaVergleich). Die Datei wird hoechstens alle 10 Minuten gelesen. */
    private List<Integer> aehnlicheDauern(Schild s) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - lernGelesenUm > 600_000) {
            lernGelesenUm = jetzt;
            try {
                lernZeilen = de.hustlebot.core.Dateien.zeilen(de.hustlebot.core.Ordner.bei("arena-lernen.txt"));
            } catch (Exception e) {
                lernZeilen = List.of();
            }
        }
        return ArenaVergleich.dauern(lernZeilen, s.punkte(), (int) Math.round(wahlChance(s, rundeZuletzt) * 100));
    }

    private Optional<Schild> top3Wahl(List<Schild> auswahl, int runde) {
        if (tabelle == null || tabelleRunde != runde || aktuellerPlatz <= 0 || auswahl.isEmpty()) return Optional.empty();
        int gelesen = 0;
        for (int i = 1; i <= 10; i++) if (i != aktuellerPlatz && tabelle[i] >= 0) gelesen++;
        if (gelesen < ArenaTabelle.MINDESTENS_GELESEN) return Optional.empty();
        long meine = tabelle[aktuellerPlatz] >= 0 ? tabelle[aktuellerPlatz] : turnierEndpunkte;
        int rest = LETZTE_RUNDE - runde;
        double[] andere = ArenaTabelle.endpunkteAndere(tabelle, haken, aktuellerPlatz, rest);
        double zukunft = rest * ArenaTabelle.JE_RUNDE_ICH;
        // Autor 22.10.: vorausdenken -- wer nach dem Angriff allein oben steht, holt nur noch 150er; Gleichstand = Pokale.
        double[] andereJetzt = ArenaTabelle.endpunkteAndere(tabelle, haken, aktuellerPlatz, 0);
        boolean[] mehrPokale = mehrPokaleAndere();
        Schild beste = null;
        double besterWert = -1, kleinster = Double.MAX_VALUE;
        StringBuilder text = new StringBuilder();
        for (Schild s : auswahl) {
            double chance = wahlChance(s, runde);
            double gewinn = punkteNachDauer(s, s.punkte());
            boolean leiter = leiterTest && runde >= 2 && runde <= 4 && s.punkte() > 150 && chance >= LEITER_AB_CHANCE
                    && gewinn >= LEITER_VON && gewinn <= LEITER_BIS;
            double[] moeglich = moeglichePunkte(s);
            double w = moeglich.length >= LETZTE_KAEMPFE_AB
                    ? ArenaTabelle.wahlWertVoraus(chance, meine, moeglich, andereJetzt, andere, mehrPokale, rest, leiter)
                    : ArenaTabelle.wahlWertVoraus(chance, meine, gewinn, andereJetzt, andere, mehrPokale, rest, leiter);
            if (moeglich.length >= LETZTE_KAEMPFE_AB && text.length() < 400) {
                text.append(String.format(java.util.Locale.ROOT, "  [%s: %s %.0f, %d %s]", s.wer(), Sprache.t("average", "Schnitt"), gewinn,
                        moeglich.length, Sprache.t("fights", "Kaempfe")));
            }
            if (leiter) text.append(String.format(java.util.Locale.ROOT, "  [Rang-Leiter: %s ~%.0f Punkte]", s.wer(), gewinn));
            kleinster = Math.min(kleinster, w);
            if (beste == null || w > besterWert + 0.005
                    || (Math.abs(w - besterWert) <= 0.005 && wert(s, runde) > wert(beste, runde))) {
                beste = s;
                besterWert = w;
            }
            if (text.length() < 400) {
                text.append(String.format(java.util.Locale.ROOT, "  %s (%d, %d %%): %s %d / %s %d.", s.wer(), s.punkte(),
                        Math.round(chance * 100), Sprache.t("win -> place", "Sieg -> Platz"),
                        ArenaTabelle.rang(meine + gewinn + ArenaTabelle.zukunft(meine + gewinn, andereJetzt, mehrPokale, rest), andere, mehrPokale),
                        Sprache.t("loss -> place", "Niederlage -> Platz"),
                        ArenaTabelle.rang(meine + ArenaTabelle.zukunft(meine, andereJetzt, mehrPokale, rest), andere, mehrPokale)));
            }
        }
        if (beste == null) return Optional.empty();
        // Autor 22.10.: "In der 5. Stage soll er versuchen, Top 1 zu bekommen -- Gegner bevorzugen, die mehr Punkte geben,
        // am besten 225, wenn die Siegchance nicht viel kleiner ist als bei Gegnern, die nur 215-224 geben." Fuehrt ein
        // Sieg zum selben Platz und liegt die Chance hoechstens 3 Prozentpunkte darunter: der mit mehr erwarteten Punkten.
        if (runde >= LETZTE_RUNDE) {
            double cBeste = wahlChance(beste, runde), gBeste = punkteNachDauer(beste, beste.punkte());
            int platzBeste = ArenaTabelle.rang(meine + gBeste, andere, mehrPokale);
            for (Schild s : auswahl) {
                if (s == beste) continue;
                double g = punkteNachDauer(s, s.punkte());
                if (g > gBeste + 0.5 && wahlChance(s, runde) >= cBeste - 0.03
                        && ArenaTabelle.rang(meine + g, andere, mehrPokale) <= platzBeste) {
                    Log.info("Arena", Sprache.t("Stage 5: " + s.wer() + " gives more points (" + Math.round(g) + " instead of "
                                    + Math.round(gBeste) + ") with almost the same chance -- taken instead of " + beste.wer() + ".",
                            "Stage 5: " + s.wer() + " gibt mehr Punkte (" + Math.round(g) + " statt " + Math.round(gBeste)
                                    + ") bei fast gleicher Chance -- statt " + beste.wer() + "."));
                    beste = s;
                    gBeste = g;
                }
            }
        }
        // Autor 30.10.: 05:10 Platz 4 -- in Runde 3 "Sieg -> Platz 4" um 23 Punkte, also die Wette auf 47 % statt Steffen 99 %.
        // Verloren, Endstand 668; mit dem 150er 818 und Platz 3 (der Zweite hatte 823).
        if (de.hustlebot.core.ArenaRegelnNeu.an()) {
            Schild sicher = auswahl.stream().max(java.util.Comparator.comparingDouble(s -> wahlChance(s, runde))).orElse(beste);
            double gSicher = punkteNachDauer(sicher, sicher.punkte());
            if (ArenaTabelle.keineWette(wahlChance(beste, runde), wahlChance(sicher, runde), rest, meine + gSicher, andere)) {
                Log.info("Arena", Sprache.t("No bet in round " + runde + ": " + sicher.wer() + " (" + Math.round(wahlChance(sicher, runde) * 100)
                                + " %) instead of " + beste.wer() + " (" + Math.round(wahlChance(beste, runde) * 100) + " %) -- top 3 stays within reach.",
                        "Keine Wette in Runde " + runde + ": " + sicher.wer() + " (" + Math.round(wahlChance(sicher, runde) * 100)
                                + " %) statt " + beste.wer() + " (" + Math.round(wahlChance(beste, runde) * 100) + " %) -- Top 3 bleibt erreichbar."));
                beste = sicher;
            }
        }
        beste = lieberSicherer225er(auswahl, runde, beste);
        if (leiterTest && runde >= 2 && runde <= 4 && beste.punkte() > 150) {
            double g = punkteNachDauer(beste, beste.punkte());
            if (g >= LEITER_VON && g <= LEITER_BIS && wahlChance(beste, runde) >= LEITER_AB_CHANCE) leiterAngewendet = true;
        }
        // Autor 03.11.: in der letzten Runde jede Chance auf Top 3 nutzen. Bringt keine Wahl vorsichtig gerechnet (Punkte nach
        // Dauer, Gleichstand nach Pokalen) Top 3, zaehlt der Gegner, mit dem es mit vollen Punkten noch reicht -- Platz 4-10
        // bringt nichts, ein sicherer 150er dort ist so viel wert wie eine Niederlage. Nur mit arena-regeln-neu.txt "an".
        if (runde >= LETZTE_RUNDE && de.hustlebot.core.ArenaRegelnNeu.an()) {
            final long ich = meine;
            boolean keinerSicher = auswahl.stream()
                    .noneMatch(s -> ArenaTabelle.rang(ich + punkteNachDauer(s, s.punkte()), andere, mehrPokale) <= 3);
            Schild chance3 = null;
            double besteC = 0;
            for (Schild s : keinerSicher ? auswahl : List.<Schild>of()) {
                double c = ArenaTabelle.letzteRundeChance(wahlChance(s, runde), meine, s.punkte(), andere, mehrPokale);
                if (c > besteC) {
                    chance3 = s;
                    besteC = c;
                }
            }
            if (chance3 != null) {
                boolean gleichstand = ArenaTabelle.rang(meine + chance3.punkte(), andere, mehrPokale) > 3;
                if (!namensSchluessel(chance3.name()).equals(zuletztGewaehlt)) {
                    Log.info("Arena", Sprache.t("Stage 5: no choice reaches the top 3 for sure (me " + meine + " points) -- places 4-10 bring nothing. "
                                    + chance3.wer() + " (" + chance3.punkte() + ", " + Math.round(wahlChance(chance3, runde) * 100)
                                    + " %) can still make it with a fight under 10 s" + (gleichstand ? " and a tie in your favour" : "") + " -- taken.",
                            "Stage 5: keine Wahl bringt sicher Top 3 (ich " + meine + " Punkte) -- Platz 4-10 bringt nichts. "
                                    + chance3.wer() + " (" + chance3.punkte() + ", " + Math.round(wahlChance(chance3, runde) * 100)
                                    + " %) reicht noch mit einem Kampf unter 10 s" + (gleichstand ? " und einem Gleichstand fuer dich" : "") + " -- genommen."));
                    fotoSerie("wahl");
                    zuletztGewaehlt = namensSchluessel(chance3.name());
                    listeSchreiben();
                }
                return Optional.of(chance3);
            }
        }
        if (besterWert - kleinster < 0.005) {
            // Idee 8 (A07, 22.10.): jede Wahl fuehrt zum selben Platz -- dann zaehlen die Punkte nicht, nur der Sieg: der Gegner
            // mit der hoechsten Siegchance (sonst zog chance^2 x Punkte einen 225er mit 85 % einem 150er mit 97 % vor).
            Schild sicherster = auswahl.stream().max(java.util.Comparator.comparingDouble(s -> wahlChance(s, runde))).orElse(beste);
            // Nacht 30.10. (nur mit arena-regeln-neu.txt "an"): Der Sieger-Modus nahm 150er, obwohl 225er fast so sicher waren --
            // je Runde bis 75 Punkte verschenkt, und die Rechnung kennt von den Gegnern nur Mindestwerte. Wie die Stage-5-Regel:
            // bis 3 Prozentpunkte unter dem Sichersten zaehlen die Punkte nach Dauer.
            if (de.hustlebot.core.ArenaRegelnNeu.an()) {
                double cSicher = wahlChance(sicherster, runde), gSicher = punkteNachDauer(sicherster, sicherster.punkte());
                for (Schild s : auswahl) {
                    double g = punkteNachDauer(s, s.punkte());
                    if (g > gSicher + 0.5 && wahlChance(s, runde) >= cSicher - 0.03) {
                        sicherster = s;
                        gSicher = g;
                    }
                }
                sicherster = lieberSicherer225er(auswahl, runde, sicherster);
            }
            if (!namensSchluessel(sicherster.name()).equals(zuletztGewaehlt)) {
                Log.info("Arena", Sprache.t("Top-3 calculation (round " + runde + "): every choice ends on the same place -- winner mode, "
                                + "the safest opponent: " + sicherster.wer() + " (" + Math.round(wahlChance(sicherster, runde) * 100) + " %).",
                        "Top-3-Rechnung (Runde " + runde + "): jede Wahl endet auf demselben Platz -- Sieger-Modus, der sicherste "
                                + "Gegner: " + sicherster.wer() + " (" + Math.round(wahlChance(sicherster, runde) * 100) + " %)."));
                zuletztGewaehlt = namensSchluessel(sicherster.name());
                listeSchreiben();
            }
            return Optional.of(sicherster);
        }
        if (!namensSchluessel(beste.name()).equals(zuletztGewaehlt)) {
            Log.info("Arena", Sprache.t("Top-3 calculation (round " + runde + "): me " + meine + " points, now place "
                    + ArenaTabelle.rang(meine + zukunft, andere) + "." + text + " Choice: " + beste.wer() + ".",
                    "Top-3-Rechnung (Runde " + runde + "): ich " + meine + " Punkte, jetzt Platz "
                    + ArenaTabelle.rang(meine + zukunft, andere) + "." + text + " Wahl: " + beste.wer() + "."));
            fotoSerie("wahl");
            zuletztGewaehlt = namensSchluessel(beste.name());
            listeSchreiben();
        }
        return Optional.of(beste);
    }

    // ---- Arena-Beobachter (Autor 21.10.) --------------------------------------------------------------------
    // "Wenn ich selbst Arena spiele (nicht der Bot), schaut der Bot im Hintergrund zu, klickt nie, zaehlt Siege und
    // Niederlagen je Gegner und setzt die Listen." Die Listen rechnet autoListe/dreimalVerloren aus dem Notizblock --
    // der Beobachter muss also nur jeden Kampf richtig eintragen. Im Vergleichsfenster (der Spieler tippt einen Gegner
    // an) Name, Clan und Kraft merken; kommt danach Victory/Defeat, zaehlt der Kampf fuer genau diesen Gegner.

    private static final de.hustlebot.core.Schalterdatei BEOBACHTER = new de.hustlebot.core.Schalterdatei(
            de.hustlebot.core.Ordner.bei("arena-beobachter.txt"), "Arena-Beobachter", true);

    public static boolean beobachterAn() {
        return BEOBACHTER.lesen();
    }

    public static void beobachterSetzen(boolean an) {
        BEOBACHTER.setzen(an, Sprache.t("Arena watcher: count your own arena fights (on/off)",
                "Arena-Beobachter: eigene Arena-Kaempfe mitzaehlen (an/aus)"));
    }

    /** So lange nach dem Vergleichsfenster gehoert ein Ergebnis noch zu diesem Gegner. */
    static final long BEOB_FRIST_MS = 3 * 60_000L;
    private String[] beobGegner;
    private long beobGegnerUm, beobGelesenUm, beobStaerke = -1, beobErgebnisSeit, beobPunkte = -1, beobDauer = -1;
    private boolean beobSieg, beobErledigt = true;

    // Arena-Schicht beim Selbstspielen (Autor 28.10.): die zuletzt bestimmten Plaetze.
    private List<de.hustlebot.schicht.ArenaStand.Platz> beobPlaetze = List.of();
    private long beobSchichtGelesenUm;
    /** So oft bestimmt der Beobachter die Listen je Platz neu (nur Bildvergleich, keine Texterkennung). */
    private static final long BEOB_SCHICHT_MS = 30_000, BEOB_SCHICHT_MIN_MS = 5_000;   // 30.10.: Autor spielt selbst -- nicht dauernd lesen

    /**
     * Autor 28.10.: "die Schicht soll da sein, wenn ich aktiv Arena spiele". Steht die Pyramide da, bekommt jeder
     * Gegner seinen Rahmen -- nach den festen Plaetzen und Autors Listenbildern, OHNE Texterkennung (Review 28.10.:
     * 9 s Lesen liess den Beobachter Autors Kaempfe verpassen) und ohne den Rundenstand des Bots anzufassen.
     * Autor 30.10.: "Alle Gegner mit Chance zeigen", auch im Seitenfenster: das Lesen der Schilder laeuft jetzt in einem eigenen Faden,
     * der Beobachter sieht derweil weiter hin. Bis das erste Ergebnis da ist, zeigt die Schicht die Listen wie bisher. Klickt nie.
     */
    private void beobachtenPyramide(Mat screen, BotContext ctx, long jetzt) {
        if (!de.hustlebot.core.ArenaSchalter.an()) return;
        // Autor 30.10.: "geht die ganze Zeit auf und zu": der Grund steht jetzt im Protokoll, und ein einzelnes Bild ohne Pyramide loest nichts aus.
        // Gemessen 30.10.: ein Blick dauerte 150-190 ms, weil alles, was nicht zu sehen ist, im ganzen Bild gesucht wird. Chat, Battle-Log und
        // Ergebnisfenster darum nur alle 0,2 s, direkt nach einem Klick und solange der Layer aus ist; die Pyramide bei jedem Blick.
        boolean alles = beobAusGrund != null || jetzt - beobAllesUm >= BEOB_ALLES_MS || jetzt - beobKlickGesehen < BEOB_NACH_KLICK_MS;
        if (alles) beobAllesUm = jetzt;
        String aus = alles && chatOffenFuerSchicht(screen, ctx) ? "Chat"
                : !(ctx.visible(screen, T_SELECT) || ctx.visible(screen, T_NEXT_STAGE)) ? "keine Pyramide"
                : alles && ctx.visibleIfKnown(screen, T_LOG_CLOSE) ? "Battle-Log"
                : alles && ergebnisFensterOffen(screen, ctx) ? "Ergebnisfenster" : null;
        if (alles) beobChatPruefen("Chat".equals(aus), jetzt);
        if (aus != null) {
            if (!aus.equals(beobAusGrund)) {
                Log.debug("Arena", "Beobachter: Layer aus -- " + aus + ".");
                beobAusGrund = aus;
                beobAusSeit = jetzt;
            }
            if (aus.equals("keine Pyramide") && jetzt - beobAusSeit < BEOB_AUS_GEDULD_MS) return;
            // Chat und Battle-Log ordnen die Pyramide nicht um: das Gelesene gilt danach weiter.
            beobSchichtAus(aus.equals("Chat") || aus.equals("Battle-Log"));
            return;
        }
        beobAusGrund = null;
        beobLetztesPyramidenBild = ctx.frame();
        schichtBefehleAusfuehren(ctx);
        beobOffeneListen(screen, ctx);
        boolean neu = !beobPyramide;
        beobPyramide = true;
        // Autor 30.10.: schon angegriffen? Dann steht oben "Next stage in" statt "Select the next opponent", und der Bot schlaegt niemanden mehr vor.
        boolean stageFertig = !ctx.visible(screen, T_SELECT);
        de.hustlebot.schicht.ArenaStand.stageFertig(stageFertig);
        // Eine neue Stage zaehlt erst, wenn sie zweimal hintereinander so gelesen wurde.
        if (alles) {
            int gelesen = beobRundeLesen(screen, ctx);
            if (beobRundeZuletzt == 0 || (gelesen != beobRundeZuletzt && gelesen == beobRundeKandidat)) {
                // Stage 1 nach einer spaeten Stage: die Arena ist vorbei -- Autors Kommentare werden ein Paket.
                if (gelesen == 1 && beobRundeZuletzt >= 3) de.hustlebot.core.KommentarPakete.arenaZuEnde();
                beobRundeZuletzt = gelesen;
            }
            beobRundeKandidat = gelesen;
        }
        if (beobRundeZuletzt > 0) de.hustlebot.schicht.ArenaStand.rundeGelesen(beobRundeZuletzt);   // Autor 01.11.: die Stage sofort anzeigen, nicht erst nach dem Lesen der Schilder
        // Neue Stage: die Pyramide hat sich umgeordnet, die gelesenen Namen stimmen nicht mehr (nach einem Kampf macht das beobZaehlen).
        // Autor 30.10.: die Lage der "Reward:"-Schilder taugt dafuer nicht: sie schwankte von Blick zu Blick, alle 10-15 s wurde alles
        // verworfen, der Layer war grau und das Seitenfenster ging zu.
        int lage = beobRundeZuletzt;
        if (lage != beobLage) {
            beobStand = null;
            beobLetzteSchilder = null;
        }
        if (beobNeuRechnen && beobLetzteSchilder != null && beobLiest.compareAndSet(false, true)) {
            beobNeuRechnen = false;
            beobFaden(screen, ctx, this::beobNeuBerechnen);
        }
        de.hustlebot.schicht.ArenaStand.Stand s = beobStand;
        long seitLesen = jetzt - beobSchichtGelesenUm;
        // Nach dem Angriff aendert sich bis zur naechsten Stage nichts mehr, was er zeigen muesste.
        boolean faellig = s != null ? seitLesen >= BEOB_SCHICHT_MS && !stageFertig : neu || seitLesen >= BEOB_SCHICHT_MIN_MS;
        if (faellig && beobLiest.compareAndSet(false, true)) {
            beobSchichtGelesenUm = jetzt;
            beobLage = lage;
            beobFaden(screen, ctx, this::beobSchilderLesen);
        }
        if (s != null) {
            de.hustlebot.schicht.ArenaStand.setzen(new de.hustlebot.schicht.ArenaStand.Stand(
                    jetzt, s.runde(), s.bildBreite(), s.bildHoehe(), stageFertig ? s.ohneZiel().gegner() : s.gegner(), s.plaetze()));
            return;
        }
        // Bis das erste Ergebnis da ist: nur die Rahmen, ohne Listen -- das Suchen der Listenbilder macht der Lese-Faden.
        if (beobPlaetze.isEmpty()) {
            int eigener = ctx.find(screen, T_ME)
                    .map(ich -> platzAusLage(ich.centerX(), ich.y() + SCHILD_HOEHE, ctx.width(), ctx.height())).orElse(0);
            java.util.Map<Integer, String> keineListe = new java.util.HashMap<>();
            for (int p = 1; p <= 10; p++) keineListe.put(p, "");
            beobPlaetze = plaetzeNachBild(screen, ctx, eigener, ctx.width(), ctx.height(), List.of(), keineListe);
        }
        de.hustlebot.schicht.ArenaStand.setzen(new de.hustlebot.schicht.ArenaStand.Stand(
                jetzt, zwischenStand(beobRundeZuletzt, rundeZuletzt), ctx.width(), ctx.height(), List.of(), beobPlaetze));
    }

    /**
     * Die Stage fuer den Zwischenstand, solange die Schilder dieser Stage noch gelesen werden. Autor 01.11.: "Stage 3 von 5, obwohl ich in Stage 4 bin" --
     * rundeZuletzt wird erst nach dem Lesen aktualisiert; die frisch am Bild gelesene Stage (zweimal hintereinander gesehen) gilt sofort.
     */
    static int zwischenStand(int frischGelesen, int zuletzt) {
        return Math.max(1, frischGelesen >= 1 && frischGelesen <= 5 ? frischGelesen : zuletzt);
    }

    /**
     * Pyramide weg, Vergleichsfenster, Chat oder Protokoll offen: Rahmen und Seitenfenster sofort weg.
     * @param nurAnsehen Autor sieht sich nur einen Gegner an: das Gelesene gilt weiter, wenn er zurueckkommt.
     */
    private void beobSchichtAus(boolean nurAnsehen) {
        if (beobPyramide || !beobPlaetze.isEmpty() || beobStand != null) de.hustlebot.schicht.ArenaStand.setzen(null);
        beobPyramide = false;
        if (!nurAnsehen) {
            beobStand = null;
            beobLetzteSchilder = null;
        }
        beobPlaetze = List.of();
    }

    /** Arbeit im Lese-Faden auf einem eingefrorenen Bild. Vorher beobLiest setzen. */
    private void beobFaden(Mat screen, BotContext ctx, java.util.function.BiConsumer<Mat, BotContext> arbeit) {
        Mat kopie = screen.clone();
        BotContext leser = ctx.standbild(ctx.frame(), kopie);
        Thread t = new Thread(() -> {
            try {
                arbeit.accept(kopie, leser);
            } catch (Exception e) {
                Log.debug("Arena", "Beobachter: Schilder nicht gelesen: " + e.getMessage());
            } finally {
                kopie.release();
                beobLiest.set(false);
            }
        }, BEOB_LESER);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);   // Autor spielt gerade: das Spiel geht vor
        t.start();
    }

    /**
     * Autor 30.10.: "Wenn ich einen Gegner zu einer Liste hinzufuege, soll der Bot das instant checken und vielleicht seine Entscheidung
     * ueberdenken." Die gelesenen Namen stimmen ja noch: nur diesen Spieler aendern und neu rechnen, ohne Texterkennung.
     */
    private void beobNeuBewerten(int platz, String wert) {
        List<Schild> alt = beobLetzteSchilder;
        if (alt == null || beobLiest.get()) {   // noch nichts gelesen, oder das Lesen laeuft gerade: danach gleich noch einmal
            beobSchichtGelesenUm = 0;
            if (alt == null) return;
        }
        List<Schild> neu = new ArrayList<>();
        for (Schild s : alt) neu.add(platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt) == platz ? mitListe(s, wert) : s);
        beobLetzteSchilder = List.copyOf(neu);
        beobNeuRechnen = true;
    }

    private static Schild mitListe(Schild s, String wert) {
        return new Schild(s.x(), s.y(), s.breite(), s.name(), s.clan(), s.botClan(), s.punkte(), "niemals".equals(wert) || "pause".equals(wert), "meiden".equals(wert),
                "sicher".equals(wert), "machbar".equals(wert), s.clanListe(), false, "fastsicher".equals(wert), "pause".equals(wert));
    }

    private void beobNeuBerechnen(Mat bild, BotContext ctx) {
        List<Schild> schilder = beobLetzteSchilder;
        if (schilder == null || !beobPyramide) return;
        long t0 = System.currentTimeMillis();
        beobZielUndMelden(bild, ctx, schilder, beobRundeLesen(bild, ctx), null);
        Log.debug("Arena", "Beobachter: nach deiner Taste neu gerechnet in " + (System.currentTimeMillis() - t0) + " ms.");
    }

    /** Was der Bot jetzt tun wuerde, und das an die Schicht. @param farben die Listen je Platz aus dem Bildvergleich, null = bleiben wie sie sind */
    private void beobZielUndMelden(Mat bild, BotContext ctx, List<Schild> schilder, int runde, java.util.Map<Integer, String> farben) {
        schichtChance.clear();
        schichtWoher.clear();
        schichtWahl.clear();
        if (farben != null) {
            schichtPlatzStufe.clear();
            schichtPlatzStufe.putAll(farben);
        }
        // Autor 30.10.: "Das Seitenfenster soll mir anzeigen, was der Bot machen wuerde." Auch nach dem Angriff rechnen: griff Autor an, bevor der Bot fertig
        // gelesen hatte, gaebe es sonst in dieser Stage nichts, dem er zustimmen koennte. Der Layer zeigt dann trotzdem keinen Vorschlag mehr.
        Optional<Schild> ziel = Optional.empty();
        boolean angegriffen = ctx.visible(bild, T_NEXT_STAGE);
        try {
            ziel = zielWaehlen(schilder, runde);
        } catch (RuntimeException e) {
            Log.debug("Arena", "Beobachter: kein Bot-Ziel gerechnet: " + e);
        }
        schichtGemeldetUm = 0;
        schichtLetzterStand = null;
        schichtMelden(bild, ctx, schilder, runde, ziel, true);
        // Waehrenddessen ging ein Vergleichsfenster auf: nichts mehr zeigen.
        if (!beobPyramide) {
            de.hustlebot.schicht.ArenaStand.setzen(null);
            return;
        }
        if (schichtLetzterStand != null) {
            beobStand = schichtLetzterStand;
            if (angegriffen) de.hustlebot.schicht.ArenaStand.setzen(schichtLetzterStand.ohneZiel());
        }
    }

    /** Liegt das Pause-Bild dieses Spielers laenger als PAUSE_TAGE? Ohne lesbaren Namen oder Bild: nein, er bleibt in der Pause. */
    private boolean pauseVorbei(String name, BotContext ctx) {
        if (name.isBlank()) return false;
        long jetzt = System.currentTimeMillis();
        try {
            for (var t : de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(name, ctx.matcher().templateRoot())) {
                if (T_PAUSE_PREFIX.equals(t.liste())
                        && pauseVorbei(java.nio.file.Files.getLastModifiedTime(t.datei()).toMillis(), jetzt)) return true;
            }
        } catch (java.io.IOException | RuntimeException e) {
            Log.debug("Arena", "Pause nicht geprueft: " + e);
        }
        return false;
    }

    static boolean pauseVorbei(long bildUm, long jetzt) {
        return jetzt - bildUm > PAUSE_TAGE * 86_400_000L;
    }

    /** Solange der Chat-Pfeil weg sein muss, bis der Chat als zu gilt: ein einzelnes Bild ohne ihn zaehlt nicht, im Chat muss jede Taste ankommen. */
    private static final long BEOB_CHAT_ZU_MS = 400;
    private long beobChatGesehen;

    /** Autor 30.10.: "Wenn ich den Chat wieder schliesse, dauert das vielleicht 5 Sekunden." Vorher waren es feste 4 s nach dem letzten Blick. */
    private void beobChatPruefen(boolean offen, long jetzt) {
        if (offen) {
            beobChatGesehen = jetzt;
        } else if (beobChatGesehen != 0 && jetzt - beobChatGesehen >= BEOB_CHAT_ZU_MS) {
            beobChatGesehen = 0;
            de.hustlebot.schicht.ArenaStand.chatZu();
        }
    }

    /** Fingerabdruck des Namensbildes -> {Name, Clan}, nur beim Zuschauen. */
    private final java.util.Map<String, String[]> beobNamen = new java.util.HashMap<>();
    /** Weisse Ziffern an dieser Stelle -> Belohnung, nur beim Zuschauen. */
    private final java.util.Map<Long, Long> beobBelohnung = new java.util.HashMap<>();
    private int beobNamenTreffer;

    /** Der Fingerabdruck des Namensbildes im Lese-Faden des Beobachters; sonst null (der Bot liest beim Spielen immer frisch). */
    private String beobNamensbild(BufferedImage bild, BotContext ctx, int mitteX, int rewardY) {
        if (bild == null || !BEOB_LESER.equals(Thread.currentThread().getName()) || !de.hustlebot.core.ArenaRegelnNeu.an()) return null;
        try {
            int oben = rewardY - SCHILD_HOEHE;
            java.awt.Rectangle k = textKasten(bild, mitteX - (int) (ctx.width() * 0.046), oben + 12,
                    mitteX + (int) (ctx.width() * 0.070), oben + 33);
            if (k == null) return null;
            Glyphenschrift.Ergebnis e = glyphen.erkennen(bild.getSubimage(k.x, k.y, k.width, k.height));
            return e.leer() || e.fingerabdruck() == null || e.fingerabdruck().isBlank() ? null : e.fingerabdruck();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Die weissen Punkte in diesem Bereich samt Lage als Zahl: gleiche Schrift an gleicher Stelle = gleiche Zahl. */
    private static long weissHash(BufferedImage bild, int x, int y, int breite, int hoehe) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(bild.getWidth(), x + breite), y1 = Math.min(bild.getHeight(), y + hoehe);
        long hash = 1469598103934665603L;
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                int p = bild.getRGB(xx, yy);
                hash = (hash ^ (Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 200 ? 1 : 0)) * 1099511628211L;
            }
        }
        return hash * 31 + x0 * 131L + y0;
    }

    /** Spieler, die in dieser Sitzung schon gemerkt sind. */
    private final java.util.Set<String> beobPyramideGemerkt = new java.util.HashSet<>();

    /**
     * Autor 30.10.: "Wenn ich in der Pyramide bin, jeden Spieler einmal abfotografieren und sich so den Namen und den Clan merken. Statt im Vergleichsfenster
     * oder auch im Vergleichsfenster, beides." Das Vergleichsfenster speichert weiter sein Foto (beobVergleichMerken), hier kommt das Namensbild dazu.
     */
    private void beobPyramideMerken(BufferedImage bild, BotContext ctx, List<Schild> schilder) {
        if (bild == null || !de.hustlebot.core.ArenaRegelnNeu.an()) return;
        try {
            java.nio.file.Path ordner = de.hustlebot.core.Ordner.bei(SPIELER_ORDNER);
            java.nio.file.Files.createDirectories(ordner);
            for (Schild s : schilder) {
                if (s.name().isBlank() || s.botClan() || unleserlich(s.name()) || !beobPyramideGemerkt.add(namensSchluessel(s.name()))) continue;
                boolean kennt = buch.nachschlagen(s.name(), s.clan()).isPresent()
                        || de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(s.name(), ctx.matcher().templateRoot()).stream()
                                .anyMatch(t -> !de.hustlebot.core.ArenaListenOrdner.istAuto(t.datei().getFileName().toString()));
                if (!kennt) de.hustlebot.core.SelbstKaempfe.gesehen(s.name(), s.clan());
                java.nio.file.Path p = ordner.resolve(spielerDatei(s.name()) + "_name.png");
                if (java.nio.file.Files.exists(p)) continue;
                int platz = platzAusLage(s.x(), s.y(), ctx.width(), ctx.height());
                BufferedImage k = platz >= 1 && platz <= 10 ? namensKasten(bild, platz) : null;
                if (k != null) javax.imageio.ImageIO.write(k, "png", p.toFile());
            }
        } catch (Exception e) {
            Log.debug("Arena", "Beobachter: Pyramide nicht gemerkt: " + e);
        }
    }

    private void beobSchilderLesen(Mat bild, BotContext ctx) {
        long t0 = System.currentTimeMillis();
        // Autor 01.11.: Protokoll offen oder Banner im Bild -- nichts lesen (auch nicht die Stage und die Farben), in 1 s noch einmal versuchen.
        String verdeckt = schilderVerdecktDurch(bild, ctx);
        if (verdeckt != null) {
            beobSchichtGelesenUm = t0 - BEOB_SCHICHT_MIN_MS + 1000;
            return;
        }
        int runde = beobRundeLesen(bild, ctx);
        rundeZuletzt = runde;
        schonAngegriffen.clear();   // wer ein "Reward:"-Schild hat, ist noch nicht angegriffen -- die Liste stammt aus dem letzten Bot-Turnier
        // Autor 30.10.: erst die Farben (nur Bildvergleich, schnell), die Namen brauchen die Texterkennung.
        java.util.Map<Integer, String> farben = new java.util.HashMap<>();
        int eigener = ctx.find(bild, T_ME)
                .map(ich -> platzAusLage(ich.centerX(), ich.y() + SCHILD_HOEHE, ctx.width(), ctx.height())).orElse(0);
        List<de.hustlebot.schicht.ArenaStand.Platz> mitFarbe = plaetzeNachBild(bild, ctx, eigener, ctx.width(), ctx.height(), List.of(), farben);
        if (beobPyramide) beobPlaetze = mitFarbe;
        Log.debug("Arena", "Beobachter: Farben nach " + (System.currentTimeMillis() - t0) + " ms.");
        // Autor 30.10., Stage 5: der Bot wollte Platz 10 (150 Punkte, 823, Platz 2), Autor griff den 225er ueber sich an und wurde Erster. Protokoll: "I am at place 2:
        // 2: ?". Die eigene (gelbe) Zahl wurde nie gelesen, die Top-3-Rechnung nahm turnierEndpunkte, und das zaehlt nur der Bot beim Spielen mit.
        turnierPunkteFehlen = true;
        if (runde == 1) turnierEndpunkte = 0;
        beobNamenTreffer = 0;
        List<Schild> schilder = schilderLesen(bild, ctx);
        if (schilder == VERDECKT) return;   // zwischen der ersten Pruefung und dem Lesen ist das Protokoll aufgegangen
        schilderCacheAt = 0;   // nur fuer die Anzeige gelesen: startet der Bot jetzt, liest er selbst neu
        if (tabelle != null && aktuellerPlatz > 0 && aktuellerPlatz < tabelle.length && tabelle[aktuellerPlatz] >= 0) turnierEndpunkte = tabelle[aktuellerPlatz];
        if (!beobPyramide || schilder.isEmpty()) return;
        beobLetzteSchilder = List.copyOf(schilder);
        beobZielUndMelden(bild, ctx, schilder, runde, farben);
        if (!beobPyramide) return;
        beobPyramideMerken(ctx.frame(), ctx, schilder);
        // Spieler, deren Liste noch fehlt (beim dritten Kampf war sein Schild nicht zu finden): jetzt steht er wieder da.
        // Hoechstens alle 5 Minuten je Spieler: das Lesen aller Schilder dauert (Pruefung 30.10.).
        List<String> offen = new ArrayList<>();
        long jetzt = System.currentTimeMillis();
        for (Schild x : schilder) {
            var e = de.hustlebot.core.SelbstKaempfe.finde(x.name());
            if (e != null && selbstListeOffen(e) && jetzt - beobOffenVersucht.getOrDefault(e.name(), 0L) >= 5 * 60_000L) {
                beobOffenVersucht.put(e.name(), jetzt);
                offen.add(e.name());
            }
        }
        if (!offen.isEmpty()) beobOffen = List.copyOf(offen);
        // Autor 03.11.: "0 Namen schon bekannt" las sich wie "keinen Spieler erkannt". Gezaehlt werden nur Namensbilder, die seit
        // dem Bot-Start schon einmal gelesen wurden (Abkuerzung fuers Tempo) -- gleich nach dem Start oder bei neuen Gegnern 0.
        // Wer der Spieler ist (Notizblock, Listen), haengt nicht daran -- das steht jetzt daneben ("5 Spieler erkannt").
        long bekannt = schilder.stream().filter(x -> !x.name().isBlank()
                && (buch.nachschlagen(x.name(), x.clan()).isPresent() || de.hustlebot.core.SelbstKaempfe.finde(x.name()) != null)).count();
        Log.debug("Arena", "Beobachter: " + schilder.size() + " Schilder in " + (System.currentTimeMillis() - t0) + " ms gelesen, "
                + bekannt + " Spieler aus dem Notizblock erkannt, " + beobNamenTreffer
                + " Namen ohne neues Lesen (Namensbild seit dem Bot-Start schon gelesen).");
    }

    /** Die Stage aus dem Bild, ohne den Rundenstand des Bots anzufassen (rundeLesen beginnt dort eine neue Runde). */
    private int beobRundeLesen(Mat screen, BotContext ctx) {
        BufferedImage f = ctx.frame();
        Optional<Matcher.Hit> h = ctx.find(screen, T_STAGE_LABEL);
        if (f != null && h.isPresent()) {
            Matcher.Hit l = h.get();
            int g = ArenaTabelle.stageLesen(ArenaTabelle.weissMaske(f, l.x() + l.width(), l.y() - 2,
                    (int) (ctx.width() * 0.07), l.height() + 4));
            if (g >= 1 && g <= 5) return g;
        }
        return Math.max(1, rundeZuletzt);
    }

    private void beobOffeneListen(Mat screen, BotContext ctx) {
        List<String> offen = beobOffen;
        if (offen.isEmpty()) return;
        beobOffen = List.of();
        for (String n : offen) {
            var e = de.hustlebot.core.SelbstKaempfe.finde(n);
            if (e != null) beobSelbstListe(screen, ctx, e);
        }
    }

    private volatile boolean beobPyramide;
    private volatile de.hustlebot.schicht.ArenaStand.Stand beobStand;
    private volatile List<String> beobOffen = List.of();
    /** Die zuletzt gelesenen Schilder dieser Lage, null = neu lesen. */
    private volatile List<Schild> beobLetzteSchilder;
    private volatile boolean beobNeuRechnen;
    private final Map<String, Long> beobOffenVersucht = new java.util.concurrent.ConcurrentHashMap<>();
    private int beobLage, beobRundeZuletzt, beobRundeKandidat;
    private long beobAllesUm;
    private static final long BEOB_ALLES_MS = 200;
    private String beobAusGrund;
    private long beobAusSeit;
    /** So lange darf die Pyramide fehlen, bevor der Layer ausgeht: etwa 3 Bilder. Vergleichsfenster, Chat und Klick wirken sofort. */
    private static final long BEOB_AUS_GEDULD_MS = 150;
    /** Das letzte Bild, auf dem die Pyramide zu sehen war: daraus wird beim Klick das Namensschild geschnitten. Jedes Bild ist neu, nichts wird ueberschrieben. */
    private volatile BufferedImage beobLetztesPyramidenBild;
    private static final String BEOB_LESER = "beob-schilder";

    /**
     * Pruefung 30.10.: der Matcher ist nicht fair. Der Lese-Faden sucht hunderte Listenbilder hintereinander und bekaeme die Sperre immer
     * wieder zuerst, der Beobachter oder der Bot kaemen lange nicht dran. Darum macht er nach jeder Suche kurz Platz.
     */
    private static void leserMachtPlatz() {
        if (!BEOB_LESER.equals(Thread.currentThread().getName())) return;
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ist dieser gelesene "Name" die Clan-Zeile (der Clan selbst oder ein Stueck davon, oder nur als Clan im Notizblock bekannt)? */
    private boolean nameIstClanZeile(String name, String clan) {
        return de.hustlebot.core.Gegnerbuch.nameIstClan(name, clan) || buch.nurAlsClanBekannt(name);
    }

    /** Darf eine gelesene Lesung den Platzhalter "Bild-..." ersetzen? Glaubhafter Name, kein Clan, kein Bruchstueck. */
    private boolean platzhalterErsetzbar(String name, String clan) {
        return de.hustlebot.core.Gegnerbuch.plausiblerName(name) && !nameIstClanZeile(name, clan);
    }

    /** Der Namenskasten auf diesem Platz als eigene Kopie, oder null. Die "Reward:"-Zeile liegt bei allen zehn Plaetzen 19-20 Punkte unter der Punktezeile (522 Schilder, 30.10.). */
    private static BufferedImage namensKasten(BufferedImage bild, int platz) {
        int w = bild.getWidth(), h = bild.getHeight();
        int mx = (int) Math.round(ArenaTabelle.X[platz] * w);
        int oben = (int) Math.round(ArenaTabelle.Y[platz] * h) + (int) Math.round(20.0 * h / 770) - SCHILD_HOEHE;
        java.awt.Rectangle k = textKasten(bild, mx - (int) (w * 0.046), oben + 12, mx + (int) (w * 0.070), oben + 33);
        if (k == null) return null;
        BufferedImage kasten = new BufferedImage(k.width, k.height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = kasten.createGraphics();
        g.drawImage(bild.getSubimage(k.x, k.y, k.width, k.height), 0, 0, null);
        g.dispose();
        return kasten;
    }
    private final java.util.concurrent.atomic.AtomicBoolean beobLiest = new java.util.concurrent.atomic.AtomicBoolean();

    /** Ein Blick des Beobachters. Klickt nie. */
    public void beobachtenSchritt(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        long jetzt = System.currentTimeMillis();
        beobKlickAuswerten();
        // Fehlerbericht R7RW (Konrad, 29.10., Windows in einer VM ohne Grafikkarte): ar_victory 131x in 105 s gesucht, Last
        // 96 % im Leerlauf -- der Beobachter suchte alle 0,8 s im ganzen Bild nach Sieg/Niederlage, auch wenn niemand Arena
        // spielte. Gezaehlt wird ein Ergebnis aber nur nach einem frisch gesehenen Vergleichsfenster -- also nur dann suchen.
        boolean ergebnisMoeglich = beobGegner != null && !beobErledigt && jetzt - beobGegnerUm <= BEOB_FRIST_MS;
        boolean sieg = ergebnisMoeglich && ctx.visible(screen, T_VICTORY);
        if (sieg || (ergebnisMoeglich && kastenSichtbar(screen, ctx, T_DEFEAT))) {
            if (beobErledigt || beobGegner == null || jetzt - beobGegnerUm > BEOB_FRIST_MS) return;
            if (de.hustlebot.core.ArenaSchalter.an()) beobSchichtAus(false);   // Autor 30.10.: im Sieges-/Verlustfenster kein Layer
            if (beobErgebnisSeit == 0) {
                beobErgebnisSeit = jetzt;
                beobSieg = false;
                beobPunkte = -1;
                beobDauer = -1;
            }
            beobSieg |= sieg;
            long punkte = ctx.find(screen, T_POINTS)
                    .flatMap(h -> optional(ArenaLeser.zahlUnter(bild, h, (int) (ctx.height() * 0.09)))).orElse(-1L);
            long dauer = ctx.find(screen, T_TIME)
                    .flatMap(h -> optional(ArenaLeser.zahlUnter(bild, h, (int) (ctx.height() * 0.09)))).orElse(-1L);
            if (punkte <= 300) beobPunkte = Math.max(beobPunkte, punkte);
            if (dauer <= Gegnerbuch.MAX_KAMPFZEIT_S) beobDauer = Math.max(beobDauer, dauer);
            if (jetzt - beobErgebnisSeit < 2_500) return;   // die Zahlen zaehlen erst hoch
            beobZaehlen(screen, ctx);
            return;
        }
        // Pruefung 29.10.: in 9 Tagen kein einziges "Watcher: ..." -- verlangt wurden 2,5 s Banner am Stueck, wer selbst
        // spielt, tippt aber meist sofort "Home". Jetzt zaehlt das Ergebnis auch, sobald das Banner wieder weg ist.
        if (beobErgebnisSeit != 0 && !beobErledigt && beobGegner != null) {
            Log.debug("Arena", "Beobachter: Ergebnis-Banner nach " + (jetzt - beobErgebnisSeit) + " ms weg -- wird jetzt gezaehlt.");
            beobZaehlen(screen, ctx);
        }
        beobErgebnisSeit = 0;
        beobachtenVergleich(screen, ctx, bild, jetzt);
    }

    /** Das gesehene Ergebnis (Sieg/Niederlage gegen beobGegner) in Notizblock und Sperrliste eintragen. */
    private void beobZaehlen(Mat screen, BotContext ctx) {
        {
            beobErledigt = true;
            String name = beobGegner[0], clan = beobGegner[1];
            boolean regelnNeu = de.hustlebot.core.ArenaRegelnNeu.an();
            if (istBot(clan) && !regelnNeu) {
                Log.debug("Arena", "Beobachter: " + name + " ist in keinem Clan -- kommt nicht in den Notizblock.");
                return;
            }
            // Der letzte Kampf VOR diesem (fuer die automatische Liste von damals, siehe beobListeVormerken).
            char letzterVor = buch.nachschlagen(name, clan).map(Gegnerbuch.Notiz::letzterKampf).orElse((char) 0);
            Glyphenschrift.Ergebnis beobNamensbild = namensbildZu(name);   // Autor 24.10.: Namensbild aus der Pyramide
            String beobFp = beobNamensbild == null ? null : beobNamensbild.fingerabdruck();
            beobStand = null;   // nach dem Kampf ordnet sich die Pyramide um
            beobLetzteSchilder = null;
            // Autor 30.10.: "Wenn er den Spieler noch nicht kennt, soll er sich nur den Namen genau merken. Getrennt in einem anderen Notizblock."
            // Autor 30.10.: auch Spieler ohne Clan zaehlen mit und kommen nach 3 Kaempfen auf eine Liste; in den normalen Notizblock weiter nicht.
            boolean ohneClan = istBot(clan) || botLautPyramide(name);
            if (regelnNeu && (ohneClan || !botKennt(name, clan, beobFp, ctx))) {
                beobFremderKampf(screen, ctx, name, ohneClan ? "" : clan, beobFp);
                return;
            }
            selbstKaempfeUebernehmen(name, clan, beobStaerke);
            buch.ergebnis(name, clan, beobSieg, beobStaerke, (int) beobDauer, beobPunkte, beobNamensbild);
            de.hustlebot.core.ArenaSperre.ergebnis(name, clan, beobStaerke,
                    buch.nachschlagen(name, clan).map(Gegnerbuch.Notiz::sets).orElse(""),
                    einheitenVon.getOrDefault(namensSchluessel(name), ""), null, beobFp, beobSieg);
            int[] b = buch.bilanzNachName(name, Math.max(0, beobStaerke), beobFp);
            AutoListe a = autoListe(b[0], b[1]);
            boolean eigenerStempel = de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(name, ctx.matcher().templateRoot()).stream()
                    .anyMatch(t -> !de.hustlebot.core.ArenaListenOrdner.istAuto(t.datei().getFileName().toString()));
            String liste = b[0] == 0 && b[1] >= 3 ? Sprache.t(" -- from now on \"never\"", " -- ab jetzt \"niemals\"")
                    : eigenerStempel ? ""
                    : a == AutoListe.SICHER ? Sprache.t(" -- automatically \"safe win\"", " -- automatisch \"sicher\"")
                    : a == AutoListe.FAST_SICHER ? Sprache.t(" -- automatically \"almost safe\"", " -- automatisch \"fast sicher\"")
                    : a == AutoListe.MACHBAR ? Sprache.t(" -- automatically \"doable\"", " -- automatisch \"machbar\"") : "";
            String wer = clan.isBlank() ? name : name + " / " + clan;
            Log.info("Arena", Sprache.t("Watcher: you " + (beobSieg ? "won" : "lost") + " against " + wer
                            + (beobPunkte >= 0 ? ", " + beobPunkte + " points" : "") + (beobDauer >= 0 ? ", " + beobDauer + " s" : "")
                            + ". Record now " + b[0] + " of " + b[1] + liste + ".",
                    "Beobachter: du hast gegen " + wer + (beobSieg ? " gewonnen" : " verloren")
                            + (beobPunkte >= 0 ? ", " + beobPunkte + " Punkte" : "") + (beobDauer >= 0 ? ", " + beobDauer + " s" : "")
                            + ". Bilanz jetzt " + b[0] + " von " + b[1] + liste + "."));
            // Autor 29.10.: dieselben Regeln wie beim Bot -- die Gegner auch in die Listen (Namensbild), nicht nur in den Notizblock.
            if (de.hustlebot.core.ArenaRegelnNeu.an()) {
                try {
                    beobListeVormerken(screen, ctx, name, clan, b, letzterVor);
                } catch (RuntimeException e) {
                    Log.warn("Arena", Sprache.t("Watcher: list entry for " + name + " failed: ",
                            "Beobachter: Listeneintrag fuer " + name + " fehlgeschlagen: ") + e);
                }
            }
        }
    }

    /**
     * Kennt der Bot diesen Spieler? Ja, wenn er im Notizblock steht oder Autor ihn selbst auf eine Liste gelegt hat. Wer schon im
     * eigenen Notizblock steht und nur durch dessen Liste ein Namensbild hat, bleibt dort, bis der Bot selbst gegen ihn kaempft.
     */
    private boolean botKennt(String name, String clan, String fingerabdruck, BotContext ctx) {
        if (buch.nachschlagen(name, clan, beobStaerke, fingerabdruck).isPresent()) return true;
        if (de.hustlebot.core.SelbstKaempfe.finde(name) != null) return false;
        return de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(name, ctx.matcher().templateRoot()).stream()
                .anyMatch(t -> !de.hustlebot.core.ArenaListenOrdner.istAuto(t.datei().getFileName().toString()));
    }

    /**
     * Autor 30.10.: "Deine Kaempfe wandern in den normalen Notizblock": der Bot kennt ihn jetzt selbst, also zaehlen sie auch fuer seine
     * Chance. Vor dem Buchen des neuen Kampfes rufen, damit der neueste auch zuletzt steht.
     */
    private void selbstKaempfeUebernehmen(String name, String clan, long staerke) {
        if (!de.hustlebot.core.ArenaRegelnNeu.an()) return;
        var e = de.hustlebot.core.SelbstKaempfe.entfernen(name);
        if (e == null || e.kaempfe().isEmpty()) return;
        for (var k : e.kaempfe()) buch.ergebnis(name, clan, k.sieg(), staerke, k.dauerS(), k.punkte(), (Glyphenschrift.Ergebnis) null);
        Log.info("Arena", Sprache.t("Own notebook: " + e.kaempfe().size() + " fights against " + name + " moved to the normal notebook.",
                "Eigener Notizblock: " + e.kaempfe().size() + " Kaempfe gegen " + name + " in den normalen Notizblock uebernommen."));
    }

    /** Ein Kampf gegen einen Spieler, den der Bot nicht kennt: nur in den eigenen Notizblock, ab dem dritten Kampf auf eine Liste. */
    private void beobFremderKampf(Mat screen, BotContext ctx, String name, String clan, String fingerabdruck) {
        // Die Sperrliste zaehlt mit (Pruefung 30.10.): trifft das Niemals-Bild einmal nicht, schuetzt sie trotzdem vor dem Bot-Angriff.
        de.hustlebot.core.ArenaSperre.ergebnis(name, clan, beobStaerke, "",
                einheitenVon.getOrDefault(namensSchluessel(name), ""), null, fingerabdruck, beobSieg);
        long jetzt = System.currentTimeMillis();
        long angriff = beobAngriffUm > 0 && jetzt - beobAngriffUm <= BEOB_FRIST_MS ? beobAngriffUm : jetzt;
        var e = de.hustlebot.core.SelbstKaempfe.ergebnis(name, clan, beobSieg,
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(angriff), java.time.ZoneId.systemDefault()),
                (int) beobDauer, beobPunkte);
        beobAngriffUm = 0;
        Log.info("Arena", Sprache.t("Watcher: you " + (beobSieg ? "won" : "lost") + " against " + name
                        + (beobPunkte >= 0 ? ", " + beobPunkte + " points" : "") + (beobDauer >= 0 ? ", " + beobDauer + " s" : "")
                        + ". New player, own notebook: " + e.siege() + " wins, " + e.niederlagen() + " defeats.",
                "Beobachter: du hast gegen " + name + (beobSieg ? " gewonnen" : " verloren")
                        + (beobPunkte >= 0 ? ", " + beobPunkte + " Punkte" : "") + (beobDauer >= 0 ? ", " + beobDauer + " s" : "")
                        + ". Neuer Spieler, eigener Notizblock: " + e.siege() + " Siege, " + e.niederlagen() + " Niederlagen."));
        try {
            beobSelbstListe(screen, ctx, e);
        } catch (RuntimeException x) {
            Log.warn("Arena", Sprache.t("Watcher: list entry for " + name + " failed: ",
                    "Beobachter: Listeneintrag fuer " + name + " fehlgeschlagen: ") + x);
        }
    }

    /** Das Wort, unter dem der eigene Notizblock und die Tasten der Schicht eine Liste fuehren. */
    private static String listenWort(ArenaListenRegel.Liste l) {
        return switch (l) {
            case NIEMALS -> "niemals";
            case MEIDEN -> "meiden";
            case SICHER -> "sicher";
            case MACHBAR -> "machbar";
            case FAST_SICHER -> "fastsicher";
            case PAUSE -> "pause";
            case KEINE -> "";
        };
    }

    /** Ist eine Liste faellig, die noch nicht eingetragen ist? "Niemals" bleibt fuer immer (Autor 22.10.). */
    private static boolean selbstListeOffen(de.hustlebot.core.SelbstKaempfe.Eintrag e) {
        ArenaListenRegel.Liste l = ArenaListenRegel.nachSelbstKaempfen(e.ergebnisse());
        return l != ArenaListenRegel.Liste.KEINE && !"niemals".equals(e.liste()) && !listenWort(l).equals(e.liste());
    }

    private void beobSelbstListe(Mat screen, BotContext ctx, de.hustlebot.core.SelbstKaempfe.Eintrag e) {
        if (!selbstListeOffen(e)) return;
        ArenaListenRegel.Liste l = ArenaListenRegel.nachSelbstKaempfen(e.ergebnisse());
        if (beobBildAblegen(screen, ctx, e.name(), "", listenPraefix(l), l, l == ArenaListenRegel.Liste.SICHER,
                l == ArenaListenRegel.Liste.NIEMALS)) {
            de.hustlebot.core.SelbstKaempfe.eingetragen(e.name(), listenWort(l));
            beobStand = null;   // die neue Farbe gleich zeigen
        }
    }

    // ---- Beobachter setzt die Listen (Autor 29.10.) --------------------------------------------------------------
    // "Wenn der Bot sieht, dass ich gegen einen Gegner verliere, gegen den er schon 2 mal verloren hat, soll er ihn als
    // blockieren abspeichern, also das Namensschild vom Spieler. ... Die gleichen Regeln wie beim normalen Bot, nur dass ich
    // spiele." Gezaehlt wird gemeinsam (der Notizblock hat Bot- und Autor-Kaempfe). Die Regel steht in ArenaListenRegel.
    //
    // Das Namensschild: Nach dem Kampf steht in der Pyramide statt "Reward:" nur noch "Victory!"/"Defeat" -- das Schild ist
    // dann nicht mehr zu finden und die Pyramide ordnet sich um. Darum schneidet der Beobachter die Namenskaesten aller
    // "Reward:"-Schilder aus, SOLANGE die Pyramide zur Wahl steht (nur Pixel, keine Texterkennung), und friert sie ein, sobald
    // das Ergebnis-Banner da ist. Faellt eine Liste an, wird erst dann (selten) jeder dieser Kaesten gelesen; der mit dem
    // Namen aus dem Vergleichsfenster uebereinstimmt, ist das Namensbild -- gespeichert ueber namensbildSpeichern, wie beim Bot.

    /** Ein Namenskasten der Pyramide: Lage (Mitte und Oberkante der "Reward:"-Zeile) und eine Kopie des Bildes. */
    private record BeobSchild(int x, int y, int breite, BufferedImage kasten) { }

    private List<BeobSchild> beobSchilder = List.of();
    private long beobSchilderUm, beobSchilderGeprueftUm;
    /** So alt darf der Stand der Pyramide sein (zwei Minuten je Runde, dazu Luft fuer das Ansehen der Vergleichsfenster). */
    private static final long BEOB_SCHILDER_HALTBAR_MS = 6 * 60_000L;
    /** So oft schaut der Beobachter nach der Pyramide (Bildvergleich auf dem ganzen Bild kostet). */
    private static final long BEOB_SCHILDER_TAKT_MS = 2_500;

    /** Merkt die Namenskaesten der Pyramide. Nur mit arena-regeln-neu.txt "an". Klickt nie. */
    private void beobSchilderMerken(Mat screen, BotContext ctx, BufferedImage bild, long jetzt) {
        if (bild == null || !de.hustlebot.core.ArenaRegelnNeu.an()) return;
        // Das Ergebnis des Kampfes steht noch aus (Banner gesehen, noch nicht gezaehlt): die Pyramide von vorher behalten.
        if (beobErgebnisSeit != 0 && !beobErledigt) return;
        if (jetzt - beobSchilderGeprueftUm < BEOB_SCHILDER_TAKT_MS) return;
        beobSchilderGeprueftUm = jetzt;
        if (!ctx.visible(screen, T_SELECT) && !ctx.visible(screen, T_NEXT_STAGE)) return;
        List<Matcher.Hit> eigene = ctx.matcher().findAll(screen, T_ME);
        int b = (int) (ctx.width() * 0.14);
        List<BeobSchild> neu = new ArrayList<>();
        for (Matcher.Hit r : rewardTreffer(screen, ctx)) {
            if (inFeld(eigene, r.centerX() - b / 2, r.y() - SCHILD_HOEHE, b, SCHILD_HOEHE)) continue;   // das eigene Schild
            int oben = r.y() - SCHILD_HOEHE;
            java.awt.Rectangle k = textKasten(bild, r.centerX() - (int) (ctx.width() * 0.046), oben + 12,
                    r.centerX() + (int) (ctx.width() * 0.070), oben + 33);
            if (k == null) continue;
            BufferedImage teil = bild.getSubimage(k.x, k.y, k.width, k.height);
            BufferedImage kopie = new BufferedImage(teil.getWidth(), teil.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = kopie.createGraphics();
            g.drawImage(teil, 0, 0, null);
            g.dispose();
            neu.add(new BeobSchild(r.centerX(), r.y(), r.width(), kopie));
        }
        beobSchilder = List.copyOf(neu);
        if (!neu.isEmpty()) beobSchilderUm = jetzt;
    }

    /** Den Namen aus einem Namenskasten lesen (PaddleOCR, sonst Windows-Texterkennung wie nameImKasten); "" = nichts. */
    private static String beobKastenLesen(BufferedImage kasten) {
        java.awt.Rectangle ganz = new java.awt.Rectangle(0, 0, kasten.getWidth(), kasten.getHeight());
        String t = ArenaLeser.nameImKastenPaddle(kasten, ganz);
        if (!t.isBlank() && !unleserlich(t)) return t;
        String w = ArenaLeser.textHinterWort(kasten, 0, 0, kasten.getWidth(), kasten.getHeight(), "Name");
        return w == null || unleserlich(w) ? "" : w;
    }

    /**
     * Das Namensschild dieses Spielers: zuerst das, auf das Autor geklickt hat (sicher, ganz ohne Texterkennung; Autor 30.10.: "den7 ...
     * Namensschild nicht gefunden"), dann die gemerkten Schilder der Pyramide, zuletzt das Bild aus einem frueheren Kampf. Oder null.
     */
    private BeobSchild beobSchildFinden(String name) {
        long jetzt = System.currentTimeMillis();
        BeobSchild geklickt = beobKlickSchild;
        if (geklickt != null && jetzt - beobKlickUm <= BEOB_SCHILDER_HALTBAR_MS) {
            String klickName = beobKlickName.isBlank() && beobGegner != null ? beobGegner[0] : beobKlickName;
            if (de.hustlebot.core.NamensVergleich.gleicherName(klickName, name)) return geklickt;
        }
        List<BeobSchild> l = beobSchilder;
        if (!l.isEmpty() && jetzt - beobSchilderUm <= BEOB_SCHILDER_HALTBAR_MS) {
            for (BeobSchild k : l) {
                String gelesen = beobKastenLesen(k.kasten());
                if (!gelesen.isBlank() && de.hustlebot.core.NamensVergleich.gleicherName(gelesen, name)) return k;
            }
        }
        return beobGemerktesSchild(name);
    }

    // ---- Autors Klicks (Autor 30.10.: "Klick auf Peter, Angriff auf Peter, gewonnen, hinzugefuegt zu Liste Sicher") ------------------
    // Laeuft unsichtbar im Hintergrund: nur damit der Bot jedes Ergebnis dem richtigen Spieler zuordnen kann. Der Klick geht immer ans Spiel.

    private long beobKlickGesehen, beobKlickUm, beobAngriffUm, beobVergleichGesehenUm;
    private String beobKlickName = "", beobKlickClan = "";
    private BeobSchild beobKlickSchild;
    /** Ein Klick zaehlt nur fuer das naechste Vergleichsfenster (Pruefung 30.10.): sonst bekaeme Spieler B den Namen und das Schild von A. */
    private boolean beobKlickFrisch, beobVergleichOffen;
    /** So lange nach einem Klick gilt das Vergleichsfenster noch als offen (die Knoepfe liegen dann an bekannter Stelle). */
    private static final long BEOB_VERGLEICH_NOCH_MS = 3_000;
    /** So lange braucht das Fenster nach einem Pfeil, bis der neue Gegner dasteht. */
    private static final long BEOB_PFEIL_MS = 350;
    /** Kurz nach einem Klick oft lesen, sonst alle 2 s (derselbe Gegner). */
    private static final long BEOB_LESEN_NACH_KLICK_MS = 300, BEOB_NACH_KLICK_MS = 1_500;

    /** Quick Attack und To battle im Vergleichsfenster (Foto 1159x770: x 580-952, y 583-652). */
    static boolean istAngriffsKnopf(double fx, double fy) {
        return fx >= 0.500 && fx <= 0.822 && fy >= 0.757 && fy <= 0.847;
    }

    /** Die Pfeile unter dem Vergleichsfenster ("Opponent selection", Foto: x 420-478 und 680-738, y 668-725): anderer Gegner. */
    static boolean istVergleichsPfeil(double fx, double fy) {
        return fy >= 0.860 && fy <= 0.945 && ((fx >= 0.355 && fx <= 0.420) || (fx >= 0.580 && fx <= 0.645));
    }

    private void beobKlickAuswerten() {
        var k = de.hustlebot.schicht.ArenaStand.letzterKlick();
        if (k == null || k.um() == beobKlickGesehen) return;
        beobKlickGesehen = k.um();
        if (k.platz() > 0) {
            beobKlickUm = k.um();
            beobKlickName = k.name();
            beobKlickClan = k.clan();
            beobKlickSchild = beobSchildAufPlatz(k.platz());
            beobKlickFrisch = true;
            beobAngriffUm = 0;
            beobGelesenUm = 0;   // das Vergleichsfenster gleich lesen, wenn es da ist
            Log.debug("Arena", "Beobachter: Klick auf Platz " + k.platz() + " (" + k.name() + ").");
            return;
        }
        if (k.um() - beobVergleichGesehenUm > BEOB_VERGLEICH_NOCH_MS) return;
        if (istAngriffsKnopf(k.fx(), k.fy())) {
            beobAngriffUm = k.um();
            Log.debug("Arena", "Beobachter: Angriff auf " + (beobGegner == null ? beobKlickName : beobGegner[0]) + ".");
        } else if (istVergleichsPfeil(k.fx(), k.fy())) {
            beobKlickName = "";
            beobKlickClan = "";
            beobKlickSchild = null;
            beobGelesenUm = k.um() + BEOB_PFEIL_MS - BEOB_LESEN_NACH_KLICK_MS;
            Log.debug("Arena", "Beobachter: Pfeil im Vergleichsfenster -- anderer Gegner.");
        }
    }

    /**
     * Autor spielte 30.10.: im Vergleichsfenster las der Bot "Khuni", "Dujei", "Agnstin clan", auf dem Schild richtig "Khun", "Duje", "Agust";
     * so entstanden doppelte Eintraege. Passt der Name im Fenster grob zum angeklickten Schild, gilt das Schild. Ganz anderer Name: die Pyramide
     * hat sich umgeordnet, dann gilt das Fenster.
     */
    static String[] nameMitKlick(String[] wer, String klickName, String klickClan) {
        if (klickName == null || klickName.isBlank()) return wer;
        if (!wer[0].isBlank() && !grobGleich(wer[0], klickName)) return wer;
        return new String[]{klickName, wer[1].isBlank() ? klickClan : wer[1]};
    }

    private static boolean grobGleich(String a, String b) {
        if (de.hustlebot.core.NamensVergleich.gleicherName(a, b)) return true;
        String x = a.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(java.util.Locale.ROOT);
        String y = b.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(java.util.Locale.ROOT);
        return x.length() >= 2 && y.length() >= 2 && x.charAt(0) == y.charAt(0) && x.charAt(1) == y.charAt(1);
    }

    /** Das Namensschild auf diesem Platz aus dem letzten Bild der Pyramide (Pruefung 30.10.: die gemerkten Schilder koennen 2,5 s alt sein), oder null. */
    private BeobSchild beobSchildAufPlatz(int platz) {
        BufferedImage bild = beobLetztesPyramidenBild;
        if (bild == null || platz < 1 || platz > 10) return null;
        BufferedImage kasten = namensKasten(bild, platz);
        if (kasten == null) return null;
        int w = bild.getWidth(), h = bild.getHeight();
        return new BeobSchild((int) Math.round(ArenaTabelle.X[platz] * w),
                (int) Math.round(ArenaTabelle.Y[platz] * h) + (int) Math.round(20.0 * h / 770), (int) (w * 0.14), kasten);
    }

    // ---- Beim Vergleichsfenster merken (Autor 30.10.: "sofort alles speichern, um den Gegner spaeter richtig zu ordnen und zu erkennen") ---

    /** Je Spieler: das neueste kleine Foto seiner Reihe im Vergleichsfenster und sein Namensbild aus der Pyramide. */
    static final String SPIELER_ORDNER = "arena-spieler";

    /** Name als Dateiname: nur Buchstaben und Ziffern (auch kyrillisch usw.). */
    static String spielerDatei(String name) {
        String kurz = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N}]", "");
        return kurz.isEmpty() ? "gegner" + Integer.toHexString(String.valueOf(name).hashCode()) : kurz;
    }

    /**
     * Die Reihe des Gegners im Vergleichsfenster (Foto 1159x770: x 210-950, y 385-572), halb so gross. Autor 30.10.: "in niedrigerer
     * Aufloesung, der Bot muss sie nur wiedererkennen koennen". Die Namensbilder fuer die Listen bleiben scharf.
     */
    static BufferedImage vergleichsFoto(BufferedImage bild) {
        int w = bild.getWidth(), h = bild.getHeight();
        int x = (int) (w * 0.18), y = (int) (h * 0.50), b = (int) (w * 0.64), t = (int) (h * 0.245);
        BufferedImage klein = new BufferedImage(Math.max(1, b / 2), Math.max(1, t / 2), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = klein.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(bild, 0, 0, klein.getWidth(), klein.getHeight(), x, y, x + b, y + t, null);
        g.dispose();
        return klein;
    }

    /** Als JPEG mit dieser Guete (0-1) schreiben. */
    static void alsJpeg(BufferedImage bild, java.nio.file.Path ziel, float guete) throws java.io.IOException {
        var schreiber = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var aus = javax.imageio.ImageIO.createImageOutputStream(ziel.toFile())) {
            schreiber.setOutput(aus);
            var p = schreiber.getDefaultWriteParam();
            p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(guete);
            schreiber.write(null, new javax.imageio.IIOImage(bild, null, null), p);
        } finally {
            schreiber.dispose();
        }
    }

    /** Autor 01.11.: "in der Arena ziemlich wenig Speicherplatz" -- ein ganzes Bild nur noch halb so breit und hoch, JPG mit Guete 70. */
    static void halbAlsJpeg(BufferedImage bild, java.nio.file.Path ziel) throws java.io.IOException {
        BufferedImage klein = new BufferedImage(Math.max(1, bild.getWidth() / 2), Math.max(1, bild.getHeight() / 2), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = klein.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(bild, 0, 0, klein.getWidth(), klein.getHeight(), null);
        g.dispose();
        alsJpeg(klein, ziel, 0.7f);
    }

    private void beobVergleichMerken(BufferedImage bild, BotContext ctx, String name, String clan) {
        if (!de.hustlebot.core.ArenaRegelnNeu.an()) return;
        try {
            if (!botKennt(name, clan, null, ctx)) de.hustlebot.core.SelbstKaempfe.gesehen(name, clan);
            java.nio.file.Path ordner = de.hustlebot.core.Ordner.bei(SPIELER_ORDNER);
            java.nio.file.Files.createDirectories(ordner);
            String datei = spielerDatei(name);
            alsJpeg(vergleichsFoto(bild), ordner.resolve(datei + ".jpg"), 0.6f);
            BeobSchild k = beobKlickSchild;
            if (k != null) javax.imageio.ImageIO.write(k.kasten(), "png", ordner.resolve(datei + "_name.png").toFile());
        } catch (Exception e) {
            Log.debug("Arena", "Beobachter: Vergleichsfenster nicht gespeichert: " + e);
        }
    }

    /** Das Namensbild aus einem frueheren Kampf gegen diesen Spieler, oder null. */
    private BeobSchild beobGemerktesSchild(String name) {
        try {
            java.nio.file.Path p = de.hustlebot.core.Ordner.bei(SPIELER_ORDNER).resolve(spielerDatei(name) + "_name.png");
            if (!java.nio.file.Files.exists(p)) return null;
            BufferedImage b = javax.imageio.ImageIO.read(p.toFile());
            return b == null ? null : new BeobSchild(0, 0, 0, b);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Nach Autors Kampf (Notizblock ist schon gebucht, b = {Siege, Kaempfe} MIT diesem Kampf): dieselbe Entscheidung wie beim
     * Bot (ArenaListenRegel.entscheide) -- ist eine Liste faellig, das Namensbild des Gegners aus der Pyramide ablegen.
     */
    private void beobListeVormerken(Mat screen, BotContext ctx, String name, String clan, int[] b, char letzterVor) {
        if (botLautPyramide(name)) return;   // in der Pyramide "Not in clan": nie auf eine Liste
        var notiz = buch.nachschlagen(name, clan);
        if (notiz.isEmpty()) return;
        java.nio.file.Path wurzel = ctx.matcher().templateRoot();
        // Auf welcher Liste stand er VOR dem Kampf? Listenbilder (die reinen "_auto"-Anzeigebilder zaehlen nicht, wie beim Bot) ...
        boolean gesperrt = false, meiden = false, sicher = false, machbar = false, fast = false, pause = false, gestempelt = false;
        for (var t : de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(name, wurzel)) {
            if (de.hustlebot.core.ArenaListenOrdner.istAuto(t.datei().getFileName().toString())) continue;
            String l = t.liste();
            if (!de.hustlebot.core.ArenaVorlagenDoppelt.vomBot(t.datei().getFileName().toString())) gestempelt = true;
            if (T_BLOCK_PREFIX.equals(l)) gesperrt = true;
            else if (T_UNGERN_PREFIX.equals(l)) meiden = true;
            else if (T_SICHER_PREFIX.equals(l)) sicher = true;
            else if (T_MACHBAR_PREFIX.equals(l)) machbar = true;
            else if (T_FAST_PREFIX.equals(l)) fast = true;
            else if (T_PAUSE_PREFIX.equals(l)) pause = true;
        }
        if (sicher && abgestuft(name)) { sicher = false; machbar = true; }   // verloren gegen "sicher" -> zaehlt als machbar
        // ... sonst die automatische Liste aus dem Notizblock von damals (ohne diesen Kampf).
        if (!gesperrt && !meiden && !sicher && !machbar && !fast && !pause) {
            AutoListe a = ArenaListenRegel.autoStufe(b[0] - (beobSieg ? 1 : 0), b[1] - 1, letzterVor);
            sicher = a == AutoListe.SICHER;
            machbar = a == AutoListe.MACHBAR;
            fast = a == AutoListe.FAST_SICHER;
        }
        long seine = beobStaerke > 0 ? beobStaerke : notiz.map(Gegnerbuch.Notiz::staerke).filter(v -> v > 0).orElse(-1L);
        boolean vielStaerker = seine > 0 && meineStaerke > 0 && seine - meineStaerke >= ArenaListenRegel.VIEL_STAERKER;
        var kampf = new ArenaListenRegel.Kampf(beobSieg, beobDauer, vielStaerker, b[0], b[1],
                notiz.get().niederlagen(), notiz.get().siege());
        var stand = new ArenaListenRegel.Stand(gesperrt, meiden, sicher, machbar, fast, pause);
        var e = ArenaListenRegel.entscheide(kampf, stand);
        if (de.hustlebot.core.ArenaRegelnNeu.an()) e = ArenaListenRegel.mitZwischenstufen(kampf, stand, e);
        if (e.abstufen()) abstufen(name);
        String art = listenPraefix(e.liste());
        if (art == null) return;
        if (beobSieg && e.liste() == ArenaListenRegel.Liste.SICHER && aufstufen(name)) return;
        // Autor 31.10. (FLIMSY auf "machbar", Protokoll: "ab jetzt safe"): Sein eigener Stempel gilt, ein Sieg stuft ihn nicht von selbst hoch.
        if (beobSieg && gestempelt) return;
        beobBildAblegen(screen, ctx, name, clan, art, e.liste(), e.dritterSieg(), e.dreiOhneSieg());
    }

    /** Legt das Namensbild des Gegners aus der Pyramide in diese Liste. @return false = sein Schild war nicht zu finden */
    private boolean beobBildAblegen(Mat screen, BotContext ctx, String name, String clan, String art, ArenaListenRegel.Liste liste,
                                    boolean dritterSieg, boolean dreiOhneSieg) {
        String wort = switch (liste) {
            case NIEMALS -> Sprache.t("never", "niemals");
            case MEIDEN -> Sprache.t("avoid", "meiden");
            case SICHER -> Sprache.t("safe", "sicher");
            case FAST_SICHER -> Sprache.t("almost safe", "fast sicher");
            case PAUSE -> Sprache.t("pause", "Pause");
            default -> Sprache.t("doable", "machbar");
        };
        BeobSchild schild = beobSchildFinden(name);
        if (schild == null) {
            Log.info("Arena", Sprache.t("Watcher: " + name + " would go on \"" + wort + "\", but his name shield was not found in the "
                            + "pyramid before the fight -- no name image saved (the notebook still counts).",
                    "Beobachter: " + name + " kaeme auf \"" + wort + "\", aber sein Namensschild war vor dem Kampf nicht in der "
                            + "Pyramide zu finden -- kein Namensbild gespeichert (der Notizblock zaehlt trotzdem)."));
            return false;
        }
        Log.info("Arena", Sprache.t("Watcher: " + name + " -- from now on \"" + wort + "\" (saving the name image).",
                "Beobachter: " + name + " -- ab jetzt \"" + wort + "\" (Namensbild wird abgelegt)."));
        // Genau der Weg des Bots (namensbildSpeichern): das Bild des Kastens gilt als "vor dem Angriff gemerkt". Der Beobachter
        // laeuft nur, solange der Bot selbst nicht spielt -- die Felder des Bots sind also frei.
        Schild s = new Schild(schild.x(), schild.y(), schild.breite(), name, clan, false, 150, false, false, false, false, false, false);
        vorAngriffFuer = vorAngriffSchluessel(s);
        vorAngriffAusschnitt = schild.kasten();
        ergebnisSieg = beobSieg;
        vorlageDritterSieg = dritterSieg;
        vorlageDreiOhneSieg = dreiOhneSieg;
        vorlageFuer = s;
        vorlageArt = art;
        vorlageDauer = (int) beobDauer;
        vorlageRunde = rundeZuletzt;
        vorlageSeit = System.currentTimeMillis();
        try {
            namensbildSpeichern(screen, ctx);
        } finally {
            vorlageFuer = null;
            vorAngriffFuer = null;
            vorAngriffAusschnitt = null;
        }
        return true;
    }

    /** Vergleichsfenster des Beobachters: welcher Gegner wird gerade angesehen (fuer das spaetere Ergebnis). */
    private void beobachtenVergleich(Mat screen, BotContext ctx, BufferedImage bild, long jetzt) {
        // Vergleichsfenster: wie im Turnier erkannt (Zeile "Your squad" UND Angriffsknopf oder Kraftzeile)
        if (!ctx.visible(screen, T_YOUR_SQUAD)) {
            beobVergleichOffen = false;
            beobGelesenUm = 0;   // Pruefung 30.10.: das naechste Fenster gleich lesen, es kann ein anderer Gegner sein
            beobSchilderMerken(screen, ctx, bild, jetzt);   // Autor 29.10.: Namenskaesten fuer die Listen (nur mit arena-regeln-neu.txt)
            beobachtenPyramide(screen, ctx, jetzt);   // Autor 28.10.: Rahmen fuer die Arena-Schicht (nur mit Schalter)
            return;
        }
        // Autor 30.10.: "Wenn ich ein Vergleichsfenster oeffne, soll der Layer nichts anzeigen, da es dann stoert."
        if (de.hustlebot.core.ArenaSchalter.an()) beobSchichtAus(true);
        beobVergleichGesehenUm = jetzt;
        if (!beobVergleichOffen) {
            beobVergleichOffen = true;
            if (!beobKlickFrisch) {   // ohne Klick auf ein Schild aufgegangen: was vom letzten Klick stammt, gehoert einem anderen Spieler
                beobKlickName = "";
                beobKlickClan = "";
                beobKlickSchild = null;
            }
            beobKlickFrisch = false;
        }
        if (!(ctx.visible(screen, T_QUICK) || ctx.visibleIfKnown(screen, T_POWER))) return;
        // derselbe Gegner -- nicht jede 0,8 s neu lesen; kurz nach einem Klick (Pfeil) oft
        if (!beobErledigt && jetzt - beobGelesenUm < (jetzt - beobKlickGesehen < BEOB_NACH_KLICK_MS ? BEOB_LESEN_NACH_KLICK_MS : 2_000)) return;
        Optional<Matcher.Hit> swords = ctx.find(screen, T_POWER);
        String[] wer = gegnerNameLesen(ctx, bild, swords);
        beobGelesenUm = jetzt;
        // Autor 30.10.: der Klick sagt, auf wen er gedrueckt hat (Name unlesbar oder schlechter gelesen).
        if (jetzt - beobKlickUm <= BEOB_FRIST_MS) wer = nameMitKlick(wer, beobKlickName, beobKlickClan);
        if (wer[0].isBlank()) return;
        long seine = -1;
        if (swords.isPresent()) {
            Matcher.Hit sw = swords.get();
            OptionalLong rechts = ArenaLeser.weisseZahl(bild, sw.x() + sw.width(), sw.y(), (int) (ctx.width() * 0.20), sw.height());
            if (rechts.isPresent() && rechts.getAsLong() > 100_000) seine = rechts.getAsLong();
        }
        if (beobGegner == null || beobErledigt || !namensSchluessel(wer[0]).equals(namensSchluessel(beobGegner[0]))) {
            Log.debug("Arena", "Beobachter: Vergleichsfenster mit " + wer[0] + " / " + wer[1] + " (Kraft " + seine + ").");
            beobStaerke = -1;   // Autor 29.10.: die Kraft des vorigen Gegners gilt nicht fuer diesen ("viel staerker" rechnet damit)
            beobVergleichMerken(bild, ctx, wer[0], wer[1]);
        }
        beobGegner = wer;
        beobGegnerUm = jetzt;
        if (seine > 0) beobStaerke = seine;
        beobErledigt = false;
    }

    /** Automatische Liste aus der Bilanz gegen einen Spieler (Autor 21.10.). */
    enum AutoListe { MACHBAR, FAST_SICHER, SICHER }

    /**
     * Autor 21.10.: "2 Siege gegen einen Spieler -> machbar, 3 Siege -> sicher" -- fuer ALLE Kaempfe (Bot und selbst
     * gespielt, beides steht im Notizblock). Damit eine alte Gluecksstraehne nicht ueber viele Niederlagen siegt:
     * "machbar" nur mit mehr Siegen als Niederlagen, "sicher" nur mit hoechstens einer Niederlage je drei Siege.
     * (3 Niederlagen und kein Sieg = "niemals" regelt dreimalVerloren.)
     * @return null = keine automatische Liste
     */
    static AutoListe autoListe(int siege, int kaempfe) {
        int niederlagen = Math.max(0, kaempfe - siege);
        if (siege >= 3 && niederlagen * 3 <= siege) {
            // Autor 31.10.: erst "fast sicher", beim naechsten Sieg "sicher". Ohne den Schalter wie bisher.
            return siege == 3 && de.hustlebot.core.ArenaRegelnNeu.an() ? AutoListe.FAST_SICHER : AutoListe.SICHER;
        }
        if (siege >= 2 && siege > niederlagen) return AutoListe.MACHBAR;
        return null;
    }

    /** Je Turnier und Name nur einmal "automatisch auf Liste" ins Protokoll. */
    private final java.util.Set<String> autoGemeldet = new java.util.HashSet<>();

    /**
     * Setzt die automatische Liste, wenn der Spieler auf KEINER eigenen Liste steht -- was der Spieler selbst
     * eingetragen hat (Namensbilder, Capture-Programm), wird nie ueberschrieben.
     */
    private Schild mitAutoListe(Schild s) {
        if (s.gesperrt() || s.ungern() || s.sicher() || s.machbar() || s.fastSicher() || s.name().isBlank() || s.botClan()) return s;
        int[] b = gesamtBilanz(s);
        AutoListe a = autoListe(b[0], b[1]);
        // Idee 1 (A18, 22.10.): war der LETZTE Kampf eine Niederlage, faellt die Liste sofort eine Stufe (sicher ->
        // machbar -> keine), statt erst, wenn die Niederlagen die Siege ueberholen (Myth Gods: S S N blieb "machbar").
        if (a != null && buch.nachschlagen(s.name(), s.clan()).map(Gegnerbuch.Notiz::letzterKampf).orElse((char) 0) == 'N') {
            a = a != AutoListe.MACHBAR ? AutoListe.MACHBAR : null;
        }
        if (a == null) return s;
        if (autoGemeldet.add(namensSchluessel(s.name()))) {
            String art = a == AutoListe.SICHER ? Sprache.t("safe win", "sicher")
                    : a == AutoListe.FAST_SICHER ? Sprache.t("almost safe", "fast sicher") : Sprache.t("doable", "machbar");
            Log.info("Arena", Sprache.t(s.wer() + ": won " + b[0] + " of " + b[1] + " -- automatically \"" + art + "\".",
                    s.wer() + ": " + b[0] + " von " + b[1] + " gewonnen -- automatisch \"" + art + "\"."));
        }
        return new Schild(s.x(), s.y(), s.breite(), s.name(), s.clan(), s.botClan(), s.punkte(), false, false,
                a == AutoListe.SICHER, a == AutoListe.MACHBAR, s.clanListe(), true, a == AutoListe.FAST_SICHER, false);
    }

    private boolean dreimalVerloren(Schild s) {
        // Autor 24.10. abends: im Vergleichsfenster als gesperrt erkannt (z. B. am Aussehen) -- nicht noch einmal waehlen.
        if (imFensterGesperrt.contains(namensSchluessel(s.name()))) {
            if (dreimalGemeldet.add(namensSchluessel(s.name()))) {
                Log.info("Arena", Sprache.t(s.name() + ": blocked -- recognised in the comparison window.",
                        s.name() + ": gesperrt -- im Vergleichsfenster wiedererkannt."));
            }
            return true;
        }
        // Autor 24.10.: mit dem Fingerabdruck des Namensbildes (Glyphenschrift) -- auch bei verlesenem Namen und neuer Kraft.
        String sperre = de.hustlebot.core.ArenaSperre.sperrgrund(s.name(), s.clan(), bekannteStaerke(s), setsVon(s),
                einheitenVon.getOrDefault(namensSchluessel(s.name()), ""), null, fingerabdruckZu(s.name()));
        if (sperre != null) {
            if (dreimalGemeldet.add(namensSchluessel(s.name()))) {
                Log.info("Arena", Sprache.t(s.name() + ": blocked -- " + sperre + ".", s.name() + ": gesperrt -- " + sperre + "."));
            }
            return true;
        }
        // Sperr-Netz (Autor 28.10., nur mit arena-regeln-neu.txt "an"): der Name eines "niemals"-Bildes, dessen Bild im Schild nicht
        // traf. Xenos: Bild traf in 14 von 21 Sichtungen, zweimal angegriffen und verloren (21.10. 01:19, 22.10. 03:38).
        if (de.hustlebot.core.ArenaRegelnNeu.an() && !s.name().isBlank()) {
            long kraft = bekannteStaerke(s);
            for (var t : de.hustlebot.core.ArenaListenNetz.treffer(listenNamenNetz, s.name())) {
                if (!t.liste().equals(de.hustlebot.core.ArenaListenNetz.NIEMALS)) continue;
                if (!de.hustlebot.core.ArenaListenNetz.kraftVertraeglich(kraft, notizbuchKraefte(t.name()))) continue;
                if (dreimalGemeldet.add(namensSchluessel(s.name()))) {
                    Log.info("Arena", Sprache.t(s.name() + ": blocked -- name of the \"never\" picture " + t.name() + ".",
                            s.name() + ": gesperrt -- Name des niemals-Bildes " + t.name() + "."));
                }
                return true;
            }
        }
        int[] b = gesamtBilanz(s);
        boolean ja = b[0] == 0 && b[1] >= 3;
        if (ja && dreimalGemeldet.add(namensSchluessel(s.name()))) {
            Log.info("Arena", Sprache.t(s.name() + ": lost " + b[1] + " times, never won -- not attacked any more.",
                    s.name() + ": " + b[1] + "-mal verloren, nie gewonnen -- wird nicht mehr angegriffen."));
        }
        return ja;
    }

    private final java.util.Set<String> dreimalGemeldet = new java.util.HashSet<>();

    /** Namen der Einheiten je Gegner (Schluessel wie pruefungen), aus der Pruefung dieses Programmlaufs (ArenaSperre). */
    private final java.util.Map<String, String> einheitenVon = new java.util.HashMap<>();

    /** Die Sets seiner Einheiten: aus der Pruefung dieses Turniers, sonst aus dem Notizblock (fuer ArenaSperre). */
    private String setsVon(Schild s) {
        ArenaPruefer.Ergebnis e = pruefungen.get(namensSchluessel(s.name()));
        if (e != null && e.sets() != null && !e.sets().isBlank()) return e.sets();
        return buch.nachschlagen(s.name(), s.clan()).map(Gegnerbuch.Notiz::sets).orElse("");
    }

    /** Gilt die einfache TS8-Strategie (ArenaEinfach)? Liest Schalter und Stufe sofort neu (fuer !thronsaal). */
    public static boolean einfacheStrategie() {
        ArenaEinfach.neuLesen();
        return ArenaEinfach.aktiv();
    }

    /**
     * TS8-Wahl (Nutzer 25.11., ArenaEinfach): nur Bilanz und Schild-Punkte. Kraft, Sets, Liste der Punkte und Top-3-Rechnung
     * spielen keine Rolle. Die Regeln stehen in ArenaEinfach (getestet), hier nur Schilder rein und Protokoll.
     */
    private Optional<Schild> einfachWaehlen(List<Schild> auswahl, int runde) {
        if (auswahl.isEmpty()) return Optional.empty();
        List<ArenaEinfach.Kandidat> k = new ArrayList<>();
        for (Schild s : auswahl) {
            k.add(new ArenaEinfach.Kandidat(s.name(), s.punkte(), ArenaEinfach.bewertung(s.name(), meineStaerke)));
            anzeigeMerken(s.name(), s.clan());
        }
        int i = ArenaEinfach.wahl(k, runde);
        if (i < 0) return Optional.empty();
        Schild z = auswahl.get(i);
        ArenaEinfach.Kandidat kz = k.get(i);
        if (!namensSchluessel(z.name()).equals(zuletztGewaehlt)) {
            StringBuilder alle = new StringBuilder();
            for (int j = 0; j < k.size(); j++) {
                if (j > 0) alle.append(" · ");
                alle.append(auswahl.get(j).wer()).append(' ').append(ArenaEinfach.lohn(runde, k.get(j).schildPunkte()))
                        .append(" (").append(ArenaEinfach.text(k.get(j).bewertung())).append(')');
            }
            String oben = ArenaEinfach.ueberMir(runde, kz.schildPunkte()) ? Sprache.t("above me", "ueber mir") : Sprache.t("below me", "unter mir");
            Log.info("Arena", Sprache.t("Choice (TS" + ArenaEinfach.STUFE + ", round " + runde + "): " + z.wer() + " -- "
                            + ArenaEinfach.lohn(runde, kz.schildPunkte()) + " points, " + (runde >= 2 ? oben + ", " : "")
                            + ArenaEinfach.text(kz.bewertung()) + ", " + Math.round(kz.bewertung().chance() * 100) + " %. All: " + alle,
                    "Wahl (TS" + ArenaEinfach.STUFE + ", Runde " + runde + "): " + z.wer() + " -- "
                            + ArenaEinfach.lohn(runde, kz.schildPunkte()) + " Punkte, " + (runde >= 2 ? oben + ", " : "")
                            + ArenaEinfach.text(kz.bewertung()) + ", " + Math.round(kz.bewertung().chance() * 100) + " %. Alle: " + alle));
            fotoSerie("wahl");
            zuletztGewaehlt = namensSchluessel(z.name());
        }
        return Optional.of(z);
    }

    private Optional<Schild> zielWaehlen(List<Schild> schilder, int runde) {
        // Autor 19.10.: "Wenn man dreimal gegen einen Spieler verliert, wird er gesperrt und nicht mehr angegriffen."
        // Gezaehlt am Namen ueber alle Clan-Schreibweisen; drei Niederlagen ohne einen Sieg = nie mehr angreifen.
        // TS8 (Nutzer 25.11.): nur die harte Liste (ar_block_) sperrt -- "dreimal verloren" und OP-Items nicht, die Bilanz entscheidet
        // dort selbst und wird nach 5 % mehr eigener Kraft neu bewertet.
        boolean einfach = ArenaEinfach.aktiv();
        List<Schild> erlaubt = schilder.stream().filter(s -> !s.gesperrt() && (einfach || (!dreimalVerloren(s) && !opGesperrt(s)))).toList();
        if (erlaubt.isEmpty()) return Optional.empty();

        // VERBRAUCHTE HERAUSNEHMEN -- aber nie bis zur Leere.
        //
        // Eigentlich sollte ein verbrauchter Gegner gar nicht mehr in der
        // Liste stehen: der Bot sucht nach "Reward:", und wo schon
        // gekaempft wurde, steht das nicht mehr. Das ist aber eine
        // Texterkennung, und die ueberliest auch mal etwas. Diese Liste ist
        // die zweite Absicherung.
        //
        // Sie darf jedoch nie die letzte Wahl wegnehmen. Verliest sich der
        // Bot bei den Namen, koennte er sich sonst selbst alle Ziele
        // sperren und die Runde verstreichen lassen -- und eine verpasste
        // Runde ist teurer als ein Angriff, der ins Leere geht. Autor:
        // "er soll aber in jeder runde IMMER einen angriff taetigen."
        List<Schild> frisch = erlaubt.stream()
                .filter(s -> !schonAngegriffen.contains(namensSchluessel(s.name())))
                .toList();
        if (!frisch.isEmpty() && frisch.size() < erlaubt.size()) {
            Log.debug("Arena", (erlaubt.size() - frisch.size())
                    + " Gegner sind diese Arena schon verbraucht und fallen weg.");
            erlaubt = frisch;
        } else if (frisch.isEmpty()) {
            warnung(Sprache.t("All remaining opponents count as already attacked. "
                    + "That is probably a misread name -- the bot "
                    + "attacks anyway instead of letting the round pass.",
                    "Alle uebrigen Gegner gelten als schon angegriffen. "
                    + "Das ist vermutlich ein Lesefehler bei den Namen -- der Bot "
                    + "greift trotzdem an, statt die Runde verstreichen zu lassen."));
        }

        // "Ungern" (ar_ungern_...) fliegt seit 25.09. nicht mehr heraus, sondern
        // bekommt +15 Punkte (Autor). "Niemals" (ar_block_...) bleibt hart
        // gesperrt -- steht mit +99 in der Liste, damit man sieht, warum.
        List<Schild> auswahl = erlaubt;
        if (!graueSchilder.isEmpty()) {
            List<Schild> ohneGrau = auswahl.stream().filter(s -> graueSchilder.stream()
                    .noneMatch(g -> Math.abs(g[0] - s.x()) < 40 && Math.abs(g[1] - s.y()) < 40)).toList();
            if (!ohneGrau.isEmpty()) auswahl = ohneGrau;
        }
        if (einfach) return einfachWaehlen(auswahl, runde);
        // Stage 1 (Autor, 11.10.): "In der ersten Runde bitte immer den schwaechsten Gegner angreifen, den er findet."
        // Der mit der kleinsten bekannten Kraft (Durchschauen oder Notizblock); "niemals" ist oben schon raus.
        // Kennt der Bot keine Kraft, gilt wie bisher: einer der Plaetze 4-9.
        if (runde <= 1) {
            // Aufgehobene (arena-aufheben.txt, sicher/machbar mit sehr gutem Team) bleiben fuer Stage 4-5 -- nur, wenn
            // sonst keiner mit bekannter Kraft da ist.
            List<Schild> mitKraft = auswahl.stream().filter(s -> bekannteStaerke(s) > 0 || s.botClan()).toList();
            List<Schild> ohneAufgehobene = mitKraft.stream().filter(s -> !spaetZiel(s)).toList();
            // Autor 01.11.: "nicht den einfachsten Spieler, sondern einen, wo er unter 150 Punkte bekommt, am besten 149" -- dann
            // steht er unter allen mit 150 und bekommt spaeter die 225er. Nachgerechnet an 197 Kaempfen: Kraft 0,7-0,9 der eigenen
            // 66 von 67 gewonnen, Dauer im Median 11-13 s (bis 10 s gibt es volle 150, 11 s = 149); unter 0,7 meist 7-8 s.
            if (de.hustlebot.core.ArenaRegelnNeu.an() && meineStaerke > 0) {
                Optional<Schild> knapp = knappZiel(ohneAufgehobene.stream().filter(s -> {
                    int platz = platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt);
                    return platz >= STAGE1_VON && platz <= STAGE1_BIS;
                }).toList());
                if (knapp.isPresent()) {
                    for (Schild s : schilder) {
                        anzeigeMerken(s.name(), s.clan());
                        listenPunkteJe.put(namensSchluessel(s.name()), listenPunkte(s));
                        listenTextJe.put(namensSchluessel(s.name()), listenText(s));
                    }
                    punkteVorbereiten(auswahl, schilder);
                    Schild z = knapp.get();
                    if (!namensSchluessel(z.name()).equals(zuletztGewaehlt)) {
                        Log.info("Arena", Sprache.t("Choice (round 1, a little under 150 points): " + z.wer() + " -- " + Math.round(siegchance(z) * 100)
                                        + " %, " + Sprache.zahl(bekannteStaerke(z)) + " power",
                                "Wahl (Runde 1, knapp unter 150 Punkte): " + z.wer() + " -- " + Math.round(siegchance(z) * 100) + " %, "
                                        + String.format(java.util.Locale.GERMANY, "%,d", bekannteStaerke(z)) + " Kraft"));
                        fotoSerie("wahl");
                        zuletztGewaehlt = namensSchluessel(z.name());
                        listeSchreiben();
                    }
                    return knapp;
                }
            }
            // Autor 16.10.: "In der ersten Runde den Spieler mit der hoechsten Siegchance angreifen -- auch zwischen den
            // Leuten ohne Clan kann sie unterschiedlich sein." Bei Gleichstand der mit weniger Kraft.
            Optional<Schild> schwaechster = (ohneAufgehobene.isEmpty() ? mitKraft : ohneAufgehobene).stream()
                    .max(java.util.Comparator.<Schild>comparingLong(s -> Math.round(siegchance(s) * 100))
                            .thenComparing(java.util.Comparator.<Schild>comparingLong(
                                    s -> bekannteStaerke(s) > 0 ? bekannteStaerke(s) : Long.MAX_VALUE).reversed()));
            if (schwaechster.isPresent()) {
                for (Schild s : schilder) {
                    anzeigeMerken(s.name(), s.clan());
                    listenPunkteJe.put(namensSchluessel(s.name()), listenPunkte(s));
                    listenTextJe.put(namensSchluessel(s.name()), listenText(s));
                }
                punkteVorbereiten(auswahl, schilder);
                Schild z = schwaechster.get();
                if (!namensSchluessel(z.name()).equals(zuletztGewaehlt)) {
                    Log.info("Arena", Sprache.t("Choice (round 1, highest win chance): " + z.wer() + " -- "
                            + Math.round(siegchance(z) * 100) + " %, "
                            + (bekannteStaerke(z) > 0 ? Sprache.zahl(bekannteStaerke(z)) + " power" : "power unknown"),
                            "Wahl (Runde 1, hoechste Siegchance): " + z.wer() + " -- " + Math.round(siegchance(z) * 100) + " %, "
                            + (bekannteStaerke(z) > 0 ? String.format(java.util.Locale.GERMANY, "%,d", bekannteStaerke(z)) + " Kraft" : "Kraft unbekannt")));
                    fotoSerie("wahl");
                    zuletztGewaehlt = namensSchluessel(z.name());
                    listeSchreiben();
                }
                return schwaechster;
            }
        }
        // Stage 1 ohne bekannte Kraft: einen der Plaetze 4-9 angreifen (Autor, 01.10.; 9 seit 22.10.) -- solange dort noch jemand ist.
        if (runde <= 1) {
            // Nach der LAGE des Schilds -- die Pyramide steht in Stage 1 still, und der Name auf dem
            // kleinen Schild ist oft nicht lesbar.
            List<Schild> gruen = auswahl.stream().filter(s -> {
                int platz = platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt);
                return platz >= STAGE1_VON && platz <= STAGE1_BIS;
            }).toList();
            if (!gruen.isEmpty()) auswahl = gruen;
            else Log.info("Arena", Sprache.t("Stage 1: none of places " + STAGE1_VON + "-" + STAGE1_BIS + " can be chosen any more -- choosing among all.",
                    "Stage 1: keiner der Plaetze " + STAGE1_VON + "-" + STAGE1_BIS + " ist mehr waehlbar -- es wird unter allen gewaehlt."));
        }
        for (Schild s : schilder) {
            anzeigeMerken(s.name(), s.clan());
            listenPunkteJe.put(namensSchluessel(s.name()), listenPunkte(s));
            listenTextJe.put(namensSchluessel(s.name()), listenText(s));
        }
        punkteVorbereiten(auswahl, schilder);
        // Autor 01.11. (Arena 15:14, 749 gegen 750): "Er haette genau einen einzelnen Typen nur angreifen muessen, der 149 oder 145
        // gibt" -- "in Stage 1 bis 3 mindestens einen, der unter 150 Punkte gibt". Mit lauter vollen 150 stand er in Stage 5 ganz oben
        // (Foto 15:14:03), ueber ihm kein 225er. Darum in Stage 2 und 3, solange noch alles voll ist, wieder ein knapper 150er.
        if (de.hustlebot.core.ArenaRegelnNeu.an() && meineStaerke > 0 && nochNichtUnter150(runde, turnierEndpunkte, turnierPunkteFehlen)) {
            Optional<Schild> knapp = knappZiel(auswahl.stream().filter(s -> s.punkte() <= 150 && !spaetZiel(s)).toList());
            if (knapp.isPresent()) {
                Schild z = knapp.get();
                if (!namensSchluessel(z.name()).equals(zuletztGewaehlt)) {
                    Log.info("Arena", Sprache.t("Choice (round " + runde + ", all fights so far gave a full 150 -- now a little under 150): " + z.wer() + " -- "
                                    + Math.round(siegchance(z) * 100) + " %, " + Sprache.zahl(bekannteStaerke(z)) + " power",
                            "Wahl (Runde " + runde + ", bisher nur volle 150 -- jetzt knapp unter 150): " + z.wer() + " -- "
                                    + Math.round(siegchance(z) * 100) + " %, " + String.format(java.util.Locale.GERMANY, "%,d", bekannteStaerke(z)) + " Kraft"));
                    fotoSerie("wahl");
                    zuletztGewaehlt = namensSchluessel(z.name());
                    listeSchreiben();
                }
                return knapp;
            }
        }
        // ENDRUNDE (Autor, 16.10., Lauf 3: Runde 5 nahm einen 150er, Ende Platz 4): "Durchrechnen, wenn er jetzt gegen
        // den Gegner ueber mir gewinnen sollte, mit 225 Punkten, ob ich dann Top 1 bis 3 erreiche. Wenn ja, soll er es
        // probieren, auch wenn die Siegchance gering ist. Platz 4 ist scheisse, Platz 1 bis 3 ist das Ziel."
        // Wer in der letzten Runde auf Platz 4 oder schlechter steht, kommt mit einem 150er kaum noch an den drei
        // vorbei (die greifen ja auch noch an) -- der 225er ueber ihm ist die einzige echte Chance. Darum: den
        // 225er mit der besten Siegchance, ohne die 85-%-Grenze. Auf Platz 1-3 bleibt die sichere Wahl.
        if (runde >= LETZTE_RUNDE && !endrundeGemeldet) {
            endrundeGemeldet = true;
            long n225 = auswahl.stream().filter(s -> s.punkte() > 150).count();
            Log.info("Arena", Sprache.t("Last round: I am at place " + (aktuellerPlatz > 0 ? aktuellerPlatz : "?") + ", " + n225
                    + " opponent(s) worth 225 to choose from.",
                    "Letzte Runde: ich auf Platz " + (aktuellerPlatz > 0 ? aktuellerPlatz : "?") + ", " + n225 + " 225er waehlbar."));
        }
        // TOP-3-RECHNUNG (Autor 21.10.): mit den Turnierpunkten aller Spieler ausrechnen, welcher Angriff Top 1-3 am
        // sichersten macht. Gelingt das Lesen nicht, gilt wie bisher das Wagnis unten und die normale Wahl.
        if (runde >= ArenaTabelle.RECHNUNG_AB) {
            Optional<Schild> rechnung = top3Wahl(auswahl, runde);
            if (rechnung.isPresent()) return rechnung;
        }
        // Lauf 5 (16.10.): auf Platz 7 brachte das Wagnis nichts -- selbst ein 225er-Sieg reicht dort nicht fuer die
        // Top 3 (Autor: "durchrechnen, OB ich dann Top 1 bis 3 erreiche"). Darum nur auf Platz 4 und 5.
        if (runde >= LETZTE_RUNDE && aktuellerPlatz >= 4 && aktuellerPlatz <= WAGNIS_BIS_PLATZ) {
            Optional<Schild> wagnis = auswahl.stream().filter(s -> s.punkte() > 150)
                    .max(java.util.Comparator.comparingDouble(this::siegchance));
            if (wagnis.isPresent()) {
                Schild z = wagnis.get();
                if (!namensSchluessel(z.name()).equals(zuletztGewaehlt)) {
                    Log.info("Arena", Sprache.t("Last round, I am at place " + aktuellerPlatz + " -- with a 150 win the top 3 is out of "
                            + "reach, so taking the risk: " + z.wer() + " for " + z.punkte() + " (win chance "
                            + Math.round(siegchance(z) * 100) + " %).",
                            "Letzte Runde, ich auf Platz " + aktuellerPlatz + " -- mit einem 150er sind die Top 3 nicht zu "
                            + "schaffen, darum das Wagnis: " + z.wer() + " fuer " + z.punkte() + " (Siegchance "
                            + Math.round(siegchance(z) * 100) + " %)."));
                    fotoSerie("wahl");
                    zuletztGewaehlt = namensSchluessel(z.name());
                    listeSchreiben();
                }
                return wagnis;
            }
            Log.debug("Arena", "Letzte Runde, Platz " + aktuellerPlatz + ": kein 225er waehlbar -- normale Wahl.");
        }
        Optional<Schild> ziel = auswahl.stream()
                .max((a, b) -> Double.compare(wert(a, runde), wert(b, runde)));
        // Nur melden und die Liste neu schreiben, wenn sich die Wahl aendert --
        // die Pyramide wird bei jedem Bild ausgewertet (25.09.: sonst stand
        // "Wahl: ..." mehrmals je Sekunde im Protokoll).
        if (ziel.isPresent() && !namensSchluessel(ziel.get().name()).equals(zuletztGewaehlt)) {
            Schild z = ziel.get();
            Log.info("Arena", Sprache.t("Choice: " + z.wer() + " -- " + punkteText(z)
                    + ", value " + Math.round(wert(z, runde)),
                    "Wahl: " + z.wer() + " -- " + punkteText(z)
                    + ", Wert " + Math.round(wert(z, runde))));
            fotoSerie("wahl");
            zuletztGewaehlt = namensSchluessel(z.name());
            listeSchreiben();
        }
        return ziel;
    }

    // ------------------------------------------------------------------
    //  Autors Punkte-System (24.09.)
    // ------------------------------------------------------------------
    //
    //  Je Gegner: Sets (F=1 E=2 D=3 C=4 B=5 A=6 S=8 je Einheit)
    //           + 3 je Einheit mit einem gelisteten guten Artefakt
    //           + 1 je Million Kampfkraft MEHR als Autor (weniger: -1)
    //             ab 20 Mio mehr +10, ab 30 Mio +20, ab 40 Mio +50 (Autor 08.10.)
    //           + 5 je Einheit unter 6 (weniger Einheiten = meist staerker)
    //           + 5 je Bauarbeiter (Einheit mit hoechstens 250 000 Leben, 27.09.)
    //           + 15 "meiden" (ar_ungern_), + 99 "niemals" (ar_block_, gesperrt)
    //           - 40 "sicherer Sieg" (ar_sicher_, Autor 27.09.; -40 seit 08.10.), - 15 "machbar"
    //           + 30 hat mich laut Kampfprotokoll besiegt (08.10.), - 20 ist an mir gescheitert
    //  (die letzten vier Zeilen: Autor, 25.09.)
    //           + 1 je Schirmherrschaft, + 3 je guter, + 2 je gutem Stigma (Autor, 01.10.)
    //           + 8 je gutem Pal (08.10., vorher 5), + 8 Clan aus der Clan-Liste (ar_clan_) (Autor, 01.10.)
    //           (fuer Autors Testlaeufe einzeln schaltbar: arena-extras.txt)
    //  Wenige Punkte = schwacher Gegner. Aus der Rangfolge unter den
    //  Gegnern dieser Runde wird eine Siegchance (85 % fuer den
    //  schwaechsten, 25 % fuer den staerksten). Der Notizblock (ab drei
    //  Kaempfen) geht immer vor -- "die notizen zaehlen am meisten".

    /** Punkte je Gegner (Vergleichsform) fuer die laufende Wahl. */
    private final java.util.Map<String, Integer> punkteJetzt = new java.util.HashMap<>();
    private int punkteMin, punkteMax;

    /** "NIEMALS (+99)" / "MEIDEN (+15)" / "" je Gegner (aus den Namensbildern). */
    private final java.util.Map<String, Integer> listenPunkteJe = new java.util.HashMap<>();

    /** "MEIDEN (+15)  CLAN-LISTE (+8)" usw. je Gegner -- was in der Liste hinter dem Namen steht. */
    private final java.util.Map<String, String> listenTextJe = new java.util.HashMap<>();

    /** Was in der Liste hinter dem Namen steht. */
    private static String listenText(Schild s) {
        StringBuilder b = new StringBuilder();
        if (s.pause() && s.gesperrt()) b.append(Sprache.t("PAUSE (not attacked for now)  ", "PAUSE (vorerst nicht angreifen)  "));
        else if (s.gesperrt()) b.append(Sprache.t("NEVER (+99)  ", "NIEMALS (+99)  "));
        if (s.fastSicher()) b.append(Sprache.t("ALMOST SAFE (-" + FAST_PUNKTE + ")  ", "FAST SICHER (-" + FAST_PUNKTE + ")  "));
        if (s.ungern()) b.append(Sprache.t("AVOID (+15)  ", "MEIDEN (+15)  "));
        if (s.sicher()) b.append(Sprache.t("SAFE WIN (-40)  ", "SICHERER SIEG (-40)  "));
        if (s.machbar()) b.append(Sprache.t("DOABLE (-15)  ", "MACHBAR (-15)  "));
        if (s.clanListe() && ArenaExtras.clans()) b.append(Sprache.t("CLAN LIST (+", "CLAN-LISTE (+") + CLAN_PUNKTE + ")  ");
        return b.toString().strip();
    }

    /**
     * Was die Durchsicht der Einheiten ergab: Sets + Artefakte + Bauarbeiter
     * + fehlende Einheiten. Null, wenn der Gegner (noch) nicht angesehen wurde.
     */
    private Integer setUndArtefaktPunkte(String name, String clan) {
        ArenaPruefer.Ergebnis e = pruefungen.get(namensSchluessel(name));
        if (e == null) e = pruefungen.get(schildAlias.getOrDefault(namensSchluessel(name), ""));
        var n = buch.nachschlagen(name, clan);
        if (n.isEmpty() || n.get().setPunkte() < 0) {
            // Idee 5 (A23): Name anders gelesen -- derselbe Spieler hat dieselbe Kampfkraft.
            Long k = staerken.get(namensSchluessel(name));
            if (k == null) k = staerken.get(schildAlias.getOrDefault(namensSchluessel(name), ""));
            if (k != null) {
                var perKraft = buch.nachKraft(k);
                if (perKraft.isPresent()) n = perKraft;
            }
        }
        if (e != null) {
            return einheitenPunkte(e.setPunkte(), e.guteArtefakte(), e.bauarbeiter(), e.einheiten())
                    + Gegnerbuch.steinPunkte(e.gelbeSteine(), e.roteSteine())
                    + extras(e, n.orElse(null)).punkte();
        }
        if (n.isPresent() && n.get().setPunkte() >= 0 && n.get().geprueftSeit(30)) {
            Gegnerbuch.Notiz x = n.get();
            return einheitenPunkte(x.setPunkte(), Math.max(0, x.artefakte()),
                    Math.max(0, x.bauarbeiter()), x.einheiten())
                    + Gegnerbuch.steinPunkte(x.gelbeSteine(), x.roteSteine())
                    + extras(null, x).punkte();
        }
        return null;
    }

    /** Schirmherrschaften und Pals eines Gegners (Autor, 01.10.). -1 = nicht angesehen. */
    private record Extras(int schirme, int guteSchirme, int stigmas, int gutePals) {
        int punkte() {
            // Unbekannt zaehlt wie ein typischer Gegner (Review 02.10.: "unbekannt darf nicht wie
            // schwach wirken"): fast alle angesehenen hatten 3 Schirmherrschaften, keine gute (+3);
            // bei den Pals ein halber guter (+2).
            return (schirme >= 0 ? Gegnerbuch.schirmPunkte(schirme, guteSchirme, stigmas)
                                 : Gegnerbuch.schirmPunkte(3, 0, 0))
                    + (gutePals >= 0 ? Gegnerbuch.palPunkte(gutePals)
                                     : (ArenaExtras.pals() ? 2 : 0));
        }

        /** "Schirm 2 (1 gut, 1 Stigma) +6  Pals 1 gut +5" fuer die Liste. */
        String text() {
            StringBuilder b = new StringBuilder();
            if (schirme >= 0) {
                b.append("Schirm ").append(schirme).append(" (").append(guteSchirme).append(" gut, ")
                 .append(stigmas).append(" Stigma) +").append(Gegnerbuch.schirmPunkte(schirme, guteSchirme, stigmas));
            }
            if (gutePals >= 0) {
                b.append(b.length() > 0 ? "  " : "").append("Pals ").append(gutePals).append(" gut +")
                 .append(Gegnerbuch.palPunkte(gutePals));
            }
            return b.toString();
        }
    }

    /** Aus der Pruefung dieses Turniers, sonst aus dem Notizblock. */
    private static Extras extras(ArenaPruefer.Ergebnis e, Gegnerbuch.Notiz n) {
        boolean eS = e != null && e.schirmAnzahl() >= 0, nS = n != null && n.schirmBekannt();
        boolean eP = e != null && e.gutePals() >= 0, nP = n != null && n.palsBekannt();
        return new Extras(eS ? e.schirmAnzahl() : nS ? n.schirmAnzahl() : -1,
                eS ? e.guteSchirme() : nS ? n.guteSchirme() : 0,
                eS ? e.guteStigmas() : nS ? n.guteStigmas() : 0,
                eP ? e.gutePals() : nP ? n.gutePals() : -1);
    }

    /** Sets + 3 je Artefakt + 5 je Bauarbeiter + 5 je Einheit unter 6. */
    static int einheitenPunkte(int sets, int artefakte, int bauarbeiter, int einheiten) {
        return Gegnerbuch.teamPunkte(sets, artefakte, bauarbeiter, einheiten);
    }

    /**
     * +15 fuer "meiden", +99 fuer "niemals". Beim Waehlen kommt "niemals"
     * gar nicht erst in die Auswahl; die 99 zeigt nur die Liste an.
     */
    private static int listenPunkte(Schild s) {
        return (s.gesperrt() ? 99 : 0) + (s.ungern() ? 15 : 0) - (s.sicher() ? 40 : 0)   // sicher -40 (Autor, 08.10., vorher -30)
                - (s.machbar() ? 15 : 0)   // ar_machbar_* (Autor, 01.10.; -15 seit 08.10., vorher -10)
                - (s.fastSicher() ? FAST_PUNKTE : 0)
                + (s.clanListe() && ArenaExtras.clans() ? CLAN_PUNKTE : 0);   // ar_clan_* (Autor, 01.10.)
    }

    /**
     * Aus dem Kampfprotokoll (Autor, 25.09.): hat mich dieser Gegner als
     * Angreifer besiegt, +30 -- er ist stark (Autor 25.09., erst +30). Ist er an mir
     * gescheitert, -20 (Autor 30.09., vorher -10) -- "heisst nicht, dass er immer
     * verliert": nur Punkte, keine feste Siegchance mehr.
     */
    private int protokollPunkte(String schluessel) {
        return (gegenMichGewonnen.contains(schluessel) ? 30 : 0)   // +30 seit 08.10. (vorher +20)
             - (gegenMichVerloren.contains(schluessel) ? 20 : 0);
    }

    /** Kampfkraft-Punkte: je Million mehr als Autor +1, weniger -1. */
    private int kraftPunkte(long seine) {
        return Gegnerbuch.kraftPunkte(seine, meineStaerke);
    }

    private void punkteVorbereiten(List<Schild> auswahl, List<Schild> alle) {
        punkteJetzt.clear();
        // Wer (noch) nicht geprueft ist, bekommt den Mittelwert der
        // Gepruefte -- sonst wirkte "unbekannt" wie "schwach".
        List<Integer> bekannt = new ArrayList<>();
        for (Schild s : auswahl) {
            Integer p = setUndArtefaktPunkte(s.name(), s.clan());
            if (p != null) bekannt.add(p);
        }
        bekannt.sort(Integer::compare);
        int ersatz = bekannt.isEmpty() ? 0 : bekannt.get(bekannt.size() / 2);

        punkteMin = Integer.MAX_VALUE;
        punkteMax = Integer.MIN_VALUE;
        // Punkte fuer ALLE Schilder (auch die, die gerade nicht zur Wahl stehen) -- die Zaehlung
        // "wie viele sind staerker als dein Team" braucht die ganze Pyramide. Rangfolge
        // (Min/Max) weiter nur ueber die Auswahl, wie bisher.
        int referenz = referenzPunkte();
        int staerker = 0;
        // Idee 8 (A04, 22.10.): auch wenige klar Schwaechere (unter 0,9x Kraft, Bots zaehlen dazu) schalten den Top-3-Modus
        // ein -- der alte Ausloeser (6 Teams staerker als deins) griff nur in 7 von 533 Kaempfen.
        int schwache = 0, kraftBekannt = 0;
        for (Schild s : alle) {
            long k = bekannteStaerke(s);
            if (s.botClan() || (k > 0 && meineStaerke > 0 && k < meineStaerke * 0.9)) schwache++;
            if (s.botClan() || k > 0) kraftBekannt++;
        }
        if (kraftBekannt >= 7 && !wenigSchwache && schwache < TOP3_UNTER_SCHWACHEN) {
            wenigSchwache = true;
            Log.info("Arena", Sprache.t("Top-3 mode: only " + schwache + " clearly weaker opponents (under 0.9x your power) in this "
                            + "tournament -- the later rounds get risky, safest fights from now on.",
                    "Top-3-Modus: nur " + schwache + " klar schwaechere Gegner (unter 0,9x deiner Kraft) in diesem Turnier -- die "
                            + "spaeteren Runden werden riskant, ab jetzt die sichersten Kaempfe."));
        }
        for (Schild s : alle) {
            Integer sa = setUndArtefaktPunkte(s.name(), s.clan());
            int p = (sa != null ? sa : ersatz) + kraftPunkte(bekannteStaerke(s)) + listenPunkte(s)
                    + protokollPunkte(namensSchluessel(s.name()));
            punkteJetzt.put(namensSchluessel(s.name()), p);
            // Fuer die Top-3-Zaehlung nur Team + Kraft (Pruefung 08.10.): Sperr-, Listen- und
            // Protokollpunkte sowie Ersatzwerte sagen nichts ueber die Staerke; Bots zaehlen nicht.
            if (referenz != Integer.MIN_VALUE && sa != null && !s.botClan()
                    && sa + kraftPunkte(bekannteStaerke(s)) > referenz) staerker++;
        }
        for (Schild s : auswahl) {
            Integer p = punkteJetzt.get(namensSchluessel(s.name()));
            if (p == null) continue;
            punkteMin = Math.min(punkteMin, p);
            punkteMax = Math.max(punkteMax, p);
        }
        // Bleibt fuer das Turnier stehen (Pruefung 08.10.): sonst wechselte der Top-3-Modus von
        // Bild zu Bild, je nachdem, welche Schilder gerade lesbar waren.
        staerkerAlsIch = Math.max(staerkerAlsIch, staerker);
        if (staerker >= TOP3_AB_STAERKEREN && !top3Gemeldet) {
            top3Gemeldet = true;
            Log.info("Arena", Sprache.t("Top-3 mode: " + staerker + " opponents have more points than "
                    + (MeinTeam.punkte() >= 0 ? "your team (" + referenz + ")" : "the best one you ever beat (" + referenz + ")")
                    + " -- only the safest fights from now on, 225 only against opponents recorded as \"safe\".",
                    "Top-3-Modus: " + staerker + " Gegner haben mehr Punkte als "
                    + (MeinTeam.punkte() >= 0 ? "dein Team (" + referenz + ")" : "der Beste, den du je geschlagen hast (" + referenz + ")")
                    + " -- nur noch sicherste Kaempfe, 225er nur als \"sicher\" aufgenommene Gegner."));
        }
    }

    /** Autor, 08.10.: ab so vielen Gegnern mit mehr Punkten als dein Team gilt "Top 3 reicht". */
    private static final int TOP3_AB_STAERKEREN = 6;
    /** Idee 8 (A04): unter so vielen klar Schwaecheren im Turnier gilt ebenfalls der Top-3-Modus. */
    private static final int TOP3_UNTER_SCHWACHEN = 5;
    /** Dieses Turnier hat weniger als TOP3_UNTER_SCHWACHEN klar schwaechere Gegner (bleibt bis Turnierende). */
    private boolean wenigSchwache;

    /** Top-3-Modus: viele Staerkere (Autor 08.10.) oder wenige klar Schwaechere (Idee 8). */
    private boolean top3Modus() {
        return staerkerAlsIch >= TOP3_AB_STAERKEREN || wenigSchwache;
    }
    /** Wie viele Gegner der Pyramide mehr Punkte haben als die Referenz (je Bild neu). */
    private int staerkerAlsIch;
    private boolean top3Gemeldet;

    /**
     * Woran die Punkte eines Gegners gemessen werden: an deinem eigenen Team (arena-ich.txt,
     * hoechstens 3 Tage alt), sonst am staerksten Gegner, den du je geschlagen hast.
     * Integer.MIN_VALUE, wenn es beides nicht gibt.
     */
    private int referenzPunkte() {
        int mein = MeinTeam.punkte();
        return mein >= 0 ? mein : hoechsteGeschlagenePunkte();
    }

    /**
     * Siegquote gegen Gegner mit aehnlich vielen Punkten, aus allen gemerkten Kaempfen:
     * jeder Gegner im Notizblock zaehlt mit seinen Siegen und Niederlagen, um so staerker, je
     * naeher seine Punktzahl an {@code punkte} liegt (Glocke, 10 Punkte breit).
     * @return {Quote, Gewicht in Kaempfen} oder null ohne Daten
     */
    private double[] erfahrung(int punkte) {
        double siege = 0, kaempfe = 0;
        for (Gegnerbuch.Notiz n : buch.alle()) {
            if (n.kaempfe() == 0 || n.teamPunkte() < 0) continue;
            int seine = n.teamPunkte() + kraftPunkte(n.staerke());
            double abstand = (punkte - seine) / 10.0;
            double w = Math.exp(-0.5 * abstand * abstand);
            siege += w * n.siege();
            kaempfe += w * n.kaempfe();
        }
        if (kaempfe <= 0) return null;
        // leichte Daempfung zur Mitte: zwei gedachte Kaempfe mit 60 %
        return new double[]{(siege + 1.2) / (kaempfe + 2.0), kaempfe};
    }

    /** Siegchance allein aus den Punkten, oder -1 wenn keine da sind. */
    private double punkteChance(Schild s) {
        Integer p = punkteJetzt.get(namensSchluessel(s.name()));
        if (p == null) return -1;
        if (punkteMax <= punkteMin) return 0.60;
        return 0.85 - 0.60 * (p - punkteMin) / (double) (punkteMax - punkteMin);
    }

    // ------------------------------------------------------------------
    //  Die Liste (Notizblock-Fenster und !liste, Autor 24.09.)
    // ------------------------------------------------------------------

    /** Hier steht die Gegnerliste des laufenden Turniers. */
    public static final java.nio.file.Path LISTE = java.nio.file.Path.of("arena-liste.txt");
    /** FREUNDE-FASSUNG: englische Legende ueber der (deutschen) Liste. */
    private static final String LISTE_LEGENDE_EN = """
            English key: Runde = round · deine Kraft = your power · Kraft = power · Sets = set points ·
            Artefakte = artifacts · Bauarbeiter = builders · Steine (gelb, rot) = stones (yellow, red) ·
            nur N Einheiten = only N units · Schirm = patronages (gut = good) · Pals (gut = good) · Punkte = points ·
            Bilanz = record (gewonnen = won, verloren = lost) · Kampf = fight · Notiz = note ·
            ZIEL = target · schon angegriffen = already attacked · HAT DICH BESIEGT = beat you ·
            AN DIR GESCHEITERT = lost to you · NIEMALS = never · MEIDEN = avoid · SICHERER SIEG = safe win ·
            MACHBAR = doable · CLAN-LISTE = clan list · Sets noch nicht angesehen = sets not looked at yet.
            Few points = weak opponent. The notebook (from 3 fights on) comes first.

            """;

    private static final java.nio.file.Path AUFHEBEN_DATEI = java.nio.file.Path.of("arena-aufheben.txt");
    private static final java.nio.file.Path ERZIELT_DATEI = java.nio.file.Path.of("arena-punkte-erzielt.txt");
    private java.util.List<String> aufhebenNamen = java.util.List.of();
    private long aufhebenStand = -1;
    private java.util.Map<String, Integer> erzieltJe = java.util.Map.of();
    private long erzieltStand = -1;

    /** Ab diesem Team-Wert (Sets, Artefakte, Steine, Extras) gilt ein sicher/machbar-Gegner als "sehr gutes Team" (11.10.). */
    private static final int SEHR_GUTES_TEAM = 70;

    /** Fuer Stage 4-5 aufheben: in arena-aufheben.txt, oder sicher/machbar mit sehr gutem Team (Autor, 11.10.). */
    private boolean spaetZiel(Schild s) {
        if (aufheben(s.name())) return true;
        if (!s.sicher() && !s.machbar() && !s.fastSicher()) return false;
        // 21.10. (Testturnier, Runde 5): "Myth Gods" stand nur AUTOMATISCH auf "sicher" (3 von 4 gewonnen) -- als
        // aufgehobener Spieler bekam er 92 % statt der echten 52 %, der Bot wagte den 225er und fiel auf Platz 7.
        // Aufheben gilt nur fuer Listen, die der Spieler selbst gesetzt hat.
        if (s.autoListe()) return false;
        Integer team = setUndArtefaktPunkte(s.name(), s.clan());
        return team != null && team >= SEHR_GUTES_TEAM;
    }

    /**
     * Steht der Name in arena-aufheben.txt (eine Zeile je Name, # = Kommentar)? Die Datei legt der Spieler
     * selbst an (Capture-Images fragt beim Aufnehmen danach); fehlt sie, ist die Liste leer. Wird neu
     * gelesen, wenn sie sich aendert.
     */
    private boolean aufheben(String name) {
        try {
            long stand = java.nio.file.Files.exists(AUFHEBEN_DATEI) ? java.nio.file.Files.getLastModifiedTime(AUFHEBEN_DATEI).toMillis() : 0;
            if (stand != aufhebenStand) {
                aufhebenStand = stand;
                aufhebenNamen = stand == 0 ? java.util.List.of() : de.hustlebot.core.Namensbilder.zeilenLesen(AUFHEBEN_DATEI)
                        .stream().map(String::strip).filter(z -> !z.isEmpty() && !z.startsWith("#")).toList();
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-aufheben.txt nicht lesbar: " + e.getMessage());
        }
        for (String n : aufhebenNamen) if (de.hustlebot.core.NamensVergleich.gleicherName(n, name)) return true;
        return false;
    }

    /** Die eigenen Punkte des Spielers gegen diesen Gegner aus arena-punkte-erzielt.txt ("Name | Punkte | Datei"), sonst -1. */
    private int erzieltePunkte(String name) {
        try {
            long stand = java.nio.file.Files.exists(ERZIELT_DATEI) ? java.nio.file.Files.getLastModifiedTime(ERZIELT_DATEI).toMillis() : 0;
            if (stand != erzieltStand) {
                erzieltStand = stand;
                java.util.Map<String, Integer> neu = new java.util.LinkedHashMap<>();
                if (stand > 0) {
                    for (String z : de.hustlebot.core.Namensbilder.zeilenLesen(ERZIELT_DATEI)) {   // UTF-8 oder ANSI (11.10.)
                        if (z.isBlank() || z.startsWith("#") || !z.contains("|")) continue;
                        String[] t = z.split("\\|");
                        try { neu.put(t[0].strip(), Integer.parseInt(t[1].strip())); } catch (NumberFormatException ignored) { }
                    }
                }
                erzieltJe = neu;
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-punkte-erzielt.txt nicht lesbar: " + e.getMessage());
        }
        for (var e : erzieltJe.entrySet()) {
            if (!e.getKey().isEmpty() && de.hustlebot.core.NamensVergleich.gleicherName(e.getKey(), name)) return e.getValue();
        }
        return -1;
    }

    /** Anzeigename und Clan je Vergleichsform. */
    private final java.util.Map<String, String[]> anzeigeNamen = new java.util.LinkedHashMap<>();
    private String zuletztGewaehlt = "";

    private void anzeigeMerken(String name, String clan) {
        String k = namensSchluessel(name);
        if (k.isEmpty()) return;
        // Die erste Lesart bleibt stehen, sonst wechselt der Name in der Liste
        // von Runde zu Runde ("Killshot", "Kinshot" ...). Nur ein fehlender
        // Clan wird nachgetragen.
        String c = clan == null ? "" : clan;
        String[] alt = anzeigeNamen.get(k);
        if (alt == null) anzeigeNamen.put(k, new String[]{name, c});
        else if (alt[1].isBlank() && !c.isBlank()) alt[1] = c;
    }

    /** Schreibt die Liste neu: je Gegner Kraft, Sets, Artefakte, Punkte, Bilanz. */
    private void listeSchreiben() {
        StringBuilder sb = new StringBuilder();
        // FREUNDE-FASSUNG (11.10.): Die Liste ist deutsch -- auf Englisch steht oben eine kurze Legende.
        if (Sprache.istEnglisch()) sb.append(LISTE_LEGENDE_EN);
        sb.append("Arena  ").append(java.time.LocalTime.now().withNano(0))
          .append("  ·  Runde ").append(Math.max(1, rundeZuletzt))
          .append("  ·  deine Kraft ").append(meineStaerke > 0 ? mio(meineStaerke) : "?")
          .append('\n');
        sb.append("Wenige Punkte = schwacher Gegner. Notizblock (ab 3 Kaempfen) geht vor.\n\n");

        List<Object[]> zeilen = new ArrayList<>();
        for (var e : anzeigeNamen.entrySet()) {
            String k = e.getKey(), name = e.getValue()[0], clan = e.getValue()[1];
            long st = staerken.getOrDefault(k, -1L);
            if (st <= 0) st = buch.nachschlagen(name, clan).map(Gegnerbuch.Notiz::staerke).orElse(-1L);
            Integer sa = setUndArtefaktPunkte(name, clan);
            int kraft = kraftPunkte(st);
            int liste = listenPunkteJe.getOrDefault(k, 0);
            int protokoll = protokollPunkte(k);
            Integer gesamt = sa == null ? null : sa + kraft + liste + protokoll;
            zeilen.add(new Object[]{k, name, clan, st, sa, kraft, gesamt});
        }
        // Wenigste Punkte oben, Unbekannte unten.
        zeilen.sort((a, b) -> {
            Integer ga = (Integer) a[6], gb = (Integer) b[6];
            if (ga == null && gb == null) return 0;
            if (ga == null) return 1;
            if (gb == null) return -1;
            return Integer.compare(ga, gb);
        });

        int nr = 1;
        for (Object[] z : zeilen) {
            String k = (String) z[0], name = (String) z[1], clan = (String) z[2];
            long st = (Long) z[3];
            Integer sa = (Integer) z[4];
            int kraft = (Integer) z[5];
            Integer gesamt = (Integer) z[6];

            sb.append(nr++).append(". ").append(name);
            if (!clan.isBlank()) sb.append("  ·  ").append(clan);
            if (k.equals(zuletztGewaehlt)) sb.append("   <- ZIEL");
            if (schonAngegriffen.contains(k)) sb.append("   (schon angegriffen)");
            String mark = listenTextJe.getOrDefault(k, "");
            if (gegenMichGewonnen.contains(k)) mark = (mark + "   HAT DICH BESIEGT (+30)").strip();
            if (gegenMichVerloren.contains(k)) mark = (mark + "   AN DIR GESCHEITERT (-20)").strip();
            if (!mark.isEmpty()) sb.append("   ").append(mark);
            sb.append('\n');

            sb.append("   Kraft ").append(st > 0 ? mio(st) : "?")
              .append(" (").append(kraft >= 0 ? "+" : "").append(kraft).append(")");
            ArenaPruefer.Ergebnis pe = pruefungen.get(k);
            var notiz = buch.nachschlagen(name, clan);
            if (sa != null) {
                String sets = pe != null ? pe.sets() : notiz.map(Gegnerbuch.Notiz::sets).orElse("");
                int arte = Math.max(0, pe != null ? pe.guteArtefakte()
                        : notiz.map(Gegnerbuch.Notiz::artefakte).orElse(0));
                int bau = Math.max(0, pe != null ? pe.bauarbeiter()
                        : notiz.map(Gegnerbuch.Notiz::bauarbeiter).orElse(0));
                int einh = pe != null ? pe.einheiten() : notiz.map(Gegnerbuch.Notiz::einheiten).orElse(-1);
                int fehlend = einh > 0 ? Math.max(0, 6 - einh) : 0;
                // Die Set-Punkte direkt, nicht aus der Summe zurueckgerechnet:
                // sonst stimmt die Anzeige nicht mehr, sobald sich die Formel aendert.
                int setP = pe != null ? pe.setPunkte() : notiz.map(Gegnerbuch.Notiz::setPunkte).orElse(0);
                int gelb = pe != null ? pe.gelbeSteine() : notiz.map(Gegnerbuch.Notiz::gelbeSteine).orElse(0);
                int rot = pe != null ? pe.roteSteine() : notiz.map(Gegnerbuch.Notiz::roteSteine).orElse(0);
                sb.append("  Sets ").append(setP)
                  .append("  Artefakte +").append(3 * arte);
                if (bau > 0) sb.append("  Bauarbeiter +").append(5 * bau);
                if (gelb + rot > 0) sb.append("  Steine +").append(Gegnerbuch.zehntelText(Gegnerbuch.steinZehntel(gelb, rot)))
                                      .append(" (").append(gelb).append(" gelb, ").append(rot).append(" rot)");
                if (fehlend > 0) sb.append("  nur ").append(einh).append(" Einheiten +").append(5 * fehlend);
                Extras ex = extras(pe, notiz.orElse(null));
                if (ex.punkte() > 0 || ex.schirme() >= 0 || ex.gutePals() >= 0) {
                    sb.append("  ").append(ex.text());
                }
                String mark2 = listenTextJe.getOrDefault(k, "");
                if (!mark2.isEmpty()) sb.append("  ").append(mark2);
                sb.append("  = ").append(gesamt).append(" Punkte\n");
                if (!sets.isBlank()) sb.append("   [").append(sets).append("]\n");
            } else {
                sb.append("  Sets noch nicht angesehen\n");
            }
            if (notiz.isPresent() && notiz.get().kaempfe() > 0) {
                Gegnerbuch.Notiz n = notiz.get();
                sb.append("   Bilanz ").append(n.siege()).append("x gewonnen, ")
                  .append(n.kaempfe() - n.siege()).append("x verloren");
                if (n.mittlereDauer() > 0) sb.append("  ·  Kampf ~").append(n.mittlereDauer()).append(" s");
                sb.append('\n');
            }
            // Autors eigene Notiz (!notiz), damit er sie im Turnier gleich sieht.
            notiz.filter(n -> !n.notiz().isBlank())
                 .ifPresent(n -> sb.append("   Notiz: ").append(n.notiz()).append('\n'));
            sb.append('\n');
        }
        try {
            java.nio.file.Files.writeString(LISTE, sb.toString());
        } catch (Exception e) {
            Log.debug("Arena", "arena-liste.txt nicht geschrieben: " + e.getMessage());
        }
    }

    private static String mio(long v) {
        return String.format(java.util.Locale.GERMANY, "%.1f Mio", v / 1_000_000.0);
    }

    /** Fuer Protokoll und Liste: "12 Punkte (Sets 9, Artefakte +3, Kraft 0)". */
    private String punkteText(Schild s) {
        Integer p = punkteJetzt.get(namensSchluessel(s.name()));
        Integer sa = setUndArtefaktPunkte(s.name(), s.clan());
        return Sprache.t((p == null ? "? points" : p + " points")
                + " (team " + (sa == null ? "unknown" : sa)
                + (bekannteStaerke(s) > 0 && meineStaerke > 0 ? ", " + Gegnerbuch.kraftHinweis(bekannteStaerke(s), meineStaerke) : ", power unknown") + ")",
                (p == null ? "? Punkte" : p + " Punkte")
                + " (Team " + (sa == null ? "unbekannt" : sa)
                + (bekannteStaerke(s) > 0 && meineStaerke > 0 ? ", " + Gegnerbuch.kraftHinweis(bekannteStaerke(s), meineStaerke) : ", Kraft unbekannt") + ")");
    }

    /**
     * Arena-Ideen 2, 5 und 6 (aus dem Hauptbot d2584de; doku/ARENA-IDEEN-16-10.md, Autor 16.10.: "alle Ideen einbauen").
     * Laeuft direkt nach der bisherigen Schaetzung, die Regeln danach gelten weiter.
     */
    private double ideenAnpassen(Schild s, double chance) {
        long seine = bekannteStaerke(s);
        double r = seine > 0 && meineStaerke > 0 ? seine / (double) meineStaerke : -1;
        // Idee 2: das Kraftverhaeltnis zuerst (Autors Kaempfe 08.-15.10.: unter 0,75x 30/30, 0,75-0,9x 28/29,
        // 0,9-1,0x 14/14, 1,0-1,1x 2/3, ab 1,1x 3/10). 70 % Kraft, 30 % die bisherige Schaetzung.
        // 22.10. (Ideen A14/A15, Rueckspiel werkzeuge/arena-rueckspiel.py an 533 Kaempfen): die Tabelle ohne Spruenge
        // (kraftBasis) und unter 0,9x fast nur die Kraft -- dort gewann der Bot 414 von 419, selbst mit 60-80 Team-Punkten
        // 30 von 32. Ab 0,9x entscheiden die Punkte (unter 40: 36/37, 60-80: 13/26), dort zaehlt die bisherige Schaetzung
        // zu 70 %. Brier-Wert 0,0467 -> 0,0432, in beiden Haelften der Daten besser.
        if (r > 0) {
            double w = r < 0.9 ? 0.05 : 0.7;
            // Pruefung 29.10.: gegen 響蛎茶多 (0,88x) viermal verloren -- die Rechnung sagte 67 %, gezeigt wurden 92 %, weil
            // unter 0,9x Team und Erfahrung nur zu 5 % zaehlten. Hat DIESER Spieler dich schon zweimal geschlagen, zaehlen
            // sie wie ab 0,9x (insgesamt bleibt die Regel richtig: 0,75-0,9x mit gleich starkem Team 42 von 45 gewonnen).
            if (r < 0.9) {
                var notiz = buch.nachschlagen(s.name(), s.clan());
                if (notiz.isPresent() && notiz.get().kaempfe() - notiz.get().siege() >= 2) w = 0.7;
            }
            chance = (1 - w) * kraftBasis(r) + w * chance;
        }
        // Idee 5: "sicher" mindestens 90 %, "machbar" mindestens 80 %, 3+ Kaempfe alle gewonnen mindestens 90 % --
        // solange er weniger Kraft hat als du.
        if (r > 0 && r < 1.0) {
            if (s.sicher()) chance = Math.max(chance, 0.90);
            else if (s.fastSicher()) chance = Math.max(chance, FAST_MINDESTENS);
            else if (s.machbar()) chance = Math.max(chance, 0.80);
            var n = buch.nachschlagen(s.name(), s.clan());
            if (n.isPresent() && n.get().kaempfe() >= 3 && n.get().siege() == n.get().kaempfe()) chance = Math.max(chance, 0.90);
        }
        // Autor 22.10. abends (Turnier 22:19): "Der Spieler unten links hatte nur 300, der andere 648 -- ein Anzeichen, dass er
        // fast keine Kaempfe gewonnen hat, da es ja schon Stage 5 ist." Turnierpunkte kommen nur aus eigenen Angriffen: wer ab
        // Stage 4 hoechstens (Stage - 3) x 150 Punkte hat, hat mindestens zwei seiner Angriffe verloren -> +5 %.
        if (tabelle != null && tabelleRunde == rundeZuletzt && rundeZuletzt >= 4) {
            int platz = platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt);
            if (platz > 0 && platz < tabelle.length && platz != aktuellerPlatz && tabelle[platz] >= 0
                    && tabelle[platz] <= 150L * (rundeZuletzt - 3)) {
                double vorher = chance;
                chance = Math.min(0.95, chance + 0.05);
                if (bonusGemeldet.add(namensSchluessel(s.name()) + "#p")) {
                    Log.info("Arena", Sprache.t(s.wer() + " has only " + tabelle[platz] + " tournament points in stage " + rundeZuletzt
                                    + " -- lost at least two attacks, win chance +5 % (" + Math.round(vorher * 100) + " -> " + Math.round(chance * 100) + " %).",
                            s.wer() + " hat in Stage " + rundeZuletzt + " erst " + tabelle[platz] + " Turnierpunkte -- mindestens zwei "
                                    + "Angriffe verloren, Siegchance +5 % (" + Math.round(vorher * 100) + " -> " + Math.round(chance * 100) + " %)."));
                }
            }
        }
        // Idee 6: Turnier-Form aus dem Kampfprotokoll: schon verloren -> +5 %; 3 Siege ohne Niederlage -> hoechstens 70 %.
        String k = namensSchluessel(s.name());
        if (!k.isEmpty()) {
            int nieder = verlorenGegen.getOrDefault(k, java.util.Set.of()).size();
            int siege = 0;
            for (java.util.Set<String> sieger : verlorenGegen.values()) if (sieger.contains(k)) siege++;
            // Idee 15 (A19, 22.10.): in Stufen -- 3 Siege hoechstens 70 %, ab 4 Siegen hoechstens 55 %. Bei 70-74 % gewann der
            // Bot nur 19 von 30 Kaempfen, in Runde 5 bei 70-79 % nur 14 von 21.
            if (nieder == 0 && siege >= 4) chance = Math.min(chance, 0.55);
            else if (nieder == 0 && siege >= 3) chance = Math.min(chance, 0.70);
            else if (nieder >= 1) chance = Math.max(chance, Math.min(0.95, chance + 0.05));   // nie nach unten (Idee 10)
        }
        // Idee 16 (A20, 22.10.): dauerte der letzte Kampf gegen ihn ueber 10 s, war es knapp -- 8 Prozentpunkte weniger.
        // Nach einem Kampf bis 10 s gewann der Bot den naechsten 39 von 41 Mal, nach 11-15 s 46 von 55, darueber 22 von 26.
        var letzter = buch.nachschlagen(s.name(), s.clan());
        if (letzter.isPresent() && letzter.get().letzteDauer() > 10) chance -= 0.08;
        // Idee 10 (A16, 22.10.): unter 0,75x Kraft gewann der Bot 264 von 264, bei 0,75-0,9x 150 von 155 -- beide standen
        // bei hoechstens 95 %, der Bot konnte unter den Sicheren nicht sortieren. Jetzt bis 99 % bzw. 97 %.
        double deckel = r > 0 && r < 0.75 ? 0.99 : r > 0 && r < 0.9 ? 0.97 : 0.95;
        return Math.max(0.05, Math.min(deckel, chance));
    }

    /** Stuetzstellen {Kraftverhaeltnis seine/meine, Siegchance}, dazwischen gerade Linien (Idee A14, 22.10.). */
    private static final double[][] KRAFT_STUETZEN = {
            {0.0, 0.99}, {0.75, 0.99}, {0.9, 0.93}, {1.0, 0.82}, {1.1, 0.72}, {1.3, 0.50}, {1.6, 0.25}};

    /**
     * Siegchance nur aus dem Kraftverhaeltnis, ohne Spruenge. Echte Quoten (533 Kaempfe bis 22.10.): unter 0,75x
     * 264/264, 0,75-0,9x 150/155, 0,9-1,0x 56/72, 1,0-1,1x 20/23, 1,1-1,3x 8/11, darueber 3/8.
     */
    static double kraftBasis(double r) {
        double[][] s = KRAFT_STUETZEN;
        if (r >= s[s.length - 1][0]) return s[s.length - 1][1];
        for (int i = 1; i < s.length; i++) {
            if (r < s[i][0]) {
                double t = (r - s[i - 1][0]) / (s[i][0] - s[i - 1][0]);
                return s[i - 1][1] + (s[i][1] - s[i - 1][1]) * Math.max(0, t);
            }
        }
        return s[0][1];
    }

    /** Ab dieser Siegchance darf ein 225er ueberhaupt gewaehlt werden (Autor, 08.10.). */
    private static final double SICHER_FUER_225 = 0.85;   // Autor, 08.10., 14:45: "mindestens 85 %"

    /**
     * Wie begehrenswert ein Gegner ist. Hoeher ist besser.
     *
     * Autor, 08.10.: "Einen 225er nur, wenn du dir absolut sicher bist -- aufgenommen als
     * 'sicher' oder unglaublich wenig Punkte. Lieber 150 bei 90 % als 225 bei 10 %. Top 1-3
     * ist super, Platz 4 bis letzter ist scheisse." Darum zaehlt ein 225er ohne Sicherheit
     * nur wie ein 150er, und die Chance geht im Quadrat ein: eine Niederlage bringt null.
     */
    private double wert(Schild s, int runde) {
        double chance = wahlChance(s, runde);
        // In Runde 1 gibt jeder 150. Dann zaehlt nur die Sicherheit.
        double punkte = (runde <= 1) ? 150 : s.punkte();
        // Top-3-Modus (Autor, 08.10.): ab 6 Gegnern mit mehr Punkten als dein Team zaehlt ein
        // 225er nur noch, wenn er als "sicher" aufgenommen ist -- sonst wie ein 150er.
        boolean top3 = top3Modus();
        boolean darf = darf225(s, runde, chance);
        if (punkte > 150 && !darf) punkte = 150;
        // REIHENFOLGE ALS RICHTLINIE (Autor 21.10.): "wenn moeglich Stage 1-3 je ein 150er, Stage 4-5 je ein 225er" (3 x 150
        // + 2 x 225) -- "nicht so streng, nur ein Tipp, wie viele Arena-Profis spielen. Der Bot soll trotzdem nach Siegchance
        // und Sets arbeiten." Darum nur ein Schubs: ein schlagbarer 225er ist in Stage 2-3 etwas weniger wert (x 0,8, fuer
        // Stage 4-5 aufheben), in Stage 4-5 etwas mehr (x 1,1). Die Siegchance entscheidet weiter.
        // Ausnahme Idee 10: aufgehobene Spieler, wenn du schon in den Top 3 bist (spaeter steht vielleicht keiner mehr ueber dir).
        boolean idee10 = spaetZiel(s) && runde >= 2 && runde <= 3 && s.punkte() > 150 && aktuellerPlatz > 0 && aktuellerPlatz <= 3;
        double[] folge = reihenfolge(runde, s.punkte(), punkte, darf, idee10);
        punkte = folge[0];
        double aufhebenFaktor = folge[1];
        punkte = punkteNachDauer(s, punkte);
        // Im Top-3-Modus zaehlt die Sicherheit noch staerker (Chance hoch drei).
        return (top3 ? chance * chance * chance : chance * chance) * punkte * aufhebenFaktor;
    }

    /** Punkte abzueglich langer Kaempfe und Autors eigener Erfahrung gegen diesen Spieler (frueher Teil von wert()). */
    private double punkteNachDauer(Schild s, double punkte) {
        punkte = punkteVorDauer(s, punkte);
        // Ueber 10 s Kampfdauer gibt es weniger Punkte (gemessen 01.10.: 14 s -> 145, 15 s -> 144,
        // also rund 1,2 je Sekunde). Wer laut Notizblock lange braucht, ist etwas weniger wert.
        int dauer = buch.nachschlagen(s.name(), s.clan()).map(Gegnerbuch.Notiz::mittlereDauer).orElse(-1);
        // Autor 30.10.: noch nie angegriffen -- wie Gegner mit aehnlicher Siegchance, ohne genug Vergleiche 220 und 149.
        if (dauer < 0 && de.hustlebot.core.ArenaRegelnNeu.an()) {
            List<Integer> aehnlich = aehnlicheDauern(s);
            if (aehnlich.isEmpty()) return ArenaVergleich.ohneVergleich(punkte);
            double abzug = 0;
            for (int d : aehnlich) abzug += dauerAbzug(punkte, d);
            return punkte - abzug / aehnlich.size();
        }
        // Idee 8: ohne Notizblock-Dauer aus dem Kraftverhaeltnis schaetzen (unter 0,75x 8 s, bis 1,0x 12 s, sonst 15 s).
        if (dauer < 0 && bekannteStaerke(s) > 0 && meineStaerke > 0) {
            double r = bekannteStaerke(s) / (double) meineStaerke;
            dauer = r < 0.75 ? 8 : r < 1.0 ? 12 : 15;
        }
        return punkte - dauerAbzug(punkte, dauer);
    }

    /** Autors eigene Punkte gegen diesen Spieler (beim Aufnehmen mit capture.bat eingetragen, 11.10.): so viel weniger als der volle Lohn seiner damaligen Stufe (225 oder 150), so viel weniger auch jetzt. */
    private double punkteVorDauer(Schild s, double punkte) {
        int erzielt = erzieltePunkte(s.name());
        return erzielt > 0 ? Math.max(0, punkte - ((erzielt > 150 ? 225 : 150) - erzielt)) : punkte;
    }

    /**
     * So viele Punkte kostet ein langer Kampf. Gemessen 30.10. an allen Siegen in arena-lernen.txt: bis 10 s voller Lohn,
     * danach je Sekunde rund 1/130 des Lohns -- beim 150er 14 s -> 145, 20 s -> 139, beim 225er 14 s -> 218, 15 s -> 216,
     * 20 s -> 208. Bis 30.10. galten fuer beide 1,2 je Sekunde: beim 225er fehlten so 20 s -> 213 statt 208.
     * Hoechstens ein Fuenftel des Lohns (vorher 30 bei 150).
     */
    static double dauerAbzug(double punkte, int dauer) {
        if (dauer <= 10 || punkte <= 0) return 0;
        return Math.min(punkte * 0.2, punkte * (dauer - 10) / 130.0);
    }

    /** Ab dieser Stage bekommt ein schlagbarer 225er den Schubs nach oben (Richtlinie 3 x 150 + 2 x 225, Autor 21.10.). */
    static final int STAGE_225_AB = 4;
    /** So viel weniger ist ein schlagbarer 225er in Stage 2-3 wert -- lieber fuer Stage 4-5 aufheben. */
    static final double AUFHEBEN_225_FAKTOR = 0.8;
    /** So viel mehr ist ein schlagbarer 225er in Stage 4-5 wert. */
    static final double STAGE45_225_FAKTOR = 1.1;

    /**
     * Richtlinie 3 x 150 + 2 x 225: {Punkte, Faktor} eines Gegners in dieser Stage. Nur ein Schubs -- die Punkte bleiben,
     * nur ein schlagbarer 225er bekommt in Stage 2-3 x 0,8 und in Stage 4-5 x 1,1.
     * @param belohnung was im Schild steht (150 / 225)
     * @param punkte    die Punkte bisher (schon auf 150 gekappt, wenn er nicht als 225er zaehlen darf)
     * @param darf225   ist er ein schlagbarer 225er?
     * @param idee10    aufgehobener Spieler, und du bist schon in den Top 3
     */
    static double[] reihenfolge(int runde, long belohnung, double punkte, boolean darf225, boolean idee10) {
        if (belohnung <= 150 || !darf225 || runde <= 1) return new double[]{punkte, 1.0};
        if (runde < STAGE_225_AB) return new double[]{punkte, idee10 ? 1.0 : AUFHEBEN_225_FAKTOR};
        return new double[]{punkte, STAGE45_225_FAKTOR};
    }

    /** Zielart-Korrektur: so viel steigt ein 150er (und ein 225er in Stage 2), gemessen in Log-Chancen (30.10.). */
    static final double UNTER_DIR_LOGIT = 1.0;
    /** So viel faellt ein 225er ab Stage 3, wenn er mindestens 0,9x deiner Kraft hat (Log-Chancen, 30.10.). */
    static final double UEBER_DIR_LOGIT = 0.7;
    /** Hoechstens so viel nach der Korrektur -- das Seitenfenster soll nie "100 %" zeigen. */
    static final double ZIELART_HOECHSTENS = 0.994;

    /**
     * Zielart-Korrektur (Auswertung 30.10., arena-lernen.txt, 614 Kaempfe in Stage 2-5 mit bekanntem Ziel). Bei gleicher
     * geschaetzter Chance gewann der Bot gegen 150er viel oefter als gegen 225er -- ein 225er steht in der Turniertabelle
     * UEBER dir, hat also seine Kaempfe gewonnen; ein 150er steht darunter und hat verloren. Das sah die Rechnung nicht:
     * <ul>
     *   <li>150er (Stage 2-5): 414 von 421 gewonnen (98,3 %), geschaetzt 92 %; bei 50-89 % Schaetzung 47 von 50.</li>
     *   <li>225er in Stage 2: 43 von 43 gewonnen, geschaetzt 90 % -- in Stage 2 hat jeder erst einen Kampf hinter sich.</li>
     *   <li>225er ab Stage 3 mit mindestens 0,9x deiner Kraft: 45 von 79 (57 %), geschaetzt 69 %. Unter 0,9x stimmte die
     *       Schaetzung (85 % zu 89 %, unter 0,75x 18 von 18) -- dort bleibt sie.</li>
     *   <li>"Not in clan" (171 von 171 gewonnen) und Gegner auf der Liste "sicher"/"machbar" (225er: 21 von 22) waren
     *       nicht ueberschaetzt -- als 225er bleiben sie, wie sie sind.</li>
     * </ul>
     * Die Verschiebung ist in Log-Chancen gerechnet, damit die Reihenfolge innerhalb einer Zielart bleibt (aus 80 % wird
     * beim 150er 92 %, aus 95 % 98 %; beim 225er aus 90 % 82 %, aus 70 % 54 %). An den Kaempfen bis 22.10. abends
     * eingestellt, an den 132 Kaempfen danach geprueft: Brier-Wert 0,0434 -> 0,0325 (kleiner = besser).
     * Autors Grenzen (Angstgegner hoechstens 60 %, einmal verloren hoechstens 75 %) wirken danach unveraendert.
     * @param belohnung      was im Schild steht (150 / 225)
     * @param verhaeltnis    seine Kraft / deine Kraft, -1 = unbekannt (dann bleibt ein 225er, wie er ist)
     * @param gelistet       "Not in clan" oder auf der Liste "sicher"/"machbar" (dann bleibt ein 225er, wie er ist)
     */
    static double zielArtKorrektur(double chance, int runde, long belohnung, double verhaeltnis, boolean gelistet) {
        if (chance <= 0 || chance >= 1) return chance;
        boolean ueberDir = belohnung > 150 && runde >= 3;
        if (ueberDir && (verhaeltnis < 0.9 || gelistet)) return chance;
        // Pruefung 30.10.: Der Zuschlag galt auch fuer einen 225er in Stage 2 mit Kraft ab 1,0x (01:25: 85 % -> 94 %, verloren;
        // ab 1,0x gewann der Bot nur 2 von 4). Darunter bleibt er: 43 von 43 gewonnen.
        if (belohnung > 150 && !ueberDir && (verhaeltnis < 0 || verhaeltnis >= 1.0)) return chance;
        double logit = Math.log(chance / (1 - chance)) + (ueberDir ? -UEBER_DIR_LOGIT : UNTER_DIR_LOGIT);
        double neu = 1 / (1 + Math.exp(-logit));
        return ueberDir ? neu : Math.min(Math.max(chance, ZIELART_HOECHSTENS), neu);
    }

    /** Unter dieser rohen Siegchance hebt "aufgehoben" nicht mehr auf 92 % an: dagegen spricht schon etwas (Autor 01.11.). */
    static final double AUFHEBEN_NUR_AB = 0.60;

    /**
     * Autor 01.11. (Stage 4, Myth Gods): Top-3-Rechnung 92 % (aufgehoben, ab Stage 4 mindestens 92 %), Klick-Zeile 47 % -- zwei Zahlen fuer denselben Klick,
     * verloren, Platz 5 statt 1. Der Boden fuer aufgehobene Spieler gilt nur, wenn die rohe Siegchance nicht deutlich dagegen spricht; bei Widerspruch
     * gilt die vorsichtigere Zahl.
     */
    static double aufgehobenAnheben(double chance, double roh, int runde, boolean aufgehoben) {
        if (!aufgehoben || runde < 4 || roh < AUFHEBEN_NUR_AB) return chance;
        return Math.max(chance, LETZTER_PLATZ_CHANCE);
    }

    /** Die Siegchance fuer die Wahl: siegchance() plus Platz in der Pyramide und aufgehobene Spieler. */
    private double wahlChance(Schild s, int runde) {
        double chance = siegchance(s);
        final double roh = chance;
        // Zielart-Korrektur (Auswertung 30.10.): 150er wurden unterschaetzt, 225er ab Stage 3 mit 0,9x Kraft und mehr
        // ueberschaetzt. arena-lernen.txt schreibt weiter die rohe siegchance() -- daran ist die Korrektur gemessen.
        long seine = bekannteStaerke(s);
        double verhaeltnis = seine > 0 && meineStaerke > 0 ? seine / (double) meineStaerke : -1;
        chance = zielArtKorrektur(chance, runde, s.punkte(), verhaeltnis, s.botClan() || s.sicher() || s.machbar() || s.fastSicher());
        // Autor 01.11.: Platz-Regeln entfernt (Platz 10 mindestens 92 %, Platz 7-9 +10 %, beide seit 11.10.). Sie hoben nur Gegner
        // mit unbekanntem Team an: der Spieler mit 72 Mio Kraft galt auf Platz 10 als fast sicher.
        // Autor, 11.10.: "Ein paar Spieler sind alle sehr gut -- er soll sie erst am Ende angreifen, Stage 4 bis 5,
        // wenn sie weiter oben sind und er mehr Punkte bekommt. Gegen die gewinne ich mit Leichtigkeit, meist
        // 215 Punkte." Namen in arena-aufheben.txt: Stage 1-3 kaum (Chance x0,4), ab Stage 4 wie "sicher" -- 225 zaehlt.
        // Dazu (Autor, 11.10.): "Spieler, die sicher oder machbar getaggt sind, aber ein sehr gutes Team haben -- auch erst
        // Stage 4 bis 5. Die stehen meistens ueber mir, der Bot holt dann mehr Punkte." Sehr gut = Team ab 70.
        boolean aufgehoben = spaetZiel(s);
        // Idee 10: aufgehobene 225er in Runde 2-3 nicht mehr zurueckhalten, wenn du schon in den Top 3 bist.
        boolean jetztNehmen = runde >= 2 && runde <= 3 && s.punkte() > 150 && aktuellerPlatz > 0 && aktuellerPlatz <= 3;
        if (aufgehoben && runde <= 3 && !jetztNehmen) chance *= 0.4;
        chance = aufgehobenAnheben(chance, roh, runde, aufgehoben);
        if (s.botClan() && de.hustlebot.core.ArenaRegelnNeu.an() && buch.nachschlagen(s.name(), s.clan()).isEmpty()) {
            chance = ohneClanNachPunkten(chance, runde, arenaPunkteAuf(platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt)));
        }
        // 29.10.: siegchance() hat "einmal verloren" schon abgezogen -- hier nur noch deckeln, nicht ein zweites Mal
        // 15 Punkte abziehen (sonst sah das Seitenfenster 29 %, die Wahl rechnete mit 14 %).
        double ergebnis = deckeln(s, chance, false);
        if (de.hustlebot.schicht.ArenaStand.an()) schichtWahl.put(schichtSchluessel(s), ergebnis);
        return ergebnis;
    }

    /**
     * Darf dieser Gegner als 225er zaehlen ("machbarer 225er")? "sicher", aufgehoben (ab Stage 4), klar schwaecher
     * (unter 90 % deiner Kraft) ab 75 %, sonst 85 % mit geprueftem Team und nicht im Top-3-Modus.
     */
    private boolean darf225(Schild s, int runde, double chance) {
        // 08.10. (Nahrungs-Lauf, Runde 2: Сибиряк, damals Platz 1): Team UND Kraft unbekannt bekamen den Ersatzwert
        // (Mittel der Gepruften) und damit 85 % -- genug fuer einen 225er. Ein 225er braucht jetzt eine echte
        // Pruefung oder "sicher".
        boolean ungeprueft = setUndArtefaktPunkte(s.name(), s.clan()) == null;
        // Autor 16.10.: "85 % Siegchance hin oder her. Am Ende geht's darum, dass der Bot Top 1-3 schafft." Auswertung
        // (doku/ARENA-IDEEN-16-10.md, Idee 1): Gegner unter 0,9x deiner Kraft 58 von 59 gewonnen; fuenf Siege ohne 225er
        // endeten 4 von 4 auf Platz 3, mit einem 225er Platz 1, 1, 2, 2, 3. Darum zaehlt ein 225er gegen einen KLAR
        // schwaecheren Gegner (Kraft unter 90 %) schon ab 75 % voll -- auch im Top-3-Modus und ohne Teampruefung.
        if (angstgegner(s)) return false;   // Idee A01: kein 225er gegen einen Angstgegner
        long seine = bekannteStaerke(s);
        boolean klarSchwaecher = seine > 0 && meineStaerke > 0 && seine < meineStaerke * 0.9;
        boolean top3 = top3Modus();
        boolean spaetAufgehoben = spaetZiel(s) && runde >= 4;
        return (s.sicher() && !s.autoListe()) || (s.fastSicher() && runde >= 4) || spaetAufgehoben || (klarSchwaecher && chance >= 0.75)
                || (chance >= SICHER_FUER_225 && !top3 && !ungeprueft);
    }

    /**
     * Die meisten Punkte (Team + Kraft), die ein je geschlagener Gegner hatte -- der Massstab
     * fuer die Siegchance seit 08.10. Integer.MIN_VALUE, wenn der Notizblock noch keinen
     * geschlagenen Gegner mit Punkten kennt.
     */
    private int hoechsteGeschlagenePunkte() {
        int beste = Integer.MIN_VALUE;
        for (Gegnerbuch.Notiz n : buch.alle()) {
            if (n.siege() == 0 || n.teamPunkte() < 0) continue;
            beste = Math.max(beste, n.teamPunkte() + kraftPunkte(n.staerke()));
        }
        return beste;
    }

    /**
     * Siegchance aus dem Punkte-Abstand zur Referenz (staerkster je geschlagener Gegner, oder
     * dein Team +6). Autor, 08.10.: "sei ein bisschen haerter": 30 und mehr darunter 90 %, auf
     * gleicher Hoehe 65 %, 25 darueber 25 %, ab 50 darueber nur noch 5 %. Beispiel 08.10.:
     * Доктор lag 72 ueber dem Besten (134 zu 62) -- 5 %, nicht 88 %.
     */
    static double chanceNachAbstand(int abstand) {
        if (abstand <= -30) return 0.90;
        if (abstand <= 0) return 0.65 + 0.25 * (-abstand) / 30.0;
        if (abstand <= 25) return 0.65 - 0.40 * abstand / 25.0;
        if (abstand <= 50) return Math.max(0.05, 0.25 - 0.20 * (abstand - 25) / 25.0);
        return 0.05;
    }

    /**
     * Wie wahrscheinlich ein Sieg gegen diesen Gegner ist.
     *
     * Die Reihenfolge der Merkmale ist keine Geschmacksfrage, sie folgt
     * ihrer Verlaesslichkeit:
     *
     *  1. DER NOTIZBLOCK. Was Autor gegen genau diesen Gegner erlebt hat,
     *     schlaegt jede Schaetzung. Erst ab drei Kaempfen -- "1 mal ist
     *     keinmal".
     *  2. DAS KAMPFPROTOKOLL dieser Runde. Wer gerade eben gegen Autor
     *     angetreten und verloren hat, faellt mit hoher Wahrscheinlichkeit
     *     noch einmal.
     *  3. "NOT IN CLAN" -- das sind meist Bots, und die schlaegt Autor
     *     nach eigener Aussage fast immer.
     *  4. Sonst offen. Der Bot tut dann NICHT so, als wuesste er etwas.
     *
     * Gesehene Artefakte ziehen den Wert herunter, sperren aber niemanden:
     * "heisst es nicht das ich nicht gegen ihn gewinnen kann, nur die
     * chancen stehen schlechter."
     */
    /**
     * Was der Bot ueber die Staerke dieses Gegners weiss.
     *
     * Zuerst die Erkundung dieses Turniers -- die ist von heute. Sonst der
     * Notizblock, der auch aeltere Turniere kennt.
     *
     * Verglichen wird ueber die Vergleichsform des Namens, nicht Zeichen
     * fuer Zeichen. Die Texterkennung liest denselben Namen im Schild und
     * im Vergleichsfenster nicht immer gleich.
     */
    private long bekannteStaerke(Schild s) {
        Long ausErkundung = staerken.get(namensSchluessel(s.name()));
        if (ausErkundung == null) ausErkundung = staerken.get(schildAlias.getOrDefault(namensSchluessel(s.name()), ""));
        if (ausErkundung != null && ausErkundung > 0) return ausErkundung;
        return buch.nachschlagen(s.name(), s.clan())
                .map(Gegnerbuch.Notiz::staerke).filter(v -> v > 0).orElse(-1L);
    }

    /** {Siegquote, Kaempfe} ueber alle Gegner im Notizblock, oder null ohne Kaempfe. */
    private double[] gesamtBilanz() {
        double siege = 0, kaempfe = 0;
        for (Gegnerbuch.Notiz n : buch.alle()) {
            siege += n.siege();
            kaempfe += n.kaempfe();
        }
        return kaempfe > 0 ? new double[]{siege / kaempfe, kaempfe} : null;
    }

    /** Mehr als der Platzhalter "unbekannt" darf eine Rechnung ohne Team und ohne Kraft nicht ergeben (Autor 01.11.). */
    static final double UNBEKANNT_HOECHSTENS = 0.50;

    /** Weder Team noch Kraft bekannt, kein "Not in clan", keine Liste von Autor: aus Punkten und Erfahrung darf keine hohe Chance werden. */
    private boolean wissenLos(Schild s) {
        return wissenLos(s.botClan(), s.sicher() || s.fastSicher() || s.machbar(), bekannteStaerke(s) > 0,
                setUndArtefaktPunkte(s.name(), s.clan()) != null);
    }

    static boolean wissenLos(boolean botClan, boolean aufAutorsListe, boolean kraftBekannt, boolean teamBekannt) {
        return !botClan && !aufAutorsListe && !kraftBekannt && !teamBekannt;
    }

    private double siegchanceRoh(Schild s) {
        double chance;
        String woher;
        if (de.hustlebot.schicht.ArenaStand.an()) schichtUngerechnet.remove(schichtSchluessel(s));

        var notiz = buch.nachschlagen(s.name(), s.clan());
        // Autor 19.10.: JkNXED bekam "87 %", obwohl der Bot 0 von 6 Mal gewonnen hatte -- die Kaempfe lagen auf
        // mehreren Eintraegen (Clan anders gelesen), keiner hatte fuer sich drei. Die Bilanz ueber alle Schreibweisen
        // zaehlt, wenn sie mehr Kaempfe kennt als der eine Eintrag.
        int[] bilanzAlle = gesamtBilanz(s);
        int einzeln = notiz.map(Gegnerbuch.Notiz::kaempfe).orElse(0);
        if (bilanzAlle[1] >= Gegnerbuch.GENUG_KAEMPFE && bilanzAlle[1] > einzeln) {
            double quote = (double) bilanzAlle[0] / bilanzAlle[1];
            double gewicht = Math.min(1.0, bilanzAlle[1] / (double) Gegnerbuch.MERKT_SICH);
            chance = 0.5 + (quote - 0.5) * (0.55 + 0.45 * gewicht);
            woher = Sprache.t("Notebook (all spellings) ", "Notizblock (alle Schreibweisen) ") + bilanzAlle[0] + "/" + bilanzAlle[1];
        } else if (notiz.isPresent() && notiz.get().aussagekraeftig()) {
            // Etwas Zurueckhaltung: aus drei Kaempfen wird keine Gewissheit.
            // Die Quote wird zur Mitte hin gedaempft, je duenner die Daten.
            double quote = notiz.get().quote();
            double gewicht = Math.min(1.0, notiz.get().kaempfe() / (double) Gegnerbuch.MERKT_SICH);
            chance = 0.5 + (quote - 0.5) * (0.55 + 0.45 * gewicht);
            woher = Sprache.t("Notebook ", "Notizblock ") + notiz.get().siege() + "/" + notiz.get().kaempfe();
            // Autors Regel vom 08.10. gilt auch hier (Pruefung 08.10.): hat er dich diese Runde
            // angegriffen und verloren und liegt nicht ueber der Messlatte, mindestens 80 % (bis 22.10.: 90 %) --
            // ausser der Notizblock kennt ihn als Angstgegner (Quote unter 50 %).
            if (quote >= 0.5 && gegenMichVerloren.contains(namensSchluessel(s.name()))) {
                Integer p = punkteJetzt.get(namensSchluessel(s.name()));
                int referenz = referenzPunkte();
                if (p != null && referenz != Integer.MIN_VALUE
                        && p - protokollPunkte(namensSchluessel(s.name())) - referenz <= 10) {
                    chance = Math.max(chance, VERLOREN_GEGEN_MICH_BODEN);
                    woher += Sprache.t(", lost to you this round (at least 80 %)", ", hat diese Runde gegen dich verloren (mindestens 80 %)");
                }
            }

        } else if (punkteChance(s) >= 0) {
            // Autors Punkte-System (24.09.): Sets, Artefakte, Kampfkraft.
            Integer p = punkteJetzt.get(namensSchluessel(s.name()));
            // Autor, 08.10. (14:29, Niederlage gegen Доктор mit 134 Punkten bei "88 %"): die Chance
            // haengt nicht an der Rangfolge dieser Runde, sondern daran, wie viele Punkte Autor je
            // geschlagen hat. Wer weit darueber liegt, bekommt ein paar Prozent -- egal, was die
            // Gesamtbilanz sagt. Ohne Notizblock bleibt die alte Rangfolge.
            int meinTeam = MeinTeam.punkte();   // arena-ich.txt, hoechstens 3 Tage alt, sonst -1
            int referenz = meinTeam >= 0 ? meinTeam : hoechsteGeschlagenePunkte();
            int abstand = p == null || referenz == Integer.MIN_VALUE ? Integer.MIN_VALUE : p - referenz;
            if (abstand != Integer.MIN_VALUE) {
                // Am eigenen Team gemessen liegt "gleich viele Punkte" bei 55 %, am besten je
                // Geschlagenen bei 65 % -- den hat Autor ja schon einmal besiegt.
                chance = chanceNachAbstand(meinTeam >= 0 ? abstand + 6 : abstand);
                woher = punkteText(s) + ", " + (abstand > 0 ? abstand + Sprache.t(" above", " ueber") : (-abstand) + Sprache.t(" below", " unter"))
                        + (meinTeam >= 0 ? Sprache.t(" your team (", " deinem Team (") + meinTeam + ")"
                                         : Sprache.t(" the best you ever beat (", " dem Besten, den du je geschlagen hast (") + referenz + ")");
            } else {
                chance = punkteChance(s);
                woher = punkteText(s);
            }
            // Dazu die Erfahrung aus dem Notizblock (Autor, 01.10.: "das war eigentlich mein
            // Plan"): wie oft wurde gegen Gegner mit aehnlich vielen Punkten gewonnen?
            double[] erf = p == null ? null : erfahrung(p);
            if (erf != null && erf[1] >= 3) {
                double g = Math.min(1.0, erf[1] / 12.0);
                chance = (1 - g) * chance + g * erf[0];
                woher += String.format(Sprache.t(", experience %.0f %% from %.0f fights", ", Erfahrung %.0f %% aus %.0f Kaempfen"), erf[0] * 100, erf[1]);
            }
            // Die Gesamtbilanz aller Kaempfe im Notizblock (Autor, 02.10.: "ich gewinn meistens"; in
            // der Nacht 19 von 20 Kaempfen gewonnen). Die Rangfolge allein gab dem staerksten Gegner
            // der Runde 25-50 % -- ein 225er verlor so gegen jeden 150er-Bot (Lauf 2 und 4: Platz 4/5).
            // Seit 08.10. nur noch fuer Gegner im bekannten Bereich: die 93 % stammen aus Kaempfen
            // gegen Schwaechere und sagen ueber einen 134-Punkte-Gegner nichts.
            double[] gesamt = abstand > 0 ? null : gesamtBilanz();
            if (gesamt != null && gesamt[1] >= 10) {
                double w = Math.min(0.5, gesamt[1] / 40.0);
                // Ueber den Gegner selbst nichts bekannt (weder Kraft noch Sets): die Gesamtbilanz nur
                // schwach (02.10., Lauf 11, Runde 5: "Xenos", alles unbekannt, 78 % -- verloren).
                if (bekannteStaerke(s) <= 0 && setUndArtefaktPunkte(s.name(), s.clan()) == null) w *= 0.4;
                chance = (1 - w) * chance + w * gesamt[0];
                woher += String.format(Sprache.t(", overall record %.0f %% from %.0f fights", ", Gesamtbilanz %.0f %% aus %.0f Kaempfen"), gesamt[0] * 100, gesamt[1]);
            }
            // Autor 01.11.: 屈機黑雞 (74 Mio Kraft, Top 1) bekam "94 % (50 points (team unknown, power unknown) ... experience 95 %)": wenig Wissen ergibt
            // wenig Punkte und sieht wie "schwach" aus. Sind Team UND Kraft unbekannt, zaehlt er hoechstens wie unbekannt (50 %): nicht sicher, in der Wahl
            // hinter jedem bekannten guten Gegner. Ausgenommen: "Not in clan" (eigene Regel), Autors Listen (sicher / fast sicher / machbar) und alle mit
            // Kaempfen im Notizblock (die Zweige davor).
            if (wissenLos(s) && chance > UNBEKANNT_HOECHSTENS) {
                chance = UNBEKANNT_HOECHSTENS;
                woher += Sprache.t(", team and power unknown -- counts at most as unknown", ", Team und Kraft unbekannt -- zaehlt hoechstens wie unbekannt");
            }
            // 22.10. nachts (Turnier 6, Runde 5): IDKFA bekam "83 %" -- in Turnier 3 hatte der Bot gegen ihn verloren (Clan
            // anders gelesen, je Eintrag 0 von 1, "zu wenig fuer ein Urteil"). Auch 1-2 eigene Kaempfe zaehlen jetzt: wie 3
            // gedachte Kaempfe mit der bisherigen Chance plus die echten (0 von 2 bei 83 % -> 50 %).
            if (bilanzAlle[1] > 0 && bilanzAlle[1] < Gegnerbuch.GENUG_KAEMPFE) {
                chance = (chance * 3 + bilanzAlle[0]) / (3.0 + bilanzAlle[1]);
                woher += Sprache.t(", own fights ", ", eigene Kaempfe ") + bilanzAlle[0] + "/" + bilanzAlle[1];
            }
            if (gegenMichVerloren.contains(namensSchluessel(s.name()))) {
                // -20 Punkte stecken schon in p. Autor, 08.10.: wer ihn angegriffen hat, verloren
                // hat UND ein schwaches Team hat, ist das sicherste Ziel -- mindestens 80 % (bis 22.10.: 90 %).
                // Ein starkes Team, das an seiner Verteidigung scheiterte, bekommt den Boden nicht.
                // Ohne die -20, die fuer diesen Gegner schon in p stecken (Pruefung 08.10.):
                // sonst hiesse "hoechstens 10 ueber der Messlatte" in Wahrheit 30.
                int reinerAbstand = abstand == Integer.MIN_VALUE ? abstand
                        : abstand - protokollPunkte(namensSchluessel(s.name()));
                if (reinerAbstand != Integer.MIN_VALUE && reinerAbstand <= 10) {
                    chance = Math.max(chance, VERLOREN_GEGEN_MICH_BODEN);
                    woher += Sprache.t(", lost to you this round (-20, at least 80 %)", ", hat diese Runde gegen dich verloren (-20, mindestens 80 %)");
                } else {
                    woher += Sprache.t(", lost to you this round (-20)", ", hat diese Runde gegen dich verloren (-20)");
                }
            }
            if (s.botClan()) {
                chance = Math.max(chance, 0.95);   // Autor, 08.10.: "Not in clan" gewinne ich zu 100 %
                woher += ", Not in clan";
            }
            long st = bekannteStaerke(s);
            if (st > 0 && st < SICHER_UNTER) chance = Math.max(chance, 0.88);
            chance = Math.max(0.05, Math.min(0.95, chance));
            Log.debug("Arena", s.wer() + ": Siegchance " + Math.round(chance * 100)
                    + " % (" + woher + ")");
            if (de.hustlebot.schicht.ArenaStand.an()) schichtWoher.put(schichtSchluessel(s), woher);
            return chance;

        } else if (gegenMichVerloren.contains(namensSchluessel(s.name()))) {
            // Vergleichsform, nicht bloss kleingeschrieben. Der Name wird
            // an zwei Stellen gelesen -- im Schild und im Protokoll -- und
            // die Texterkennung liefert dabei nicht immer dieselben
            // Sonderzeichen. Ohne die Vergleichsform traf der Bonus nie.
            chance = 0.55;   // 30.09.: vorher fest 0,80 -- "heisst nicht, dass er immer verliert"
            woher = Sprache.t("otherwise unknown, lost to you this round", "sonst unbekannt, hat diese Runde gegen dich verloren");

        } else if (s.botClan()) {
            chance = 0.95;   // Autor, 08.10.: "Not in clan" gewinne ich zu 100 %
            woher = "Not in clan";

        } else {
            chance = 0.50;
            woher = Sprache.t("unknown", "unbekannt");
        }

        // DIE KAMPFSTAERKE, aus der Erkundung dieser Runde oder aus dem
        // Notizblock.
        //
        // Das war der eigentliche Zweck der Erkundung, und genau der fehlte:
        // der Bot hat in Runde 1 alle neun Gegner durchgeblaettert, jede
        // Staerke gelesen und aufgeschrieben -- und dann bei der Auswahl
        // nicht eine einzige davon angesehen. Ein Unbekannter mit 60 Mio und
        // einer mit 15 Mio bekamen dieselbe Bewertung.
        //
        // Verlaesslich ist nur die untere Grenze: unter 22 Mio gewinnt Autor
        // nach eigener Aussage so gut wie immer. Darueber entscheidet die
        // Ausruestung, deshalb wird der Wert nur sanft gedrueckt, nie zur
        // Gewissheit erklaert.
        long staerke = bekannteStaerke(s);
        if (staerke > 0) {
            if (staerke < SICHER_UNTER) {
                chance = Math.max(chance, 0.88);
                woher += Sprache.t(", only ", ", nur ") + Sprache.zahl(staerke);
            } else if (meineStaerke > 0 && staerke > meineStaerke * 1.5) {
                chance -= 0.20;
                woher += ", " + Sprache.zahl(staerke) + Sprache.t(" -- much stronger", " -- deutlich staerker");
            } else if (meineStaerke > 0 && staerke > meineStaerke) {
                chance -= 0.10;
                woher += ", " + Sprache.zahl(staerke) + Sprache.t(" -- stronger than you", " -- staerker als du");
            } else {
                woher += ", " + Sprache.zahl(staerke);
            }
        }

        // Artefakte druecken, sperren aber nicht.
        int arte = notiz.map(Gegnerbuch.Notiz::artefakte).orElse(-1);
        if (arte > 0) {
            // Autor 31.10.: "6 % pro Artefakt finde ich insane, lieber 2 % oder 5 %?" Rueckrechnung an 138 Kaempfen (arena-lernen.txt): 5 % am besten, 2-6 % kaum zu unterscheiden.
            chance -= (de.hustlebot.core.ArenaRegelnNeu.an() ? 0.05 : 0.06) * arte;
            woher += ", " + arte + Sprache.t(" strong artifacts", " starke Artefakte");
        }

        // Autor 22.10.: "die vorherigen Kaempfe bei jedem Spieler sollen die Siegchance beeinflussen -- bei einem Sieg
        // erhoehen, bei einer Niederlage verringern". Ab 3 Kaempfen ist die Bilanz oben schon die Grundlage; bei 1-2
        // Kaempfen wie im Punkte-Zweig: 3 gedachte Kaempfe mit der bisherigen Chance plus die echten.
        if (bilanzAlle[1] > 0 && bilanzAlle[1] < Gegnerbuch.GENUG_KAEMPFE && !s.botClan()) {
            chance = (Math.max(0.05, Math.min(0.95, chance)) * 3 + bilanzAlle[0]) / (3.0 + bilanzAlle[1]);
            woher += Sprache.t(", own fights ", ", eigene Kaempfe ") + bilanzAlle[0] + "/" + bilanzAlle[1];
        }

        chance = Math.max(0.05, Math.min(0.95, chance));
        Log.debug("Arena", s.wer() + ": Siegchance " + Math.round(chance * 100)
                + " % (" + woher + ")");
        if (de.hustlebot.schicht.ArenaStand.an()) {
            schichtWoher.put(schichtSchluessel(s), woher);
            // Autor 01.11.: 50 % "unknown" ist nur der Platzhalter -- nichts ueber den Gegner bekannt (keine Kraft, kein Notizblock, keine Punkte,
            // keine Artefakte, kein eigener Kampf). Die Anzeige zeigt dann "?" statt einer Zahl (schichtMelden); die Wahl des Bots rechnet weiter.
            if (woher.equals(Sprache.t("unknown", "unbekannt"))) schichtUngerechnet.add(schichtSchluessel(s));
        }
        return chance;
    }

    /**
     * Welche Runde gerade laeuft.
     *
     * Gezaehlt wird in erster Linie SELBST: jeder Angriff beendet eine
     * Runde, also ist die Runde immer "Angriffe plus eins". Das braucht
     * keine Texterkennung und geht nie kaputt.
     *
     * Frueher haeng die Runde allein an ar_stage_label. War die Zeile
     * unlesbar -- die Vorlage ist freiwillig, und "3/5" wird leicht als
     * "35" gelesen und dann verworfen --, blieb rundeZuletzt auf 0 stehen.
     * In wert() gilt runde<=1 als Runde eins, und dort sind alle Gegner
     * pauschal 150 Punkte wert. Der Bot haette also in allen fuenf Runden
     * nie die Gegner ueber sich bevorzugt, die 225 geben. Der Kern der
     * Taktik waere lautlos ausgefallen.
     *
     * Die Texterkennung korrigiert nur noch, hoechstens alle drei
     * Sekunden.
     */
    private int rundeLesen(Mat screen, BotContext ctx) {
        if (rundeZuletzt < 1) rundeZuletzt = 1;

        long jetzt = System.currentTimeMillis();
        Optional<Matcher.Hit> h = ctx.find(screen, T_STAGE_LABEL);
        if (h.isEmpty()) return rundeZuletzt;
        // Ben 19.-21.10. (Bericht L2BM: Bild "Stage 4/5", der Bot wartete noch auf Runde 3): die Texterkennung lieferte
        // die Zahl oft nicht. Zuerst die eigenen Ziffernmuster -- schnell, ohne PowerShell, darum bei jedem Bild.
        // Gemessen an Autors Bildern vom 15.10.: 14 von 14 richtig (eines davon zeigte Stage 4, der Bot glaubte 3).
        int gelesen = -1;
        BufferedImage f = ctx.frame();
        if (f != null) {
            Matcher.Hit l = h.get();
            gelesen = ArenaTabelle.stageLesen(ArenaTabelle.weissMaske(f, l.x() + l.width(), l.y() - 2,
                    (int) (ctx.width() * 0.07), l.height() + 4));
        }
        if (gelesen < 0 && jetzt - rundeGelesenAt >= LESEPAUSE_MS) {
            rundeGelesenAt = jetzt;
            OptionalLong r = ArenaLeser.zahlNeben(f, h.get(), (int) (ctx.width() * 0.07));
            if (r.isPresent() && r.getAsLong() >= 1 && r.getAsLong() <= 5) gelesen = (int) r.getAsLong();
        }
        if (gelesen < 1 || gelesen > 5 || gelesen == rundeZuletzt) {
            rundeRueckwaerts = 0;
            return rundeZuletzt;
        }
        // Rueckwaerts nur, wenn es zweimal hintereinander so dasteht (die Notbremse kann vorausgezaehlt haben).
        if (gelesen < rundeZuletzt && ++rundeRueckwaerts < 2) return rundeZuletzt;
        rundeRueckwaerts = 0;
        if (gelesen > rundeZuletzt && rundeGespielt) {
            Log.info("Arena", Sprache.t("Stage " + gelesen + " read -- new round.", "Stage " + gelesen + " gelesen -- neue Runde."));
        }
        rundeZuletzt = gelesen;
        neueRunde();
        return rundeZuletzt;
    }

    /** Wie oft hintereinander eine KLEINERE Stage gelesen wurde (erst beim zweiten Mal glauben). */
    private int rundeRueckwaerts;

    /**
     * Vergisst die gemerkte Restzeit.
     *
     * Beim Rundenwechsel springt die Uhr wieder auf zwei Minuten. Der
     * gemerkte Wert von eben waere fast abgelaufen -- der Bot haette
     * geglaubt, es sei sofort Zeit zuzuschlagen, und haette den ganzen
     * Vorteil des spaeten Angriffs weggeworfen.
     */
    private void uhrVergessen() {
        restzeitWert = -1;
        letzterUhrversuch = 0;
    }

    /**
     * Restzeit der Runde in Sekunden, oder -1.
     *
     * Die Uhr wird hoechstens alle drei Sekunden wirklich abgelesen,
     * dazwischen wird heruntergezaehlt. Jedes Ablesen startet einen eigenen
     * PowerShell-Vorgang von rund einer Sekunde; bei jedem Bild waeren das
     * zwei Sekunden Rechenzeit pro halber Sekunde Takt -- der Bot waere
     * langsamer als die Uhr, die er liest, und haette die Runde verpasst.
     */
    private long restzeitLesen(Mat screen, BotContext ctx) {
        long jetzt = System.currentTimeMillis();

        // Neu abgelesen wird hoechstens alle drei Sekunden -- und das gilt
        // auch fuer einen VERSUCH, der nichts ergibt. Sonst haette ein
        // unlesbares Uhrensymbol wieder eine Texterkennung pro Bild
        // ausgeloest, also genau das, was hier abgestellt werden soll.
        if (jetzt - letzterUhrversuch >= LESEPAUSE_MS) {
            letzterUhrversuch = jetzt;
            Optional<Matcher.Hit> h = ctx.find(screen, T_CLOCK);
            if (h.isPresent() && ctx.frame() != null) {
                Matcher.Hit c = h.get();
                int ux = c.x() + c.width(), uy = c.y() - c.height() / 3;
                int uw = (int) (ctx.width() * 0.09), uh = c.height() + 2 * c.height() / 3;
                // Ben 21.10. (Runde endete 12-19 s frueher als gedacht): zuerst die eigenen Ziffernmuster, dann wie
                // bisher die Texterkennung.
                long gelesen = ArenaTabelle.uhrLesen(ArenaTabelle.weissMaske(ctx.frame(), ux, uy, uw, uh));
                if (gelesen < 0) gelesen = ArenaLeser.sekunden(ctx.frame(), ux, uy, uw, uh).orElse(-1);
                if (gelesen >= 0 && gelesen <= 180) {   // mehr waere verlesen
                    restzeitWert = gelesen;
                    // Pruefer 29.10. (3.112, Runde 1 in 4 von 4 Turnieren verpasst): das Bild kann schon 16 s alt sein
                    // (Schilder lesen davor) -- die Uhr gilt fuer den Moment der Aufnahme, nicht fuer jetzt.
                    restzeitGelesenAt = uhrGiltAb(ctx.bildUm(), jetzt);
                }
            }
        }

        // Heruntergezaehlt bis null, aber die Runde laeuft noch? Dann ist
        // der gemerkte Wert veraltet -- lieber gar nichts sagen als etwas
        // Falsches. Sonst haette der Bot bei einer Uhr, die er einmal
        // falsch gelesen hat, dauerhaft "noch 0 Sekunden" geglaubt oder
        // umgekehrt zu viel Zeit angenommen und die Runde verpasst.
        if (restzeitWert < 0) return -1;
        if (System.currentTimeMillis() - restzeitGelesenAt > 3 * 60_000L) {
            restzeitWert = -1;
            return -1;
        }

        // Zwischen zwei Ablesungen wird selbst heruntergezaehlt. Den alten
        // Wert unveraendert zurueckzugeben waere falsch: schlaegt das
        // Ablesen mehrmals fehl, haette der Bot geglaubt, es sei noch
        // genauso viel Zeit wie vor einer Minute.
        long vergangen = (jetzt - restzeitGelesenAt) / 1000;
        return Math.max(0, restzeitWert - vergangen);
    }

    /** Ab wann eine abgelesene Uhr gilt: zur Aufnahmezeit des Bildes; ohne (0) oder bei unsinniger Zeit jetzt. */
    static long uhrGiltAb(long bildUm, long jetzt) {
        return bildUm > 0 && bildUm <= jetzt && jetzt - bildUm < 3 * 60_000L ? bildUm : jetzt;
    }

    /**
     * Pruefer 29.10.: die Gegner fuer die Schicht nicht mehr lesen, wenn die Runde bald zu Ende ist und der eigene Angriff
     * noch fehlt -- das erste Lesen dauerte 16 s, danach kam der Angriff zu spaet ("Round missed" 21:20, 22:38, 22:57, 23:07).
     */
    static boolean schichtLesenErlaubt(boolean rundeGespielt, long restSekunden, long inDerRundeMs, long spaetestensS) {
        if (rundeGespielt) return true;
        long grenze = Math.max(40, spaetestensS + 10);
        if (restSekunden >= 0) return restSekunden >= grenze;
        return inDerRundeMs < RUNDE_SPAETESTENS_MS - grenze * 1000;
    }

    /**
     * Pruefer 29.10.: das Kampfprotokoll vor dem Angriff nur lesen, wenn danach noch genug Zeit bleibt (15 s + wie lange
     * das Lesen zuletzt dauerte; es kostete 4-11 s). In Runde 1 gar nicht: 106 von 106 Lesungen heute ergaben "nobody
     * attacked you and failed".
     */
    static boolean protokollVorAngriff(int runde, long restSekunden, long protokollDauerMs) {
        if (runde == 1) return false;
        return restSekunden < 0 || restSekunden >= 15 + (protokollDauerMs + 999) / 1000;
    }

    // ==================================================================
    //  6. Vergleichsfenster
    // ==================================================================

    /**
     * Vor dem Angriff noch einmal hinsehen.
     *
     * Hier stehen zum ersten Mal beide Kampfstaerken. Ist der Gegner klar
     * schwaecher, wird zugeschlagen. Sonst blaettert der Bot mit dem Pfeil
     * einen weiter -- das ist ein Klick statt drei, weil es den Umweg
     * ueber die Pyramide spart.
     *
     * Nach neun Blicken ist Schluss und es wird genommen, was dasteht.
     * Verlieren kostet nichts, eine verpasste Runde schon.
     */
    private Outcome vergleich(Mat screen, BotContext ctx, RunTracker runs) {
        turnierGesehen = true;   // fuer die Aepfel-Pruefung in anmelden()
        imTurnier = true;        // 11.10.: Vergleichsfenster = Turnier laeuft (auch nach Spiel-Neustart)
        unbekannteBilder = 0;
        BufferedImage bild = ctx.frame();

        // Beide Staerken aus EINEM breiten Streifen lesen.
        //
        // Vorher wurden zwei schmale Ausschnitte links und rechts der
        // Schwerter gelesen. Der rechte war zu knapp: aus "24 956 401"
        // wurde "24 956", und weil das unter 22 Millionen liegt, hielt der
        // Bot jeden Gegner fuer einen sicheren Sieg. Er hat dann Leute
        // angegriffen, die dreimal so stark waren wie Autor.
        //
        // Ein Streifen ueber die ganze Zeile kann nichts abschneiden. Die
        // erste Zahl darin ist Autors Staerke, die zweite die des Gegners
        // -- so stehen sie im Spiel, links und rechts der Schwerter.
        long meine = -1, seine = -1;
        Optional<Matcher.Hit> swords = ctx.find(screen, T_POWER);
        if (swords.isPresent()) {
            Matcher.Hit sw = swords.get();
            // Seit 29.09. zuerst links und rechts der Schwerter je ein Stueck, mit der
            // Vorstufe fuer weisse Zahlen: Der Streifen unten las nur "37472sssD", der
            // Bot hielt jede Kraft fuer "nicht lesbar". Das Stueck links endet vor dem
            // Liga-Abzeichen (sonst wuerde aus "3" und "37 472 535" eine Zahl).
            int stueck = (int) (ctx.width() * 0.20);
            // Autor 01.11. (Pruefung 3.137, Abweichung 10): die eigene Kraft nur aus dem FERTIG geoeffneten Fenster lesen. Um 14:49:35 stand "4.055.264" statt 42.055.264 (Ziffer verloren),
            // wahrscheinlich waehrend das Fenster noch hereinglitt; der Schutz weiter unten fing es ab, aber nur dieses eine Mal und nur, wenn eine bekannte Kraft da ist.
            long jetztFenster = System.currentTimeMillis();
            boolean fensterFertig = fensterStill(sw.x(), sw.y(), fensterSchwerterX, fensterSchwerterY, jetztFenster - fensterSchwerterUm);
            fensterSchwerterX = sw.x();
            fensterSchwerterY = sw.y();
            fensterSchwerterUm = jetztFenster;
            OptionalLong links = fensterFertig ? ArenaLeser.weisseZahl(bild, sw.x() - stueck, sw.y(), stueck, sw.height()) : OptionalLong.empty();
            OptionalLong rechts = ArenaLeser.weisseZahl(bild, sw.x() + sw.width(), sw.y(), stueck, sw.height());
            if (links.isPresent() && links.getAsLong() > 100_000) meine = links.getAsLong();
            if (rechts.isPresent() && rechts.getAsLong() > 100_000) seine = rechts.getAsLong();
            // Autor 01.11.: die eigene Kraft nur uebernehmen, wenn sie zur bekannten passt (siehe eigeneKraftPruefen).
            long bekannteEigene = meineStaerke > 0 ? meineStaerke : (meine > 0 || !fensterFertig ? eigeneKraftGemerkt() : -1);
            if (!fensterFertig) meine = bekannteEigene;   // das Fenster bewegt sich noch: die bekannte eigene Kraft gilt, gelesen wird beim naechsten Bild
            if (meine > 0 && eigeneKraftPruefen(meine, bekannteEigene) < 0) {
                if (eigeneKraftVerlesen.add(meine)) {
                    Log.warn("Arena", Sprache.t("Own power read as " + String.format("%,d", meine) + " in the comparison window -- does not fit "
                                    + String.format("%,d", bekannteEigene) + ", reading ignored (digit lost).",
                            "Eigene Kraft im Vergleichsfenster als " + String.format("%,d", meine) + " gelesen -- passt nicht zu "
                                    + String.format("%,d", bekannteEigene) + ", Lesung ignoriert (Ziffer verloren)."));
                }
                meine = bekannteEigene > 0 ? bekannteEigene : -1;
            }
            // Pruefung 29.10.: keine Obergrenze -- "284.380.601" statt 28.438.060 (orange "i" als 1), "784.346" statt
            // 40.788.154 (Fenster glitt noch herein). Ausserhalb von 0,2- bis 3-mal der eigenen Kraft = nicht gelesen.
            if (seine > 0 && !kraftPlausibel(seine, meine)) {
                Log.debug("Arena", "Kraft " + String.format("%,d", seine) + " passt nicht zur eigenen ("
                        + String.format("%,d", meine) + ") -- verworfen.");
                seine = -1;
            }

            if (meine < 0 || seine < 0) {
                List<Long> zahlen = ArenaLeser.zahlenIn(bild,
                        (int) (ctx.width() * 0.10), sw.y(),
                        (int) (ctx.width() * 0.80), sw.height());

                // Nur richtig grosse Zahlen sind Kampfstaerken. Die kleinen
                // Ziffern der Ausruestung (I, II, 100) stehen in derselben
                // Zeile und wuerden sonst mitgezaehlt.
                List<Long> werte = zahlen.stream().filter(z -> z > 100_000).toList();
                if (werte.size() >= 2) {
                    if (meine < 0 && fensterFertig) meine = werte.get(0);
                    if (seine < 0) seine = werte.get(1);
                } else if (werte.size() == 1 && meine < 0 && fensterFertig) {
                    meine = werte.get(0);
                }
                if (meine > 0 && eigeneKraftPruefen(meine, bekannteEigene) < 0) meine = bekannteEigene > 0 ? bekannteEigene : -1;
            }
            if (seine > 0) letzteGegnerstaerke = seine;
            if (meine > 0) { meineStaerke = meine; Gegnerbuch.setMeineKraft(meine); }
        }

        // ---- Erkundung: alle Gegner einmal ansehen ----------------------
        //
        // Autors Idee, und eine gute: "er koennte sie ja alle in der ersten
        // runde anschauen, sich im log die power von jedem spieler merken
        // das er das spaeter nichtmehr machen muss."
        //
        // Genau dafuer ist die Wartezeit da. Der Bot blaettert einmal im
        // Kreis durch alle Gegner, liest bei jedem Namen und Kampfstaerke,
        // schreibt beides in den Notizblock -- und weiss danach fuer das
        // ganze Turnier Bescheid, ohne noch einmal nachsehen zu muessen.
        //
        // Wann Schluss ist, sagt der Rundlauf selbst: taucht ein Name zum
        // zweiten Mal auf, war man einmal herum. Das ist verlaesslicher,
        // als die Zahl der Gegner zu raten -- unten stehen fuenf Punkte,
        // angreifen darf man aber jeden.
        String[] wer = gegnerNameLesen(ctx, bild, swords);
        String werDaSteht = wer[0];
        String seinClan = wer[1];
        String schluessel = namensSchluessel(werDaSteht);
        if (!werDaSteht.isBlank()) anzeigeMerken(werDaSteht, seinClan);

        // Wer hier steht, an der Kampfstaerke erkannt (die liest sich verlaesslicher als der
        // Name). Nach einem Klick auf den Pfeil zaehlt erst wieder ein Bild, auf dem ein
        // ANDERER steht -- sonst wird zweimal geblaettert oder die Pruefung des falschen
        // Gegners begonnen.
        String ident = seine > 0 ? "#" + seine : schluessel;
        if (nurErkunden && blaetterKlickUm > 0) {
            boolean gewechselt = !ident.isEmpty() && !ident.equals(letzterGesehen);
            if (gewechselt) {
                if (blaetterPlatz > 0) blaetterPlatz = naechsterPlatz(blaetterPlatz);
                blaetterKlickUm = 0;
            } else if (System.currentTimeMillis() - blaetterKlickUm < 2500) {
                return Outcome.WAITING;   // noch das alte Bild
            } else {
                // Der Klick kam nicht an: noch einmal, ohne den Platz weiterzuzaehlen.
                blaetterKlickUm = 0;
            }
        }

        // Der Platz steht im Fenster ("Place: 7") -- verlaesslicher als die Blaetterfolge.
        int fensterPlatz = platzImFenster(bild, ctx, swords, ident);
        int platzHier = fensterPlatz > 0 ? fensterPlatz : blaetterPlatz;

        // Fingerabdruck-Spieler: Im Vergleichsfenster liest sich der Name genauso schlecht wie
        // in der Pyramide. Steht an diesem Platz ein "Bild-..."-Spieler, ist das hier
        // derselbe -- Lesart dem Fingerabdruck zuordnen.
        if (rundeZuletzt <= 1 && nurErkunden && platzHier > 0) {
            String amPlatz = fingerabdruckAmPlatz(platzHier);
            if (amPlatz != null && !amPlatz.equals(schluessel)) {
                String[] nc = anzeigeNamen.get(amPlatz);
                if (nc != null) {
                    if (!schluessel.isEmpty()) namensKarte.put(vergleichsform(werDaSteht), amPlatz);
                    Log.debug("Arena", "Platz " + platzHier + ": \"" + werDaSteht + "\" ist " + nc[0] + " (Fingerabdruck).");
                    werDaSteht = nc[0];
                    schluessel = amPlatz;
                }
            }
        }

        // Autor 01.11.: "Wenn der Bot ein Schild oeffnet, kennt er dessen Namen." Direkt nach dem Klick steht der Gewaehlte da (durchgeblaettert == 0). Liest
        // das Fenster dann einen "Namen", der nur ein Stueck des Clans ist ("*Cthulh" / Clan "Realm" bei 屈機黑雞 / Cthulhu's Realm) oder in einer ganz
        // anderen Schrift steht als der Name auf dem Schild, gilt der Name vom Schild -- Kraft und Team landen unter ihm.
        if (!nurErkunden && durchgeblaettert == 0 && imKampfGegen != null && imKampfGegen.x() > 0 && !imKampfGegen.name().isBlank()
                && fensterNameGehoertNichtDazu(werDaSteht, seinClan, imKampfGegen.name(), imKampfGegen.clan())) {
            Log.info("Arena", Sprache.t("Window reads \"" + werDaSteht + "\" -- only part of the clan line, not a name. It is the shield I clicked: "
                            + imKampfGegen.name() + ".",
                    "Fenster liest \"" + werDaSteht + "\" -- nur ein Stueck der Clan-Zeile, kein Name. Es ist das angeklickte Schild: "
                            + imKampfGegen.name() + "."));
            String alt = schluessel;
            werDaSteht = imKampfGegen.name();
            if (!imKampfGegen.clan().isBlank()) seinClan = imKampfGegen.clan();
            schluessel = namensSchluessel(werDaSteht);
            ident = seine > 0 ? "#" + seine : schluessel;
            if (!alt.isEmpty() && !alt.equals(schluessel)) schildAlias.put(schluessel, alt);
            anzeigeMerken(werDaSteht, seinClan);
        }
        // Idee B30 (22.10., Arena 1 um 14:10): im Fenster unleserlich gelesene Namen ("i", "2811", '"Benbli n TO') bekamen
        // eigene Notizblock-Eintraege -- und in Runde 4 standen RedWineClub und юра als "Team unbekannt" da, obwohl beide
        // gerade angesehen waren. Jetzt gilt dann der Name vom Schild an diesem Platz (gleiche Runde, Kraft passt).
        if (schlechterFensterName(werDaSteht) && platzHier > 0 && !schluessel.startsWith("bild")) {
            Schild amPlatz = schildAmPlatz(platzHier, seine);
            if (amPlatz != null) {
                Log.info("Arena", Sprache.t("Place " + platzHier + ": \"" + werDaSteht + "\" is unreadable in the window -- it is "
                                + amPlatz.name() + " (shield at this place).",
                        "Platz " + platzHier + ": \"" + werDaSteht + "\" ist im Fenster unleserlich -- es ist "
                                + amPlatz.name() + " (Schild an diesem Platz)."));
                werDaSteht = amPlatz.name();
                if (seinClan.isBlank() || unleserlich(seinClan)) seinClan = amPlatz.clan();
                schluessel = namensSchluessel(werDaSteht);
                ident = seine > 0 ? "#" + seine : schluessel;
                anzeigeMerken(werDaSteht, seinClan);
            }
        }

        if (!schluessel.isEmpty() && (fensterPlatz > 0 || (rundeZuletzt <= 1 && nurErkunden && platzHier > 0))) {
            Schild amPlatz = schildAmPlatz(platzHier, seine);
            String sk = amPlatz == null ? "" : namensSchluessel(amPlatz.name());
            if (!sk.isEmpty() && !sk.equals(schluessel) && !schluessel.equals(schildAlias.get(sk))) {
                schildAlias.put(sk, schluessel);
                // Autor 24.10.: das Namensbild des Schilds gilt auch fuer die Lesart im Fenster
                Glyphenschrift.Ergebnis nb = namensbildVon.get(sk);
                if (nb != null) namensbildVon.putIfAbsent(schluessel, nb);
                Log.debug("Arena", "Schild \"" + amPlatz.name() + "\" = im Fenster \"" + werDaSteht + "\" (Platz " + platzHier + ").");
            }
        }

        boolean ohneClanHier = false;
        if (!schluessel.isEmpty()) {
            // Stage 1: Platz aus der Blaetterfolge merken (Autor, 01.10.) -- verlaesslicher als der
            // Name auf dem kleinen Schild der Pyramide.
            if (rundeZuletzt <= 1 && nurErkunden && platzHier > 0 && !platzInStage1.containsKey(schluessel)) {
                platzInStage1.put(schluessel, platzHier);
                Log.info("Arena", Sprache.t("Place " + platzHier + (fensterPlatz > 0 ? " (per window)" : " (per paging order)") + ": " + werDaSteht
                        + (gruenInStage1(schluessel) ? " -- looked at in Stage 1" : " -- not before Stage 2"),
                        "Platz " + platzHier + (fensterPlatz > 0 ? " (laut Fenster)" : " (laut Blaetterfolge)") + ": " + werDaSteht
                        + (gruenInStage1(schluessel) ? " -- wird in Stage 1 angesehen" : " -- erst in Stage 2")));
            }
            boolean ohneClan = istBot(seinClan) || de.hustlebot.core.Gegnerbuch.keinClan(werDaSteht)
                    || nurImVergleich(screen, ctx, swords, T_NOT_IN_CLAN)
                    || (seinClan.isBlank() && botLautPyramide(werDaSteht));
            // Autor, 08.10.: ohne Clan, aber Zahlen oder Zeichen im Namen = echter Spieler, kein Bot.
            if (ohneClan && de.hustlebot.core.NamensVergleich.siehtMenschlichAus(werDaSteht)) {
                ohneClan = false;
                seinClan = de.hustlebot.core.Gegnerbuch.OHNE_CLAN;
            }
            // Autor, 13.10. (Punkt 4): "Not in a clan" auch ansehen -- mindestens die Kraft (die steht hier schon,
            // seine), ab 50 Mio das ganze Team: dann gilt er wie ein echter Spieler (Pruefung in jeder Stage,
            // Notizblock, kein "freier Sieg").
            if (ohneClan && seine >= OHNE_CLAN_TEAM_AB) {
                ohneClan = false;
                seinClan = de.hustlebot.core.Gegnerbuch.OHNE_CLAN;
                if (!erkundet.contains(schluessel)) {
                    Log.info("Arena", Sprache.t("\"" + werDaSteht + "\" has no clan but " + String.format("%,d", seine) + " power -- gets a full look like a real player.",
                            "\"" + werDaSteht + "\" hat keinen Clan, aber " + String.format("%,d", seine)
                            + " Kraft -- wird wie ein echter Spieler komplett angesehen."));
                }
            }
            ohneClanHier = ohneClan;
            if (ohneClan) ohneClanGesehen.add(schluessel);
            // Pruefung 29.10.: weicht die Kraft um mehr als 5 % vom bekannten Wert ab, erst eine zweite gleiche Lesung
            // glauben (Lesefehler beim Hereingleiten des Fensters gingen sonst in den Notizblock).
            Long kraftVorher = schluessel.isBlank() ? null : staerken.get(schluessel);
            if (seine > 0 && kraftVorher != null && !kraftBestaetigt(schluessel, seine, kraftVorher)) {
                Log.debug("Arena", werDaSteht + ": Kraft " + String.format("%,d", seine) + " statt bisher "
                        + String.format("%,d", kraftVorher) + " -- erst nach einer zweiten gleichen Lesung.");
                seine = kraftVorher;
            }
            if (seine > 0) {
                String bekannt = schluesselNachStaerke.putIfAbsent(seine, schluessel);
                if (bekannt != null && !bekannt.equals(schluessel) && !de.hustlebot.core.Gegnerbuch.namenVertraeglich(schluessel, bekannt)) {
                    // Autor 01.11.: gleiche Kraft allein macht keinen "gleichen Gegner" -- der Name muss passen (oder unlesbar sein).
                    Log.debug("Arena", "\"" + werDaSteht + "\" hat dieselbe Kraft wie \"" + bekannt + "\" (" + String.format("%,d", seine)
                            + "), aber der Name passt nicht -- nicht derselbe Gegner.");
                } else if (bekannt != null && !bekannt.equals(schluessel)) {
                    // Zweite Lesart desselben Gegners: alles vom ersten Schluessel uebernehmen.
                    Log.info("Arena", Sprache.t("\"" + werDaSteht + "\" has the same power as \"" + bekannt
                            + "\" (" + String.format("%,d", seine) + ") -- same opponent, not looked at again.",
                            "\"" + werDaSteht + "\" hat dieselbe Kampfstaerke wie \"" + bekannt
                            + "\" (" + String.format("%,d", seine) + ") -- derselbe Gegner, wird nicht noch einmal angesehen."));
                    namensKarte.put(vergleichsform(werDaSteht), bekannt);
                    schluessel = bekannt;
                    if (pruefungen.containsKey(bekannt)) pruefVersucht.add(bekannt);
                    // Mit dem bekannten Namen weiter (01.10.: Sieg gegen "TRTa" landete als eigener
                    // Eintrag im Notizblock, war aber "tig").
                    String[] nc = anzeigeNamen.get(bekannt);
                    if (nc != null) {
                        werDaSteht = nc[0];
                        if (seinClan.isBlank() && nc.length > 1) seinClan = nc[1];
                    }
                }
            }
            if (seine > 0 && !schluessel.isBlank()) {
                staerken.put(schluessel, seine);   // Code-Pruefung 22.10.: nie unter ""
                schichtStandVeraltet = true;   // Autor 01.11.: Kraft gelesen -- Seitenfenster rechnet sofort neu
            }
            // Kraft auch nachtragen, wenn sie beim ersten Mal nicht lesbar war (30.09.).
            // Autor 24.10.: mit dem Namensbild aus der Pyramide -- landet so bei derselben Notiz, egal wie hier gelesen.
            if (seine > 0 && !ohneClan && !werDaSteht.isBlank()) buch.merkeStaerke(werDaSteht, seinClan, seine, namensbildZu(werDaSteht));

            if (erkundet.add(schluessel)) {
                // AUCH OHNE STAERKE vermerken. Vorher fiel ein Gegner, bei
                // dem die Texterkennung die Zahl nicht las, still aus der
                // Erkundung heraus -- kein Eintrag, keine Zeile im
                // Protokoll, beim naechsten Turnier wieder voellig
                // unbekannt.
                Log.info("Arena", Sprache.t("Scouted: " + werDaSteht
                        + (seinClan.isBlank() ? "" : " / " + seinClan)
                        + (seine > 0 ? " has " + String.format("%,d", seine) + " power"
                                     : " -- power not readable")
                        + (ohneClan ? " (not in a clan, does not go into the notebook)" : "."),
                        "Erkundet: " + werDaSteht
                        + (seinClan.isBlank() ? "" : " / " + seinClan)
                        + (seine > 0 ? " hat " + String.format("%,d", seine) + " Kampfstaerke"
                                     : " -- Staerke nicht lesbar")
                        + (ohneClan ? " (in keinem Clan, kommt nicht in den Notizblock)" : ".")));
                // Spieler ohne Clan werden nicht gemerkt -- sie kommen nie
                // wieder, und die Notiz waere fuer immer totes Gewicht.
                //
                // Eingetragen wird mit dem ECHTEN Clan. Frueher stand hier
                // ein leerer, und weil der Notizblock unter Name UND Clan
                // gefuehrt wird, fand das Nachschlagen den Eintrag nie --
                // derselbe Gegner stand am Ende zweimal darin.
                listeSchreiben();
                if (!ohneClan && seine > 0) {
                    buch.merkeStaerke(werDaSteht, seinClan, seine, namensbildZu(werDaSteht));
                }
            }
        }

        // ---- Pal-Besuch (Autor, 02.10.) -----------------------------------------------
        // Einzeln aus der Pyramide angeklickt, damit die Pfote da ist: nur die Pals ansehen. Danach
        // schliesst der Zweig "ohne gewaehltes Ziel" unten das Fenster wieder.
        if (palBesuchFuer != null && !pruefer.laeuft() && imKampfGegen == null && !nurErkunden) {
            long restPal = restSekunden();
            if ((restPal >= 0 && restPal < reserveS() + EXTRAS_BRAUCHT_S)
                    || (restPal < 0 && System.currentTimeMillis() - rundeSeit > RUNDE_SPAETESTENS_MS - EXTRAS_BRAUCHT_S * 1000)) {
                palBesuchFuer = null;   // keine Zeit mehr -- das Fenster geht unten wieder zu
            }
        }
        if (palBesuchFuer != null && !pruefer.laeuft() && imKampfGegen == null && !nurErkunden) {
            boolean pfote = ArenaPruefer.pfoteDa(screen, ctx, swords.orElse(null));
            if (!pfote && System.currentTimeMillis() - palBesuchSeit < 1500) return Outcome.WAITING;
            palBesuchFuer = null;
            var n = schluessel.isEmpty() || ohneClanHier ? Optional.<Gegnerbuch.Notiz>empty()
                    : buch.nachschlagen(werDaSteht, seinClan);
            if (pfote && n.isPresent() && !n.get().palsBekannt()) {
                palVersucht.add(schluessel);
                pruefer.startenNurExtras(werDaSteht, seinClan, false, true);
                Outcome o = pruefer.schritt(screen, ctx, swords.orElse(null));
                if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
                return o != null ? o : Outcome.ACTED;
            }
            if (!pfote) Log.info("Arena", Sprache.t("Pal visit at " + werDaSteht + ": no paw visible -- moving on.",
                    "Pal-Besuch bei " + werDaSteht + ": keine Pfote zu sehen -- weiter."));
        }

        // ---- Zuerst das eigene Team (Autor, 08.10.) -------------------------
        //
        // "Der Bot soll in jeder Arena, bevor er einen Gegner anschaut, erst mein Team anschauen."
        // Im Vergleichsfenster steht oben Autors Team, unten der Gegner -- je mit Kraftbalken
        // (Schwerter). Dieselbe Pruefung wie beim Gegner, nur am oberen Balken.
        if (!eigenesTeamGeprueft && rundeZuletzt <= 1 && nurErkunden && !pruefer.laeuft()) {
            eigenesTeamGeprueft = true;
            List<Matcher.Hit> balken = new ArrayList<>(ctx.matcher().findAll(screen, T_POWER, 0.84, 3));
            balken.sort(java.util.Comparator.comparingInt(Matcher.Hit::centerY));
            // Idee 7 (B24, 22.10.): im heutigen Vergleichsfenster gibt es nur EIN Schwerter-Symbol, zwischen den Reihen (links
            // deine Kraft, rechts seine) -- "nur 1 Kraftbalken" stand 44-mal im Protokoll, dein Team wurde nie angesehen.
            // Deine Reihe steht 0,144 x Hoehe ueber den Schwertern (Foto Arena 4); der Pruefer sucht die Reihe 0,218 x Hoehe
            // unter seinem Anker -- also ein gedachter Anker so weit darueber.
            if (balken.size() == 1) {
                Matcher.Hit m = balken.get(0);
                int hoch = (int) Math.round(ctx.height() * (0.218 + 0.144));
                balken.add(0, new Matcher.Hit(m.name(), m.x(), m.y() - hoch, m.width(), m.height(), m.score()));
            }
            // Autor 22.10.: "in Zukunft nur auf die Anordnung der Einheiten schauen" -- sieht deine Reihe (Held + Einheiten)
            // aus wie beim letzten vollen Scan und ist der hoechstens 3 Tage alt, wird nicht neu gescannt.
            // Autor 24.10.: "Bei mir fast genau das gleiche wie bei der Sperrliste vom Gegner -- auch die Namen und die Einheiten
            // mit Namen und Skin merken, und die Kraft jeder einzelnen Einheit. Von mir aus einmal kurz durchswipen, aber nicht
            // jedes Mal das ganze Team neu einscannen." Der volle Scan lief in etwa jedem dritten Turnier (12-19 s), die Reihe
            // war dabei nur zu 75-84 % aehnlich -- fast immer stand dasselbe Team da. Jetzt in drei Stufen:
            //  1. nur im Vergleichsfenster: Portraets deiner Einheiten (wie bei der Sperrliste), deine Kraft und die Reihe;
            //  2. sonst kurz durchswipen (Schnell-Blick: Ausruestung, Name, Talent, Guardian, Skin, Kraft, Leben je Einheit);
            //  3. nur wenn sich dabei wirklich etwas geaendert hat: der volle Scan.
            // Autor 03.11.: "Wenn ich mehr als 25 Arenen spielen will oder laenger als 3 Stunden (Nachtlauf 4-10 h), in der ersten Arena
            // Stage 1 zuerst mein Team anschauen -- Pals, Schirmherren, Stigma-Sets, Einheiten -- der Punkte halber." Sonst schaut der
            // Bot nur, ob die Reihe gleich aussieht; hier einmal je Start der volle Scan, damit die Messlatte frisch ist.
            if (!langerLaufGescannt && langerLauf(runs.targetRuns(),
                    de.hustlebot.core.Dauerwerte.zahl(de.hustlebot.core.Dauerwerte.ENDE_UM, 0L), System.currentTimeMillis())) {
                langerLaufGescannt = true;
                eigenesTeamVoll = true;
                Log.info("Arena", Sprache.t("Long run (" + runs.targetRuns() + " arenas planned or 3 h and more) -- first a full scan of your own team.",
                        "Langer Lauf (" + runs.targetRuns() + " Arenen geplant oder 3 h und mehr) -- erst ein voller Scan deines Teams."));
            }
            boolean vollScan = eigenesTeamVoll;
            eigenesTeamVoll = false;
            if (!balken.isEmpty()) {
                Matcher.Hit unten = balken.get(balken.size() - 1);
                if (reiheBeimScan != null) reiheBeimScan.release();
                reiheBeimScan = eigeneReihe(screen, ctx, unten);
                Matcher.Hit anker = swords.orElse(unten);
                reiheBilderBeimScan = ArenaSperrBilder.eigeneReihe(bild, anker.x(), anker.y());
                kraftBeimScan = meine;
            }
            if (!balken.isEmpty() && !vollScan && MeinTeam.punkte() < 0) {
                Log.info("Arena", Sprache.t("Own team: no benchmark yet (or older than 3 days) -- full scan.",
                        "Eigenes Team: noch keine Messlatte (oder aelter als 3 Tage) -- voller Scan."));
            } else if (!balken.isEmpty() && !vollScan) {
                double gleich = reiheVergleichen(reiheBeimScan);
                ArenaSperrBilder.Merkmale alteBilder = ArenaSperrBilder.laden(java.nio.file.Path.of(REIHE_BILDER_DATEI));
                int alleSkins = alteBilder == null ? 0 : alteBilder.anzahl();
                int skins = ArenaSperrBilder.gleicheEinheiten(alteBilder, reiheBilderBeimScan, 0);
                boolean skinsGleich = alleSkins > 0 && skins >= Math.min(5, alleSkins);
                long alteKraft = eigeneKraftGemerkt();
                boolean kraftBekannt = meine > 0 && alteKraft > 0;
                boolean kraftGleich = kraftBekannt && Math.abs(meine - alteKraft) <= Math.max(meine, alteKraft) / 100;
                boolean kraftOk = !kraftBekannt || kraftGleich;
                String reihe = gleich >= 0 ? Math.round(gleich * 100) + " %" : "?";
                String warumEn = (alleSkins > 0 ? skins + " of " + alleSkins + " unit pictures equal" : "no unit pictures saved yet")
                        + ", power " + (meine > 0 ? String.format("%,d", meine) : "not readable")
                        + (alteKraft > 0 ? " (last scan " + String.format("%,d", alteKraft) + ")" : "") + ", line-up " + reihe;
                String warumDe = (alleSkins > 0 ? skins + " von " + alleSkins + " Einheiten-Bildern gleich" : "noch keine Einheiten-Bilder gemerkt")
                        + ", Kraft " + (meine > 0 ? String.format("%,d", meine) : "nicht lesbar")
                        + (alteKraft > 0 ? " (letzter Scan " + String.format("%,d", alteKraft) + ")" : "") + ", Reihe " + reihe;
                if ((skinsGleich && kraftOk) || (kraftGleich && gleich >= REIHE_AEHNLICH_AB) || (gleich >= REIHE_GLEICH_AB && kraftOk)) {
                    Log.info("Arena", Sprache.t("Own team: same as at the last scan (" + warumEn + ") -- no new scan, the benchmark stays ("
                                    + MeinTeam.punkte() + ").",
                            "Eigenes Team: gleich wie beim letzten Scan (" + warumDe + ") -- kein neuer Scan, die Messlatte bleibt ("
                                    + MeinTeam.punkte() + ")."));
                    return Outcome.WAITING;
                }
                boolean kurz = ArenaAussehen.bekannt(EIGEN_SCHLUESSEL) && balken.size() >= 2 && ArenaPruefer.moeglich(ctx)
                        && zeitFuerPruefung();
                Log.info("Arena", Sprache.t("Own team: not sure it is the same (" + warumEn + ") -- "
                                + (kurz ? "quick look through the units." : "full scan."),
                        "Eigenes Team: nicht sicher dasselbe (" + warumDe + ") -- "
                                + (kurz ? "kurz durchsehen." : "voller Scan.")));
                if (kurz) {
                    Matcher.Hit oben = balken.get(0), unten = balken.get(balken.size() - 1);
                    pruefer.startenEigenesAussehen(oben, unten, ArenaAussehen.laden(EIGEN_SCHLUESSEL));
                    Outcome o = pruefer.schritt(screen, ctx, oben);
                    if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
                    return o != null ? o : Outcome.ACTED;
                }
            }
            if (balken.size() >= 2 && ArenaPruefer.moeglich(ctx) && zeitFuerPruefung()) {
                Matcher.Hit oben = balken.get(0), unten = balken.get(balken.size() - 1);
                pruefer.startenEigenesTeam(oben, unten, ArenaPruefer.pfoteDa(screen, ctx, oben));
                Outcome o = pruefer.schritt(screen, ctx, oben);
                if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
                return o != null ? o : Outcome.ACTED;
            }
            Log.info("Arena", Sprache.t("Own team not looked at: " + balken.size() + " power bars found in the comparison window"
                    + (balken.size() >= 2 ? " (not enough time)" : " (one at the top and one at the bottom are needed)") + ".",
                    "Eigenes Team nicht angesehen: " + balken.size() + " Kraftbalken im Vergleichsfenster gefunden"
                    + (balken.size() >= 2 ? " (zu wenig Zeit)" : " (es braucht oben und unten je einen)") + "."));
        }

        // ---- Sets und Artefakte ansehen (Autor, 24.09.) -------------------
        //
        // Einmal je Gegner und Turnier, nur wenn der Notizblock keine
        // Pruefung von den letzten 14 Tagen kennt und die Runde noch lang
        // genug ist (15 s bleiben immer fuer den Angriff).
        // Nicht mehr pruefen, wenn das Ziel schon gewaehlt ist -- dann wird angegriffen (01.10.,
        // Runde 4: nach der Wahl noch 11 s Einheiten angesehen, knapp vor Rundenende).
        // ---- Schnell-Blick (Autor 22.10.): bekannte Gegner nur durchklicken -- sieht das Team noch so aus? ----
        if (!pruefer.laeuft() && (nurErkunden || imKampfGegen == null) && ArenaPruefer.moeglich(ctx)
                && brauchtSchnellblick(schluessel, werDaSteht, seinClan, ohneClanHier)) {
            aussehenInRunde.add(schluessel);
            if (!gegnerIstGesperrt(screen, ctx, swords)) {
                schnellblickFuer = schluessel;
                pruefer.startenAussehen(werDaSteht, seinClan, ArenaAussehen.laden(schluessel));
                Outcome o = pruefer.schritt(screen, ctx, swords.orElse(null));
                if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
                return o != null ? o : Outcome.ACTED;
            }
        }

        if (!pruefer.laeuft() && (nurErkunden || imKampfGegen == null)
                && brauchtPruefung(ctx, werDaSteht, seinClan, schluessel, ohneClanHier)
                && !(nurErkunden && erstSchwaecherer(schluessel))) {
            if (neuAnsehen.remove(schluessel)) {
                Log.info("Arena", Sprache.t("Looked at again in full: " + werDaSteht + " -- the team looks different from last time.",
                        "Noch einmal komplett angesehen: " + werDaSteht + " -- das Team sieht anders aus als beim letzten Mal."));
            } else if (pruefungen.containsKey(schluessel) || pruefVersucht.contains(schluessel)) {
                boolean luecke = lueckenhaft(schluessel);
                if (luecke) nachgeprueft.add(schluessel);   // zweiter Blick ab Stage 3 (08.10.)
                Log.info("Arena", Sprache.t("Looked at again in full (Stage " + rundeZuletzt + ", no. " + (geprueftInRunde.size() + 1)
                        + " in this stage): " + werDaSteht + (luecke ? " -- the first check had gaps." : " -- re-checking the values."),
                        "Noch einmal komplett angesehen (Stage " + rundeZuletzt + ", " + (geprueftInRunde.size() + 1)
                        + ". in dieser Stage): " + werDaSteht + (luecke ? " -- die erste Pruefung hatte Luecken." : " -- Werte nachpruefen.")));
            }
            geprueftInRunde.add(schluessel);
            pruefVersucht.add(schluessel);
            // "Niemals" wird nicht angesehen. Als versucht vermerkt, damit
            // die Sperrliste nicht bei jedem Bild neu durchsucht wird.
            // Messung 30.10.: "Fenster geht nicht zu" traf immer dieselben (QUIJOTE 20 von 21, -UMPM- 10 von 10): 50 von 59 Abbruechen,
            // in jedem Turnier ein verlorener Anlauf. Nach zwei Abbruechen je Spieler nicht mehr ansehen, bis der Bot neu startet.
            boolean pruefenLohnt = pruefAbbrueche.getOrDefault(schluessel, 0) < PRUEF_ABBRUECHE_MAX;
            if (pruefenLohnt && !gegnerIstGesperrt(screen, ctx, swords)) {
                pruefer.starten(werDaSteht, seinClan, ArenaPruefer.pfoteDa(screen, ctx, swords.orElse(null)));
                pruefungBegonnenUm = System.currentTimeMillis();
                Outcome o = pruefer.schritt(screen, ctx, swords.orElse(null));
                if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
                return o != null ? o : Outcome.ACTED;
            }
        } else if (!pruefer.laeuft() && (nurErkunden || imKampfGegen == null)
                && brauchtExtras(werDaSteht, seinClan, schluessel, ohneClanHier,
                        ArenaPruefer.pfoteDa(screen, ctx, swords.orElse(null)))) {
            // Schirmherrschaften/Pals nachholen (01.10.): die Einheiten sind frisch geprueft.
            pruefVersucht.add(schluessel);
            var n = buch.nachschlagen(werDaSteht, seinClan).orElseThrow();
            boolean pfote = ArenaPruefer.pfoteDa(screen, ctx, swords.orElse(null));
            if (pfote) palVersucht.add(schluessel);
            pruefer.startenNurExtras(werDaSteht, seinClan, schirmFehlt(n), palsFehlt(n) && pfote);
            Outcome o = pruefer.schritt(screen, ctx, swords.orElse(null));
            if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
            return o != null ? o : Outcome.ACTED;
        }

        // Solange die Erkundung laeuft, wird nicht angegriffen -- erst
        // ansehen, dann entscheiden.
        //
        // ZWEI GRENZEN, und beide sind noetig. Liest die Texterkennung die
        // Namen nicht (Zierschrift, Kyrillisch), waechst die Liste nie und
        // der Rundlauf schliesst sich nicht -- dagegen die Zahl der
        // Blaetter. Und jeder Durchgang kostet zwei Texterkennungen, also
        // gut zwei Sekunden; bei achtzehn Durchgaengen ist die Runde vorbei,
        // bevor der erste Angriff kommt -- dagegen die Uhr.
        // Nur zum Durchschauen hier (Wartezeit der Stage): nie angreifen, weiterblaettern.
        if (nurErkunden) {
            return erkundungsSchritt(screen, ctx, runs, schluessel, ident);
        }

        // Ohne Ziel aus der Pyramide wird hier nicht angegriffen (Autor, 01.10.: "erst
        // nachdem er die 5 Gegner durchgeschaut hat entscheiden, nicht schon beim zweiten
        // Spieler"). Bis dahin griff der Bot jeden an, der schwaecher war -- auch einen, den
        // er in dieser Arena schon hatte (Stage 3: graue Knoepfe, 90 s gewartet). Zurueck
        // zur Pyramide: dort wird nach Punkten und Notizblock gewaehlt.
        if (imKampfGegen == null) {
            if (System.currentTimeMillis() - letzteAngriffsmeldung > 1000) {
                letzteAngriffsmeldung = System.currentTimeMillis();
                Log.info("Arena", Sprache.t("Comparison window without a chosen target"
                        + (werDaSteht.isBlank() ? "" : " (" + werDaSteht + ")")
                        + " -- back to the pyramid, the choice is made there.",
                        "Vergleichsfenster ohne gewaehltes Ziel"
                        + (werDaSteht.isBlank() ? "" : " (" + werDaSteht + ")")
                        + " -- zurueck zur Pyramide, dort wird gewaehlt."));
            }
            if (vergleichSchliessen(screen, ctx, Sprache.t("Back to the pyramid (no target chosen)", "Zurueck zur Pyramide (kein Ziel gewaehlt)"))) {
                return Outcome.ACTED;
            }
            return warteMit(ctx, screen, Sprache.t("Comparison window (no target)", "Vergleichsfenster (ohne Ziel)"), T_CLOSE_X);
        }

        boolean gesperrt = gegnerIstGesperrt(screen, ctx, swords);
        boolean genugGesehen = durchgeblaettert >= MAX_DURCHBLAETTERN;
        // Der in der Pyramide gewaehlte Gegner wird angegriffen (24.09.) --
        // die Wahl dort kennt Punkte und Notizblock. Frueher blaetterte der
        // Bot hier weiter, sobald der Gegner staerker war, und warf die Wahl
        // damit um.
        // Steht hier ein ANDERER Gegner, den der Bot in diesem Turnier schon kennt, ist es nicht
        // das Ziel -- auch direkt nach dem Klick (01.10., Stage 5: gewaehlt "Утята", das war
        // aber der Clan von "tig"; tig ist staerker als Autor).
        boolean andererBekannter = imKampfGegen != null && !schluessel.isEmpty()
                && erkundet.contains(schluessel)
                && !namensSchluessel(imKampfGegen.name()).equals(schluessel);
        boolean istZiel = imKampfGegen != null
                && ((durchgeblaettert == 0 && !andererBekannter)   // direkt nach dem Klick steht der Gewaehlte da
                    || schluessel.isEmpty()
                    || namensSchluessel(imKampfGegen.name()).equals(schluessel));
        // Am sichersten: der Platz. Das gewaehlte Schild lag an einem Platz der Pyramide, das
        // Fenster nennt den Platz des Gegners -- stimmen sie ueberein, ist er es (auch wenn der
        // Name ganz anders gelesen wurde), sonst nicht.
        int zielPlatz = imKampfGegen != null && imKampfGegen.x() > 0
                ? platzAusLage(imKampfGegen.x(), imKampfGegen.y(), breiteZuletzt, hoeheZuletzt) : -1;
        if (zielPlatz > 0 && fensterPlatz > 0) istZiel = fensterPlatz == zielPlatz;
        // Autor 24.10. (Turnier 2, Runde 3): in der Pyramide "0 AIULDCOO", hier "ALI. DKH" -- nicht als derselbe erkannt,
        // der Bot blaetterte alle 9 durch und die Runde ging verloren. Ist die Kraft des Gewaehlten bekannt und steht hier
        // dieselbe (bis 0,5 % Lesefehler), ist er es -- egal, wie der Name gelesen wurde.
        // Code-Review 26.10.: nur ohne sicheren Platz-Abgleich -- ein klares Platz-Nein (Klick daneben) gilt.
        if (!istZiel && imKampfGegen != null && seine > 0 && (zielPlatz <= 0 || fensterPlatz <= 0)) {
            long gewaehlt = bekannteStaerke(imKampfGegen);
            if (gewaehlt > 0 && Math.abs(gewaehlt - seine) <= Math.max(gewaehlt, seine) / 200) {
                istZiel = true;
                Log.debug("Arena", werDaSteht + " hat dieselbe Kraft wie der gewaehlte " + imKampfGegen.wer() + " (" + seine + ") -- er ist es.");
            }
        }

        // Gesperrt wird nie angegriffen -- so hat Autor es festgelegt, ohne
        // Ausnahme. Aber es muss ein Ende haben.
        //
        // Frueher hatte der Fall "gesperrt UND alle schon gesehen" gar
        // keinen Zweig: die erste Bedingung verlangte !genugGesehen, die
        // zweite !gesperrt. Beide fielen durch, und der Bot blaetterte bis
        // zum Rundenende im Kreis, ohne je anzugreifen.
        if (gesperrt) {
            if (durchgeblaettert < MAX_BLAETTERN_HART) {
                durchgeblaettert++;
                Log.info("Arena", Sprache.t("This opponent is on the hard block list -- on "
                        + "to the next one (" + durchgeblaettert + "/" + MAX_BLAETTERN_HART + ").",
                        "Dieser Gegner steht auf der harten Sperrliste -- weiter "
                        + "zum naechsten (" + durchgeblaettert + "/" + MAX_BLAETTERN_HART + ")."));
                ctx.clickIfVisible(screen, T_NEXT_ARROW, Sprache.t("Next opponent", "Naechster Gegner"));
                return Outcome.ACTED;
            }
            Log.alarm("Arena", Sprache.t("After " + durchgeblaettert + " looks there is still a "
                    + "blocked opponent. This round stays empty. If this happens often, one of the "
                    + "names belongs in ar_ungern_ instead of ar_block_.",
                    "Nach " + durchgeblaettert + " Blicken steht immer noch ein "
                    + "gesperrter Gegner da. Diese Runde bleibt leer. Wenn das oefter "
                    + "vorkommt, gehoert einer der Namen in ar_ungern_ statt ar_block_."));
            return Outcome.WAITING;
        }

        // 22.10. nachts (Turnier 3, Runde 3): gewaehlt mit "Kraft unbekannt" (Name in Pyramide und Fenster verschieden
        // gelesen), im Fenster standen dann 53,7 Mio gegen 34,5 Mio -- angegriffen, verloren, am Ende Platz 8. War die Kraft
        // bei der Wahl unbekannt und ist der Gegner hier ueber 30 % staerker: Kraft merken, EINMAL je Runde und Gegner zurueck
        // zur Pyramide und neu waehlen (mit bekannter Kraft faellt er dort nach hinten).
        if (istZiel && !ArenaEinfach.aktiv() && meine > 0 && seine > meine * 13 / 10 && imKampfGegen != null && bekannteStaerke(imKampfGegen) <= 0
                && zuStarkZurueck.add(rundeZuletzt + ":" + namensSchluessel(imKampfGegen.name()))) {
            if (!namensSchluessel(imKampfGegen.name()).isBlank()) staerken.put(namensSchluessel(imKampfGegen.name()), seine);
            Log.info("Arena", Sprache.t("Chosen with unknown power, but here they have " + seine + " against my " + meine
                            + " -- back to the pyramid, choosing again with the power known.",
                    "Mit unbekannter Kraft gewaehlt, hier aber " + seine + " gegen meine " + meine
                            + " -- zurueck zur Pyramide, neu waehlen mit bekannter Kraft."));
            if (vergleichSchliessen(screen, ctx, Sprache.t("Back to the pyramid (opponent much stronger)", "Zurueck zur Pyramide (Gegner viel staerker)"))) {
                imKampfGegen = null;
                return Outcome.ACTED;
            }
        }

        // Autor 01.11.: "Neues Wissen aus dem Vergleichsfenster unter diesem Eintrag speichern." Das angeklickte Schild kennt der Bot; steht unter seinem
        // Namen noch keine Kraft (das Fenster las ihn anders), gilt die hier gelesene -- auch fuer die naechste Stage (Wahl, Siegchance, Seitenfenster).
        if (istZiel && imKampfGegen != null && imKampfGegen.x() > 0 && seine > 0 && !imKampfGegen.name().isBlank()) {
            String sk = namensSchluessel(imKampfGegen.name());
            if (!sk.isBlank() && !staerken.containsKey(sk)) {
                staerken.put(sk, seine);
                schichtStandVeraltet = true;
                if (!schluessel.isBlank() && !schluessel.equals(sk)) schildAlias.putIfAbsent(sk, schluessel);
                if (!unleserlich(imKampfGegen.name()) && !de.hustlebot.core.NamensVergleich.keinName(imKampfGegen.name())) {
                    buch.merkeStaerke(imKampfGegen.name(), imKampfGegen.clan(), seine, namensbildZu(imKampfGegen.name()));
                }
            }
        }
        // Autor 24.10. (Turnier 5, 04:00): in der Pyramide als "-UMPM-" gelesen -- hier stand die Kraft 36.548.453,
        // dieselbe wie beim gesperrten "OMYth Gods" (0 Siege, 4 Niederlagen). Angegriffen, verloren, Platz 4. Jetzt vor
        // dem Angriff mit der HIER gelesenen Kraft und dem Clan noch einmal gegen die Sperrliste pruefen: gesperrt ->
        // Kraft merken, zurueck zur Pyramide und neu waehlen (dort faellt er mit bekannter Kraft heraus).
        // Autor 24.10. abends: dazu das AUSSEHEN -- die Portraets seiner Einheiten und das Clan-Banner aus diesem Fenster
        // (nur ausgeschnitten, kein Klick, ~1 ms). So greift die Sperre auch, wenn Name und Clan verlesen sind und seine
        // Kraft sich geaendert hat (anderes Ausruestungsstueck). Auch ohne lesbare Kraft wird geprueft.
        ArenaSperrBilder.Merkmale aussehenHier = null;
        String sperrSchluessel = rundeZuletzt + ":" + (seine > 0 ? String.valueOf(seine) : "?" + schluessel);
        // Code-Review 26.10.: schon als gesperrt erkannt, aber das X ging nicht -- nur das Schliessen wiederholen
        // (nicht jedes Bild Sperrgrund und Aussehen neu rechnen), und nie angreifen.
        if (sperrSchluessel.equals(sperreOffen)) {
            if (vergleichSchliessen(screen, ctx, Sprache.t("Back to the pyramid (blocked opponent)", "Zurueck zur Pyramide (gesperrter Gegner)"))) {
                sperreOffen = null;
                imKampfGegen = null;
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }
        // Sperr-Netz (Autor 28.10., nur mit arena-regeln-neu.txt "an"): auch der Zweig "nach N Blicken -- verlieren kostet nichts"
        // greift an, was im Fenster steht -- 29 Mal in den Logs 16.-24.10. Dort stand nicht das gewaehlte Ziel, die Sperrpruefung lief aber
        // nur fuer das Ziel. Jetzt wird auch der Angezeigte geprueft (Sperrdatei, Name eines niemals-Bildes, Notizblock 0 Siege aus 3).
        boolean netzAn = de.hustlebot.core.ArenaRegelnNeu.an();
        boolean fremdPruefen = netzAn && !istZiel && genugGesehen;
        if ((istZiel || fremdPruefen) && imKampfGegen != null && !ArenaEinfach.aktiv()
                && (seine > 0 || swords.isPresent() || (netzAn && !werDaSteht.isBlank()))
                && gesperrtZurueck.add(sperrSchluessel)) {
            aussehenHier = swords.isPresent() ? ArenaSperrBilder.ausVergleich(bild, swords.get().x(), swords.get().y())
                    : ArenaSperrBilder.Merkmale.LEER;
            // Autor 24.10.: dazu der Fingerabdruck seines Namensbildes aus der Pyramide (Glyphenschrift).
            // Beim angezeigten Nicht-Ziel gehoeren Sets, Einheiten und Namensbild des GEWAEHLTEN nicht zu ihm.
            String setsHier = istZiel ? setsVon(imKampfGegen)
                    : buch.nachschlagen(werDaSteht, seinClan).map(Gegnerbuch.Notiz::sets).orElse("");
            String einheitenHier = einheitenVon.getOrDefault(istZiel ? namensSchluessel(imKampfGegen.name()) : schluessel, "");
            String fpHier = istZiel ? fingerabdruckZu(imKampfGegen.name()) : null;
            String sperre = netzAn
                    ? blockGrundNeu(werDaSteht, seinClan, seine, setsHier, einheitenHier, aussehenHier, fpHier, ctx)
                    : de.hustlebot.core.ArenaSperre.sperrgrund(werDaSteht, seinClan, seine, setsHier, einheitenHier,
                            aussehenHier, fpHier);
            if (sperre != null) {
                String sperrName = istZiel ? namensSchluessel(imKampfGegen.name()) : schluessel;
                if (!sperrName.isBlank()) {
                    if (seine > 0) staerken.put(sperrName, seine);
                    imFensterGesperrt.add(sperrName);
                }
                Log.info("Arena", Sprache.t("Not attacking " + werDaSteht + " -- blocked (" + sperre + "). Back to the pyramid, choosing someone else.",
                        werDaSteht + " wird nicht angegriffen -- gesperrt (" + sperre + "). Zurueck zur Pyramide, neu waehlen."));
                if (vergleichSchliessen(screen, ctx, Sprache.t("Back to the pyramid (blocked opponent)", "Zurueck zur Pyramide (gesperrter Gegner)"))) {
                    imKampfGegen = null;
                    return Outcome.ACTED;
                }
                // Code-Review 26.10.: X ging in diesem Bild nicht -- NICHT angreifen, im naechsten Bild nur noch schliessen.
                sperreOffen = sperrSchluessel;
                return Outcome.WAITING;
            }
        }

        // Autor 22.10.: vor jedem Angriff kurz nachsehen, ob es noch dasselbe Team ist (Schnell-Blick, ~5 s).
        if (istZiel && !pruefer.laeuft() && ArenaPruefer.moeglich(ctx) && vorAngriffBlickNoetig(schluessel)
                && System.currentTimeMillis() - angriffGeklicktUm >= 4000) {
            aussehenInRunde.add(schluessel);
            if (!gegnerIstGesperrt(screen, ctx, swords)) {
                Log.info("Arena", Sprache.t("Before the attack: quick look at " + werDaSteht + " -- still the same team?",
                        "Vor dem Angriff: Schnell-Blick auf " + werDaSteht + " -- noch dasselbe Team?"));
                schnellblickFuer = schluessel;
                vorAngriffBlick = true;
                pruefer.startenAussehen(werDaSteht, seinClan, ArenaAussehen.laden(schluessel));
                Outcome o = pruefer.schritt(screen, ctx, swords.orElse(null));
                if (o == null) { pruefungVerbuchen(); pruefungFertigUm = System.currentTimeMillis(); }
                return o != null ? o : Outcome.ACTED;
            }
        }

        if (istZiel || genugGesehen) {
            String grund = istZiel ? Sprache.t("chosen in the pyramid (points + notebook)", "in der Pyramide gewaehlt (Punkte + Notizblock)")
                    : Sprache.t("after " + durchgeblaettert + " looks -- losing costs nothing", "nach " + durchgeblaettert + " Blicken -- verlieren kostet nichts");
            // Nur einmal je Sekunde melden. Kommt der Klick nicht durch,
            // stand dieselbe Zeile sonst hundertfach im Protokoll und hat
            // alles andere zugedeckt.
            if (System.currentTimeMillis() - letzteAngriffsmeldung > 1000) {
                letzteAngriffsmeldung = System.currentTimeMillis();
                Log.info("Arena", Sprache.t("Attack: " + grund
                        + (seine > 0 ? "  [my " + meine + " against their " + seine + "]" : ""),
                        "Angriff: " + grund
                        + (seine > 0 ? "  [meine " + meine + " gegen seine " + seine + "]" : "")));
            }
            // Nicht zweimal: das Fenster blendet nach dem Klick noch aus, und ein zweiter Klick
            // traf auf dem Ergebnis einen Gegenstand -- dessen Info-Fenster verdeckte Dauer und
            // Punkte (01.10., Runde 3: "Spell Caster Dust", zweimal ohne Punkte).
            if (System.currentTimeMillis() - angriffGeklicktUm < 4000) return Outcome.WAITING;
            if (ctx.clickIfVisible(screen, T_QUICK, Sprache.t("Quick attack (Quick Attack)", "Schnellangriff (Quick Attack)"))) {
                fotoSerie("angriff");   // das Bild von VOR dem Klick: Vergleichsfenster mit beiden Teams
                // Autor 24.10. abends: sein Aussehen (Einheiten-Portraets, Clan-Banner) fuer die Sperrliste -- nur wenn hier
                // wirklich der in der Pyramide Gewaehlte steht, sonst landete ein fremdes Team in seinem Profil.
                kampfAussehen = !istZiel ? null : aussehenHier != null ? aussehenHier : swords.isPresent()
                        ? ArenaSperrBilder.ausVergleich(bild, swords.get().x(), swords.get().y()) : null;
                angriffeGemacht++;
                angriffGeklicktUm = System.currentTimeMillis();
                eigenerAngriffUm = angriffGeklicktUm;
                // Ben 21.10.: wann diese Runde laut Uhr endet -- danach muss die naechste kommen (siehe pyramide()).
                rundenendeErwartet = restzeitWert >= 0 ? restzeitGelesenAt + restzeitWert * 1000L : 0;
                if (angriffsphaseSeit > 0) {
                    long vorarbeit = angriffGeklicktUm - angriffsphaseSeit;
                    vorarbeitSchnittMs = (vorarbeitSchnittMs * 3 + vorarbeit) / 4;
                    vorarbeitLetzte.addLast(vorarbeit);
                    while (vorarbeitLetzte.size() > VORARBEIT_MERKEN) vorarbeitLetzte.removeFirst();
                    Log.debug("Arena", "Vorarbeit bis zum Angriff: " + vorarbeit / 1000 + " s (Schnitt "
                            + vorarbeitSchnittMs / 1000 + " s, Angriff ab " + spaetestensS() + " s Rest).");
                }
                quickFehltSeit = 0;
                rundeGespielt = true;   // diese Stage ist gespielt -- nicht noch einen oeffnen (01:47)
                // Arena 13 (22.10.): vor dem Angriff war schon "alle durchgeschaut" -- nach dem Angriff gibt es einen neuen
                // Rundgang (Stage 1: auch Platz 1-3 und 10, ab Stage 2: Luecken), sonst griff nachAngriffDurchschauen() nie.
                allesErkundet = false;

                // WER HIER WIRKLICH STEHT, nicht wer angeklickt wurde.
                //
                // imKampfGegen wird gesetzt, wenn in der Pyramide ein Schild
                // angeklickt wird. Danach blaettert der Bot im
                // Vergleichsfenster aber unter Umstaenden mehrfach weiter --
                // und dann stimmt der Merker nicht mehr mit dem ueberein,
                // gegen den tatsaechlich angetreten wird. Der Notizblock
                // haette das Ergebnis dem falschen Spieler zugeschrieben.
                if (!werDaSteht.isBlank()) {
                    boolean fingerabdruck = imKampfGegen != null && imKampfGegen.name().startsWith("Bild-");
                    if (!fingerabdruck && (imKampfGegen == null
                            || !namensSchluessel(imKampfGegen.name()).equals(schluessel))) {
                        offenesErgebnisEintragen();   // ein offener alter Kampf zuerst
                        imKampfGegen = new Schild(0, 0, 0, werDaSteht, seinClan,
                                istBot(seinClan), 0, false, false, false, false, false, false);
                    }
                    if (merkeVerbraucht(werDaSteht, "Eigener Angriff")) {
                        angriffOhneErgebnis = namensSchluessel(werDaSteht);
                        angriffOhneErgebnisRunde = rundeZuletzt;
                    }
                }

                // Die Schaetzung von jetzt festhalten -- nach dem Kampf steht sie neben dem
                // Ergebnis in arena-lernen.txt (08.10.).
                if (imKampfGegen != null) {
                    String k = namensSchluessel(imKampfGegen.name());
                    kampfPunkte = punkteJetzt.get(k);
                    kampfReferenz = referenzPunkte();
                    kampfTop3 = top3Modus();
                    try {
                        kampfChance = siegchance(imKampfGegen);
                    } catch (RuntimeException e) {
                        kampfChance = -1;
                    }
                }

                runs.progress(Sprache.t("Attack " + angriffeGemacht, "Angriff " + angriffeGemacht));
                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }
            // Der eigene Angriff dieser Runde ist schon durch (Test 29.09., 23:40):
            // "Quick Attack" ist grau, oben steht "Next stage in". Direkt nach dem
            // Sieg war das oben noch nicht zu sehen, der Bot oeffnete den naechsten
            // Gegner und wartete hier eine Minute lang mit Warnungen. Zurueck zur
            // Pyramide -- dort wartet er ohnehin ruhig auf die neue Runde.
            // Graue Knoepfe (Autor, 30.09.): "Quick Attack" und "To battle" sind grau, wenn
            // in dieser Stage schon angegriffen wurde -- egal gegen wen. Dann ist hier
            // nichts mehr zu holen (01:08: 90 s lang gewartet, Stage verpasst).
            if (quickFehltSeit == 0) quickFehltSeit = System.currentTimeMillis();
            boolean grau = System.currentTimeMillis() - quickFehltSeit > 5000;   // 5 s: das Fenster braucht manchmal laenger (01.10.)
            boolean naechsteStage = ctx.visible(screen, T_NEXT_STAGE);
            // Grau, aber in dieser Stage noch kein eigener Angriff: dann ist DIESER Gegner
            // verbraucht (Autor, 01.10., Stage 3: der Bot wollte MR.JUAN aus Stage 1 noch
            // einmal und wartete 90 s). Merken, zurueck, einen anderen waehlen. Passiert es
            // zweimal in einer Runde, ist die Stage wohl doch gespielt (Autor selbst?).
            if (grau && !naechsteStage && !rundeGespielt && grauInRunde < 2) {
                if (!werDaSteht.isBlank()) merkeVerbraucht(werDaSteht, "Knoepfe grau");
                if (imKampfGegen != null) {
                    if (!imKampfGegen.name().isBlank()) merkeVerbraucht(imKampfGegen.name(), "Knoepfe grau");
                    if (imKampfGegen.x() > 0) graueSchilder.add(new int[]{imKampfGegen.x(), imKampfGegen.y()});
                }
                if (vergleichSchliessen(screen, ctx, Sprache.t("Buttons grey without own attack -- opponent used up, back to the pyramid",
                        "Knoepfe grau ohne eigenen Angriff -- Gegner verbraucht, zurueck zur Pyramide"))) {
                    Log.info("Arena", Sprache.t("No orange \"Quick Attack\" at " + (werDaSteht.isBlank() ? "this opponent" : werDaSteht)
                            + " although nobody has been attacked in this stage yet -- they are probably already used up. "
                            + "Choosing another one.",
                            "Kein orangener \"Quick Attack\" bei " + (werDaSteht.isBlank() ? "diesem Gegner" : werDaSteht)
                            + ", obwohl in dieser Stage noch nicht angegriffen wurde -- er ist wohl schon verbraucht. "
                            + "Es wird ein anderer gewaehlt."));
                    grauInRunde++;   // nur zaehlen, wenn das Fenster wirklich zuging (Review 01.10.)
                    imKampfGegen = null;
                    quickFehltSeit = 0;
                    durchgeblaettert = 0;
                    return Outcome.ACTED;
                }
                // Das X war nicht zu treffen: warten, nicht gleich die Stage abschreiben.
                return warteMit(ctx, screen, Sprache.t("Comparison window (grey, closing)", "Vergleichsfenster (grau, schliessen)"), T_CLOSE_X);
            }
            if ((grau || naechsteStage)
                    && vergleichSchliessen(screen, ctx, Sprache.t("Stage already played (buttons grey) -- back to the pyramid",
                            "Stage schon gespielt (Knoepfe grau) -- zurueck zur Pyramide"))) {
                Log.info("Arena", Sprache.t("No orange \"Quick Attack\" -- this stage is already played, waiting for the next one.",
                        "Kein orangener \"Quick Attack\" -- diese Stage ist schon gespielt, es wird auf die naechste gewartet."));
                rundeGespielt = true;
                imKampfGegen = null;
                quickFehltSeit = 0;
                return Outcome.ACTED;
            }
            // Der Knopf ist nicht da. Nicht endlos dieselbe Zeile schreiben
            // -- einmal sagen, warum, und dann warten.
            return warteMit(ctx, screen, Sprache.t("Comparison window (attack)", "Vergleichsfenster (Angriff)"), T_QUICK);
        }

        durchgeblaettert++;
        Log.info("Arena", Sprache.t((imKampfGegen != null
                        ? "This is " + (werDaSteht.isBlank() ? "somebody else" : werDaSteht)
                          + ", chosen was " + imKampfGegen.wer()
                        : "Power not readable")
                + " -- on to the next one (" + durchgeblaettert + "/"
                + MAX_DURCHBLAETTERN + ").",
                (imKampfGegen != null
                        ? "Hier steht " + (werDaSteht.isBlank() ? "jemand anderes" : werDaSteht)
                          + ", gewaehlt war " + imKampfGegen.wer()
                        : "Kampfkraft nicht lesbar")
                + " -- weiter zum naechsten (" + durchgeblaettert + "/"
                + MAX_DURCHBLAETTERN + ")."));
        if (ctx.clickIfVisible(screen, T_NEXT_ARROW, Sprache.t("Next opponent", "Naechster Gegner"))) {
            return Outcome.ACTED;
        }
        return warteMit(ctx, screen, Sprache.t("Comparison window", "Vergleichsfenster"), T_NEXT_ARROW, T_QUICK);
    }

    /**
     * Durchschauen in der Wartezeit einer Stage (Autor, 30.09.). Die Kraft liest
     * vergleich() bei jedem Gegner, Sets/Artefakte/Bauarbeiter prueft es, wo noetig
     * (nicht in den letzten PRUEFUNG_GILT_TAGE Tagen angesehen). Hier nur: weiter-
     * blaettern, merken wann man einmal herum ist, und rechtzeitig zurueck zur
     * Pyramide -- gewaehlt und angegriffen wird dort, in den letzten
     * SPAETESTENS_BEI_SEKUNDEN.
     */
    private Outcome erkundungsSchritt(Mat screen, BotContext ctx, RunTracker runs, String schluessel, String ident) {
        letzterErkundungsBlick = System.currentTimeMillis();
        // Einmal herum? Derselbe Gegner wie eben (nach der Einheiten-Pruefung) zaehlt nicht.
        boolean herum = false;
        // Weder Name noch Kraft lesbar (Bild mitten im Umblaettern): bis zu 2 s noch einmal
        // hinsehen, statt den Gegner zu ueberspringen (01.10.: GiaoGiao erst in Stage 3 gesehen).
        if (ident.isEmpty()) {
            if (unlesbarSeit == 0) unlesbarSeit = System.currentTimeMillis();
            if (System.currentTimeMillis() - unlesbarSeit < 2000) return Outcome.WAITING;
        } else {
            unlesbarSeit = 0;
        }
        boolean anderer = !ident.isEmpty() && !ident.equals(letzterGesehen);
        if (!ident.isEmpty()) letzterGesehen = ident;
        if (!schluessel.isEmpty() && !rundgang.add(schluessel) && anderer) herum = true;
        if (herum || rundgangBlaetter >= MAX_BLAETTERN_HART) {
            herumRunde = rundeZuletzt;   // jetzt kennt der Bot die Kraft aller Gegner dieser Runde
            boolean pruefbar = ArenaPruefer.moeglich(ctx);
            int pruefStand = pruefungen.size() + pruefVersucht.size();
            boolean nichtsNeues = pruefStandBeiRundgang >= 0 && pruefStand == pruefStandBeiRundgang;
            pruefStandBeiRundgang = pruefStand;
            // Autor 03.11. (schwaechste zuerst): hat die Reihenfolge einen ganzen Rundgang lang nur zurueckgestellt, laesst sich der
            // schwaechere wohl nicht ansehen -- fuer diese Runde wie frueher, der Reihe nach.
            if (nichtsNeues && !zurueckgestellt.isEmpty() && !reihenfolgeAus) {
                reihenfolgeAus = true;
                nichtsNeues = false;
                Log.info("Arena", Sprache.t("Weakest-first order: a whole lap without a check -- this round in the old order.",
                        "Reihenfolge schwaechste zuerst: ein ganzer Rundgang ohne Pruefung -- diese Runde wieder der Reihe nach."));
            }
            if (nichtsNeues && pruefbar && !rundgang.stream().allMatch(this::durchgeschaut)) {
                Log.info("Arena", Sprache.t("A whole lap without a new unit check -- enough scouting for this round.",
                        "Ein ganzer Rundgang ohne neue Einheiten-Pruefung -- genug durchgeschaut fuer diese Runde."));
            }
            if (!pruefbar || nichtsNeues || rundgang.stream().allMatch(this::durchgeschaut)) {
                allesErkundet = true;
                allesErkundetRunde = Math.max(1, rundeZuletzt);
                Log.info("Arena", Sprache.t("All opponents scouted: " + staerken.size() + " power values, "
                        + pruefungen.size() + " checked in this tournament, the rest was known"
                        + (pruefbar ? "" : " (unit check not possible, images missing)")
                        + ". The choice is made in the last " + SPAETESTENS_BEI_SEKUNDEN + " s.",
                        "Alle Gegner durchgeschaut: " + staerken.size() + " Kampfstaerken, "
                        + pruefungen.size() + " in diesem Turnier geprueft, der Rest war bekannt"
                        + (pruefbar ? "" : " (Einheiten-Pruefung nicht moeglich, Bilder fehlen)")
                        + ". Gewaehlt wird in den letzten " + SPAETESTENS_BEI_SEKUNDEN + " s."));
            }
            rundgang.clear();
            rundgangBlaetter = 0;
        }
        long rest = restSekunden();
        boolean zeitReicht = rest >= 0 ? rest > erkundenBisS()
                : System.currentTimeMillis() - rundeSeit < RUNDE_SPAETESTENS_MS - 8_000;
        if (allesErkundet || !zeitReicht) {
            nurErkunden = false;
            buch.jetztSpeichern();
            listeSchreiben();
            if (vergleichSchliessen(screen, ctx, allesErkundet
                    ? Sprache.t("All scouted -- back to the pyramid", "Alle durchgeschaut -- zurueck zur Pyramide")
                    : Sprache.t("Attacking soon -- back to the pyramid", "Gleich wird angegriffen -- zurueck zur Pyramide"))) {
                imKampfGegen = null;
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }
        rundgangBlaetter++;
        if (ctx.clickIfVisible(screen, T_NEXT_ARROW, Sprache.t("Next opponent (scouting)", "Naechster Gegner (Durchschauen)"))) {
            unlesbarSeit = 0;   // der naechste Gegner bekommt wieder seine 2 s (Review 01.10.)
            blaetterKlickUm = System.currentTimeMillis();   // der Platz zaehlt weiter, sobald ein anderer dasteht
            runs.progress(Sprache.t("Scouting", "Durchschauen"));
            return Outcome.ACTED;
        }
        return warteMit(ctx, screen, Sprache.t("Comparison window (scouting)", "Vergleichsfenster (Durchschauen)"), T_NEXT_ARROW);
    }

    /**
     * Das Vergleichsfenster schliessen. Sein rotes X sitzt bei 950,132 -- dort passt
     * ar_dweller_close (0,996), ar_close_x (das X des Turnierfensters) nur 0,80 und
     * damit unter der Schwelle: Bis 30.09. ging das Fenster deshalb NIE zu, der Bot
     * blieb bei grauen Knoepfen stehen (01:08, 01:47).
     */
    private boolean vergleichSchliessen(Mat screen, BotContext ctx, String grund) {
        boolean zu = ctx.clickIfVisibleIfKnown(screen, "ar_dweller_close", grund)
                || ctx.clickIfVisible(screen, T_CLOSE_X, grund);
        if (zu) ctx.sleepUntilChanged(20, 800);   // nicht im naechsten Bild noch einmal klicken
        return zu;
    }

    /** Ist dieser Gegner fertig durchgeschaut, oder gibt es bei ihm nichts zu pruefen? */
    /** Autor 03.11.: in dieser Runde zurueckgestellt, weil erst ein schwaecherer unbekannter dran ist. */
    private final java.util.Set<String> zurueckgestellt = new java.util.HashSet<>();
    private int reihenfolgeRunde = -1, herumRunde = -1;
    private boolean reihenfolgeAus;

    /**
     * Autor 03.11.: "Der Bot soll gezielt Teams anschauen, die er noch nicht kennt -- vom schwaechsten bis zum staerksten." Beim
     * Durchschauen wird ein unbekannter Gegner erst geprueft, wenn kein schwaecherer unbekannter mehr offen ist. Die Kraft eines
     * Gegners kennt der Bot erst, wenn er einmal im Vergleichsfenster stand -- bis alle bekannt sind (ein Rundgang mit dem Pfeil),
     * wird nur geblaettert. Nur mit arena-regeln-neu.txt "an".
     */
    private boolean erstSchwaecherer(String schluessel) {
        if (!de.hustlebot.core.ArenaRegelnNeu.an()) return false;
        if (reihenfolgeRunde != rundeZuletzt) {
            reihenfolgeRunde = rundeZuletzt;
            zurueckgestellt.clear();
            reihenfolgeAus = false;
        }
        if (reihenfolgeAus) return false;
        Long meine = staerken.get(schluessel);
        if (meine == null || meine <= 0) return false;
        if (herumRunde != rundeZuletzt && staerken.size() < 9) {
            zurueckgestellt.add(schluessel);
            return true;
        }
        String schwaecher = schwaechererOffen(schluessel, meine, staerken, this::pruefbarOffen);
        if (schwaecher == null) return false;
        if (zurueckgestellt.add(schluessel)) {
            Log.debug("Arena", "Schwaechste zuerst: " + schluessel + " (" + meine + ") wartet, erst " + schwaecher + " (" + staerken.get(schwaecher) + ").");
        }
        return true;
    }

    /** Noch nicht durchgeschaut und nicht zu oft abgebrochen -- kommt fuer "schwaechste zuerst" noch dran. */
    private boolean pruefbarOffen(String k) {
        return !durchgeschaut(k) && pruefAbbrueche.getOrDefault(k, 0) < PRUEF_ABBRUECHE_MAX;
    }

    /** Ein schwaecherer Gegner (kleinere Kraft), der noch offen ist, oder null. */
    static String schwaechererOffen(String schluessel, long kraft, java.util.Map<String, Long> staerken, java.util.function.Predicate<String> offen) {
        String best = null;
        long bestKraft = Long.MAX_VALUE;
        for (var e : staerken.entrySet()) {
            Long k = e.getValue();
            if (e.getKey().equals(schluessel) || k == null || k <= 0 || k >= kraft || !offen.test(e.getKey())) continue;
            if (k < bestKraft) {
                best = e.getKey();
                bestKraft = k;
            }
        }
        return best;
    }

    private boolean durchgeschaut(String schluessel) {
        if (ohneClanGesehen.contains(schluessel) || pruefungen.containsKey(schluessel)
                || pruefVersucht.contains(schluessel)) return true;
        // Stage 1: die Plaetze 1-3 und 10 kommen erst in Stage 2 dran -- fuer diese Runde fertig.
        // Nach dem eigenen Angriff (Autor 22.10.) gehoeren sie dazu: die Zeit ist uebrig, in Stage 2 fehlt sie dann nicht.
        if (rundeZuletzt <= 1 && !rundeGespielt && platzInStage1.containsKey(schluessel) && !gruenInStage1(schluessel)) return true;
        String[] nc = anzeigeNamen.get(schluessel);
        if (nc == null) return true;   // ohne lesbaren Namen laesst sich nichts nachschlagen
        var notiz = buch.nachschlagen(nc[0], nc.length > 1 ? nc[1] : "");
        return notiz.isPresent() && notiz.get().geprueftSeit(PRUEFUNG_GILT_TAGE);
    }

    /**
     * Autor 22.10.: "Wenn der Bot in der Stage 1 einen Gegner fruehzeitig angreift, oder auch in den anderen Stages, kann
     * er die restliche Zeit auch noch nutzen, um einen Gegner durchzuschauen, den er noch nicht durchgeschaut hat. Wenn
     * noch genuegend Zeit da ist." Nach dem eigenen Angriff und dem Ergebnis wird darum weiter geblaettert und angesehen,
     * solange die Uhr der Stage mehr als erkundenBisS() zeigt (eine volle Pruefung braucht reserveS() + 21 s).
     * null = nichts zu tun, dann wird wie bisher gewartet.
     */
    private Outcome nachAngriffDurchschauen(Mat screen, BotContext ctx) {
        if (!rundeGespielt || ArenaEinfach.aktiv()) return null;
        // Autor 01.11.: "in Stage 5, nachdem der Bot angegriffen hat, soll er nicht mehr die anderen Gegner anschauen."
        if (rundeZuletzt >= LETZTE_RUNDE) return null;
        long jetzt = System.currentTimeMillis();
        if (angriffGeklicktUm > 0 && jetzt - angriffGeklicktUm < 15_000) return null;   // erst das Ergebnis
        if (jetzt - schildGeklicktUm < 3000) return Outcome.WAITING;   // das Vergleichsfenster geht gerade auf
        long rest = restzeitLesen(screen, ctx);
        if (rest < 0) return null;   // ohne Uhr nicht -- sonst steht der Bot beim Stagewechsel noch im Fenster
        if (nurErkunden) {
            if (jetzt - letzterErkundungsBlick < 3000) return Outcome.WAITING;   // nur kurz beim Umblaettern weg
            nurErkunden = false;
        }
        if (!allesErkundet && rest > erkundenBisS()) {
            if (nachAngriffGemeldetRunde != rundeZuletzt) {
                nachAngriffGemeldetRunde = rundeZuletzt;
                Log.info("Arena", Sprache.t("Stage " + rundeZuletzt + " is played, " + rest
                        + " s are left -- using them to scout opponents not looked at yet.",
                        "Stage " + rundeZuletzt + " ist gespielt, noch " + rest
                        + " s uebrig -- damit werden Gegner angesehen, die noch fehlen."));
            }
            return erkundungStarten(screen, ctx, Math.max(1, rundeZuletzt), rest);
        }
        if (allesErkundet && rest > erkundenBisS() + EXTRAS_BRAUCHT_S) return palBesuchStarten(screen, ctx);
        return null;
    }

    /** In welcher Stage "nach dem Angriff durchschauen" schon gemeldet wurde (nur einmal je Stage ins Protokoll). */
    private int nachAngriffGemeldetRunde = -1;

    /**
     * Autor 22.10.: "Ich starte immer bei 2 Minuten rein, der Bot schaut den ersten Gegner aber erst bei 1:54 an." Beim
     * ersten Durchschauen des Turniers erst klicken, dann lesen: nur die Lage der Schilder (Vorlage "Reward:", schnell),
     * ohne Namen, Listen und Texterkennung -- die liest schilderLesen() beim naechsten Blick auf die Pyramide (~1,5 s
     * gespart).
     */
    private boolean schnellstartGemacht;

    private List<Schild> schildLagen(Mat screen, BotContext ctx) {
        List<Matcher.Hit> eigene = ctx.matcher().findAll(screen, T_ME);
        if (!eigene.isEmpty()) {
            Matcher.Hit ich = eigene.get(0);
            meinPlatz = platzAusLage(ich.centerX(), ich.y() + SCHILD_HOEHE, ctx.width(), ctx.height());
        }
        int b = (int) (ctx.width() * 0.14);
        List<Schild> l = new ArrayList<>();
        for (Matcher.Hit r : rewardTreffer(screen, ctx)) {
            if (inFeld(eigene, r.centerX() - b / 2, r.y() - SCHILD_HOEHE, b, SCHILD_HOEHE)) continue;
            l.add(new Schild(r.centerX(), r.y(), r.width(), "", "", false, 150, false, false, false, false, false, false));
        }
        return l;
    }

    /** Aus der Pyramide ins Vergleichsfenster, nur um durchzuschauen (neuer Rundgang). */
    private Outcome erkundungStarten(Mat screen, BotContext ctx, int runde, long rest) {
        if (ArenaEinfach.aktiv()) return null;   // TS8 (Nutzer 25.11.): kein Durchschauen -- Kraft und Sets zaehlen nicht
        boolean schnell = runde <= 1 && !schnellstartGemacht;
        List<Schild> schilder = schnell ? schildLagen(screen, ctx) : List.of();
        if (schilder.isEmpty()) {
            schnell = false;
            schilder = schilderLesen(screen, ctx);
        }
        if (schnell) {
            schnellstartGemacht = true;
            Log.debug("Arena", "Schnellstart: erst klicken, die Namen der " + schilder.size() + " Schilder kommen danach.");
        }
        if (schilder.isEmpty()) return null;
        Schild s = schilder.get(0);
        if (runde <= 1) {
            // Autor 22.10.: bei Platz 8 anfangen -- die Blaetterfolge geht dann 8, 9, (10, 1-3 nur vorbei), 4-7. Dort sitzt
            // in Runde 1 meistens der Schwaechste, er wird zuerst und in Ruhe angesehen. Fehlt Platz 8, der kleinste aus 4-9.
            Schild bester = null;
            int besterPlatz = 99;
            for (Schild k : schilder) {
                int platz = platzAusLage(k.x(), k.y(), ctx.width(), ctx.height());
                if (platz < STAGE1_VON || platz > STAGE1_BIS) continue;
                int rang = platz == ZUERST_RUNDE1 ? 0 : platz;
                if (rang < besterPlatz) {
                    bester = k;
                    besterPlatz = rang;
                }
            }
            if (bester != null) s = bester;
        } else if (runde <= 3) {
            // Autor 22.10.: in Runde 2 und 3 sitzt der Schwaechste meistens auf Platz 9-10 -- dort anfangen.
            for (Schild k : schilder) {
                int platz = platzAusLage(k.x(), k.y(), ctx.width(), ctx.height());
                if (platz == ZUERST_RUNDE23 || (platz == 10 && s == schilder.get(0))) s = k;
                if (platz == ZUERST_RUNDE23) break;
            }
        }
        nurErkunden = true;
        letzterErkundungsBlick = System.currentTimeMillis();
        imKampfGegen = null;
        rundgang.clear();
        rundgangBlaetter = 0;
        pruefStandBeiRundgang = pruefungen.size() + pruefVersucht.size();
        letzterGesehen = "";
        blaetterKlickUm = 0;
        // Ohne den eigenen Platz (ar_me nicht gefunden) laesst sich die Blaetterfolge nicht
        // zaehlen -- das eigene Schild wird uebersprungen und die Zaehlung waere um eins daneben.
        blaetterPlatz = runde <= 1 && meinPlatz > 0 ? platzAusLage(s.x(), s.y(), ctx.width(), ctx.height()) : -1;
        Log.info("Arena", Sprache.t("Round " + runde + ": using the waiting time for scouting ("
                + (rest < 0 ? "?" : rest) + " s left)" + (blaetterPlatz > 0 ? ", starting at place " + blaetterPlatz : "") + ".",
                "Runde " + runde + ": Wartezeit zum Durchschauen nutzen (noch "
                + (rest < 0 ? "?" : rest) + " s)" + (blaetterPlatz > 0 ? ", Start bei Platz " + blaetterPlatz : "") + "."));
        schildOeffnen(ctx, s);
        return Outcome.ACTED;
    }

    // ==================================================================
    //  7. Ergebnis
    // ==================================================================

    private Outcome ergebnis(Mat screen, BotContext ctx, RunTracker runs) {
        angriffGeklicktUm = 0;
        unbekannteBilder = 0;
        BufferedImage bild = ctx.frame();

        boolean gewonnen = ctx.visible(screen, T_VICTORY);
        long punkte = ctx.find(screen, T_POINTS)
                .flatMap(h -> optional(ArenaLeser.zahlUnter(bild, h, (int) (ctx.height() * 0.09))))
                .orElse(-1L);
        long dauer = ctx.find(screen, T_TIME)
                .flatMap(h -> {
                    OptionalLong z = ArenaLeser.zahlUnter(bild, h, (int) (ctx.height() * 0.09));
                    // Einstellige Dauer ("7s") liest die Erkennung allein nicht (01.10.).
                    if (z.isEmpty()) z = ArenaLeser.zahlHinterWort(bild, h.x() - 20, h.y() + h.height(),
                            h.width() + 40, (int) (ctx.height() * 0.09), "Dauer");
                    return optional(z);
                })
                .orElse(-1L);

        // Nur EINMAL melden und eintragen.
        //
        // Der Ergebnisbildschirm steht mehrere Bilder lang, und solange
        // "Home" noch nicht anklickbar ist, laeuft dieser Zweig immer
        // wieder. Im Protokoll stand deshalb zweimal "Sieg, 150
        // Turnierpunkte" fuer denselben Kampf -- und wer das liest, zaehlt
        // Kaempfe, die es nie gab.
        //
        // Aber NICHT gleich beim ersten Bild (25.09.): die Zaehler laufen
        // dort erst hoch (0 ... 150), und wer das erste Bild nimmt, notiert
        // 37 statt 150. Deshalb ueber die Bilder das Groesste merken und
        // erst eintragen, wenn "Home" da ist oder nach vier Sekunden.
        if (ergebnisSeit == 0 && (imKampfGegen != null || !ergebnisGemeldet)) {
            ergebnisSeit = System.currentTimeMillis();
            ergebnisSieg = false;
            ergebnisPunkte = -1;
            ergebnisDauer = -1;
            ergebnisStaerke = letzteGegnerstaerke;
            // Spaetestens jetzt ist dieser Gegner verbraucht -- der Kampf
            // ist ja vorbei. Beim Klick auf den Schnellangriff wird das
            // schon versucht; hier steht es noch einmal fuer den Fall, dass
            // der Name dort nicht lesbar war.
            if (imKampfGegen != null) merkeVerbraucht(imKampfGegen.name(), "Kampf beendet");
        }
        if (ergebnisSeit > 0) {
            ergebnisSieg |= gewonnen;
            // Mehr als 300 Punkte oder 5 Minuten gibt es nicht -- verlesen.
            if (punkte <= 300) ergebnisPunkte = Math.max(ergebnisPunkte, punkte);
            if (dauer <= Gegnerbuch.MAX_KAMPFZEIT_S) ergebnisDauer = Math.max(ergebnisDauer, dauer);   // Idee 16: mehr ist verlesen
            // Mindestens 2,5 s zusehen: "Home" steht von Anfang an da, die Zahlen zaehlen erst
            // hoch -- deshalb fehlten Zeit und Punkte im Notizblock meist (Autor, 01.10.).
            long steht = System.currentTimeMillis() - ergebnisSeit;
            // Und bis zu 6 s, solange die Punktzahl noch nicht zu lesen war: Nach dem
            // Schnellangriff steht das Ergebnis sofort da, die Zahlen zaehlen erst hoch
            // (02:00, 01.10.: nach 2,5 s "Sieg" ohne Punkte und Zeit).
            if (steht < 2500 || (ergebnisSieg && ergebnisPunkte < 0 && steht < 6000)
                    || (!ctx.visible(screen, T_HOME_RESULT) && steht < 6000)) {
                return Outcome.WAITING;
            }
            // TS8: keine Listenbilder nach den TS11-Regeln (7 s / 14 s) -- dort zaehlt nur die Bilanz in arena-ts8.txt.
            if (ArenaEinfach.aktiv()) vorlageFuer = null;
            else namensbildVormerken();
            offenesErgebnisEintragen();
        }

        // Nur der "Home" unten auf dem Ergebnisbildschirm. Der gruene "Home" oben rechts in
        // der Pyramide passt zu 0,89 auf dieselbe Vorlage (01.10., 01:16) -- und der wuerde
        // das Turnier verlassen.
        // 28.10. (Berichte #YMS7/#LHD9): endet das Turnier mit dem letzten Kampf, steht "Claim reward"
        // gruen oben rechts hinter dem Ergebnis und passt besser als der "Home" unten -- dann nur noch
        // "alles da, aber nichts geklickt" bis zum Notausstieg. Deshalb gezielt den unteren suchen.
        Optional<Matcher.Hit> home = homeUnten(ctx, screen);
        if (home.isPresent()) {
            ctx.klick(home.get(), Sprache.t("Back to the pyramid (Home)", "Zurueck zur Pyramide (Home)"));
            runs.progress(gewonnen ? Sprache.t("Victory", "Sieg") : Sprache.t("Defeat", "Niederlage"));
            ctx.sleepUntilChanged(20, 1500);   // statt dreimal zu klicken
            return Outcome.ACTED;
        }
        return warteMit(ctx, screen, Sprache.t("Result screen", "Ergebnisbildschirm"), T_HOME_RESULT);
    }

    /** Der "Home" in der unteren Bildhaelfte (Ergebnisbildschirm), nie der gruene Knopf oben rechts. */
    private Optional<Matcher.Hit> homeUnten(BotContext ctx, Mat screen) {
        Optional<Matcher.Hit> home = ctx.find(screen, T_HOME_RESULT);
        if (home.isPresent() && home.get().centerY() > ctx.height() * 0.5) return home;
        if (home.isEmpty()) return Optional.empty();
        for (Matcher.Hit h : ctx.matcher().findAll(screen, T_HOME_RESULT)) {
            if (h.centerY() > ctx.height() * 0.5) return Optional.of(h);
        }
        return Optional.empty();
    }

    /**
     * Traegt den offenen Kampfausgang ein: ins Protokoll und in den
     * Notizblock. Laeuft, wenn "Home" da ist oder vier Sekunden um sind --
     * und zur Sicherheit auch, bevor der naechste Kampf beginnt oder ein
     * neues Turnier anfaengt, damit kein Kampf verloren geht.
     */
    /** Pokalzahl ("300", von der kyrillischen Lesung als "зоо", "хооо" geliefert)? */
    static boolean pokalZeile(String z) {
        if (z == null) return false;
        String t = z.strip().toLowerCase();
        if (t.isEmpty() || t.length() > 6) return false;
        return t.chars().allMatch(c -> "0123456789oо3зxх ".indexOf(c) >= 0);
    }

    /**
     * Autor 01.11.: das CLANBAND des Schilds -- die Zeile unter dem Namen, ab dem Namensanfang (hinter dem Wappen), vor der Punktezeile. Getrennt gelesen, damit
     * der Clan nie als Name durchgeht (siehe {@link ArenaSchildLage}). null = nichts Lesbares (auch die Punktezahl zaehlt nicht).
     */
    private String clanImBand(BufferedImage bild, BotContext ctx, int mitteX, int rewardY) {
        return clanBandLesen(bild, ctx.width(), mitteX, rewardY);
    }

    static String clanBandLesen(BufferedImage bild, int fensterBreite, int mitteX, int rewardY) {
        if (bild == null) return null;
        int oben = rewardY - SCHILD_HOEHE;
        int x0 = mitteX - (int) (fensterBreite * 0.046), x1 = mitteX + (int) (fensterBreite * 0.070);
        java.awt.Rectangle nk = textKasten(bild, x0, oben + 12, x1, oben + 33);
        int start = nk != null ? Math.max(x0, nk.x - 3) : x0 + (int) (fensterBreite * 0.013);
        List<String> z = ArenaLeser.liesNamenZeilen(bild, start, oben + CLAN_BAND_VON, x1 - start, CLAN_BAND_HOEHE);
        if (z.isEmpty()) return null;
        String t = z.get(0).strip();
        return t.isEmpty() || pokalZeile(t) ? null : t;
    }

    /** Das Clanband liegt unter dem Namensband (Zeilen 12-33 ab Schildoberkante) und endet vor der Punktezeile (ab 53). */
    static final int CLAN_BAND_VON = 33, CLAN_BAND_HOEHE = 20;

    /** Name im Schild ueber dem "Reward:" -- nur die weisse Schrift; null = nicht lesbar. */
    private final Map<Long, String> kastenCache = new java.util.HashMap<>();
    /** "Place: N" des Gegners im Vergleichsfenster, je Gegner (Kraft) und Runde. */
    private final Map<String, Integer> fensterPlatzCache = new java.util.HashMap<>();
    private final Map<String, Integer> fensterPlatzFehl = new java.util.HashMap<>();

    /**
     * Der Platz des Gegners, der im Vergleichsfenster steht -- das Spiel schreibt ihn unter den
     * Kraftbalken rechts ("Place: 7"). Daran erkennt der Bot sein Ziel auch dann, wenn der Name
     * verstuemmelt gelesen wird (01.10., Runde 2, Stage 3: "Y hnbRM BenhKhl" auf dem Schild,
     * "YnnbAM Benno-IVI" im Fenster -- neunmal geblaettert, dann den Falschen angegriffen).
     * -1 = nicht lesbar.
     */
    private int platzImFenster(BufferedImage bild, BotContext ctx, Optional<Matcher.Hit> swords, String ident) {
        if (bild == null || swords.isEmpty() || ident.isEmpty()) return -1;
        Integer bekannt = fensterPlatzCache.get(ident);
        if (bekannt != null) return bekannt;
        Matcher.Hit s = swords.get();
        String zeile = ArenaLeser.liesTextGross(bild, s.centerX() + (int) (ctx.width() * 0.18),
                s.y() + s.height() - 24, (int) (ctx.width() * 0.22), (int) (ctx.height() * 0.055));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:pl|p1|ac)\\w*\\W{0,3}(\\d{1,2})").matcher(zeile);
        int platz = -1;
        if (m.find()) {
            int p = Integer.parseInt(m.group(1));
            if (p >= 1 && p <= 10) platz = p;
        }
        if (platz < 0) {
            // Pruefung 29.10.: nur 319 von 721 gelesen (381x nur "Place:") -- die Ziffer ist weiss mit dunklem Rand wie
            // "+150". Zweiter Versuch mit der Weiss-Vorstufe; ein Fehlschlag wird nicht fuer die ganze Runde gemerkt.
            OptionalLong w = ArenaLeser.weisseZahl(bild, s.centerX() + (int) (ctx.width() * 0.18),
                    s.y() + s.height() - 24, (int) (ctx.width() * 0.22), (int) (ctx.height() * 0.055));
            if (w.isPresent() && w.getAsLong() >= 1 && w.getAsLong() <= 10) platz = (int) w.getAsLong();
        }
        if (platz < 0) {
            Log.debug("Arena", "Platz im Vergleichsfenster nicht lesbar: \"" + zeile + "\"");
            // hoechstens dreimal je Gegner und Runde versuchen (jede Lesung kostet Zeit im Angriffsfenster)
            if (fensterPlatzFehl.merge(ident, 1, Integer::sum) < 3) return platz;
        }
        fensterPlatzCache.put(ident, platz);
        return platz;
    }

    private String nameImKasten(BufferedImage bild, BotContext ctx, int mitteX, int rewardY) {
        if (bild == null) return null;
        int oben = rewardY - SCHILD_HOEHE;
        java.awt.Rectangle k = textKasten(bild, mitteX - (int) (ctx.width() * 0.046), oben + 12,
                mitteX + (int) (ctx.width() * 0.070), oben + 33);
        if (k == null) return null;
        long hash = 1469598103934665603L;
        for (int y = k.y; y < k.y + k.height; y += 2) {
            for (int x = k.x; x < k.x + k.width; x += 2) {
                int p = bild.getRGB(x, y);
                hash = (hash ^ (Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 200 ? 1 : 0)) * 1099511628211L;
            }
        }
        hash = hash * 31 + k.width;
        String bekannt = kastenCache.get(hash);
        if (bekannt != null) return bekannt.isEmpty() ? null : bekannt;
        String text = ArenaLeser.textHinterWort(bild, k.x, k.y, k.width, k.height, "Name");
        if (unleserlich(text)) text = "";
        kastenCache.put(hash, text);
        if (!text.isEmpty()) Log.debug("Arena", "Name im weissen Kasten bei " + mitteX + "," + rewardY + ": " + text);
        return text.isEmpty() ? null : text;
    }

    // ---- Eigene Schrifterkennung fuer Spielernamen (Autor 24.10.) -------------------------------------------------
    /** Die Glyphen-Bibliothek im Bot-Ordner (glyphen\). */
    private final Glyphenschrift glyphen = Glyphenschrift.standard();
    /** Namensbild (Fingerabdruck + eigene Schrift) je Gegner (namensSchluessel) in diesem Turnier, aus seinem Schild. */
    private final Map<String, Glyphenschrift.Ergebnis> namensbildVon = new java.util.HashMap<>();
    /** Schon zerlegte Schild-Ausschnitte (Kennwert der weissen Punkte -> Ergebnis): nicht zweimal zerlegen und lernen. */
    private final Map<Long, Glyphenschrift.Ergebnis> namensbildCache = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /**
     * Autor 24.10.: das Namensbild im Schild -- derselbe Ausschnitt wie die Namensbilder der Listen (nur die weisse
     * Schrift) -- mit der eigenen Schrifterkennung zerlegen: Fingerabdruck, egal wie Windows den Namen liest. Aus einer
     * sauberen Windows-Lesung mit passender Zeichenzahl lernt die Bibliothek die Buchstaben. Keine Texterkennung,
     * gemessen 0,4-2 ms je Name; dasselbe Bild wird nur einmal zerlegt. null = keine Schrift gefunden.
     */
    private Glyphenschrift.Ergebnis namensbildErkennen(BufferedImage bild, BotContext ctx, int mitteX, int rewardY, String gelesen) {
        if (bild == null) return null;
        try {
            int oben = rewardY - SCHILD_HOEHE;
            java.awt.Rectangle k = textKasten(bild, mitteX - (int) (ctx.width() * 0.046), oben + 12,
                    mitteX + (int) (ctx.width() * 0.070), oben + 33);
            if (k == null) return null;
            long hash = 1469598103934665603L;
            for (int y = k.y; y < k.y + k.height; y++) {
                for (int x = k.x; x < k.x + k.width; x++) {
                    int p = bild.getRGB(x, y);
                    hash = (hash ^ (Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 200 ? 1 : 0)) * 1099511628211L;
                }
            }
            hash = hash * 31 + k.width * 131L + k.height;
            Glyphenschrift.Ergebnis e = namensbildCache.get(hash);
            if (e == null) {
                e = glyphen.erkennen(bild.getSubimage(k.x, k.y, k.width, k.height));
                if (namensbildCache.size() > 500) namensbildCache.clear();
                namensbildCache.put(hash, e);
                if (gelesen != null && !gelesen.isBlank() && !unleserlich(gelesen)) glyphen.lernen(e, gelesen);
            }
            return e.leer() ? null : e;
        } catch (RuntimeException ex) {
            Log.debug("Arena", "Namensbild nicht zerlegt: " + ex);
            return null;
        }
    }

    /** Das Namensbild dieses Gegners aus der Pyramide (auch ueber die Zuordnung Schild = Fenster), oder null. */
    private Glyphenschrift.Ergebnis namensbildZu(String name) {
        String k = namensSchluessel(name);
        if (k.isEmpty()) return null;
        Glyphenschrift.Ergebnis e = namensbildVon.get(k);
        if (e == null) e = namensbildVon.get(schildAlias.getOrDefault(k, ""));
        return e;
    }

    /** Fingerabdruck des Namensbildes dieses Gegners, oder null. */
    private String fingerabdruckZu(String name) {
        Glyphenschrift.Ergebnis e = namensbildZu(name);
        return e == null ? null : e.fingerabdruck();
    }

    /**
     * Sieht der gelesene Name nach Lesefehler aus? Leer, unter drei Buchstaben, oder
     * Zeichen, die in keinem Namen vorkommen ("JtHXED •" fuer JINXED MURENA).
     */
    /**
     * Das Schild dieser Runde an diesem Platz (aus der zuletzt gelesenen Pyramide), mit lesbarem Namen -- oder null.
     * Kennt der Bot die Kraft des Schild-Spielers und weicht sie von der im Fenster ab, ist es nicht derselbe: null.
     */
    private Schild schildAmPlatz(int platz, long kraftImFenster) {
        if (schilderCacheRunde != rundeZuletzt || breiteZuletzt <= 0) return null;
        for (Schild s : schilderCache) {
            if (s.name().isBlank() || unleserlich(s.name())) continue;
            if (platzAusLage(s.x(), s.y(), breiteZuletzt, hoeheZuletzt) != platz) continue;
            Long bekannt = staerken.get(namensSchluessel(s.name()));
            if (bekannt != null && bekannt > 0 && kraftImFenster > 0 && !bekannt.equals(kraftImFenster)) return null;
            return s;
        }
        return null;
    }

    /**
     * Arena 6 (22.10.): "GROGU —" war fast richtig (nur der Strich stoerte), der Schild-Name "isezanas I" dagegen falsch --
     * der Fenstername wird nur noch ersetzt, wenn er wirklich nichts taugt: unter drei Buchstaben, mit Anfuehrungszeichen
     * vorn ("'\"Benbli n TO" war die Clan-Zeile) oder mehr Ziffern als Buchstaben ("2811").
     */
    /**
     * Autor 01.11.: gehoert der im Vergleichsfenster gelesene "Name" gar nicht zu diesem Schild? Ja, wenn der Schild-Name in einer ganz anderen Schrift steht
     * (weder lateinisch noch kyrillisch, z. B. 屈機黑雞) und das Fenster nur lateinische Buchstaben las (die Clan-Zeile "Cthulhu's Realm"), oder wenn der
     * gelesene Name (ab 4 Zeichen) der Anfang des Clan-Namens ist, ohne dass Schild-Name und gelesener Name zusammenpassen.
     */
    static boolean fensterNameGehoertNichtDazu(String fensterName, String fensterClan, String schildName, String schildClan) {
        if (fensterName == null || schildName == null || schildName.isBlank() || fensterName.isBlank()) return false;
        if (de.hustlebot.core.NamensVergleich.gleicherName(fensterName, schildName)) return false;
        int schildBuchstaben = 0, schildFremd = 0, fensterLatein = 0, fensterAndere = 0;
        for (int i = 0; i < schildName.length(); ) {
            int c = schildName.codePointAt(i);
            i += Character.charCount(c);
            if (!Character.isLetter(c)) continue;
            schildBuchstaben++;
            var skript = Character.UnicodeScript.of(c);
            if (skript != Character.UnicodeScript.LATIN && skript != Character.UnicodeScript.CYRILLIC) schildFremd++;
        }
        for (int i = 0; i < fensterName.length(); ) {
            int c = fensterName.codePointAt(i);
            i += Character.charCount(c);
            if (!Character.isLetter(c)) continue;
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) fensterLatein++;
            else fensterAndere++;
        }
        if (schildBuchstaben >= 2 && schildFremd == schildBuchstaben && fensterLatein >= 3 && fensterAndere == 0) return true;
        String f = de.hustlebot.core.NamensVergleich.form(fensterName);
        if (f.length() < 4) return false;
        for (String clan : new String[]{fensterClan, schildClan}) {
            if (clan == null || clan.isBlank()) continue;
            String c = de.hustlebot.core.NamensVergleich.form(clan);
            if (c.length() > f.length() && c.startsWith(f)) return true;
        }
        return false;
    }

    static boolean schlechterFensterName(String name) {
        if (name == null || name.isBlank()) return true;
        int buchstaben = 0, ziffern = 0;
        for (char c : name.toCharArray()) {
            if (Character.isLetter(c)) buchstaben++;
            else if (Character.isDigit(c)) ziffern++;
        }
        String s = name.strip();
        return buchstaben < 3 || ziffern > buchstaben || s.startsWith("'") || s.startsWith("\"");
    }

    static boolean unleserlich(String name) {
        if (name == null) return true;
        int buchstaben = 0;
        for (char c : name.toCharArray()) {
            if (Character.isLetter(c)) buchstaben++;
            else if (!Character.isDigit(c) && " _.-'()".indexOf(c) < 0) return true;
        }
        return buchstaben < 3;
    }

    /**
     * Legt fuer ein Schild mit unleserlichem Namen ein Namensbild an -- aber erst, wenn auch
     * die vergroesserte Lesung nichts Brauchbares ergibt (kleine Schrift liest sich gross
     * oft doch). Liefert den neuen Anzeigenamen "Bild-MMdd-HHmmss" oder null.
     */
    private String fingerabdruckAnlegen(BufferedImage bild, BotContext ctx, int mitteX, int rewardY, String gelesen) {
        if (bild == null) return null;
        int oben = rewardY - SCHILD_HOEHE;
        java.awt.Rectangle k = textKasten(bild, mitteX - (int) (ctx.width() * 0.046), oben + 12,
                mitteX + (int) (ctx.width() * 0.070), oben + 33);
        if (k == null) return null;
        String gross = ArenaLeser.liesTextGross(bild, k.x, k.y, k.width, k.height);
        if (!unleserlich(gross)) {
            Log.debug("Arena", "Name vergroessert doch lesbar: \"" + gross + "\" (vorher \"" + gelesen + "\").");
            return null;   // der Aufrufer behaelt seinen Namen -- beim naechsten Lesen klappt es meist
        }
        String kennung = "Bild-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss"));
        java.nio.file.Path ziel = Matcher.GELERNT.resolve(de.hustlebot.core.Vorlagenordner.fuer(T_NAME_PREFIX))
                .resolve(T_NAME_PREFIX + kennung + ".png");
        try {
            java.nio.file.Files.createDirectories(ziel.getParent());
            javax.imageio.ImageIO.write(bild.getSubimage(k.x, k.y, k.width, k.height), "png", ziel.toFile());
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Name image could not be saved: ", "Namensbild konnte nicht gespeichert werden: ") + e.getMessage());
            return null;
        }
        Log.info("Arena", Sprache.t("Name not readable (\"" + gelesen + "\") -- name image saved as a fingerprint: "
                + ziel.getFileName() + ". In the notebook this player is now called \"" + kennung
                + "\" (rename with !note, e.g. alias).",
                "Name nicht lesbar (\"" + gelesen + "\") -- Namensbild als Fingerabdruck gespeichert: "
                + ziel.getFileName() + ". Der Spieler heisst im Notizblock jetzt \"" + kennung
                + "\" (umbenennen mit !notiz, z. B. alias)."));
        return kennung;
    }

    // --- Abgestuft: "sicher" verloren -> zaehlt als "machbar" (Autor, 11.10.) ------------------------------------
    private static final java.nio.file.Path ABGESTUFT = java.nio.file.Path.of("arena-abgestuft.txt");

    private static synchronized java.util.List<String> abgestuftLaden() {
        try {
            if (!java.nio.file.Files.exists(ABGESTUFT)) return new ArrayList<>();
            java.util.List<String> l = new ArrayList<>();
            for (String z : java.nio.file.Files.readAllLines(ABGESTUFT, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!z.isBlank() && !z.strip().startsWith("#")) l.add(z.strip());
            }
            return l;
        } catch (Exception e) {
            Log.debug("Arena", "arena-abgestuft.txt nicht lesbar: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    static boolean abgestuft(String name) {
        if (name == null || name.isBlank()) return false;
        for (String a : abgestuftLaden()) if (de.hustlebot.core.NamensVergleich.gleicherName(a, name)) return true;
        return false;
    }

    private static synchronized void abstufen(String name) {
        if (name == null || name.isBlank() || abgestuft(name)) return;
        try {
            if (!java.nio.file.Files.exists(ABGESTUFT)) java.nio.file.Files.writeString(ABGESTUFT,
                    Sprache.t("# Players with a \"safe\" image whom the bot lost to: they count as \"doable\".\n",
                    "# Spieler mit \"sicher\"-Bild, gegen die der Bot verloren hat: zaehlen als \"machbar\".\n"),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(ABGESTUFT, name.strip() + "\n", java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            Log.info("Arena", Sprache.t(name + " was \"safe\" and won -- from now on \"doable\" (arena-abgestuft.txt).",
                    name + " stand auf \"sicher\" und hat gewonnen -- ab jetzt \"machbar\" (arena-abgestuft.txt)."));
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("arena-abgestuft.txt not written: ", "arena-abgestuft.txt nicht geschrieben: ") + e.getMessage());
        }
    }

    /** Wieder "sicher" (schneller Sieg / dritter Sieg): aus der Liste nehmen. true, wenn er drin stand. */
    private static synchronized boolean aufstufen(String name) {
        if (!abgestuft(name)) return false;
        try {
            java.util.List<String> rest = new ArrayList<>();
            for (String z : java.nio.file.Files.readAllLines(ABGESTUFT, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!z.strip().startsWith("#") && de.hustlebot.core.NamensVergleich.gleicherName(z.strip(), name)) continue;
                rest.add(z);
            }
            java.nio.file.Files.write(ABGESTUFT, rest, java.nio.charset.StandardCharsets.UTF_8);
            Log.info("Arena", Sprache.t(name + " beaten quickly again -- counts as \"safe\" again.",
                    name + " wieder schnell besiegt -- zaehlt wieder als \"sicher\"."));
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("arena-abgestuft.txt not changed: ", "arena-abgestuft.txt nicht geaendert: ") + e.getMessage());
        }
        return true;
    }

    // Die Grenzen (Autor 29.10.: 7 s fuer alle Kaempfe) und die Regel selbst stehen in ArenaListenRegel -- der Bot (hier)
    // und der Beobachter (beobZaehlen) benutzen dieselbe Funktion.

    /** Das Namensbild kommt vom dritten Sieg (nicht von der Kampfdauer). */
    private boolean vorlageDritterSieg, vorlageDreiOhneSieg;

    private static String listenPraefix(ArenaListenRegel.Liste l) {
        return switch (l) {
            case NIEMALS -> T_BLOCK_PREFIX;
            case MEIDEN -> T_UNGERN_PREFIX;
            case MACHBAR -> T_MACHBAR_PREFIX;
            case FAST_SICHER -> T_FAST_PREFIX;
            case PAUSE -> T_PAUSE_PREFIX;
            case SICHER -> T_SICHER_PREFIX;
            case KEINE -> null;
        };
    }

    private void namensbildVormerken() {
        vorlageFuer = null;
        vorlageDritterSieg = false;
        vorlageDreiOhneSieg = false;
        // Welche Liste faellig ist, entscheidet ArenaListenRegel.entscheide (Autor 11.10./19.10./29.10., die Regeln stehen
        // dort). Hier nur: Zahlen zusammentragen, "sicher" abstufen, das Namensbild fuer die Pyramide vormerken.
        // Die Zahlen aus dem Notizblock enthalten diesen Kampf noch NICHT -- daher je +1.
        if (imKampfGegen == null || imKampfGegen.x() <= 0) return;
        if (imKampfGegen.botClan() || istBot(imKampfGegen.clan())) return;   // "Not in clan" kommt nie wieder
        Schild g = imKampfGegen;
        var notiz = buch.nachschlagen(g.name(), g.clan());
        boolean vielStaerker = false;
        int siegeGesamt = 0, kaempfeGesamt = 0;
        if (!ergebnisSieg) {
            long seine = ergebnisStaerke > 0 ? ergebnisStaerke : bekannteStaerke(g);
            vielStaerker = seine > 0 && meineStaerke > 0 && seine - meineStaerke >= ArenaListenRegel.VIEL_STAERKER;
            // Autor 19.10.: "Er hat viermal gegen ihn verloren" -- ab der dritten Niederlage ohne einen Sieg (alle
            // Schreibweisen des Namens, diese mitgezaehlt) -> "niemals".
            int[] gesamt = buch.bilanzNachName(g.name(), seine);
            siegeGesamt = gesamt[0];
            kaempfeGesamt = gesamt[1] + 1;
        }
        var kampf = new ArenaListenRegel.Kampf(ergebnisSieg, ergebnisDauer, vielStaerker, siegeGesamt, kaempfeGesamt,
                notiz.map(Gegnerbuch.Notiz::niederlagen).orElse(0) + 1,
                notiz.map(Gegnerbuch.Notiz::siege).orElse(0) + 1);
        var stand = new ArenaListenRegel.Stand(g.gesperrt() && !g.pause(), g.ungern(), g.sicher(), g.machbar(), g.fastSicher(), g.pause());
        var e = ArenaListenRegel.entscheide(kampf, stand);
        if (de.hustlebot.core.ArenaRegelnNeu.an()) e = ArenaListenRegel.mitZwischenstufen(kampf, stand, e);
        // "sicher" verloren -> "machbar". Bilder werden dabei nie geloescht: der Name kommt in arena-abgestuft.txt,
        // dann zaehlt sein sicher-Bild als machbar.
        if (e.abstufen()) abstufen(g.name());
        String art = listenPraefix(e.liste());
        if (art == null) return;
        // Wieder "sicher" (schneller Sieg / dritter Sieg), obwohl er abgestuft war: das Bild ist schon da.
        if (ergebnisSieg && e.liste() == ArenaListenRegel.Liste.SICHER && aufstufen(g.name())) return;
        vorlageDritterSieg = e.dritterSieg();
        vorlageDreiOhneSieg = e.dreiOhneSieg();
        vorlageFuer = g;
        vorlageArt = art;
        vorlageDauer = (int) ergebnisDauer;
        vorlageRunde = rundeZuletzt;
        vorlageSeit = System.currentTimeMillis();
    }

    // --- Aufnehmen auf Zuruf (Autor, 02.10.) ----------------------------------------------
    /**
     * Autor nennt einen Spieler, der gerade nicht in der Pyramide steht ("Flimsy in ar_machbar"):
     * Zeile "Name | machbar" (oder sicher / meiden) in arena-aufnehmen.txt. Sobald der Name auf einem
     * Schild gelesen wird, schneidet der Bot ihn aus wie ein Namensbild und streicht die Zeile.
     */
    private static final java.nio.file.Path AUFNEHMEN_DATEI = java.nio.file.Path.of("arena-aufnehmen.txt");
    private java.util.List<String[]> aufnehmenListe;
    private long aufnehmenGelesenUm;

    private java.util.List<String[]> aufnehmenLaden() {
        long jetzt = System.currentTimeMillis();
        if (aufnehmenListe != null && jetzt - aufnehmenGelesenUm < 10_000) return aufnehmenListe;
        java.util.List<String[]> l = new ArrayList<>();
        try {
            if (java.nio.file.Files.exists(AUFNEHMEN_DATEI)) {
                for (String z : java.nio.file.Files.readAllLines(AUFNEHMEN_DATEI, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (z.isBlank() || z.strip().startsWith("#") || !z.contains("|")) continue;
                    String[] t = z.split("\\|", 2);
                    l.add(new String[]{t[0].strip(), t[1].strip().toLowerCase()});
                }
            }
        } catch (Exception e) {
            Log.debug("Arena", "arena-aufnehmen.txt nicht lesbar: " + e.getMessage());
        }
        aufnehmenListe = l;
        aufnehmenGelesenUm = jetzt;
        return l;
    }

    /** @return true, wenn ein neues Bild gespeichert wurde */
    private boolean aufZurufAufnehmen(BufferedImage bild, BotContext ctx, Matcher.Hit reward, String name) {
        if (bild == null) return false;
        java.util.List<String[]> liste = aufnehmenLaden();
        if (liste.isEmpty()) return false;
        for (String[] e : liste) {
            if (!de.hustlebot.core.NamensVergleich.gleicherName(e[0], name)) continue;
            // Autor 19.10.: auch "niemals" (Capture-Programm "Arena: type names") und die englischen Woerter.
            String art = e[1].startsWith("sicher") || e[1].startsWith("safe") ? T_SICHER_PREFIX
                    : e[1].startsWith("meiden") || e[1].startsWith("ungern") || e[1].startsWith("avoid") ? T_UNGERN_PREFIX
                    : e[1].startsWith("machbar") || e[1].startsWith("doable") ? T_MACHBAR_PREFIX
                    : e[1].startsWith("niemals") || e[1].startsWith("never") || e[1].startsWith("block") ? T_BLOCK_PREFIX : null;
            if (art == null) return false;
            int oben = reward.y() - SCHILD_HOEHE;
            java.awt.Rectangle kasten = textKasten(bild, reward.centerX() - (int) (ctx.width() * 0.046), oben + 12,
                    reward.centerX() + (int) (ctx.width() * 0.070), oben + 33);
            if (kasten == null) return false;
            String kurz = e[0].replaceAll("[^\\p{L}\\p{N}]", "");
            if (kurz.isEmpty()) kurz = "gegner";
            // Autor 21.10.: in denselben Ordner wie das Capture-Programm, und jeder Spieler nur einmal -- die Bilder, unter
            // denen er schon steht, kommen nach templates-aussortiert (seine neue Wahl gilt; geloescht wird nichts).
            java.nio.file.Path ziel = de.hustlebot.core.ArenaVorlagenDoppelt.ordnerFuer(ctx.matcher().templateRoot(), art)
                    .resolve(art + kurz + ".png");
            for (var t : de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(e[0], ctx.matcher().templateRoot())) {
                try {
                    java.nio.file.Path weg = de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(de.hustlebot.core.Ordner.basis(), t.datei());
                    java.nio.file.Files.createDirectories(weg.getParent());
                    java.nio.file.Files.move(t.datei(), weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Log.info("Arena", Sprache.t(e[0] + " was already on a list (" + t.datei().getFileName() + ") -- moved to the new list.",
                            e[0] + " stand schon auf einer Liste (" + t.datei().getFileName() + ") -- kommt in die neue Liste."));
                } catch (Exception ex) {
                    Log.debug("Arena", "Altes Listen-Bild nicht aussortiert: " + ex.getMessage());
                }
            }
            try {
                java.nio.file.Files.createDirectories(ziel.getParent());
                javax.imageio.ImageIO.write(bild.getSubimage(kasten.x, kasten.y, kasten.width, kasten.height), "png", ziel.toFile());
                // Zeile streichen
                java.util.List<String> rest = new ArrayList<>();
                for (String z : java.nio.file.Files.readAllLines(AUFNEHMEN_DATEI, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (z.contains("|") && de.hustlebot.core.NamensVergleich.gleicherName(z.split("\\|", 2)[0].strip(), e[0])) continue;
                    rest.add(z);
                }
                java.nio.file.Files.write(AUFNEHMEN_DATEI, rest, java.nio.charset.StandardCharsets.UTF_8);
                aufnehmenListe = null;
                Log.info("Arena", Sprache.t("Recorded on request: " + ziel.getFileName() + " -- " + name + " is now on \""
                        + e[1] + "\".",
                        "Auf Zuruf aufgenommen: " + ziel.getFileName() + " -- " + name + " steht ab jetzt auf \""
                        + e[1] + "\"."));
                return true;
            } catch (Exception ex) {
                Log.warn("Arena", Sprache.t("Name image on request not saved: ", "Namensbild auf Zuruf nicht gespeichert: ") + ex.getMessage());
                return false;
            }
        }
        return false;
    }

    /** Schneidet den Namen aus dem Schild der Pyramide und legt ihn als Listen-Bild ab. */
    private void namensbildSpeichern(Mat screen, BotContext ctx) {
        Schild s = vorlageFuer;
        boolean aufgeben = rundeZuletzt != vorlageRunde || System.currentTimeMillis() - vorlageSeit > 20_000;
        BufferedImage bild = ctx.frame();
        // Autor 28.10.: das vor dem Angriff gemerkte Namensschild -- dann braucht es die Pyramide nicht mehr.
        BufferedImage vorher = vorAngriffBild(s);
        if (vorher != null || (!aufgeben && bild != null && (ctx.visible(screen, T_SELECT) || ctx.visible(screen, T_NEXT_STAGE)))) {
            int oben = s.y() - SCHILD_HOEHE;
            java.awt.Rectangle kasten = vorher != null || bild == null ? null : textKasten(bild,
                    s.x() - (int) (ctx.width() * 0.046), oben + 12,
                    s.x() + (int) (ctx.width() * 0.070), oben + 33);
            if (vorher != null || kasten != null) {
                String kurz = s.name().replaceAll("[^\\p{L}\\p{N}]", "");   // auch kyrillisch ("Сволочь")
                if (kurz.isEmpty()) kurz = "gegner";
                String stempel = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmm"));
                // Autor 21.10.: in denselben Ordner wie das Capture-Programm (templates\arena\gegner\...).
                java.nio.file.Path ziel = de.hustlebot.core.ArenaVorlagenDoppelt.ordnerFuer(ctx.matcher().templateRoot(), vorlageArt)
                        .resolve(vorlageArt + kurz + "_" + stempel + ".png");
                try {
                    BufferedImage ausschnitt = vorher != null ? vorher
                            : bild.getSubimage(kasten.x, kasten.y, kasten.width, kasten.height);
                    // Autor, 11.10.: kein Name zweimal in einer Liste. Steht er schon irgendwo: in derselben Liste ->
                    // nicht noch einmal; in der eigenen Liste des Spielers (templates\) -> seine Entscheidung gilt,
                    // nichts aufnehmen; nur ein selbst gelerntes Bild (gelernt\) in einer anderen Liste wird ersetzt
                    // (nach templates-aussortiert, geloescht wird nichts).
                    // Autor 21.10.: auch nach dem NAMEN suchen -- das Bild allein liess Doppelte durch ("Denis" zweimal in sicher).
                    var schon = de.hustlebot.core.ArenaVorlagenDoppelt.finde(ausschnitt, ctx.matcher().templateRoot(), null);
                    if (schon.isEmpty()) {
                        var nachName = de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(s.name(), ctx.matcher().templateRoot());
                        // eigene Aufnahme des Spielers zuerst -- seine Entscheidung gilt
                        schon = nachName.stream().filter(t -> !de.hustlebot.core.ArenaVorlagenDoppelt.vomBot(t.datei().getFileName().toString()))
                                .findFirst().or(() -> nachName.stream().findFirst());
                        // weitere Bot-Bilder desselben Spielers in anderen Listen: weg damit (nicht geloescht)
                        for (var t : nachName) {
                            if (schon.isPresent() && t.datei().equals(schon.get().datei())) continue;
                            if (!de.hustlebot.core.ArenaVorlagenDoppelt.vomBot(t.datei().getFileName().toString())) continue;
                            java.nio.file.Path weg = de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(de.hustlebot.core.Ordner.basis(), t.datei());
                            java.nio.file.Files.createDirectories(weg.getParent());
                            java.nio.file.Files.move(t.datei(), weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    if (schon.isPresent()) {
                        java.nio.file.Path alt = schon.get().datei();
                        // Selbst gelernt = Bot-Aufnahme (Endung _MMdd-HHmm) oder noch im alten Ordner gelernt\.
                        boolean gelernt = de.hustlebot.core.ArenaVorlagenDoppelt.vomBot(alt.getFileName().toString())
                                || alt.toAbsolutePath().normalize().startsWith(Matcher.GELERNT.toAbsolutePath().normalize());
                        if (schon.get().liste().equals(vorlageArt) || !gelernt) {
                            Log.info("Arena", Sprache.t("Name image for " + s.wer() + " not recorded: already in "
                                    + alt.getFileName() + (gelernt ? "" : " (your list applies)") + ".",
                                    "Namensbild fuer " + s.wer() + " nicht aufgenommen: steht schon in "
                                    + alt.getFileName() + (gelernt ? "" : " (deine Liste gilt)") + "."));
                            vorlageFuer = null;
                            return;
                        }
                        java.nio.file.Path weg = de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(de.hustlebot.core.Ordner.basis(), alt);
                        java.nio.file.Files.createDirectories(weg.getParent());
                        java.nio.file.Files.move(alt, weg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        Log.info("Arena", Sprache.t("Old name image " + alt.getFileName() + " moved to templates-aussortiert (new list).",
                                "Altes Namensbild " + alt.getFileName() + " nach templates-aussortiert (neue Liste)."));
                    }
                    java.nio.file.Files.createDirectories(ziel.getParent());
                    javax.imageio.ImageIO.write(ausschnitt, "png", ziel.toFile());
                    Log.info("Arena", Sprache.t("Name image saved: " + ziel.getFileName() + " -- " + s.wer()
                            + (vorlageArt.equals(T_PAUSE_PREFIX) ? ", defeat, from now on \"pause\" (not attacked for " + PAUSE_TAGE + " days, the next defeat means never)."
                               : vorlageArt.equals(T_FAST_PREFIX) ? ", victory, from now on \"almost safe\" (-" + FAST_PUNKTE + "), the next victory means safe."
                               : vorlageDreiOhneSieg ? ", 3 or more defeats and no win, from now on \"never\"."
                               : vorlageArt.equals(T_BLOCK_PREFIX) ? ", defeat in " + vorlageDauer + " s, from now on \"never\" (clearly lost)."
                               : vorlageArt.equals(T_UNGERN_PREFIX) ? ", defeat" + (vorlageDauer > 0 ? " in " + vorlageDauer + " s" : "")
                                 + ", from now on \"avoid\" (+15)."
                               : !ergebnisSieg && vorlageArt.equals(T_MACHBAR_PREFIX) ? ", defeat after " + vorlageDauer + " s, from now on \"doable\" (-15)."
                               : vorlageDritterSieg ? ", third victory, from now on \"safe\" (-40)."
                               : ", victory" + (vorlageDauer > 0 ? " in " + vorlageDauer + " s" : "") + ", from now on "
                                 + (vorlageArt.equals(T_SICHER_PREFIX) ? "\"safe\" (-40)." : "\"doable\" (-15).")),
                            "Namensbild gespeichert: " + ziel.getFileName() + " -- " + s.wer()
                            + (vorlageArt.equals(T_PAUSE_PREFIX) ? ", Niederlage, ab jetzt \"Pause\" (" + PAUSE_TAGE + " Tage nicht angreifen, die naechste Niederlage heisst niemals)."
                               : vorlageArt.equals(T_FAST_PREFIX) ? ", Sieg, ab jetzt \"fast sicher\" (-" + FAST_PUNKTE + "), der naechste Sieg heisst sicher."
                               : vorlageDreiOhneSieg ? ", 3 oder mehr Niederlagen ohne Sieg, ab jetzt \"niemals\"."
                               : vorlageArt.equals(T_BLOCK_PREFIX) ? ", Niederlage in " + vorlageDauer + " s, ab jetzt \"niemals\" (sicher verloren)."
                               : vorlageArt.equals(T_UNGERN_PREFIX) ? ", Niederlage" + (vorlageDauer > 0 ? " in " + vorlageDauer + " s" : "")
                                 + ", ab jetzt \"meiden\" (+15)."
                               : !ergebnisSieg && vorlageArt.equals(T_MACHBAR_PREFIX) ? ", Niederlage nach " + vorlageDauer + " s, ab jetzt \"machbar\" (-15)."
                               : vorlageDritterSieg ? ", dritter Sieg, ab jetzt \"sicher\" (-40)."
                               : ", Sieg" + (vorlageDauer > 0 ? " in " + vorlageDauer + " s" : "") + ", ab jetzt "
                                 + (vorlageArt.equals(T_SICHER_PREFIX) ? "\"sicher\" (-40)." : "\"machbar\" (-15)."))));
                    ctx.matcher().loadAll();
                } catch (Exception e) {
                    Log.warn("Arena", Sprache.t("Name image could not be saved: ", "Namensbild konnte nicht gespeichert werden: ") + e.getMessage());
                }
                vorlageFuer = null;
                return;
            }
            if (System.currentTimeMillis() - vorlageSeit < 5000) return;   // naechstes Bild probieren
            aufgeben = true;
        }
        if (aufgeben) {
            Log.info("Arena", Sprache.t("Name image for " + s.wer() + " not recorded (shield no longer readable at the same spot).",
                    "Namensbild fuer " + s.wer() + " nicht aufgenommen (Schild nicht mehr an derselben Stelle lesbar)."));
            vorlageFuer = null;
        }
    }

    /**
     * Der Kasten um die weisse Schrift mit dunklem Rand im Bereich (x0,y0)-(x1,y1), 2 Punkte
     * Rand -- so eng wie Autors eigene Aufnahmen (ar_block_1: 127x20). null, wenn dort
     * nichts aussieht wie ein Name.
     */
    static java.awt.Rectangle textKasten(BufferedImage bild, int x0, int y0, int x1, int y1) {
        x0 = Math.max(1, x0); y0 = Math.max(1, y0);
        x1 = Math.min(bild.getWidth() - 2, x1); y1 = Math.min(bild.getHeight() - 2, y1);
        // Links im Schild sitzt das bunte Clan-Wappen: Spalten mit vielen kraeftigen Farben
        // (im linken Drittel) gehoeren nicht zum Namen -- rechts davon anfangen.
        int start = x0;
        for (int x = x0; x < x0 + (x1 - x0) / 3; x++) {
            int bunt = 0;
            for (int y = y0; y < y1; y++) {
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                // kraeftige Farbe = Clan-Wappen. (Mittleres Grau taugt nicht als Merkmal: die
                // Kanten der Schrift sind auch grau -- "GiaoGiao" verlor so seine ersten Buchstaben.)
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) >= 80) bunt++;
            }
            if (bunt * 4 >= (y1 - y0)) start = x + 3;
        }
        x0 = start;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, treffer = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                if (Math.min(r, Math.min(g, b)) < 185) continue;   // nicht weiss
                boolean rand = false;
                for (int dy = -1; dy <= 1 && !rand; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int q = bild.getRGB(x + dx, y + dy);
                        if (Math.max((q >> 16) & 0xff, Math.max((q >> 8) & 0xff, q & 0xff)) <= 100) { rand = true; break; }
                    }
                }
                if (!rand) continue;
                treffer++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (treffer < 25 || maxX < 0) return null;
        int w = maxX - minX + 1, h = maxY - minY + 1;
        if (w < 18 || h < 9 || h > 30) return null;
        int rx = Math.max(0, minX - 2), ry = Math.max(0, minY - 2);
        return new java.awt.Rectangle(rx, ry, Math.min(bild.getWidth() - rx, w + 4), Math.min(bild.getHeight() - ry, h + 4));
    }

    /**
     * 30.10., 01:26:56 "Victory against xMeowth, 150, 7 s", 01:27:05 "Victory, 150, 7 s": dasselbe Fenster stand noch, der Bot zaehlte
     * 150 Punkte doppelt (Abschluss 891 statt 741) und rechnete damit im Turnier weiter. Ohne Gegner, gleiche Zahlen, kurz danach:
     * kein neuer Kampf, zwischen zwei Kaempfen liegt immer eine neue Runde.
     */
    static boolean doppeltesErgebnis(boolean ohneGegner, long punkte, long dauer, long letztePunkte, long letzteDauer, long seitLetztemMs) {
        return ohneGegner && punkte == letztePunkte && dauer == letzteDauer && seitLetztemMs < 30_000;
    }

    private long letztesErgebnisUm, letztePunkte = -1, letzteDauer = -1;

    private void offenesErgebnisEintragen() {
        if (ergebnisSeit == 0) return;
        ergebnisSeit = 0;
        angriffOhneErgebnis = null;   // es gab einen Kampf -- der Gegner ist wirklich verbraucht
        ergebnisGemeldet = true;
        long jetztErgebnis = System.currentTimeMillis();
        if (doppeltesErgebnis(imKampfGegen == null, ergebnisPunkte, ergebnisDauer, letztePunkte, letzteDauer, jetztErgebnis - letztesErgebnisUm)) {
            Log.debug("Arena", "Ergebnisfenster noch einmal gelesen -- derselbe Kampf, zaehlt nicht doppelt.");
            return;
        }
        letztesErgebnisUm = jetztErgebnis;
        letztePunkte = ergebnisPunkte;
        letzteDauer = ergebnisDauer;
        kaempfeSeitTurnierEnde++;
        // Fuer !stats: Niederlagen bringen 0 Punkte, Siege zaehlen nur mit
        // lesbarer Zahl -- wie im Notizblock, damit der Schnitt nicht kippt.
        if (ergebnisSieg) {
            kaempfeSieg++;
            if (ergebnisPunkte > 0) { punkteSumme += ergebnisPunkte; punkteKaempfe++; }
            if (ergebnisPunkte > 0) turnierEndpunkte += ergebnisPunkte;
            else turnierPunkteFehlen = true;
        } else {
            kaempfeNiederlage++;
            punkteKaempfe++;
        }
        Log.info("Arena", Sprache.t((ergebnisSieg ? "Victory" : "Defeat")
                + (imKampfGegen != null ? " against " + imKampfGegen.wer() : "")
                + (ergebnisPunkte >= 0 ? ", " + ergebnisPunkte + " tournament points" : "")
                + (ergebnisDauer  >= 0 ? ", fight time " + ergebnisDauer + " s" : "")
                + (ergebnisDauer > 10 ? "  (over ten seconds -- hence fewer points)" : ""),
                (ergebnisSieg ? "Sieg" : "Niederlage")
                + (imKampfGegen != null ? " gegen " + imKampfGegen.wer() : "")
                + (ergebnisPunkte >= 0 ? ", " + ergebnisPunkte + " Turnierpunkte" : "")
                + (ergebnisDauer  >= 0 ? ", Kampfdauer " + ergebnisDauer + " s" : "")
                + (ergebnisDauer > 10 ? "  (ueber zehn Sekunden -- deshalb weniger Punkte)" : "")));
        fotoSerie(ergebnisSieg ? "sieg" : "niederlage");
        if (imKampfGegen != null) {
            // Schaetzung und Ergebnis nebeneinander -- daraus wird die Chance-Kurve nachgestellt.
            ArenaLernen.eintragen(imKampfGegen.name(), imKampfGegen.clan(),
                    ergebnisStaerke > 0 ? ergebnisStaerke : bekannteStaerke(imKampfGegen), meineStaerke,
                    kampfPunkte, kampfReferenz, kampfChance, kampfTop3,
                    ergebnisSieg, ergebnisDauer, ergebnisPunkte, rundeZuletzt,
                    imKampfGegen.punkte(), kampfRest, kampfPlatz, ArenaAussehen.kraftWerte(namensSchluessel(imKampfGegen.name())));
        }
        kampfRest = -1;
        kampfPlatz = -1;
        kampfChance = -1;
        kampfPunkte = null;
        kampfReferenz = Integer.MIN_VALUE;
        if (imKampfGegen == null) return;

        // Autor 22.10. abends: Sperre nach Kraft, Name, Clan und Sets -- auch wenn der Name jedes Mal anders gelesen wird.
        // Autor 24.10. abends: mit seinem Aussehen aus dem Vergleichsfenster (Einheiten-Portraets, Clan-Banner).
        // Autor 24.10.: mit dem Fingerabdruck seines Namensbildes (Glyphenschrift) -- jede neue Variante wird gemerkt.
        Glyphenschrift.Ergebnis seinNamensbild = namensbildZu(imKampfGegen.name());
        de.hustlebot.core.ArenaSperre.ergebnis(imKampfGegen.name(), imKampfGegen.clan(),
                ergebnisStaerke > 0 ? ergebnisStaerke : bekannteStaerke(imKampfGegen), setsVon(imKampfGegen),
                einheitenVon.getOrDefault(namensSchluessel(imKampfGegen.name()), ""), kampfAussehen,
                seinNamensbild == null ? null : seinNamensbild.fingerabdruck(), ergebnisSieg);
        kampfAussehen = null;
        // TS8 (Nutzer 25.11.): Bilanz mit der eigenen Kraft zum Kampf -- steigt sie um 5 %, wird der Gegner neu bewertet.
        if (ArenaEinfach.aktiv() && !imKampfGegen.name().isBlank() && !unleserlich(imKampfGegen.name())) {
            ArenaEinfach.eintragen(imKampfGegen.name(), ergebnisSieg, meineStaerke);
        }
        // Genau EINMAL je Kampf: ohne das Zuruecksetzen von imKampfGegen
        // wuerde derselbe Kampf ein Dutzend Mal in der Bilanz landen.
        if (istBot(imKampfGegen.clan()) || imKampfGegen.botClan()) {
            Log.debug("Arena", imKampfGegen.wer() + " ist in keinem Clan -- "
                    + "kommt nicht in den Notizblock.");
        } else {
            selbstKaempfeUebernehmen(imKampfGegen.name(), imKampfGegen.clan(), ergebnisStaerke);
            // Mit Kampfdauer (24.09.) und Turnierpunkten (25.09.).
            buch.ergebnis(imKampfGegen.name(), imKampfGegen.clan(), ergebnisSieg,
                    ergebnisStaerke, (int) ergebnisDauer, ergebnisPunkte, seinNamensbild);
        }
        imKampfGegen = null;
        listeSchreiben();
        letzteGegnerstaerke = -1;
    }

    // ==================================================================
    //  8. Turnierende
    // ==================================================================

    /**
     * Abschluss: Platz merken, Belohnung holen, Durchlauf zaehlen.
     *
     * Hier stand zuerst die Rundennummer als Merker fuer "schon
     * eingetragen". Das war aus zwei Gruenden falsch. Erstens ist ein Feld
     * mit zwei Bedeutungen eine Falle fuer den naechsten, der es liest.
     * Zweitens -- und schlimmer -- haette der Bot ewig am
     * Abschlussbildschirm gestanden, wenn die Texterkennung den Platz
     * einmal nicht liest: der Merker waere nie gesetzt worden, der
     * Durchlauf nie gezaehlt, und "Claim reward" ist nach dem ersten Klick
     * weg. Genau diese Sorte Schleife hat im Verlies eine Nacht gekostet.
     *
     * Jetzt gibt es eigene Merker und eine Frist: nach zwanzig Bildern
     * ohne lesbaren Platz wird der Durchlauf trotzdem gezaehlt, nur eben
     * ohne Platzierung in der Statistik. Ein fehlender Eintrag ist
     * aergerlich, ein stehender Bot ist schlimmer.
     */
    private Outcome turnierEnde(Mat screen, BotContext ctx, RunTracker runs) {
        unbekannteBilder = 0;
        endeBilder++;
        imAbschluss = true;   // 11.10.: fuer draussen() -- der Abschlussbildschirm steht noch

        // Schon abgehakt? Dann nur noch warten, bis das Spiel den Bildschirm
        // wechselt.
        //
        // Hier lag ein boeser Fehler. Frueher rief dieser Zweig am Ende
        // neuesTurnier(), und das setzte platzEingetragen, belohnungGeholt
        // und endeBilder sofort wieder auf Anfang -- ohne dass irgendetwas
        // vom Abschlussbildschirm wegnavigiert haette. Beim naechsten Bild
        // war "Tournament ended" also immer noch da, der Platz wurde ein
        // zweites Mal eingetragen, und zwanzig Bilder spaeter zaehlte ein
        // zweiter Durchlauf. Aus einem Turnier wurden in der Statistik
        // zwei, drei, vier. Jetzt wird genau einmal gezaehlt, und
        // neuesTurnier() macht erst der naechste Klick auf "Participate".
        if (turnierGezaehlt) {
            // Nicht nur warten. Bleibt "Tournament ended" stehen -- weil der
            // Klick auf Claim nicht ankam, weil die Truhe darueber liegt --,
            // stuende der Bot hier sonst bis zum Totmann-Schalter und die
            // ganze Sitzung waere beendet. Alle paar Sekunden ein Versuch,
            // hier wegzukommen.
            if (endeBilder % 20 == 0) {
                Log.info("Arena", Sprache.t("The final screen is still there. Trying "
                        + "to leave it.",
                        "Der Abschlussbildschirm steht noch. Es wird versucht, "
                        + "ihn zu verlassen."));
                // 30.10. (Bericht #4A2J): liegt "Victory!" noch darueber, trifft Claim ins Leere und "gelingt"
                // trotzdem jedes Mal -- 55 Klicks bis zum Haenger-Alarm. Erst das Fenster mit dem "Home" unten zu.
                Optional<Matcher.Hit> home = homeUnten(ctx, screen);
                if (home.isPresent()) {
                    ctx.klick(home.get(), Sprache.t("Close the result window over the final screen (Home)",
                            "Ergebnisfenster ueber dem Abschluss schliessen (Home)"));
                    ctx.sleepUntilChanged(20, 1500);
                    return Outcome.ACTED;
                }
                // 24.10. (Bericht #NRM3): endet das Turnier beim Durchschauen, liegt das
                // Vergleichsfenster ueber "Claim reward" -- sein X (ar_dweller_close) erst zu.
                if (ctx.clickIfVisible(screen, T_CLAIM, Sprache.t("Claim reward (second attempt)", "Belohnung abholen (zweiter Versuch)"))
                 || ctx.clickIfVisibleIfKnown(screen, "ar_dweller_close", Sprache.t("Close the comparison window over the final screen", "Vergleichsfenster ueber dem Abschluss schliessen"))
                 || ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the final screen", "Abschluss schliessen"))) {
                    return Outcome.ACTED;
                }
            }
            return Outcome.WAITING;
        }

        if (!platzEingetragen) {
            Optional<Matcher.Hit> h = ctx.find(screen, T_POSITION);
            if (h.isPresent()) {
                Matcher.Hit lh = h.get();
                BufferedImage endbild = ctx.frame();
                OptionalLong p = ArenaLeser.zahlNeben(endbild, lh, (int) (ctx.width() * 0.06));
                // Einstellig ("3") liest die Erkennung allein nicht -- die ganze Zeile
                // "Your position: 3" schon (01.10.: Platz nie gelesen, kein Bericht).
                if (p.isEmpty() || p.getAsLong() < 1 || p.getAsLong() > 10) {
                    String zeile = ArenaLeser.liesTextGross(endbild, lh.x() - 4, lh.y() - 6,
                            lh.width() + (int) (ctx.width() * 0.07), lh.height() + 12);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)pos\\w*\\D{0,4}(\\d{1,2})").matcher(zeile);
                    p = m.find() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
                }
                if (p.isEmpty() || p.getAsLong() < 1 || p.getAsLong() > 10) {
                    p = ArenaLeser.zahlHinterWort(endbild, lh.x() + lh.width(), lh.y() - 6,
                            (int) (ctx.width() * 0.07), lh.height() + 12, "Platz");
                }
                if (p.isPresent() && p.getAsLong() >= 1 && p.getAsLong() <= 10) {
                    turnierBeendet((int) p.getAsLong());
                    platzEingetragen = true;
                }
            }
            // Nicht gleich "Claim" druecken: dem Platz ein paar Bilder Zeit geben.
            if (!platzEingetragen && endeBilder < 8) {
                return Outcome.WAITING;
            }
            if (!platzEingetragen && endeBilder >= 8) {
                warnung(Sprache.t("The place could not be read. The tournament is "
                        + "still counted as played, just without a placing. "
                        + "Check template ar_position.",
                        "Der Platz liess sich nicht ablesen. Das Turnier wird "
                        + "trotzdem als gespielt gezaehlt, nur ohne Platzierung. "
                        + "Vorlage ar_position pruefen."));
                arenaBericht("?");
                platzEingetragen = true;
            }
        }

        // 28.10. (Berichte #LR92/#LHD9): liegt das "Victory!"-Fenster des letzten Kampfs ueber dem
        // Abschluss, kommt Claim nie an -- 4300 Klicks in 12 Minuten. Nach 5 wirkungslosen Klicks
        // erst das Fenster mit dem "Home" unten schliessen.
        if (claimKlicks >= 5 && ctx.visible(screen, T_CLAIM)) {
            Optional<Matcher.Hit> home = homeUnten(ctx, screen);
            if (home.isPresent()) {
                ctx.klick(home.get(), Sprache.t("Close the result window over the final screen (Home)",
                        "Ergebnisfenster ueber dem Abschluss schliessen (Home)"));
                claimKlicks = 0;
                ctx.sleepUntilChanged(20, 1500);
                return Outcome.ACTED;
            }
        }

        // Belohnung holen. Danach ist der Knopf weg -- daran erkennt der
        // Bot, dass er hier fertig ist.
        if (ctx.visible(screen, T_CLAIM)) claimKlicks++;
        if (ctx.clickIfVisible(screen, T_CLAIM, Sprache.t("Claim the reward (Claim reward)", "Belohnung abholen (Claim reward)"))) {
            belohnungGeholt = true;
            return Outcome.ACTED;
        }

        if (!belohnungGeholt && endeBilder < 20) {
            return Outcome.WAITING;     // Knopf kommt vielleicht noch
        }

        // Aufgeschobene Notizen sichern, bevor das Turnier abgehakt wird --
        // sonst waeren die Erkundungsdaten weg, wenn das Turnier mitten in
        // der Erkundung endet.
        buch.jetztSpeichern();

        runs.completeRun();
        turnierGezaehlt = true;
        tempoRundeFertig();
        imTurnier = false;
        // Nachspiel des Motors (11.10.): den Abschluss noch schliessen, dann heim in die Burg
        // (Pruefung 11.10.: steht das Spiel danach schon in der Burg, ist es dort fertig --
        // nicht erst noch einmal zur Arena-Insel und ins Turnierfenster).
        if (nurZuEndeSpielen) nachspielHeimweg = true;
        if (runs.isDone() && !nurZuEndeSpielen) {
            Log.info("Arena", Sprache.t("All tournaments played.", "Alle Turniere gespielt."));
            return Outcome.FINISHED;
        }
        // neuesTurnier() macht erst das naechste "Participate". Bis dahin
        // bleibt turnierGezaehlt stehen und schuetzt vor Doppelzaehlung.
        Log.info("Arena", Sprache.t("Tournament done. Going back to the tournament window.", "Turnier fertig. Es geht zurueck ins Turnierfenster."));
        return Outcome.ACTED;
    }

    /**
     * Autor 24.10.: "pro abgeschlossene Arena um 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Lernstand in arena-tempo.txt. Runde = ein abgeschlossenes Turnier
     * (gezaehlt wird beim Abschluss, auch wenn der Platz nicht lesbar war); Fehler = jede Warnung der Arena waehrend des
     * Turniers (nicht die Meldungen, dass eine Hilfsdatei nicht geschrieben werden konnte).
     * STAND: ArenaMode selbst hat keine festen Pausen (nur Bildwechsel-Zyklen, Warten auf das Spiel und Lese-Drosseln) --
     * Pausen mit Faktor gibt es hier noch nicht: spaeter {@code ctx.sleep(tempo.ms(...))} bzw. {@code tempo.ms(...)}.
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("arena-tempo.txt", "Arena");
    private int runFehler;
    /** Abgeschlossene Turniere seit dem Start (fuer die Tempo-Zeile alle 10 Turniere; nicht in reset()). */
    private int tempoRunden;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn("Arena", text);
    }

    private void tempoRundeFertig() {
        tempoRunden++;
        tempo.rundeFertig(runFehler == 0);
        if (tempoRunden % 10 == 0) {
            Log.info("Arena", Sprache.t("Speed after " + tempoRunden + " tournaments: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + tempoRunden + " Turnieren: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    /** Die kurze Discord-Zeile nach einem Turnier (Autor, 01.10.), abschaltbar mit !arenabericht aus. */
    private void arenaBericht(String platz) {
        if (!BERICHT.lesen()) return;
        long jetzt = System.currentTimeMillis();
        String dauer = turnierPyramideUm > 0 ? minuten(jetzt - turnierPyramideUm) : "?";
        String warten = turnierWartezeitMs > 0 ? minuten(turnierWartezeitMs) : "?";
        Log.hinweis(Sprache.t("Arena done", "Arena fertig"), Sprache.t("Place **", "Platz **") + platz
                + Sprache.t("** · points **", "** · Punkte **") + (turnierPunkteFehlen ? Sprache.t("at least ", "mind. ") : "")
                + turnierEndpunkte + "** · Arena " + dauer + Sprache.t(" · waiting ", " · Wartezeit ") + warten
                + (ticketsZuletzt >= 0 ? " · Tickets " + ticketsZuletzt : ""));
    }

    /** Kaempfe seit dem letzten Turnierende, und wann das war. */
    private int kaempfeSeitTurnierEnde;
    private long turnierEndeUm;

    /**
     * 30.10. 04:17: "Platz 2" nach dem Notausstieg war dasselbe Turnier wie 04:12 und verfaelschte die Statistik. Kein eigener Kampf seit
     * dem letzten Ende und weniger als 6 Minuten danach (ein Turnier dauert laenger): kein neues Turnier.
     */
    static boolean turnierDoppelt(int kaempfeSeitEnde, long seitEndeMs, boolean schonEinEnde) {
        return schonEinEnde && kaempfeSeitEnde == 0 && seitEndeMs < 6 * 60_000L;
    }

    private void turnierBeendet(int platz) {
        long jetzt = System.currentTimeMillis();
        boolean doppelt = turnierDoppelt(kaempfeSeitTurnierEnde, jetzt - turnierEndeUm, turnierEndeUm > 0);
        kaempfeSeitTurnierEnde = 0;
        turnierEndeUm = jetzt;
        if (doppelt) {
            Log.info("Arena", Sprache.t("Final screen of the same tournament (no fight since the last end) -- not counted again.",
                    "Abschlussbild desselben Turniers (kein Kampf seit dem letzten Ende) -- zaehlt nicht noch einmal."));
            return;
        }
        leiterEintragen(platz);
        arenaBericht(String.valueOf(platz));
        de.hustlebot.core.ArenaPlaetze.eintragen(platz);   // Autor 21.10.: Statistik Top 1/2/3 je Tag
        platzierungen[platz]++;
        turniere++;
        Log.info("Arena", Sprache.t("Tournament finished in place " + platz
                + ". " + turniere + " tournaments so far, " + siege() + " of them in the top three.",
                "Turnier beendet auf Platz " + platz
                + ". Bisher " + turniere + " Turniere, davon " + siege() + " in den ersten drei."));
        fotoSerie("ende-platz" + platz);
    }

    // ---- Rang-Leiter-Test (Autor 22.10., Fable-Idee 15) ---------------------------------------------------------------
    // "Wenn er 224 bekommt, erhoeht sich die Chance, dass er nochmal einen 225 bekommt, ziemlich stark. Der Bot soll aber
    // auch nicht zu wenig bekommen. So 215 bis 224 waere gut. Koennte er auch in Stage 2 schon machen." Spaeter: "In Stage
    // 2 bis 4 am besten immer Gegner, die 215 bis 224 geben -- in der fuenften Stage nicht, nur 225 oder 150." Stage 2-4:
    // ein 225er mit mindestens 88 % und erwarteten 215-224 Punkten (laengerer Kampf) rechnet so, als bleibe der Bot danach
    // unter den anderen 225er-Siegern. Stage 5 zaehlt wie bisher die erwarteten Punkte. Ergebnis je Turnier in arena-rangleiter.txt.
    static final double LEITER_VON = 215, LEITER_BIS = 224, LEITER_AB_CHANCE = 0.88;
    private static final java.nio.file.Path LEITER_DATEI = java.nio.file.Path.of("arena-rangleiter.txt");
    private boolean leiterTest, leiterAngewendet;

    private void leiterEintragen(int platz) {
        try {
            if (!java.nio.file.Files.exists(LEITER_DATEI)) {
                java.nio.file.Files.writeString(LEITER_DATEI, "# Rang-Leiter-Test (Autor 22.10.): Zeit, Test-Turnier (1/0), Regel gegriffen (1/0), Platz\n",
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            String zeile = de.hustlebot.core.Zeitzone.jetzt().toString().substring(0, 16).replace('T', ' ')
                    + "\t" + (leiterTest ? 1 : 0) + "\t" + (leiterAngewendet ? 1 : 0) + "\t" + platz + "\n";
            java.nio.file.Files.writeString(LEITER_DATEI, zeile, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug("Arena", "arena-rangleiter.txt nicht geschrieben: " + e.getMessage());
        }
    }

    private int siege() {
        return platzierungen[1] + platzierungen[2] + platzierungen[3];
    }

    /** "9:40 min" aus Millisekunden. */
    private static String minuten(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60) + " min";
    }

    // ==================================================================
    //  Statistik
    // ==================================================================

    @Override
    public Map<String, String> extraStats() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(Sprache.t("Tournaments played", "Turniere gespielt"),
                String.valueOf(turniere));
        extra.put(Sprache.t("1st / 2nd / 3rd", "Erster / Zweiter / Dritter"),
                platzierungen[1] + " / " + platzierungen[2] + " / " + platzierungen[3]);
        if (turniere > 0) {
            double quote = 100.0 * siege() / turniere;
            extra.put(Sprache.t("Top 3 rate", "Siegesquote (Top 3)"),
                    String.format("%.0f %%", quote)
                            + " (" + siege() + Sprache.t(" of ", " von ") + turniere + ")");
        }
        // Autor, 25.09.: Kaempfe, Punkte je Kampf und Tickets dazu.
        int kaempfe = kaempfeSieg + kaempfeNiederlage;
        if (kaempfe > 0) {
            extra.put(Sprache.t("Fights won / lost", "Kaempfe gewonnen / verloren"),
                    kaempfeSieg + " / " + kaempfeNiederlage
                            + String.format(" (%.0f %%)", 100.0 * kaempfeSieg / kaempfe));
        }
        if (punkteKaempfe > 0) {
            extra.put(Sprache.t("Tournament points per fight", "Turnierpunkte je Kampf"),
                    String.valueOf(Math.round((double) punkteSumme / punkteKaempfe)));
        }
        extra.put(Sprache.t("Tickets left", "Tickets uebrig"), kasse.mitNahrung()
                ? Sprache.t("playing with apples", "es wird mit Aepfeln gespielt")
                : ticketsZuletzt >= 0
                        ? ticketsZuletzt + Sprache.t(" (as of ", " (Stand ")
                                + java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(ticketsZuletztUm),
                                        java.time.ZoneId.systemDefault()).withNano(0).withSecond(0)
                                + (Sprache.t(")", " Uhr)"))
                        : Sprache.t("not read yet", "noch nicht abgelesen"));
        return extra;
    }

    // ==================================================================
    //  Kleinkram
    // ==================================================================

    /**
     * Ist das ein Spieler ohne Clan?
     *
     * Autor: "der bot muss sich beim notizblock die typen nicht merken die
     * not in clan sind." Das sind nach seiner Erfahrung ohnehin Bots -- sie
     * kommen nie wieder, und jeder von ihnen wuerde den Notizblock um eine
     * Zeile aufblaehen, die niemand je nachschlaegt.
     *
     * Geprueft wird auf den Anfang, nicht auf den ganzen Text: die
     * Texterkennung schneidet gern ab, im Protokoll standen schon
     * "Not in cl" und "Not in c".
     */
    private static boolean istBot(String clan) {
        // 30.09.: die Texterkennung liest "Not in clan" auch als "Not i", "VN0t i",
        // "Ue Not", "V Not i" -- die landeten alle im Notizblock (Autor: nicht merken).
        return de.hustlebot.core.Gegnerbuch.keinClan(clan);
    }

    /**
     * Liest den Namen des Gegners im Vergleichsfenster.
     *
     * Er steht UNTER der Staerkezeile, links -- dort, wo auch "Not in
     * clan" steht. Der Ankerpunkt sind die gekreuzten Schwerter: von dort
     * ein Stueck nach unten und die linke Haelfte des Fensters.
     *
     * Ohne Ankerpunkt wird gar nicht erst geraten. Ein falsch gelesener
     * Name waere schlimmer als keiner: er landete als eigener Gegner im
     * Notizblock und verwaesserte die Bilanz.
     */
    private String[] gegnerNameLesen(BotContext ctx, BufferedImage bild,
                                     Optional<Matcher.Hit> schwerter) {
        if (schwerter.isEmpty() || bild == null) return new String[]{"", ""};
        Matcher.Hit sw = schwerter.get();

        // Name UND Clan lesen, nicht nur den Namen.
        //
        // Vorher wurde nur die erste Zeile genommen und der Clan als leer
        // eingetragen. Der Notizblock wird aber unter Name UND Clan
        // gefuehrt -- die Erkundung legte damit Eintraege an, die beim
        // Nachschlagen nie gefunden wurden, und derselbe Gegner stand
        // zweimal darin.
        // Mit kyrillischem Nachschlag. Namen wie ~ЦИРИ~ hat das deutsche
        // Modell nie lesen koennen -- und genau die stehen bei Autor oben
        // in der Pyramide.
        // Pruefer 29.10.: erst hinter dem Stufen-Abzeichen anfangen (siehe namensAnfangNachAbzeichen); rechts endet der
        // Ausschnitt wie bisher bei 0,46 der Breite.
        int links = (int) (ctx.width() * 0.16), rechts = (int) (ctx.width() * 0.46);
        int nachAbzeichen = namensAnfangNachAbzeichen(bild, sw.y() + sw.height());
        if (nachAbzeichen > links && nachAbzeichen < rechts - ctx.width() * 0.08) links = nachAbzeichen;
        else Log.debug("Arena", "Stufen-Abzeichen neben dem Namen nicht gefunden -- Ausschnitt wie bisher.");
        // Autor 01.11. (Pruefung 3.137): Name und Clan nach LAGE. Die Texterkennung ueber den ganzen Ausschnitt liefert meist nur EINE Zeile (die erste) und
        // gar keine Lage: ist der Name unlesbar, rutscht der Clan auf Platz 1 ("Not in clan", "BO_Myth Gods" als Name), und der Clan fehlt fast immer. Darum
        // je Zeile ihr festes Band lesen (Name rechts vom Stufen-Abzeichen, Clan eine Zeile tiefer rechts vom kleinen Wappen); der ganze Ausschnitt wird nur
        // noch gefragt, wenn das Namensband nichts Lesbares ergibt.
        int unten = sw.y() + sw.height();
        final int nameX = links;
        int breiteName = rechts - nameX;
        String[] baender = fensterBaender(bild, nameX, unten, rechts, ctx.height() / 770.0);
        String nameBand = baender[0], clanBand = baender[1];
        java.util.concurrent.atomic.AtomicReference<List<String>> ganzSpeicher = new java.util.concurrent.atomic.AtomicReference<>(List.of());
        ArenaSchildLage.Ergebnis lage = ArenaSchildLage.teilenFenster(() -> {
            ganzSpeicher.set(ArenaLeser.liesNamenZeilen(bild, nameX, unten, breiteName, (int) (ctx.height() * 0.07)));
            return ganzSpeicher.get();
        }, nameBand, clanBand);
        String name = lage.name(), clan = lage.clan();
        // Pruefung 29.10.: nur EINE Zeile gelesen, und die ist als Clan eines Spielers bekannt -> der Name selbst war
        // unlesbar (chinesisch, Zierschrift); sonst mischte der Notizblock zwei Spieler unter dem Clan-Namen.
        if (!lage.nameAusBand() && ganzSpeicher.get().size() == 1 && !name.isBlank() && buch.nurAlsClanBekannt(name)) {
            Log.debug("Arena", "\"" + name + "\" ist ein Clan, kein Name -- Name gilt als unlesbar.");
            return new String[]{"", name};
        }
        return new String[]{name, clan};
    }

    /** Namensband und Clanband des Vergleichsfensters lesen: {Name oder null, Clan oder null}. unten = Unterkante der gekreuzten Schwerter, f = Hoehe/770. */
    static String[] fensterBaender(BufferedImage bild, int nameX, int unten, int rechts, double f) {
        int clanX = nameX + (int) Math.round(FENSTER_CLAN_EINZUG * f);
        List<String> nameZeilen = ArenaLeser.liesNamenZeilen(bild, nameX, unten + (int) Math.round(FENSTER_NAME_VON * f), rechts - nameX, (int) Math.round(FENSTER_NAME_HOEHE * f));
        String nameBand = nameZeilen.isEmpty() ? null : nameZeilen.get(0).strip();
        if (nameBand != null && (unleserlich(nameBand) || de.hustlebot.core.NamensVergleich.keinName(nameBand))) nameBand = null;
        List<String> clanZeilen = ArenaLeser.liesNamenZeilen(bild, clanX, unten + (int) Math.round(FENSTER_CLAN_VON * f), rechts - clanX, (int) Math.round(FENSTER_CLAN_HOEHE * f));
        String clanBand = clanZeilen.isEmpty() ? null : clanZeilen.get(0).strip();
        if (clanBand == null || de.hustlebot.core.NamensVergleich.form(clanBand).length() < 4) {   // duenne Zeile, die Lesung schwankt mit dem Ausschnitt: einmal etwas anders
            clanZeilen = ArenaLeser.liesNamenZeilen(bild, clanX, unten + (int) Math.round((FENSTER_CLAN_VON + 2) * f), rechts - clanX, (int) Math.round((FENSTER_CLAN_HOEHE + 2) * f));
            if (!clanZeilen.isEmpty() && de.hustlebot.core.NamensVergleich.form(clanZeilen.get(0)).length() > (clanBand == null ? 0 : de.hustlebot.core.NamensVergleich.form(clanBand).length())) clanBand = clanZeilen.get(0).strip();
        }
        if (clanBand != null && (clanBand.isEmpty() || pokalZeile(clanBand))) clanBand = null;
        return new String[]{nameBand, clanBand};
    }

    /** Vergleichsfenster: Namensband und Clanband unter den gekreuzten Schwertern (bei 770 Zeilen Hoehe; gemessen an f_544/f_199/f_177 vom 01.11.). */
    static final int FENSTER_NAME_VON = 8, FENSTER_NAME_HOEHE = 20, FENSTER_CLAN_VON = 28, FENSTER_CLAN_HOEHE = 20, FENSTER_CLAN_EINZUG = 30;

    /**
     * Pruefer 29.10. (3.109-3.112): links neben dem Namen steht im Vergleichsfenster das Stufen-Abzeichen (Burg mit Zahl).
     * Seit PaddleOCR die Namen liest, wurde es mitgelesen ("G4nawa", "49cowboyup", "53HELIOS", "i66Bernd" -- 11 von 23
     * Namen). Gemessen an 20 Fotos vom 15.10. (1159 x 770, Zeile 389-414): Abzeichen 33-35 Bildpunkte breit, danach 9-10
     * frei, dann der Name; mit Pal-Pfote davor steht dort ein 38-39 breiter Block mit 27 frei dahinter. Gesucht wird darum der
     * Block in Abzeichen-Breite mit kurzer Luecke dahinter. Das Fenster sitzt nicht immer gleich (Abzeichen bei 277 oder
     * 282), feste Stellen reichen nicht.
     *
     * @param unterSchwertern Oberkante des Namensausschnitts (unter den gekreuzten Schwertern)
     * @return wo der Ausschnitt beginnen soll (3 Bildpunkte hinter dem Abzeichen), -1 = nicht gefunden
     */
    static int namensAnfangNachAbzeichen(BufferedImage bild, int unterSchwertern) {
        if (bild == null) return -1;
        int w = bild.getWidth(), h = bild.getHeight();
        int y0 = Math.max(0, unterSchwertern + (int) (h * 0.004)), y1 = Math.min(h, unterSchwertern + (int) (h * 0.036));
        int ref = (int) (w * 0.21), bis = Math.min(w, (int) (w * 0.40));   // bei 0,21 ist nur Fenstergrund
        if (y1 - y0 < 5 || bis <= ref) return -1;
        int mindLuecke = Math.max(4, Math.round(w * 0.0035f));
        List<int[]> bloecke = new java.util.ArrayList<>();
        int start = -1, ende = -1, leer = 0;
        for (int x = ref; x < bis; x++) {
            int tinte = 0;
            for (int y = y0; y < y1; y++) {
                int a = bild.getRGB(x, y), g = bild.getRGB(ref, y);   // je Zeile gegen den Grund daneben (auch bei Linien)
                int d = Math.abs(((a >> 16) & 255) - ((g >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((g >> 8) & 255))
                        + Math.abs((a & 255) - (g & 255));
                if (d > 90) tinte++;
            }
            if (tinte >= 2) {
                if (start < 0) start = x;
                ende = x;
                leer = 0;
            } else if (start >= 0 && ++leer >= mindLuecke) {
                bloecke.add(new int[]{start, ende});
                start = -1;
            }
        }
        if (start >= 0) bloecke.add(new int[]{start, ende});
        for (int i = 0; i < Math.min(2, bloecke.size() - 1); i++) {
            int[] b = bloecke.get(i);
            double breite = (b[1] - b[0] + 1) / (double) w;
            int luecke = bloecke.get(i + 1)[0] - b[1] - 1;
            if (breite >= 0.025 && breite <= 0.036 && luecke <= w * 0.012) return b[1] + Math.max(3, Math.round(w * 0.003f));
        }
        return -1;
    }

    /**
     * Vergleichsform eines Namens.
     *
     * Die Texterkennung liest denselben Text nicht immer gleich -- im
     * Protokoll stehen "Not in clan", "Not in cl" und "Not in c" fuer
     * dieselbe Zeile. Beim Rundlauf durch die Gegner haengt aber alles
     * daran, denselben Spieler beim zweiten Blick WIEDERZUERKENNEN.
     *
     * Deshalb wird nur der Kern verglichen: Kleinbuchstaben und Ziffern,
     * alles andere faellt weg. Aus "~ЦИРИ~" und "ЦИРИ" wird dasselbe.
     */
    /** Gelesene Form -> der Schluessel, unter dem der Gegner dieses Turnier gefuehrt wird. */
    private final java.util.Map<String, String> namensKarte = new java.util.HashMap<>();

    /**
     * Der Schluessel fuer einen gelesenen Namen -- mit Blick auf die Namen,
     * die dieses Turnier schon vorkamen (25.09.: aus 9 Gegnern waren 22
     * geworden, weil jede Lese-Variante ein eigener Gegner war). Aehnlich
     * gelesene Namen bekommen denselben Schluessel wie der erste; wie aehnlich
     * reicht, steht in NamensVergleich.
     */
    private String namensSchluessel(String name) {
        return schluesselIn(namensKarte, name, gleich -> Log.debug("Arena", "Name \"" + name.strip() + "\" gilt als derselbe Gegner wie \"" + gleich + "\"."));
    }

    /** Der Schluessel eines Namens in der Karte eines Turniers (siehe namensSchluessel); {@code gemeldet} bekommt den Schluessel, wenn der Name als schon bekannter gilt. */
    static String schluesselIn(java.util.Map<String, String> karte, String name, java.util.function.Consumer<String> gemeldet) {
        String k = vergleichsform(name);
        if (k.isEmpty()) return "";
        String bekannt = karte.get(k);
        if (bekannt != null) return bekannt;
        // Gegen JEDE schon gesehene Lesart pruefen, nicht nur gegen die erste
        // einer Gruppe -- "xa30lu" passt zu "cxa30luhhkh", aber nicht zur
        // ersten Lesart "cka30whhkh" derselben Person.
        String gleich = k;
        // Lauf 2 (08.10., Runde 4): "aleauL92" stand auf dem Schild als "(3 ateaut92" -- der Pokal/die
        // Platzzahl davor wurde mitgelesen, der Name passte zu keinem bekannten, und der Bot hielt
        // ihn fuer einen Unbekannten (15 % statt 44 %). Darum auch ohne so einen kurzen Vorsatz pruefen.
        String ohneVorsatz = vergleichsform(name.strip().replaceFirst("^\\S{1,2}\\s+", ""));
        for (var e : karte.entrySet()) {
            if (de.hustlebot.core.NamensVergleich.gleicheForm(k, e.getKey(), 0.70) || kurzerNameGleich(k, e.getKey())
                    || (!ohneVorsatz.equals(k) && ohneVorsatz.length() >= 5
                        && de.hustlebot.core.NamensVergleich.gleicheForm(ohneVorsatz, e.getKey(), 0.70))) {
                gleich = e.getValue();
                gemeldet.accept(gleich);
                break;
            }
        }
        karte.put(k, gleich);   // erst nach der Schleife: nicht waehrend des Durchlaufs aendern
        return gleich;
    }

    /**
     * Pruefung 01.11.: "kall" (4 Zeichen) wurde im Kampfprotokoll als "4kall", "Jakall", "nankall" und "kalt" gelesen -- vier Spieler statt einem ("kall 0 S / 7 N;
     * jakall 0 / 3; 4kall 0 / 2; kalt 0 / 1"), weil gleicheForm erst ab 5 Zeichen vergleicht. Das Protokoll liest links vom Namen 1-3 Zeichen vom Wappen mit.
     * Gilt nur HIER, im Schluessel eines Turniers (hoechstens zehn Spieler) -- nicht im Notizblock mit tausenden Namen: ein vierstelliger Name mit 1-3
     * Zeichen davor, oder ein vierstelliger mit einem anderen Zeichen (kall/kalt). Formen wie vergleichsform().
     */
    static boolean kurzerNameGleich(String a, String b) {
        if (a == null || b == null) return false;
        String kurz = a.length() <= b.length() ? a : b, lang = kurz == a ? b : a;
        if (kurz.length() != 4 || lang.length() > 7) return false;
        if (kurz.chars().anyMatch(Character::isDigit)) return false;   // "4711", "max1": Ziffern sind Teil echter Namen
        if (lang.length() == 4) return de.hustlebot.core.NamensVergleich.abstandBis(kurz, lang, 1) <= 1;
        return lang.endsWith(kurz);
    }

    static String vergleichsform(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Ist diese Vorlage im Vergleichsfenster zu sehen -- und nur dort?
     *
     * Das Fenster deckt die Pyramide nur teilweise ab; links und rechts
     * bleiben Schilder sichtbar. Eine Suche ueber das ganze Bild trifft
     * deshalb auch Gegner, die gar nicht gemeint sind. Genau so wurde ein
     * Spieler MIT Clan fuer clanlos gehalten, weil irgendwo am Rand ein
     * "Not in clan" stand -- und deshalb nicht in den Notizblock
     * aufgenommen, obwohl Autor ihm wieder begegnen wird.
     *
     * Der Gegner steht immer UNTER der Staerkezeile. Nur dort wird gesucht.
     */
    private boolean nurImVergleich(Mat screen, BotContext ctx,
                                   Optional<Matcher.Hit> schwerter, String vorlage) {
        if (!ctx.matcher().has(vorlage)) return false;
        if (schwerter.isEmpty()) return ctx.visible(screen, vorlage);

        int untergrenze = schwerter.get().y();
        for (Matcher.Hit h : ctx.matcher().findAll(screen, vorlage)) {
            if (h.centerY() > untergrenze) return true;
        }
        return false;
    }

    /**
     * Steht der Gegner IM VERGLEICHSFENSTER auf der harten Sperrliste?
     *
     * Frueher wurde dafuer das ganze Bild abgesucht. Das Vergleichsfenster
     * deckt die Pyramide aber nur teilweise ab -- links und rechts bleiben
     * Schilder sichtbar. Stand Rina irgendwo am Rand, galt der Gegner im
     * Fenster als gesperrt, obwohl dort jemand ganz anderes stand. Der Bot
     * blaetterte weiter, fand Rina im Hintergrund erneut, blaetterte
     * wieder -- und verbrauchte alle Blicke, ohne je anzugreifen.
     *
     * Der Name des Gegners steht UNTER der Staerkezeile mit den gekreuzten
     * Schwertern. Nur dieser Bereich wird geprueft.
     */
    private boolean gegnerIstGesperrt(Mat screen, BotContext ctx,
                                      Optional<Matcher.Hit> schwerter) {
        List<Matcher.Hit> sperr = trefferMitPrefix(screen, ctx, T_BLOCK_PREFIX);
        if (sperr.isEmpty()) return false;

        if (schwerter.isEmpty()) {
            // Ohne Ankerpunkt bleibt nur das ganze Bild. Lieber einmal zu
            // vorsichtig als einen gesperrten Gegner angreifen -- aber es
            // gehoert ins Protokoll, damit der Grund fuer ein merkwuerdiges
            // Ueberspringen nachvollziehbar ist.
            Log.debug("Arena", "ar_power_swords nicht gefunden -- die Sperrliste wird "
                    + "auf dem ganzen Bild geprueft, das kann zu viel treffen.");
            return true;
        }

        int untergrenze = schwerter.get().y();
        for (Matcher.Hit h : sperr) {
            if (h.centerY() > untergrenze) return true;
        }
        return false;
    }

    /** Alle Treffer aller Vorlagen, deren Name so anfaengt. */
    /**
     * "Reward:" auf allen Schilden der Pyramide. Bis 02.10. nur ar_reward (blau/grau) -- auf dem
     * GOLDENEN Schild (Platz 1) passte es nur zu 0,60, auf dem ORANGEN (Platz 3) zu 0,73: die beiden
     * besten Gegner (225 Punkte) waren fuer den Bot unsichtbar (Lauf 2, Stage 5: Flimsy). Jetzt die
     * ganze Serie, ar_reward_2 (gold) und ar_reward_3 (orange) dazu; derselbe Schild zaehlt einmal.
     */
    private List<Matcher.Hit> rewardTreffer(Mat screen, BotContext ctx) {
        List<Matcher.Hit> alle = new ArrayList<>();
        for (String t : ctx.matcher().serie(T_REWARD)) {
            for (Matcher.Hit h : ctx.matcher().findAll(screen, t)) {
                boolean doppelt = false;
                for (Matcher.Hit a : alle) {
                    if (Math.abs(a.centerX() - h.centerX()) < 20 && Math.abs(a.centerY() - h.centerY()) < 12) {
                        doppelt = true;
                        break;
                    }
                }
                if (!doppelt) alle.add(h);
            }
        }
        platzErsatz(screen, ctx, alle);
        return alle;
    }

    /** Schwelle fuer "Reward:" an einer festen Platz-Stelle, an der die ganze Suche nichts fand. */
    static final double PLATZ_ERSATZ_SCHWELLE = 0.70;

    /**
     * Pruefung 29.10. nachts: Platz 6 (rechts in der 3. Reihe) fehlte in 5 von 5 Turnieren in "Places in Stage 1" --
     * der Gegner dort war in Runde 1 nie waehlbar. Nachgemessen am Pyramidenbild 23:52: "Reward:" passt dort nur zu
     * 0,76 (Plaetze 4/5 zu 0,86, die anderen 0,92-1,00), die Suche verlangt 0,84. Darum an jeder festen Platz-Stelle
     * (ArenaTabelle), an der nichts gefunden wurde, nur dort noch einmal mit 0,70 suchen. Das eigene Schild und
     * besiegte ("Victory!") kamen dort auf hoechstens 0,28.
     */
    private void platzErsatz(Mat screen, BotContext ctx, List<Matcher.Hit> alle) {
        double fy = screen.rows() / 770.0;
        for (int p = 1; p < ArenaTabelle.X.length; p++) {
            int cx = (int) Math.round(ArenaTabelle.X[p] * screen.cols());
            int cy = (int) Math.round(ArenaTabelle.Y[p] * screen.rows() + 29 * fy);   // Mitte von "Reward:"
            boolean da = false;
            for (Matcher.Hit a : alle) {
                if (Math.abs(a.centerX() - cx) < 50 && Math.abs(a.centerY() - cy) < 25) {
                    da = true;
                    break;
                }
            }
            if (da) continue;
            Matcher.Hit best = null;
            org.opencv.core.Rect bereich = new org.opencv.core.Rect(cx - 80, cy - (int) Math.round(30 * fy), 160,
                    (int) Math.round(60 * fy));
            for (String t : ctx.matcher().serie(T_REWARD)) {
                Optional<Matcher.Hit> h = ctx.matcher().findImBereich(screen, t, bereich, PLATZ_ERSATZ_SCHWELLE);
                if (h.isPresent() && (best == null || h.get().score() > best.score())) best = h.get();
            }
            if (best != null) {
                Log.debug("Arena", "Platz " + p + ": \"Reward:\" nur an der festen Stelle gefunden (" + String.format(
                        java.util.Locale.ROOT, "%.2f", best.score()) + ").");
                alle.add(best);
            }
        }
    }

    /**
     * Die Felder der Schilder, in denen ein Listen-Treffer liegen muss, um zu diesem Schild zu gehoeren: dasselbe Rechteck wie inFeld in
     * schilderLesen (Breite = Schildbreite, Hoehe = SCHILD_HOEHE, oben = "Reward:" minus SCHILD_HOEHE). Das eigene Schild entfaellt.
     */
    static List<org.opencv.core.Rect> schildFelder(List<Matcher.Hit> rewards, List<Matcher.Hit> eigene, int schildBreite) {
        List<org.opencv.core.Rect> felder = new ArrayList<>();
        for (Matcher.Hit r : rewards) {
            int x = r.centerX() - schildBreite / 2;
            int y = r.y() - SCHILD_HOEHE;
            if (inFeld(eigene, x, y, schildBreite, SCHILD_HOEHE)) continue;
            felder.add(new org.opencv.core.Rect(x, y, schildBreite, SCHILD_HOEHE));
        }
        return felder;
    }

    /**
     * Treffer aller Vorlagen mit dieser Vorsilbe, aber nur dort, wo die MITTE des Treffers in einem der Felder liegt -- genau das, was
     * inFeld danach ohnehin verlangt. Gesucht wird je Feld nur in dem Bereich, in dem eine Vorlage ihre Mitte im Feld haben kann (Feld +
     * halbe Vorlage ringsum); findImBereich rechnet an jeder Stelle denselben Wert wie die Suche im ganzen Bild (Schwelle gleich).
     * Gegenueber der Suche im ganzen Bild (7,6 s fuer 226 Listenbilder, gemessen 01.11.) ein Bruchteil der Flaeche.
     * Die "_auto"-Bilder entscheiden nichts (wie in trefferMitPrefix).
     */
    static List<Matcher.Hit> trefferInFeldern(Matcher matcher, Mat screen, String prefix, List<org.opencv.core.Rect> felder,
                                              java.util.Map<String, int[]> groessen, double schwelle) {
        List<Matcher.Hit> alle = new ArrayList<>();
        if (felder.isEmpty()) return alle;
        for (String name : matcher.names()) {
            if (!name.startsWith(prefix) || de.hustlebot.core.ArenaListenOrdner.istAuto(name)) continue;
            int[] g = groessen.get(name);
            if (g == null) continue;
            int halbB = g[0] / 2, halbH = g[1] / 2;
            for (org.opencv.core.Rect f : felder) {
                // Mitte = links oben + Breite/2 (ganzzahlig, wie Hit.centerX): links oben von f.x - halbB bis f.x + f.width - halbB.
                org.opencv.core.Rect bereich = new org.opencv.core.Rect(f.x - halbB, f.y - halbH, f.width + g[0], f.height + g[1]);
                matcher.findImBereich(screen, name, bereich, schwelle).ifPresent(alle::add);
            }
            leserMachtPlatz();
        }
        return alle;
    }

    private List<Matcher.Hit> trefferMitPrefix(Mat screen, BotContext ctx, String prefix) {
        List<Matcher.Hit> alle = new ArrayList<>();
        for (String name : ctx.matcher().names()) {
            // Review 28.10.: "_auto"-Bilder hat der Bot nur zum Ansehen in Autors Listen gelegt -- sie entscheiden nichts
            // (die Auto-Liste kommt weiter aus dem Notizblock, samt "letzter Kampf verloren -> eine Stufe runter").
            if (de.hustlebot.core.ArenaListenOrdner.istAuto(name)) continue;
            if (name.startsWith(prefix)) {
                // Der Lese-Faden sucht nur auf den Schildern: das ganze Bild kostete in der ersten Runde 13 von 18 s (188 Listenbilder).
                if (BEOB_LESER.equals(Thread.currentThread().getName())) alle.addAll(trefferAufSchilden(screen, ctx, name));
                else alle.addAll(ctx.matcher().findAll(screen, name));
                leserMachtPlatz();
            }
        }
        return alle;
    }

    /** Diese Vorlage auf den zehn Schildern der Pyramide: Name und Clan liegen 62 bis 10 Punkte ueber der Punktezeile. */
    private static List<Matcher.Hit> trefferAufSchilden(Mat screen, BotContext ctx, String name) {
        List<Matcher.Hit> l = new ArrayList<>();
        int w = ctx.width(), h = ctx.height();
        double s = h / 770.0;
        for (int p = 1; p <= 10; p++) {
            int mx = (int) Math.round(ArenaTabelle.X[p] * w), my = (int) Math.round(ArenaTabelle.Y[p] * h);
            org.opencv.core.Rect r = new org.opencv.core.Rect(mx - (int) (w * 0.095), my - (int) (62 * s), (int) (w * 0.19), (int) (72 * s));
            ctx.matcher().findImBereich(screen, name, r, Matcher.DEFAULT_THRESHOLD).ifPresent(l::add);
        }
        return l;
    }

    /** Liegt einer der Treffer im angegebenen Rechteck? */
    private static boolean inFeld(List<Matcher.Hit> treffer,
                                  int x, int y, int breite, int hoehe) {
        for (Matcher.Hit h : treffer) {
            if (h.centerX() >= x && h.centerX() <= x + breite
                    && h.centerY() >= y && h.centerY() <= y + hoehe) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Long> optional(OptionalLong o) {
        return o.isPresent() ? Optional.of(o.getAsLong()) : Optional.empty();
    }

    /**
     * Schreibt alle 15 Sekunden ins Protokoll, was in der Pyramide steht.
     *
     * Kostet nichts und ist die einzige Moeglichkeit, die gelesenen Namen
     * und Punktzahlen zu pruefen, ohne dass Autor ein Bildschirmfoto
     * schicken muss. Steht dort Unsinn, sieht man es sofort.
     */
    private void bericht(List<Schild> schilder, int runde, long rest, int haekchen) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzterBericht < 15_000) return;
        letzterBericht = jetzt;

        StringBuilder sb = new StringBuilder();
        sb.append(Sprache.t("Round ", "Runde ")).append(runde).append(Sprache.t(", ", ", noch "))
          .append(rest < 0 ? "?" : rest).append(Sprache.t(" s left, ", " s, ")).append(haekchen)
          .append(Sprache.t(" of 9 done. Attackable: ", " von 9 fertig. Angreifbar: ")).append(schilder.size()).append(" -- ");
        for (Schild s : schilder) {
            sb.append(s.name().isBlank() ? "?" : s.name())
              .append("(").append(s.punkte());
            if (s.botClan())  sb.append(",Bot");
            if (s.gesperrt()) sb.append(Sprache.t(",BLOCKED", ",GESPERRT"));
            if (s.ungern())   sb.append(Sprache.t(",avoid", ",ungern"));
            if (s.sicher())   sb.append(Sprache.t(",safe", ",sicher"));
            if (s.machbar())  sb.append(Sprache.t(",doable", ",machbar"));
            sb.append(") ");
        }
        // Idee 10 (A03, 22.10.): Vorrat an sicheren Gegnern (ab 90 %) fuer die restlichen Runden. Bei hoechstens 4 solchen
        // im Turnier kam der Bot nur in 17 von 32 Turnieren in die Top 3, bei 5 und mehr in 43 von 63. Autor: die
        // Leichtesten trotzdem gleich angreifen -- der Vorrat steht nur im Protokoll und in der Top-3-Rechnung.
        int restRunden = LETZTE_RUNDE - runde + 1;
        long sichere = schilder.stream().filter(s -> !s.gesperrt() && siegchance(s) >= 0.90).count();
        sb.append(Sprache.t("| Supply: " + sichere + " safe (from 90 %) for " + restRunden + " rounds",
                "| Vorrat: " + sichere + " sichere (ab 90 %) fuer " + restRunden + " Runden"));
        if (sichere < restRunden) sb.append(Sprache.t(" -- too few, later rounds get risky", " -- zu wenig, spaetere Runden werden riskant"));
        Log.info("Arena", sb.toString().trim());
    }
}
