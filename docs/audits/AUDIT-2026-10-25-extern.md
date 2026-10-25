# Audit 2026-10-25 (extern)

Externes Review eines Seniors über die drei Repos guru, verso-launcher und guru2acra, Stand
Verso `9c4cc57` (0.5.1). Bewertung des Reviewers: guru 7/10, verso-launcher 6/10, guru2acra 4,5/10.
Er hat den Code gelesen, aber nichts gebaut oder getestet (kein Android-SDK); Testzahlen aus den
Quellen.

Hier nur die Verso-Punkte, jeder gegen den Code nachgeprüft. Die Punkte zu Guru und guru2acra
gehören in deren Repos und sind unten nur aufgelistet, nicht geprüft.

**Ergebnis:** zwei echte Lücken bei den Daten (#1, #2), eine teilweise (#3), ein kleiner
Klick-Verlust (#5) und ein Barrierefreiheits-Hinweis (#6b). Mehreres ist Geschmack, schon
dokumentiert oder in Kotlin so nicht umsetzbar.

---

## Übergreifend (vom Reviewer)

- **Verhältnis Prosa zu Code:** 5 500 Zeilen Code, `CLAUDE.md` mit 400 Zeilen und 16 harten Regeln,
  obwohl die Datei „Keep it short“ verlangt; Kommentare erzählen Randfälle statt Absicht.
  **Berechtigt.** Kürzen wäre eine eigene Aufgabe.
- **„Fast alles direkt auf `main` (5 Merges)“:** **trifft so nicht zu.** Branches werden per
  Fast-Forward gemergt und hinterlassen keinen Merge-Commit (283 Commits, 5 Merge-Commits).
- **Bus-Faktor 1:** stimmt; ein Ein-Personen-Projekt.

## A. Daten

### 1. `~backup` nach einem Neustart vernichtet das letzte gute Backup — überholt durch `27df7ad`
- **Stand:** Backups nach MediaStore-Regeln, Verso löscht keine mehr (harte Regel 6). Ein `~backup`
  nach einem leeren Start ist das neueste und wird von `~restore` genommen; die älteren bleiben
  und sind mit `~restore-saf` erreichbar (`CAVEATS.md`).
- **Stelle:** `BackupStore` (genau ein aktuelles Backup, harte Regel 6), Toast
  `toast_config_reset` („config unreadable — started over. ~restore brings a backup back“).
- **Nachgeprüft:** ja. Ist `verso.json` unlesbar, startet Verso mit Standard-Favoriten neu. Tippt
  der Nutzer dann `~backup` statt `~restore`, ersetzt die Standard-Konfiguration das einzige
  Backup. Das Netz aus harter Regel 4 ist weg.
- **Vorschlag:** `~backup` abweisen, solange die Konfiguration aus dem Neustart stammt und nichts
  wiederhergestellt wurde (das Prozess-Kennzeichen `startedOver` gibt es schon). Alternative des
  Reviewers: die letzten N Backups behalten (ändert harte Regel 6).

### 2. Ein vorübergehender Lesefehler legt eine intakte Konfiguration beiseite — überholt durch `5dfa971`
- **Stand:** `verso.json` nach SharedPreferences-Regeln (harte Regel 4): kein Beiseitelegen, kein
  Export mehr. Ein Lesefehler lädt leer, die nächste Änderung schreibt darüber — wie bei
  SharedPreferences, bewusst übernommen; das Netz sind die Backups (`CAVEATS.md`).
- **Stelle:** `ConfigStore.load` / `setAsideUnreadable`, `LauncherState.kt:115-117`.
- **Nachgeprüft:** ja. `load()` gibt für „nicht lesbar“ (IO-Fehler) und „nicht parsebar“ gleich
  `null`. `setAsideUnreadable` liest erneut, parst aber nicht. Gelingt der zweite Lesezugriff,
  wird eine intakte Datei exportiert und gelöscht. Verloren ist sie nicht (in Download/Verso als
  `verso-unreadable-…`, über `~restore-saf` erreichbar), aber Verso startet unnötig leer.
- **Vorschlag:** den zweiten Lesezugriff parsen; ist die Datei in Ordnung, laden statt
  beiseitelegen.

### 3. `ConfigJson.parse` fängt jede `Exception` — überholt durch `5dfa971`
- **Stand:** nach den neuen Regeln lädt jeder Parse-Fehler leer, wie SharedPreferences (das selbst
  breit fängt). Ein deterministischer Bug verschöbe keine Dateien mehr; er würde die Datei bei der
  nächsten Änderung überschreiben, das Netz sind die Backups.
- **Stelle:** `ConfigJson.kt:26-34`.
- **Nachgeprüft:** teilweise. Wirft eine eigene Hilfsfunktion beim Parsen deterministisch, gilt
  jede gültige Konfiguration als kaputt und wird bei jedem Start beiseitegelegt (exportiert, also
  nicht verloren, aber jeder Start ist ein Neustart).
- **Vorschlag:** nur `JSONException` (und weiter `StackOverflowError`) fangen. Ein Bug ließe die
  Konfiguration dann „nicht geladen“, bei jedem Resume neu versucht (harte Regel 5), statt Dateien
  zu verschieben. Vorher prüfen, was Androids `org.json` außer `JSONException` werfen kann.

## B. Bedienung

### 4. `~restart` kann nicht reparieren — verworfen
- **Stelle:** `MainActivity.kt` `runCommand`: `gate.busy` vor dem RESTART-Zweig.
- **Nachgeprüft:** so gewollt (harte Regel 15: `~restart` setzt nur zurück, wenn kein Lauf geht).
  Den Vorschlag „`~restart` vor die `busy`-Prüfung“ würde nichts reparieren: Hängt ein
  Systemaufruf auf dem IO-Thread, reiht sich das neue Laden nach `Core.reset` hinter ihm ein.
  Schon in `CAVEATS.md` („A stuck system call holds the `~` commands“, Abhilfe Force Stop).

### 5. Der Schutz gegen veraltete Klicks verschluckt einen Klick — ✅ `75a01b5`
- **Stelle:** `AppDrawer.kt:105-119` (Touch-Listener), `clickStillValid`.
- **Nachgeprüft:** ja, kleiner als beschrieben. Ein Wisch endet mit `ACTION_UP` und setzt
  `clickDownGeneration`, obwohl kein Klick folgt. Ändert sich danach die Liste (Tippen in der
  Kommandozeile), wird der nächste Klick ohne Berührung (Tastatur, Switch Access, Voice Access)
  einmal verworfen; `clickStillValid` setzt die Marke zurück, der zweite Versuch geht durch.
- **Vorschlag:** die Marke löschen, sobald aus der Berührung ein Wisch wird
  (`OnScrollListener`, `SCROLL_STATE_TOUCH_SCROLL`). Test in `AppDrawerTest`.

### 6a. Startseite in dp, ignoriert die Schriftgröße — verworfen (bewusst)
- In `README.md` und `CAVEATS.md` dokumentiert; die Favoriten werden auf den Bildschirm
  eingepasst (`HomeStyle.favoriteTextSizePx`). Der Reviewer meint, nur die Uhr brauche die
  Ausnahme. Eine Änderung wäre eine Entscheidung des Users.

### 6b. Zustand und „Double-tap to…“ in `contentDescription` statt `stateDescription` — ⬜ offen
- **Stelle:** `AppDrawer.kt:479ff` (Bearbeitungsmodi), `HomePage.unavailableDescription`,
  `strings.xml` `a11y_*_on/off`, `a11y_app_paused/archived`.
- **Nachgeprüft:** ja. Der Weg der Plattform ist `stateDescription` plus eine beschriftete
  Klick-Aktion; TalkBack hängt seinen eigenen Hinweis an. Ändert, was TalkBack sagt: braucht
  einen Test auf dem A17 (Samsung-TalkBack spricht anders als Googles).

## C. Struktur und Tests

### 7. Struktur — teils verworfen
- `MainActivity` 745, `LauncherLogic` 572 Zeilen, `PageTurn` als implizite Zustandsmaschine:
  Geschmack.
- detekt-Baseline mit 34 Einträgen: die Altlast vom Einschalten, laut `CLAUDE.md` nur schrumpfend.
- **`SwipeFrame`: stumme No-op-Defaults der Lambdas** (`onSwipe`, `onDragStart`, `onDrag`,
  `onDragEnd`, …) — ✅ `9cf7a84` (`SwipeFrame.Host` ohne Defaults; dazu ein Test für den Flick).

### 8. Tests — teils verworfen
- `SourceGuardTest` pinnt wörtliche Schnipsel: schon Audit 2026-10-23 #20, verworfen.
- `EditPermit` über das Typsystem statt den Quelltext-Scan: in Kotlin so nicht möglich — im selben
  Modul lässt sich jeder Konstruktor aufrufen und jedes Interface implementieren. Entschieden in
  Audit 2026-10-22 #5.
- `LauncherState.adoptions` / `CleanupOutcome.Superseded` unerreichbar: **stimmt.** Seit das Gate
  prozessweit gilt, kann während `~cleanup` kein Restore übernommen werden. — ✅ entfernt
  (`refactor/drop-superseded`).

### Nicht geprüft
- Die Kontur auf getipptem Text in der Kommandozeile fehlt vielleicht (beide `onDraw`-Durchläufe
  zeichnen denselben gecachten Render-Node neu, aus dem Plattformcode abgeleitet). Prüfbar nur
  auf dem Gerät mit hellem Hintergrundbild.

---

## Guru und guru2acra (nicht geprüft, gehören in deren Repos)

**Guru:** `GuruScreen.kt:278` misst die letzte Zeile mit der alten Textgröße (lange Übersetzung
läuft beim ersten Layout über; `GuruScreenTest:104` prüft die Breite nicht); U+2028/U+2029 als
rohe Literale in `AcraJson.kt:56`; `EXIT_LOOKBACK = 16` zählt alle Prozessenden (nach vielen
Low-Memory-Kills fallen ANRs heraus); `showPending` liest `isFinishing` auf dem IO-Thread;
Grundentscheidung öffentliches `Download/Guru/`.

**guru2acra:** IO auf dem Main-Thread im Share-Pfad (bis 512 KiB je Datei, fsync, in `onCreate`);
exportiertes Share-Ziel nimmt Berichte jeder App an (Confused Deputy); Reporter-Zugangsdaten im
APK; keine Obergrenzen (Inbox, `EXTRA_TEXT`, quadratischer Scan in `ShareText.reports`); kein CI,
kein detekt, `mockk` unbenutzt, lokale Pfade in `CLAUDE.md`; `Wait` läuft über das Backoff statt
bis `until`.

---

## Empfehlung

1–3 überholt durch die Plattform-Regeln (`27df7ad`, `5dfa971`). Umsetzen: **5**, auf einem Branch, mit Test und Mutationsprobe. Danach **6b** (mit
TalkBack auf dem A17) und die `SwipeFrame`-Defaults. Offen zur Wahl: `Superseded` behalten oder
entfernen.
