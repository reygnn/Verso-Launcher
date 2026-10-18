# Verso: Crash-only Umbau-Reihenfolge

Oct 18, 2026 · @reygnn

Geordnete, je einzeln mergebare Branch-Serie, um Verso unter einem expliziten Crash-only-Regime schlanker und robuster zu machen. Grundlage: Quellanalyse von `reygnn/verso-launcher` (read-only).

## Kontext und Funde

Verso hat den Crash-only-Schlüsselstein bereits eingebaut (`Guard.wrap` um jeden IO-Task, plus das Beiseitelegen einer unlesbaren `verso.json` — der „Poison-Pill-Pfad“, im Code `setAsideUnreadable`), nutzt ihn aber nicht, um die übrigen Catches wegzulassen. Geprüft gegen Commit `273e31d` (0.2.12). Der Feature-Freeze betrifft nur neue nutzerseitige Features; Code-Vereinfachung ist Maintenance und erlaubt, darf aber keine Regression bringen.

Was nach drei Review-Runden übrig bleibt: **ein** echter Maintenance-Branch (2a), **ein** Doku-Branch (4), **zwei** zurückgestellte Ideen (1, 2b) und **ein** gestrichener (3).

Zentrale Funde:

- `configEpoch` ist auf App-Ebene toter Code, auf Klassenebene aber über `LauncherState.adopt` erreichbar und getestet — das ist der Kern von Branch 2a.
- `SourceGuardTest.kt` prüft die Regeln 7 (`parseForeign`) und 13 (`density`), **nicht** 1–3. IDEAS.md-Punkt D5 (Source-Guards für 1–3) bleibt offen.
- Auf dem Main-Thread gibt es fünf Catch-Stellen, jede verzweigt nach Exception-Typ mit eigener Meldung — ein einzelnes `onError` bildet das nicht ab (Branch 1, zurückgestellt).

## Leitplanken

Zwei Regeln über allem:

1. **Die durable Seite wird nicht angefasst.** `Guard.wrap`, `ConfigStore.write`, `fsyncDir`, `ConfigJson.parse/parseForeign` samt `StackOverflowError`-Fang, `setAsideUnreadable` — tabu, in jedem Branch. Sie schützen vor Korruption oder Absturzschleife.
2. **Ein Branch = eine Absicht.** Kein verhaltensneutraler Refactor gemischt mit einer Semantikänderung. Jeder Branch grün mergebar, Tests mitgewandert.

Reihenfolge: **2a** (die eigentliche Vereinfachung) zuerst, **4** (Doku) danach. **1** und **2b** sind zurückgestellt, **3** ist gestrichen.

## Branch 2a — `refactor/drop-config-epoch`

**Ziel:** Die Invariante „vor dem Laden ändert nichts die Config“ in `LauncherState` selbst verankern und das dann überflüssige `configEpoch` streichen. Verhaltensgleich für die App.

**Warum `configEpoch` heute lebt:** Der Vergleich (`:113`) greift nur beim Erstladen und könnte nur scheitern, wenn dazwischen `mutated()` oder `adopt()` liefe. In der App passiert das nie: alle sechs Bearbeitungen prüfen `!configLoaded`, `~restore`/`~restore-saf` brechen über `adoptRestored` mit „nicht geladen“ ab (`MainActivity:812`/`:844`), `~cleanup` lehnt ab (`LauncherState:218`), zwei Erstlade-Anfragen regelt `loadGeneration`. **Auf Klassenebene ist der Pfad aber lebendig:** `LauncherState.adopt` prüft selbst nichts, und drei Tests rufen `adopt()` mitten im Erstladen auf (`LauncherStateTest:262/808/866`) — dort greift `configEpoch` wirklich. Tot ist er nur, weil der einzige Produktionsaufrufer ihn nie so nutzt.

**Diff:**

- `adopt` bekommt `if (!configLoaded) return false` und liefert `Boolean` (wie `moveFavorite`/`toggleOrder`); so ist die Sperre eine Regel der Klasse, nicht nur von `MainActivity`.
- `adoptRestored` zeigt die „wiederhergestellt“-Meldung nur bei `true` (in der App ohnehin stets `true`, da `configLoaded` nie wieder `false` wird).
- `configEpoch` fällt weg: Feld, Capture (`:92`), Vergleich (`:113` → nur noch `diskConfig != null`), beide `++` (`adopt`, `mutated`).
- Die Doku zieht mit: Klassen-KDoc von `LauncherState` (`:12`, „zwei Zähler“ → einer), KDoc von `config` (`:44`), KDoc und Kommentar an `adopt` (`:278/282`), der Datenfluss in `CLAUDE.md` (`:118`, „… and the config epoch“) und `README.md` („What was chopped“: „two counters“ → einer).

**Tests:**

- Die drei Tests `:262/808/866` werden ersetzt durch „`adopt` vor dem Laden wird abgewiesen (`false`), das Erstladen landet trotzdem“ und „das Erstladen der neuesten Generation übernimmt die Datei unverändert“.
- Gegenprobe: ohne die Sperre in `adopt` wird der neue Test **rot** — anders als bei reiner Streichung ist die Absicherung damit echt prüfbar (im Sinne eures `test/CLAUDE.md`-Mutationschecks).
- `configLoaded is false until the disk copy or a restore is adopted` (`:253`) bleibt, wird aber umbenannt: ein Restore vor dem Laden wird jetzt abgewiesen, „or a restore“ stimmt nicht mehr.
- `app/src/test/CLAUDE.md`: Zahlen und Beschreibung von `LauncherStateTest` nachführen.
- `MainActivityFlowsTest` und `ConfigStoreTest` ändern sich nicht.

**Merge-Kriterium:** kein `configEpoch` mehr im Code; Verhalten unverändert; Gegenproben-Test grün (und ohne Sperre rot). Rund 6 Zeilen weg, \~2 dazu.

## Branch 4 — `docs/crash-only-model` (nur Doku)

**Ziel:** Das Modell festnageln, damit künftige Beiträge die Catches nicht wieder verstreuen.

- `CLAUDE.md`: die Crash-only-Leitzeile an die bestehende Hard Rule 2 („A HOME app must never throw“) anhängen, keine neue Regel — „Fang nur, wo es die Absturzschleife bricht (`Guard`, das Beiseitelegen einer unlesbaren `verso.json`) oder `verso.json` schützt (atomarer Write); flüchtige, rekonstruierbare Arbeit darf crashen.“ `Guard` = IO-Thread. Im Text die Begriffe der Regeln verwenden, nicht „Poison-Pill“.
- Die Main-Thread-Catch-Konvention als Regel festhalten (statt Branch 1 als Code), ohne die Stellen aufzuzählen (eine Liste veraltet beim nächsten Catch): „Main-Thread-Catches degradieren mit typ-spezifischer Meldung, nie still; kein Einheitsnetz.“
- Die Enumerations-Catches (`Apps.kt`) und Backup-Catches als Korrektheit/UX kennzeichnen, nicht als Crash-Panzerung.
- `IDEAS.md`: D5 (Source-Guards für 1–3) **bleibt offen** — `SourceGuardTest` deckt nur 7 und 13.
- Hard Rule 16 **nur, falls 2b kommt** (sie beschreibt den Prozess-Read); ohne 2b nicht eintragen.

## Branch 3 — gestrichen (war: `refactor/drop-home-cache`)

**Warum gestrichen:** Kein Entfernen von Redundanz, sondern eine Regression. `home-cache.txt` ist beim Kaltstart die einzige Namensquelle: `HomeCache.format` speichert `key<TAB>label`, aber `config.favorites` enthält nur Keys (`package/class`). Namen nicht-umbenannter Apps kommen erst aus der `LauncherApps`-Enumeration. Ohne Cache bliebe die Startseite beim Kaltstart namenlos, bis die Enumeration da ist. `HomeCache.kt`/`HomeCacheTest.kt` bleiben unverändert.

## Zurückgestellt

- **Branch 1 (`uiAction`-Netz):** Fünf Main-Thread-Catch-Stellen (`startGuarded`, App-Start mit innerem Quiet-Mode-`try`, `openAppInfo`, `~restore-saf`-Picker), jede verzweigt nach Exception-Typ mit eigener Meldung (`toast_denied`/`notFound`/`toast_not_default_home`/`toast_restore_no_picker`/`noApp()`). Ein `onError: () -> Unit` verliert diese Meldungen; `onError: (Exception) -> Unit` baut im Handler das `catch` nach. Kein Gewinn — was bleibt, ist die Konvention in `CLAUDE.md` (→ Branch 4).
- **Branch 2b (`refactor/config-per-process`):** Heute liest eine neu aufgebaute Activity die Config über den IO-Thread, also korrekt in der Warteschlange hinter jedem ausstehenden Speichern. Kosten: ein kurzes not-loaded-Fenster (Home aus Cache, Bearbeitung kurz abgewiesen). `cfg` von `LauncherState` nach `VersoApp` zu ziehen wäre ein großer Umbau ohne belegten Nutzen. Zurückgestellt im IDEAS.md-Stil — nur bei beobachtetem Problem.

## Was bewusst nicht angefasst wird

Die Enumerations-Lattice in `Apps.kt` bleibt: sie berechnet `complete`/`emptyProfiles` für `~cleanup` (Hard Rule 14) und die „letzte gute Liste“. Das ist Fachkorrektheit, nicht Crash-Panzerung — Branch 4 macht nur ihre Rolle explizit.

## Netto

Nach drei Review-Runden bleibt als echte Arbeit **Branch 2a** (`configEpoch` raus, Invariante in `adopt`, Gegenproben-Test) plus **Branch 4** (Doku). **Branch 1** und **2b** sind zurückgestellt, **Branch 3** gestrichen. Kein Branch berührt die durable Seite; der Gewinn ist bescheiden, aber sauber und prüfbar.
