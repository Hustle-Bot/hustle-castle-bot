package de.hustlebot.discord;

import de.hustlebot.bot.BotEngine;
import de.hustlebot.bot.DungeonMode;
import de.hustlebot.bot.MeinTeam;
import de.hustlebot.core.Kriegsplan;
import de.hustlebot.core.Log;
import de.hustlebot.core.NotAusWache;
import de.hustlebot.core.PowerControl;
import de.hustlebot.core.Sprache;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Discord fuer die Freunde-Fassung.
 *
 * Das ist eine eigene, kleine Bruecke -- nicht die grosse aus Autors Bot. Die
 * kann vierzig Befehle und haengt an Steckdose, Uebersetzer, Notizblock und
 * Bedienfeld. Hier gibt es zehn Befehle und sonst nichts.
 *
 * WAS SIE TROTZDEM KANN, weil es ohne nicht geht:
 *
 *   - eine Warteschlange (!after), damit Verlies und Kathedrale nacheinander
 *     laufen koennen
 *   - die Kriegsuhr: Wird eine Aufstellung faellig, wird der laufende
 *     Durchgang zu Ende gebracht, dann der Krieg gesetzt, dann geht es weiter
 */
public final class DiscordBridge {

    private final BotEngine engine;
    private final DiscordClient client;
    private final String botToken;
    private final String channelId;
    private final String statsChannelId;
    private final String fassung;

    private DiscordGateway gateway;

    /**
     * Geplantes Herunterfahren des PCs (!off HH:MM). offAtMillis 0 heisst: nichts
     * geplant. Der Motor wird ueber setStopAtClock so gestellt, dass er einen
     * laufenden Durchlauf noch beendet und dann stehen bleibt; die Wache faehrt
     * danach herunter. So klappt es auch, wenn der Bot zur Uhrzeit schon fertig
     * ist und nur noch wartet.
     */
    private volatile long offAtMillis = 0;
    private volatile String offClock = "";

    // !off HH:MM ueberlebt seit 12.10. einen Neustart (Autor: "jede Einstellung"); schon vorbei = verworfen.
    {
        long um = de.hustlebot.core.Dauerwerte.zahl(de.hustlebot.core.Dauerwerte.AUS_UM, 0L);
        if (um > System.currentTimeMillis()) {
            offAtMillis = um;
            offClock = de.hustlebot.core.Dauerwerte.lesen(de.hustlebot.core.Dauerwerte.AUS_UHRZEIT, "");
        } else if (um != 0) {
            offMerken();
        }
    }

    private void offMerken() {
        de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.AUS_UHRZEIT, offAtMillis > 0 ? offClock : null);
        de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.AUS_UM, offAtMillis > 0 ? offAtMillis : null);
    }
    private volatile boolean offAusgeloest = false;

    /** Not-Aus: die Wache liest die Taste ab, hier steht der Name und die Konfig-Datei. */
    private NotAusWache notAusWache;
    private Thread notAusFaden;
    private volatile String notAusTaste = "";
    private java.nio.file.Path configDatei;

    /**
     * Ein Auftrag in der Warteschlange. freund ist leer bei zufaelligem
     * Mitspieler, sonst der Name aus "!start dungeon 10 friend Lisa".
     */
    private record Auftrag(String modus, int runs, String weg, String freund, boolean warten) { }

    /**
     * Was in einem Befehl nach "friend" steht -- und der Befehl ohne diesen
     * Teil. warten: am Ende stand "wait" -- der Bot laedt nicht selbst ein,
     * sondern wartet auf die Einladung des Freundes.
     */
    record FreundAngabe(String rest, String freund, boolean genannt, boolean warten) {}

    /**
     * Trennt den Freund aus dem Befehl heraus:
     *
     *     "dungeon 10 friend Lisa"         ->  "dungeon 10"  und  "Lisa"
     *     "dungeon 10 friend: Sir Camelot"  ->  "dungeon 10"  und  "Sir Camelot"
     *
     * Das MUSS vor allem anderen passieren: zahlAus() liest alle Ziffern
     * zusammen, aus "dungeon 10 friend Ady712" wuerden sonst 10712
     * Durchlaeufe. Und modusAus() sucht nur nach Wortteilen -- ein Freund
     * namens "Warrior" machte aus jedem Befehl den Clan-Krieg.
     */
    static FreundAngabe freundAbtrennen(String rest) {
        if (rest == null) return new FreundAngabe("", "", false, false);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(^|\\s)(friend|freund)(?=[\\s:=]|$)[\\s:=]*(.*)$")
                .matcher(rest);
        if (!m.find()) return new FreundAngabe(rest, "", false, false);

        // "friend Lisa wait": das letzte Wort schaltet das Warten ein. Nur
        // wenn davor noch ein Name steht -- ein Freund namens "Wait" bleibt
        // ein Freund namens Wait.
        String name = m.group(3).trim();
        boolean warten = false;
        java.util.regex.Matcher w = java.util.regex.Pattern
                .compile("(?i)^(.*\\S)\\s+(wait|warte|warten)$").matcher(name);
        if (w.find()) {
            name = w.group(1).trim();
            warten = true;
        }
        return new FreundAngabe(rest.substring(0, m.start()).trim(), name, true, warten);
    }

    /**
     * "dungeon 10 lisa" -- der Freund auch ohne das Wort friend. So tippt
     * Autor es (12.09.).
     *
     * Was nach dem Modus steht und keine Zahl ist, gilt als Name -- aber nur
     * im Verlies. Ob es fuer den Namen ein Bild gibt, prueft danach
     * freundPruefen(), wie beim Wort friend auch. "random" heisst
     * ausdruecklich: kein Freund.
     *
     * Fuellwoerter hinter der Zahl sind KEIN Name: "10 mal", "10x", "10 runs",
     * "20 durchlaeufe" -- zahlAus() liest ohnehin nur die Ziffern. Wer einen
     * Freund hat, der wirklich "Mal" heisst, schreibt "friend Mal".
     *
     * Der Modus ist das ERSTE Modus-Wort. modusAus() sucht nur nach
     * Wortteilen; ueber den ganzen Befehl gefragt, machte ein Freund namens
     * "DK" oder "Kirchenmaus" aus dem Verlies eine Kathedrale.
     *
     * Wie bei freundAbtrennen(): Das MUSS vor zahlAus() passieren, sonst
     * wuerden aus "dungeon 10 ady712" 10712 Durchlaeufe.
     */
    static FreundAngabe freundOhneWort(String rest) {
        FreundAngabe keiner = new FreundAngabe(rest, "", false, false);
        if (rest == null || rest.isBlank()) return keiner;

        StringBuilder vorn = new StringBuilder();
        List<String> name = new ArrayList<>();
        boolean nachModus = false;
        for (String wort : rest.trim().split("\\s+")) {
            if (!nachModus) {
                vorn.append(wort).append(' ');
                String modus = modusAus(wort);
                if (modus == null) continue;
                if (!"dungeon".equals(modus)) return keiner;   // anderer Modus: kein Freund
                nachModus = true;
                continue;
            }
            if (wort.matches("(?i)\\d+x?") || FUELLWORT.matcher(wort).matches()) {
                vorn.append(wort).append(' ');
                continue;
            }
            name.add(wort);
        }
        boolean warten = false;
        if (name.size() > 1 && name.get(name.size() - 1).matches("(?i)wait|warte|warten")) {
            name.remove(name.size() - 1);
            warten = true;
        }
        String freund = String.join(" ", name).trim();
        if (freund.isEmpty() || freund.matches("(?i)random|zufall|zufaellig|zufällig")) return keiner;
        return new FreundAngabe(vorn.toString().trim(), freund, true, warten);
    }

    /** Woerter hinter der Zahl, die keinen Freund meinen -- siehe freundOhneWort(). */
    private static final java.util.regex.Pattern FUELLWORT = java.util.regex.Pattern.compile(
            "(?i)x|mal|run|runs|runde|runden|times|durchlauf|durchlaeufe|durchläufe"
            + "|durchgang|durchgaenge|durchgänge|stueck|stück|bitte|please");

    /**
     * Prueft den Freund aus einem Befehl und sagt in Discord, was nicht passt.
     * Liefert null, wenn der Befehl deshalb nicht ausgefuehrt wird -- sonst
     * den Freund (leer fuer zufaelligen Mitspieler).
     */
    private String freundPruefen(FreundAngabe angabe, String modus, String befehl) {
        if (!angabe.genannt()) return "";
        if (angabe.freund().isEmpty()) {
            client.sendCard(Sprache.t("Which friend?", "Welcher Freund?"),
                    Sprache.t("The name is missing after `friend`:\n", "Nach `friend` fehlt der Name:\n")
                    + "`!" + befehl + " dungeon 10 lisa`",
                    DiscordClient.FARBE_WARNUNG);
            return null;
        }
        if (!"dungeon".equals(modus)) {
            client.sendCard(Sprache.t("Friends only in the dungeon", "Freunde nur im Verlies"),
                    Sprache.t("Playing together with a friend only works in the dungeon. Nothing was started.",
                              "Zusammen mit einem Freund spielen geht nur im Verlies. "
                              + "Es wurde nichts gestartet."), DiscordClient.FARBE_WARNUNG);
            return null;
        }
        // Mehrere Freunde durch Komma: "wetter,reaper,locke" -- auch mit
        // Leerzeichen und in GROSS/klein (Autor, 16.09.). freundVorlage() macht
        // jeden Namen klein und ohne Sonderzeichen zum Bildnamen.
        java.util.List<String> namen = new java.util.ArrayList<>();
        for (String teil : angabe.freund().split(",")) {
            String n = teil.trim();
            if (!n.isEmpty()) namen.add(n);
        }
        if (namen.isEmpty()) {
            client.sendCard(Sprache.t("Which friend?", "Welcher Freund?"),
                    "`!" + befehl + " dungeon 100 wetter,reaper,locke`",
                    DiscordClient.FARBE_WARNUNG);
            return null;
        }

        for (String n : namen) {
            String v = DungeonMode.freundVorlage(n);
            if (v.equals("friend_invite") || v.equals(DungeonMode.freundVorlage(""))) {
                client.sendCard(Sprache.t("Name not possible", "Name geht nicht"),
                        "`" + n + "`" + Sprache.t(" cannot be used as a friend's name.",
                                                  " geht als Freundesname nicht."), DiscordClient.FARBE_WARNUNG);
                return null;
            }
        }

        // "wait" gibt es nur mit GENAU einem Freund.
        if (angabe.warten() && namen.size() == 1) {
            if (!engine.context().matcher().liegtBereit("invite_accept")) {
                client.sendCard(Sprache.t("The Accept button is missing", "Der Accept-Knopf fehlt"),
                        Sprache.t("To wait for the invitation from " + namen.get(0) + ", the bot needs "
                                  + "the picture of the green **Accept** button. It should come with the "
                                  + "bot – please tell Autor.",
                                  "Um auf die Einladung von " + namen.get(0) + " zu warten, braucht "
                                  + "der Bot das Bild des grünen **Accept**-Knopfes. Das sollte "
                                  + "mitgeliefert sein – bitte sag Autor Bescheid."),
                        DiscordClient.FARBE_WARNUNG);
                return null;
            }
            return namen.get(0);
        }

        // Jeder genannte Freund braucht sein Namensbild.
        java.util.List<String> fehlt = new java.util.ArrayList<>();
        for (String n : namen) {
            if (!engine.context().matcher().liegtBereit(DungeonMode.freundVorlage(n))) {
                fehlt.add(n + " (`" + DungeonMode.freundVorlage(n) + "`)");
            }
        }
        if (!fehlt.isEmpty()) {
            client.sendCard(Sprache.t("A friend's picture is missing", "Bild eines Freundes fehlt"),
                    Sprache.t("The bot finds a friend in the friends list by a picture of their "
                              + "name. Still missing:\n" + String.join("\n", fehlt) + "\n\n"
                              + "Open the friends list in the game (Dungeon → Friend), start "
                              + "**Capture-Images.exe**, go to the step **Name of a friend**, frame "
                              + "only the letters and save. Type the name when asked. Then try again.",
                              "Der Bot findet einen Freund in der Freundesliste an einem Bild seines "
                              + "Namens. Noch nicht da:\n" + String.join("\n", fehlt) + "\n\n"
                              + "Öffne im Spiel die Freundesliste (Verlies → Friend), starte "
                              + "**Capture-Images.exe**, klick dich zum Schritt **Name eines "
                              + "Freundes**, rahme nur die Buchstaben ein und speichere. Wenn gefragt "
                              + "wird, tipp den Namen ein. Dann noch einmal probieren."),
                    DiscordClient.FARBE_WARNUNG);
            return null;
        }
        return String.join(",", namen);
    }

    private final List<Auftrag> kette = new ArrayList<>();
    private final Kriegsplan kriegsplan = new Kriegsplan();

    /** Sicherheit (16.10.): gefaehrliche Befehle nur fuer den Besitzer des Servers + discord.erlaubteNutzer. */
    private final Rechte rechte = Rechte.ausConfig(de.hustlebot.core.Ordner.bei("config.properties"));
    /** Flut-Bremse (16.10.): Foto-/Berichts-Befehle hoechstens alle 5 s je Nutzer. */
    private final Flutbremse bremse = new Flutbremse();
    /** Fotos nur vom Spielfenster (16.10.), fuer !screenshot, !live und !fix. */
    private final Spielfoto spielfoto = new Spielfoto();

    private BotEngine.State letzterZustand = BotEngine.State.BEREIT;
    private int statsMinuten = de.hustlebot.core.Dauerwerte.zahl(de.hustlebot.core.Dauerwerte.STATS, 0);   // seit 12.10. gemerkt
    private long statsZuletzt = 0;
    /** !watch (Autor 21.10., aus dem Hauptbot): alle so viele Minuten ein Bild vom Spiel; 0 = aus. Gemerkt ueber Neustarts. */
    private volatile int watchMinuten = de.hustlebot.core.Dauerwerte.zahl(de.hustlebot.core.Dauerwerte.WACH, 0);
    private volatile long watchNaechstes = 0;
    private static final int WATCH_MIN = 1;
    private static final int WATCH_MAX = 240;
    /** Wie viele Durchlaeufe zuletzt gemeldet wurden -- fuer die Meldung je Durchlauf. */
    private int gemeldeteRuns = -1;

    public DiscordBridge(BotEngine engine, DiscordClient client,
                         String botToken, String channelId, String statsChannelId,
                         String fassung) {
        this.engine = engine;
        this.client = client;
        // Autor 17.10.: Kathedralen-Zeitmesser -- seit 24.10. eine Karte je 10 Runs (Kathedralzeiten.MELDEN_ALLE).
        de.hustlebot.core.Kathedralzeiten.melder = text -> client.sendCard(
                Sprache.t("\u23F1 Cathedral times (10 runs)", "\u23F1 Kathedralen-Zeiten (10 Runs)"), text, DiscordClient.FARBE_INFO);
        this.botToken = botToken == null ? "" : botToken.trim();
        this.channelId = channelId == null ? "" : channelId.trim();
        this.statsChannelId = statsChannelId == null ? "" : statsChannelId.trim();
        this.fassung = fassung;

        // Ohne Spiel startet der Motor nicht -- und sagt es hierueber ruhig,
        // statt mit einer roten Fehlerkarte (13.09.).
        engine.setMeldungOhneSpiel(() -> client.sendCard(
                Sprache.t("Hustle Castle is not open", "Hustle Castle ist nicht offen"),
                Sprache.t("The bot did not start. Open the game first, then type the command again.",
                          "Der Bot ist nicht gestartet. Mach zuerst das Spiel auf und tipp den "
                          + "Befehl dann noch einmal."),
                DiscordClient.FARBE_INFO));

        // "!burg": Meldung, sobald der Bot in der Burg steht (oder es nicht schaffte).
        engine.setHeimFertig(ok -> {
            if (ok) {
                client.sendCard(Sprache.t("Bot is in the castle", "Bot ist in der Burg"),
                        Sprache.t("Waiting for your next command.", "Wartet auf deinen nächsten Befehl."),
                        DiscordClient.FARBE_ERFOLG);
            } else {
                client.sendCard(Sprache.t("Castle not reached", "Burg nicht erreicht"),
                        Sprache.t("The bot did not get back to the castle safely and stopped. "
                                  + "Please check on the PC.",
                                  "Der Bot ist nicht sicher in die Burg gekommen und hat angehalten. "
                                  + "Bitte am Rechner nachsehen."), DiscordClient.FARBE_WARNUNG);
            }
        });
    }

    /**
     * "!burg" / "!castle": laufenden Modus zu Ende spielen (oder abbrechen, wenn
     * er haengt), in die Burg zurueck und dort auf den naechsten Befehl warten.
     */
    private void burg() {
        synchronized (kette) { kette.clear(); }
        engine.geheHeim();
        boolean laeuft = engine.state() == BotEngine.State.LAEUFT
                || engine.state() == BotEngine.State.PAUSIERT;
        client.sendCard(Sprache.t("On the way to the castle", "Auf dem Weg in die Burg"),
                laeuft
                    ? Sprache.t("The bot finishes the current run (or stops it if it hangs) and "
                                + "goes back to the castle. It reports once it is there.",
                                "Der Bot spielt den aktuellen Durchlauf noch zu Ende (oder bricht ab, "
                                + "wenn er hängt) und kehrt in die Burg zurück. Er meldet sich, wenn er "
                                + "da ist.")
                    : Sprache.t("The bot goes back to the castle and reports once it is there.",
                                "Der Bot kehrt in die Burg zurück. Er meldet sich, wenn er da ist."),
                DiscordClient.FARBE_INFO);
    }

    public Kriegsplan kriegsplan() {
        return kriegsplan;
    }

    public void start() {
        // !mute gilt auch nach einem Neustart.
        client.setStumm(java.nio.file.Files.exists(de.hustlebot.core.Ordner.bei("stumm.txt")));

        if (!botToken.isBlank() && !channelId.isBlank()) {
            List<String> kanaele = new ArrayList<>();
            kanaele.add(channelId);
            if (!statsChannelId.isBlank() && !statsChannelId.equals(channelId)) {
                kanaele.add(statsChannelId);
            }
            gateway = new DiscordGateway(botToken, kanaele, (k, t, a) -> {
                befehl(k, t, a);   // a: Name, Nutzer-ID und Server des Absenders (Sicherheit 16.10.)
            }, rechte, bremse);
            // Kurze Hinweise des Gateways ("Besitzer unbekannt", "bitte warten") als Karte in den Kanal.
            gateway.setAntwort((kanal, text) -> client.sendCardTo(kanal == null || kanal.isEmpty() ? channelId : kanal,
                    Sprache.t("Note", "Hinweis"), text, DiscordClient.FARBE_WARNUNG));
            if (!rechte.erlaubte().isEmpty()) {
                Log.info("Discord", Sprache.t("Additionally allowed users (discord.erlaubteNutzer): " + rechte.erlaubte(),
                        "Zusaetzlich erlaubte Nutzer (discord.erlaubteNutzer): " + rechte.erlaubte()));
            }
            gateway.setFixAnbindung(new StartFormularAnbindung());   // Start-Formular (29.09.)
            // Rechte im Kanal nachsehen (30.09.): fehlt "Links einbetten", kommen die Karten leer an.
            Thread rechte = new Thread(() -> {
                try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
                String hinweis = client.rechtePruefen();
                if (hinweis != null) client.sendText("⚠️ " + hinweis);
            }, "discord-rechte");
            rechte.setDaemon(true);
            rechte.start();
            gateway.start();

            // Ab jetzt landen Alarme aus dem Bot (Hitze, Spiel abgestuerzt,
            // anderes Geraet angemeldet ...) auch im Discord-Kanal -- wie beim
            // Hauptbot. Ohne diese Zeile stuenden sie nur im Protokoll.
            Log.setMelder((bereich, meldung) ->
                    client.sendCard(bereich, meldung, DiscordClient.FARBE_WARNUNG));
            // Kurze Hinweise (10.10., Titan-Grabung: alle 1000 Spitzhacken die Zeiten) -- wie im Hauptbot.
            Log.setHinweisMelder((titel, text) -> client.sendCard(titel, text, DiscordClient.FARBE_INFO));
            // Titan-Rangliste um 0 und 12 Uhr (Hauptbot 10.10.): am Ende des Besuchs ein Bild mit Platz 25 und dem Faktor.
            de.hustlebot.bot.TitanEventMode.tagesRanglisteZiel(png -> client.sendFile(
                    Sprache.t("**Titan excavation: ranking (0:00 / 12:00)**", "**Titan-Grabung: Rangliste (0 / 12 Uhr)**")
                            + (de.hustlebot.bot.TitanEventMode.letzterRanglisteText().isEmpty() ? ""
                               : "\n" + de.hustlebot.bot.TitanEventMode.letzterRanglisteText()),
                    "titan-rangliste.png", png));
            // Stigmas leveln (Hauptbot 11.10.): nach jeder Sorte (Mind, Will ...) die Uebersicht mit den Funden.
            de.hustlebot.bot.StigmaMode.discordBildZiel((text, png) -> client.sendFile(text, "stigmas.png", png));
        } else {
            Log.info("Discord", Sprache.t("Discord is not set up -- the bot is controlled with the control window "
                    + "(Discord is optional, see GUIDE-1-SETUP-EN.txt).",
                    "Discord ist nicht eingerichtet -- der Bot wird ueber das Steuerfenster bedient "
                    + "(Discord ist freiwillig, siehe GUIDE-1-SETUP-EN.txt)."));
            // Code-Pruefung 16.10.: ohne Discord kamen Alarme (anderes Geraet angemeldet, Hitze, Haenger) nirgends an.
            // Die Karte geht zuerst an den Mithoerer (Steuerfenster, Windows-Meldung) -- nach Discord dann eben nicht.
            Log.setMelder((bereich, meldung) -> client.sendCard(bereich, meldung, DiscordClient.FARBE_WARNUNG));
            Log.setHinweisMelder((titel, text) -> client.sendCard(titel, text, DiscordClient.FARBE_INFO));
        }

        Thread wache = new Thread(this::wacheLaeuft, "discord-wache");
        wache.setDaemon(true);
        wache.start();
    }

    public void stop() {
        if (gateway != null) gateway.stop();
    }

    // ------------------------------------------------------------------
    //  Steuerprogramm (3.2): Befehle aus dem Fenster, wie in Discord getippt
    // ------------------------------------------------------------------

    private volatile de.hustlebot.steuer.Steuerprogramm steuer;

    /** Von Main: das Steuerprogramm. */
    public void setSteuerprogramm(de.hustlebot.steuer.Steuerprogramm s) {
        this.steuer = s;
    }

    /** Ein Befehl aus dem Steuerprogramm (nur vom eigenen Rechner) -- wie in Discord getippt. */
    public void befehlVonBedienfeld(String text) {
        befehl(channelId, text);
    }

    /** Ein Wert in config.properties -- Kommentare und andere Zeilen bleiben stehen. Gilt nach einem Neustart. */
    public static void configWertSchreiben(java.nio.file.Path datei, String schluessel, String wert)
            throws java.io.IOException {
        schreibeConfigWert(datei, schluessel, wert);
    }

    /**
     * Schreibt einen einzelnen Wert in config.properties, ohne die anderen
     * Zeilen und Kommentare anzutasten -- zeilenweise, dann atomar umbenannt.
     */
    private static void schreibeConfigWert(java.nio.file.Path datei, String schluessel, String wert)
            throws java.io.IOException {
        java.util.List<String> zeilen = java.nio.file.Files.exists(datei)
                ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(datei,
                        java.nio.charset.StandardCharsets.ISO_8859_1))
                : new java.util.ArrayList<>();
        boolean gesetzt = false;
        for (int i = 0; i < zeilen.size(); i++) {
            String z = zeilen.get(i).trim();
            if (z.startsWith(schluessel + "=") || z.startsWith(schluessel + " ")
                    || z.equals(schluessel)) {
                zeilen.set(i, schluessel + "=" + wert);
                gesetzt = true;
                break;
            }
        }
        if (!gesetzt) zeilen.add(schluessel + "=" + wert);

        java.nio.file.Path neu = datei.resolveSibling(datei.getFileName() + ".neu");
        java.nio.file.Files.write(neu, zeilen, java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            java.nio.file.Files.move(neu, datei,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(neu, datei,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }


    // ------------------------------------------------------------------
    //  Befehle
    // ------------------------------------------------------------------

    /** Ein Befehl vom eigenen Rechner (Steuerprogramm, Start-Formular ohne Absender): darf alles. */
    private void befehl(String kanal, String roh) {
        befehl(kanal, roh, DiscordGateway.Absender.eigenerRechner());
    }

    private void befehl(String kanal, String roh, DiscordGateway.Absender absender) {
        try {
            String text = roh == null ? "" : roh.trim();
            if (!text.startsWith("!")) return;

            // "! start" soll dasselbe sein wie "!start". Auf dem Handy setzt
            // die Tastatur nach dem ! gern von selbst ein Leerzeichen -- sonst
            // passierte gar nichts. Grossschreibung (!START) ist durch das
            // toLowerCase() unten erledigt.
            text = "!" + text.substring(1).stripLeading();

            String[] teile = text.split("\\s+", 2);
            String verb = teile[0].toLowerCase(Locale.ROOT);
            String rest = teile.length > 1 ? teile[1].trim() : "";

            Log.info("Discord", "Command: " + text);

            // "!lighthouse off" / "!colosseum off", auch deutsch und in verschiedenen Schreibweisen
            // (Autor, 09.10.) -- dasselbe wie "!settings lighthouse off".
            String ortWort = ortBefehl(verb);
            if (ortWort != null) {
                if (!erlaubt(kanal, "!settings", absender)) return;   // ist eine Einstellung (16.10.)
                einstellungen(ortWort + (rest.isBlank() ? "" : " " + rest));
                return;
            }

            // 11.10. (Hauptbot, Autor: "jeden Befehl im Discord auf Englisch, auf Deutsch und mit verschiedenen
            // Schreibweisen"): jede bekannte Schreibweise aus Befehlsnamen wird still zu dem Befehl, den die Verteilung
            // unten kennt ("!stopp" -> "!stop", "!shutdown" -> "!off"). Ein frueher angelegtes eigenes Kuerzel gewinnt.
            if (kuerzel().befehlFuer(verb, "") == null) verb = Befehlsnamen.kanonisch(verb);
            // Steuerfenster aus dem Hauptbot (16.10.): "!clearall" heisst hier "!clear" (der Freunde-Bot leert den
            // Kanal, in dem getippt wurde) -- schon VOR der Rechte-Pruefung, damit es genauso nur der Besitzer darf.
            if (verb.equals("!clearall")) verb = "!clear";

            // Sicherheit (16.10.): gefaehrliche Befehle nur fuer den Besitzer des Servers, Fotos/Berichte gebremst.
            // Kuerzel und Tippfehler landen unten wieder hier (befehl(...) mit demselben Absender) -- der
            // aufgeloeste Befehl wird also ebenfalls geprueft.
            if (!erlaubt(kanal, verb, absender)) return;

            switch (verb) {
                case "!help", "!hilfe" -> hilfe(rest);
                case "!start"      -> { if (rest.isBlank()) startKarte(kanal); else starten(rest); }
                // Knopf "Loop" im Steuerfenster (16.10.): der Event-Kreislauf wie frueher der Haken "Event-Kreislauf".
                case "!ablauf", "!routine" -> ablauf(rest);
                case "!formularstart" -> formularStart(kanal);   // Knopf "So starten" (Start-Formular)
                case "!stop"       -> { stoppen(); }
                case "!burg", "!castle", "!home" -> burg();
                case "!pause"      -> pause();
                case "!resume", "!weiter" -> weiter();
                case "!status", "!stand" -> { status(); }
                case "!after"      -> anhaengen(rest);
                case "!clanwar", "!kriegsplan" -> clanwar(rest);
                // Knoepfe des Steuerprogramms (aus dem Hauptbot, 16.10.).
                case "!zeitzone", "!timezone" -> commandZeitzone(kanal, rest);
                case "!neustart", "!restart" -> commandRestart();
                case "!autostart" -> commandAutostart(rest);
                case "!settings", "!einstellungen", "!aufstellung" -> einstellungen(rest);
                case "!screenshot" -> bildschirmfoto();
                // Autor 18.10.: "!opfer Josie, Magier 3" = Namen der Opfer-Einheiten fuer den Verlies-Altar.
                case "!opfer", "!sacrifice" -> {
                    String r = rest == null ? "" : rest.trim();
                    java.nio.file.Path d = de.hustlebot.core.Ordner.bei("opfer-namen.txt");
                    try {
                        if (istAus(r.toLowerCase(Locale.ROOT)) || r.equalsIgnoreCase("none") || r.equalsIgnoreCase("keine")) java.nio.file.Files.deleteIfExists(d);
                        else if (!r.isEmpty()) java.nio.file.Files.writeString(d, r + System.lineSeparator());
                    } catch (Exception e) {
                        Log.warn("Verlies", "opfer-namen.txt: " + e.getMessage());
                    }
                    java.util.List<String> n = de.hustlebot.bot.DungeonMode.opferNamen();
                    client.sendCard(Sprache.t("Sacrifice units", "Opfer-Einheiten"), n.isEmpty()
                            ? Sprache.t("No names set -- the bot uses \"ersatz\" and the sacrifice unit of the team scan.",
                                        "Keine Namen eingetragen -- der Bot nimmt \"ersatz\" und die Opfer-Einheit aus dem Team-Scan.")
                            : Sprache.t("Sacrificed by name: ", "Am Namen geopfert: ") + String.join(", ", n), DiscordClient.FARBE_INFO);
                }
                // Autor 18.10.: "!fotoserie 5" = 5 Minuten lang die Bilder des Bots nach logsotoserie speichern.
                case "!fotoserie", "!photoseries" -> {
                    String z = rest == null ? "" : rest.replaceAll("[^0-9]", "");
                    int min = z.isEmpty() || z.length() > 3 ? 5 : Integer.parseInt(z);
                    java.nio.file.Path o = de.hustlebot.core.Fotoserie.starten(min);
                    client.sendCard(Sprache.t("Photo series", "Fotoserie"), Sprache.t("Saving the bot's pictures for " + Math.max(1, Math.min(10, min))
                            + " min (about 1.5 per second) to " + o, "Speichere " + Math.max(1, Math.min(10, min))
                            + " min lang die Bilder des Bots (etwa 1,5 je Sekunde) nach " + o), DiscordClient.FARBE_INFO);
                }
                // Autor 21.10.: Lesetest Nahrung oben in der Burg bei wechselndem Hintergrund
                case "!lesetest", "!readtest" -> {
                    String z = rest == null ? "" : rest.replaceAll("[^0-9]", "");
                    int n = z.isEmpty() || z.length() > 3 ? 20 : Integer.parseInt(z);
                    client.sendCard(Sprache.t("Read test", "Lesetest"), de.hustlebot.bot.Lesetest.starten(engine, n), DiscordClient.FARBE_INFO);
                }
                case "!reiniger", "!cleaner" -> client.sendCardTo(kanal == null || kanal.isEmpty() ? channelId : kanal,
                        Sprache.t("🧹 PC cleaner", "🧹 PC-Cleaner"),
                        Sprache.t("Your PC slow? In the bot folder there is **PC-Cleaner** -- double-click **PC-Cleaner.bat**, "
                                  + "allow changes, pick the language. It asks before every step: programs (disable/uninstall/keep), "
                                  + "junk, caches, autostart, background brakes, performance, drive, virus scan. Personal files are never touched.\n"
                                  + "Folder: `" + de.hustlebot.core.Ordner.bei("PC-Cleaner") + "`",
                                  "PC zu langsam? Im Bot-Ordner liegt **PC-Cleaner** -- **PC-Cleaner.bat** doppelklicken, "
                                  + "Aenderungen zulassen, Sprache waehlen. Er fragt vor jedem Schritt: Programme (deaktivieren/loeschen/behalten), "
                                  + "Datenmuell, Zwischenspeicher, Autostart, Hintergrund-Bremsen, Leistung, Festplatte, Virenscan. Persoenliche Dateien bleiben unberuehrt.\n"
                                  + "Ordner: `" + de.hustlebot.core.Ordner.bei("PC-Cleaner") + "`"),
                        DiscordClient.FARBE_INFO);
                case "!stats"      -> { statistik(rest); }
                case "!times", "!zeiten", "!kathedralzeiten", "!cathedraltimes" -> client.sendCard(
                        Sprache.t("\u23F1 Cathedral times", "\u23F1 Kathedralen-Zeiten"),
                        de.hustlebot.core.Kathedralzeiten.uebersicht(), DiscordClient.FARBE_INFO);
                // Titan-Grabung und Stigmas (aus dem Hauptbot, Freunde-Update 10.10.)
                case "!titan", "!grabung", "!excavation" -> commandTitan(rest);
                case "!graben", "!titangraben", "!titanstart", "!dig", "!digging", "!ausgraben" -> commandTitan("start " + rest);
                case "!stigmas", "!stigma" -> commandStigmas(rest);
                // Eigenes Team (aus dem Hauptbot, Auftrag 6 vom 11.10.): "!team", "!team scan", "!team name <Name>" ...
                // rest behaelt hier die Gross-/Kleinschreibung (nur verb ist klein) -- darum zweimal rest.
                case "!team", "!teamscan" -> commandTeam(kanal, rest, rest);
                // Arena (aus dem Hauptbot, Freunde 3.0)
                case "!arenareserve" -> arenaReserve(rest);
                // Nutzer 25.11.: Thronsaal-Stufe von Hand (sonst liest sie der Team-Scan); TS8 = einfache Arena-Strategie.
                case "!throneroom", "!thronsaal", "!ts" -> thronsaal(rest);
                // Nutzer 25.11.: Kathedrale in der Arena-Wartezeit (nach dem eigenen Angriff, Stage 1-4).
                case "!arenacathedral", "!arenakathedrale" -> arenaKathedraleSchalter(rest);
                case "!arenabericht", "!arenareport", "!arenastatistik", "!arenastats" -> arenaBerichtSchalter(rest);
                case "!notizen", "!notizblock", "!notes" -> notizen();
                case "!notiz", "!note" -> notiz(kanal, rest);
                case "!liste", "!list" -> arenaListe(kanal);
                case "!log", "!bericht", "!report" -> log();
                case "!watch", "!wach" -> watch(rest);
                // Autor 21.10. (aus dem Hauptbot): Titanen-Turm und Clan-Laden von Hand starten, zum Ausprobieren --
                // sonst laufen sie von selbst um 02:00 und 12:00. Wie jeder Start: ein offener Clan-Krieg geht vor.
                case "!tower", "!turm", "!titanturm", "!titantower" -> starten("titantower");
                case "!friends", "!freunde", "!geschenke", "!sendfriends", "!freundesenden" -> starten("freundesenden");
                case "!clanstore", "!clanladen", "!clanshop" -> starten("clanstore");
                // Autor 22.10. abends: Gem Bay, Portal und Nahrungskisten aus dem Hauptbot in den Freunde-Bot.
                case "!gembay", "!edelsteinbucht", "!gembucht" -> starten("gembay");
                case "!portal" -> starten("portal");
                case "!foodchests", "!nahrungskisten" -> starten("nahrungskisten");
                // Spiel starten, ohne einen Modus zu starten (der Launcher-Sperre-Schalter wird dabei aufgehoben).
                case "!game", "!spiel", "!hc" -> spielStarten();
                case "!event" -> eventBefehl(rest);
                // Autor 24.10.: eigene Namens-Schrift der Arena (Glyphenschrift) ansehen und beschriften
                case "!glyphs", "!glyphen" -> glyphenZeigen(rest);
                case "!glyph", "!glyphe" -> glypheBeschriften(rest);
                case "!arena","!opponent", "!gegner", "!arenaopponent", "!arenagegner" -> arenaGegner(kanal, rest);
                case "!off", "!aus", "!feierabend" -> off(rest);
                case "!specs", "!werte" -> specs(rest);
                case "!live" -> live(kanal, rest);
                case "!mute", "!stumm", "!unmute", "!laut" -> mute(verb, rest);
                case "!update", "!updates", "!neu" -> {
                    // Nur die neueste Fassung, aeltere mit "!update alle" (sonst fiel das Neue hinten weg).
                    boolean alle = rest.strip().toLowerCase(Locale.ROOT).matches("(all|alle|alles)\\b.*");
                    client.sendCardTo(kanal,
                            Sprache.t("What is new", "Was ist neu"),
                            NeuerungenFreunde.text(alle) + Sprache.t("\n🔥 big  ✨ noticeable  • small",
                                                                   "\n🔥 gross  ✨ spuerbar  • klein")
                                    + (alle ? "" : Sprache.t("\nOlder versions: `!update all`",
                                                             "\nAeltere Fassungen: `!update alle`")),
                            DiscordClient.FARBE_INFO);
                }
                case "!shortcut", "!shortcuts", "!kuerzel", "!kürzel" -> shortcut(kanal, rest);
                case "!clear", "!leeren" -> clear(kanal, rest);
                case "!queue", "!wq", "!warteschlange" -> queue(rest);
                case "!ende", "!end" -> ende(rest);
                case "!english", "!englisch", "!en" -> sprache(Sprache.Lang.EN);
                case "!text", "!nurtext", "!plain" -> nurText(rest);
                case "!deutsch", "!german", "!de" -> sprache(Sprache.Lang.DE);
                case "!pc", "!rechner" -> pc(rest);
                case "!notaus", "!not-aus", "!kill", "!emergency" -> {
                    engine.stop(Sprache.t("Emergency stop via Discord", "Not-Aus ueber Discord"));
                    client.sendCard(Sprache.t("Emergency stop", "Not-Aus"),
                            Sprache.t("The bot has stopped. `!start` gets it going again.",
                                      "Der Bot steht. Mit `!start` geht es wieder los."),
                            DiscordClient.FARBE_WARNUNG);
                }
                default -> {
                    String echt = kuerzel().befehlFuer(verb, rest);
                    Befehlsnamen.Treffer t = echt == null ? Befehlsnamen.suchen(verb) : null;
                    if (echt != null) {
                        Log.info("Discord", Sprache.t("Shortcut " + verb + " -> " + echt, "Kuerzel " + verb + " -> " + echt));
                        befehl(kanal, echt, absender);
                    } else if (t != null && !t.fragen()
                            && !Befehlsnamen.normal(t.befehl()).equals(Befehlsnamen.normal(verb))) {
                        // Eindeutiger Tippfehler: ausfuehren und kurz sagen, wie verstanden.
                        Log.info("Discord", Sprache.t("Typo " + verb + " -> " + t.befehl(), "Tippfehler " + verb + " -> " + t.befehl()));
                        client.sendCardTo(kanal, Sprache.t("Understood as", "Verstanden als"),
                                "`" + t.befehl() + "`", DiscordClient.FARBE_INFO);
                        befehl(kanal, t.befehl() + (rest.isEmpty() ? "" : " " + rest), absender);
                    } else if (t != null && t.fragen()) {
                        // Zwei Moeglichkeiten oder ein Befehl, der etwas abschaltet: nur vorschlagen.
                        String liste = "`" + String.join("`, `", t.vorschlaege()) + "`";
                        client.send(Sprache.t("Did you mean " + liste + "? Please type it again correctly.",
                                              "Meintest du " + liste + "? Bitte noch einmal richtig tippen."));
                    } else {
                        client.send(Sprache.t("I don't know that command. Try `!help`.",
                                              "Den Befehl kenne ich nicht. Versuch `!help`."));
                    }
                }
            }
        } catch (Exception e) {
            Log.error("Discord", Sprache.t("Command failed", "Befehl fehlgeschlagen"), e);
            client.send(Sprache.t("Something went wrong: ", "Da ist etwas schiefgegangen: ") + e.getMessage());
        }
    }

    /**
     * Darf dieser Absender den Befehl jetzt ausfuehren? (Sicherheit 16.10.)
     *
     *   - Gefaehrliche Befehle (!pc, !off, !notaus, !settings, !clear, !screenshot, !live): nur der
     *     Besitzer des Discord-Servers oder eine ID aus discord.erlaubteNutzer. Ist der Besitzer
     *     unbekannt, ist gesperrt -- und das Gateway versucht gleich noch einmal, ihn zu holen.
     *   - Foto-/Berichts-Befehle (!screenshot, !live, !log, !fix): hoechstens alle 5 s je Nutzer.
     *
     * Vom eigenen Rechner (Steuerprogramm) geht alles. Bei Nein steht der Grund als Karte im Kanal.
     */
    private boolean erlaubt(String kanal, String verb, DiscordGateway.Absender absender) {
        if (absender == null || !absender.vonDiscord()) return true;
        String ziel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        String sperre = rechte.sperrtext(absender.guild(), absender.id(), verb);
        if (sperre != null) {
            Log.warn("Discord", Sprache.t("Command " + verb + " from " + absender.name() + " (" + absender.id() + ") refused: " + (rechte.besitzerBekannt(absender.guild()) ? "not the server owner" : "server owner unknown"),
                    "Befehl " + verb + " von " + absender.name() + " (" + absender.id() + ") abgelehnt: "
                    + (rechte.besitzerBekannt(absender.guild()) ? "nicht der Besitzer des Servers" : "Besitzer des Servers unbekannt")));
            client.sendCardTo(ziel, Sprache.t("Not allowed", "Nicht erlaubt"), sperre, DiscordClient.FARBE_WARNUNG);
            if (gateway != null && !rechte.besitzerBekannt(absender.guild())) gateway.besitzerNachladen();
            return false;
        }
        if (Flutbremse.gebremst(verb) && !bremse.erlaubt(absender.id())) {
            Log.info("Discord", Sprache.t("Command " + verb + " from " + absender.name() + " throttled (too fast in a row).",
                    "Befehl " + verb + " von " + absender.name() + " gebremst (zu schnell hintereinander)."));
            client.sendCardTo(ziel, Sprache.t("Please wait", "Bitte warten"),
                    Flutbremse.wartetext(bremse.restSekunden(absender.id())), DiscordClient.FARBE_INFO);
            return false;
        }
        return true;
    }

    private void hilfe(String rest) {
        String w = rest == null ? "" : rest.toLowerCase(Locale.ROOT);
        if (w.startsWith("all") || w.startsWith("lang") || w.startsWith("voll")) {
            hilfeAlles();
            return;
        }
        client.sendCard(Sprache.t("Commands", "Befehle"), Sprache.t(
                "▶️ **Start & stop**\n"
                + "`!start` form to click  ·  `!start eventloop` cathedral -> event -> dungeon, again and again\n"
                + "`!start dungeon 5`  ·  `!start cathedral 3`  ·  `!start clanwar`\n"
                + "`!start cathedral 3 b` every waypoint  ·  `!start cathedral 3 c` turbo\n"
                + "`!start dungeon 10 lisa` with a friend  ·  `!start event` Hammer event (`!event ranking` picture)\n"
                + "`!start excavation` (or `!dig`) Titan excavation  ·  `!titan` more  ·  `!stigmas` stigmas (level, delete, settings)\n"
                + "`!start lighthouse`  ·  `!start colosseum`  ·  `!start titantower` (`!tower`)  ·  `!start clanstore` (`!clanstore`)\n"
                + "`!team` what the bot knows about your squad  ·  `!team scan` look at it now\n"
                + "`!stop`  ·  `!pause`  ·  `!resume`  ·  `!castle` back to the castle  ·  `!emergency`\n\n"
                + "🏟️ **Arena**  `!start arena 5` (tickets)  ·  `!start arena 5 food`  ·  `!notes`  ·  `!list`  ·  `!arenareserve 10`  ·  `!arena Rina safe`  ·  `!throneroom 8`\n\n"
                + "📋 **Queue**  `!after dungeon 20`  ·  `!queue`\n\n"
                + "⚔️ **Clan war**  `!clanwar on` / `off`  ·  `!settings`\n\n"
                + "👀 **Look in**  `!status`  ·  `!stats`  ·  `!times` cathedral times  ·  `!specs`  ·  `!screenshot`  ·  `!watch 5` photo every 5 min  ·  `!live`  ·  `!log`  ·  `!cleaner` PC cleaner\n\n"
                + "⏰ **Stop on its own**  `!end 06:30`  ·  `!off 06:30` / `!off 3pm` / `!off now`  ·  `!pc`\n\n"
                + "⭐ **Useful**  `!shortcut`  ·  `!update`  ·  `!mute`  ·  `!clear`  ·  `!game` start the game  ·  `!english` / `!deutsch`  ·  `!text on` if answers look empty\n\n"
                + "_`!help all` explains everything._",

                "▶️ **Starten & Anhalten**\n"
                + "`!start` Formular zum Anklicken  ·  `!start eventablauf` Kathedrale -> Event -> Verlies, immer wieder\n"
                + "`!start dungeon 5`  ·  `!start cathedral 3`  ·  `!start clanwar`\n"
                + "`!start cathedral 3 b` alle Wegpunkte  ·  `!start cathedral 3 c` Turbo\n"
                + "`!start dungeon 10 lisa` mit einem Freund  ·  `!start event` Hammer-Event (`!event rangliste` Bild)\n"
                + "`!start grabung` Titan-Grabung  ·  `!titan` mehr dazu  ·  `!stigmas` Stigmas (leveln, loeschen, einstellen)\n"
                + "`!start lighthouse`  ·  `!start colosseum`  ·  `!start titantower` (`!tower`)  ·  `!start clanstore` (`!clanstore`)\n"
                + "`!team` was der Bot über deine Truppe weiß  ·  `!team scan` jetzt ansehen\n"
                + "`!stop`  ·  `!pause`  ·  `!resume`  ·  `!burg` zurück in die Burg  ·  `!notaus`\n\n"
                + "🏟️ **Arena**  `!start arena 5` (Tickets)  ·  `!start arena 5 aepfel`  ·  `!notizen`  ·  `!liste`  ·  `!arenareserve 10`  ·  `!arena Rina sicher`  ·  `!thronsaal 8`\n\n"
                + "📋 **Warteschlange**  `!after dungeon 20`  ·  `!queue`\n\n"
                + "⚔️ **Clan-Krieg**  `!clanwar on` / `off`  ·  `!settings`\n\n"
                + "👀 **Nachsehen**  `!status`  ·  `!stats`  ·  `!zeiten` Kathedralen-Zeiten  ·  `!specs`  ·  `!screenshot`  ·  `!wach 5` alle 5 Min ein Foto  ·  `!live`  ·  `!log`  ·  `!reiniger` PC-Cleaner\n\n"
                + "⏰ **Von selbst aufhören**  `!ende 06:30`  ·  `!off 06:30` / `!off 15 uhr` / `!off now`  ·  `!pc`\n\n"
                + "⭐ **Praktisch**  `!shortcut`  ·  `!update`  ·  `!mute`  ·  `!clear`  ·  `!spiel` Spiel starten  ·  `!english` / `!deutsch`  ·  `!text an`, wenn Antworten leer aussehen\n\n"
                + "_`!help all` erklärt alles ausführlich._"),
                DiscordClient.FARBE_INFO);
    }

    /** Die ausführliche Hilfe: mehr Erklärung, für alle, die es genau wollen. */
    private void hilfeAlles() {
        client.sendCard(Sprache.t("Commands – in detail", "Befehle – ausführlich"), Sprache.t(
                "▶️ **Start & stop**\n"
                + "`!start` (or `/start`) opens a form: mode, runs, route, friend, PC off – just click\n"
                + "`!start eventloop` cathedral (keys, chests) -> hammer event -> dungeon runs, again and again · "
                + "`!start cathedral 3 noevent` cathedral only\n"
                + "`!start dungeon 5` 5 dungeon runs · `!start clanwar` deploy now\n"
                + "`!start dungeon 10 lisa` with your friend Lisa (capture her name once with "
                + "Capture-Images.exe). Several: `wetter,reaper,locke` – whoever accepts first\n"
                + "`!start cathedral 3` shortest way (all bosses + floor guardian) · `b` every "
                + "waypoint · `c` turbo, as fast as possible\n"
                + "`!start event` Hammer event until the hammers are gone (`!start event 5` = 5 fields) · "
                + "`!settings pass gold` / `silver` / `none` · `!settings magnifiers 5` magnifiers per field · "
                + "`!settings chest wood off` which chests to take\n"
                + "`!start excavation` (or `!dig`, `!start grabung`) Titan excavation: boss x10, all pickaxes, super pickaxes + dynamite · "
                + "`!titan 5000` at most 5000 pickaxes · `!titan ranking` · `!titan drill on`/`off` · `!titan hc on`/`off` · "
                + "`!settings automation titan` / `hammer` which event the loop plays\n"
                + "`!stigmas delete` deletes all stigmas with level 1-79 except the kept sets (standard Bull, Dragon, Wolf) "
                + "(`!stigmas delete test` = dry run; game in English) · `!stigmas level` Last Guardian: look at Bull stigmas, "
                + "equip better ones, level good ones, a picture per kind · `!stigmas level <titan name>` another titan "
                + "(`... 100` = then level 100) · `!stigmas 100` equipped ones towards level 100 (power rays + Stones of "
                + "Creation, no dry run) · `!stigmas settings` form + own ranking\n"
                + "🛡️ **Your own team** (barracks, hero, titan patrons -- once, then every 3 days by itself)\n"
                + "`!team` what the bot knows (squad power, units, sacrifice unit, hero, patrons, points)\n"
                + "`!team scan` look at it now (after the current run)  ·  `!team on` / `off` the 3-day automatic\n"
                + "`!team points off` / `on` use your team as the yardstick in the arena win chance\n"
                + "`!team hero 12 4` points for a good main / side hero ability  ·  `!team name Autor` your player name\n"
                + "`!start lighthouse` / `colosseum` / `titantower` / `clanstore` -- they also run on their own "
                + "(`!settings lighthouse off` etc.) · `!settings automation on` = `!start cathedral` loops "
                + "cathedral -> event -> dungeon · `!settings reports 10` Discord only every 10 runs\n"
                + "`!stop` stops and empties the queue · `!pause` / `!resume` · `!castle` finishes "
                + "the run and goes back to the castle · `!emergency` stops at once\n\n"
                + "🏟️ **Arena**  `!start arena 5` five tournaments, one ticket each · `!start arena 5 food` "
                + "with apples · capture your own name once (Capture-Images.exe, \"Arena: your name\") · "
                + "`!notes` arena notebook as a file · `!note Max has 4 builders` your own note "
                + "(`!note Max off` deletes it) · `!list` opponents of the tournament · `!arenareserve 10` "
                + "keep 10 tickets · `!arenareport off` no line after each tournament · `!settings pay arena food` · "
                + "`!glyphs` characters of player names without a letter · `!glyph 17 a` labels one\n\n"
                + "📋 **Queue**  `!after dungeon 20` adds to the end · `!queue` shows it · "
                + "`!queue clear` / `!queue del 2`\n\n"
                + "⚔️ **Clan war & squad**  `!clanwar on` deploys by itself (" + Kriegsplan.zeitenText("/") + "), "
                + "`!clanwar off` leaves it alone · `!settings` Deploy the best or Previous Squad per mode\n\n"
                + "👀 **Look in**  `!status` · `!stats` (`!stats 10` every 10 min, `!stats off`) · "
                + "`!specs` machine values (`!specs 10`) · `!screenshot` · `!watch 5` a picture every 5 min (`!watch off`) · `!live` picture refreshing "
                + "every 2 s for 5 min (`!live stop`) · `!log` error report as a file\n\n"
                + "⏰ **Stop on its own**  `!end 06:30` stops after the run, PC stays on · "
                + "`!off 06:30` / `!off 3pm` shuts the PC down afterwards · `!off now` right away (type twice) · "
                + "`!off delete` takes it back · `!pc off` / `!pc restart` / `!pc cancel`\n\n"
                + "⭐ **Useful**  `!shortcut` your own short command, e.g. `!mouse 25` · "
                + "`!shortcut list` · `!update` what is new · `!mute` / `!unmute` no notifications · "
                + "`!clear` empties the channel (asks first) · `!english` / `!deutsch` · `!text on` if answers look empty\n\n"
                + "_If the bot hangs, `!emergency`, `!off now` and `!pc off` still work after about "
                + "a minute (emergency watch). Upper/lower case and a space after ! do not matter._",

                "▶️ **Starten & Anhalten**\n"
                + "`!start` (oder `/start`) öffnet ein Formular: Modus, Anzahl, Weg, Freund, PC aus – nur anklicken\n"
                + "`!start eventablauf` Kathedrale (Schlüssel, Kisten) -> Hammer-Event -> Verlies-Runs, immer wieder · "
                + "`!start cathedral 3 ohneevent` nur Kathedrale\n"
                + "`!start dungeon 5` 5 Verlies-Durchläufe · `!start clanwar` jetzt aufstellen\n"
                + "`!start dungeon 10 lisa` mit Freund Lisa (ihren Namen einmal mit "
                + "Capture-Images.exe aufnehmen). Mehrere: `wetter,reaper,locke` – wer zuerst annimmt\n"
                + "`!start cathedral 3` kürzester Weg (alle Bosse + Etagenwächter) · `b` jeder "
                + "Wegpunkt · `c` Turbo, so schnell wie möglich\n"
                + "`!start event` Hammer-Event, bis die Hämmer alle sind (`!start event 5` = 5 Felder) · "
                + "`!settings pass gold` / `silber` / `kein` · `!settings lupen 5` Lupen je Feld · "
                + "`!settings kiste holz aus` welche Kisten mitkommen\n"
                + "`!start grabung` (oder `!graben`) Titan-Grabung: Boss x10, alle Spitzhacken, Super-Spitzhacken + Dynamit · "
                + "`!titan 5000` hoechstens 5000 Spitzhacken · `!titan rangliste` · `!titan drill an`/`aus` · "
                + "`!settings eventautomatik titan` / `hammer` welches Event der Kreislauf spielt\n"
                + "`!stigmas loeschen` loescht alle Stigmas mit Level 1-79 ausser den behaltenen Sets (Standard Bull, "
                + "Dragon, Wolf) (`!stigmas loeschen probe` = Probelauf; Spiel auf Englisch) · `!stigmas leveln` Last Guardian: "
                + "Bull-Stigmas ansehen, bessere einsetzen, gute hochleveln, ein Bild je Sorte · `!stigmas leveln <Titan-Name>` "
                + "anderer Titan (`... 100` = danach Level 100) · `!stigmas hundert` eingesetzte Richtung Level 100 (Strahlen + "
                + "Stones of Creation, kein Probelauf) · `!stigmas einstellen` Formular + eigene Rangliste\n"
                + "🛡️ **Eigenes Team** (Kaserne, Held, Schirmherren -- einmal, dann alle 3 Tage von selbst)\n"
                + "`!team` was der Bot weiss (Squad power, Einheiten, Opfereinheit, Held, Schirmherren, Punkte)\n"
                + "`!team scan` jetzt ansehen (nach dem laufenden Run)  ·  `!team an` / `aus` die 3-Tage-Automatik\n"
                + "`!team punkte aus` / `an` dein Team als Messlatte in der Arena-Siegchance\n"
                + "`!team held 12 4` Punkte fuer gute Haupt-/Nebenfaehigkeit des Helden  ·  `!team name Autor` dein Spielername\n"
                + "`!start lighthouse` / `colosseum` / `titantower` / `clanstore` -- laufen auch von selbst "
                + "(`!settings leuchtturm aus` usw.) · `!settings eventautomatik an` = `!start kathedrale` "
                + "im Kreis Kathedrale -> Event -> Verlies · `!settings meldungen 10` Discord nur alle 10 Runs\n"
                + "`!stop` hält an & leert die Warteschlange · `!pause` / `!resume` · `!burg` spielt "
                + "den Durchlauf zu Ende und geht in die Burg · `!notaus` hält sofort an\n\n"
                + "🏟️ **Arena**  `!start arena 5` fünf Turniere, je ein Ticket · `!start arena 5 aepfel` "
                + "mit Äpfeln · einmal den eigenen Namen aufnehmen (Capture-Images.exe, \"Arena: dein Name\") · "
                + "`!notizen` Arena-Notizblock als Datei · `!notiz Max hat 4 Bauarbeiter` eigene Notiz "
                + "(`!notiz Max weg` löscht sie) · `!liste` Gegner des Turniers · `!arenareserve 10` "
                + "10 Tickets aufheben · `!arenabericht aus` keine Zeile nach jedem Turnier · `!settings bezahlen arena food` · "
                + "`!glyphen` Zeichen in Spielernamen ohne Buchstaben · `!glyphe 17 a` beschriftet eines\n\n"
                + "📋 **Warteschlange**  `!after dungeon 20` hängt hinten an · `!queue` zeigt sie · "
                + "`!queue clear` / `!queue del 2`\n\n"
                + "⚔️ **Clan-Krieg & Aufstellung**  `!clanwar on` stellt von selbst auf "
                + "(" + Kriegsplan.zeitenText("/") + "), `!clanwar off` lässt ihn in Ruhe · `!settings` je Modus "
                + "Deploy the best oder Previous Squad\n\n"
                + "👀 **Nachsehen**  `!status` · `!stats` (`!stats 10` alle 10 Min, `!stats off`) · "
                + "`!specs` Rechnerwerte (`!specs 10`) · `!screenshot` · `!wach 5` alle 5 Min ein Foto (`!wach aus`) · `!live` Bild, das sich 5 Min "
                + "lang alle 2 s erneuert (`!live stop`) · `!log` Fehlerbericht als Datei\n\n"
                + "⏰ **Von selbst aufhören**  `!ende 06:30` nach dem Durchlauf aufhören, PC bleibt an · "
                + "`!off 06:30` / `!off 15 uhr` / `!off 3pm` danach PC aus · `!off now` sofort (zweimal tippen) · "
                + "`!off delete` zurück · `!pc off` / `!pc restart` / `!pc cancel`\n\n"
                + "⭐ **Praktisch**  `!shortcut` eigener Kurzbefehl, z.B. `!maus 25` · `!shortcut liste` · "
                + "`!update` was ist neu · `!mute` / `!unmute` ohne Benachrichtigung · `!clear` Kanal "
                + "leeren (fragt vorher) · `!english` / `!deutsch` · `!text an`, wenn Antworten leer aussehen\n\n"
                + "_Hängt der Bot, gehen `!notaus`, `!off now` und `!pc off` nach etwa einer Minute "
                + "trotzdem (Notfall-Wache). Groß/klein und ein Leerzeichen nach dem ! sind egal._"),
                DiscordClient.FARBE_INFO);
    }

    /**
     * "!ablauf" / "!routine" (Knopf "Loop" im Steuerfenster, 16.10.): derselbe Start wie frueher der Haken
     * "Event-Kreislauf" im Freunde-Steuerfenster ("!start cathedral ... c eventloop") -- Kathedrale im Kreislauf mit
     * dem Event, Leuchtturm/Kolosseum/Clan-Krieg nebenbei wie eingestellt. Ohne Zahl: immer wieder, bis !stop.
     * "!ablauf ohne" = nur Kathedrale, ohne Event (auch bis !stop).
     */
    private void ablauf(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        // Wie im Hauptbot (Pruefung 11.10.): "!ablauf aus" darf nicht den ganzen Ablauf starten.
        if (r.matches("(aus|off|stop|stopp|ende|end|halt|beenden|anhalten|abbrechen|cancel)\\b.*")) {
            client.send(Sprache.t("To stop the bot: `!stop`. `!routine` alone starts the event loop.",
                                  "Zum Anhalten: `!stop`. `!ablauf` allein startet den Event-Kreislauf."));
            return;
        }
        boolean ohneEvent = r.contains("ohne") || r.contains("noevent") || r.contains("no event") || r.contains("without");
        starten(ohneEvent ? "cathedral " + BIS_LEER + " c noevent" : "cathedral c eventloop");
    }

    private void starten(String rest) {
        // Event-Ablauf (29.09.): "!start eventablauf" = Kathedrale im Kreislauf (Schluessel ->
        // Kisten -> Hammer-Event -> Verlies), "ohneevent" = nur Kathedrale. Ohne Wort gilt
        // der Schalter unter !settings.
        Boolean kreislauf = null;
        de.hustlebot.core.Bezahlung.Art bezahlArt = null;
        // Portal-Plan (Autor 23.10.): "!start portal bis 135 farm 80:2mio,135:2mio,130:1,5mio" -- vor allem anderen
        // abtrennen, damit die Zahlen nicht als Rundenzahl oder Mitspieler gelesen werden.
        if (rest != null && rest.toLowerCase(java.util.Locale.ROOT).contains("portal")) {
            rest = de.hustlebot.bot.PortalMode.planAusText(rest);
        }
        StringBuilder uebrig = new StringBuilder();
        for (String w : (rest == null ? "" : rest.trim()).split("\\s+")) {
            String k = w.toLowerCase(java.util.Locale.ROOT);
            // Bezahlart fuer diesen Auftrag (29.09.): tickets | food | tickets+food | all/alles | food+chests
            // (Mia 21.10.: food+chests = Nahrung, dann Nahrungskisten, nie Tickets)
            if (k.matches("tickets|keys|schluessel|schlüssel|food|nahrung|essen|tickets\\+food|alles|all|food\\+chests?|food\\+kisten|nahrung\\+kisten|essen\\+kisten")) {
                bezahlArt = de.hustlebot.core.Bezahlung.ausWort(k.equals("all") ? "alles" : k.matches("keys|schluessel|schlüssel") ? "tickets"
                        : k.matches("nahrung|essen") ? "food" : k);
                continue;
            }
            if (k.matches("eventablauf|event-ablauf|eventloop|event-loop|kreislauf")) {
                kreislauf = Boolean.TRUE;
                w = "cathedral";
            } else if (k.matches("ohneevent|ohne-event|noevent|no-event|nurkathedrale|onlycathedral")) {
                kreislauf = Boolean.FALSE;
                continue;
            }
            uebrig.append(uebrig.length() == 0 ? "" : " ").append(w);
        }
        rest = uebrig.toString();
        FreundAngabe freundAngabe = freundAbtrennen(rest);
        if (!freundAngabe.genannt()) freundAngabe = freundOhneWort(rest);
        rest = freundAngabe.rest();
        String modus = modusAus(rest);
        if (modus == null) {
            client.send(Sprache.t("Which mode? Try `!start dungeon 5`, `!start cathedral 3`, `!start arena 5` or `!start clanwar`.", "Welcher Modus? Versuch `!start dungeon 5`, `!start cathedral 3`, "
                    + "`!start arena 5` oder `!start clanwar`."));
            return;
        }
        // 22.10. nachts (Befehls-Test): "!tower" waehrend der Titanen-Turm lief -- der Motor lehnte ab, und dabei wurde die
        // Warteschlange geleert (Arena x20 war weg). Laeuft der Bot, nichts anfassen, nur Bescheid sagen.
        // Code-Pruefung 22.10.: PAUSIERT genauso -- der Motor lehnt dort jeden Start ab, und vorher war dabei die
        // Warteschlange des pausierten Auftrags (z. B. der Kreislauf) geleert.
        if (engine.state() == BotEngine.State.LAEUFT || engine.state() == BotEngine.State.PAUSIERT) {
            client.sendCard(Sprache.t("Could not start", "Konnte nicht starten"),
                    "**" + modusName(modus) + Sprache.t("** did not start.\n", "** ist nicht losgelaufen.\n")
                    + (engine.state() == BotEngine.State.PAUSIERT
                       ? Sprache.t("The bot is paused. Press **F10** to continue -- or press Stop first, then start again.",
                                   "Der Bot ist pausiert. **F10** druecken zum Weitermachen -- oder erst Stopp, dann neu starten.")
                       : Sprache.t("The bot is playing right now. Stop it first (`!stop`), or queue this: `!after " + modus + "`.",
                                   "Der Bot spielt gerade. Erst anhalten (`!stop`) oder hinten anhaengen: `!after " + modus + "`.")),
                    DiscordClient.FARBE_FEHLER);
            return;
        }
        String freund = freundPruefen(freundAngabe, modus, "start");
        if (freund == null) return;
        // Arena mit Aepfeln (Hauptbot 25.09.): "!start arena 10 aepfel" / "apples" / "futter" ... wie "food".
        if (bezahlArt == null && "arena".equals(modus) && mitEssen(rest)) {
            bezahlArt = de.hustlebot.core.Bezahlung.ausWort("food");
        }
        // Titan-Grabung ohne ihre Bilder: gar nicht erst anfangen (Hauptbot 04.10.) -- nichts veraendern.
        if ("grabung".equals(modus) && titanVorlagenFehlen()) return;
        if ("arena".equals(modus) && arenaNameFehlt()) return;

        de.hustlebot.core.Eventautomatik.fuerAuftrag(kreislauf);
        de.hustlebot.core.Bezahlung.auftragBeginnt(bezahlArt);
        // Frischer Start: die Zaehler fuer !stats fangen wieder bei null an.
        de.hustlebot.core.Energieplan.zaehlerZuruecksetzen();
        de.hustlebot.core.Laufzaehler.zuruecksetzen();
        int runs = zahlAus(rest);
        if (runs <= 0) runs = "clanwar".equals(modus) ? 1 : 1;
        // Das Hammer-Event ohne Zahl: bis die Haemmer alle sind (es hoert von selbst auf).
        if (laeuftBisLeer(modus) && zahlAus(rest) <= 0) runs = BIS_LEER;

        // Autor, 11.10.: "Nicht zuerst in die Karte, sondern immer zuerst in die Settings ..." -- faelliger Rundgang zuerst
        // (wie im Hauptbot): ist das eigene Team dran (erster Start, danach alle 3 Tage), kommt es ganz nach vorn.
        boolean teamZuerst = !"teamscan".equals(modus) && MeinTeam.scanFaellig() && teamVorlagenDa();
        synchronized (kette) {
            kette.clear();
            if (teamZuerst) {
                kette.add(new Auftrag("teamscan", 1, "c", "", false));
                teamAngestossenUm = System.currentTimeMillis();
            }
            kette.add(new Auftrag(modus, runs, weg(rest), freund,
                    !freund.isEmpty() && freundAngabe.warten()));
        }
        if (teamZuerst) {
            client.sendCard(Sprache.t("First: your team", "Erst: dein Team"),
                    Sprache.t("Game settings (English, HD off, V Sync off), then your units with pictures and points -- then ",
                              "Spiel-Einstellungen (English, HD aus, V Sync aus), dann deine Einheiten mit Bildern und Punkten -- danach ")
                    + modusName(modus) + ".", DiscordClient.FARBE_START);
        }
        naechstenStarten(true);
    }

    private void anhaengen(String rest) {
        FreundAngabe freundAngabe = freundAbtrennen(rest);
        if (!freundAngabe.genannt()) freundAngabe = freundOhneWort(rest);
        rest = freundAngabe.rest();
        String modus = modusAus(rest);
        if (modus == null || "clanwar".equals(modus)) {
            client.send(Sprache.t("Which mode? The clan war is queued by itself. Try `!after dungeon 20`.",
                    "Welcher Modus? Der Clan-Krieg reiht sich von selbst ein. Versuch `!after dungeon 20`."));
            return;
        }
        // Code-Review 23.10.: "!after portal bis 135 farm ..." machte aus "135" die Rundenzahl des alten Level-80-Autokampfs.
        if ("portal".equals(modus) && rest.matches("(?is).*\\b(bis|farm)\\b.*")) {
            client.send(Sprache.t("A portal plan (bis/farm) only works with `!start portal ...`.",
                    "Ein Portal-Plan (bis/farm) geht nur mit `!start portal ...`."));
            return;
        }
        String freund = freundPruefen(freundAngabe, modus, "after");
        if (freund == null) return;
        if ("grabung".equals(modus) && titanVorlagenFehlen()) return;
        if ("arena".equals(modus) && arenaNameFehlt()) return;
        int runs = Math.max(1, zahlAus(rest));
        if (laeuftBisLeer(modus) && zahlAus(rest) <= 0) runs = BIS_LEER;

        synchronized (kette) {
            if (kette.size() >= 10) {
                client.send(Sprache.t("The queue is full (ten entries).", "Die Warteschlange ist voll (zehn Einträge)."));
                return;
            }
            kette.add(new Auftrag(modus, runs, weg(rest), freund,
                    !freund.isEmpty() && freundAngabe.warten()));
        }
        client.sendCard(Sprache.t("Queued", "In der Warteschlange"), "**" + modusName(modus) + "** x" + runs
                + (freund.isEmpty() ? "" : Sprache.t(" with ", " mit ") + freund)
                + (!freund.isEmpty() && freundAngabe.warten() ? Sprache.t(" (waits for the invitation)", " (wartet auf die Einladung)") : "")
                + Sprache.t(" runs as soon as the current one is done.", " läuft, sobald das Laufende fertig ist."),
                DiscordClient.FARBE_INFO);

        if (engine.state() != BotEngine.State.LAEUFT
                && engine.state() != BotEngine.State.PAUSIERT) {
            naechstenStarten(true);
        }
    }

    // ------------------------------------------------------------------
    //  Titan-Grabung und Stigmas (aus dem Hauptbot, Freunde-Update 10.10.)
    // ------------------------------------------------------------------

    /**
     * Titan-Grabung: fehlen Pflicht-Bilder (ev_titan_*), eine klare Karte schicken und true liefern --
     * dann wird nicht gestartet. Die Bilder kommen mit dem Update (Ordner templates\gelernt\titan-event).
     */
    private boolean titanVorlagenFehlen() {
        var m = engine.context().matcher();
        var pflicht = de.hustlebot.bot.TitanEventMode.fehlendePflicht(m);
        if (pflicht.isEmpty()) return false;
        var wichtig = de.hustlebot.bot.TitanEventMode.fehlendeWichtige(m);
        client.sendCard(Sprache.t("Titan excavation: pictures missing", "Titan-Grabung: Bilder fehlen"),
                Sprache.t("Not started. These pictures are still missing:\n", "Nicht gestartet. Es fehlen noch diese Bilder:\n")
                + "`" + String.join("`, `", pflicht) + "`"
                + (wichtig.isEmpty() ? "" : Sprache.t("\n\nAlso recommended: ", "\n\nAusserdem empfohlen: ")
                        + "`" + String.join("`, `", wichtig) + "`")
                + Sprache.t("\n\nThey come with the update (folder templates\\gelernt\\titan-event). Please install the "
                            + "update again.",
                            "\n\nSie kommen mit dem Update (Ordner templates\\gelernt\\titan-event). Bitte das Update noch "
                            + "einmal einspielen."),
                DiscordClient.FARBE_WARNUNG);
        Log.warn("Titan-Event", Sprache.t("Titan excavation not started -- pictures are missing: " + String.join(", ", pflicht),
                "Titan-Grabung nicht gestartet -- es fehlen Bilder: " + String.join(", ", pflicht)));
        return true;
    }

    /**
     * "!titan" (Hauptbot 04./09.10.): "!titan start" / "!graben" = nur den Titan-Ablauf (ins Event, Boss X10, alle
     * Spitzhacken, Super-Spitzhacken + Dynamit, Event zu). "!titan 5000" = hoechstens so viele Spitzhacken.
     * "!titan rangliste" = Bild der Rangliste. "!titan drill an|aus" = Drills nach den Spitzhacken mitbenutzen.
     * "!titan" allein zeigt, wie man startet und ob Bilder fehlen. ("!settings titantower" bleibt der Titanen-Turm.)
     */
    private void commandTitan(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
        // Hauptbot 17.10.: "!titan hc on|off" -- HC-Belohnungen gezielt ausgraben (Standard an) oder nur gerade runter.
        if (r.startsWith("hc")) {
            String a = r.substring(2).trim();
            if (istAn(a) || istAus(a)) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("titan-hc.txt"), istAn(a) ? "an\n" : "aus\n");
                } catch (Exception e) {
                    Log.warn("Titan", Sprache.t("titan-hc.txt not written: ", "titan-hc.txt nicht geschrieben: ") + e.getMessage());
                }
            }
            boolean an;
            try {
                an = !de.hustlebot.core.Dateien.text(java.nio.file.Path.of("titan-hc.txt")).trim().toLowerCase(Locale.ROOT).startsWith("aus");
            } catch (Exception e) {
                an = true;   // keine Datei = an
            }
            String stand = an ? Sprache.t("on", "an") : Sprache.t("off", "aus");
            client.sendCard(Sprache.t("Titan event: HC rewards", "Titan-Event: HC-Belohnungen"),
                    Sprache.t("Dig towards HC rewards: **" + stand + "** -- when on, the bot digs the shortest way to every HC it sees "
                                    + "(pickaxes, super pickaxes, dynamite and drills), otherwise straight down (`!titan hc on` / `off`).",
                              "HC-Belohnungen ausgraben: **" + stand + "** -- an: der Bot graebt den kuerzesten Weg zu jedem HC im Bild "
                                    + "(Spitzhacken, Super-Spitzhacken, Dynamit und Drills), aus: gerade runter (`!titan hc an` / `aus`)."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Autor 18.10.: Ziel-Tiefe (titan-ziel.txt) per Knopf -- "!titan ziel 150000" / "!titan ziel aus". Stand die Datei
        // auf 150000 und das Konto war tiefer, ging der Bot ins Event und gleich wieder raus.
        if (r.startsWith("ziel") || r.startsWith("depth") || r.startsWith("target")) {
            String a = r.replaceFirst("^(ziel|depth|target)", "").trim();
            java.nio.file.Path datei = de.hustlebot.core.Ordner.bei("titan-ziel.txt");
            try {
                if (istAus(a) || a.equals("0") || a.equals("none") || a.equals("kein") || a.equals("keins")) {
                    java.nio.file.Files.deleteIfExists(datei);
                } else if (a.matches("[0-9][0-9 .,]{0,12}") && a.replaceAll("[^0-9]", "").length() <= 9) {
                    java.nio.file.Files.writeString(datei, a.replaceAll("[^0-9]", "") + "\n");
                }
            } catch (Exception e) {
                Log.warn("Titan", Sprache.t("titan-ziel.txt not written: ", "titan-ziel.txt nicht geschrieben: ") + e.getMessage());
            }
            long ziel = java.nio.file.Files.exists(datei) ? de.hustlebot.bot.TitanEventMode.zielLesen() : -1;
            client.sendCard(Sprache.t("Titan event: target depth", "Titan-Event: Ziel-Tiefe"),
                    ziel > 0
                            ? Sprache.t("The bot stops digging at **" + String.format(Locale.ROOT, "%,d", ziel) + " m**. No limit: `!titan depth off`.",
                                        "Der Bot hoert bei **" + String.format(Locale.GERMANY, "%,d", ziel) + " m** auf zu graben. Ohne Grenze: `!titan ziel aus`.")
                            : Sprache.t("No target depth -- the bot digs until the pickaxes are used up. Set one: `!titan depth 150000`.",
                                        "Keine Ziel-Tiefe -- der Bot graebt, bis die Spitzhacken aufgebraucht sind. Setzen: `!titan ziel 150000`."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Autor, 09.10. 22:50: Drills nur auf Ansage -- "!titan drill an" / "!titan drill aus".
        if (r.startsWith("drill")) {
            String a = r.substring(5).trim();
            if (istAn(a) || istAus(a)) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("titan-drill.txt"), istAn(a) ? "an\n" : "aus\n");
                } catch (Exception e) {
                    Log.warn("Titan", Sprache.t("titan-drill.txt not written: ", "titan-drill.txt nicht geschrieben: ") + e.getMessage());
                }
            }
            boolean an;
            try {
                String t = de.hustlebot.core.Dateien.text(java.nio.file.Path.of("titan-drill.txt")).trim().toLowerCase(Locale.ROOT);
                an = t.startsWith("an") || t.startsWith("on");
            } catch (Exception e) {
                an = true;   // keine Datei = an (Autor 18.10.)
            }
            String stand = an ? Sprache.t("on", "an") : Sprache.t("off", "aus");
            client.sendCard(Sprache.t("Titan event: drills", "Titan-Event: Drills"),
                    Sprache.t("Drills: **" + stand + "** -- used after pickaxes, dynamite and super pickaxes (`!titan drill on` / `off`).",
                              "Drills: **" + stand + "** -- nach Spitzhacken, Dynamit und Super-Spitzhacken (`!titan drill an` / `aus`)."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // "!titan 5000" / "!graben 5000": hoechstens so viele Spitzhacken. Jede Zahl 1 bis 1.000.000, auch
        // "100.000", "100,000", "100k" (Tausend) oder "1m" (Million) -- Hauptbot 09.10.
        java.util.regex.Matcher zahl = java.util.regex.Pattern.compile("(\\d[\\d.,]*)\\s*(k|tsd|m|mio)?\\b").matcher(r);
        boolean nurZahl = r.matches("[\\d.,]+\\s*(k|tsd|m|mio)?");
        if (nurZahl || r.startsWith("start") || r.startsWith("los") || r.startsWith("go") || r.startsWith("grab")) {
            long grenze = 0;
            if (zahl.find()) {
                String ziffern = zahl.group(1);
                String einheit = zahl.group(2);
                try {
                    if (einheit != null) {
                        double wert = Double.parseDouble(ziffern.replace(',', '.'));
                        grenze = Math.round(wert * (einheit.startsWith("m") ? 1_000_000 : 1_000));
                    } else {
                        grenze = Long.parseLong(ziffern.replaceAll("[.,]", ""));
                    }
                } catch (NumberFormatException e) {
                    grenze = 0;
                }
                grenze = Math.min(1_000_000, Math.max(0, grenze));
            }
            if (titanVorlagenFehlen()) return;
            de.hustlebot.bot.TitanEventMode.grenzeFuerNaechstenStart(grenze);
            if (grenze > 0) client.send(Sprache.t("Titan excavation: at most " + grenze + " pickaxes, then done.",
                    "Titan-Grabung: hoechstens " + grenze + " Spitzhacken, dann fertig."));
            starten("grabung");
            return;
        }
        if (r.startsWith("rang") || r.startsWith("rank") || r.startsWith("platz")) {
            if (!(engine.activeMode() instanceof de.hustlebot.bot.TitanEventMode titan)
                    || (engine.state() != BotEngine.State.LAEUFT && engine.state() != BotEngine.State.PAUSIERT)) {
                client.send(Sprache.t("I can only show the ranking while the titan excavation is running (`!start grabung`).",
                        "Die Rangliste kann ich nur zeigen, waehrend die Titan-Grabung laeuft (`!start grabung`)."));
                return;
            }
            titan.ranglisteSchicken(png -> client.sendFile(Sprache.t("**Titan excavation ranking**", "**Titan-Grabung: Rangliste**")
                    + (titan.ranglisteText().isEmpty() ? "" : "\n" + titan.ranglisteText()),
                    "titan-rangliste.png", png));
            client.send(Sprache.t("OK -- I'll open the ranking at the next quiet moment and send a picture.",
                    "Ok -- ich oeffne die Rangliste an der naechsten ruhigen Stelle und schicke gleich ein Bild."));
            return;
        }
        var m = engine.context().matcher();
        var pflicht = de.hustlebot.bot.TitanEventMode.fehlendePflicht(m);
        var wichtig = de.hustlebot.bot.TitanEventMode.fehlendeWichtige(m);
        client.sendCard(Sprache.t("Titan excavation", "Titan-Grabung"),
                Sprache.t("`!start grabung` (or `!graben`) – into the event, boss (x10), all pickaxes, super pickaxes + dynamite\n"
                        + "`!titan 5000` – at most 5000 pickaxes\n`!titan ranking` – picture of the ranking (while digging)\n"
                        + "`!titan drill on` / `off` – also use the drills after the pickaxes\n"
                        + "`!titan hc on` / `off` – dig the shortest way to every HC reward (on by default) or straight down\n"
                        + "`!settings eventautomatik titan` / `hammer` – which event the loop (`!start eventloop`) plays\n\n",
                        "`!start grabung` (oder `!graben`) – ins Event, Boss (x10), alle Spitzhacken, Super-Spitzhacken + Dynamit\n"
                        + "`!titan 5000` – hoechstens 5000 Spitzhacken\n`!titan rangliste` – Bild der Rangliste (waehrend gegraben wird)\n"
                        + "`!titan drill an` / `aus` – nach den Spitzhacken auch die Drills benutzen\n"
                        + "`!titan hc an` / `aus` – den kuerzesten Weg zu jeder HC-Belohnung graben (Standard an) oder gerade runter\n"
                        + "`!settings eventautomatik titan` / `hammer` – welches Event der Kreislauf (`!start eventablauf`) spielt\n\n")
                + (pflicht.isEmpty()
                        ? Sprache.t("All required pictures are there.", "Alle Pflicht-Bilder sind da.")
                        : Sprache.t("Missing (required): ", "Es fehlen (Pflicht): ") + "`" + String.join("`, `", pflicht) + "`")
                + (wichtig.isEmpty() ? "" : Sprache.t("\nRecommended: ", "\nEmpfohlen: ") + "`" + String.join("`, `", wichtig) + "`"),
                DiscordClient.FARBE_INFO);
    }

    /** Die Woerter hinter "!stigmas" (Hauptbot 11.10.: Deutsch, Englisch, verschiedene Schreibweisen). */
    private static final java.util.List<java.util.List<String>> STIGMA_WOERTER = java.util.List.of(
            java.util.List.of("loeschen", "löschen", "loschen", "delete", "del", "remove", "entfernen", "zerlegen",
                    "zerleg", "disassemble", "wegwerfen", "aufraeumen", "cleanup", "clean"),
            java.util.List.of("leveln", "level", "lvl", "levelup", "hochleveln", "upgrade", "aufwerten", "verbessern",
                    "improve", "equip", "ausruesten", "tauschen", "swap"),
            java.util.List.of("einstellen", "einstellung", "einstellungen", "settings", "setting", "setup", "config",
                    "konfig", "konfiguration", "configure", "optionen", "options", "filter", "filtern", "auswahl",
                    "anpassen", "formular", "form", "menue", "menü", "menu"),
            java.util.List.of("standard", "default", "reset", "zuruecksetzen", "zurücksetzen", "werkseinstellung"),
            java.util.List.of("hundert", "100", "max", "maxen", "maximal", "voll", "full", "levelhundert"),
            java.util.List.of("rangliste", "ranking", "rang", "reihenfolge", "order", "prioritaet", "priorität",
                    "priority", "stufen", "tiers", "liste"),
            java.util.List.of("hilfe", "help", "info", "befehle", "commands", "stand", "status"));
    private static final java.util.Set<String> PROBE_WOERTER = java.util.Set.of("probe", "probelauf", "test", "testlauf",
            "dry", "dryrun", "trocken", "simulation", "simulieren", "nurschauen", "ansehen", "check");
    /** Freunde: Fuell- und Schalterwoerter, die nicht zum Titan-Namen gehoeren ("... ab will", "... alle", "... ohne 100"). */
    private static final java.util.Set<String> STIGMA_FUELLWOERTER = java.util.Set.of("ab", "from", "bei", "beim", "at",
            "fuer", "for", "mit", "with", "titan", "titanen", "the", "der", "den", "dem", "run", "alle", "all", "jede",
            "jedes", "every", "ohne", "no", "without", "nur", "only", "und", "and");
    /** Freunde (Pruefung 11.10.): "ab will", "ohne 100", "markiert" (nur Kacheln ab Level 2), "alles" (loeschen ohne Sets). */
    private static final java.util.Set<String> STIGMA_AB = java.util.Set.of("ab", "from", "bei", "beim", "at");
    private static final java.util.Set<String> STIGMA_OHNE = java.util.Set.of("ohne", "no", "without");
    private static final java.util.Set<String> STIGMA_MARKIERT = java.util.Set.of("markiert", "markierte", "markierten", "marked");
    private static final java.util.Set<String> STIGMA_ALLES = java.util.Set.of("alles", "all", "alle", "everything");

    /** Fuell-, Schalter- oder Befehlswort -- faellt am Anfang und Ende des Titan-Namens weg, mitten drin nicht. */
    private static boolean stigmaSchalter(String n) {
        return STIGMA_FUELLWOERTER.contains(n) || STIGMA_MARKIERT.contains(n) || STIGMA_ALLES.contains(n) || stigmaBefehlsWort(n);
    }

    /** Freunde (Pruefung 11.10.): unbekanntes Wort neben einem bekannten Titan oder unbrauchbarer Name -- nachfragen. */
    private void stigmaNachfragen(String wort, String titanName, boolean bekannt, boolean hundert) {
        String tn = titanName.toLowerCase(java.util.Locale.ROOT);
        client.sendCard("Stigmas", bekannt
                ? Sprache.t("I don't know the word `" + wort + "` -- nothing is started. " + titanName + ": `!stigmas "
                                + (hundert ? "100 " : "level ") + tn + "`" + (hundert ? "" : ", dry run: `!stigmas level " + tn + " test`") + ".",
                        "Das Wort `" + wort + "` kenne ich nicht -- es wird nichts gestartet. " + titanName + ": `!stigmas "
                                + (hundert ? "hundert " : "leveln ") + tn + "`" + (hundert ? "" : ", Probelauf: `!stigmas leveln " + tn + " probe`") + ".")
                : Sprache.t("I can't use the titan name `" + wort + "` (at least 3 letters) -- nothing is started. Write the name "
                                + "as it stands above the titan in the game (English), e.g. `!stigmas level frost giant`.",
                        "Mit dem Titan-Namen `" + wort + "` kann ich nichts anfangen (mindestens 3 Buchstaben) -- es wird nichts "
                                + "gestartet. Den Namen so schreiben, wie er im Spiel ueber dem Titan steht (Englisch), z. B. "
                                + "`!stigmas leveln frost giant`."),
                DiscordClient.FARBE_WARNUNG);
    }

    private static boolean stigmaProbeWort(String wort) {
        String n = Befehlsnamen.normal(wort);
        return PROBE_WOERTER.contains(n) || n.startsWith("probe") || n.startsWith("test") || n.startsWith("dry");
    }

    /** Ein Wort aus STIGMA_WOERTER (genau, nach normal()) -- gehoert nicht zum Titan-Namen. */
    private static boolean stigmaBefehlsWort(String wort) {
        String n = Befehlsnamen.normal(wort);
        for (java.util.List<String> g : STIGMA_WOERTER) {
            for (String a : g) if (Befehlsnamen.normal(a).equals(n)) return true;
        }
        return false;
    }

    /** Laeuft gerade etwas? Dann Hinweis und false. */
    private boolean stigmaFrei() {
        if (engine.state() == BotEngine.State.LAEUFT || engine.state() == BotEngine.State.PAUSIERT) {
            client.send(Sprache.t("The bot is busy -- stop it first (`!stop`).",
                    "Der Bot arbeitet gerade -- erst `!stop`, dann noch einmal."));
            return false;
        }
        return true;
    }

    /** Ueber die Warteschlange starten wie "!start" (StigmaMode.naechsterStart ist schon gesetzt). */
    private void stigmaStarten() {
        synchronized (kette) {
            kette.clear();
            kette.add(new Auftrag("stigmas", 1, "a", "", false));
        }
        naechstenStarten(true);
    }

    /**
     * "!stigmas loeschen" (Hauptbot 10.10.): alle Stigmas mit Level 1-79 zerlegen, ausser den Sets, die
     * behalten werden (Standard Bull, Dragon, Wolf). "!stigmas leveln" (Hauptbot 11.10.): Bull-Stigmas ansehen, bessere
     * einsetzen, gute hochleveln; nach jeder Sorte kommt ein Bild. "!stigmas hundert": die eingesetzten so weit wie
     * moeglich Richtung Level 100. "!stigmas einstellen": Formular fuer Sorten, Hochleveln, Tauschen, Behalten, dazu die
     * eigene Rangliste. Hinter jedem "probe"/"test"/"dry run" fuer einen Probelauf. Laeuft ueber die Warteschlange.
     *
     * FREUNDE (11.10.): Ihre Titanen sind nicht Autors. Ohne Titan gilt der Last Guardian. Medusa, Thundersaurus,
     * Torturer und Keeper of Stars mit Autors festen Regeln, jeder andere Name ("!stigmas leveln frost giant") als
     * eigener Titan mit den Einstellungen des Freundes. Pruefung 11.10.: bei JEDEM Titan wird jede Kachel angesehen
     * ("markiert" = nur die ab Level 2) und Level 100 (Strahlen + Stones of Creation) nur mit "100"/"hundert" gemacht --
     * vorher kaufte "!stigmas leveln medusa" ungefragt Raenge. Level 100 hat keinen Probelauf.
     */
    private void commandStigmas(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
        String[] worte = r.isEmpty() ? new String[0] : r.split("[\\s,]+");
        String was = worte.length == 0 ? null : Befehlsnamen.wahl(worte[0], STIGMA_WOERTER);
        boolean probe = r.replace(" ", "").contains("dryrun");
        for (String w : worte) {
            if (stigmaProbeWort(w)) probe = true;
        }
        String behalten = de.hustlebot.core.Stigmaplan.behalten().isEmpty() ? Sprache.t("nothing", "nichts")
                : String.join(", ", de.hustlebot.core.Stigmaplan.behalten().stream()
                        .map(de.hustlebot.core.Stigmaplan::gross).sorted().toList());
        if ("loeschen".equals(was)) {
            // Hauptbot-Pruefung 11.10.: "!stigmas loeschen prbe" (Tippfehler) startete echtes Loeschen. Jetzt: jedes
            // weitere Wort muss ein Probelauf-Wort sein, sonst wird nachgefragt statt geloescht.
            boolean allesLoeschen = false;
            for (int i = 1; i < worte.length; i++) {
                String n = Befehlsnamen.normal(worte[i]);
                if (STIGMA_ALLES.contains(n)) {
                    allesLoeschen = true;
                    continue;
                }
                if (!n.isEmpty() && !stigmaProbeWort(n) && !n.equals("run")) {
                    client.sendCard("Stigmas", Sprache.t(
                            "I don't know the word `" + worte[i] + "` -- nothing is deleted. Dry run: `!stigmas delete test`, "
                                    + "really delete: `!stigmas delete`.",
                            "Das Wort `" + worte[i] + "` kenne ich nicht -- es wird nichts geloescht. Probelauf: "
                                    + "`!stigmas loeschen probe`, wirklich loeschen: `!stigmas loeschen`."), DiscordClient.FARBE_WARNUNG);
                    return;
                }
            }
            // Freunde (Pruefung 11.10.): Wird kein Set behalten (Formular ohne Haken, leere Zeile "behalten="), zerlegt der
            // Lauf auch Bull, Dragon und Wolf -- das nur mit ausdruecklichem "alles".
            if (!probe && !allesLoeschen && de.hustlebot.core.Stigmaplan.behalten().isEmpty()) {
                client.sendCard("Stigmas", Sprache.t(
                        "No set is kept (`!stigmas settings`) -- ALL stigmas with level 1-79 would be disassembled, "
                                + "Bull, Dragon and Wolf too. Nothing is deleted. If that is really what you want: `!stigmas delete all`.",
                        "Es wird kein Set behalten (`!stigmas einstellen`) -- dann wuerden ALLE Stigmas mit Level 1-79 "
                                + "zerlegt, auch Bull, Dragon und Wolf. Es wird nichts geloescht. Wenn das wirklich so sein soll: "
                                + "`!stigmas loeschen alles`."), DiscordClient.FARBE_WARNUNG);
                return;
            }
            if (!stigmaFrei()) return;
            de.hustlebot.bot.StigmaMode.naechsterStart(probe);
            client.sendCard("Stigmas", probe
                    ? Sprache.t("Dry run: going through the list, nothing is deleted.",
                                "Probelauf: Ich gehe die Liste durch und loesche nichts.")
                    : Sprache.t("Deleting all stigmas with level 1-79, except: " + behalten + ". "
                                + "The game must be in English.",
                                "Ich loesche alle Stigmas mit Level 1-79, ausser: " + behalten + ". "
                                + "Das Spiel muss auf Englisch stehen."), DiscordClient.FARBE_INFO);
            stigmaStarten();
            return;
        }
        // Titan (Hauptbot 11.10. 03:45: "!stigmas leveln keeper", "... sternenwaechter", "... torturer"). Freunde: alles
        // hinter dem ersten Wort ausser Probe-Woertern und den Schaltern "ab <Sorte>" / "ohne 100" ist der Titan-Name.
        // Pruefung 11.10.: Fuell-, Schalter- und Befehlswoerter ("the", "alle", "100", "markiert") fallen nur am Anfang und
        // am Ende weg -- "lord of the deep" wurde sonst "lord of deep" und nie gefunden. Ein Sortenname gehoert zum Namen,
        // ausser mit "ab" davor oder wenn er allein steht ("!stigmas leveln will").
        java.util.List<String> nameWorte = new java.util.ArrayList<>();
        int startName = -1;
        boolean nachProbe = false;
        for (int i = 1; i < worte.length; i++) {
            String n = Befehlsnamen.normal(worte[i]);
            if (n.isEmpty()) continue;
            if (stigmaProbeWort(n) || (nachProbe && n.equals("run"))) {   // "dry run"
                nachProbe = true;
                continue;
            }
            nachProbe = false;
            String danach = i + 1 < worte.length ? Befehlsnamen.normal(worte[i + 1]) : "";
            int sorte = de.hustlebot.core.Stigmaplan.NAMEN.indexOf(danach);
            if (STIGMA_AB.contains(n) && sorte >= 0) {   // "!stigmas leveln ab will"
                if (startName < 0) startName = sorte;
                i++;
                continue;
            }
            if (STIGMA_OHNE.contains(n) && (danach.equals("100") || danach.equals("hundert"))) {   // "ohne 100"
                i++;
                continue;
            }
            if (n.matches("(ohne|no|without)(100|hundert)")) continue;
            nameWorte.add(n);
        }
        while (!nameWorte.isEmpty() && stigmaSchalter(nameWorte.get(0))) nameWorte.remove(0);
        while (!nameWorte.isEmpty() && stigmaSchalter(nameWorte.get(nameWorte.size() - 1))) nameWorte.remove(nameWorte.size() - 1);
        if (!nameWorte.isEmpty() && de.hustlebot.core.Stigmaplan.NAMEN.containsAll(nameWorte)) {
            if (startName < 0) startName = de.hustlebot.core.Stigmaplan.NAMEN.indexOf(nameWorte.get(0));
            nameWorte.clear();
        }
        // Bekannte Titanen nur an ganzen Woertern (Stigmaplan.titanFund). Bleibt daneben ein unbekanntes Wort uebrig
        // ("!stigmas leveln medusa prob" -- vertipptes "probe"), wird nachgefragt statt echt gestartet.
        de.hustlebot.core.Stigmaplan.Titan titanWahl = null;
        String unbekannt = null;
        var fund = de.hustlebot.core.Stigmaplan.titanFund(nameWorte);
        if (fund != null) {
            titanWahl = fund.titan();
            for (int i = 0; i < nameWorte.size() && unbekannt == null; i++) {
                if (i >= fund.von() && i < fund.bis()) continue;
                String n = nameWorte.get(i);
                int sorte = de.hustlebot.core.Stigmaplan.NAMEN.indexOf(n);
                if (sorte >= 0) {   // "!stigmas leveln medusa will"
                    if (startName < 0) startName = sorte;
                } else if (!stigmaSchalter(n)) {
                    unbekannt = n;
                }
            }
        } else if (!nameWorte.isEmpty()) {
            titanWahl = de.hustlebot.core.Stigmaplan.titanFrei(String.join(" ", nameWorte));
            if (titanWahl == null) unbekannt = String.join(" ", nameWorte);
        }
        String titanKey = titanWahl == null ? "lastguardian" : titanWahl.schluessel();
        boolean freierTitan = de.hustlebot.core.Stigmaplan.frei(titanWahl);
        String titanName = de.hustlebot.core.Stigmaplan.titanFuer(titanKey).anzeige();
        String profil = freierTitan ? Sprache.t(" (own titan name: rated by your settings)",
                " (eigener Titan-Name: bewertet nach deinen Einstellungen)") : "";
        if ("hundert".equals(was)) {
            // Freunde (Pruefung 11.10.): "!stigmas 100 test" startete einen ECHTEN Lauf (Strahlen + Stones of Creation,
            // nicht rueckgaengig zu machen) -- Level 100 hat keinen Probelauf, also gar nicht starten.
            if (probe) {
                client.sendCard("Stigmas", Sprache.t(
                        "Level 100 has no dry run -- it really uses power rays and Stones of Creation. Nothing is started. "
                                + "If it should really start: the same command without `test`.",
                        "Fuer Level 100 gibt es keinen Probelauf -- es werden wirklich Strahlen und Stones of Creation verbraucht. "
                                + "Es wird nichts gestartet. Wenn es wirklich losgehen soll: derselbe Befehl ohne `probe`."),
                        DiscordClient.FARBE_WARNUNG);
                return;
            }
            if (unbekannt != null) {
                stigmaNachfragen(unbekannt, titanName, fund != null, true);
                return;
            }
            if (!stigmaFrei()) return;
            de.hustlebot.bot.StigmaMode.naechsterStart(false, true, 0, titanKey, true, false, true);
            client.sendCard("Stigmas", Sprache.t(titanName + ": bringing the equipped stigmas as close to level 100 as possible "
                                                 + "(power rays + Stones of Creation). The game must be in English.",
                                                 titanName + ": die eingesetzten Stigmas so weit wie moeglich auf Level 100 "
                                                 + "(Strahlen + Stones of Creation). Das Spiel muss auf Englisch stehen."),
                    DiscordClient.FARBE_INFO);
            stigmaStarten();
            return;
        }
        if ("leveln".equals(was)) {
            if (unbekannt != null) {
                stigmaNachfragen(unbekannt, titanName, fund != null, false);
                return;
            }
            if (!stigmaFrei()) return;
            // Freunde (Pruefung 11.10.): Level 100 (Strahlen + Stones of Creation) bei JEDEM Titan nur mit "100"/"hundert"
            // -- vorher machte "!stigmas leveln medusa" es ungefragt. Jede Kachel wird angesehen; "markiert" = nur die ab
            // Level 2 (Autors Weg bei seinen anderen Titanen).
            boolean ohneHundert = r.matches(".*\\b(ohne|no|without)\\s*(hundert|100)\\b.*");
            boolean mitHundert = !ohneHundert && r.matches(".*\\b(hundert|100|max|maximal)\\b.*");
            boolean nurMarkierte = r.matches(".*\\b(markiert|markierte|markierten|marked)\\b.*");
            de.hustlebot.bot.StigmaMode.naechsterStart(probe, true, Math.max(0, startName), titanKey, mitHundert,
                    nurMarkierte, false);
            client.sendCard("Stigmas", (probe
                    ? Sprache.t("Dry run at " + titanName + profil + ": reading and rating Bull stigmas, nothing is changed.",
                                "Probelauf beim " + titanName + profil + ": Bull-Stigmas lesen und bewerten, nichts wird veraendert.")
                    : Sprache.t(titanName + profil + ": checking Bull stigmas, equipping better ones, levelling good ones. "
                                + "After each kind a picture comes here.",
                                titanName + profil + ": Bull-Stigmas pruefen, bessere einsetzen, gute hochleveln. "
                                + "Nach jeder Sorte kommt hier ein Bild.")
                      + (mitHundert ? Sprache.t(" Then level 100: the equipped stigmas as high as possible with power rays and "
                                + "Stones of Creation.", " Danach Level 100: die eingesetzten Stigmas mit Strahlen und Stones of "
                                + "Creation so weit wie moeglich hoch.") : ""))
                    + (nurMarkierte ? Sprache.t(" Only tiles from level 2 (marked).", " Nur Kacheln ab Level 2 (markiert).") : "")
                    + Sprache.t(" The game must be in English.", " Das Spiel muss auf Englisch stehen."), DiscordClient.FARBE_INFO);
            stigmaStarten();
            return;
        }
        if ("standard".equals(was)) {
            de.hustlebot.core.Stigmaplan.standard();
            Log.info("Einstellungen", Sprache.t("Stigmas: back to the standard rules.", "Stigmas: zurueck auf die Standard-Regeln."));
        }
        // Ohne (oder mit unbekanntem) Wort, "einstellen", "hilfe", "standard", "rangliste": Stand und Knoepfe.
        client.sendCardMitKnoepfen(channelId, Sprache.t("Stigmas – settings", "Stigmas – Einstellungen"),
                ("standard".equals(was) ? Sprache.t("Back to the standard rules.\n\n", "Zurueck auf die Standard-Regeln.\n\n") : "")
                + ("rangliste".equals(was) ? stigmaRanglisteText() + "\n\n" : "")
                + de.hustlebot.core.Stigmaplan.beschreibung() + "\n\n"
                + Sprache.t("Bull set. Commands: `!stigmas level` (Last Guardian) · `!stigmas level <titan name>` · "
                            + "`!stigmas level test` · `!stigmas level <titan name> 100` (then level 100) · `!stigmas 100 <titan name>` · "
                            + "`!stigmas delete` · `!stigmas delete test` · "
                            + "`!stigmas settings` · `!stigmas ranking`. Game in English.",
                            "Bull-Set. Befehle: `!stigmas leveln` (Last Guardian) · `!stigmas leveln <Titan-Name>` · "
                            + "`!stigmas leveln probe` · `!stigmas leveln <Titan-Name> 100` (danach Level 100) · "
                            + "`!stigmas hundert <Titan-Name>` · `!stigmas loeschen` · "
                            + "`!stigmas loeschen probe` · `!stigmas einstellen` · `!stigmas rangliste`. Spiel auf Englisch."),
                DiscordClient.FARBE_INFO,
                java.util.List.of(
                        new String[]{Sprache.t("⚙️ Change settings", "⚙️ Einstellen"), "setformular:stigmas"},
                        new String[]{Sprache.t("📝 Ranking", "📝 Rangliste"), "setformular:stigmarang"},
                        new String[]{Sprache.t("▶️ Level now", "▶️ Leveln starten"), "!stigmas leveln"},
                        new String[]{Sprache.t("🔍 Level – dry run", "🔍 Leveln – Probelauf"), "!stigmas leveln probe"},
                        new String[]{Sprache.t("🗑️ Delete – dry run", "🗑️ Loeschen – Probelauf"), "!stigmas loeschen probe"},
                        new String[]{Sprache.t("↩️ Standard", "↩️ Standard"), "!stigmas standard"}));
    }

    /** Die Rangliste aus "!stigmas einstellen", nummeriert (fuer "!stigmas rangliste" und den Stand nach dem Formular). */
    private static String stigmaRanglisteText() {
        StringBuilder t = new StringBuilder(de.hustlebot.core.Stigmaplan.eigeneRangliste()
                ? Sprache.t("**Own ranking:**\n", "**Eigene Rangliste:**\n") : Sprache.t("**Standard ranking:**\n", "**Standard-Rangliste:**\n"));
        java.util.List<String> zeilen = de.hustlebot.core.Stigmaplan.einstellungsZeilen();
        for (int i = 0; i < zeilen.size(); i++) t.append(i + 1).append(". ").append(zeilen.get(i)).append("\n");
        return t + "" + (zeilen.size() + 1) + Sprache.t(". everything else", ". alles andere");
    }

    // ------------------------------------------------------------------
    //  Arena (aus dem Hauptbot, Freunde 3.0)
    // ------------------------------------------------------------------

    /**
     * Arena ohne das Bild des eigenen Namens (ar_me): nicht starten und sagen, was fehlt. Die Freunde-Fassung
     * startet sonst auch mit fehlenden Bildern -- hier hielte der Bot aber das eigene Schild fuer einen Gegner.
     */
    private boolean arenaNameFehlt() {
        if (engine.context().matcher().liegtBereit(de.hustlebot.bot.ArenaMode.bildEigenerName())) return false;
        client.sendCard(Sprache.t("Arena: your name is missing", "Arena: dein Name fehlt"),
                Sprache.t("Not started. Capture your name once: Capture-Images.exe -> \"Arena: your name\" "
                          + "(your name in your own shield of the pyramid, letters only).",
                          "Nicht gestartet. Einmal deinen Namen aufnehmen: Capture-Images.exe -> \"Arena: dein Name\" "
                          + "(dein Name im eigenen Schild der Pyramide, nur die Buchstaben)."),
                DiscordClient.FARBE_WARNUNG);
        return true;
    }

    /** "!arenareserve 10": so viele Tickets bleiben liegen, 0 = alle spielen (arena-reserve.txt). */
    private void arenaReserve(String rest) {
        String w = rest == null ? "" : rest.trim();
        if (w.matches("\\d{1,4}")) de.hustlebot.bot.ArenaMode.setTicketReserve(Integer.parseInt(w));
        int r = de.hustlebot.bot.ArenaMode.ticketReserve();
        client.sendCard(Sprache.t("Arena ticket reserve: ", "Arena-Ticket-Reserve: ") + r,
                (r > 0 ? Sprache.t("The bot stops as soon as only " + r + " tickets are left.",
                                   "Der Bot hoert auf, sobald nur noch " + r + " Tickets da sind.")
                       : Sprache.t("No reserve -- the bot plays as long as there are tickets.",
                                   "Keine Reserve -- der Bot spielt, solange Tickets da sind."))
                + Sprache.t("\n`!arenareserve 10` · `!arenareserve 0` (off)", "\n`!arenareserve 10` · `!arenareserve 0` (aus)"),
                DiscordClient.FARBE_INFO);
    }

    /** "!thronsaal 8": Stufe des Kontos von Hand setzen (thronsaal.txt); ohne Zahl nur anzeigen. */
    private void thronsaal(String rest) {
        String w = rest == null ? "" : rest.trim();
        String konto = de.hustlebot.core.Konten.aktiv();
        if (konto.isBlank()) konto = de.hustlebot.bot.MeinTeam.spielername();
        if (w.matches("\\d{1,2}")) de.hustlebot.core.Thronsaal.vonHandSetzen(konto, Integer.parseInt(w));
        int stufe = de.hustlebot.core.Thronsaal.stufe(konto);
        if (stufe > 0) de.hustlebot.core.Thronsaal.aktuellSetzen(stufe);
        boolean einfach = de.hustlebot.bot.ArenaMode.einfacheStrategie();
        String wer = konto.isBlank() ? Sprache.t("(account name not known yet)", "(Kontoname noch unbekannt)") : konto;
        client.sendCard(Sprache.t("Throne room: ", "Thronsaal: ") + (stufe > 0 ? stufe : "?") + " -- " + wer,
                Sprache.t("Arena strategy: " + (einfach ? "simple (only the own record per opponent)" : "full (power, sets, checks)") + ".\n"
                                + "Modes this level does not have yet are skipped (e.g. Dungeon from 9).\n`!throneroom 8` sets the level.",
                        "Arena-Strategie: " + (einfach ? "einfach (nur die eigene Bilanz je Gegner)" : "voll (Kraft, Sets, Pruefungen)") + ".\n"
                                + "Modi, die es auf dieser Stufe noch nicht gibt, werden uebersprungen (z. B. Verlies ab 9).\n`!thronsaal 8` setzt die Stufe."),
                DiscordClient.FARBE_INFO);
    }

    /** "!arenakathedrale an/aus": Kathedrale in der Arena-Wartezeit (arena-kathedrale.txt). */
    private void arenaKathedraleSchalter(String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        var schalter = de.hustlebot.bot.ArenaMode.KATHEDRALE_AUSFLUG;
        if (de.hustlebot.core.Schalterdatei.istAn(w)) schalter.setzen(true, Sprache.t("set with !arenacathedral on", "gesetzt mit !arenakathedrale an"));
        else if (de.hustlebot.core.Schalterdatei.istAus(w)) schalter.setzen(false, Sprache.t("set with !arenacathedral off", "gesetzt mit !arenakathedrale aus"));
        client.sendCard(Sprache.t("Cathedral in the arena waiting time: ", "Kathedrale in der Arena-Wartezeit: ") + anAus(schalter.lesen()),
                Sprache.t("After its own attack (stages 1-4) the bot closes the tournament window, plays the cathedral and is back in time "
                                + "for the next stage. The cathedral journey stays open in between.\n`!arenacathedral on` · `!arenacathedral off`",
                        "Nach dem eigenen Angriff (Stage 1-4) schliesst der Bot das Turnierfenster, spielt Kathedrale und ist rechtzeitig "
                                + "zur naechsten Stage zurueck. Die Kathedralen-Reise bleibt dazwischen offen.\n`!arenakathedrale an` · `!arenakathedrale aus`"),
                DiscordClient.FARBE_INFO);
    }

    /** "!arenabericht an/aus": nach jedem Turnier eine kurze Zeile (arenabericht.txt). */
    private void arenaBerichtSchalter(String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        var schalter = de.hustlebot.bot.ArenaMode.BERICHT;
        if (de.hustlebot.core.Schalterdatei.istAn(w)) schalter.setzen(true, Sprache.t("set with !arenareport on", "gesetzt mit !arenabericht an"));
        else if (de.hustlebot.core.Schalterdatei.istAus(w)) schalter.setzen(false, Sprache.t("set with !arenareport off", "gesetzt mit !arenabericht aus"));
        client.sendCard(Sprache.t("Arena report: ", "Arena-Bericht: ") + anAus(schalter.lesen()),
                Sprache.t("After every tournament a short line: place, points, arena time, waiting time.\n"
                          + "`!arenareport on` · `!arenareport off`",
                          "Nach jedem Turnier kommt eine kurze Zeile: Platz, Punkte, Dauer der Arena, Wartezeit.\n"
                          + "`!arenabericht an` · `!arenabericht aus`"),
                DiscordClient.FARBE_INFO);
    }

    /** "!notizen": der Arena-Notizblock als Datei (im Hauptbot zusaetzlich als Fenster am Rechner). */
    private void notizen() {
        java.nio.file.Path datei = de.hustlebot.core.Gegnerbuch.NOTIZBLOCK;
        try {
            if (!java.nio.file.Files.exists(datei)) {
                client.sendCard(Sprache.t("Notebook", "Notizblock"),
                        Sprache.t("Still empty -- it fills up after the first arena fight.",
                                  "Noch leer -- er fuellt sich nach dem ersten Arena-Kampf."),
                        DiscordClient.FARBE_INFO);
                return;
            }
            client.sendFile(Sprache.t("Arena notebook:", "Arena-Notizblock:"),
                    "arena-notizblock.txt", java.nio.file.Files.readAllBytes(datei));
        } catch (Exception e) {
            client.sendCard(Sprache.t("Notebook", "Notizblock"),
                    Sprache.t("Could not read the notebook: ", "Notizblock nicht lesbar: ") + e.getMessage(),
                    DiscordClient.FARBE_WARNUNG);
        }
    }

    /**
     * "!notiz Rina hat 4 Bauarbeiter": eigene Notiz zu einem Arena-Gegner (ersetzt die alte),
     * "!notiz Rina" zeigt sie, "!notiz Rina weg" loescht sie. rest behaelt die Grossschreibung.
     */
    private void notiz(String kanal, String rest) {
        String ziel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        rest = rest == null ? "" : rest.trim();
        String titel = Sprache.t("Note", "Notiz");
        if (rest.isEmpty()) {
            client.sendCardTo(ziel, titel, Sprache.t(
                    "`!note Max has 4 builders` saves a note on Max (replaces the old one).\n"
                    + "`!note Max` shows it, `!note Max off` deletes it.\n"
                    + "Names with spaces in quotes: `!note \"Dark Knight\" text`.",
                    "`!notiz Max hat 4 Bauarbeiter` speichert eine Notiz zu Max (ersetzt die alte).\n"
                    + "`!notiz Max` zeigt sie, `!notiz Max weg` loescht sie.\n"
                    + "Namen mit Leerzeichen in Anfuehrungszeichen: `!notiz \"Dark Knight\" Text`."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        var buch = de.hustlebot.core.Gegnerbuch.gemeinsam();
        String name, text;
        if (rest.startsWith("\"") && rest.indexOf('"', 1) > 1) {
            int ende = rest.indexOf('"', 1);
            name = rest.substring(1, ende).trim();
            text = rest.substring(ende + 1).trim();
        } else {
            // Bekannte Namen zuerst -- "Not iB4an" hat ein Leerzeichen.
            name = buch.bekannterNameAm(rest);
            if (name.isEmpty()) {
                int sp = rest.indexOf(' ');
                name = sp > 0 ? rest.substring(0, sp) : rest;
            }
            text = rest.substring(Math.min(rest.length(), name.length())).trim();
        }
        var vorher = buch.mitNamen(name);
        if (text.isEmpty()) {
            String stand = vorher.stream().filter(n -> !n.notiz().isBlank())
                    .map(n -> "**" + n.name() + "**" + (n.clan().isBlank() ? "" : " (" + n.clan() + ")") + ": " + n.notiz())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            client.sendCardTo(ziel, titel, stand.isEmpty()
                    ? Sprache.t("No note on " + name + " yet.", "Zu " + name + " steht noch keine Notiz.")
                    : stand, DiscordClient.FARBE_INFO);
            return;
        }
        if (text.toLowerCase(Locale.ROOT).matches("weg|l(oe|ö)schen|delete|off|aus|entfernen|-")) {
            int n = buch.setzeNotiz(name, "");
            client.sendCardTo(ziel, titel, n > 0
                    ? Sprache.t("Note on " + name + " deleted.", "Notiz zu " + name + " geloescht.")
                    : Sprache.t(name + " is not in the notebook.", name + " steht nicht im Notizblock."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        int n = buch.setzeNotiz(name, text);
        String zusatz = vorher.isEmpty()
                ? Sprache.t("\n(" + name + " has not come up yet -- the note waits until the bot meets them.)",
                            "\n(" + name + " kam noch nicht vor -- die Notiz wartet, bis der Bot ihn trifft.)")
                : n > 1 ? Sprache.t("\n(Applies to " + n + " entries with this name -- different clans.)",
                                    "\n(Gilt fuer " + n + " Eintraege mit diesem Namen -- verschiedene Clans.)")
                        : "";
        client.sendCardTo(ziel, titel,
                Sprache.t("Saved for **" + name + "**: " + text, "Gespeichert zu **" + name + "**: " + text) + zusatz,
                DiscordClient.FARBE_ERFOLG);
    }

    /** "!liste": die Arena-Gegner des laufenden (oder letzten) Turniers als Datei, darunter der Notizblock. */
    private void arenaListe(String kanal) {
        String ziel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        try {
            java.nio.file.Path liste = de.hustlebot.bot.ArenaMode.LISTE;
            java.nio.file.Path buchDatei = de.hustlebot.core.Gegnerbuch.NOTIZBLOCK;
            StringBuilder sb = new StringBuilder();
            if (java.nio.file.Files.exists(liste)) {
                sb.append(de.hustlebot.core.Dateien.text(liste));
            } else {
                sb.append(Sprache.t("No arena list yet -- it is written during a tournament.\n",
                        "Noch keine Arena-Liste -- sie entsteht waehrend eines Turniers.\n"));
            }
            if (java.nio.file.Files.exists(buchDatei)) {
                sb.append("\n==============================================================\n")
                  .append(Sprache.t("Notebook (all tournaments)\n", "Notizblock (alle Turniere)\n"))
                  .append(de.hustlebot.core.Dateien.text(buchDatei));
            }
            client.sendFile(Sprache.t("Arena opponent list:", "Arena-Gegnerliste:"),
                    "arena-liste.txt", sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            client.sendCardTo(ziel, Sprache.t("List", "Liste"),
                    Sprache.t("Could not read the list: ", "Liste nicht lesbar: ") + e.getMessage(),
                    DiscordClient.FARBE_WARNUNG);
        }
    }

    // Die Arena-Pause (Kathedralen-Run waehrend der Arena-Wartezeit, 30.09.) ist seit 11.10. weg:
    // Autor: "Niemals waehrend dem Warten auf Arena [Kathedrale spielen], einfach warten." Einschuebe
    // (Leuchtturm, Turm, Clan-Laden) warten jetzt, bis das Turnier zu Ende ist (BotEngine/ArenaMode).

    /** Einschub (Leuchtturm, Turm, Clan-Laden) waehrend der Arena: das laufende Glied und der Rest dahinter. */
    private Auftrag arenaEinschubLaufend, arenaEinschubRest;
    private int arenaEinschubFertig;

    /**
     * Die Arena vor dem Einschub ist fertig: den Rest um die seitdem wirklich gespielten Turniere kuerzen
     * (wie der Hauptbot mit restVon/"mehr"). Ohne gespieltes Turnier bleibt er ganz. Aufruf in synchronized (kette).
     */
    private void arenaEinschubNachziehen() {
        var t = engine.tracker();
        int gespielt = t == null ? 0 : Math.max(0, t.completedRuns() - arenaEinschubFertig);
        for (int i = 0; i < kette.size(); i++) {
            Auftrag a = kette.get(i);
            if (a != arenaEinschubRest) continue;
            int runs = a.runs() - gespielt;
            if (runs <= 0) kette.remove(i);
            else if (gespielt > 0) kette.set(i, new Auftrag(a.modus(), runs, a.weg(), a.freund(), a.warten()));
            break;
        }
        arenaEinschubLaufend = arenaEinschubRest = null;
    }

    private void stoppen() {
        synchronized (kette) {
            kette.clear();
        }
        engine.stop(Sprache.t("stopped via Discord", "über Discord gestoppt"));
        client.sendCard(Sprache.t("Stopped", "Angehalten"), Sprache.t("The bot has stopped, the queue is empty.", "Der Bot ist gestoppt, die Warteschlange ist leer."),
                DiscordClient.FARBE_WARNUNG);
    }

    private void pause() {
        if (engine.state() != BotEngine.State.LAEUFT) {
            client.send(Sprache.t("Nothing is running right now.", "Es läuft gerade nichts."));
            return;
        }
        engine.togglePause();
        client.sendCard("Pause", Sprache.t("`!resume` carries on.", "Mit `!resume` geht es weiter."),
                DiscordClient.FARBE_WARNUNG);
    }

    private void weiter() {
        if (engine.state() != BotEngine.State.PAUSIERT) {
            client.send(Sprache.t("The bot is not paused.", "Der Bot ist gar nicht angehalten."));
            return;
        }
        engine.togglePause();
        var t = engine.tracker();
        String stand = t == null ? "" : Sprache.t("Run ", "Durchlauf ") + (t.completedRuns() + 1)
                + Sprache.t(" of ", " von ") + t.targetRuns() + "\n";
        long pause = t == null ? 0 : t.letztePauseSekunden();
        client.sendCard(Sprache.t("Continuing", "Es geht weiter"),
                stand + (pause > 0 ? Sprache.t("The pause (" + dauerText(pause) + ") does not count "
                                               + "towards the average.",
                                               "Die Pause (" + dauerText(pause) + ") zaehlt nicht in "
                                               + "den Schnitt.") : ""),
                DiscordClient.FARBE_ERFOLG);
    }

    private void status() {
        StringBuilder sb = new StringBuilder();
        sb.append(Sprache.t("⚙️ **State:** ", "⚙️ **Zustand:** ")).append(zustandName(engine.state())).append('\n');
        if (engine.statusText() != null && !engine.statusText().isBlank()) {
            sb.append(Sprache.t("**Now:** ", "**Gerade:** ")).append(engine.statusText()).append('\n');
        }
        var t = engine.tracker();
        if (t != null) {
            sb.append(Sprache.t("**Runs:** ", "**Durchläufe:** ")).append(t.completedRuns())
              .append(" / ").append(t.targetRuns()).append('\n');
        }

        synchronized (kette) {
            if (kette.size() > 1) {
                sb.append(Sprache.t("**Waiting:** ", "**Wartet noch:** "));
                for (int i = 1; i < kette.size(); i++) {
                    if (i > 1) sb.append(", ");
                    sb.append(modusName(kette.get(i).modus()))
                      .append(" x").append(kette.get(i).runs());
                }
                sb.append('\n');
            }
        }

        sb.append(Sprache.t("**Clan war clock:** ", "**Kriegsuhr:** "))
          .append(kriegsplan.istSelbsttaetig() ? Sprache.t("on", "an") : Sprache.t("off", "aus")).append('\n');

        // Auftrag 6 (11.10.): das eigene Team im Status (wie im Hauptbot).
        int team = MeinTeam.punkte();
        sb.append(Sprache.t("**Your team:** ", "**Dein Team:** "));
        if (team >= 0) {
            sb.append("**").append(team).append(Sprache.t(" points**", " Punkte**"))
              .append(Sprache.t(" (seen ", " (angesehen ")).append(MeinTeam.stand().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")))
              .append(MeinTeam.opfereinheit().isEmpty() ? "" : Sprache.t(", sacrifice unit ", ", Opfereinheit ") + MeinTeam.opfereinheit()).append(")");
        } else {
            sb.append(Sprache.t("not looked at yet (`!team scan`)", "noch nicht angesehen (`!team scan`)"));
        }
        sb.append('\n');
        sb.append(Sprache.t("**Version:** ", "**Fassung:** ")).append(fassung);

        client.sendCard("Status", sb.toString(), DiscordClient.FARBE_INFO);
    }

    /** Modi mit Aufstell-Wahl in der Freunde-Fassung (seit 3.0 mit Arena). */
    private static final List<String> AUFSTELL_MODI =
            List.of("dungeon", "cathedral", "arena", "clanwar_tower", "clanwar_outpost");

    /**
     * "!settings" / "!einstellungen": je Modus waehlen, ob beim Aufstellen
     * "Deploy the best" oder "Previous Squad" gedrueckt wird.
     *
     *   !settings                      zeigt alle Modi mit Haken beim Aktiven
     *   !settings verlies previous     stellt das Verlies auf Previous Squad
     */
    /** Hammer-Event: Pass und Lupen je Feld, fuer !settings. */
    private static String eventZeile() {
        var p = de.hustlebot.core.Eventautomatik.pass();
        return Sprache.t("🔨 **Hammer event** – pass: **", "🔨 **Hammer-Event** – Pass: **")
                + Sprache.t(p.englisch, p.anzeige) + "** · "
                + Sprache.t("max. magnifiers per field: **", "hoechstens Lupen je Feld: **") + de.hustlebot.core.Eventautomatik.maxLupen() + "**\n"
                + Sprache.t("_No pass: hammers only · silver: + magnifiers · gold: + blue hammers_\n",
                            "_Kein Pass: nur Haemmer · Silber: + Lupen · Gold: + blaue Haemmer_\n")
                + Sprache.t("_Change: `!settings pass gold` · `!settings lupen 5` · start: `!start event`_",
                            "_Aendern: `!settings pass gold` · `!settings lupen 5` · Start: `!start event`_");
    }

    private void einstellungen(String rest) {
        String[] w = rest == null || rest.isBlank() ? new String[0] : rest.trim().split("\\s+");

        // Ohne Zusatz: die kurze Karte mit Knoepfen und Formularen (07.10., wie im Hauptbot).
        if (w.length == 0) {
            einstellungenKurz();
            return;
        }
        // "!settings all": alles ausfuehrlich, wie bisher.
        if (w.length == 1 && w[0].toLowerCase(java.util.Locale.ROOT).matches("all|alle|alles|voll|lang|full")) {
            StringBuilder sb = new StringBuilder();
            sb.append(Sprache.t("**Squad** – which button is pressed when deploying:\n", "**Truppenaufstellung** – welcher Knopf beim Aufstellen gedrückt wird:\n"));
            for (String m : AUFSTELL_MODI) sb.append(aufstellZeilen(m));
            sb.append(Sprache.t("_Change: `!settings dungeon previous` or `!settings tower best`_\n\n", "_Umstellen: `!settings verlies previous` oder `!settings turm best`_\n\n"));

            sb.append(Sprache.t("🛑 **Emergency key** – stops the bot everywhere at once, even if Discord is stuck. It never starts anything.\n", "🛑 **Not-Aus-Taste** – hält den Bot überall sofort an, auch wenn "
                    + "Discord klemmt. Sie startet nie etwas, sie hält nur an.\n"));
            if (notAusTaste.isBlank()) {
                sb.append(Sprache.t("Now: **off**\n", "Zurzeit: **aus**\n"));
            } else {
                sb.append(Sprache.t("Now: **", "Zurzeit: **")).append(notAusTaste).append("**\n");
            }
            sb.append(Sprache.t("_Change: `!settings emergency F9` · Off: `!settings emergency off`_\n", "_Umstellen: `!settings notaus F9` · Ausschalten: `!settings notaus aus`_\n"));
            sb.append(Sprache.t("_Allowed: ", "_Erlaubt: ")).append(NotAusWache.erlaubteTasten()).append("_\n\n");
            sb.append(eventZeile()).append("\n\n").append(automatikZeile()).append("\n\n").append(nebenbeiZeile())
              .append("\n\n").append(bezahlZeilen())
              .append("\n\n").append(de.hustlebot.core.Tagestipp.beschreibung());

            client.sendCard(Sprache.t("Settings", "Einstellungen"), sb.toString(), DiscordClient.FARBE_INFO);
            return;
        }

        // Bezahlen mit Nahrung (29.09., wie beim Hauptbot):
        //   !settings pay cathedral all | tickets | food | tickets+food | food+chests
        //   !settings foodlimit cathedral 30m | off      (je Tag)
        //   !settings storage 1.2m                       (Nahrungskisten fuellen bis hierhin auf)
        String b0 = w[0].toLowerCase(Locale.ROOT);
        // Tipp des Tages (Autor, 30.09.): !settings tipp an | aus | jetzt | ping everyone|aus|<Nutzer-ID>
        if (b0.equals("tipp") || b0.equals("tip") || b0.equals("tipps") || b0.equals("tagestipp")
                || b0.equals("dailytip")) {
            String ta = w.length >= 2 ? w[1].toLowerCase() : "";
            if (ta.equals("an") || ta.equals("on") || ta.equals("ein")) de.hustlebot.core.Tagestipp.setAn(true);
            else if (ta.equals("aus") || ta.equals("off")) de.hustlebot.core.Tagestipp.setAn(false);
            else if (ta.equals("ping") || ta.equals("erwaehnung") || ta.equals("mention")) {
                if (w.length >= 3 && !de.hustlebot.core.Tagestipp.setPing(w[2])) {
                    client.send(Sprache.t("Use `!settings tip ping everyone`, `off` or a user ID.",
                            "Bitte `!settings tipp ping everyone`, `aus` oder eine Nutzer-ID angeben."));
                    return;
                }
            } else if (ta.equals("jetzt") || ta.equals("now") || ta.equals("test")
                    || ta.equals("weiter") || ta.equals("next") || ta.equals("naechster")) {
                // Von Hand, ohne Erwaehnung: "jetzt" zeigt den Tipp von heute, "weiter" springt zum naechsten.
                if (ta.equals("weiter") || ta.equals("next") || ta.equals("naechster")) {
                    de.hustlebot.core.Tagestipp.weiter();
                }
                String[] t = de.hustlebot.core.Tagestipp.heute(java.time.LocalDateTime.now());
                if (t != null) client.sendCardMitPing("", t[0], t[1], DiscordClient.FARBE_INFO);
                return;
            }
            client.sendCard(Sprache.t("Daily tip", "Tipp des Tages"),
                    de.hustlebot.core.Tagestipp.beschreibung(), DiscordClient.FARBE_INFO);
            return;
        }
        if (b0.equals("pay") || b0.equals("bezahlen") || b0.equals("payment") || b0.equals("bezahlung")) {
            String m = w.length >= 2 ? de.hustlebot.core.Bezahlung.modusAusWort(w[1]) : null;
            var art = w.length >= 3 ? de.hustlebot.core.Bezahlung.ausWort(w[2].equalsIgnoreCase("all") ? "alles" : w[2]) : null;
            if (m != null && art != null) de.hustlebot.core.Bezahlung.setStandard(m, art);   // seit 3.0 auch Arena
            // Steuerfenster (16.10.): "!settings bezahlen alle alles" = fuer alle Modi auf einmal.
            if (m == null && art != null && w[1].toLowerCase(Locale.ROOT).matches("alle|all|alles"))
                for (String mm : BEZAHL_MODI) de.hustlebot.core.Bezahlung.setStandard(mm, art);
            client.sendCard(Sprache.t("Payment", "Bezahlen"), bezahlZeilen(), DiscordClient.FARBE_INFO);
            return;
        }
        if (b0.equals("foodlimit") || b0.equals("nahrungslimit") || b0.equals("food-limit") || b0.equals("nahrungs-limit")) {
            String m = w.length >= 2 ? de.hustlebot.core.Bezahlung.modusAusWort(w[1]) : null;
            long l = w.length >= 3 ? menge(w[2]) : -2;
            if (m != null && l >= -1) de.hustlebot.core.Bezahlung.setLimit(m, l);   // seit 3.0 auch Arena
            // Steuerfenster (16.10.): "!settings nahrungslimit alle 30m" = fuer alle Modi auf einmal.
            if (m == null && l >= -1 && w[1].toLowerCase(Locale.ROOT).matches("alle|all|alles"))
                for (String mm : BEZAHL_MODI) de.hustlebot.core.Bezahlung.setLimit(mm, l);
            client.sendCard(Sprache.t("Payment", "Bezahlen"), bezahlZeilen(), DiscordClient.FARBE_INFO);
            return;
        }
        if (b0.equals("storage") || b0.equals("lager")) {
            long l = w.length >= 2 ? menge(w[1]) : -2;
            if (l >= 100_000L) de.hustlebot.core.Bezahlung.setLager(l);
            client.sendCard(Sprache.t("Payment", "Bezahlen"), bezahlZeilen(), DiscordClient.FARBE_INFO);
            return;
        }

        // Hammer-Event (28.09.): !settings pass kein|silber|gold, !settings lupen 5
        if (w[0].equalsIgnoreCase("pass")) {
            var neu = w.length >= 2 ? de.hustlebot.core.Eventautomatik.passAusWort(w[1]) : null;
            if (neu != null) de.hustlebot.core.Eventautomatik.setPass(neu);
            client.sendCard(Sprache.t("Hammer event", "Hammer-Event"), eventZeile(), DiscordClient.FARBE_INFO);
            return;
        }
        if (w[0].equalsIgnoreCase("lupen") || w[0].equalsIgnoreCase("lupe") || w[0].equalsIgnoreCase("magnifiers")) {
            if (w.length >= 2 && w[1].matches("[0-9]{1,2}")) de.hustlebot.core.Eventautomatik.setMaxLupen(Integer.parseInt(w[1]));
            client.sendCard(Sprache.t("Hammer event", "Hammer-Event"), eventZeile(), DiscordClient.FARBE_INFO);
            return;
        }

        // Not-Aus-Taste ist kein Aufstell-Modus -- eigener Zweig.
        String w0 = w[0].toLowerCase(Locale.ROOT);
        String w1 = w.length >= 2 ? w[1].toLowerCase(Locale.ROOT) : "";
        if (w0.equals("kiste") || w0.equals("kisten") || w0.equals("chest") || w0.equals("chests")) {
            String art = w.length >= 2 ? de.hustlebot.core.Eventkisten.art(w[1]) : null;
            String an = w.length >= 3 ? w[2].toLowerCase(Locale.ROOT) : "";
            if (art != null && (istAn(an) || istAus(an))) de.hustlebot.core.Eventkisten.setzen(art, istAn(an));
            client.sendCard(Sprache.t("Event chests", "Kisten im Event"), de.hustlebot.core.Eventkisten.beschreibung()
                    + Sprache.t("\n_Change: `!settings chest wood off` (wood, iron, gold, mithril, mana, food, torches)_",
                                "\n_Aendern: `!settings kiste holz aus` (holz, eisen, gold, mithril, mana, nahrung, fackeln)_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("reports") || w0.equals("meldungen") || w0.equals("meldung")) {
            if (w1.matches("\\d{1,2}")) de.hustlebot.core.Meldetakt.setTakt(Integer.parseInt(w1));
            client.sendCard(Sprache.t("Run reports", "Meldungen"), Sprache.t("Discord reports: ", "Discord meldet: ")
                    + de.hustlebot.core.Meldetakt.beschreibung()
                    + Sprache.t("\n_Change: `!settings reports 1` / `5` / `10`_", "\n_Aendern: `!settings meldungen 1` / `5` / `10`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Nahrungskisten je Event-Gang (30.09.): !settings nahrungskisten 25 | 999 (= bis die Haemmer alle sind)
        if (w0.equals("nahrungskisten") || w0.equals("foodchests") || w0.equals("kistenlimit")) {
            if (w1.matches("\\d{1,3}")) de.hustlebot.core.Eventautomatik.setNahrungskisten(Integer.parseInt(w1));
            client.sendCard(Sprache.t("Food chests per event", "Nahrungskisten je Event"),
                    Sprache.t("The hammer event is left after ", "Das Hammer-Event wird nach ")
                    + de.hustlebot.core.Eventautomatik.nahrungskistenJeEvent()
                    + Sprache.t(" food chests (or when the hammers are gone).\n_Change: `!settings foodchests 25` / `999`_",
                                " Nahrungskisten verlassen (oder wenn die Haemmer alle sind).\n_Aendern: `!settings nahrungskisten 25` / `999`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Zauberstab-Grenze (30.09.): !settings zauberstaebe 1000 | aus
        if (w0.equals("zauberstaebe") || w0.equals("zauberstab") || w0.equals("staebe") || w0.equals("wands") || w0.equals("wand")) {
            String ziffern = w1.replaceAll("[^0-9]", "");
            if (istAus(w1)) de.hustlebot.core.Zauberstabziel.setZiel(0);
            else if (!ziffern.isEmpty() && ziffern.length() < 10) de.hustlebot.core.Zauberstabziel.setZiel(Long.parseLong(ziffern));
            client.sendCard(Sprache.t("Magic wands", "Zauberstaebe"),
                    de.hustlebot.core.Zauberstabziel.beschreibung()
                    + Sprache.t("\n_Change: `!settings wands 1000` / `!settings wands off`. "
                                + "Needs the pictures ev_reiter_rankings and ev_reiter_game (Capture-Images.exe)._",
                                "\n_Aendern: `!settings zauberstaebe 1000` / `!settings zauberstaebe aus`. "
                                + "Braucht die Bilder ev_reiter_rankings und ev_reiter_game (Capture-Images.exe)._"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Autor, 24.10.: Hammer- und Titan-Event getrennt -- "!settings hammerevent 100" / "!settings titanevent 250"
        // = so viele Kathedralen im Kreislauf, dann ins Event (1 bis 500, 0 = nur wenn Schluessel und Kisten alle sind).
        // Autor, 24.10.: hoechstens N Stages (Zauberstab-Felder) je Gang ins Hammer-Event, 0 = alle Haemmer.
        if (w0.equals("hammerstages") || w0.equals("stages")) {
            if (w1.matches("\\d{1,4}")) de.hustlebot.core.Eventautomatik.setStagesJeGang(Integer.parseInt(w1));
            client.sendCard(Sprache.t("Event automation", "Event-Automatik"), automatikZeile(), DiscordClient.FARBE_INFO);
            return;
        }
        // Code-Review 24.10.: NICHT "titan"/"hammer" allein -- "!settings titan best|previous" stellt den Aussenposten-Trupp.
        if (w0.equals("hammerevent") || w0.equals("titanevent")) {
            boolean titanZeile = w0.startsWith("titan");
            if (w1.matches("\\d{1,4}")) {
                if (titanZeile) de.hustlebot.core.Eventautomatik.setTitanAlleRuns(Integer.parseInt(w1));
                else de.hustlebot.core.Eventautomatik.setHammerAlleRuns(Integer.parseInt(w1));
            }
            client.sendCard(Sprache.t("Event automation", "Event-Automatik"), automatikZeile(), DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("eventautomatik") || w0.equals("automation") || w0.equals("automatik") || w0.equals("loop")) {
            if (istAn(w1) || istAus(w1)) de.hustlebot.core.Eventautomatik.setAn(istAn(w1));
            else if (w1.matches("\\d{1,3}")) de.hustlebot.core.Eventautomatik.setVerliesRuns(Integer.parseInt(w1));   // 0 = keine
            // Hauptbot 09.10.: welches Event der Kreislauf spielt -- "!settings eventautomatik titan" / "... hammer".
            else if (w1.startsWith("titan") || w1.startsWith("grab") || w1.startsWith("excav"))
                de.hustlebot.core.Eventautomatik.setTitan(true);
            else if (w1.startsWith("hammer"))
                de.hustlebot.core.Eventautomatik.setTitan(false);
            client.sendCard(Sprache.t("Event automation", "Event-Automatik"), automatikZeile(), DiscordClient.FARBE_INFO);
            return;
        }
        // Ben 17.10.: Helden-Set vor/nach dem automatischen Clan-Krieg (Kleiderbuegel, Set am Namen).
        if (w0.equals("heldenset") || w0.equals("heroset")) {
            String[] t2 = rest.trim().split("\\s+", 3);
            String was = t2.length > 1 ? t2[1].toLowerCase(Locale.ROOT) : "";
            String name = t2.length > 2 ? t2[2].trim() : "";
            // Autor 17.10.: "jetzt schon testen" -- einmal "vorher"-Set anlegen und gleich wieder das "nachher"-Set.
            if (was.equals("test") || was.equals("probe")) {
                if (engine.state() == BotEngine.State.LAEUFT) {
                    client.send(Sprache.t("The bot is busy -- stop it first, then `!settings heroset test`.",
                            "Der Bot ist beschaeftigt -- erst stoppen, dann `!settings heldenset test`."));
                } else {
                    starten("heldenset 1");
                }
                return;
            }
            if (de.hustlebot.core.Schalterdatei.istAn(was)) de.hustlebot.bot.HeldenSet.setAn(true);
            else if (de.hustlebot.core.Schalterdatei.istAus(was)) de.hustlebot.bot.HeldenSet.setAn(false);
            else if ((was.equals("vorher") || was.equals("before")) && !name.isEmpty()) de.hustlebot.bot.HeldenSet.setVorher(name);
            else if ((was.equals("nachher") || was.equals("after")) && !name.isEmpty()) de.hustlebot.bot.HeldenSet.setNachher(name);
            client.sendCard(Sprache.t("Hero set for clan war", "Helden-Set fuer den Clan-Krieg"),
                    de.hustlebot.bot.HeldenSet.beschreibung()
                    + Sprache.t("\n_Change: `!settings heroset on` / `off` · `!settings heroset before War 2` · `!settings heroset after Normal 1` (name + slot)_",
                                "\n_Aendern: `!settings heldenset an` / `aus` · `!settings heldenset vorher War 2` · `!settings heldenset nachher Normal 1` (Name + Platz)_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Autor 16.10.: Spiel-Neustart alle 2 h auch fuer die Freunde, abschaltbar. "jetzt" = beim naechsten Run-Ende (Test).
        if (w0.equals("spielneustart") || w0.equals("gamerestart") || w0.equals("restartgame") || w0.equals("neustart")) {
            if (de.hustlebot.core.Schalterdatei.istAn(w1)) de.hustlebot.core.Spielneustart.setzen(true);
            else if (de.hustlebot.core.Schalterdatei.istAus(w1)) de.hustlebot.core.Spielneustart.setzen(false);
            else if (w1.equals("jetzt") || w1.equals("now")) de.hustlebot.core.Spielneustart.jetztAnfordern();
            client.sendCard(Sprache.t("Game restart", "Spiel-Neustart"),
                    de.hustlebot.core.Spielneustart.beschreibung()
                    + ((w1.equals("jetzt") || w1.equals("now"))
                        ? Sprache.t("\nRequested: the game restarts after the current cathedral run.",
                                    "\nAngefordert: das Spiel startet nach dem laufenden Kathedralen-Run neu.") : "")
                    + Sprache.t("\n_Change: `!settings spielneustart on` / `off`_", "\n_Aendern: `!settings spielneustart an` / `aus`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        // Autor 21.10. (Mia, mehrere Spielkonten): Konto am Spielernamen erkennen, je Konto eigene Dateien (core.Konten).
        if (w0.equals("accounts") || w0.equals("account") || w0.equals("konten") || w0.equals("konto")) {
            if (de.hustlebot.core.Schalterdatei.istAn(w1)) de.hustlebot.core.Konten.setzen(true);
            else if (de.hustlebot.core.Schalterdatei.istAus(w1)) de.hustlebot.core.Konten.setzen(false);
            java.util.List<String> konten = de.hustlebot.core.Konten.bekannt();
            client.sendCard(Sprache.t("Several game accounts", "Mehrere Spielkonten"),
                    (de.hustlebot.core.Konten.an()
                    ? Sprache.t("**On** -- at every start the bot reads the player name in the castle and takes that account's "
                                + "team scan, notebook, opponent lists and payment settings. A new account: do one team scan there.",
                                "**An** -- bei jedem Start liest der Bot den Spielernamen in der Burg und nimmt Team-Scan, Notizblock, "
                                + "Gegnerlisten und Bezahl-Einstellungen dieses Kontos. Neues Konto: dort einmal einen Team-Scan machen.")
                    : Sprache.t("**Off** -- one account (as before).", "**Aus** -- ein Konto (wie bisher)."))
                    + (konten.isEmpty() ? "" : "\n" + Sprache.t("Accounts: ", "Konten: ") + String.join(", ", konten)
                    + Sprache.t(" (active: ", " (aktiv: ") + konten.get(0) + ")"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("arenawatch") || w0.equals("arenabeobachter") || w0.equals("beobachter") || w0.equals("watcher")) {
            if (de.hustlebot.core.Schalterdatei.istAn(w1)) de.hustlebot.bot.ArenaMode.beobachterSetzen(true);
            else if (de.hustlebot.core.Schalterdatei.istAus(w1)) de.hustlebot.bot.ArenaMode.beobachterSetzen(false);
            client.sendCard(Sprache.t("Arena watcher", "Arena-Beobachter"),
                    de.hustlebot.bot.ArenaMode.beobachterAn()
                    ? Sprache.t("**On** -- when you play arena yourself, the bot counts every fight per opponent (never clicks). "
                                + "2 wins = doable, 3 wins = safe win, 3 losses without a win = never.",
                                "**An** -- spielst du selbst Arena, zaehlt der Bot jeden Kampf je Gegner mit (klickt nie). "
                                + "2 Siege = machbar, 3 Siege = sicher, 3 Niederlagen ohne Sieg = niemals.")
                    : Sprache.t("**Off** -- your own arena fights are not counted.", "**Aus** -- eigene Arena-Kaempfe werden nicht mitgezaehlt."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("mauswache") || w0.equals("maus") || w0.equals("mouse") || w0.equals("mousepause")) {
            if (de.hustlebot.core.Schalterdatei.istAn(w1)) de.hustlebot.core.Mauswache.setzen(true);
            else if (de.hustlebot.core.Schalterdatei.istAus(w1)) de.hustlebot.core.Mauswache.setzen(false);
            client.sendCard(Sprache.t("Stop when the mouse moves", "Stopp bei Mausbewegung"),
                    de.hustlebot.core.Mauswache.an()
                    ? Sprache.t("**On** -- if you move the mouse while the bot plays, it stops.",
                                "**An** -- bewegst du die Maus, waehrend der Bot spielt, stoppt er.")
                    : Sprache.t("**Off** -- the bot keeps playing when you move the mouse. Stop it with F9.",
                                "**Aus** -- der Bot spielt weiter, auch wenn du die Maus bewegst. Anhalten mit F9."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("lighthouse") || w0.equals("leuchtturm") || w0.equals("colosseum") || w0.equals("kolosseum")) {
            var ort = w0.startsWith("l") ? de.hustlebot.bot.LighthouseMode.LEUCHTTURM : de.hustlebot.bot.LighthouseMode.KOLOSSEUM;
            if (istAn(w1) || istAus(w1)) ort.plan().setAn(istAn(w1));
            client.sendCard(modusName(ort.id()), modusName(ort.id()) + ": " + (ort.plan().istAn() ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                    + Sprache.t(" (visited by itself when energy is full)\n_Change: `!" + (w0.startsWith("l") ? "lighthouse" : "colosseum") + " on` / `off`_",
                                " (wird von selbst besucht, wenn die Energie voll ist)\n_Aendern: `!" + (w0.startsWith("l") ? "leuchtturm" : "kolosseum") + " an` / `aus`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("titantower") || w0.equals("titanturm") || w0.equals("tower2")) {
            if (istAn(w1) || istAus(w1)) de.hustlebot.core.Turmplan.setAn(istAn(w1));
            client.sendCard(modusName("titantower"), de.hustlebot.core.Turmplan.beschreibung()
                    + Sprache.t("\n_Change: `!settings titantower on` / `off`_", "\n_Aendern: `!settings titanturm an` / `aus`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("friends") || w0.equals("freunde") || w0.equals("freundesenden") || w0.equals("sendfriends")) {
            if (istAn(w1) || istAus(w1)) de.hustlebot.core.Freundeplan.setAn(istAn(w1));
            client.sendCard(modusName("freundesenden"), de.hustlebot.core.Freundeplan.beschreibung()
                    + Sprache.t("\n_Change: `!settings friends on` / `off`_", "\n_Aendern: `!settings freunde an` / `aus`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.equals("clanladen") || w0.equals("clanstore") || w0.equals("clanshop")) {
            if (istAn(w1) || istAus(w1)) de.hustlebot.core.Ladenplan.setAn(istAn(w1));
            client.sendCard(modusName("clanladen"), de.hustlebot.core.Ladenplan.beschreibung()
                    + Sprache.t("\n_Change: `!settings clanstore on` / `off`_", "\n_Aendern: `!settings clanladen an` / `aus`_"),
                    DiscordClient.FARBE_INFO);
            return;
        }

        if (w[0].equalsIgnoreCase("notaus") || w[0].equalsIgnoreCase("not-aus")
                || w[0].equalsIgnoreCase("panik") || w[0].equalsIgnoreCase("emergency")
                || w[0].equalsIgnoreCase("emergencykey") || w[0].equalsIgnoreCase("panic")) {   // 3.1: englische Namen
            if (w.length < 2) {
                client.sendCard(Sprache.t("Emergency key", "Not-Aus-Taste"),
                        (notAusTaste.isBlank()
                           ? Sprache.t("**No** emergency key is set right now.", "Zurzeit ist **keine** Not-Aus-Taste gesetzt.")
                           : Sprache.t("Now: **", "Zurzeit: **") + notAusTaste + Sprache.t("**. This key stops the bot everywhere at once – even if Discord is stuck.", "**. Diese Taste hält den Bot überall "
                             + "sofort an – auch wenn Discord klemmt."))
                        + Sprache.t("\nChange: `!settings emergency F9`. Allowed: ", "\nUmstellen: `!settings notaus F9`. Erlaubt: ")
                        + NotAusWache.erlaubteTasten() + Sprache.t(". Off: `!settings emergency off`.", ". Ausschalten: `!settings notausaus`."),
                        DiscordClient.FARBE_INFO);
                return;
            }
            notAusEinstellen(w[1]);
            return;
        }

        // Ben 16.10.: "Previous squad" in JEDEM Modus (Verlies, Kathedrale, Arena, Clan-Krieg) -- damit seine
        // historische Kraft nicht steigt, wenn nachts die Kriegsausruestung angelegt ist. Ein Befehl fuer alle Stellen.
        String w0klein = w[0].toLowerCase(Locale.ROOT);
        if (w0klein.equals("squad") || w0klein.equals("truppe") || w0klein.equals("aufstellung")) {
            if (w.length >= 2) {
                de.hustlebot.bot.Aufstellung.Wahl alleWahl = de.hustlebot.bot.Aufstellung.wahlAusText(w[1]);
                if (alleWahl == null) {
                    client.sendCard(Sprache.t("Best or previous?", "Best oder previous?"),
                            "`best` = Deploy the best, `previous` = Previous Squad.", DiscordClient.FARBE_WARNUNG);
                    return;
                }
                for (String m : AUFSTELL_MODI) de.hustlebot.bot.Aufstellung.setzen(m, alleWahl);
            }
            StringBuilder alle = new StringBuilder();
            for (String m : AUFSTELL_MODI) alle.append(aufstellZeilen(m));
            alle.append(Sprache.t("_Lighthouse and colosseum: the bot never presses \"Deploy the best\" there -- the squad that "
                            + "is already set fights. Change: `!settings squad previous` / `best`_",
                    "_Leuchtturm und Kolosseum: dort drueckt der Bot nie \"Deploy the best\" -- es kaempft die Truppe, die "
                            + "schon drinsteht. Aendern: `!settings squad previous` / `best`_"));
            client.sendCard(Sprache.t("Squad (all modes)", "Truppe (alle Modi)"), alle.toString(), DiscordClient.FARBE_INFO);
            return;
        }
        String modus = de.hustlebot.bot.Aufstellung.ausText(w[0]);
        if (modus.isEmpty() || !AUFSTELL_MODI.contains(modus)) {
            client.sendCard(Sprache.t("Which mode?", "Welcher Modus?"),
                    Sprache.t("Pick one: `dungeon`, `cathedral`, `arena`, `tower`, `outpost`.\nExample: `!settings dungeon previous`", "Nimm einen davon: `verlies`, `kathedrale`, `arena`, `turm`, `aussenposten`.\n"
                    + "Beispiel: `!settings verlies previous`"), DiscordClient.FARBE_WARNUNG);
            return;
        }
        if (w.length < 2) {
            client.sendCard(Sprache.t("Squad", "Truppenaufstellung"),
                    aufstellZeilen(modus) + Sprache.t("\n_Change: `!settings ", "\n_Umstellen: `!settings ") + w[0]
                    + " previous` / `... best`_", DiscordClient.FARBE_INFO);
            return;
        }
        de.hustlebot.bot.Aufstellung.Wahl wahl =
                de.hustlebot.bot.Aufstellung.wahlAusText(w[1]);
        if (wahl == null) {
            client.sendCard(Sprache.t("Best or previous?", "Best oder previous?"),
                    "`best` = Deploy the best, `previous` = Previous Squad.",
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        de.hustlebot.bot.Aufstellung.setzen(modus, wahl);
        client.sendCard(Sprache.t("Saved", "Gespeichert"),
                aufstellZeilen(modus)
                + Sprache.t("\nApplies from the next deployment. Stays after a restart.", "\nGilt ab dem nächsten Aufstellen. Bleibt auch nach einem Neustart."),
                DiscordClient.FARBE_ERFOLG);
    }

    private String aufstellZeilen(String modus) {
        boolean previous = de.hustlebot.bot.Aufstellung.istPrevious(modus);
        return "**" + de.hustlebot.bot.Aufstellung.anzeige(modus) + "**\n"
                + (previous ? "⬜ " : "✅ ") + "Deploy the best\n"
                + (previous ? "✅ " : "⬜ ") + "Previous Squad\n\n";
    }

    private void clanwar(String rest) {
        String wort = rest.toLowerCase(Locale.ROOT);

        if (wort.startsWith("on") || wort.startsWith("an")) {
            kriegsplan.setSelbsttaetig(true);
            client.sendCard(Sprache.t("Clan war clock on", "Kriegsuhr an"), Sprache.t("The bot deploys by itself at " + Kriegsplan.zeitenText(", ") + ". A run in progress is finished first, then it deploys, then it carries on.",
                    "Der Bot stellt von selbst auf, um " + Kriegsplan.zeitenText(", ") + ". "
                    + "Ein laufender Durchgang wird erst zu Ende gespielt, dann wird "
                    + "aufgestellt, dann geht es weiter."),
                    DiscordClient.FARBE_ERFOLG);
            return;
        }
        if (wort.startsWith("off") || wort.startsWith("aus")) {
            kriegsplan.setSelbsttaetig(false);
            synchronized (kette) {
                // Punkt 5: auch einen schon eingereihten Krieg herausnehmen.
                for (int i = kette.size() - 1; i >= 1; i--) {
                    if ("clanwar".equals(kette.get(i).modus())) kette.remove(i);
                }
            }
            client.sendCard(Sprache.t("Clan war clock off", "Kriegsuhr aus"),
                    Sprache.t("The bot leaves the clan war alone. This stays after a restart.",
                              "Der Bot lässt den Clan-Krieg in Ruhe. Das bleibt auch nach einem "
                              + "Neustart so."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        client.sendCard(Sprache.t("Clan war clock", "Kriegsuhr"), kriegsplan.beschreibung(),
                DiscordClient.FARBE_INFO);
    }

    /**
     * Das Bild fuers Verschicken (16.10.): NUR das Spielfenster (PrintWindow), auch wenn Discord oder
     * ein Browser darueber liegt. Geht das nicht, das letzte Bild des Bots -- Spielfoto vermerkt das im Protokoll.
     */
    private BufferedImage spielbild() {
        BufferedImage fenster = spielfoto.aufnehmen(engine.context().window());
        return fenster != null ? fenster : engine.context().frame();
    }

    /** Bild nur vom Spielfenster als PNG -- fuer den Fehlerbericht aus dem Steuerprogramm (16.10.). */
    public byte[] spielbildPng() {
        try {
            BufferedImage b = spielbild();
            if (b == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(b, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void bildschirmfoto() {
        try {
            BufferedImage bild = spielbild();
            if (bild == null) {
                client.send(Sprache.t("No picture right now – is the game running?", "Es gibt gerade kein Bild – läuft das Spiel?"));
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(bild, "png", out);
            client.sendFile(Sprache.t("This is what it looks like right now", "So sieht es gerade aus"), "screenshot.png", out.toByteArray());
        } catch (Exception e) {
            client.send(Sprache.t("The picture did not work: ", "Das Bild hat nicht geklappt: ") + e.getMessage());
        }
    }

    /**
     * !log -- schickt den aktuellen Fehlerbericht als Datei. Damit laesst sich
     * von der Ferne sehen, was der Bot gerade tut und welche Vorlagen wie knapp
     * daneben liegen (z. B. warum der Confirm-Knopf nicht getroffen wird).
     */
    private void log() {
        try {
            java.util.Map<String, String> fakten = new java.util.LinkedHashMap<>();
            fakten.put(Sprache.t("State", "Zustand"), engine.state().name());
            fakten.put("Status", engine.statusText());
            fakten.put(Sprache.t("Window size", "Fenstergroesse"),
                    engine.context().width() + "x" + engine.context().height());
            fakten.put(Sprache.t("Templates loaded", "Vorlagen geladen"),
                    String.valueOf(engine.context().matcher().names().size()));
            String text = de.hustlebot.core.Report.build(fakten);
            client.sendFile(Sprache.t("Current error report:", "Aktueller Fehlerbericht:"), "fehlerbericht.txt",
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            client.send(Sprache.t("The report could not be made: ", "Der Bericht konnte nicht erstellt werden: ") + e.getMessage());
        }
    }

    private void statistik(String rest) {
        String wort = rest.toLowerCase(Locale.ROOT);

        if (wort.startsWith("off") || wort.startsWith("stop") || wort.startsWith("aus")) {
            statsMinuten = 0;
            de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.STATS, 0);
            client.send(Sprache.t("The numbers no longer come by themselves.", "Die Zahlen kommen jetzt nicht mehr von selbst."));
            return;
        }

        int minuten = zahlAus(rest);
        if (minuten > 0) {
            statsMinuten = minuten;
            de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.STATS, minuten);
            statsZuletzt = System.currentTimeMillis();
            client.send(Sprache.t("The numbers now come every ", "Die Zahlen kommen jetzt alle ") + minuten + Sprache.t(" minutes. ", " Minuten. ")
                    + Sprache.t("`!stats off` stops it.", "`!stats off` hört damit auf."));
        }
        statsSchicken();
    }

    /**
     * !stats wie beim Hauptbot (Autor, 23.09., Punkt 14): Modus, Zustand,
     * Warteschlange mit Fertig-Uhrzeit, Schnitt je Modus mit Runs pro Stunde,
     * Clan-Krieg (Einheiten/Titanen getrennt), Laufzeit, Ether, Schluessel,
     * Particle of Light und was der laufende Modus selbst meldet (Etage ...).
     * Alles zweisprachig.
     */
    private void statsSchicken() {
        var t = engine.tracker();
        var tele = engine.context() == null ? null : engine.context().laufstatistik();
        boolean laeuft = engine.state() == BotEngine.State.LAEUFT;
        StringBuilder sb = new StringBuilder();

        sb.append(Sprache.t("**Mode:** ", "**Spielmodus:** "))
          .append(modusName(engine.lastModeId())).append('\n');
        sb.append(Sprache.t("**State:** ", "**Zustand:** ")).append(zustandName(engine.state())).append("\n\n");

        // --- Durchlaeufe und Warteschlange ---
        sb.append(Sprache.t("__Runs__\n", "__Durchlaeufe__\n"));
        int done = t != null ? t.completedRuns() : 0;
        int ziel = t != null ? t.targetRuns() : 0;
        long summe = 0;
        synchronized (kette) {
            if (kette.isEmpty()) {
                sb.append(Sprache.t("Completed: **", "Abgeschlossen: **")).append(done);
                if (ziel > 0) sb.append(Sprache.t(" of ", " von ")).append(ziel);
                sb.append("**\n");
                if (laeuft && tele != null && ziel > done) {
                    long avg = tele.averageRunSeconds(engine.lastModeId());
                    if (avg > 0) summe = (long) (ziel - done) * avg;
                }
            } else {
                int nr = 0;
                for (Auftrag a : kette) {
                    if ("clanwar".equals(a.modus())) continue;   // steht unten gesondert
                    nr++;
                    boolean aktiv = nr == 1 && laeuft;
                    int fertig = aktiv && t != null && t.targetRuns() == a.runs() ? done : 0;
                    sb.append(nr).append(". ").append(aktiv ? "▶ " : "· ")
                      .append("**").append(fertig).append(Sprache.t(" of ", " von "))
                      .append(a.runs()).append("** ").append(modusName(a.modus()));
                    if ("cathedral".equals(a.modus()) && !"a".equals(a.weg())) {
                        sb.append("c".equals(a.weg()) ? Sprache.t(" (route c, turbo)", " (Weg c, Turbo)")
                                                      : Sprache.t(" (route b)", " (Weg b)"));
                    }
                    long avg = tele == null ? 0 : tele.averageRunSeconds(a.modus());
                    if (avg > 0) {
                        summe += (long) Math.max(0, a.runs() - fertig) * avg;
                        sb.append(Sprache.t("  – done ~", "  – fertig ~"))
                          .append(uhrzeitText(System.currentTimeMillis() + summe * 1000L));
                    }
                    sb.append('\n');
                }
            }
        }
        if (summe > 0) {
            sb.append(Sprache.t("All done: **", "Alles fertig: **"))
              .append(uhrzeitText(System.currentTimeMillis() + summe * 1000L))
              .append(Sprache.t("**  (in ", " Uhr**  (in "))
              .append(dauerText(summe)).append(")\n");
        }

        if (tele != null) {
            boolean gemessen = false;
            for (String m : tele.gemesseneModi()) {
                long avg = tele.averageRunSeconds(m);
                if (avg <= 0) continue;
                gemessen = true;
                sb.append(Sprache.t("Average per ", "Mittlere Dauer je "))
                  .append(modusName(m)).append(Sprache.t(" run: ", "-Durchlauf: "))
                  .append(dauerText(avg)).append("  (").append(tele.runsOfMode(m)).append(")  –  ")
                  .append(Sprache.komma(tele.runsPerHour(m), 1))
                  .append(Sprache.t(" per hour\n", " pro Stunde\n"));
            }
            if (!gemessen) {
                sb.append(Sprache.t("Average per run: not known yet\n",
                                    "Mittlere Dauer je Durchlauf: noch keine Angabe\n"));
            }
            sb.append(Sprache.t("Resurrections: ", "Wiederbelebungen: "))
              .append(tele.resurrections()).append('\n');
        }
        if (t != null && t.failedBattles() > 0) {
            sb.append(Sprache.t("Battles lost: ", "Verlorene Kaempfe: "))
              .append(t.failedBattles()).append('\n');
        }
        sb.append(Sprache.t("Clan war – units deployed: ", "Clan-Krieg – Einheiten gesetzt: "))
          .append(Kriegsplan.gesetzteEinheiten()).append('\n');
        sb.append(Sprache.t("Clan war – titans deployed: ", "Clan-Krieg – Titanen gesetzt: "))
          .append(Kriegsplan.gesetzteTitanen()).append('\n');
        // Hammer-Event, Leuchtturm, Kolosseum seit dem letzten !start (29.09.): je Besuch mit
        // Kaempfen 5 moegliche Kaempfe -- "3 of 5", zwei Besuche "5 of 10".
        int stages = de.hustlebot.core.Laufzaehler.eventStages();
        if (stages > 0 || de.hustlebot.core.Eventautomatik.istAn()) {
            sb.append(Sprache.t("Hammer event: ", "Hammer-Event: ")).append(stages)
              .append(Sprache.t(stages == 1 ? " stage done" : " stages done", stages == 1 ? " Stage geschafft" : " Stages geschafft"))
              .append('\n');
        }
        for (var o : java.util.List.of(de.hustlebot.bot.LighthouseMode.LEUCHTTURM,
                                       de.hustlebot.bot.LighthouseMode.KOLOSSEUM)) {
            var plan = o.plan();
            if (!plan.istAn() && plan.kampfBesuche() == 0) continue;
            int besuche = plan.kampfBesuche();
            // Seit 30.09.: die wirklich abgelesene Energie je Besuch, sonst wie bisher 5 je Besuch.
            int moeglich = plan.moegliche() > 0 ? Math.max(plan.moegliche(), plan.kaempfe())
                    : besuche * de.hustlebot.core.Energieplan.KAEMPFE_JE_BESUCH;
            sb.append(Sprache.t(o.en(), o.de())).append(": ").append(besuche)
              .append(Sprache.t(besuche == 1 ? " visit, " : " visits, ", besuche == 1 ? " Besuch, " : " Besuche, "))
              .append(plan.siege()).append(Sprache.t(" of ", " von ")).append(moeglich)
              .append(Sprache.t(" fights won", " Kaempfen gewonnen"));
            if (plan.istAn()) sb.append(Sprache.t(" · next visit ", " · naechster Besuch ")).append("sofort".equals(plan.naechsterBesuch()) ? Sprache.t("now", "sofort") : plan.naechsterBesuch());
            sb.append('\n');
        }
        sb.append('\n');

        // --- Laufzeit ---
        if (tele != null) {
            long minuten = tele.sessionSeconds() / 60;
            sb.append(Sprache.t("__Uptime__\n", "__Laufzeit__\n"))
              .append(Sprache.t("Minutes running: **", "Bot-Minuten am Stueck: **"))
              .append(minuten).append("**\n");
        }
        if (offAtMillis > 0) {
            sb.append(Sprache.t("PC off at: ", "PC aus um: ")).append(offClock).append('\n');
        }

        // --- Ether, Schluessel, Particle of Light ---
        if (tele != null) {
            if (tele.hasEther()) {
                long gained = tele.etherGained();
                sb.append(Sprache.t("\n__Ether (Dungeon)__\n", "\n__Ether (Verlies)__\n"))
                  .append(Sprache.t("Now: **", "Jetzt: **"))
                  .append(de.hustlebot.core.Laufstatistik.format(tele.etherNow())).append("**")
                  .append(Sprache.t("   ·   Gained: **", "   ·   Dazugekommen: **"))
                  .append(gained >= 0 ? "+" : "")
                  .append(de.hustlebot.core.Laufstatistik.format(gained)).append("**\n");
            }
            sb.append(Sprache.t("\n__Dark Cathedral__\n", "\n__Dunkle Kathedrale__\n"));
            if (tele.hasKeys()) {
                sb.append(Sprache.t("Keys left: **", "Schluessel uebrig: **"))
                  .append(tele.keysNow()).append("**");
                if (tele.keysStart() >= 0) {
                    sb.append(Sprache.t("   ·   Spent: **", "   ·   Verbraucht: **"))
                      .append(tele.keysUsed()).append("**");
                }
                sb.append('\n');
            } else {
                sb.append(Sprache.t("Keys: not read yet\n", "Schluessel: noch nicht abgelesen\n"));
            }
            if (tele.hasLight()) {
                long gained = tele.lightGained();
                sb.append("Particle of Light: **")
                  .append(de.hustlebot.core.Laufstatistik.format(tele.lightNow())).append("**")
                  .append(Sprache.t("   ·   Gained: **", "   ·   Dazugekommen: **"))
                  .append(gained >= 0 ? "+" : "")
                  .append(de.hustlebot.core.Laufstatistik.format(gained)).append("**\n");
            }
            // Arena (Freunde 3.0): nur, wenn schon einmal Tickets abgelesen wurden.
            if (tele.hasTickets()) {
                sb.append("\n__Arena__\n")
                  .append(Sprache.t("Tickets left: **", "Tickets uebrig: **")).append(tele.ticketsNow()).append("**");
                if (tele.ticketsStart() >= 0) {
                    sb.append(Sprache.t("   ·   Spent: **", "   ·   Verbraucht: **")).append(tele.ticketsUsed()).append("**");
                }
                sb.append('\n');
            }
        }

        // --- Was der laufende Modus selbst meldet (Etage, Weg ...) ---
        var modus = engine.activeMode();
        if (modus != null) {
            var eigene = modus.extraStats();
            if (!eigene.isEmpty()) {
                sb.append("\n__").append(modus.displayName()).append("__\n");
                eigene.forEach((k, v) -> sb.append(k).append(": **").append(v).append("**\n"));
            }
        }

        String ziel2 = statsChannelId.isBlank() ? channelId : statsChannelId;
        client.sendCardTo(ziel2, Sprache.t("Statistics", "Statistik"), sb.toString(),
                DiscordClient.FARBE_INFO);
    }

    /** Die alte, kurze Zahlenkarte -- nicht mehr benutzt, bleibt zum Vergleich. */
    @SuppressWarnings("unused")
    private void statsSchickenAlt() {
        StringBuilder sb = new StringBuilder();
        var t = engine.tracker();
        boolean laeuft = engine.state() == BotEngine.State.LAEUFT;

        if (t != null && t.targetRuns() > 0) {
            // Ein kleiner Fortschrittsbalken sagt mehr als zwei Zahlen --
            // aber die Zahlen stehen VORNE.
            //
            // Vorher stand der Balken vorn, aus Vollblock-Zeichen und in
            // Schreibmaschinenschrift (zwischen ` und `). Bei einem fertigen
            // Durchlauf sind das zehn Vollbloecke auf hellem Kasten: Discord
            // macht daraus einen weissen Klotz, in dem nichts mehr zu lesen
            // ist. Genau so sah es bei Autor am 19.09. aus.
            //
            // Jetzt: erst die Zahlen, dann ein Balken aus Zeichen mit Luft
            // dazwischen, ohne den hellen Kasten. Kennt eine Schriftart die
            // Balkenzeichen nicht, steht die Auskunft trotzdem da.
            int anteil = (int) Math.round(10.0 * t.completedRuns() / Math.max(1, t.targetRuns()));
            anteil = Math.max(0, Math.min(10, anteil));
            int prozent = Math.min(100, (int) Math.round(100.0 * t.completedRuns()
                    / Math.max(1, t.targetRuns())));
            sb.append("🎯 **").append(modusName(engine.lastModeId())).append("**  ")
              .append(t.completedRuns()).append(" / ").append(t.targetRuns())
              .append("   ").append("▰".repeat(anteil)).append("▱".repeat(10 - anteil))
              .append("  ").append(prozent).append(" %\n");
        } else {
            sb.append("🎯 **Geschafft:** – (gerade läuft nichts)\n");
        }

        // Mittlere Dauer, Verlies und Kathedrale getrennt.
        var tele = engine.context() == null ? null : engine.context().laufstatistik();
        if (tele != null) {
            long vD = tele.averageRunSeconds("dungeon");
            long vK = tele.averageRunSeconds("cathedral");
            if (vD > 0 || vK > 0) {
                sb.append("⏱️ **⌀ pro Durchlauf:**");
                if (vD > 0) sb.append(" Verlies ").append(dauerText(vD));
                if (vD > 0 && vK > 0) sb.append(" ·");
                if (vK > 0) sb.append(" Kathedrale ").append(dauerText(vK));
                sb.append('\n');
            }

            // Schluessel der Kathedrale (Autor, 19.09.). -1 heisst "noch nicht
            // abgelesen" -- der Stand steht nur auf dem Teilnahmebildschirm.
            long schluessel = tele.keysNow();
            if (schluessel >= 0) {
                sb.append("🔑 **Schlüssel übrig:** ").append(schluessel);
                long start = tele.keysStart();
                if (start > schluessel) {
                    sb.append("  (").append(start - schluessel).append(" verbraucht)");
                }
                sb.append('\n');
            }

            // Fertig-Uhrzeit: aus mittlerer Dauer des laufenden Modus.
            if (laeuft && t != null) {
                long avg = tele.averageRunSeconds(engine.lastModeId());
                int rest = Math.max(0, t.targetRuns() - t.completedRuns());
                if (avg > 0 && rest > 0) {
                    long fertig = System.currentTimeMillis() + rest * avg * 1000L;
                    sb.append("🏁 **Fertig etwa:** ").append(uhrzeitText(fertig))
                      .append(" Uhr  (noch ").append(rest).append(")\n");
                }
            }
        }

        // Warteschlange.
        if (kette.isEmpty()) {
            sb.append("**Warteschlange:** leer\n");
        } else {
            StringBuilder q = new StringBuilder();
            for (Auftrag a : kette) {
                if (q.length() > 0) q.append(", ");
                q.append(modusName(a.modus())).append(" x").append(a.runs());
            }
            sb.append("📋 **Warteschlange:** ").append(kette.size())
              .append("  (").append(q).append(")\n");
        }

        // Geplante Abschaltung.
        if (offAtMillis > 0) {
            sb.append("🌙 **PC aus um:** ").append(offClock).append(" Uhr\n");
        }

        if (t != null && t.failedBattles() > 0) {
            sb.append("💀 **Verlorene Kämpfe:** ").append(t.failedBattles()).append('\n');
        }
        if (Kriegsplan.gesetzteKriege() > 0) {
            sb.append("**Aufgestellte Kriege:** ").append(Kriegsplan.gesetzteKriege()).append('\n');
        }
        sb.append("**Zustand:** ").append(engine.state());

        String ziel = statsChannelId.isBlank() ? channelId : statsChannelId;
        client.sendCardTo(ziel, "Zahlen", sb.toString(), DiscordClient.FARBE_INFO);
    }

    /** Der Zustand des Motors in der eingestellten Sprache (die Namen im Code sind deutsch). */
    private static String zustandName(BotEngine.State state) {
        if (!Sprache.istEnglisch()) return state.name();
        return switch (state) {
            case BEREIT   -> "READY";
            case LAEUFT   -> "RUNNING";
            case PAUSIERT -> "PAUSED";
            case GESTOPPT -> "STOPPED";
            case FEHLER   -> "ERROR";
            case FERTIG   -> "DONE";
        };
    }

    /** Sekunden lesbar: "95 s" oder "1:35 min". */
    private static String dauerText(long sekunden) {
        if (sekunden < 120) return sekunden + " s";
        return (sekunden / 60) + ":" + String.format("%02d", sekunden % 60) + " min";
    }

    /** Ein Zeitpunkt (Millisekunden) als "HH:MM" in Autors Zeitzone. */
    private static String uhrzeitText(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(BotEngine.ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    // ------------------------------------------------------------------
    //  !off -- PC zu einer Uhrzeit herunterfahren
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  Neu in 2.1 (Autor, 23.09.): Befehle aus dem Hauptbot
    // ------------------------------------------------------------------

    /** !specs alle N Minuten von selbst (0 = aus). */
    private int specsMinuten = 0;
    private long specsZuletzt = 0;

    /**
     * "!specs" -- Rechnerwerte. Ohne Zusatzprogramm und ohne Admin-Rechte
     * (Autors Wahl): Auslastung, Speicher, Platte, und bei NVIDIA-Karten die
     * Temperatur der Grafikkarte ueber das Treiber-Werkzeug nvidia-smi. Eine
     * CPU-Temperatur gibt es so nicht.
     */
    private void specs(String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith("off") || w.startsWith("aus") || w.startsWith("stop")) {
            specsMinuten = 0;
            client.send(Sprache.t("No more values by themselves. `!specs 10` turns it back on.",
                                  "Es kommen keine Werte mehr von selbst. `!specs 10` schaltet es wieder ein."));
            return;
        }
        int minuten = zahlAus(w);
        if (minuten > 0) {
            specsMinuten = minuten;
            specsZuletzt = System.currentTimeMillis();
            client.send(Sprache.t("Values every " + minuten + " minutes now. `!specs off` stops it.",
                                  "Die Werte kommen jetzt alle " + minuten + " Minuten. `!specs off` hoert auf."));
        }
        specsSchicken();
    }

    private void specsSchicken() {
        de.hustlebot.core.SystemMonitor.Reading r = de.hustlebot.core.SystemMonitor.read();
        StringBuilder sb = new StringBuilder();
        sb.append("🖥️ **CPU:** ").append(Math.round(r.cpuPercent())).append(" %")
          .append(Sprache.t("  (bot ", "  (Bot ")).append(Math.round(r.botCpuPercent())).append(" %)\n");
        sb.append("🧠 **RAM:** ").append(r.ramUsedMb() / 1024).append(" / ")
          .append(r.ramTotalMb() / 1024).append(" GB  (").append(Math.round(r.ramPercent())).append(" %)\n");
        sb.append(Sprache.t("💾 **Disk free:** ", "💾 **Platte frei:** ")).append(r.diskFreeGb()).append(" GB\n");
        String[] gpu = nvidia();
        if (gpu != null) {
            sb.append("🎮 **GPU:** ").append(gpu[2]).append(" – ").append(gpu[0]).append(" °C, ")
              .append(gpu[1]).append(Sprache.t(" % load\n", " % Last\n"));
        } else {
            sb.append(Sprache.t("🎮 GPU temperature: only for NVIDIA cards\n",
                                "🎮 Grafikkarten-Temperatur: nur bei NVIDIA-Karten\n"));
        }
        sb.append(Sprache.t("_CPU temperature would need admin rights -- left out on purpose._",
                            "_CPU-Temperatur braucht Admin-Rechte -- bewusst weggelassen._"));
        client.sendCard(Sprache.t("Machine values", "Rechnerwerte"), sb.toString(), DiscordClient.FARBE_INFO);
    }

    /** {Temperatur, Last, Name} der NVIDIA-Karte, oder null. */
    private static String[] nvidia() {
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=temperature.gpu,utilization.gpu,name",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String zeile = new String(p.getInputStream().readAllBytes()).trim().split("\\R")[0];
            String[] t = zeile.split(",\\s*", 3);
            if (t.length < 3 || !t[0].matches("\\d+")) return null;
            return new String[]{t[0], t[1], t[2].replace("NVIDIA ", "")};
        } catch (Exception e) {
            return null;
        }
    }

    /** Vom Takt aus: !specs alle N Minuten. */
    private void specsPruefen() {
        if (specsMinuten <= 0) return;
        if (System.currentTimeMillis() - specsZuletzt < specsMinuten * 60_000L) return;
        specsZuletzt = System.currentTimeMillis();
        specsSchicken();
    }

    private LiveBild liveBildObjekt;

    /** "!live" -- ein Bild, das sich 5 Minuten lang alle 2 Sekunden erneuert. */
    private void live(String kanal, String rest) {
        if (liveBildObjekt == null) {
            // Zuerst nur das Spielfenster (16.10.); geht das nicht, wie bisher: das Bild des Bots
            // oder der ganze Bildschirm.
            liveBildObjekt = new LiveBild(client, () -> spielfoto.aufnehmen(engine.context().window()), () -> {
                BufferedImage frame = engine.context().frame();
                if (frame != null && engine.state() == BotEngine.State.LAEUFT) return frame;
                try {
                    java.awt.Rectangle ganz = new java.awt.Rectangle(
                            java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                    return new java.awt.Robot().createScreenCapture(ganz);
                } catch (Exception e) {
                    return frame;
                }
            });
        }
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith("stop") || w.startsWith("aus") || w.startsWith("off") || w.startsWith("end")) {
            liveBildObjekt.stoppen();
            return;
        }
        if (!liveBildObjekt.starten(kanal)) {
            client.send(Sprache.t("Already running. `!live stop` ends it.",
                                  "Laeuft schon. `!live stop` beendet es."));
        }
    }

    /** "!mute" -- alle Nachrichten ohne Benachrichtigung, "!unmute" zurueck. */
    private void mute(String verb, String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        boolean an = !(verb.equals("!unmute") || verb.equals("!laut")
                || w.startsWith("aus") || w.startsWith("off"));
        java.nio.file.Path datei = de.hustlebot.core.Ordner.bei("stumm.txt");
        try {
            if (an) java.nio.file.Files.writeString(datei, Sprache.t("!mute is on.\n", "!mute ist an.\n"));
            else java.nio.file.Files.deleteIfExists(datei);
        } catch (Exception e) {
            Log.warn("Discord", "stumm.txt: " + e.getMessage());
        }
        client.setStumm(false);
        client.sendCard(an ? Sprache.t("Muted", "Stumm") : Sprache.t("Sound on", "Laut"),
                an ? Sprache.t("Every bot message now arrives without a notification. `!unmute` switches it back.",
                               "Alle Nachrichten des Bots kommen jetzt ohne Benachrichtigung. `!unmute` schaltet zurueck.")
                   : Sprache.t("Bot messages notify you again.", "Die Nachrichten des Bots klingeln wieder."),
                DiscordClient.FARBE_INFO);
        client.setStumm(an);
    }

    /** "!queue" zeigt die Warteschlange, "!queue clear" leert die wartenden, "!queue del 2" nimmt eine raus. */
    private void queue(String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        synchronized (kette) {
            if (w.startsWith("clear") || w.startsWith("leer")) {
                while (kette.size() > 1) kette.remove(kette.size() - 1);
            } else if (w.matches("(del|delete|weg|loeschen|remove)\\s+\\d+")) {
                int nr = Integer.parseInt(w.replaceAll("\\D+", ""));
                if (nr >= 2 && nr <= kette.size()) kette.remove(nr - 1);
            }
            StringBuilder sb = new StringBuilder();
            if (kette.isEmpty()) {
                sb.append(Sprache.t("The queue is empty.", "Die Warteschlange ist leer."));
            } else {
                for (int i = 0; i < kette.size(); i++) {
                    Auftrag a = kette.get(i);
                    sb.append(i + 1).append(". ").append(i == 0 ? "▶ " : "· ")
                      .append(modusName(a.modus())).append(" x").append(a.runs());
                    if ("cathedral".equals(a.modus()) && !"a".equals(a.weg())) sb.append(" (").append(a.weg()).append(")");
                    sb.append('\n');
                }
            }
            sb.append(Sprache.t("\n`!queue clear` empties the waiting ones · `!queue del 2` removes number 2",
                                "\n`!queue clear` leert die wartenden · `!queue del 2` nimmt Nummer 2 heraus"));
            client.sendCard(Sprache.t("Queue", "Warteschlange"), sb.toString(), DiscordClient.FARBE_INFO);
        }
    }

    /** "!ende 06:30" -- nach dem Durchlauf um 06:30 aufhoeren, der PC bleibt an. */
    private void ende(String rest) {
        String w = rest == null ? "" : rest.trim();
        if (w.isEmpty() || w.matches("(?i)weg|aus|off|delete")) {
            engine.setStopAtClock("");
            client.send(Sprache.t("No stop time set.", "Keine Endzeit gesetzt."));
            return;
        }
        String uhr = normalizeClock(w);
        engine.setStopAtClock(uhr.isEmpty() ? w : uhr);
        if (engine.stopAtMillis() <= 0) {
            client.send(Sprache.t("Time not understood, e.g. `!ende 06:30` or `!ende 6am`.",
                                  "Uhrzeit nicht verstanden, z.B. `!ende 06:30` oder `!ende 6am`."));
            return;
        }
        client.sendCard(Sprache.t("Stop time set", "Endzeit gesetzt"),
                Sprache.t("The bot finishes the run that is going on at ", "Der Bot beendet um ")
                + engine.stopAtClock()
                + Sprache.t(" and stops. The PC stays on (for switching off: `!off`).",
                            " den laufenden Durchlauf und hoert auf. Der PC bleibt an (Ausschalten: `!off`)."),
                DiscordClient.FARBE_INFO);
    }

    /**
     * "!text an": Antworten als normaler Text statt als Karte -- fuer Freunde, bei denen die
     * Karten leer ankommen (Discord-Einstellung "Linkvorschau/Einbettungen" aus, oder dem Bot
     * fehlt im Kanal das Recht "Links einbetten"). "!text aus" = wieder Karten.
     */
    private void nurText(String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (istAn(w)) client.setNurText(true);
        else if (istAus(w)) client.setNurText(false);
        // ohne Wort: nur den Stand zeigen (Code-Pruefung 30.09.: kein Umschalten aus Versehen)
        client.sendCard(Sprache.t("Text or cards", "Text oder Karten"),
                client.nurText()
                    ? Sprache.t("The bot now answers as plain text (saved). `!text off` brings the cards back.",
                                "Der Bot antwortet jetzt als normaler Text (gespeichert). `!text aus` bringt die Karten zurueck.")
                    : Sprache.t("The bot answers with cards again. If they look empty: `!text on`, or in Discord "
                                + "Settings -> Appearance -> Messages switch on \"Show embeds and link previews\".",
                                "Der Bot antwortet wieder mit Karten. Sehen sie leer aus: `!text an`, oder in Discord "
                                + "unter Einstellungen -> Erscheinungsbild -> Nachrichten \"Einbettungen und Linkvorschau anzeigen\" einschalten."),
                DiscordClient.FARBE_INFO);
    }

    private void sprache(Sprache.Lang lang) {
        Sprache.set(lang);
        // 11.10.: in sprache.txt gemerkt -- gilt nach dem Neustart weiter, auch fuer Settings.exe und Capture-Images.exe.
        boolean gemerkt = Sprache.speichern(lang);
        client.sendCard(lang == Sprache.Lang.EN ? "Language: English" : "Sprache: Deutsch",
                (lang == Sprache.Lang.EN ? "Everything the bot writes is now in English. Back with `!deutsch`."
                                         : "Alles, was der Bot schreibt, steht jetzt auf Deutsch. Zurueck mit `!english`.")
                + (gemerkt ? (lang == Sprache.Lang.EN ? "\nSaved -- still applies after a restart."
                                                      : "\nGespeichert -- gilt auch nach einem Neustart.")
                           : (lang == Sprache.Lang.EN ? "\nCould not save the choice (sprache.txt); it applies until the next restart."
                                                      : "\nKonnte die Wahl nicht speichern (sprache.txt); sie gilt bis zum naechsten Neustart.")),
                DiscordClient.FARBE_ERFOLG);
    }

    private long pcNeustartGefragt = 0;
    private long pcAusGefragt = 0;

    /** "!pc off" (in 60 s), "!pc off now" (zweimal), "!pc restart" (zweimal), "!pc cancel". */
    private void pc(String rest) {
        String w = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith("restart") || w.startsWith("neustart") || w.startsWith("reboot")) {
            long jetzt = System.currentTimeMillis();
            if (jetzt - pcNeustartGefragt > 60_000) {
                pcNeustartGefragt = jetzt;
                client.sendCard(Sprache.t("Restart the PC?", "PC neu starten?"),
                        Sprache.t("This restarts **Windows**. Type `!pc restart` again within a minute.",
                                  "Das startet **Windows** neu. Tippe `!pc restart` innerhalb einer Minute noch einmal."),
                        DiscordClient.FARBE_WARNUNG);
                return;
            }
            pcNeustartGefragt = 0;
            PowerControl.Result r = PowerControl.restart(60, Sprache.t("Restart via Discord", "Neustart ueber Discord"));
            client.sendCard(Sprache.t("PC restarting", "PC startet neu"), r.message(),
                    r.ok() ? DiscordClient.FARBE_ERFOLG : DiscordClient.FARBE_FEHLER);
            return;
        }
        if (w.startsWith("cancel") || w.startsWith("abbruch") || w.startsWith("stop")) {
            PowerControl.Result r = PowerControl.cancel();
            client.sendCard(Sprache.t("Cancelled", "Abgebrochen"), r.message(), DiscordClient.FARBE_INFO);
            return;
        }
        if (w.startsWith("off") || w.startsWith("aus")) {
            boolean sofort = w.contains("now") || w.contains("jetzt") || w.contains("sofort");
            if (sofort) {
                // Sofort-Aus (5 s, kaum Zeit fuer !pc cancel) wie der Neustart nur nach zweimal Tippen:
                // seit den neuen Schreibweisen ist "!shutdown jetzt" schnell getippt (Freunde-Pruefung 11.10.).
                long jetzt = System.currentTimeMillis();
                if (jetzt - pcAusGefragt > 60_000) {
                    pcAusGefragt = jetzt;
                    client.sendCard(Sprache.t("Shut down the PC right away?", "PC sofort ausschalten?"),
                            Sprache.t("This shuts down **Windows** in 5 seconds, not only the bot. Unsaved work on the PC "
                                    + "is lost. Type the command again within a minute. Only stop the bot: `!stop`.",
                                    "Das faehrt **Windows** in 5 Sekunden herunter, nicht nur den Bot. Ungespeicherte Arbeit "
                                    + "am PC geht verloren. Tippe den Befehl innerhalb einer Minute noch einmal. "
                                    + "Nur den Bot anhalten: `!stop`."),
                            DiscordClient.FARBE_WARNUNG);
                    return;
                }
                pcAusGefragt = 0;
            }
            engine.stop(Sprache.t("PC is being shut down", "PC wird ausgeschaltet"));
            PowerControl.Result r = PowerControl.shutdown(sofort ? 5 : 60, Sprache.t("Shutdown via Discord", "Ausschalten ueber Discord"));
            client.sendCard(Sprache.t("PC shutting down", "PC faehrt herunter"),
                    r.message() + Sprache.t("\n`!pc cancel` stops it.", "\n`!pc cancel` bricht ab."),
                    r.ok() ? DiscordClient.FARBE_WARNUNG : DiscordClient.FARBE_FEHLER);
            return;
        }
        client.sendCard("!pc", Sprache.t(
                "`!pc off` – shut down in 60 s  ·  `!pc off now` – right away (type twice)\n"
                + "`!pc restart` – restart Windows (type twice)  ·  `!pc cancel` – take it back",
                "`!pc off` – in 60 s herunterfahren  ·  `!pc off now` – sofort (zweimal tippen)\n"
                + "`!pc restart` – Windows neu starten (zweimal tippen)  ·  `!pc cancel` – zuruecknehmen"),
                DiscordClient.FARBE_INFO);
    }

    /**
     * Macht aus "6:30", "0630", "15 uhr", "3pm", "3:30 pm" ein "06:30"/"15:30".
     * Leer bei Unsinn. Gleich wie im Hauptbot.
     */
    static String normalizeClock(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        boolean pm = t.endsWith("pm") || t.endsWith("p.m.");
        boolean am = t.endsWith("am") || t.endsWith("a.m.");
        boolean uhr = t.endsWith("uhr");
        if (pm || am) t = t.replaceAll("(p\\.m\\.|a\\.m\\.|pm|am)$", "");
        if (uhr) t = t.substring(0, t.length() - 3);
        String clock = t.replace('.', ':').replace('-', ':');
        if (clock.matches("\\d{1,2}") && (pm || am || uhr)) clock = clock + ":00";
        if (clock.matches("\\d{3,4}")) {
            int cut = clock.length() - 2;
            clock = clock.substring(0, cut) + ":" + clock.substring(cut);
        }
        if (!clock.matches("\\d{1,2}:\\d{2}")) return "";
        String[] parts = clock.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (pm || am) {
            if (hour < 1 || hour > 12) return "";
            if (pm && hour < 12) hour += 12;
            if (am && hour == 12) hour = 0;
        }
        if (hour > 23 || minute > 59) return "";
        return String.format("%02d:%02d", hour, minute);
    }

    // --- !clear ---------------------------------------------------------

    private void clear(String kanal, String rest) {
        // Nur ein ausdrueckliches "ja"/"yes" (oder der Knopf) leert den Kanal -- blosses Wiederholen
        // von "!clear" zaehlt nicht mehr als Ja (Freunde-Pruefung 11.10.).
        boolean ja = rest.toLowerCase(Locale.ROOT).startsWith("ja")
                || rest.toLowerCase(Locale.ROOT).startsWith("yes");
        if (!ja) {
            client.sendCardMitKnoepfen(kanal, Sprache.t("Empty this channel?", "Kanal leeren?"),
                    Sprache.t("Deletes **all** messages here. Cannot be undone. `!clear yes` or the button.",
                              "Loescht **alle** Nachrichten hier. Nicht rueckgaengig zu machen. `!clear ja` oder der Knopf."),
                    DiscordClient.FARBE_WARNUNG,
                    List.<String[]>of(new String[]{Sprache.t("Yes, empty it", "Ja, leeren"), "!clear ja"}));
            return;
        }
        Thread t = new Thread(() -> {
            int n = client.kanalLeeren(kanal);
            client.sendCardTo(kanal, n < 0 ? Sprache.t("Not allowed", "Nicht erlaubt")
                                           : Sprache.t("Channel emptied", "Kanal geleert"),
                    n < 0 ? Sprache.t("The bot needs the permission **Manage Messages** in this channel.",
                                      "Dem Bot fehlt hier das Recht **Nachrichten verwalten**.")
                          : n + Sprache.t(" messages deleted.", " Nachrichten geloescht."),
                    n < 0 ? DiscordClient.FARBE_WARNUNG : DiscordClient.FARBE_ERFOLG);
        }, "discord-leeren");
        t.setDaemon(true);
        t.start();
    }

    // --- !shortcut ------------------------------------------------------

    private static final java.util.Set<String> BEFEHLE = java.util.Set.of(
            "!help", "!start", "!stop", "!burg", "!castle", "!home", "!pause", "!resume", "!status",
            // 16.10. (Steuerprogramm aus dem Hauptbot): sonst koennte ein Kuerzel diese Woerter belegen.
            "!team", "!teamscan", "!ablauf", "!routine", "!kriegsplan", "!zeitzone", "!timezone", "!autostart",
            "!neustart", "!restart",
            "!after", "!clanwar", "!settings", "!einstellungen", "!aufstellung", "!screenshot", "!fotoserie", "!photoseries", "!lesetest", "!readtest", "!opfer", "!sacrifice", "!reiniger", "!cleaner",
            "!stats", "!times", "!zeiten", "!kathedralzeiten", "!cathedraltimes", "!log", "!bericht", "!report", "!off", "!aus", "!feierabend", "!specs", "!werte",
            "!live", "!mute", "!stumm", "!unmute", "!laut", "!update", "!updates", "!neu", "!shortcut",
            "!shortcuts", "!kuerzel", "!kürzel", "!clear", "!leeren", "!queue", "!wq", "!warteschlange",
            "!ende", "!end", "!english", "!englisch", "!en", "!deutsch", "!german", "!de", "!pc",
            "!rechner", "!notaus", "!not-aus", "!kill", "!emergency", "!text", "!nurtext", "!plain",
            "!titan", "!grabung", "!excavation", "!graben", "!titangraben", "!titanstart", "!stigmas", "!stigma",
            "!gembay", "!edelsteinbucht", "!gembucht", "!portal", "!foodchests", "!nahrungskisten",
            "!glyphs", "!glyphen", "!glyph", "!glyphe");

    /** So viele Zeichen zeigt "!glyphs" auf einmal. */
    private static final int GLYPHEN_JE_BILD = 30;

    /**
     * Autor 24.10.: "!glyphs" -- die Zeichen der eigenen Namens-Schrift (Glyphenschrift), die noch keinen Buchstaben
     * haben, als Bild: je Zeile die Nummer, das Zeichen und ein Namensbild, in dem es vorkommt (rot unterstrichen).
     * "!glyphs 120" = ab Nummer 120. Beschriften mit "!glyph <Nummer> <Zeichen>".
     */
    private void glyphenZeigen(String rest) {
        var gs = de.hustlebot.core.Glyphenschrift.standard();
        java.nio.file.Path ordner = de.hustlebot.core.Glyphenschrift.ORDNER;
        String z = rest == null ? "" : rest.replaceAll("[^0-9]", "");
        int ab = z.isEmpty() || z.length() > 7 ? 1 : Integer.parseInt(z);
        java.util.TreeSet<Integer> nummern = new java.util.TreeSet<>();
        try (var s = java.nio.file.Files.list(ordner)) {
            s.forEach(p -> {
                String f = p.getFileName().toString();
                if (f.matches("\\d{1,7}\\.png")) nummern.add(Integer.parseInt(f.substring(0, f.length() - 4)));
            });
        } catch (Exception e) {
            // noch keine Bibliothek
        }
        List<Integer> zeigen = new ArrayList<>();
        int offen = 0, weiterAb = -1;
        for (int nr : nummern) {
            if (gs.zeichen(nr) != null) continue;
            offen++;
            if (nr < ab) continue;
            if (zeigen.size() < GLYPHEN_JE_BILD) zeigen.add(nr);
            else if (weiterAb < 0) weiterAb = nr;
        }
        if (zeigen.isEmpty()) {
            client.sendCard(Sprache.t("Name font", "Namens-Schrift"), nummern.isEmpty()
                    ? Sprache.t("No characters collected yet -- they come with the arena.",
                                "Noch keine Zeichen gesammelt -- sie kommen mit der Arena.")
                    : Sprache.t("All " + nummern.size() + " characters have a letter" + (ab > 1 ? " (from number " + ab + ")" : "") + ".",
                                "Alle " + nummern.size() + " Zeichen haben einen Buchstaben" + (ab > 1 ? " (ab Nummer " + ab + ")" : "") + "."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(glyphenTafel(gs, ordner, zeigen), "png", out);
            client.sendFile(Sprache.t("**Name font: " + offen + " characters without a letter.** Label one with `!glyph <number> <character>`,"
                            + " e.g. `!glyph 17 a` or `!glyph 17 𝔍` (any character). `!glyph 17 part` = second half of the character before,"
                            + " `!glyph 17 clear` = remove." + (weiterAb > 0 ? " More: `!glyphs " + weiterAb + "`" : ""),
                            "**Namens-Schrift: " + offen + " Zeichen ohne Buchstaben.** Beschriften mit `!glyph <Nummer> <Zeichen>`,"
                            + " z. B. `!glyph 17 a` oder `!glyph 17 𝔍` (jedes Zeichen). `!glyph 17 teil` = zweite Haelfte des Zeichens davor,"
                            + " `!glyph 17 weg` = Zuordnung loeschen." + (weiterAb > 0 ? " Weitere: `!glyphs " + weiterAb + "`" : "")),
                    "glyphen.png", out.toByteArray());
        } catch (Exception e) {
            client.sendCard(Sprache.t("Name font", "Namens-Schrift"),
                    Sprache.t("Picture could not be made: ", "Bild ging nicht: ") + e.getMessage(), DiscordClient.FARBE_WARNUNG);
        }
    }

    /** Je Zeile: Nummer, Glyphe (vergroessert) und ein Namensbild, in dem sie vorkommt -- dort rot unterstrichen. */
    private static BufferedImage glyphenTafel(de.hustlebot.core.Glyphenschrift gs, java.nio.file.Path ordner,
                                              List<Integer> nummern) {
        // Beispiel-Namen aus glyphen\namen\namen.txt: kurz TAB Fingerabdruck TAB Schrift TAB Zeit
        java.util.Map<Integer, String[]> beispiel = new java.util.HashMap<>();
        try {
            java.nio.file.Path liste = ordner.resolve("namen").resolve("namen.txt");
            if (java.nio.file.Files.exists(liste)) {
                for (String zeile : de.hustlebot.core.Dateien.zeilen(liste)) {
                    String[] t = zeile.split("\t", -1);
                    if (t.length < 2) continue;
                    int[] nr = de.hustlebot.core.Glyphenschrift.nummern(t[1]);
                    for (int i = 0; i < nr.length; i++) {
                        if (nummern.contains(nr[i]) && !beispiel.containsKey(nr[i])) {
                            beispiel.put(nr[i], new String[]{t[0].strip(), String.valueOf(i), String.valueOf(nr.length),
                                    t.length > 2 ? t[2] : ""});
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.debug("Discord", "namen.txt: " + e.getMessage());
        }
        final int zeile = 50, breite = 820;
        BufferedImage tafel = new BufferedImage(breite, zeile * nummern.size() + 8, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = tafel.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new java.awt.Color(0x2b2d31));
            g.fillRect(0, 0, tafel.getWidth(), tafel.getHeight());
            for (int k = 0; k < nummern.size(); k++) {
                int nr = nummern.get(k), y = 4 + k * zeile;
                if (k % 2 == 1) {
                    g.setColor(new java.awt.Color(0x313338));
                    g.fillRect(0, y, breite, zeile);
                }
                g.setColor(java.awt.Color.WHITE);
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 18));
                g.drawString(String.valueOf(nr), 10, y + 31);
                try {
                    BufferedImage gl = ImageIO.read(gs.glyphenBild(nr).toFile());
                    if (gl != null) g.drawImage(gl, 80, y + 4, 39, 42, null);
                } catch (Exception e) {
                    // Bild fehlt -- nur die Nummer
                }
                String[] b = beispiel.get(nr);
                if (b == null) continue;
                try {
                    BufferedImage name = ImageIO.read(ordner.resolve("namen").resolve(b[0] + ".png").toFile());
                    if (name == null) continue;
                    double f = Math.min(40.0 / name.getHeight(), 480.0 / name.getWidth());
                    int w = (int) Math.round(name.getWidth() * f), h = (int) Math.round(name.getHeight() * f);
                    int x0 = 140, y0 = y + (zeile - h) / 2;
                    g.drawImage(name, x0, y0, w, h, null);
                    // das Zeichen im Namen unterstreichen (Namensbild noch einmal zerlegen; nur wenn die Zahl passt)
                    var zl = de.hustlebot.core.GlyphenZerlegung.zerlegen(name);
                    int i = Integer.parseInt(b[1]);
                    if (zl.anzahl() == Integer.parseInt(b[2])) {
                        var st = zl.stuecke().get(i);
                        g.setColor(new java.awt.Color(0xff4040));
                        g.fillRect(x0 + (int) Math.round(st.x0() * f), Math.min(y + zeile - 4, y0 + h + 1),
                                Math.max(3, (int) Math.round(st.breite() * f)), 3);
                    }
                    g.setColor(new java.awt.Color(0xb5bac1));
                    g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 15));
                    if (!b[3].isBlank()) g.drawString(b[3], x0 + w + 14, y + 30);
                } catch (Exception e) {
                    // Beispiel fehlt -- nur Nummer und Zeichen
                }
            }
        } finally {
            g.dispose();
        }
        return tafel;
    }

    /**
     * Autor 24.10.: "!glyph 17 a" -- Zeichen Nummer 17 der Namens-Schrift ist ein "a" (jedes Unicode-Zeichen, auch 𝔍,
     * 元 oder ع). "!glyph 17 teil"/"part" = zweite Haelfte des Zeichens davor, "!glyph 17 weg"/"clear" = Zuordnung von
     * Hand loeschen. Danach wird der Notizblock neu geschrieben, damit die Namen gleich mit den Zeichen dastehen.
     */
    private void glypheBeschriften(String rest) {
        String r = rest == null ? "" : rest.strip();
        String[] t = r.split("\\s+", 2);
        if (t.length < 2 || !t[0].matches("\\d{1,7}")) {
            client.sendCard(Sprache.t("Name font", "Namens-Schrift"),
                    Sprache.t("Use: `!glyph <number> <character>`, e.g. `!glyph 17 a`. The numbers are in `!glyphs`.",
                              "So geht es: `!glyph <Nummer> <Zeichen>`, z. B. `!glyph 17 a`. Die Nummern zeigt `!glyphs`."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        int nr = Integer.parseInt(t[0]);
        String zeichen = t[1].strip();
        if (zeichen.length() >= 2 && zeichen.startsWith("`") && zeichen.endsWith("`")) zeichen = zeichen.substring(1, zeichen.length() - 1).strip();
        String wort = zeichen.toLowerCase(Locale.ROOT);
        String wert = switch (wort) {
            case "part", "teil" -> "";
            case "clear", "weg", "delete", "loeschen", "löschen", "none" -> null;
            default -> zeichen;
        };
        if (wert != null && (wert.isEmpty() && !wort.equals("part") && !wort.equals("teil") || wert.codePointCount(0, wert.length()) > 4)) {
            client.sendCard(Sprache.t("Name font", "Namens-Schrift"),
                    Sprache.t("One character please (at most 4), e.g. `!glyph " + nr + " a`.",
                              "Bitte ein Zeichen (hoechstens 4), z. B. `!glyph " + nr + " a`."), DiscordClient.FARBE_WARNUNG);
            return;
        }
        var gs = de.hustlebot.core.Glyphenschrift.standard();
        boolean ok = gs.beschriften(nr, wert);
        if (ok) {
            try {
                de.hustlebot.core.Gegnerbuch.gemeinsam().neuSchreiben();
            } catch (Exception e) {
                Log.debug("Discord", "Notizblock nach !glyph: " + e.getMessage());
            }
        }
        client.sendCard(Sprache.t("Name font", "Namens-Schrift"), !ok
                ? Sprache.t("There is no character number " + nr + " (see `!glyphs`).",
                            "Ein Zeichen Nummer " + nr + " gibt es nicht (siehe `!glyphs`).")
                : wert == null ? Sprache.t("Character " + nr + ": manual label removed.", "Zeichen " + nr + ": Zuordnung von Hand geloescht.")
                : wert.isEmpty() ? Sprache.t("Character " + nr + " = second half of the character before (shows nothing).",
                                             "Zeichen " + nr + " = zweite Haelfte des Zeichens davor (zeigt nichts).")
                : Sprache.t("Character " + nr + " = „" + wert + "“", "Zeichen " + nr + " = „" + wert + "“"),
                ok ? DiscordClient.FARBE_INFO : DiscordClient.FARBE_WARNUNG);
    }

    private Kuerzel kuerzelObjekt;

    private synchronized Kuerzel kuerzel() {
        if (kuerzelObjekt == null) {
            Kuerzel.setDatei(de.hustlebot.core.Ordner.bei("kuerzel.txt"));
            java.util.Set<String> alle = new java.util.HashSet<>(BEFEHLE);
            alle.addAll(Befehlsnamen.alleSchreibweisen());   // 11.10. (Hauptbot): auch "!shutdown", "!repair" ...
            kuerzelObjekt = new Kuerzel(alle);
        }
        return kuerzelObjekt;
    }

    private volatile Kuerzel.Ziel kuerzelWahl;
    private volatile String kuerzelFreund = "";
    private volatile long kuerzelWahlUm;

    /** Wie im Hauptbot: !shortcut -> Modus waehlen -> Wort waehlen. */
    private void shortcut(String kanal, String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        String[] w = r.isEmpty() ? new String[0] : r.split("\\s+");
        boolean wahlOffen = kuerzelWahl != null && System.currentTimeMillis() - kuerzelWahlUm < 600_000;

        if (w.length > 0 && w[0].matches("liste|list|alle|all")) {
            StringBuilder t = new StringBuilder();
            var alle = kuerzel().alle();
            if (alle.isEmpty()) t.append(Sprache.t("No shortcuts yet.", "Noch keine Kuerzel."));
            alle.forEach((k, e) -> t.append("`!").append(k).append("` → ")
                    .append(Kuerzel.beschreibung(e, !Sprache.istEnglisch())).append('\n'));
            client.sendCardTo(kanal, Sprache.t("Your shortcuts", "Deine Kuerzel"), t.toString(), DiscordClient.FARBE_INFO);
            return;
        }
        if (w.length > 1 && w[0].matches("loeschen|löschen|delete|del|weg")) {
            boolean weg = kuerzel().loeschen(w[1]);
            client.send(weg ? Sprache.t("`!" + w[1] + "` deleted.", "`!" + w[1] + "` geloescht.")
                            : Sprache.t("There is no `!" + w[1] + "`.", "`!" + w[1] + "` gibt es nicht."));
            return;
        }
        if (w.length == 0 || w[0].matches("neu|new")) {
            kuerzelWahl = null;
            List<String[]> knoepfe = new ArrayList<>();
            StringBuilder text = new StringBuilder(Sprache.t(
                    "**Step 1 of 2 – what should it start?** Tap a button or type `!shortcut 3`.\n\n",
                    "**Schritt 1 von 2 – was soll es starten?** Knopf druecken oder `!shortcut 3` tippen.\n\n"));
            int nr = 1;
            for (Kuerzel.Ziel z : Kuerzel.Ziel.values()) {
                String name = Sprache.t(z.en, z.de);
                text.append('`').append(nr).append("` ").append(name).append('\n');
                knoepfe.add(new String[]{nr + " · " + name, "!shortcut wahl " + nr});
                nr++;
            }
            client.sendCardMitKnoepfen(kanal, Sprache.t("Create a shortcut", "Kuerzel anlegen"),
                    text.toString(), DiscordClient.FARBE_INFO, knoepfe);
            return;
        }
        boolean istWahl = w[0].equals("wahl") || w[0].equals("choose");
        boolean istWort = w[0].equals("wort") || w[0].equals("word");
        String[] arg = istWahl || istWort ? java.util.Arrays.copyOfRange(w, 1, w.length) : w;
        if (arg.length == 0) { shortcut(kanal, ""); return; }

        if (istWahl || (!istWort && !wahlOffen)
                || (!istWort && arg.length > 1 && Kuerzel.zielAus(String.join(" ", arg)) != null)) {
            Kuerzel.Ziel ziel = Kuerzel.zielAus(String.join(" ", arg));
            if (ziel == null) {
                client.send(Sprache.t("Pick 1 to 6 or e.g. `cath c`. `!shortcut` shows the list.",
                                      "Nimm 1 bis 6 oder z.B. `kath c`. `!shortcut` zeigt die Liste."));
                return;
            }
            String freund = "";
            if (ziel == Kuerzel.Ziel.VERLIES_FREUND) {
                String letztes = arg[arg.length - 1];
                if (arg.length > 1 && !letztes.matches("\\d+")) freund = letztes;
                if (freund.isEmpty()) {
                    client.send(Sprache.t("With whom? e.g. `!shortcut 5 lisa`", "Mit wem? z.B. `!shortcut 5 lisa`"));
                    return;
                }
            }
            kuerzelWahl = ziel;
            kuerzelFreund = freund;
            kuerzelWahlUm = System.currentTimeMillis();
            List<String> vorschlaege = kuerzel().vorschlaege(ziel);
            List<String[]> knoepfe = new ArrayList<>();
            for (String v : vorschlaege) knoepfe.add(new String[]{"!" + v, "!shortcut wort " + v});
            client.sendCardMitKnoepfen(kanal, Sprache.t("Shortcut for: ", "Kuerzel fuer: ") + Sprache.t(ziel.en, ziel.de),
                    Sprache.t("**Step 2 of 2 – pick a word**, or your own: `!shortcut maus` / `!shortcut 7`",
                              "**Schritt 2 von 2 – such dir ein Wort aus**, oder ein eigenes: `!shortcut maus` / `!shortcut 7`"),
                    DiscordClient.FARBE_INFO, knoepfe);
            return;
        }
        if (!wahlOffen) {
            client.send(Sprache.t("First pick a mode: `!shortcut`", "Erst einen Modus waehlen: `!shortcut`"));
            return;
        }
        String wort = arg[0].startsWith("!") ? arg[0].substring(1) : arg[0];
        String grund = kuerzel().istFrei(wort);
        if (grund != null) {
            client.send("`!" + wort + "`: " + grund);
            return;
        }
        kuerzel().setzen(wort, kuerzelWahl, kuerzelFreund);
        client.sendCardTo(kanal, Sprache.t("Shortcut saved", "Kuerzel gespeichert"),
                "`!" + wort + "` → " + Kuerzel.beschreibung(new Kuerzel.Eintrag(kuerzelWahl, kuerzelFreund),
                        !Sprache.istEnglisch())
                + Sprache.t("\n`!" + wort + " 25` = 25 runs. Upper/lower case does not matter.",
                            "\n`!" + wort + " 25` = 25 Runs. Gross/klein ist egal."),
                DiscordClient.FARBE_ERFOLG);
        kuerzelWahl = null;
    }

    /**
     * "!off 06:30" faehrt den PC um 06:30 herunter -- ein laufender Durchlauf
     * wird vorher noch beendet. "!off delete" (oder "aus"/"weg") nimmt es
     * zurueck.
     */
    private void off(String rest) {
        String w = rest == null ? "" : rest.trim();

        if (w.isBlank()) {
            if (offAtMillis > 0) {
                client.send(Sprache.t("PC shutdown is set to **", "PC-Abschaltung steht auf **") + offClock + "** (in "
                        + Math.max(0, (offAtMillis - System.currentTimeMillis()) / 60_000)
                        + Sprache.t(" minutes). Cancel with `!off delete`.", " Minuten). Ausschalten mit `!off delete`."));
            } else {
                client.send(Sprache.t("No shutdown set. Example: `!off 06:30` shuts the PC down at 06:30 (the running run is finished first). Take back: `!off delete`.", "Keine Abschaltung gesetzt. Beispiel: `!off 06:30` – dann fährt "
                        + "der PC um 06:30 herunter (der laufende Durchlauf wird noch beendet). "
                        + "Zurücknehmen: `!off delete`."));
            }
            return;
        }

        String klein = w.toLowerCase(Locale.ROOT);
        if (klein.startsWith("delete") || klein.startsWith("aus") || klein.startsWith("weg")
                || klein.startsWith("stop") || klein.startsWith("ab")) {
            boolean liefSchon = offAusgeloest;
            offAtMillis = 0;
            offClock = "";
            offMerken();
            offAusgeloest = false;
            engine.setStopAtClock("");
            if (liefSchon) {
                PowerControl.Result r = PowerControl.cancel();
                client.sendCard(Sprache.t("Shutdown cancelled", "Abschaltung zurückgenommen"),
                        Sprache.t("The shutdown was stopped. ", "Das Herunterfahren wurde gestoppt. ") + r.message(),
                        DiscordClient.FARBE_ERFOLG);
            } else {
                client.sendCard(Sprache.t("Shutdown off", "Abschaltung aus"),
                        Sprache.t("Nothing will be shut down.", "Es wird nichts mehr heruntergefahren."), DiscordClient.FARBE_ERFOLG);
            }
            return;
        }

        // "!off now" -- sofort, wie !pc off now.
        if (klein.matches("(now|jetzt|sofort)\\b.*")) {
            pc("off now");
            return;
        }

        // Eine Uhrzeit, auch "3pm", "3:30 pm", "15 uhr". Der Motor prueft und
        // rechnet den naechsten Zeitpunkt aus.
        String uhr = normalizeClock(w);
        engine.setStopAtClock(uhr.isEmpty() ? w : uhr);
        long ziel = engine.stopAtMillis();
        if (ziel <= 0) {
            client.send(Sprache.t("Time not understood. Please as **HH:MM** or 3pm, e.g. `!off 06:30`.", "Die Uhrzeit habe ich nicht verstanden. Bitte als **HH:MM**, "
                    + "z.B. `!off 06:30`."));
            return;
        }
        offAtMillis = ziel;
        offClock = engine.stopAtClock();
        offMerken();
        offAusgeloest = false;

        long minuten = Math.max(0, (ziel - System.currentTimeMillis()) / 60_000);
        client.sendCard(Sprache.t("PC shutdown set", "PC-Abschaltung gesetzt"), Sprache.t("At **" + offClock + "** (in " + minuten + " minutes) the PC shuts down. A running run is finished first. Take back: `!off delete`.",
                "Um **" + offClock + "** (in " + minuten + " Minuten) fährt der PC herunter. "
                + "Ein laufender Durchlauf wird vorher noch beendet. Zurücknehmen: `!off delete`."),
                DiscordClient.FARBE_WARNUNG);
    }

    /** Wache: ist die Abschaltzeit erreicht und der Bot steht, wird heruntergefahren. */
    private void offPruefen() {
        if (offAtMillis <= 0 || offAusgeloest) return;
        if (System.currentTimeMillis() < offAtMillis) return;

        // Laeuft noch ein Durchlauf, erst den Motor sauber anhalten lassen --
        // setStopAtClock hat ihn auf dieselbe Zeit gestellt, er beendet den
        // laufenden Durchlauf und geht dann auf FERTIG/GESTOPPT. Herunterfahren
        // erst, wenn wirklich nichts mehr laeuft.
        BotEngine.State s = engine.state();
        if (s == BotEngine.State.LAEUFT || s == BotEngine.State.PAUSIERT) return;

        offAusgeloest = true;
        PowerControl.shutdown(60, Sprache.t("Bot shutdown time " + offClock + " reached.", "Bot-Abschaltzeit " + offClock + " erreicht."));
        client.sendCard(Sprache.t("PC shutting down", "PC wird heruntergefahren"), Sprache.t("The shutdown time **" + offClock + "** is reached. The PC shuts down in 60 seconds. Cancel with `!off delete`.",
                "Die Abschaltzeit **" + offClock + "** ist erreicht. Der PC fährt in 60 Sekunden "
                + "herunter. Abbrechen mit `!off delete`."), DiscordClient.FARBE_WARNUNG);
    }

    // ------------------------------------------------------------------
    //  Not-Aus -- eine Taste haelt den Bot ueberall sofort an
    // ------------------------------------------------------------------

    /** Von Main einmal beim Start aufgerufen: Taste aus der Konfig, Datei zum Speichern. */
    public void notAusStarten(String taste, java.nio.file.Path configDatei) {
        this.configDatei = configDatei;
        notAusNeu(taste);
    }

    /**
     * F10 = Pause UND Weiter, F11 = Weiter (aus dem Hauptbot; der Tagestipp kuendigte die Tasten an, eingebaut waren sie nicht).
     */
    public void notPauseStarten(String pauseTaste, String weiterTaste) {
        NotAusWache p = NotAusWache.aus(pauseTaste, "Pause", Sprache.t("pauses the bot / resumes it", "pausiert den Bot / setzt ihn fort"), () -> {
            if (engine.state() == BotEngine.State.PAUSIERT) {
                engine.togglePause();
                client.sendCard(Sprache.t("Resumed", "Fortgesetzt"), Sprache.t("Key " + pauseTaste + " -- the bot continues.",
                        "Taste " + pauseTaste + " -- der Bot macht weiter."), DiscordClient.FARBE_INFO);
                return;
            }
            if (engine.state() != BotEngine.State.LAEUFT) return;
            engine.togglePause();
            client.sendCard(Sprache.t("Paused", "Pausiert"), Sprache.t("Key " + pauseTaste + " -- the bot pauses. Resume with "
                    + pauseTaste + " again or " + weiterTaste + ".", "Taste " + pauseTaste + " -- der Bot pausiert. Weiter mit "
                    + pauseTaste + " (noch einmal) oder " + weiterTaste + "."), DiscordClient.FARBE_INFO);
        });
        NotAusWache w = NotAusWache.aus(weiterTaste, "Weiter", Sprache.t("resumes the paused bot", "setzt den pausierten Bot fort"), () -> {
            if (engine.state() != BotEngine.State.PAUSIERT) return;
            engine.togglePause();
            client.sendCard(Sprache.t("Resumed", "Fortgesetzt"), Sprache.t("Key " + weiterTaste + " -- the bot continues.",
                    "Taste " + weiterTaste + " -- der Bot macht weiter."), DiscordClient.FARBE_INFO);
        });
        for (NotAusWache x : new NotAusWache[]{p, w}) {
            if (x == null) continue;
            Thread t = new Thread(x, "taste-" + x.taste());
            t.setDaemon(true);
            t.start();
        }
    }

    /** Mauswache: bewegt der Spieler die Maus, waehrend der Bot spielt, pausiert er; nach 5 min Ruhe geht es weiter. */
    public void mauswacheStarten() {
        de.hustlebot.core.Mauswache.starten(
                () -> engine.state() == BotEngine.State.LAEUFT,
                () -> engine.state() == BotEngine.State.PAUSIERT,
                () -> {
                    if (!de.hustlebot.core.Mauswache.an()) return false;   // Einstellung "Pause when the mouse moves: Off"
                    // Autor 16.10.: Maus bewegt = STOPP, nicht Pause -- danach gleich den naechsten Modus starten koennen,
                    // ohne erst F10/F9. (Der Zustands-Melder raeumt bei GESTOPPT die Warteschlange auf.)
                    if (engine.state() != BotEngine.State.LAEUFT) return false;
                    mausStoppUm = System.currentTimeMillis();
                    engine.stop(Sprache.t("mouse moved", "Maus bewegt"));
                    client.sendCard(Sprache.t("Stopped (mouse)", "Gestoppt (Maus)"),
                            Sprache.t("You moved the mouse -- the bot stopped. Start the next mode whenever you like.",
                                      "Du hast die Maus bewegt -- der Bot hat gestoppt. Starte den naechsten Modus, wann du willst."),
                            DiscordClient.FARBE_INFO);
                    return true;
                },
                () -> {
                    if (!engine.fortsetzenOhneAutor()) return;
                    client.sendCard(Sprache.t("Resumed", "Fortgesetzt"),
                            Sprache.t("5 minutes without mouse movement -- the bot continues.",
                                      "5 Minuten keine Mausbewegung -- der Bot macht weiter."), DiscordClient.FARBE_INFO);
                });
    }

    /** (Neu) einrichten: alte Wache stoppen, neue mit dieser Taste starten. */
    private synchronized void notAusNeu(String taste) {
        if (notAusWache != null) {
            notAusWache.stoppen();
            notAusWache = null;
            notAusFaden = null;
        }
        NotAusWache wache = NotAusWache.aus(taste, () -> notAus(notAusTaste));
        if (wache == null) {
            notAusTaste = "";
            Log.info("Not-Aus", Sprache.t("No emergency key set.", "Keine Not-Aus-Taste gesetzt."));
            return;
        }
        notAusTaste = wache.taste();
        notAusWache = wache;
        notAusFaden = new Thread(wache, "not-aus-wache");
        notAusFaden.setDaemon(true);
        notAusFaden.start();
    }

    /** Wird von der Wache gerufen, wenn die Taste gedrueckt wurde. Wie !stop. */
    public void notAus(String taste) {
        synchronized (kette) {
            kette.clear();
        }
        engine.stop(Sprache.t("Emergency key " + taste + " pressed", "Not-Aus-Taste " + taste + " gedrueckt"));
        client.sendCard(Sprache.t("Emergency stop", "Not-Aus"), Sprache.t("Key **" + taste + "** was pressed – the bot has stopped and the queue is empty. Carry on with `!start …`.",
                "Taste **" + taste + "** wurde gedrückt – der Bot ist gestoppt und die "
                + "Warteschlange ist leer. Weiter geht es mit `!start …`."),
                DiscordClient.FARBE_WARNUNG);
    }

    /** !settings notaus <Taste> -- Taste umstellen oder mit "aus" abschalten. */
    private void notAusEinstellen(String taste) {
        String t = taste.trim();
        boolean abschalten = t.equalsIgnoreCase("aus") || t.equalsIgnoreCase("off")
                || t.equalsIgnoreCase("keine") || t.equalsIgnoreCase("nein");

        if (!abschalten && !NotAusWache.gueltig(t)) {
            client.sendCard(Sprache.t("Key not allowed", "Taste nicht erlaubt"), Sprache.t("\"" + t + "\" is not a known emergency key. Allowed: " + NotAusWache.erlaubteTasten() + ".\nExample: `!settings emergency F9`.",
                    "„" + t + "\" kenne ich nicht als Not-Aus-Taste. Erlaubt sind: "
                    + NotAusWache.erlaubteTasten() + ".\nBeispiel: `!settings notaus F9`."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }

        String zuSpeichern = abschalten ? "" : t;
        try {
            if (configDatei != null) {
                de.hustlebot.Einrichtung.einzelnSchreiben(
                        configDatei, de.hustlebot.Einrichtung.K_NOTAUS, zuSpeichern);
            }
        } catch (Exception e) {
            Log.error("Not-Aus", Sprache.t("Could not save the key.", "Konnte die Taste nicht speichern."), e);
            client.send(Sprache.t("The key could not be saved: ", "Die Taste ließ sich nicht speichern: ") + e.getMessage());
            return;
        }

        notAusNeu(zuSpeichern);

        if (abschalten) {
            client.sendCard(Sprache.t("Emergency key off", "Not-Aus aus"),
                    Sprache.t("No emergency key is set any more.", "Es ist keine Not-Aus-Taste mehr gesetzt."), DiscordClient.FARBE_ERFOLG);
        } else {
            // Ein Buchstabe wirkt auch beim Tippen in einem anderen Programm.
            // Genau daran ist die alte Taste ^ gescheitert -- deshalb hier ein
            // deutlicher Hinweis statt einer stillen Ueberraschung.
            String warnung = NotAusWache.imAlltagGefaehrlich(zuSpeichern)
                    ? Sprache.t("\n\n⚠️ Careful: this key works **everywhere** – even when you type it in "
                                + "another program, the bot stops. A function key like F9 is safer.",
                                "\n\n⚠️ Achtung: Diese Taste wirkt **überall** – auch wenn du sie in "
                                + "einem anderen Programm tippst, hält der Bot an. Sicherer ist eine "
                                + "Funktionstaste wie F9.")
                    : "";
            client.sendCard(Sprache.t("Emergency key set", "Not-Aus gesetzt"),
                    "**" + notAusTaste + Sprache.t("** stops the bot everywhere at once from now on – even if Discord is stuck. Stays after a restart.", "** hält den Bot ab jetzt überall sofort an – "
                    + "auch wenn Discord klemmt. Bleibt auch nach einem Neustart.") + warnung,
                    DiscordClient.FARBE_ERFOLG);
        }
    }

    // ------------------------------------------------------------------
    //  Die Wache
    // ------------------------------------------------------------------

    /**
     * Sieht alle zwei Sekunden nach, was der Motor macht.
     *
     * Zwei Aufgaben (unter anderem):
     *   - Ist ein Durchgang zu Ende, den naechsten aus der Kette starten
     *   - Ist ein Krieg faellig, ihn vorziehen
     */
    private void wacheLaeuft() {
        tippBeimStart();   // Tipp des Tages bei jedem Start (30.09.)
        while (true) {
            try {
                Thread.sleep(2000);
                zustandPruefen();
                motorPruefen();
                runPruefen();
                kriegPruefen();
                nebenbeiPruefen();
                resetStartPruefen();
                statsPruefen();
                watchPruefen();
                specsPruefen();
                offPruefen();
                tagestippPruefen();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.debug("Discord", "Watcher: " + e.getMessage());
            }
        }
    }

    /** Wann zuletzt versucht wurde, den Tipp zu schicken (nach einem Fehlschlag erst in 2 min wieder). */
    private long tippVersuchUm = 0;

    /**
     * Tipp des Tages (Autor, 30.09.): um 12:00, sonst nachts um 02:00 nachgeholt, im Hauptkanal
     * mit Erwaehnung (@everyone, einstellbar). Weitergezaehlt wird erst, wenn Discord ihn hat.
     */
    private void tagestippPruefen() {
        java.time.LocalDateTime jetzt = java.time.LocalDateTime.now();
        String[] t = de.hustlebot.core.Tagestipp.faellig(jetzt);
        if (t == null) return;
        long ms = System.currentTimeMillis();
        if (ms - tippVersuchUm < 120_000) return;
        tippVersuchUm = ms;
        tippZeigen(jetzt, t);
    }

    /** Schickt den Tipp -- mit Erwaehnung nur beim ersten Mal am Tipp-Tag -- und vermerkt ihn, wenn er ankam. */
    private void tippZeigen(java.time.LocalDateTime jetzt, String[] t) {
        boolean mitPing = de.hustlebot.core.Tagestipp.pingFaellig(jetzt);
        if (client.sendCardMitPing(mitPing ? tippKopf() : "", t[0], t[1], DiscordClient.FARBE_INFO)) {
            de.hustlebot.core.Tagestipp.gepostet(jetzt, mitPing);
        }
    }

    /**
     * Beim Start des Bots den Tipp des Tages zeigen (Autor, 30.09.: "jedes Mal, wenn man den
     * Bot startet") -- nicht nach einem Selbst-Neustart (Spiel-Neustart alle 2 h), sonst
     * kaeme er zwoelfmal am Tag.
     */
    private void tippBeimStart() {
        try {
            Thread.sleep(6000);   // Discord ist gerade erst angemeldet
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!de.hustlebot.core.Tagestipp.istAn()) return;
        java.time.LocalDateTime jetzt = java.time.LocalDateTime.now();
        String[] t = de.hustlebot.core.Tagestipp.heute(jetzt);
        if (t != null) tippZeigen(jetzt, t);
    }

    /** Die Zeile vor der Tipp-Karte: Erwaehnung plus zwei Worte, damit nie ein nacktes @everyone dasteht. */
    private static String tippKopf() {
        String ping = de.hustlebot.core.Tagestipp.ping();
        return ping.isBlank() ? "" : ping + " " + Sprache.t("Daily tip", "Tipp des Tages");
    }

    /** So lange darf die Hauptschleife stillstehen, bevor ein Haenger gemeldet wird. */
    private static final long MOTOR_HAENGER_MS = 3 * 60_000L;
    private boolean motorHaengtGemeldet = false;

    /**
     * Unabhaengiger Haenger-Waechter (wie beim Hauptbot): Bleibt die
     * Hauptschleife mitten in einem Schritt stecken, aendert sich der Zustand
     * nie -- zustandPruefen() merkt davon nichts. Hier wird stattdessen die
     * Uhr der Hauptschleife gelesen. Nur melden, nichts anhalten, nichts neu
     * starten.
     */
    private void motorPruefen() {
        BotEngine.State st = engine.state();
        if (st != BotEngine.State.LAEUFT && st != BotEngine.State.PAUSIERT) {
            motorHaengtGemeldet = false;
            return;
        }
        long alter = engine.heartbeatAlterMs();
        if (alter < MOTOR_HAENGER_MS) {
            motorHaengtGemeldet = false;
            return;
        }
        if (motorHaengtGemeldet) return;
        motorHaengtGemeldet = true;

        Log.warn("Discord", Sprache.t("The main loop has not responded for " + (alter / 1000) + " s.",
                "Die Hauptschleife antwortet seit " + (alter / 1000) + " s nicht mehr."));
        client.sendCard(Sprache.t("Bot is not responding", "Bot antwortet nicht"),
                Sprache.t("The bot has not moved on for **" + (alter / 1000) + " s**, even though it "
                          + "should be running. Nothing was stopped or restarted. Picture and report below.",
                          "Der Bot kommt seit **" + (alter / 1000) + " s** nicht weiter, obwohl er laufen "
                          + "sollte. Nichts wurde angehalten oder neu gestartet. Bild und Bericht darunter."),
                DiscordClient.FARBE_FEHLER);
        bildschirmfoto();
        log();
    }

    private void zustandPruefen() {
        BotEngine.State jetzt = engine.state();
        if (jetzt == letzterZustand) return;

        BotEngine.State vorher = letzterZustand;
        letzterZustand = jetzt;

        // Autor 21.10. (aus dem Hauptbot): steht der Bot, gibt es nichts mehr mitzuschauen -- sonst tropfen weiter
        // Bilder eines stehenden Spiels in den Kanal. Eine Pause zaehlt nicht dazu, da geht es gleich weiter.
        if (watchMinuten > 0 && (jetzt == BotEngine.State.GESTOPPT || jetzt == BotEngine.State.FERTIG
                || jetzt == BotEngine.State.FEHLER)) {
            watchMinuten = 0;
            de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.WACH, 0);
            client.sendCard(Sprache.t("Watching stopped", "Mitschauen beendet"),
                    Sprache.t("The bot is no longer running, so no more pictures.\nTurn it back on any time with `!watch 5`.",
                              "Der Bot laeuft nicht mehr, deshalb kommen keine Bilder mehr.\nMit `!wach 5` laesst es sich jederzeit wieder einschalten."),
                    DiscordClient.FARBE_INFO);
        }

        boolean lief = vorher == BotEngine.State.LAEUFT
                    || vorher == BotEngine.State.PAUSIERT;
        if (!lief) return;

        if (jetzt == BotEngine.State.FERTIG) {
            synchronized (kette) {
                if (!kette.isEmpty()) {
                    Auftrag fertig = kette.remove(0);
                    if ("clanwar".equals(fertig.modus())) {
                        // Code-Review 23.10.: Hier stand Kriegsplan.aufstellungErledigt() (= beide gesetzt) -- auch wenn nichts
                        // gesetzt war (Burg nicht erreicht ...), und jeder Krieg zaehlte doppelt. Den Vermerk schreibt jetzt nur
                        // der Clan-Krieg selbst; ohne Vermerk sieht kriegPruefen nach 15 min noch einmal nach.
                        kriegLaufEndeUm = System.currentTimeMillis();
                    }
                    if (fertig == arenaEinschubLaufend) arenaEinschubNachziehen();
                }
                // Event-Automatik: Hat der fertige Modus etwas angehaengt (nach dem
                // Kreislauf die Verlies-Runs)? Danach wieder Event -> Kathedrale, bis
                // zwei Runden hintereinander gar nichts gespielt wurde.
                var folge = engine.folgeAbholen();
                if (folge != null) {
                    kette.add(0, new Auftrag(folge.modus(), folge.runs(), "c", "", false));
                    if (de.hustlebot.core.Eventautomatik.istAn() && "dungeon".equals(folge.modus())) {
                        var ft = engine.tracker();
                        int gespielt = ft != null ? ft.completedRuns() : 0;
                        if (de.hustlebot.core.Eventautomatik.leereRunde(gespielt == 0) < 2) {
                            kette.add(1, new Auftrag("cathedral", BIS_LEER, "c", "", false));
                            de.hustlebot.core.Eventautomatik.eventZuerstMerken();
                        }
                    }
                }
            }
            naechstenStarten(false);
            if (engine.state() != BotEngine.State.LAEUFT) resetStartKette = false;   // Kette zu Ende

        } else if (jetzt == BotEngine.State.FEHLER
                || jetzt == BotEngine.State.GESTOPPT) {
            resetStartKette = false;
            if (jetzt == BotEngine.State.FEHLER) {
                // Recovery wie beim Hauptbot: Meldung, Bild, Fehlerbericht.
                // Kein Neustart -- der Freund entscheidet selbst, wie es weitergeht.
                String grund = engine.statusText();
                client.sendCard(Sprache.t("Stopped with an error", "Abbruch mit Fehler"),
                        (grund == null || grund.isBlank()
                            ? Sprache.t("The bot stopped.", "Der Bot hat angehalten.")
                            : grund)
                        + Sprache.t("\n\nPicture and report below.", "\n\nBild und Bericht darunter."),
                        DiscordClient.FARBE_FEHLER);
                bildschirmfoto();
                log();
            }
            // Ben 19.10.: "Kreislauf ohne Event" -- der eingeschobene Leuchtturm brach ab (Burg nicht erreicht), danach war
            // die ganze Warteschlange weg und der Bot stand bis zum Morgen still. Bricht ein EINSCHUB (Leuchtturm,
            // Kolosseum, Titanen-Turm, Clan-Laden, Clan-Krieg) mit Fehler ab, geht es mit dem Rest weiter.
            String fehlModus = engine.lastModeId();
            boolean einschub = jetzt == BotEngine.State.FEHLER && fehlModus != null && !NEBENBEI_BEI.contains(fehlModus);
            boolean weiter = false;
            synchronized (kette) {
                if (einschub && kette.size() > 1) {
                    kette.remove(0);
                    weiter = true;
                }
            }
            if (weiter) {
                Log.warn("Warteschlange", Sprache.t("Side job " + fehlModus + " failed -- carrying on with the rest of the queue.",
                        "Einschub " + fehlModus + " fehlgeschlagen -- es geht mit dem Rest der Warteschlange weiter."));
                naechstenStarten(false);
                return;
            }
            synchronized (kette) {
                if (kette.size() > 1) {
                    client.sendCard(Sprache.t("Queue dropped", "Warteschlange verworfen"), Sprache.t("The running mode ended early, so the rest of the queue was dropped.",
                            "Der laufende Modus hat vorzeitig aufgehört, deshalb wurde "
                            + "der Rest der Warteschlange verworfen."),
                            DiscordClient.FARBE_WARNUNG);
                }
                kette.clear();
            }
        }
    }

    /**
     * Zieht einen faelligen Clan-Krieg vor.
     *
     * NICHT mitten im Durchgang. Autors Ansage: erst den laufenden Durchgang zu
     * Ende bringen, dann den Krieg setzen, dann weitermachen. Deshalb wird hier
     * nur die Warteschlange umgestellt -- der Wechsel passiert von allein,
     * sobald der Motor auf FERTIG geht.
     */
    /** Wann der Clan-Krieg zuletzt eingeschoben wurde (fuer den zweiten Versuch im selben Fenster). */
    private volatile long kriegEingeschobenUm = 0;
    /** Wann die Maus den Bot zuletzt gestoppt hat -- danach 10 min keinen Krieg von selbst starten. */
    private volatile long mausStoppUm = 0;
    /** Krieg faellig, aber kein Spiel offen: wann zuletzt nachgesehen, fuer welchen Krieg schon Bescheid gegeben. */
    private volatile long kriegOhneSpielUm = 0;
    private volatile String kriegOhneSpielGemeldet = "";

    /** Wann zuletzt ein Clan-Krieg-Auftrag zu Ende ging (ohne Vermerk erst nach 15 min wieder einreihen). */
    private volatile long kriegLaufEndeUm = 0;

    private void kriegPruefen() {
        if (!kriegsplan.istSelbsttaetig()) return;
        // Code-Pruefung 16.10.: nach einem Maus-Stopp startete der Leerlauf-Krieg nach 2 s neu -- Stopp/Start-Flattern.
        if (System.currentTimeMillis() - mausStoppUm < 10 * 60_000L) return;
        if (kriegsplan.nochAufzustellen().isEmpty()) return;
        if (System.currentTimeMillis() - kriegLaufEndeUm < 15 * 60_000L) return;
        // Laeuft gerade ein Modus mit Einschub-Mechanik (Kathedrale/Verlies/Arena -- z. B. der Event-Ablauf)?
        // Dann uebernimmt nebenbeiPruefen() den Krieg ueber den bewaehrten Einschub-Weg (mit Rest-Weiterlauf);
        // ein blosses Anhaengen hier kaeme im nie endenden Auftrag nie dran (16.10.). Sonst (Leerlauf, kurze Modi) wie bisher.
        if (engine.state() == BotEngine.State.LAEUFT
                && engine.lastModeId() != null && NEBENBEI_BEI.contains(engine.lastModeId())) return;
        // Autor 24.10.: Hat sich der laufende Modus SELBST angehalten (Portal: Tasche leer, zweimal verloren ...), trotzdem
        // den Krieg setzen und danach mit dem naechsten Auftrag weitermachen (Arena, Event-Loop, Verlies). NICHT bei Autors
        // eigener Pause und nicht nach einem Rauswurf (er hat sich auf einem anderen Geraet angemeldet) -- dann wartet der Bot.
        if (engine.state() == BotEngine.State.PAUSIERT && engine.modusPauseUm() > 0
                && de.hustlebot.bot.Stoerfenster.rauswurfUm() < engine.modusPauseUm() - 5_000L) {
            synchronized (kette) {
                // Der angehaltene Auftrag steht vorn und faellt beim FERTIG weg -- der Krieg kommt direkt dahinter.
                if (!kette.isEmpty() && kette.stream().noneMatch(a -> "clanwar".equals(a.modus()))) {
                    de.hustlebot.bot.HeldenSet.automatischerKrieg();
                    kette.add(1, new Auftrag("clanwar", 1, "a", "", false));
                }
            }
            if (engine.modusPauseBeenden()) {
                Log.info("Warteschlange", Sprache.t("Clan war is due while the mode is paused -- the pause ends, the clan war comes next.",
                        "Clan-Krieg faellig, waehrend der Modus angehalten ist -- die Pause endet, der Clan-Krieg kommt als Naechstes."));
            }
            return;
        }
        // Autor 24.10.: "Clan-Krieg immer setzen, egal welcher Modus -- danach zurueck zur eigentlichen Taetigkeit."
        // Portal, Titanen-Turm und Hammer-Event (KRIEG_FORTSETZEN) bekommen den Krieg ebenfalls als
        // Einschub (nebenbeiPruefen), statt dass er hinten an den Auftrag gehaengt wird.
        if (engine.state() == BotEngine.State.LAEUFT && engine.lastModeId() != null
                && KRIEG_FORTSETZEN.contains(engine.lastModeId())) {
            synchronized (kette) {
                if (!kette.isEmpty()) return;
            }
        }
        // Autor 23.10. (Server ohne offenes Spiel): Der Start scheiterte, die Kette war wieder leer -- und alle 2-3 s kamen
        // "Clan-Krieg ist faellig" und "Hustle Castle ist nicht offen" neu in den Kanal. Jetzt: ohne Spiel nichts anhaengen,
        // einmal je Krieg Bescheid geben.
        // Autor 30.10. (Bot-Programm bleibt auf dem Server offen, das Spiel geht nur zur Kriegszeit auf): nicht mehr 5 Minuten warten,
        // sonst blieb das geoeffnete Spiel so lange ungenutzt. Das Nachsehen ist billig, gemeldet wird weiter nur einmal je Krieg.
        if (engine.state() != BotEngine.State.LAEUFT && engine.state() != BotEngine.State.PAUSIERT) {
            long jetzt = System.currentTimeMillis();
            if (!engine.context().window().locate()) {
                kriegOhneSpielUm = jetzt;
                String krieg = String.valueOf(kriegsplan.nochAufzustellen().orElse(null));
                if (!krieg.equals(kriegOhneSpielGemeldet)) {
                    kriegOhneSpielGemeldet = krieg;
                    client.sendCard(Sprache.t("Clan war is due", "Clan-Krieg ist fällig"),
                            Sprache.t("Hustle Castle is not open, so the bot cannot deploy. It looks again every 5 minutes.",
                                    "Hustle Castle ist nicht offen, deshalb kann der Bot nicht aufstellen. Er schaut alle 5 Minuten wieder nach."),
                            DiscordClient.FARBE_INFO);
                }
                return;
            }
            kriegOhneSpielUm = 0;
        }

        synchronized (kette) {
            boolean kriegSchonDrin = kette.stream()
                    .anyMatch(a -> "clanwar".equals(a.modus()));
            if (kriegSchonDrin) return;

            de.hustlebot.bot.HeldenSet.automatischerKrieg();   // Ben 17.10.: Helden-Set nur beim automatischen Krieg
            if (kette.isEmpty()) {
                kette.add(new Auftrag("clanwar", 1, "a", "", false));
            } else {
                kette.add(1, new Auftrag("clanwar", 1, "a", "", false));
                return;   // laeuft gerade etwas: erst zu Ende bringen
            }
        }

        if (engine.state() != BotEngine.State.LAEUFT
                && engine.state() != BotEngine.State.PAUSIERT) {
            client.sendCard(Sprache.t("Clan war is due", "Clan-Krieg ist fällig"),
                    Sprache.t("Deploying for the clan war now.", "Es wird jetzt für den Clan-Krieg aufgestellt."), DiscordClient.FARBE_START);
            naechstenStarten(false);
        }
    }

    /**
     * Nach jedem fertigen Durchlauf eine kurze Karte: der wievielte es war und
     * wie lange er gedauert hat (Autor, 19.09.). Die Wache sieht alle zwei
     * Sekunden nach, ob der Zaehler weitergesprungen ist -- so braucht es
     * keinen Rueckruf quer durch den Motor.
     */
    private void runPruefen() {
        var t = engine.tracker();
        if (t == null) {
            gemeldeteRuns = -1;
            return;
        }
        int fertig = t.completedRuns();
        if (gemeldeteRuns < 0) {           // erster Blick: nur merken, nicht melden
            gemeldeteRuns = fertig;
            return;
        }
        if (fertig <= gemeldeteRuns) return;
        int vorher = gemeldeteRuns;
        gemeldeteRuns = fertig;
        if (fertig == 1 || fertig < vorher) letzteRunZeiten.clear();
        if (t.lastRunSeconds() > 0) letzteRunZeiten.addLast(t.lastRunSeconds());
        while (letzteRunZeiten.size() > 10) letzteRunZeiten.removeFirst();
        // Wie oft melden: jeden Run, alle 5 oder alle 10 (!settings reports); Run 1 immer.
        int takt = de.hustlebot.core.Meldetakt.takt();
        if (!(fertig == 1 || takt <= 1 || fertig / takt > vorher / takt)) return;
        // Hauptbot 10.10.: Die Titan-Grabung zaehlt ihre Abschnitte intern als Durchlaeufe -- kein "Durchlauf 1
        // fertig, 52:36". Den Stand meldet die Grabung selbst (alle 1000 Spitzhacken, am Ende "Fertig").
        if ("grabung".equals(engine.lastModeId())) return;

        StringBuilder sb = new StringBuilder();
        long dauer = Math.round(letzteRunZeiten.stream().mapToLong(Long::longValue).average().orElse(0));
        if (dauer > 0) sb.append(takt <= 1 && letzteRunZeiten.size() <= 1
                ? Sprache.t("**Duration:** ", "**Dauer:** ")
                : Sprache.t("**Average per run (last " + letzteRunZeiten.size() + "):** ",
                            "**Schnitt je Run (letzte " + letzteRunZeiten.size() + "):** "))
                .append(dauerText(dauer)).append('\n');
        if (t.failedBattles() > 0) {
            sb.append(Sprache.t("**Battles lost:** ", "**Verlorene Kämpfe:** ")).append(t.failedBattles()).append('\n');
        }

        // Geschaetzte Fertig-Uhrzeit, solange noch etwas aussteht.
        var tele = engine.context() == null ? null : engine.context().laufstatistik();
        int rest = Math.max(0, t.targetRuns() - fertig);
        boolean ohneZiel = t.targetRuns() >= 100_000;   // "bis leer" (Event, Kreislauf)
        if (tele != null && rest > 0 && !ohneZiel) {
            long schnitt = tele.averageRunSeconds(engine.lastModeId());
            if (schnitt > 0) {
                sb.append(Sprache.t("**Done at about:** ", "**Fertig etwa:** "))
                  .append(uhrzeitText(System.currentTimeMillis() + rest * schnitt * 1000L))
                  .append(Sprache.t("\n", " Uhr\n"));
            }
        }
        sb.append("_").append(modusName(engine.lastModeId())).append("_");

        client.sendCard(Sprache.t("Run ", "Durchlauf ") + fertig
                + (ohneZiel ? "" : Sprache.t(" of ", " von ") + t.targetRuns()) + Sprache.t(" done", " fertig"),
                sb.toString(), DiscordClient.FARBE_ERFOLG);
    }

    /**
     * "!watch 5" / "!wach 5" (Autor 21.10., aus dem Hauptbot): alle 5 Minuten ein Bild vom Spiel, "!watch off" beendet es,
     * "!watch status" zeigt nur den Stand. Ohne Zahl: alle 10 Minuten. Nach einem Neustart gilt es weiter
     * (dauerwerte.properties); wenn der Bot stehen bleibt, schaltet es sich selbst ab.
     */
    private void watch(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("aus") || r.startsWith("stop") || r.startsWith("off")) {
            watchMinuten = 0;
            de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.WACH, 0);
            client.sendCard(Sprache.t("Watching stopped", "Mitschauen beendet"),
                    Sprache.t("No more pictures will be sent by themselves.", "Es werden keine Bilder mehr von selbst geschickt."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (r.matches("stand|status|zeigen|show|info")) {
            client.sendCard(Sprache.t("Watching", "Mitschauen"), watchMinuten > 0
                    ? Sprache.t("A picture every " + watchMinuten + " minutes. Stop: `!watch off`.",
                                "Alle " + watchMinuten + " Minuten ein Bild. Aus: `!wach aus`.")
                    : Sprache.t("Off. Turn on: `!watch 5`.", "Aus. Einschalten: `!wach 5`."), DiscordClient.FARBE_INFO);
            return;
        }
        int minuten = zahlAus(r);
        if (minuten < 0) minuten = 10;   // ohne Angabe: alle zehn Minuten
        if (minuten < WATCH_MIN || minuten > WATCH_MAX) {
            client.send(Sprache.t("Please pick an interval between " + WATCH_MIN + " and " + WATCH_MAX
                                  + " minutes, for example `!watch 5`.",
                                  "Bitte einen Abstand zwischen " + WATCH_MIN + " und " + WATCH_MAX
                                  + " Minuten angeben, zum Beispiel `!wach 5`."));
            return;
        }
        watchMinuten = minuten;
        de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.WACH, minuten);
        watchNaechstes = System.currentTimeMillis() + minuten * 60_000L;
        client.sendCard(Sprache.t("Watching turned on", "Mitschauen eingeschaltet"),
                Sprache.t("A picture of the game every **" + minuten + " minutes**.\nStop it with `!watch off`.",
                          "Alle **" + minuten + " Minuten** kommt ein Bild vom Spiel.\nBeenden mit `!wach aus`."),
                DiscordClient.FARBE_INFO);
        bildschirmfoto();
    }

    /**
     * "!arena Rina safe" / "!arena Walking Death avoid" (Autor 21.10., aus dem Hauptbot): einen Gegner per Name auf eine
     * Arena-Liste setzen -- sicher/safe, machbar/doable, meiden/avoid, niemals/never. Der Freunde-Bot schreibt dafuer
     * eine Zeile "Name | sicher" in arena-aufnehmen.txt (wie das Capture-Programm "Arena: type names"); das Namensbild
     * schneidet der Bot selbst aus, sobald er den Namen in der Arena-Pyramide sieht. Ohne Angaben: wer noch wartet.
     */
    private void arenaGegner(String kanal, String rest) {
        String titel = Sprache.t("Arena list", "Arena-Liste");
        String hilfe = Sprache.t(
                "`!arena Rina safe` -- safe win  ·  `!arena Walking Death avoid` -- names with spaces work too\n"
                + "Words: `safe` `doable` `avoid` `never` (German: `sicher` `machbar` `meiden` `niemals`)\n"
                + "The bot cuts out the name picture by itself as soon as it sees the name in the arena pyramid.",
                "`!arena Rina sicher` -- sicherer Sieg  ·  `!arena Walking Death meiden` -- Namen mit Leerzeichen gehen auch\n"
                + "Woerter: `sicher` `machbar` `meiden` `niemals` (englisch: `safe` `doable` `avoid` `never`)\n"
                + "Das Namensbild schneidet der Bot selbst aus, sobald er den Namen in der Arena-Pyramide sieht.");
        de.hustlebot.core.ArenaAufnehmen.Zeile z = de.hustlebot.core.ArenaAufnehmen.zerlegen(rest);
        if (z == null) {
            List<String> offen = de.hustlebot.core.ArenaAufnehmen.offene(de.hustlebot.core.ArenaAufnehmen.datei());
            StringBuilder sb = new StringBuilder();
            if (rest != null && !rest.isBlank()) {
                sb.append(Sprache.t("That was not clear -- I need a name and a list word.", "Das war nicht eindeutig -- ich brauche einen Namen und ein Listen-Wort."))
                  .append("\n\n");
            }
            if (offen.isEmpty()) {
                sb.append(Sprache.t("Nobody is waiting for a name picture.", "Niemand wartet auf sein Namensbild."));
            } else {
                sb.append(Sprache.t("Waiting until the bot sees them in the arena:", "Warten, bis der Bot sie in der Arena sieht:"));
                for (int i = 0; i < offen.size() && i < 30; i++) sb.append("\n`").append(offen.get(i)).append('`');
                if (offen.size() > 30) sb.append(Sprache.t("\n... and " + (offen.size() - 30) + " more", "\n... und " + (offen.size() - 30) + " weitere"));
            }
            sb.append("\n\n").append(hilfe);
            client.sendCardTo(kanal, titel, sb.toString(),
                    rest == null || rest.isBlank() ? DiscordClient.FARBE_INFO : DiscordClient.FARBE_WARNUNG);
            return;
        }
        try {
            String alt = de.hustlebot.core.ArenaAufnehmen.eintragen(de.hustlebot.core.ArenaAufnehmen.datei(), z);
            Log.info("Arena", "arena-aufnehmen.txt: " + z.name() + " -> " + z.art().wort + " (per Discord)");
            de.hustlebot.core.ArenaAufnehmen.Art vorher = de.hustlebot.core.ArenaAufnehmen.artAus(alt);
            String text = Sprache.t("**" + z.name() + "** noted for **" + z.art().en + "**.",
                                    "**" + z.name() + "** ist fuer **" + z.art().de + "** vorgemerkt.")
                    + (alt == null ? "" : Sprache.t("\n(Replaces the earlier note: " + (vorher == null ? alt : vorher.en) + ".)",
                                                    "\n(Ersetzt den frueheren Eintrag: " + (vorher == null ? alt : vorher.de) + ".)"))
                    + Sprache.t("\nThe bot cuts out the name picture by itself as soon as it sees the name in the arena pyramid.",
                                "\nDas Namensbild schneidet der Bot selbst aus, sobald er den Namen in der Arena-Pyramide sieht.");
            client.sendCardTo(kanal, titel, text, DiscordClient.FARBE_ERFOLG);
        } catch (Exception e) {
            client.sendCardTo(kanal, titel, Sprache.t("Could not be saved: ", "Konnte nicht gespeichert werden: ") + e.getMessage(),
                    DiscordClient.FARBE_FEHLER);
        }
    }

    /**
     * "!event ranking" / "!event rangliste" (Autor 21.10., aus dem Hauptbot, dort seit 04.10.): der Bot oeffnet im
     * laufenden Hammer-Event den Reiter "Rankings", schickt ein Bild der Platzierung und geht zurueck zum Spielfeld.
     */
    private void eventBefehl(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (!(r.startsWith("rang") || r.startsWith("rank") || r.startsWith("platz"))) {
            client.send(Sprache.t("Usage: `!event ranking` -- sends a picture of the event ranking (while the hammer event is running).",
                    "So geht es: `!event rangliste` -- schickt ein Bild der Event-Rangliste (waehrend das Hammer-Event laeuft)."));
            return;
        }
        if (!(engine.activeMode() instanceof de.hustlebot.bot.EventMode ev)
                || (engine.state() != BotEngine.State.LAEUFT && engine.state() != BotEngine.State.PAUSIERT)) {
            client.send(Sprache.t("I can only show the ranking while the hammer event is running (`!start event`).",
                    "Die Rangliste kann ich nur zeigen, waehrend das Hammer-Event laeuft (`!start event`)."));
            return;
        }
        var m = engine.context().matcher();
        if (!m.has("ev_reiter_rankings") || !m.has("ev_reiter_game")) {
            client.sendCard(Sprache.t("Ranking not possible", "Rangliste nicht moeglich"),
                    Sprache.t("The pictures `ev_reiter_rankings` and `ev_reiter_game` are missing (Capture-Images.exe, HAMMER-EVENT) -- "
                              + "without them the bot could not get back to the playing field.",
                              "Die Bilder `ev_reiter_rankings` und `ev_reiter_game` fehlen (Capture-Images.exe, HAMMER-EVENT) -- "
                              + "ohne sie kaeme der Bot nicht zurueck aufs Spielfeld."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        ev.ranglisteSchicken(png -> client.sendFile(Sprache.t("**Event ranking**", "**Event-Rangliste**"),
                "rangliste.png", png));
        client.send(Sprache.t("OK -- I'll open the ranking at the next quiet moment and send a picture.",
                "Ok -- ich oeffne die Rangliste an der naechsten ruhigen Stelle und schicke gleich ein Bild."));
    }

    /**
     * "!game" / "!spiel" (Autor 21.10., aus dem Hauptbot): Hustle Castle starten, falls es nicht laeuft -- erst direkt,
     * sonst ueber den MY.GAMES Launcher. Ein ausdrueckliches Kommando, also darf der Bot den Launcher wieder anfassen,
     * auch wenn er vorher von Hand zugemacht wurde. Das Starten dauert, darum in einem eigenen Faden.
     */
    private void spielStarten() {
        de.hustlebot.core.GameLauncher.wiederErlauben();
        client.sendCard(Sprache.t("Starting the game", "Spiel wird gestartet"),
                Sprache.t("Looking for Hustle Castle. If it is not running it gets started -- first directly, "
                          + "and through the MY.GAMES launcher if that fails.",
                          "Der Bot sieht nach Hustle Castle. Laeuft es nicht, wird es gestartet -- erst direkt, "
                          + "und wenn das nicht geht, ueber den MY.GAMES Launcher."),
                DiscordClient.FARBE_INFO);
        Thread starter = new Thread(() -> {
            de.hustlebot.core.GameLauncher.Ergebnis versuch =
                    de.hustlebot.core.GameLauncher.sicherstellen(engine.context().window());
            client.sendCard(
                    versuch.ok() ? Sprache.t("Game is up", "Spiel laeuft")
                                 : Sprache.t("Could not start", "Start nicht moeglich"),
                    versuch.meldung(),
                    versuch.ok() ? DiscordClient.FARBE_ERFOLG : DiscordClient.FARBE_FEHLER);
        }, "spiel-starten");
        starter.setDaemon(true);
        starter.start();
    }

    /** Das selbsttaetige Bild von !watch, wenn die Zeit um ist (aus der 2-s-Schleife). */
    private void watchPruefen() {
        if (watchMinuten <= 0 || System.currentTimeMillis() < watchNaechstes) return;
        watchNaechstes = System.currentTimeMillis() + watchMinuten * 60_000L;
        bildschirmfoto();
    }

    private void statsPruefen() {
        if (statsMinuten <= 0) return;
        long jetzt = System.currentTimeMillis();
        if (jetzt - statsZuletzt >= statsMinuten * 60_000L) {
            statsZuletzt = jetzt;
            statsSchicken();
        }
    }

    // ------------------------------------------------------------------
    //  Kleinkram
    // ------------------------------------------------------------------

    /** "Bis leer" (keine Zahl noetig): das Event, und die Kathedrale mit Event-Automatik. */
    private static final int BIS_LEER = 100_000;

    /** Die Modi mit eigener Bezahlart -- "!settings bezahlen alle ..." / "nahrungslimit alle ..." (16.10.). */
    private static final java.util.List<String> BEZAHL_MODI = java.util.List.of("dungeon", "cathedral", "arena");

    private static boolean laeuftBisLeer(String modus) {
        // Seit 10.10. auch die Titan-Grabung: bis Tickets und Spitzhacken alle sind (wie im Hauptbot).
        return "event".equals(modus) || "grabung".equals(modus)
                || ("cathedral".equals(modus) && de.hustlebot.core.Eventautomatik.istAn());
    }

    /** Dauer der letzten (hoechstens 10) Runs fuer den Schnitt in der Meldung. */
    private final java.util.ArrayDeque<Long> letzteRunZeiten = new java.util.ArrayDeque<>();

    private static boolean istAn(String w) {
        return w.equals("an") || w.equals("on") || w.equals("ein") || w.equals("ja") || w.equals("yes");
    }

    /**
     * "!lighthouse", "!leuchturm", "!coloseum", "!kolloseum", "!kolo" ... -> "leuchtturm" / "kolosseum"
     * fuer einstellungen(), sonst null (Autor, 09.10.; wie im Hauptbot, dort mit OrtBefehlTest).
     */
    static String ortBefehl(String verb) {
        if (verb == null || !verb.startsWith("!")) return null;
        String v = verb.substring(1).toLowerCase(Locale.ROOT);
        if (v.matches("leuch?t{1,2}ur[mn]|leuchtum|leut{1,2}urm|leuchthaus"
                + "|lig?h?th?ous?e?|lighthose|ligthouse|lh")) return "leuchtturm";
        if (v.matches("[ck]oll?[oi]ss?[ei]?um|[ck]olos+eo|kolo|colo")) return "kolosseum";
        return null;
    }

    private static boolean istAus(String w) {
        return w.equals("aus") || w.equals("off") || w.equals("nein") || w.equals("no");
    }

    private static String automatikZeile() {
        boolean an = de.hustlebot.core.Eventautomatik.istAn();
        return Sprache.t("🔁 **Event automation:** ", "🔁 **Event-Automatik:** ") + (an ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                + Sprache.t(" – `!start cathedral` then loops: cathedral (keys → food up to the limit → food chests → chests) → hammer event (until the hammers are gone) → cathedral with a fresh food budget → ..."
                            + (de.hustlebot.core.Eventautomatik.verliesRuns() > 0
                                ? " When everything is empty: " + de.hustlebot.core.Eventautomatik.verliesRuns() + " dungeon runs, then again."
                                : " No dungeon runs."),
                            " – `!start kathedrale` laeuft dann im Kreis: Kathedrale (Schluessel → Nahrung bis zum Limit → Nahrungskisten → Kisten) → Hammer-Event (bis die Haemmer alle sind) → Kathedrale mit neuem Nahrungs-Budget → ..."
                            + (de.hustlebot.core.Eventautomatik.verliesRuns() > 0
                                ? " Ist alles leer: " + de.hustlebot.core.Eventautomatik.verliesRuns() + " Verlies-Runs, dann von vorn."
                                : " Keine Verlies-Runs."))
                // Seit 10.10.: welches Event im Kreislauf (Hauptbot 09.10.)
                + (de.hustlebot.core.Eventautomatik.titan()
                        ? Sprache.t("\n⛏️ Event in the loop: **titan excavation** (boss x10, all pickaxes, super pickaxes + dynamite) "
                                    + (de.hustlebot.core.Eventautomatik.titanAlleRuns() > 0
                                        ? "every " + de.hustlebot.core.Eventautomatik.titanAlleRuns() + " cathedral runs and " : "")
                                    + "when keys and chests are gone; last 12 h at most every 20 runs.",
                                    "\n⛏️ Event im Kreislauf: **Titan-Grabung** (Boss x10, alle Spitzhacken, Super-Spitzhacken + Dynamit) "
                                    + (de.hustlebot.core.Eventautomatik.titanAlleRuns() > 0
                                        ? "alle " + de.hustlebot.core.Eventautomatik.titanAlleRuns() + " Kathedralen-Runs und " : "")
                                    + "wenn Schluessel und Kisten alle sind; letzte 12 h hoechstens alle 20 Runs.")
                        : Sprache.t("\n🔨 Event in the loop: **hammer event** ("
                                    + (de.hustlebot.core.Eventautomatik.stagesJeGang() > 0
                                        ? "at most " + de.hustlebot.core.Eventautomatik.stagesJeGang() + " stages per visit" : "all hammers") + ") "
                                    + (de.hustlebot.core.Eventautomatik.hammerAlleRuns() > 0
                                        ? "every " + de.hustlebot.core.Eventautomatik.hammerAlleRuns() + " cathedral runs and "
                                        : "") + "when keys and chests are gone.",
                                    "\n🔨 Event im Kreislauf: **Hammer-Event** ("
                                    + (de.hustlebot.core.Eventautomatik.stagesJeGang() > 0
                                        ? "hoechstens " + de.hustlebot.core.Eventautomatik.stagesJeGang() + " Stages je Gang" : "alle Haemmer") + ") "
                                    + (de.hustlebot.core.Eventautomatik.hammerAlleRuns() > 0
                                        ? "alle " + de.hustlebot.core.Eventautomatik.hammerAlleRuns() + " Kathedralen-Runs und "
                                        : "") + "wenn Schluessel und Kisten alle sind."))
                + Sprache.t("\n_Change: `!settings automation on` / `off` / `25` (dungeon runs at the end, 0 = none) · `!settings automation titan` / `hammer` · `!settings hammerevent 100` / `!settings titanevent 100` (every N cathedrals, 1-500, 0 = off) · `!settings hammerstages 10` (at most 10 stages per visit, 0 = all hammers) · `!settings foodchests 999`_",
                            "\n_Aendern: `!settings eventautomatik an` / `aus` / `25` (Verlies-Runs am Ende, 0 = keine) · `!settings eventautomatik titan` / `hammer` · `!settings hammerevent 100` / `!settings titanevent 100` (alle N Kathedralen, 1-500, 0 = aus) · `!settings hammerstages 10` (hoechstens 10 Stages je Gang, 0 = alle Haemmer) · `!settings nahrungskisten 999`_")
                + "\n📦 " + de.hustlebot.core.Eventkisten.beschreibung()
                + "\n📣 " + de.hustlebot.core.Meldetakt.beschreibung();
    }

    private static String nebenbeiZeile() {
        var lh = de.hustlebot.bot.LighthouseMode.LEUCHTTURM.plan();
        var ko = de.hustlebot.bot.LighthouseMode.KOLOSSEUM.plan();
        return Sprache.t("🗼 **On the side:** lighthouse ", "🗼 **Nebenbei:** Leuchtturm ") + (lh.istAn() ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                + Sprache.t(" · colosseum ", " · Kolosseum ") + (ko.istAn() ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                + Sprache.t(" · titan tower ", " · Titanen-Turm ") + (de.hustlebot.core.Turmplan.istAn() ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                + Sprache.t(" · clan store ", " · Clan-Laden ") + (de.hustlebot.core.Ladenplan.istAn() ? Sprache.t("on", "an") : Sprache.t("off", "aus"))
                + Sprache.t(" · send to friends ", " · Freunden senden ") + (de.hustlebot.core.Freundeplan.istAn() ? Sprache.t("on", "an") : Sprache.t("off", "aus"));
    }

    // ------------------------------------------------------------------
    //  Eigenes Team ansehen (aus dem Hauptbot, Autor 11.10., Auftrag 6)
    // ------------------------------------------------------------------

    private boolean teamVorlagenGemeldet;
    private volatile long teamAngestossenUm;
    /** "!team scan" waehrend des Laufs: nebenbeiPruefen schiebt den Scan hinter den laufenden Run. */
    private volatile boolean teamVonHand;

    /** Sind die Pflicht-Bilder fuer den Team-Scan da? Sonst einmal Bescheid sagen. */
    private boolean teamVorlagenDa() {
        var fehlend = engine.context().matcher().missing(new de.hustlebot.bot.TeamScanMode().requiredTemplates());
        if (fehlend.isEmpty()) return true;
        if (!teamVorlagenGemeldet) {
            teamVorlagenGemeldet = true;
            Log.info("Team", "Das eigene Team waere dran, aber es fehlen Bilder: " + String.join(", ", fehlend)
                    + " (templates\\gelernt\\team). Bis sie da sind, wird es ausgelassen.");
        }
        return false;
    }

    /**
     * Den Team-Scan hinter den laufenden Run schieben (wie im Hauptbot). Hier ueber den Einschub-Weg von
     * nebenbeiPruefen: der Wunsch wird vermerkt, der naechste Takt (2 s) reiht ihn ein. true, wenn das geht.
     */
    private boolean teamEinschieben(boolean vonHand) {
        String modus = engine.lastModeId();
        if (modus == null || "teamscan".equals(modus) || BotEngine.istBotengang(modus)) return false;
        if (!NEBENBEI_BEI.contains(modus)) return false;
        if (kriegsplan.faellig().isPresent()) return false;   // der Krieg geht vor
        if (engine.wechselAngefordert()) return false;
        if (!teamVorlagenDa()) return false;
        synchronized (kette) {
            if (kette.stream().anyMatch(a -> "clanwar".equals(a.modus()) || "teamscan".equals(a.modus()))) return false;
        }
        if (vonHand) teamVonHand = true;
        Log.info("Team", "Nach dem laufenden Run sieht sich der Bot dein Team an (Kaserne, Held, Schirmherren), "
                + "danach weiter mit " + modusName(modus) + ".");
        return true;
    }

    /**
     * "!zeitzone" zeigt die eingestellte Zone und die Uhrzeit, "!zeitzone Europe/Berlin" (auch "london",
     * "+2") stellt sie um (aus dem Hauptbot, 16.10.). Clan-Krieg, Titanen-Turm und Team-Scan rechnen damit.
     */
    private void commandZeitzone(String channel, String rest) {
        String wunsch = rest == null ? "" : rest.trim();
        if (wunsch.isEmpty() || wunsch.matches("(?i)stand|status|zeigen|show|\\?")) {
            client.sendCardTo(channel, Sprache.t("Time zone", "Zeitzone"), zeitzoneZeile()
                    + "\n\n" + Sprache.t("Change it with `!timezone Europe/Berlin` -- ", "Umstellen mit `!zeitzone Europe/Berlin` -- ")
                    + de.hustlebot.core.Zeitzone.beispiele(), DiscordClient.FARBE_INFO);
            return;
        }
        if (wunsch.matches("(?i)standard|default|reset|zurueck|berlin")) wunsch = "Europe/Berlin";
        if (!de.hustlebot.core.Zeitzone.setzen(wunsch)) {
            client.sendCardTo(channel, Sprache.t("Time zone", "Zeitzone"),
                    Sprache.t("I do not know the time zone \"", "Die Zeitzone \"") + wunsch
                    + Sprache.t("\". It stays at ", "\" kenne ich nicht. Es bleibt bei ")
                    + de.hustlebot.core.Zeitzone.name() + ".\n" + de.hustlebot.core.Zeitzone.beispiele(),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        // 16.10.: eine Freundin in England stellte London ein -- das Spiel laeuft aber nach Berliner Zeit.
        boolean berlin = "Europe/Berlin".equals(de.hustlebot.core.Zeitzone.name());
        client.sendCardTo(channel, Sprache.t("Time zone set", "Zeitzone umgestellt"), zeitzoneZeile()
                + "\n" + Sprache.t("Clan war, Titan tower and the team scan now go by this clock.",
                                  "Clan-Krieg, Titanen-Turm und Team-Scan gehen jetzt nach dieser Uhr.")
                + (berlin ? "" : "\n\n" + Sprache.t("⚠️ Careful: Hustle Castle runs on **Berlin time** (clan war, daily reset), also in England. "
                        + "With another zone the bot comes too early or too late. Back: `!timezone Europe/Berlin`.",
                        "⚠️ Achtung: Hustle Castle laeuft nach **Berliner Zeit** (Clan-Krieg, Tages-Reset), auch in England. "
                        + "Mit einer anderen Zone kommt der Bot zu frueh oder zu spaet. Zurueck: `!zeitzone Europe/Berlin`.")),
                berlin ? DiscordClient.FARBE_ERFOLG : DiscordClient.FARBE_WARNUNG);
    }

    private static String zeitzoneZeile() {
        return "**" + Sprache.t("Time zone: ", "Zeitzone: ") + de.hustlebot.core.Zeitzone.beschreibung() + "**";
    }

    /** "!neustart" (Knopf "Bot neu starten" im Steuerprogramm, aus dem Hauptbot 16.10.). */
    private void commandRestart() {
        client.sendCard(Sprache.t("Restart", "Neustart"),
                Sprache.t("The bot halts and starts again with the same settings.",
                          "Der Bot wird angehalten und mit denselben Einstellungen neu gestartet."),
                DiscordClient.FARBE_WARNUNG);
        // In einem eigenen Faden, weil der Neustart auf den alten Lauf wartet.
        Thread thread = new Thread(() -> {
            boolean ok = engine.restart();
            if (!ok && engine.context().window().locate()) {
                client.sendCard(Sprache.t("Restart failed", "Neustart fehlgeschlagen"),
                        Sprache.t("Please close the bot and start it again from the desktop icon.",
                                  "Bitte den Bot schliessen und ueber das Desktop-Symbol neu starten."),
                        DiscordClient.FARBE_FEHLER);
            }
        }, "discord-restart");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * "!autostart an|aus" (Steuerprogramm, Einstellungen; 16.10.). Im Hauptbot schaltet das Autors geplante
     * Aufgabe -- bei den Freunden ist der Autostart die Verknuepfung im Autostart-Ordner, die Install.bat
     * anlegt ("Hustle Castle Bot.lnk"). Ohne Angabe: zeigen, ob sie da ist.
     */
    private void commandAutostart(String rest) {
        java.nio.file.Path link = java.nio.file.Path.of(System.getenv("APPDATA"), "Microsoft", "Windows",
                "Start Menu", "Programs", "Startup", "Hustle Castle Bot.lnk");
        String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (r.matches("(an|on|ein|ja|yes).*")) {
                String exe = System.getProperty("jpackage.app-path");
                if (exe == null || exe.isBlank()) {
                    client.send(Sprache.t("Autostart only works in the installed bot (HustleBot.exe).",
                            "Autostart geht nur im installierten Bot (HustleBot.exe)."));
                    return;
                }
                // Pfade ueber Umgebungsvariablen, nicht in den Befehl -- ein Hochkomma im Pfad kann so nichts einschleusen.
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:HB_LINK); $s.TargetPath=$env:HB_EXE; "
                        + "$s.WorkingDirectory=(Split-Path -Parent $env:HB_EXE); $s.Save()");
                pb.environment().put("HB_LINK", link.toString());
                pb.environment().put("HB_EXE", exe);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                // Code-Pruefung 16.10.: vorher las readAllBytes() vor waitFor -- eine haengende PowerShell blockierte
                // den Befehls-Faden und damit alle Knoepfe.
                if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } else if (r.matches("(aus|off|nein|no|stop).*")) {
                java.nio.file.Files.deleteIfExists(link);
            }
        } catch (Exception e) {
            Log.warn("Autostart", e.getMessage());
        }
        boolean an = java.nio.file.Files.exists(link);
        client.sendCard(Sprache.t("Autostart", "Autostart"), an
                ? Sprache.t("**On** -- the bot starts when Windows starts.", "**An** -- der Bot startet beim Hochfahren von Windows.")
                : Sprache.t("**Off** -- the bot does not start by itself.", "**Aus** -- der Bot startet nicht von selbst."),
                an ? DiscordClient.FARBE_ERFOLG : DiscordClient.FARBE_INFO);
    }

    /**
     * "!team" zeigt, was der Bot ueber das eigene Team weiss (arena-ich.txt). "!team scan" sieht es jetzt
     * an (nach dem laufenden Run, sonst sofort), "!team an|aus" schaltet die 3-Tage-Automatik,
     * "!team punkte an|aus" das Team als Messlatte in der Siegchance, "!team held 12 4" die Punkte
     * fuer den Helden, "!team name Autor" den eigenen Spielernamen, "!team vorlagen" zeigt, was fehlt.
     */
    private void commandTeam(String kanal, String rest, String restOriginal) {
        String channel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        String[] w = rest == null || rest.isBlank() ? new String[0] : rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
        String w0 = w.length > 0 ? w[0] : "";
        String titel = Sprache.t("Your team", "Dein Team");
        if (w0.isEmpty() || w0.matches("stand|status|zeigen|show|info")) {
            client.sendCardTo(channel, titel, MeinTeam.karte() + "\n\n_"
                    + Sprache.t("`!team scan` looks at it now · `!team on`/`off` 3-day automatic · `!team points off` · `!team hero 12 4` · `!team name <name>`",
                                "`!team scan` jetzt ansehen · `!team an`/`aus` 3-Tage-Automatik · `!team punkte aus` · `!team held 12 4` · `!team name <Name>`")
                    + "_", DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.matches("scan|jetzt|now|ansehen|look|start|los|go")) {
            java.util.List<String> fehlend = engine.context().matcher().missing(new de.hustlebot.bot.TeamScanMode().requiredTemplates());
            if (!fehlend.isEmpty()) {
                client.sendCardTo(channel, titel, Sprache.t("Pictures are missing for the team scan: ", "Fuer den Team-Scan fehlen Bilder: ")
                        + "`" + String.join("`, `", fehlend) + "`" + Sprache.t(" (templates\\gelernt\\team -- please report it on GitHub).",
                        " (templates\\gelernt\\team -- bitte Autor Bescheid geben)."), DiscordClient.FARBE_WARNUNG);
                return;
            }
            if (engine.state() == BotEngine.State.LAEUFT) {
                if (teamEinschieben(true)) {
                    client.sendCardTo(channel, titel, Sprache.t("After the current run the bot looks at your team (barracks, hero, titan patrons), then carries on.",
                            "Nach dem laufenden Run sieht sich der Bot dein Team an (Kaserne, Held, Schirmherren) und macht dann weiter."), DiscordClient.FARBE_INFO);
                } else {
                    client.sendCardTo(channel, titel, Sprache.t("Not now -- a switch or the clan war is already pending. Try again in a minute.",
                            "Gerade nicht -- ein Wechsel oder der Clan-Krieg steht schon an. In einer Minute noch einmal."), DiscordClient.FARBE_WARNUNG);
                }
                return;
            }
            // Autor 15.10.: "Teste solange, bis er die Waffe auch liest" -- "!team scan waffe" sieht bei jeder Einheit
            // auch die Waffe an (sonst nur bei 1-5/6), einmalig fuer diesen Rundgang.
            de.hustlebot.bot.TeamScan.waffeTestEinmal(rest != null && rest.toLowerCase(Locale.ROOT).contains("waffe"));
            starten("teamscan 1");
            return;
        }
        if (istAn(w0) || istAus(w0)) {
            MeinTeam.setAutomatik(istAn(w0));
            client.sendCardTo(channel, titel, Sprache.t("Looking at your team every 3 days by itself: ", "Dein Team alle 3 Tage von selbst ansehen: ")
                    + "**" + anAus(istAn(w0)) + "**", DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.matches("punkte|points|messlatte|yardstick|eigenesteam")) {
            String a = w.length >= 2 ? w[1] : "";
            if (istAn(a) || istAus(a)) de.hustlebot.core.ArenaExtras.setEigenesTeam(istAn(a));
            client.sendCardTo(channel, titel, teamZeile(), DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.matches("held|hero")) {
            if (w.length >= 3 && w[1].matches("\\d{1,3}") && w[2].matches("\\d{1,3}")) {
                MeinTeam.setHeldPunkte(Integer.parseInt(w[1]), Integer.parseInt(w[2]));
            }
            client.sendCardTo(channel, titel, Sprache.t("Hero points: good main ability **+", "Held-Punkte: gute Hauptfaehigkeit **+")
                    + MeinTeam.heldHaupt() + Sprache.t("**, each good side ability **+", "**, je gute Nebenfaehigkeit **+") + MeinTeam.heldNeben() + "**"
                    + Sprache.t("\n_`!team hero 12 4` changes it._", "\n_`!team held 12 4` aendert es._"), DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.matches("name|spielername|playername")) {
            String n = restOriginal == null ? "" : restOriginal.trim().replaceFirst("(?i)^\\S+\\s*", "").trim();
            if (!n.isEmpty()) MeinTeam.setSpielername(n);
            client.sendCardTo(channel, titel, Sprache.t("Your player name: **", "Dein Spielername: **")
                    + (MeinTeam.spielername().isEmpty() ? Sprache.t("unknown", "unbekannt") : MeinTeam.spielername()) + "**"
                    + Sprache.t("\n_The bot reads it at the top left of the castle during the team scan; `!team name YourName` sets it by hand._",
                                "\n_Der Bot liest ihn beim Team-Scan oben links in der Burg; `!team name Autor` setzt ihn von Hand._"),
                    DiscordClient.FARBE_INFO);
            return;
        }
        if (w0.matches("vorlagen|templates|fehlt|missing|bilder|pictures")) {
            java.util.List<String> fehlend = engine.context().matcher().missing(de.hustlebot.bot.TeamScanMode.optionalTemplates());
            client.sendCardTo(channel, titel, fehlend.isEmpty()
                    ? Sprache.t("All pictures for the team scan are there.", "Alle Bilder fuer den Team-Scan sind da.")
                    : Sprache.t("Missing pictures (templates\\gelernt\\team): ", "Fehlende Bilder (templates\\gelernt\\team): ")
                            + "`" + String.join("`, `", fehlend) + "`", DiscordClient.FARBE_INFO);
            return;
        }
        client.sendCardTo(channel, titel, Sprache.t("Not understood. `!team`, `!team scan`, `!team on|off`, `!team points on|off`, `!team hero 12 4`, `!team name <name>`.",
                "Nicht verstanden. `!team`, `!team scan`, `!team an|aus`, `!team punkte an|aus`, `!team held 12 4`, `!team name <Name>`."),
                DiscordClient.FARBE_WARNUNG);
    }

    /** "Eigenes Team als Messlatte: an (73 Punkte, Stand 11.10.)" fuer !team punkte. */
    private String teamZeile() {
        int p = MeinTeam.punkte();
        return "**" + Sprache.t("Own team as yardstick in the arena: ", "Eigenes Team als Messlatte in der Arena: ")
                + anAus(de.hustlebot.core.ArenaExtras.eigenesTeam()) + "**"
                + (p >= 0 ? " (" + p + Sprache.t(" points, seen ", " Punkte, angesehen ")
                        + MeinTeam.stand().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm")) + ")"
                          : Sprache.t(" (not looked at yet)", " (noch nicht angesehen)"))
                + "\n_`!team punkte " + Sprache.t("on", "an") + "` / `" + Sprache.t("off", "aus") + "` · `!team scan`_";
    }

    /** Modi, in die sich Leuchtturm, Kolosseum, Turm und Clan-Laden einschieben duerfen. */
    private static final java.util.Set<String> NEBENBEI_BEI = java.util.Set.of("dungeon", "cathedral", "arena");   // wie im Hauptbot (seit 3.0 mit Arena)
    /**
     * Modi, die fuer den Clan-Krieg an einer sicheren Stelle pausieren (wechselJetzt) und danach weitergehen (Autor 24.10.).
     * Andere Modi (Stigmas, Gem Bay ...) spielen wie bisher zu Ende, der Krieg folgt danach -- ein Abbruch nach 15 Minuten
     * liesse sie sonst unfertig liegen (sie lassen sich nicht fortsetzen). Die Grabung bleibt draussen: ihre Grenze
     * ("!titan N") wird beim Start verbraucht, eine Fortsetzung liefe unbegrenzt.
     */
    private static final java.util.Set<String> KRIEG_FORTSETZEN = java.util.Set.of("portal", "titantower", "event");
    private final java.util.Set<String> vorlagenGemeldet = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean vorlagenDa(de.hustlebot.bot.GameMode mode, String id) {
        var fehlend = engine.context().matcher().missing(mode.requiredTemplates());
        if (fehlend.isEmpty()) return true;
        if (vorlagenGemeldet.add(id)) {
            Log.warn("Warteschlange", Sprache.t(modusName(id) + ": templates are missing (" + String.join(", ", fehlend)
                    + ") -- not visited.",
                    modusName(id) + ": es fehlen Vorlagen (" + String.join(", ", fehlend)
                    + ") -- wird nicht besucht."));
        }
        return false;
    }

    /** Wann der Bot zuletzt von selbst nach dem Reset losgeschickt wurde (hoechstens alle 20 min). */
    private long resetStartUm;
    /** Laeuft gerade die Kette vom 02:00-Auto-Start? Dann am Ende keinen "Bot fertig"-Bericht (Code-Pruefung 22.10.). */
    private volatile boolean resetStartKette;

    /**
     * Autor 22.10.: "Ausserdem soll der Bot jeden Tag um 2 Uhr nachts, wenn resettet (anhand der Zeitzone natuerlich auch),
     * immer den Titanenturm spielen, ausser er ist ausgemacht. Und die Ressourcen an Freunde schicken." Laeuft gerade ein
     * Auftrag, nimmt nebenbeiPruefen() beides nach dem Run mit. Steht der Bot (fertig, gestoppt, nie gestartet), startet
     * er beides hier selbst -- nur im Fenster nach dem Reset (02:00 Spielzeit), nicht um 12:00 (da spielt Autor vielleicht
     * selbst). Pausiert (Autor im Spiel) bleibt er stehen.
     */
    private void resetStartPruefen() {
        BotEngine.State st = engine.state();
        // Code-Pruefung 22.10.: nur wenn der Bot von selbst fertig ist oder noch nie lief -- NICHT nach Autors Stopp,
        // Maus-Stopp (Freund spielt), Not-Aus oder Fehler (vorher: Neustart um 02:00 und danach alle 20 min wieder).
        if (st != BotEngine.State.BEREIT && st != BotEngine.State.FERTIG) return;
        if (st != letzterZustand) return;   // den Zustandswechsel erst von zustandPruefen verarbeiten lassen
        // Autor am Handy ("anderes Geraet"), "!burg"-Heimweg oder Lesetest klicken gerade: nicht dazwischenfunken.
        if (de.hustlebot.bot.Stoerfenster.istRausgeworfen() || engine.heimGewuenscht()
                || de.hustlebot.bot.Lesetest.laeuft()) return;
        synchronized (kette) {
            if (!kette.isEmpty()) return;   // etwas wartet schon -- nicht wegwerfen
        }
        if (System.currentTimeMillis() - resetStartUm < 20 * 60_000L) return;
        // Beides nur in den ersten 4 Stunden nach dem Reset (02:00-06:00), sonst spielt Autor morgens vielleicht selbst.
        java.util.function.Predicate<java.time.LocalDateTime> nachReset = f -> f.getHour() == 2
                && java.time.Duration.between(f, de.hustlebot.core.Zeitzone.jetzt()).toHours() < 4;
        var turm = de.hustlebot.core.Turmplan.faellig().filter(nachReset);
        var freunde = de.hustlebot.core.Freundeplan.faellig().filter(nachReset);
        boolean mitTurm = turm.isPresent()
                && vorlagenDa(new de.hustlebot.bot.TitanTurmMode(), "titantower");
        boolean mitFreunden = freunde.isPresent()
                && vorlagenDa(new de.hustlebot.bot.FreundeSendenMode(), "freundesenden");
        if (!mitTurm && !mitFreunden) return;
        resetStartUm = System.currentTimeMillis();
        // Spiel zu: nicht starten (sonst alle 20 min eine Karte "Mach zuerst das Spiel auf") und nichts verbuchen --
        // in 20 min wieder nachsehen.
        if (!engine.context().window().locate()) return;
        synchronized (kette) {
            kette.clear();
            if (mitTurm) kette.add(new Auftrag("titantower", 1, "c", "", false));
            if (mitFreunden) kette.add(new Auftrag("freundesenden", 1, "c", "", false));
        }
        resetStartKette = true;
        naechstenStarten(true);
        // Code-Pruefung 22.10.: Versuche erst nach dem gelungenen Start verbuchen -- sonst war das Fenster verbraucht.
        if (engine.state() != BotEngine.State.LAEUFT) {
            resetStartKette = false;
            return;
        }
        if (mitTurm) de.hustlebot.core.Turmplan.angestossen(turm.get());
        if (mitFreunden) de.hustlebot.core.Freundeplan.angestossen(freunde.get());
        Log.info("Warteschlange", Sprache.t("Daily reset (02:00 game time): the bot was idle -- now "
                        + (mitTurm ? "titan tower" + (mitFreunden ? ", then " : "") : "") + (mitFreunden ? "send to friends" : "") + ".",
                "Tagesreset (02:00 Spielzeit): der Bot stand -- jetzt "
                        + (mitTurm ? "Titanen-Turm" + (mitFreunden ? ", danach " : "") : "") + (mitFreunden ? "Freunden senden" : "") + "."));
        naechstenStarten(true);
    }

    /**
     * Leuchtturm/Kolosseum (Energie voll), Titanen-Turm (02:00 und 12:00) und
     * Clan-Laden: nach dem laufenden Run kurz dorthin, danach geht der Rest des
     * laufenden Auftrags weiter (wie im Hauptbot).
     */
    private void nebenbeiPruefen() {
        if (engine.state() != BotEngine.State.LAEUFT) return;
        String modus = engine.lastModeId();
        if (modus == null || !(NEBENBEI_BEI.contains(modus) || KRIEG_FORTSETZEN.contains(modus))) return;
        // Autor 24.10.: bei allen anderen Modi nur der Clan-Krieg (Leuchtturm & Co. bleiben beim Kreislauf).
        boolean nurKrieg = !NEBENBEI_BEI.contains(modus);
        if (engine.wechselAngefordert()) return;
        // Clan-Krieg wartet oder laeuft schon (eingereiht)? Dann hier Ruhe -- erst den Krieg zu Ende, "Krieg geht vor".
        synchronized (kette) {
            if (kette.stream().anyMatch(a -> "clanwar".equals(a.modus()))) return;
        }
        java.util.List<String> dazu = new java.util.ArrayList<>();
        java.util.List<Runnable> vermerken = new java.util.ArrayList<>();   // erst nach dem Einschub
        // Clan-Krieg geht vor und NUR zu den festen Kriegszeiten (Weltzeit, s. Kriegsplan), einmal je Fenster --
        // nicht energie-/zeitgetaktet wie Leuchtturm/Kolosseum (Autor 16.10.). Eingeschoben ueber denselben Weg wie
        // die anderen, damit der Rest des laufenden Auftrags danach weiterlaeuft. (16.10.: vorher kam der Krieg im
        // Event-Ablauf nie dran, weil er nur hinten an den nie endenden Kathedralen-Auftrag gehaengt wurde.)
        var krieg = kriegsplan.faellig();
        // Code-Pruefung 16.10.: schlug der eingeschobene Krieg fehl (Burg nicht erreicht ...), kam er im Fenster nie wieder
        // -- frueher haengte kriegPruefen ihn nach jedem Lauf neu an. Jetzt: noch nicht aufgestellt -> nach 15 min nochmal.
        boolean kriegNochmal = krieg.isEmpty() && kriegsplan.istSelbsttaetig() && kriegsplan.nochAufzustellen().isPresent()
                && System.currentTimeMillis() - kriegEingeschobenUm > 15 * 60_000L;
        if ((krieg.isPresent() || kriegNochmal) && vorlagenDa(new de.hustlebot.bot.ClanWarMode(), "clanwar")) {
            if (krieg.isPresent()) vermerken.add(() -> kriegsplan.angestossen(krieg.get()));   // einmal je Fenster losgeschickt
            vermerken.add(() -> kriegEingeschobenUm = System.currentTimeMillis());
            vermerken.add(de.hustlebot.bot.HeldenSet::automatischerKrieg);   // Ben 17.10.: Helden-Set nur beim automatischen Krieg
            dazu.add("clanwar");
        } else if (!nurKrieg) {
            boolean leuchtturmDabei = false;
            for (var o : java.util.List.of(de.hustlebot.bot.LighthouseMode.LEUCHTTURM,
                                           de.hustlebot.bot.LighthouseMode.KOLOSSEUM)) {
                // Das Kolosseum kommt immer gleich mit dem Leuchtturm (29.09.).
                boolean mitLeuchtturm = o == de.hustlebot.bot.LighthouseMode.KOLOSSEUM && leuchtturmDabei && o.plan().istAn();
                if ((o.plan().faellig() || mitLeuchtturm) && vorlagenDa(new de.hustlebot.bot.LighthouseMode(o), o.id())) {
                    vermerken.add(() -> o.plan().angestossen());
                    dazu.add(o.id());
                    if (o == de.hustlebot.bot.LighthouseMode.LEUCHTTURM) leuchtturmDabei = true;
                }
            }
            var turm = de.hustlebot.core.Turmplan.faellig();
            if (turm.isPresent() && vorlagenDa(new de.hustlebot.bot.TitanTurmMode(), "titantower")) {
                vermerken.add(() -> de.hustlebot.core.Turmplan.angestossen(turm.get()));
                dazu.add("titantower");
            }
            // Freunden senden (Hauptbot 05.10., Freunde-Bot 22.10.): gleich hinter dem Turm, einmal je Fenster.
            var sendeTag = de.hustlebot.core.Freundeplan.faellig();
            if (sendeTag.isPresent()
                    && vorlagenDa(new de.hustlebot.bot.FreundeSendenMode(), "freundesenden")) {
                vermerken.add(() -> de.hustlebot.core.Freundeplan.angestossen(sendeTag.get()));
                dazu.add("freundesenden");
            }
            var laden = de.hustlebot.core.Ladenplan.faellig();
            if (laden.isPresent() && vorlagenDa(new de.hustlebot.bot.ClanLadenMode(), "clanladen")) {
                vermerken.add(() -> de.hustlebot.core.Ladenplan.angestossen(laden.get()));
                dazu.add("clanladen");
            }
            // Eigenes Team ansehen (Hauptbot 11.10., Auftrag 6): beim ersten Mal und alle 3 Tage, oder von Hand
            // ("!team scan" waehrend des Laufs) -- nachrangig, nie vor dem Krieg; hoechstens alle 30 Minuten angestossen,
            // weil scanFaellig() erst nach dem fertigen Scan wieder "nein" sagt.
            if ((MeinTeam.scanFaellig() || teamVonHand)
                    && System.currentTimeMillis() - teamAngestossenUm >= 30 * 60_000L
                    && teamVorlagenDa()) {
                vermerken.add(() -> { teamAngestossenUm = System.currentTimeMillis(); teamVonHand = false; });
                dazu.add("teamscan");
            }
        }
        // Autor 25.10. (Zweitkonto TR5): "alle 30 Minuten nachschauen, ob neue Gegner da sind" -- Burgen angreifen, bei
        // jedem Modus hier (auch Event/Portal), nur mit Schalter burgen-plan.txt = an. Nie zusammen mit dem Krieg.
        // Die Arena macht es selbst in den Wartezeiten zwischen den Stages (ArenaMode, Autor 25.10.).
        if (!dazu.contains("clanwar") && !"arena".equals(modus) && de.hustlebot.core.Burgenplan.faellig()
                && vorlagenDa(new de.hustlebot.bot.BurgenMode(), "burgen")) {
            vermerken.add(de.hustlebot.core.Burgenplan::angestossen);
            dazu.add("burgen");
        }
        if (dazu.isEmpty()) return;
        synchronized (kette) {
            if (kette.isEmpty()) return;
            vermerken.forEach(Runnable::run);
            Auftrag laufend = kette.get(0);
            var t = engine.tracker();
            // Arena (Freunde-Pruefung 11.10.): Ein Wechsel aus der Warteschlange spielt kein Turnier mehr zu Ende --
            // deshalb hier ohne "- 1"; bei FERTIG wird um die wirklich gespielten Turniere gekuerzt.
            boolean arena = "arena".equals(laufend.modus());
            // Autor 24.10.: Portal, Titanen-Turm und Event wechseln an einer sicheren Stelle MITTEN im Lauf (wechselJetzt)
            // und gehen danach weiter -- deren Rest ist der ganze offene Teil, nicht "ohne den laufenden Durchlauf".
            // Das Event wechselt erst nach dem naechsten fertigen Feld (wie ein Durchlauf) -- dort gilt die "- 1" weiter.
            boolean fortsetzen = KRIEG_FORTSETZEN.contains(laufend.modus()) && !"event".equals(laufend.modus());
            // Portal ohne Plan: laeuft gerade ein Kampf, zaehlt der Motor ihn noch, bevor er wechselt (Code-Review 24.10.).
            int nochImKampf = "portal".equals(laufend.modus())
                    && engine.modus("portal") instanceof de.hustlebot.bot.PortalMode p && p.kampfLaeuft() ? 1 : 0;
            int rest = t == null ? 0 : fortsetzen ? Math.max(1, t.targetRuns() - t.completedRuns() - nochImKampf)
                    : Math.max(0, t.targetRuns() - t.completedRuns() - (arena ? 0 : 1));
            int ab = 1;
            for (String m : dazu) kette.add(ab++, new Auftrag(m, 1, "c", "", false));
            if (rest > 0) {
                Auftrag restAuftrag = new Auftrag(laufend.modus(), rest,
                        fortsetzen ? "fortsetzung" : laufend.weg(), laufend.freund(), laufend.warten());
                kette.add(ab, restAuftrag);
                if (arena) {
                    arenaEinschubLaufend = laufend;
                    arenaEinschubRest = restAuftrag;
                    arenaEinschubFertig = t.completedRuns();
                }
            }
        }
        engine.wechselNachDurchlauf();
        Log.info("Warteschlange", Sprache.t("After the current run: " + String.join(", ", dazu.stream().map(DiscordBridge::modusName).toList())
                + ", then on with " + modusName(modus) + ".",
                "Nach dem laufenden Run: " + String.join(", ", dazu.stream().map(DiscordBridge::modusName).toList())
                + ", danach weiter mit " + modusName(modus) + "."));
    }

    private void naechstenStarten(boolean melden) {
        Auftrag a;
        synchronized (kette) {
            if (kette.isEmpty()) return;
            a = kette.get(0);
        }
        // Portal nach dem Clan-Krieg: den Rest des Plans uebernehmen (Autor 24.10.).
        if ("portal".equals(a.modus()) && "fortsetzung".equals(a.weg())) de.hustlebot.bot.PortalMode.fortsetzungErwarten();
        if ("titantower".equals(a.modus()) && "fortsetzung".equals(a.weg())) de.hustlebot.bot.TitanTurmMode.fortsetzungErwarten();

        if ("cathedral".equals(a.modus())) {
            engine.setCathedralRoute(a.weg());
        }

        // Jeder Auftrag bringt seinen Mitspieler mit: mit Freund nur, wenn
        // einer genannt wurde -- sonst liefe "!start dungeon 10" noch mit dem
        // Freund vom letzten Mal.
        boolean ok = engine.start(a.modus(), a.runs(),
                a.freund().isEmpty() ? "random" : (a.warten() ? "friendwait" : "friend"),
                a.freund());
        if (!ok && !engine.context().window().locate()) {
            // Hustle Castle ist nicht offen. Das hat der Motor schon ruhig
            // gemeldet (setMeldungOhneSpiel oben) -- keine rote Karte dazu.
            synchronized (kette) {
                kette.clear();
            }
            return;
        }
        if (!ok) {
            // Den Grund gleich mitschicken. Sonst weiss ein Freund nur, DASS es
            // nicht geht -- etwa nicht, welche seiner eigenen Bilder noch fehlen.
            // Nur bei FEHLER: laeuft der Bot schon, steht dort der Text des
            // laufenden Durchgangs, und der ist kein Grund.
            // 16.10.: Pausiert (Maus bewegt) lehnt der Motor jeden Start ab -- dann DAS sagen, nicht "laeuft das Spiel?".
            String grund = engine.state() == BotEngine.State.FEHLER ? engine.statusText()
                    : engine.state() == BotEngine.State.PAUSIERT
                    ? Sprache.t("The bot is paused. Press **F10** to continue -- or press Stop first, then start again.",
                                "Der Bot ist pausiert. **F10** druecken zum Weitermachen -- oder erst Stopp, dann neu starten.")
                    // 22.10. nachts (Befehls-Test): "!tower" waehrend eines laufenden Modus -- die Karte fragte "Laeuft das
                    // Spiel?", obwohl der Bot nur beschaeftigt war.
                    : engine.state() == BotEngine.State.LAEUFT
                    ? Sprache.t("The bot is playing right now. Stop it first (`!stop`), or queue this: `!after " + a.modus() + "`.",
                                "Der Bot spielt gerade. Erst anhalten (`!stop`) oder hinten anhaengen: `!after " + a.modus() + "`.")
                    : null;
            client.sendCard(Sprache.t("Could not start", "Konnte nicht starten"),
                    "**" + modusName(a.modus()) + Sprache.t("** did not start.\n", "** ist nicht losgelaufen.\n")
                    + (grund == null || grund.isBlank()
                       ? Sprache.t("Is the game running, and have you captured your own pictures?", "Läuft das Spiel, und hast du deine eigenen Bilder aufgenommen?")
                       : grund),
                    DiscordClient.FARBE_FEHLER);
            synchronized (kette) {
                kette.clear();
            }
            return;
        }
        letzterZustand = BotEngine.State.LAEUFT;

        if (melden || !"clanwar".equals(a.modus())) {
            client.sendCard(Sprache.t("Started", "Gestartet"),
                    "**" + modusName(a.modus()) + "**"
                    + (a.runs() > 1 ? " x" + a.runs() : "")
                    + ("cathedral".equals(a.modus())
                            ? ("b".equals(a.weg()) ? Sprache.t("  ·  every waypoint", "  ·  über alle Wegpunkte") : "c".equals(a.weg()) ? "  ·  Turbo (c)" : Sprache.t("  ·  shortest way", "  ·  kürzester Weg"))
                            : "")
                    + (a.freund().isEmpty() ? "" : Sprache.t("  ·  with ", "  ·  mit ") + a.freund()
                            + (a.warten() ? Sprache.t(", waiting for the invitation", ", wartet auf die Einladung") : "")),
                    DiscordClient.FARBE_START);
        }
    }

    /**
     * Der Modus, wie ein Mensch ihn liest. Getippt wird weiter englisch
     * (!start dungeon) -- das steht so in der Anleitung und passt zum Spiel.
     */
    private static String modusName(String modus) {
        if (modus == null) return "";
        return switch (modus) {
            case "dungeon"   -> Sprache.t("Dungeon", "Verlies");
            case "cathedral" -> Sprache.t("Cathedral", "Kathedrale");
            case "clanwar"   -> Sprache.t("Clan war", "Clan-Krieg");
            case "event"     -> Sprache.t("Hammer event", "Hammer-Event");
            case "lighthouse" -> Sprache.t("Lighthouse", "Leuchtturm");
            case "colosseum"  -> Sprache.t("Colosseum", "Kolosseum");
            case "titantower" -> Sprache.t("Titan tower", "Titanen-Turm");
            case "freundesenden" -> Sprache.t("Send to friends", "Freunden senden");
            case "burgen" -> Sprache.t("Castle raids", "Burgen angreifen");
            case "clanladen"  -> Sprache.t("Clan store", "Clan-Laden");
            case "grabung"    -> Sprache.t("Titan excavation", "Titan-Grabung");
            case "stigmas"    -> "Stigmas";
            case "arena"      -> "Arena";
            case "teamscan"   -> Sprache.t("Own team", "Eigenes Team");
            case "heldenset"  -> Sprache.t("Hero set (test)", "Helden-Set (Probe)");
            case "gembay"     -> "Gem Bay";
            case "portal"     -> "Portal";
            case "nahrungskisten" -> Sprache.t("Food chests", "Nahrungskisten");
            default          -> modus;
        };
    }

    /**
     * Arena mit Aepfeln statt Tickets? (Hauptbot 25.09.: "!start arena 10 food oder !start arena 10 aepfel
     * ... alle moeglichen Schreibweisen".)
     */
    static boolean mitEssen(String rest) {
        if (rest == null) return false;
        String t = rest.toLowerCase(java.util.Locale.ROOT);
        return t.matches(".*(^|\\s)(food|foods|fod|fodd|foood|f00d|essen|esen|nahrung|futter"
                + "|äpfel|äpfeln|äpfl|äpfle|apfel|apfeln|aepfel|aepfeln|aepfl|apfl"
                + "|apple|apples|aple|aples|appel|appels|appl|äppel|aeppel|obst)(\\s|$).*");
    }

    private static String modusAus(String rest) {
        if (rest == null) return null;
        String t = rest.toLowerCase(Locale.ROOT);
        // Titan-Event "Titanic Excavation" (Hauptbot 04.10.): "!start grabung", "!start titan event",
        // "!start excavation". MUSS vor "event" (Hammer) und "titan" (Titanen-Turm) stehen --
        // "!start titan" allein bleibt der Titanen-Turm.
        if (t.contains("grabung") || t.contains("excavation") || t.contains("titanic")
                || t.matches(".*titan(en)?[ -]?event.*")
                || t.matches(".*(^|\\s)(dig|graben|mine)(\\s|$).*")) return "grabung";
        // Arena (Hauptbot 25.09., Freunde 3.0): auch Tippfehler und "Turnier"/"tournament".
        if (t.contains("arena") || t.contains("turnier") || t.contains("tournament")
                || t.matches(".*(^|\\s)(arene|arenen|arenna|areena|arna|arnea|aerna|arean"
                        + "|ahrena|tunier|turnir|tunir|tournement|tourney|pvp)(\\s|$).*")) return "arena";
        // Helden-Set-Probe (17.10.): "!start heldenset" -- vor "team", sonst wuerde es nichts.
        if (t.contains("heldenset") || t.contains("heroset")) return "heldenset";
        // Eigenes Team ansehen (Hauptbot 11.10., Auftrag 6): Kaserne, Held, Schirmherren -- "!start teamscan".
        if (t.contains("teamscan") || t.contains("team scan") || t.contains("eigenes team")
                || t.contains("eigenesteam") || t.contains("meinteam") || t.contains("myteam")) {
            return "teamscan";
        }
        // Autor 25.10. (Zweitkonten): Burgen anderer Spieler auf den Inseln der Weltkarte angreifen -- "!start burgen".
        if (t.contains("burgen") || t.contains("castles") || t.contains("raid") || t.contains("pvp map")) return "burgen";
        if (t.contains("event") || t.contains("hammer"))         return "event";
        // Hauptbot 09.10. / Freunde-Bot 22.10.: Portal (Level 80 im Autokampf), Gem Bay, Nahrungskisten.
        if (t.contains("portal"))                                return "portal";
        if (t.contains("gembay") || t.contains("gem bay") || t.contains("gem-bay")
                || t.contains("edelstein"))                      return "gembay";
        if (t.contains("nahrungskisten") || t.contains("foodchests")
                || t.contains("food chests"))                    return "nahrungskisten";
        if (t.contains("lighthouse") || t.contains("leuchtturm")) return "lighthouse";
        if (t.contains("colosseum") || t.contains("kolosseum"))  return "colosseum";
        // Freunden senden (Hauptbot 05.10., Freunde-Bot 22.10.). Nicht "friend"/"freunde" allein: das gehoert im Verlies
        // zum Mitspieler.
        if (t.matches(".*freunde?n?[ -]?senden.*") || t.contains("geschenke") || t.contains("sendfriends")
                || t.contains("sendtofriends") || t.contains("friendgifts")) return "freundesenden";
        if (t.contains("titan") || t.contains("turm") && !t.contains("leuchtturm")) return "titantower";
        if (t.contains("clanladen") || t.contains("clanstore") || t.contains("clan store")
                || t.contains("clanshop"))                       return "clanladen";
        if (t.contains("cathedral") || t.contains("kathedrale")) return "cathedral";
        if (t.contains("dungeon") || t.contains("verlies"))      return "dungeon";
        if (t.contains("clanwar") || t.contains("clan war")
                || t.contains("war"))                            return "clanwar";
        return null;
    }

    // ------------------------------------------------------------------
    //  Start-Formular (vom Hauptbot, 29.09.; Autor: "start formular einbauen
    //  im freunde bot"). "!start" ohne Angaben oder "/start" oeffnet es.
    //  Schritt 1 fuer alle Modi, Schritt 2 nur fuer den Event-Ablauf. Es
    //  erzeugt nur Befehle, die dieser Bot versteht (englische Modusnamen).
    // ------------------------------------------------------------------

    /** Die Anbindung ans Gateway: Start-Formular und Einstellungs-Formulare (07.10.). */
    private final class StartFormularAnbindung implements DiscordGateway.FixAnbindung {
        @Override public String startFormular(String autor, String knopf) { return DiscordBridge.this.startFormular(knopf); }
        @Override public void startEingereicht(String kanal, String autor, java.util.Map<String, String> felder) {
            DiscordBridge.this.startEingereicht(kanal, felder);
        }
        @Override public String setFormular(String autor, String bereich) { return DiscordBridge.this.setFormular(bereich); }
        @Override public void setEingereicht(String kanal, String autor, String bereich, java.util.Map<String, String> felder) {
            DiscordBridge.this.setEingereicht(kanal, bereich, felder);
        }
    }

    // ------------------------------------------------------------------
    //  !settings mit Knoepfen und Formularen -- wie im Hauptbot (Autor, 07.10.:
    //  "Es soll im Freunde-Bot genauso ausschauen mit genau den gleichen Klicks
    //  wie in meinem normalen Bot"). Nur, was dieser Bot auch kann.
    // ------------------------------------------------------------------

    /**
     * Die fuenf Knoepfe unter !settings -- bei jedem Aufruf neu, damit die Beschriftung der gerade
     * eingestellten Sprache folgt (als Konstante blieb sie nach !deutsch/!english bis zum Neustart stehen).
     */
    private static java.util.List<String[]> setKnoepfeKurz() {
        return java.util.List.of(
            new String[]{"⚔️ " + Sprache.t("Clan war", "Clan-Krieg"), "setformular:krieg"},
            new String[]{"💰 " + Sprache.t("Payment", "Bezahlen"), "setformular:bezahlen"},
            new String[]{"🔨 " + Sprache.t("Hammer event", "Hammer-Event"), "setformular:automatik"},
            new String[]{"🗼 " + Sprache.t("On the side", "Nebenbei"), "setformular:nebenbei"},
            new String[]{"🛡️ " + Sprache.t("Squads", "Aufstellung"), "setformular:aufstellung"});
    }

    private static String anAus(boolean an) {
        return an ? Sprache.t("on", "an") : Sprache.t("off", "aus");
    }

    private static String bezahlKurz(String modus) {
        String t = switch (modus) {
            case "dungeon" -> Sprache.t("torches", "Fackeln");
            case "cathedral" -> Sprache.t("keys", "Schluessel");
            default -> Sprache.t("tickets", "Tickets");
        };
        String n = Sprache.t("food", "Nahrung"), k = Sprache.t("chests", "Kisten");
        var art = de.hustlebot.core.Bezahlung.standard(modus);
        long l = de.hustlebot.core.Bezahlung.limit(modus);
        String kette = switch (art) {
            case TICKETS -> t;
            case NAHRUNG -> n;
            case ERST_TICKETS -> t + " → " + n;
            case ALLES -> t + " → " + n + " → " + k;
            case NAHRUNG_KISTEN -> n + " → " + k;   // Mia 21.10.: ohne Tickets
        };
        return kette + (art.mitNahrung() && l > 0 ? " (max " + de.hustlebot.core.Bezahlung.zahl(l) + ")" : "");
    }

    /** !settings ohne Zusatz: kurze Karte mit Knoepfen (wie im Hauptbot). Alles andere: !settings all. */
    private void einstellungenKurz() {
        String text = Sprache.t("**Press a button to change something.**\n\n",
                                "**Zum Aendern auf einen Knopf druecken.**\n\n")
                + "⚔️ **" + Sprache.t("Clan war", "Clan-Krieg") + ":** "
                + Sprache.t("deploy automatically ", "selbst aufstellen ") + anAus(kriegsplan.istSelbsttaetig())
                + " · " + Sprache.t("food chests before ", "Nahrungskisten vorher ")
                + anAus(de.hustlebot.bot.Nahrungskisten.istAn()) + "\n"
                + "💰 **" + Sprache.t("Payment", "Bezahlen") + ":** "
                + Sprache.t("Dungeon ", "Verlies ") + bezahlKurz("dungeon")
                + " · " + Sprache.t("Cathedral ", "Kathedrale ") + bezahlKurz("cathedral")
                + " · Arena " + bezahlKurz("arena") + "\n"
                + "🔨 **" + Sprache.t("Hammer event", "Hammer-Event") + ":** " + Sprache.t("automation ", "Automatik ") + anAus(de.hustlebot.core.Eventautomatik.schalterAn())
                + " · " + Sprache.t(de.hustlebot.core.Eventautomatik.pass().englisch, de.hustlebot.core.Eventautomatik.pass().anzeige)
                + " · max " + de.hustlebot.core.Eventautomatik.maxLupen() + Sprache.t(" magnifiers", " Lupen")
                + " · " + Sprache.t("then ", "danach ") + de.hustlebot.core.Eventautomatik.verliesRuns()
                + Sprache.t(" dungeon runs", " Verlies-Runs") + "\n"
                + "🗼 **" + Sprache.t("On the side", "Nebenbei") + ":** "
                + Sprache.t("Lighthouse ", "Leuchtturm ") + anAus(de.hustlebot.bot.LighthouseMode.LEUCHTTURM.plan().istAn())
                + " · " + Sprache.t("Colosseum ", "Kolosseum ") + anAus(de.hustlebot.bot.LighthouseMode.KOLOSSEUM.plan().istAn())
                + " · " + Sprache.t("Titan tower ", "Titanen-Turm ") + anAus(de.hustlebot.core.Turmplan.istAn())
                + " · " + Sprache.t("Send to friends ", "Freunden senden ") + anAus(de.hustlebot.core.Freundeplan.istAn())
                + "\n📣 **" + Sprache.t("Reports", "Meldungen") + ":** " + de.hustlebot.core.Meldetakt.beschreibung()
                + "\n📦 **" + Sprache.t("Event chests", "Kisten im Event") + ":** " + de.hustlebot.core.Eventkisten.beschreibung()
                + "\n\n_" + Sprache.t("Everything else (emergency key, tip of the day ...): ",
                                        "Alles andere (Not-Aus-Taste, Tagestipp ...): ")
                + "`!settings all`_";
        client.sendCardMitKnoepfen(channelId, Sprache.t("Settings", "Einstellungen"), text,
                DiscordClient.FARBE_INFO, setKnoepfeKurz());
    }

    /** Eine An/Aus-Auswahl mit dem jetzigen Wert vorausgewaehlt. */
    private static String anAusFeld(String id, String titel, String erklaerung, boolean an) {
        return "{\"type\":18,\"label\":" + fjs(fschneiden(titel, 45)) + ",\"description\":" + fjs(fschneiden(erklaerung, 100))
                + ",\"component\":{\"type\":3,\"custom_id\":\"" + id + "\",\"required\":true,\"options\":["
                + foption(Sprache.t("On", "An"), "an", null, an) + "," + foption(Sprache.t("Off", "Aus"), "aus", null, !an) + "]}}";
    }

    /** Ein Schreibfeld mit dem jetzigen Wert. */
    private static String textFeld(String id, String titel, String erklaerung, String wert) {
        return "{\"type\":18,\"label\":" + fjs(fschneiden(titel, 45)) + ",\"description\":" + fjs(fschneiden(erklaerung, 100))
                + ",\"component\":{\"type\":4,\"custom_id\":\"" + id + "\",\"style\":1,\"required\":false,"
                + "\"min_length\":0,\"max_length\":20,\"value\":" + fjs(wert) + "}}";
    }

    private static String passFeld() {
        var pass = de.hustlebot.core.Eventautomatik.pass();
        return "{\"type\":18,\"label\":" + fjs(Sprache.t("Your event pass", "Dein Event-Pass")) + ",\"description\":"
                + fjs(Sprache.t("Decides whether the bot may use magnifiers and blue hammers",
                                "Bestimmt, ob der Bot Lupen und blaue Haemmer benutzen darf"))
                + ",\"component\":{\"type\":3,\"custom_id\":\"pass\",\"required\":true,\"options\":["
                + foption(Sprache.t("No pass (hammers only)", "Kein Pass (nur Haemmer)"), "kein", null,
                        pass == de.hustlebot.core.Eventautomatik.Pass.KEIN) + ","
                + foption(Sprache.t("Silver pass (hammers + magnifiers)", "Silber-Pass (Haemmer + Lupen)"), "silber", null,
                        pass == de.hustlebot.core.Eventautomatik.Pass.SILBER) + ","
                + foption(Sprache.t("Gold pass (+ blue hammers)", "Gold-Pass (+ blaue Haemmer)"), "gold", null,
                        pass == de.hustlebot.core.Eventautomatik.Pass.GOLD) + "]}}";
    }

    /** Eine Auswahl (auch mehrere) mit fertigen Optionen (Hauptbot 11.10., fuer "!stigmas einstellen"). */
    private static String auswahlFeld(String id, String titel, String erklaerung, CharSequence optionen, int min, int max) {
        return "{\"type\":18,\"label\":" + fjs(fschneiden(titel, 45)) + ",\"description\":" + fjs(fschneiden(erklaerung, 100))
                + ",\"component\":{\"type\":3,\"custom_id\":\"" + id + "\",\"required\":" + (min > 0)
                + ",\"min_values\":" + min + ",\"max_values\":" + max + ",\"options\":[" + optionen + "]}}";
    }

    private String setFormular(String bereich) {
        String titel, felder;
        switch (bereich) {
            case "stigmas" -> {
                // Hauptbot 11.10. (Autor: "Sets und Stats in den Einstellungen oder einem Discord-Formular waehlbar").
                StringBuilder sorten = new StringBuilder(), level = new StringBuilder(), rang = new StringBuilder(),
                        zweit = new StringBuilder(), sets = new StringBuilder();
                for (String n : de.hustlebot.core.Stigmaplan.NAMEN) {
                    sorten.append(sorten.length() == 0 ? "" : ",").append(foption(de.hustlebot.core.Stigmaplan.gross(n), n,
                            null, de.hustlebot.core.Stigmaplan.namen().contains(n)));
                }
                for (String l : de.hustlebot.core.Stigmaplan.LEVEL) {
                    level.append(level.length() == 0 ? "" : ",").append(foption(de.hustlebot.core.Stigmaplan.levelText(l), l,
                            l.equals("anders3") ? Sprache.t("3x of a stat that is not ticked -- one purple ray, +2 levels",
                                                            "3x eines nicht angehakten Werts -- ein lila Strahl, +2 Level")
                                    : l.endsWith("4") ? Sprache.t("up to level 20", "bis Level 20") : Sprache.t("up to level 3-4", "bis Level 3-4"),
                            de.hustlebot.core.Stigmaplan.leveln(l)));
                }
                for (String g : de.hustlebot.core.Stigmaplan.WERTE) {
                    rang.append(rang.length() == 0 ? "" : ",").append(foption(de.hustlebot.core.Stigmaplan.wertText(g), g,
                            null, de.hustlebot.core.Stigmaplan.hauptKuerzel().equals(g)));
                    zweit.append(zweit.length() == 0 ? "" : ",").append(foption(de.hustlebot.core.Stigmaplan.wertText(g), g,
                            null, de.hustlebot.core.Stigmaplan.zweitKuerzel().equals(g)));
                }
                for (String set : de.hustlebot.core.Stigmaplan.SETS) {
                    sets.append(sets.length() == 0 ? "" : ",").append(foption(de.hustlebot.core.Stigmaplan.gross(set), set,
                            null, de.hustlebot.core.Stigmaplan.behalten().contains(set)));
                }
                titel = "Stigmas – Last Guardian, Bull";
                felder = String.join(",",
                        auswahlFeld("sorten", Sprache.t("Kinds to look at", "Sorten, die angesehen werden"), "Bull Mind, Will, Courage ...",
                                sorten, 1, de.hustlebot.core.Stigmaplan.NAMEN.size()),
                        auswahlFeld("level", Sprache.t("Level up when ...", "Hochleveln, wenn ..."),
                                Sprache.t("Nothing ticked = never level up", "Nichts angehakt = nie hochleveln"),
                                level, 0, de.hustlebot.core.Stigmaplan.LEVEL.size()),
                        auswahlFeld("haupt", Sprache.t("Swap by: main stat", "Tauschen nach: Hauptwert"),
                                Sprache.t("Counts most when equipping (standard: magic damage)",
                                          "Zaehlt beim Einsetzen am meisten (Standard: mag. Schaden)"),
                                rang, 1, 1),
                        auswahlFeld("zweit", Sprache.t("Swap by: second stat", "Tauschen nach: Zweitwert"),
                                Sprache.t("Counts next (standard: health). Own ranking: button \"Ranking\"",
                                          "Zaehlt danach (Standard: Leben). Eigene Rangliste: Knopf \"Rangliste\""),
                                zweit, 1, 1),
                        auswahlFeld("behalten", Sprache.t("Keep when deleting", "Beim Loeschen behalten"),
                                Sprache.t("These sets are never disassembled (standard: Bull, Dragon, Wolf)",
                                          "Diese Sets werden nie zerlegt (Standard: Bull, Dragon, Wolf)"),
                                sets, 0, de.hustlebot.core.Stigmaplan.SETS.size()));
            }
            case "stigmarang" -> {
                // Hauptbot 11.10. 02:55 (Autor: "dass ich theoretisch alle moeglichen Kombinationen rausfiltern kann").
                StringBuilder text = new StringBuilder();
                java.util.List<String> zeilen = de.hustlebot.core.Stigmaplan.einstellungsZeilen();
                for (int i = 0; i < zeilen.size(); i++) text.append(i == 0 ? "" : "\n").append(i + 1).append(". ").append(zeilen.get(i));
                titel = Sprache.t("Stigmas – own ranking", "Stigmas – eigene Rangliste");
                felder = "{\"type\":18,\"label\":" + fjs(Sprache.t("Ranking (top = best)", "Rangliste (oben = am besten)"))
                        + ",\"description\":" + fjs(fschneiden(Sprache.t("One tier per line, e.g. 4x armor + 1x health · 3x health without damage. Empty = standard",
                                "Eine Stufe je Zeile, z. B. 4x Rüstung + 1x Leben · 3x Leben ohne Schaden. Leer = Standard"), 100))
                        + ",\"component\":{\"type\":4,\"custom_id\":\"rangliste\",\"style\":2,\"required\":false,"
                        + "\"min_length\":0,\"max_length\":4000,\"value\":" + fjs(text.toString()) + "}}";
            }
            case "krieg" -> {
                titel = Sprache.t("Clan war & food", "Clan-Krieg & Nahrung");
                felder = String.join(",",
                        anAusFeld("krieg", Sprache.t("Deploy clan war automatically", "Clan-Krieg selbst aufstellen"),
                                Sprache.t("Places your squads at war times", "Setzt deine Trupps zu den Kriegszeiten automatisch"),
                                kriegsplan.istSelbsttaetig()),
                        anAusFeld("nahrung", Sprache.t("Food chests before the war", "Nahrungskisten vor dem Krieg"),
                                Sprache.t("Not enough food: open chests first", "Reicht die Nahrung nicht: erst Kisten oeffnen"),
                                de.hustlebot.bot.Nahrungskisten.istAn()),
                        anAusFeld("laden", Sprache.t("Buy food chests in the clan store", "Food chests im Clan-Laden kaufen"),
                                Sprache.t("At 02:00 and 12:00, with clan coins (never diamonds)", "Um 02:00 und 12:00, mit Clan-Muenzen (nie Diamanten)"),
                                de.hustlebot.core.Ladenplan.istAn()));
            }
            case "aufstellung" -> {
                titel = Sprache.t("Squads", "Truppenaufstellung");
                StringBuilder b = new StringBuilder();
                for (String m : de.hustlebot.bot.Aufstellung.modi()) {
                    boolean prev = de.hustlebot.bot.Aufstellung.istPrevious(m);
                    if (b.length() > 0) b.append(',');
                    b.append("{\"type\":18,\"label\":").append(fjs(fschneiden(de.hustlebot.bot.Aufstellung.anzeige(m), 45)))
                     .append(",\"component\":{\"type\":3,\"custom_id\":\"auf_").append(m).append("\",\"required\":true,\"options\":[")
                     .append(foption(Sprache.t("Deploy the best (strongest)", "Deploy the best (die Staerksten)"), "best", null, !prev)).append(',')
                     .append(foption(Sprache.t("Previous Squad (like last time)", "Previous Squad (wie beim letzten Mal)"), "previous", null, prev))
                     .append("]}}");
                }
                felder = b.toString();
            }
            case "nebenbei" -> {
                titel = Sprache.t("On the side", "Nebenbei");
                int[] mx = {de.hustlebot.core.Turmplan.maximum("easy"), de.hustlebot.core.Turmplan.maximum("hard"),
                        de.hustlebot.core.Turmplan.maximum("veryhard")};
                felder = String.join(",",
                        anAusFeld("leuchtturm", Sprache.t("Lighthouse between runs", "Leuchtturm zwischen den Runs"),
                                Sprache.t("Fights when there is energy", "Kaempft, wenn Energie da ist"),
                                de.hustlebot.bot.LighthouseMode.LEUCHTTURM.plan().istAn()),
                        anAusFeld("kolosseum", Sprache.t("Colosseum between runs", "Kolosseum zwischen den Runs"),
                                Sprache.t("Fights when there is energy", "Kaempft, wenn Energie da ist"),
                                de.hustlebot.bot.LighthouseMode.KOLOSSEUM.plan().istAn()),
                        textFeld("grenze", Sprache.t("Colosseum: enemy too strong from", "Kolosseum: Gegner zu stark ab"),
                                Sprache.t("This much MORE power than you, e.g. 500000 or 500k", "So viel MEHR Power als du, z. B. 500000 oder 500k"),
                                String.valueOf(de.hustlebot.bot.LighthouseMode.kolosseumGrenze())),
                        anAusFeld("turm", Sprache.t("Titan tower (02:00 and 12:00)", "Titanen-Turm (02:00 und 12:00)"),
                                Sprache.t("Easy, Hard and Very Hard in auto battle", "Easy, Hard und Very Hard im Auto-Kampf"),
                                de.hustlebot.core.Turmplan.istAn()),
                        textFeld("turmmax", Sprache.t("Tower: highest level", "Turm: hoechstes Level"),
                                Sprache.t("Easy, Hard, Very Hard -- 1 to 30 each", "Easy, Hard, Very Hard -- je 1 bis 30"),
                                mx[0] + "," + mx[1] + "," + mx[2]));
            }
            case "automatik" -> {
                titel = Sprache.t("Hammer event", "Hammer-Event");
                felder = String.join(",",
                        anAusFeld("an", Sprache.t("Event automation (loop)", "Event-Automatik (Kreislauf)"),
                                Sprache.t("Default for !start cathedral", "Standard fuer !start kathedrale"),
                                de.hustlebot.core.Eventautomatik.schalterAn()),
                        passFeld(),
                        textFeld("lupen", Sprache.t("Magnifiers per field (max)", "Lupen je Feld (hoechstens)"),
                                Sprache.t("Gold pass: 5 is good. 0 = none", "Gold-Pass: 5 ist gut (3 kommen je Feld zurueck). 0 = keine"),
                                String.valueOf(de.hustlebot.core.Eventautomatik.maxLupen())),
                        textFeld("runs", Sprache.t("Dungeon runs at the end", "Verlies-Runs am Ende"),
                                Sprache.t("When keys, chests and hammers are gone (1 to 500)", "Wenn Schluessel, Kisten und Haemmer alle sind (1 bis 500)"),
                                String.valueOf(de.hustlebot.core.Eventautomatik.verliesRuns())));
            }
            case "bezahlen" -> {
                titel = Sprache.t("Payment", "Bezahlen");
                StringBuilder b = new StringBuilder();
                for (String m : de.hustlebot.core.Bezahlung.MODI) {
                    var jetzt = de.hustlebot.core.Bezahlung.standard(m);
                    String tickets = m.equals("dungeon") ? Sprache.t("torches", "Fackeln")
                            : m.equals("cathedral") ? Sprache.t("keys", "Schluessel") : "Tickets";
                    String nur = Sprache.t("only ", "nur "), erst = Sprache.t("first ", "erst "), nahrung = Sprache.t("food", "Nahrung");
                    b.append("{\"type\":18,\"label\":").append(fjs(de.hustlebot.core.Bezahlung.anzeigeName(m)))
                     .append(",\"component\":{\"type\":3,\"custom_id\":\"bez_").append(m).append("\",\"required\":true,\"options\":[")
                     .append(foption(nur + tickets, "tickets", null, jetzt == de.hustlebot.core.Bezahlung.Art.TICKETS)).append(',')
                     .append(foption(nur + nahrung, "food", null, jetzt == de.hustlebot.core.Bezahlung.Art.NAHRUNG)).append(',')
                     .append(foption(erst + tickets + Sprache.t(", then food", ", dann Nahrung"), "tickets+food", null,
                             jetzt == de.hustlebot.core.Bezahlung.Art.ERST_TICKETS)).append(',')
                     .append(foption(tickets + Sprache.t(" -> food -> food chests", " -> Nahrung -> Nahrungskisten"), "alles",
                             Sprache.t("only from the bag", "nur aus der Tasche"), jetzt == de.hustlebot.core.Bezahlung.Art.ALLES)).append(',')
                     // Mia 21.10.: ohne Tickets, aber mit Nahrungskisten ("food only" oeffnet keine)
                     .append(foption(Sprache.t("food -> food chests (no tickets)", "Nahrung -> Nahrungskisten (ohne Tickets)"), "food+chests",
                             Sprache.t("only from the bag", "nur aus der Tasche"), jetzt == de.hustlebot.core.Bezahlung.Art.NAHRUNG_KISTEN))
                     .append("]}},");
                }
                String limits = de.hustlebot.core.Bezahlung.MODI.stream()
                        .map(m -> kurzeMenge(de.hustlebot.core.Bezahlung.limit(m)))
                        .reduce((x, y) -> x + "," + y).orElse("");
                b.append(textFeld("limit", Sprache.t("Food limit per day", "Nahrungs-Limit je Tag"),
                        Sprache.t("Dungeon, cathedral, arena -- e.g. off,30m,off", "Verlies, Kathedrale, Arena -- z. B. aus,30m,aus"), limits)).append(',');
                b.append(textFeld("lager", Sprache.t("Fill food up to", "Nahrung auffuellen bis"),
                        Sprache.t("Food chests fill up to here, e.g. 1.2m", "Nahrungskisten fuellen bis hierhin auf, z. B. 1.2m"),
                        kurzeMenge(de.hustlebot.core.Bezahlung.lager()).replace("1200k", "1.2m")));
                felder = b.toString();
            }
            default -> { return null; }
        }
        return "{\"custom_id\":\"set:" + bereich + "\",\"title\":" + fjs(fschneiden(titel, 45)) + ",\"components\":[" + felder + "]}";
    }

    private void setEingereicht(String kanal, String bereich, java.util.Map<String, String> f) {
        String ziel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        StringBuilder hinweis = new StringBuilder();
        switch (bereich) {
            case "stigmas" -> {
                // Eine leere Mehrfachauswahl kommt von Discord gar nicht mit -- fehlt "level" oder "behalten", wurde dort
                // alles abgehakt (Hauptbot 11.10.).
                java.util.function.Function<String, java.util.Set<String>> liste = k -> {
                    java.util.Set<String> m = new java.util.LinkedHashSet<>();
                    for (String t : f.getOrDefault(k, "").split(",")) if (!t.isBlank()) m.add(t.trim());
                    return m;
                };
                de.hustlebot.core.Stigmaplan.setzen(f.containsKey("sorten") ? liste.apply("sorten") : null,
                        liste.apply("level"), f.get("haupt"), f.get("zweit"), liste.apply("behalten"));
                if (de.hustlebot.core.Stigmaplan.behalten().isEmpty()) {
                    hinweis.append(Sprache.t("⚠️ No set is kept: `!stigmas delete` then disassembles ALL stigmas with level 1-79.\n\n",
                            "⚠️ Kein Set wird behalten: `!stigmas loeschen` zerlegt dann ALLE Stigmas mit Level 1-79.\n\n"));
                }
            }
            case "stigmarang" -> {
                String fehler = de.hustlebot.core.Stigmaplan.ranglisteSetzen(f.getOrDefault("rangliste", ""));
                if (fehler != null) {
                    client.sendCardTo(ziel, Sprache.t("❌ Ranking not saved", "❌ Rangliste nicht gespeichert"), fehler
                            + Sprache.t("\nHow it works: one tier per line, e.g. `4x armor + 1x health`, `4x magic damage without damage`, "
                                        + "`3x health + 2 different`, several options with `or`. The old list stays.",
                                        "\nSo geht es: eine Stufe je Zeile, z. B. `4x Rüstung + 1x Leben`, `4x mag. Schaden ohne Schaden`, "
                                        + "`3x Leben + 2 verschiedene`, mehrere Moeglichkeiten mit `oder`. Die alte Liste gilt weiter."),
                            DiscordClient.FARBE_WARNUNG);
                    return;
                }
            }
            case "krieg" -> {
                if (f.containsKey("krieg")) kriegsplan.setSelbsttaetig("an".equals(f.get("krieg")));
                if (f.containsKey("nahrung")) de.hustlebot.bot.Nahrungskisten.setAn("an".equals(f.get("nahrung")));
                if (f.containsKey("laden")) de.hustlebot.core.Ladenplan.setAn("an".equals(f.get("laden")));
            }
            case "aufstellung" -> {
                for (String m : de.hustlebot.bot.Aufstellung.modi()) {
                    String w = f.get("auf_" + m);
                    if (w != null) de.hustlebot.bot.Aufstellung.setzen(m, "previous".equals(w)
                            ? de.hustlebot.bot.Aufstellung.Wahl.PREVIOUS : de.hustlebot.bot.Aufstellung.Wahl.BEST);
                }
            }
            case "nebenbei" -> {
                if (f.containsKey("leuchtturm")) de.hustlebot.bot.LighthouseMode.LEUCHTTURM.plan().setAn("an".equals(f.get("leuchtturm")));
                if (f.containsKey("kolosseum")) de.hustlebot.bot.LighthouseMode.KOLOSSEUM.plan().setAn("an".equals(f.get("kolosseum")));
                if (f.containsKey("turm")) de.hustlebot.core.Turmplan.setAn("an".equals(f.get("turm")));
                String g = f.getOrDefault("grenze", "").trim().toLowerCase(java.util.Locale.ROOT).replace(',', '.');
                if (!g.isEmpty()) {
                    try {
                        double faktor = g.endsWith("m") ? 1_000_000 : g.endsWith("k") ? 1_000 : 1;
                        de.hustlebot.bot.LighthouseMode.setKolosseumGrenze(
                                Math.round(Double.parseDouble(g.replaceAll("[^0-9.]", "")) * faktor));
                    } catch (NumberFormatException e) {
                        hinweis.append(Sprache.t("Colosseum limit not understood -- unchanged.\n",
                                "Kolosseum-Grenze nicht verstanden -- bleibt, wie sie war.\n"));
                    }
                }
                String[] tm = f.getOrDefault("turmmax", "").split("[,; ]+");
                if (tm.length == 3) {
                    String[] tuerme = {"easy", "hard", "veryhard"};
                    for (int i = 0; i < 3; i++) {
                        try {
                            de.hustlebot.core.Turmplan.setMaximum(tuerme[i], Integer.parseInt(tm[i].replaceAll("[^0-9]", "")));
                        } catch (NumberFormatException e) {
                            hinweis.append(Sprache.t("Tower level not understood: ", "Turm-Level nicht verstanden: ")).append(tm[i]).append('\n');
                        }
                    }
                } else if (!f.getOrDefault("turmmax", "").isBlank()) {
                    hinweis.append(Sprache.t("Tower levels as three numbers, e.g. 30,30,25 -- unchanged.\n",
                            "Turm-Level bitte als drei Zahlen, z. B. 30,30,25 -- bleibt, wie es war.\n"));
                }
            }
            case "automatik" -> {
                if (f.containsKey("an")) de.hustlebot.core.Eventautomatik.setAn("an".equals(f.get("an")));
                var pass = de.hustlebot.core.Eventautomatik.passAusWort(f.get("pass"));
                if (pass != null && pass != de.hustlebot.core.Eventautomatik.pass()) de.hustlebot.core.Eventautomatik.setPass(pass);
                String lu = f.getOrDefault("lupen", "").replaceAll("[^0-9]", "");
                if (!lu.isEmpty() && lu.length() <= 2) de.hustlebot.core.Eventautomatik.setMaxLupen(Integer.parseInt(lu));
                String r = f.getOrDefault("runs", "").replaceAll("[^0-9]", "");
                if (!r.isEmpty() && r.length() <= 3) de.hustlebot.core.Eventautomatik.setVerliesRuns(Integer.parseInt(r));
                else if (!f.getOrDefault("runs", "").isBlank()) {
                    hinweis.append(Sprache.t("Dungeon runs as a number from 1 to 500 -- unchanged.\n",
                            "Verlies-Runs bitte als Zahl von 1 bis 500 -- bleibt, wie es war.\n"));
                }
            }
            case "bezahlen" -> {
                for (String m : de.hustlebot.core.Bezahlung.MODI) {
                    var a = de.hustlebot.core.Bezahlung.ausWort(f.get("bez_" + m));
                    if (a != null && a != de.hustlebot.core.Bezahlung.standard(m)) de.hustlebot.core.Bezahlung.setStandard(m, a);
                }
                String roh = f.getOrDefault("limit", "").trim();
                if (!roh.isEmpty()) {
                    String[] teile = roh.split("[,; /]+");
                    if (teile.length == de.hustlebot.core.Bezahlung.MODI.size()) {
                        for (int i = 0; i < teile.length; i++) {
                            String m = de.hustlebot.core.Bezahlung.MODI.get(i);
                            long l = menge(teile[i]);
                            if (l < -1) {
                                hinweis.append(Sprache.t("Limit not understood: ", "Limit nicht verstanden: ")).append(teile[i]).append('\n');
                            } else if (l != de.hustlebot.core.Bezahlung.limit(m)
                                    && !(l == -1 && de.hustlebot.core.Bezahlung.limit(m) <= 0)) {
                                de.hustlebot.core.Bezahlung.setLimit(m, l);
                            }
                        }
                    } else {
                        hinweis.append(Sprache.t("Food limit as three values, e.g. off,500k,off -- unchanged.\n",
                                "Nahrungs-Limit bitte als drei Werte, z. B. aus,500k,aus -- bleibt, wie es war.\n"));
                    }
                }
                String lagerRoh = f.getOrDefault("lager", "").trim();
                if (!lagerRoh.isEmpty()) {
                    long l = menge(lagerRoh);
                    if (l >= 100_000L) {
                        if (l != de.hustlebot.core.Bezahlung.lager()) de.hustlebot.core.Bezahlung.setLager(l);
                    } else {
                        hinweis.append(Sprache.t("\"Fill food up to\" as an amount like 1.2m -- unchanged.\n",
                                "\"Nahrung auffuellen bis\" bitte als Menge wie 1.2m -- bleibt, wie es war.\n"));
                    }
                }
            }
            default -> { return; }
        }
        Log.info("Einstellungen", Sprache.t("Form \"" + bereich + "\" saved: " + f, "Formular \"" + bereich + "\" gespeichert: " + f));
        String stand = switch (bereich) {
            case "stigmas" -> de.hustlebot.core.Stigmaplan.beschreibung();
            case "stigmarang" -> stigmaRanglisteText();
            case "krieg" -> "**" + Sprache.t("Deploy clan war automatically: ", "Clan-Krieg selbst aufstellen: ")
                    + anAus(kriegsplan.istSelbsttaetig()) + "**\n"
                    + Sprache.t("Food chests before the war: ", "Nahrungskisten vor dem Krieg: ") + anAus(de.hustlebot.bot.Nahrungskisten.istAn()) + "\n"
                    + Sprache.t("Food chests in the clan store: ", "Food chests im Clan-Laden: ") + anAus(de.hustlebot.core.Ladenplan.istAn());
            case "aufstellung" -> de.hustlebot.bot.Aufstellung.modi().stream().map(this::aufstellZeilen)
                    .reduce("", (a, b) -> a + b);
            case "nebenbei" -> nebenbeiZeile();
            case "bezahlen" -> bezahlZeilen();
            default -> automatikZeile();
        };
        client.sendCardTo(ziel, Sprache.t("✅ Saved", "✅ Gespeichert"), hinweis + stand, DiscordClient.FARBE_ERFOLG);
    }

    /** "!start" ohne Angaben: Karte mit dem Knopf, der das Formular oeffnet. */
    private void startKarte(String kanal) {
        client.sendCardMitKnoepfen(kanal == null || kanal.isEmpty() ? channelId : kanal,
                Sprache.t("Start a mode", "Modus starten"),
                Sprache.t("Press **Open form**, pick mode, runs and options, press **Submit**.\n"
                          + "_Or directly: `/start` · classic: `!start cathedral 10 c` · `!start eventloop`_",
                          "Auf **Formular öffnen** drücken, Modus, Anzahl und Optionen wählen, **Absenden**.\n"
                          + "_Oder direkt: `/start` · wie bisher: `!start cathedral 10 c` · `!start eventablauf`_"),
                DiscordClient.FARBE_INFO,
                java.util.List.<String[]>of(new String[]{Sprache.t("📝 Open form", "📝 Formular öffnen"), "startformular"}));
    }

    private static String fjs(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String fschneiden(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String foption(String label, String wert, String beschreibung, boolean standard) {
        return "{\"label\":" + fjs(fschneiden(label, 100)) + ",\"value\":" + fjs(fschneiden(wert, 100))
                + (beschreibung == null ? "" : ",\"description\":" + fjs(fschneiden(beschreibung, 100)))
                + (standard ? ",\"default\":true" : "") + "}";
    }

    private static String ftextFeld(String id, String titel, String erklaerung, String wert) {
        return "{\"type\":18,\"label\":" + fjs(fschneiden(titel, 45)) + ",\"description\":" + fjs(fschneiden(erklaerung, 100))
                + ",\"component\":{\"type\":4,\"custom_id\":\"" + id + "\",\"style\":1,\"required\":false,"
                + "\"min_length\":0,\"max_length\":20,\"value\":" + fjs(wert) + "}}";
    }

    private String startFormular(String knopf) {
        if ("startformular:event".equals(knopf)) return eventFormular();
        java.util.List<String> modi = new java.util.ArrayList<>();
        {
            modi.add(foption(Sprache.t("Event loop", "Event-Ablauf (Kreislauf)"), "eventloop",
                    Sprache.t("Cathedral > chests > hammer event > dungeon, again and again",
                              "Kathedrale > Kisten > Hammer-Event > Verlies, immer wieder"), false));
            modi.add(foption(Sprache.t("Cathedral", "Kathedrale"), "cathedral",
                    Sprache.t("Cathedral only, no event", "Nur Kathedrale, ohne Event"), false));
        }
        modi.add(foption(Sprache.t("Dungeon", "Verlies"), "dungeon",
                Sprache.t("With a teammate -- random or friends", "Mit Mitspieler -- zufällig oder Freunde"), false));
        // Arena (Hauptbot, Freunde 3.0): je Turnier ein Ticket, mit "Bezahlen: nur Nahrung" mit Aepfeln.
        modi.add(foption(Sprache.t("Arena (tournament)", "Arena (Turnier)"), "arena",
                Sprache.t("One ticket per tournament -- \"Pay: food only\" = apples", "Je Turnier ein Ticket -- \"Bezahlen: nur Nahrung\" = Äpfel"), false));
        modi.add(foption(Sprache.t("Hammer event (event only)", "Hammer-Event (nur Event)"), "event",
                Sprache.t("Plays until the hammers are gone", "Spielt, bis die Hämmer alle sind"), false));
        // Titan-Grabung (Hauptbot 09.10., Freunde 10.10.)
        modi.add(foption(Sprache.t("Titan excavation (pickaxe event)", "Titan-Grabung (Spitzhacken-Event)"), "grabung",
                Sprache.t("Boss x10, all pickaxes, super pickaxes + dynamite", "Boss x10, alle Spitzhacken, Super-Spitzhacken + Dynamit"), false));
        modi.add(foption(Sprache.t("Titan tower (once)", "Titanen-Turm (einmal)"), "titantower", null, false));
        modi.add(foption(Sprache.t("Clan war deploy (once)", "Clan-Krieg aufstellen (einmal)"), "clanwar", null, false));
        modi.add(foption(Sprache.t("Lighthouse (once)", "Leuchtturm (einmal)"), "lighthouse", null, false));
        modi.add(foption(Sprache.t("Colosseum (once)", "Kolosseum (einmal)"), "colosseum", null, false));
        modi.add(foption(Sprache.t("Clan store (once)", "Clan-Laden (einmal)"), "clanstore", null, false));
        String optionen = String.join(",",
                foption(Sprache.t("Cathedral: shortest way (default)", "Kathedrale: kürzester Weg (Standard)"), "weg:a", null, false),
                foption(Sprache.t("Cathedral: turbo", "Kathedrale: Turbo"), "weg:c",
                        Sprache.t("As fast as possible", "So schnell wie möglich"), false),
                foption(Sprache.t("Cathedral: every waypoint", "Kathedrale: alle Wegpunkte"), "weg:b", null, false),
                foption(Sprache.t("Dungeon: random players", "Verlies: mit zufälligen Spielern"), "mit:zufall", null, false),
                foption(Sprache.t("Dungeon: with friends", "Verlies: mit Freunden"), "mit:freunde",
                        Sprache.t("Enter the names in the field below", "Namen im Feld darunter eintragen"), false),
                foption(Sprache.t("Pay: keys / torches only", "Bezahlen: nur Schlüssel / Fackeln"), "bez:tickets", null, false),
                foption(Sprache.t("Pay: food only", "Bezahlen: nur Nahrung"), "bez:food", null, false),
                foption(Sprache.t("Pay: keys first, then food", "Bezahlen: erst Schlüssel, dann Nahrung"), "bez:tickets+food", null, false),
                foption(Sprache.t("Pay: keys > food > food chests", "Bezahlen: Schlüssel > Nahrung > Nahrungskisten"), "bez:alles",
                        Sprache.t("Food chests only from the bag", "Nahrungskisten nur aus der Tasche"), false),
                // Mia 21.10.: Nahrung und Kisten, aber nie Tickets
                foption(Sprache.t("Pay: food > food chests (no keys)", "Bezahlen: Nahrung > Nahrungskisten (ohne Schlüssel)"), "bez:food+chests",
                        Sprache.t("Food chests only from the bag", "Nahrungskisten nur aus der Tasche"), false));
        return "{\"custom_id\":\"start:form\",\"title\":" + fjs(Sprache.t("Start a mode", "Modus starten"))
                + ",\"components\":["
                + "{\"type\":18,\"label\":" + fjs(Sprache.t("What should the bot play?", "Was soll der Bot spielen?"))
                + ",\"component\":{\"type\":3,\"custom_id\":\"modus\",\"required\":true,\"options\":[" + String.join(",", modi) + "]}},"
                + "{\"type\":18,\"label\":" + fjs(Sprache.t("Number of runs", "Anzahl Runs"))
                + ",\"description\":" + fjs(Sprache.t("A number or \"unlimited\" -- event loop: leave empty = unlimited",
                                                      "Zahl oder \"unendlich\" -- beim Event-Ablauf leer lassen = unendlich"))
                + ",\"component\":{\"type\":4,\"custom_id\":\"anzahl\",\"style\":1,\"required\":false,"
                + "\"min_length\":0,\"max_length\":10,\"placeholder\":" + fjs(Sprache.t("unlimited or 25", "unendlich oder 25")) + "}},"
                + "{\"type\":18,\"label\":" + fjs(Sprache.t("Options (several possible)", "Optionen (mehrere möglich)"))
                + ",\"description\":" + fjs(Sprache.t("Route, teammate, payment -- nothing chosen = default", "Weg, Mitspieler, Bezahlen -- nichts gewählt = Standard"))
                + ",\"component\":{\"type\":3,\"custom_id\":\"optionen\",\"required\":false,\"min_values\":0,"
                + "\"max_values\":9,\"options\":[" + optionen + "]}},"
                + "{\"type\":18,\"label\":" + fjs(Sprache.t("Friend (dungeon with friends only)", "Freund (nur Verlies mit Freunden)"))
                + ",\"description\":" + fjs(Sprache.t("Name of your friend, e.g. lisa", "Name deines Freundes, z. B. lisa"))
                + ",\"component\":{\"type\":4,\"custom_id\":\"freunde\",\"style\":1,\"required\":false,\"min_length\":0,"
                + "\"max_length\":100,\"placeholder\":" + fjs("lisa") + "}},"
                + "{\"type\":18,\"label\":" + fjs(Sprache.t("Shut down PC (optional)", "PC herunterfahren (freiwillig)"))
                + ",\"description\":" + fjs(Sprache.t("A time like 23:30 -- empty = PC stays on", "Uhrzeit wie 23:30 -- leer = PC bleibt an"))
                + ",\"component\":{\"type\":4,\"custom_id\":\"aus\",\"style\":1,\"required\":false,\"min_length\":0,"
                + "\"max_length\":10,\"placeholder\":" + fjs("23:30") + "}}"
                + "]}";
    }

    /** Schritt 2 fuer den Event-Ablauf: Verlies-Runs, Lupen, Pass, Discord-Takt -- mit dem jetzigen Stand. */
    private String eventFormular() {
        var pass = de.hustlebot.core.Eventautomatik.pass();
        int takt = de.hustlebot.core.Meldetakt.takt();
        String passFeld = "{\"type\":18,\"label\":" + fjs(Sprache.t("Your event pass", "Dein Event-Pass"))
                + ",\"description\":" + fjs(Sprache.t("Decides whether the bot may use magnifiers and blue hammers",
                                                      "Bestimmt, ob der Bot Lupen und blaue Hämmer benutzen darf"))
                + ",\"component\":{\"type\":3,\"custom_id\":\"pass\",\"required\":true,\"options\":["
                + foption(Sprache.t("No pass (hammers only)", "Kein Pass (nur Hämmer)"), "kein", null, pass == de.hustlebot.core.Eventautomatik.Pass.KEIN) + ","
                + foption(Sprache.t("Silver pass (hammers + magnifiers)", "Silber-Pass (Hämmer + Lupen)"), "silber", null, pass == de.hustlebot.core.Eventautomatik.Pass.SILBER) + ","
                + foption(Sprache.t("Gold pass (+ blue hammers)", "Gold-Pass (+ blaue Hämmer)"), "gold", null, pass == de.hustlebot.core.Eventautomatik.Pass.GOLD) + "]}}";
        String taktFeld = "{\"type\":18,\"label\":" + fjs(Sprache.t("Discord reports", "Discord-Meldung"))
                + ",\"description\":" + fjs(Sprache.t("How often Discord reports a run or an event stage",
                                                      "Wie oft Discord einen Run bzw. eine Event-Stage meldet"))
                + ",\"component\":{\"type\":3,\"custom_id\":\"takt\",\"required\":true,\"options\":["
                + foption(Sprache.t("Every run / stage", "Jeden Run / jede Stage"), "1", null, takt == 1) + ","
                + foption(Sprache.t("Run 1, then every 5", "Run 1, dann alle 5"), "5", null, takt == 5) + ","
                + foption(Sprache.t("Run 1, then every 10", "Run 1, dann alle 10"), "10", null, takt == 10) + "]}}";
        long lim = de.hustlebot.core.Bezahlung.limit("cathedral");
        String felder = String.join(",",
                ftextFeld("nahrung", Sprache.t("Food limit per day (cathedral)", "Nahrungs-Limit je Tag (Kathedrale)"),
                        Sprache.t("e.g. 30m, 500k, unlimited -- only used when paying with food",
                                  "z. B. 30m, 500k, unendlich -- nur beim Bezahlen mit Nahrung"),
                        lim <= 0 ? Sprache.t("unlimited", "unendlich") : kurzeMenge(lim)),
                ftextFeld("runs", Sprache.t("Dungeon runs at the end", "Verlies-Runs am Ende"),
                        Sprache.t("When keys, chests and hammers are gone (1 to 500)",
                                  "Wenn Schlüssel, Kisten und Hämmer alle sind (1 bis 500)"),
                        String.valueOf(de.hustlebot.core.Eventautomatik.verliesRuns())),
                ftextFeld("lupen", Sprache.t("Magnifiers per field (max)", "Lupen je Feld (höchstens)"),
                        Sprache.t("Gold pass: 5 is good (3 come back per field). 0 = none",
                                  "Gold-Pass: 5 ist gut (3 kommen je Feld zurück). 0 = keine"),
                        String.valueOf(de.hustlebot.core.Eventautomatik.maxLupen())),
                passFeld, taktFeld);
        return "{\"custom_id\":\"start:event\",\"title\":" + fjs(Sprache.t("Set up the event loop", "Event-Ablauf einstellen"))
                + ",\"components\":[" + felder + "]}";
    }

    /** Stand des Event-Ablaufs in einer Zeile. */
    private static String eventStand() {
        var pass = de.hustlebot.core.Eventautomatik.pass();
        return Sprache.t("Dungeon runs at the end: **", "Verlies-Runs am Ende: **") + de.hustlebot.core.Eventautomatik.verliesRuns()
                + Sprache.t("** · magnifiers per field: **", "** · Lupen je Feld: **") + de.hustlebot.core.Eventautomatik.maxLupen()
                + "** · **" + Sprache.t(pass.englisch, pass.anzeige) + "** · Discord: **"
                + (de.hustlebot.core.Meldetakt.takt() == 1 ? Sprache.t("every run", "jeden Run")
                   : Sprache.t("run 1, then every ", "Run 1, dann alle ") + de.hustlebot.core.Meldetakt.takt()) + "**";
    }

    /** Schritt 1 fertig, Schritt 2 steht noch aus (hoechstens 30 Minuten). */
    private record WartenderStart(String befehl, String uhr, long seit, DiscordGateway.Absender absender) {
        boolean abgelaufen() { return System.currentTimeMillis() - seit > 30 * 60_000L; }
    }
    private volatile WartenderStart startWartet;

    /** Wer das Formular abgeschickt hat -- das Gateway legt Nutzer-ID und Server in die Felder (16.10.). */
    private static DiscordGateway.Absender absenderAus(java.util.Map<String, String> felder) {
        return new DiscordGateway.Absender("Formular", felder.getOrDefault("_nutzerId", ""), felder.getOrDefault("_guild", ""));
    }

    /** Das ausgefuellte Formular: in Text-Befehle uebersetzen, zeigen und ausfuehren. */
    private void startEingereicht(String kanal, java.util.Map<String, String> felder) {
        String ziel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        if ("start:event".equals(felder.get("_formular"))) {
            eventEingereicht(ziel, felder);
            return;
        }
        DiscordGateway.Absender absender = absenderAus(felder);
        String modus = felder.getOrDefault("modus", "");
        boolean kreislauf = modus.equals("eventloop");
        boolean bisLeer = kreislauf || modus.equals("event") || modus.equals("grabung");
        boolean einmal = modus.matches("titantower|clanwar|lighthouse|colosseum|clanstore");
        String roh = felder.getOrDefault("anzahl", "").trim().toLowerCase(Locale.ROOT);
        boolean unendlich = roh.matches("u|0|unendlich.*|unbegrenzt|endlos|unlimited|infinite|inf|∞")
                || (roh.isEmpty() && bisLeer);
        String zahl = roh.replaceAll("[^0-9]", "");
        int n = unendlich ? (einmal ? 1 : 100_000)
                : roh.isEmpty() && einmal ? 1
                : zahl.isEmpty() || zahl.length() > 6 ? -1 : Integer.parseInt(zahl);
        if (modus.isBlank() || n < 1 || n > 100_000) {
            client.sendCardTo(ziel, Sprache.t("Not started", "Nicht gestartet"),
                    Sprache.t("Please choose what to play and enter how often (a number or \"unlimited\").",
                              "Bitte wählen, was gespielt wird, und eintragen, wie oft (Zahl oder \"unendlich\")."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        java.util.List<String> opt = java.util.Arrays.stream(felder.getOrDefault("optionen", "").split(","))
                .map(String::trim).filter(o -> !o.isEmpty()).toList();
        java.util.List<String> wege = opt.stream().filter(o -> o.startsWith("weg:")).toList();
        java.util.List<String> mit = opt.stream().filter(o -> o.startsWith("mit:")).toList();
        java.util.List<String> bez = opt.stream().filter(o -> o.startsWith("bez:")).toList();
        if (wege.size() > 1 || mit.size() > 1 || bez.size() > 1) {
            client.sendCardTo(ziel, Sprache.t("Not started", "Nicht gestartet"),
                    Sprache.t("Please pick only ONE route, ONE teammate choice and ONE way to pay.",
                              "Bitte je nur EINEN Weg, EINEN Mitspieler und EINE Bezahlart wählen."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        String freund = felder.getOrDefault("freunde", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_,]", "").replaceAll(",+", ",").replaceAll("^,|,$", "");
        StringBuilder b = new StringBuilder("!start ").append(modus);
        if (!(unendlich && bisLeer)) b.append(' ').append(n);
        if ((kreislauf || modus.equals("cathedral")) && !wege.isEmpty() && !wege.get(0).equals("weg:a")) {
            b.append(' ').append(wege.get(0).substring(4));
        }
        if (!bez.isEmpty()) b.append(' ').append(bez.get(0).substring(4));
        // "Kathedrale" im Formular heisst: ohne Event -- auch wenn die Automatik an ist.
        if (modus.equals("cathedral") && de.hustlebot.core.Eventautomatik.istAn()) b.append(" noevent");
        if (modus.equals("dungeon") && (mit.contains("mit:freunde") || !freund.isEmpty())) {
            if (freund.isEmpty()) {
                client.sendCardTo(ziel, Sprache.t("Not started", "Nicht gestartet"),
                        Sprache.t("\"With friends\" was chosen -- please enter the name (e.g. lisa).",
                                  "\"Mit Freunden\" gewählt -- bitte den Namen eintragen (z. B. lisa)."),
                        DiscordClient.FARBE_WARNUNG);
                return;
            }
            b.append(" friend ").append(freund);   // zuletzt: nimmt den Rest der Zeile
        }
        String aus = felder.getOrDefault("aus", "").trim();
        String uhr = aus.matches("[0-9]{1,2}[:.][0-9]{2}") ? aus.replace('.', ':') : "";
        if (!aus.isEmpty() && !aus.matches("-+") && uhr.isEmpty()) {
            client.sendCardTo(ziel, Sprache.t("Not started", "Nicht gestartet"),
                    Sprache.t("\"Shut down PC\": please enter a time like 23:30.",
                              "\"PC herunterfahren\": bitte eine Uhrzeit wie 23:30 eintragen."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        String befehl = b.toString();
        if (kreislauf) {
            startWartet = new WartenderStart(befehl, uhr, System.currentTimeMillis(), absender);
            client.sendCardMitKnoepfen(ziel, Sprache.t("🔨 Event loop – step 2 of 2", "🔨 Event-Ablauf – Schritt 2 von 2"),
                    Sprache.t("Step 1 arrived: `", "Schritt 1 ist angekommen: `") + befehl + "`"
                    + (uhr.isEmpty() ? "" : Sprache.t(" · PC off at ", " · PC aus um ") + uhr)
                    + Sprache.t("\n\n**The event loop is set up like this:**\n", "\n\n**So ist der Event-Ablauf eingestellt:**\n") + eventStand()
                    + Sprache.t("\n\n**⚙️ Event settings** = look, change and start\n**▶️ Start like this** = go right away",
                                "\n\n**⚙️ Event-Einstellungen** = ansehen, ändern und starten\n**▶️ So starten** = gleich los"),
                    DiscordClient.FARBE_INFO,
                    java.util.List.<String[]>of(new String[]{Sprache.t("⚙️ Event settings", "⚙️ Event-Einstellungen"), "startformular:event"},
                            new String[]{Sprache.t("▶️ Start like this", "▶️ So starten"), "!formularstart"}));
            return;
        }
        startAusfuehren(ziel, befehl, uhr, absender);
    }

    /** Knopf "So starten": den wartenden Event-Ablauf ohne Aenderung starten. */
    private void formularStart(String kanal) {
        String ziel = kanal == null || kanal.isEmpty() ? channelId : kanal;
        WartenderStart w = startWartet;
        startWartet = null;
        if (w == null || w.abgelaufen()) {
            client.sendCardTo(ziel, Sprache.t("Nothing to start", "Nichts zu starten"),
                    Sprache.t("Step 1 is missing or older than 30 minutes -- please `!start` again.",
                              "Schritt 1 fehlt oder ist älter als 30 Minuten -- bitte `!start` noch einmal."),
                    DiscordClient.FARBE_WARNUNG);
            return;
        }
        startAusfuehren(ziel, w.befehl(), w.uhr(), w.absender());
    }

    /** Schritt 2 abgeschickt: speichern (gilt auch spaeter, wie unter !settings), dann starten. */
    private void eventEingereicht(String ziel, java.util.Map<String, String> f) {
        StringBuilder hinweis = new StringBuilder();
        var pass = de.hustlebot.core.Eventautomatik.passAusWort(f.get("pass"));
        if (pass != null && pass != de.hustlebot.core.Eventautomatik.pass()) de.hustlebot.core.Eventautomatik.setPass(pass);
        String lu = f.getOrDefault("lupen", "").replaceAll("[^0-9]", "");
        if (!lu.isEmpty() && lu.length() <= 2) de.hustlebot.core.Eventautomatik.setMaxLupen(Integer.parseInt(lu));
        String r = f.getOrDefault("runs", "").replaceAll("[^0-9]", "");
        if (!r.isEmpty() && r.length() <= 3) de.hustlebot.core.Eventautomatik.setVerliesRuns(Integer.parseInt(r));
        else if (!f.getOrDefault("runs", "").isBlank()) {
            hinweis.append(Sprache.t("Dungeon runs: please a number from 1 to 500 -- unchanged.\n",
                                     "Verlies-Runs bitte als Zahl von 1 bis 500 -- bleibt, wie es war.\n"));
        }
        String nahrung = f.getOrDefault("nahrung", "").trim();
        if (!nahrung.isEmpty()) {
            long l = menge(nahrung);
            if (l >= -1) de.hustlebot.core.Bezahlung.setLimit("cathedral", l);
            else hinweis.append(Sprache.t("Food limit: please an amount like 30m -- unchanged.\n",
                                          "Nahrungs-Limit bitte als Menge wie 30m -- bleibt, wie es war.\n"));
        }
        String takt = f.getOrDefault("takt", "");
        if (takt.matches("1|5|10") && Integer.parseInt(takt) != de.hustlebot.core.Meldetakt.takt()) {
            de.hustlebot.core.Meldetakt.setTakt(Integer.parseInt(takt));
        }
        if (hinweis.length() > 0) client.sendCardTo(ziel, Sprache.t("Note", "Hinweis"), hinweis.toString(), DiscordClient.FARBE_WARNUNG);
        WartenderStart w = startWartet;
        startWartet = null;
        if (w == null || w.abgelaufen()) {
            client.sendCardTo(ziel, Sprache.t("💾 Saved", "💾 Gespeichert"),
                    eventStand() + Sprache.t("\n\nStep 1 is missing or older than 30 minutes -- to start: `!start eventloop`.",
                                             "\n\nSchritt 1 fehlt oder ist älter als 30 Minuten -- zum Starten `!start eventablauf`."),
                    DiscordClient.FARBE_INFO);
            return;
        }
        startAusfuehren(ziel, w.befehl(), w.uhr(), w.absender());
    }

    /**
     * Den fertigen Befehl zeigen (zum Selbertippen) und ausfuehren, dazu ggf. "!off HH:MM".
     * Das "!off" ist ein gefaehrlicher Befehl: es laeuft mit dem Absender des Formulars durch
     * die Rechtepruefung (16.10.) -- der Start selbst darf jeder.
     */
    private void startAusfuehren(String ziel, String befehl, String uhr, DiscordGateway.Absender absender) {
        boolean kreislauf = befehl.contains(" eventloop");
        client.sendCardTo(ziel, Sprache.t("▶️ Starting", "▶️ Wird gestartet"),
                Sprache.t("Next time just type:\n", "Beim nächsten Mal einfach tippen:\n") + "`" + befehl + "`"
                + (uhr.isEmpty() ? "" : "\n`!off " + uhr + "`")
                + (kreislauf ? Sprache.t("\n\n**Event loop:** ", "\n\n**Event-Ablauf:** ") + eventStand() : ""),
                DiscordClient.FARBE_START);
        befehl(ziel, befehl);
        if (!uhr.isEmpty()) befehl(ziel, "!off " + uhr, absender == null ? absenderAus(java.util.Map.of()) : absender);
    }

    /** "500k", "1.2m", "30mio", "200000", "aus"/"off"/"unendlich" -> Zahl; -1 = ohne Grenze; -2 = nicht verstanden. */
    private static long menge(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        if (t.isEmpty() || t.equals("aus") || t.equals("off") || t.equals("0") || t.equals("kein") || t.equals("none")) return -1;
        if (t.startsWith("unendlich") || t.startsWith("unbegrenzt") || t.equals("inf") || t.equals("\u221e")
                || t.equals("infinite") || t.equals("unlimited")) return -1;
        t = t.replace("millionen", "m").replace("million", "m").replace("mio", "m");
        try {
            double faktor = t.endsWith("m") ? 1_000_000 : t.endsWith("k") ? 1_000 : 1;
            String zahl = t.replaceAll("[^0-9.]", "");
            if (!t.endsWith("m") && !t.endsWith("k")) zahl = zahl.replace(".", "");
            long n = Math.round(Double.parseDouble(zahl) * faktor);
            return n <= 0 ? -1 : n;
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    private static String kurzeMenge(long n) {
        if (n <= 0) return Sprache.t("off", "aus");
        if (n % 1_000_000 == 0) return n / 1_000_000 + "m";
        if (n % 100_000 == 0 && n > 1_000_000) return (n / 100_000) / 10.0 + "m";
        if (n % 1_000 == 0) return n / 1_000 + "k";
        return String.valueOf(n);
    }

    /** Bezahlen fuer !settings: Bezahlart je Modus, Nahrungs-Limit je Tag, Lager-Ziel. */
    private static String bezahlZeilen() {
        StringBuilder b = new StringBuilder(Sprache.t("💰 **Payment**\n", "💰 **Bezahlen**\n"));
        for (String m : java.util.List.of("dungeon", "cathedral", "arena")) {
            var art = de.hustlebot.core.Bezahlung.standard(m);
            long l = de.hustlebot.core.Bezahlung.limit(m);
            b.append(m.equals("arena") ? "Arena: **"
                    : Sprache.t(m.equals("dungeon") ? "Dungeon: **" : "Cathedral: **", m.equals("dungeon") ? "Verlies: **" : "Kathedrale: **"))
             .append(Sprache.t(art.englisch, art.anzeige)).append("**")
             .append(Sprache.t(" · food limit per day: ", " · Nahrungs-Limit je Tag: ")).append(kurzeMenge(l))
             .append(Sprache.t(" (used today ", " (heute verbraucht ")).append(kurzeMenge(de.hustlebot.core.Bezahlung.verbraucht(m)).replace("off", "0").replace("aus", "0")).append(")\n");
        }
        b.append(Sprache.t("Food chests fill up to: **", "Nahrungskisten fuellen auf bis: **")).append(kurzeMenge(de.hustlebot.core.Bezahlung.lager())).append("**\n");
        b.append(Sprache.t("_`!settings pay cathedral all` (tickets / food / tickets+food / all / food+chests) · `!settings foodlimit cathedral 30m` · `!settings storage 1.2m`_",
                           "_`!settings bezahlen kathedrale alles` (tickets / food / tickets+food / alles / food+chests) · `!settings nahrungslimit kathedrale 30m` · `!settings lager 1.2m`_"));
        return b.toString();
    }

    private static int zahlAus(String text) {
        if (text == null) return -1;
        String ziffern = text.replaceAll("[^0-9]", "");
        if (ziffern.isEmpty()) return -1;
        try {
            return Integer.parseInt(ziffern);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Welcher Weg durch die Kathedrale? Ein einzelnes "b" heisst: alle
     * Wegpunkte mitnehmen, ein "c" (oder "turbo"/"fast"): so schnell wie
     * moeglich. Ohne Angabe: "a", der kuerzeste Weg zum Waechter.
     */
    private static String weg(String rest) {
        if (rest == null) return "a";
        for (String w : rest.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.equals("b")) return "b";
            if (w.equals("c") || w.equals("turbo") || w.equals("fast")) return "c";
        }
        return "a";
    }
}
