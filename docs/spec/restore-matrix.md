# Verso: Restore-Matrix

Oct 26, 2026 · @reygnn

Die Tabelle unten **ist** der Testplan für `~backup`, `~restore`, `~restore-saf` und `~undo`.
Als Code steht sie in `RestoreMatrix.kt` (Testquellen); zwei Tests halten den Code an
dieselben Antworten:

- `RestoreMatrixTest` (JVM): jede Zelle des Kreuzprodukts, jede noch einmal mit dem Prozesstod vor
  jedem Schritt, und jede Folge von bis zu vier Befehlen gegen ein eigenes Modell.
- `RestoreMatrixFlowsTest` (Robolectric, die echte Activity und die echten `Commands`): die
  Zellen, die ein Nutzer erreichen kann.

Die Erwartungen kommen aus dieser Spec und den Hard Rules, nie aus `Commands`.

## §1 Achsen

| Achse | Werte |
|---|---|
| Befehl | `~backup`, `~restore`, `~undo`, `~restore-saf` |
| `verso.json` | GOOD (eine installierte App, eine deinstallierte), FRESH (keine Datei), UNREADABLE (zerrissen) |
| geladen | ja, nein |
| Download/Verso | EMPTY, LOOKALIKES (nur Fast-Treffer), BACKUPS, UNDO_POINTS, BOTH_UNDO_NEWEST, BOTH_BACKUP_NEWEST |
| Inhalt der genommenen Datei | DIFFERENT, SAME_AS_CURRENT, NOT_A_CONFIG (gültiges JSON, keine Config) |
| Fehler (einer zur Zeit) | NONE, LIST, READ, DOWNLOAD_WRITE, CONFIG_WRITE |

4 × 3 × 2 × 6 × 3 × 5 = **2 160 Zellen**.

Jede Zeile in Download/Verso trägt eine eigene Config. Wird etwas übernommen, zeigt sich also,
welche Zeile es war. Die Zeitstempel in den Namen laufen absichtlich gegen die `_ID`s, denn nach
Hard Rule 6 entscheidet die `_ID`. Die Fast-Treffer (`verso.tmp.json`, `verso-backup.json`,
`Verso-…json`, `verso-pre-restore.tmp.json`, `…json.txt`) haben die höchsten `_ID`s: Ein
gelockertes Namensmuster würde einen von ihnen nehmen.

## §2 Die Tabelle

Vor dem Laden: **jeder Befehl** → `toast_not_loaded`. Nichts wird geschrieben, im Speicher bleibt
die leere Config (Hard Rule 5).

Geladen, `~backup`: DOWNLOAD_WRITE → `toast_backup_failed`, sonst `toast_backup_ok` und eine neue
`verso-…`-Zeile mit der aktuellen Config. `verso.json` bleibt unverändert.

Geladen, `~restore` / `~undo` / `~restore-saf`, in dieser Reihenfolge geprüft:

| Bedingung | Ergebnis | neue Zeile | Speicher / `verso.json` |
|---|---|---|---|
| LIST (nicht bei `-saf`) | `toast_restore_list_failed` | – | unverändert |
| keine Zeile der Quelle (`~restore`: nur Backups, `~undo`: nur Rücksprungpunkte jeder Art) | `toast_restore_none` / `toast_undo_none` | – | unverändert |
| READ | `toast_restore_failed` | – | unverändert |
| NOT_A_CONFIG (`parseForeign`, Hard Rule 7) | `toast_restore_failed` | – | unverändert |
| SAME_AS_CURRENT | `toast_restore_same` / `toast_undo_same` | – | unverändert |
| DOWNLOAD_WRITE | `toast_restore_undo_failed` | – | unverändert |
| CONFIG_WRITE | `toast_restore_write_failed` | Rücksprungpunkt (bleibt) | unverändert |
| sonst | `toast_restore_ok` / `toast_undo_ok` | `verso-pre-restore` / `verso-pre-undo` (`-saf`: `pre-restore`) | die genommene Config |

Eine neue Zeile trägt immer die Config, die der Nutzer vorher hatte, bei `~backup` die aktuelle.

## §3 Invarianten (nach jeder Zelle, jedem Schritt, jedem Tod)

- **I1 (6):** Keine Zeile in Download/Verso verschwindet oder ändert sich; dazu kommt höchstens die
  eine aus §2.
- **I2 (5):** Vor dem Laden wird nichts geschrieben, auch nicht an den Fenster-Prüfungen vorbei:
  `adopt`, `save`, `loadedSnapshot` und `editPermit` des States selbst halten.
- **I3 (7):** `verso.json` ändert sich nie, ohne dass die ersetzte Config in einer Zeile liegt.
- **I4 (4):** Speicher und `verso.json` unterscheiden sich nur nach einem Speicherfehler, und dann
  wird er gemeldet. Eine unlesbare `verso.json` wird genau dann gemeldet, wenn sie geladen wurde.
- **I5 (Absturz):** Stirbt der Prozess vor irgendeinem Schritt, lädt der nächste Start die alte
  oder die neue Config, und die alte liegt dann in einer Zeile (Ausnahme: F1).
- **I6 (`~undo`):** Direkt nach einem `~restore` oder `~undo`, der etwas geändert hat,
  bringt `~undo` genau die Config davor zurück. `~undo ~undo` ist also die Identität.

## §4 Folgen

Alle Folgen der Länge 1–4 aus `~backup`, `~restore`, `~undo` und einer Bearbeitung
(Favorit umschalten), 340 Folgen. Nach jedem Schritt müssen Speicher, `verso.json` und alle Zeilen
(Art und Inhalt) dem Modell in `RestoreMatrixTest.Model` gleichen, das die Regeln von §2 einfach
nacherzählt. Dazu gilt I6.

## §5 Was welche Ebene deckt

- **JVM:** alle 2 160 Zellen, die Absturzpunkte, die Folgen. Verbunden werden die echten Teile
  (`LauncherState`, `ConfigStore` auf echten Dateien, `RestoreSteps`, `BackupFiles`, `ConfigJson`) durch eine Nacherzählung der Verdrahtung in `Commands`.
- **Robolectric:** 55 erreichbare Zellen durch die echte Activity. Sie prüfen genau die
  Verdrahtung, die die JVM-Ebene nacherzählt: welche Quelle, welcher Rücksprungpunkt, welche
  Meldung, die Ablehnung vor dem Laden. Die Download/Verso-Attrappe (`ListingDownloads`) listet nur
  Zeilen mit `_ID` und Namen und spielt Fehler ein. Die Regeln von MediaStore (Umbenennen, Besitzer)
  bleiben beim `BackupStoreInstrumentedTest` auf dem Gerät.
- **Nicht in der Matrix:** ein `verso.json`-Schreibfehler über die echte Activity, FRESH dort (das Setzen der Standard-Favoriten
  läuft durchs selbe Gate), eine liegengebliebene `.bak` (`ConfigStoreTest`), Gate und Pick
  (`MainActivityFlowsTest`, `CommandGateTest`, `PendingPickTest`).

## §6 Befunde

- **F1:** Bei einer frischen Installation hat der erste Schreibvorgang von `verso.json` keine
  `.bak`. Stirbt der Prozess mittendrin (z. B. bei einem `~restore`), lädt der nächste Start leer
  und meldet „unlesbar“. Verloren geht nichts: Der Nutzer hatte die leere Config, und die
  wiederhergestellte Datei liegt weiter in Download/Verso. SharedPreferences verhält sich genauso
  (Hard Rule 4). Kein Handlungsbedarf, solange man diese Bedingungen hinnimmt.

## §7 Mutationsprobe (2026-10-26)

Gegen JVM- und Robolectric-Ebene eingespielt: 29 Mutanten in `BackupFiles`, `RestoreSteps`,
`LauncherState`, `ConfigJson`, `ConfigStore`, `Commands`, `MainActivity` und `BackupStore`.
Gefangen wurden 27. Zwei überleben:

- Eine überschriebene `.bak`: Das deckt `ConfigStoreTest`, nicht die Matrix.
- Ein gestrichenes `pending = null` in `BackupStore.write`: gleichwertig zum Original, denn danach
  wirft im `try` nichts mehr.
