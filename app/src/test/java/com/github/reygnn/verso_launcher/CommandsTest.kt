package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The "~" commands: which one a prompt spells, lists or abbreviates. */
class CommandsTest {

    @get:Rule val locale = DefaultLocaleRule()

    // ---- parseCommand -------------------------------------------------------

    @Test fun `every command parses, a bare tilde and the dropped ones do not`() {
        assertNull(Commands.parseCommand("~"))
        assertNull(Commands.parseCommand("~load"))
        assertNull(Commands.parseCommand("~save"))
        assertEquals(Command.RESTART, Commands.parseCommand("~restart"))
        assertNull(Commands.parseCommand("~reset"))
        assertNull(Commands.parseCommand("~new"))
    }

    /**
     * The config is the user's file at a fixed place: backups are copies of it, there is nothing to
     * undo, link or edit here, and "~restart" reads it again.
     */
    @Test fun `~backup, ~restore, ~undo, ~restore-saf, ~cleanup, ~open and ~edit are gone`() {
        for (gone in listOf("~backup", "~restore", "~undo", "~restore-saf", "~cleanup", "~open", "~edit")) {
            assertNull(gone, Commands.parseCommand(gone))
            assertNull(gone, Commands.resolveCommand(gone))
        }
        assertNull(Commands.resolveCommand("~b"))
        assertNull(Commands.resolveCommand("~u"))
        assertNull(Commands.resolveCommand("~o"))
        assertNull(Commands.resolveCommand("~e"))
    }

    @Test fun `commands are case-insensitive`() {
        assertEquals(Command.RESTART, Commands.parseCommand("~RESTART"))
        assertEquals(Command.RESTART, Commands.parseCommand("~ReStArT"))
    }

    /** No spelling has an "i" today; one added with it ("~edit" had) must still parse under tr. */
    @Test fun `commands still parse under a Turkish default locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals(Command.RESTART, Commands.parseCommand("~RESTART"))
    }

    /** Exact matching is what spares the "~" sigil an escape. */
    @Test fun `a prefix or a partial is not a command`() {
        assertNull(Commands.parseCommand("~restarts"))
        assertNull(Commands.parseCommand("~ restart"))
        assertNull(Commands.parseCommand("~resta"))
        assertNull(Commands.parseCommand("~restart now"))
        assertNull(Commands.parseCommand("restart"))
        assertNull(Commands.parseCommand(""))
    }

    // ---- commandsMatching / resolveCommand -----------------------------------

    @Test fun `a bare tilde lists every command`() {
        assertEquals(Commands.ALL.size, Commands.commandsMatching("~").size)
    }

    @Test fun `typing narrows the overview`() {
        assertEquals(listOf("~restart"), Commands.commandsMatching("~r").map { it.first })
        assertEquals(listOf("~restart"), Commands.commandsMatching("~rest").map { it.first })
        assertEquals(emptyList<String>(), Commands.commandsMatching("~zzz").map { it.first })
    }

    /** One key after the tilde does. */
    @Test fun `~restart resolves from its first letter`() {
        assertEquals(Command.RESTART, Commands.resolveCommand("~r"))
    }

    @Test fun `~verify resolves from its first letter`() {
        assertEquals(Command.VERIFY, Commands.resolveCommand("~v"))
        assertEquals(listOf("~verify"), Commands.commandsMatching("~v").map { it.first })
    }

    /** The overview is bottom-anchored: the reload of the config file sits right above the prompt. */
    @Test fun `~restart is the command nearest the prompt`() {
        assertEquals("~restart", Commands.ALL.last().first)
    }

    /** "~crash": only in a daily-driver build (unit tests compile it in) and only spelled out. */
    @Test fun `~crash only in a daily-driver build, never abbreviated`() {
        val crash = Command.CRASH.takeIf { DailyDriver.ENABLED }
        assertEquals(crash, Commands.parseCommand("~crash"))
        assertEquals(crash, Commands.resolveCommand("~crash"))
        assertEquals(null, Commands.resolveCommand("~cr"))
        assertEquals(null, Commands.resolveCommand("~c"))
        assertEquals(DailyDriver.ENABLED, "~crash" in Commands.commandsMatching("~").map { it.first })
    }

    @Test fun `an exact spelling resolves`() {
        for ((spelling, command) in Commands.ALL.filter { it.second != Command.CRASH }) {
            assertEquals(spelling, command, Commands.resolveCommand(spelling))
        }
    }

    /** "~" is a prefix of every command: Enter on it alone must still run none. */
    @Test fun `a bare tilde resolves to nothing, also folded`() {
        assertNull(Commands.resolveCommand("~"))
        assertNull(Commands.resolveCommand("~\u0307"))
    }

    /** Otherwise a command added to COMMANDS without a line here would look covered. */
    @Test fun `parseCommand covers every entry in COMMANDS`() {
        for ((spelling, command) in Commands.ALL) {
            assertEquals(spelling, command, Commands.parseCommand(spelling))
        }
    }

    /** These fold apart from parseCommand; the Turkish case is the next test. */
    @Test fun `commandsMatching and resolveCommand are case-insensitive too`() {
        assertEquals(listOf("~restart"), Commands.commandsMatching("~R").map { it.first })
        assertEquals(Command.RESTART, Commands.resolveCommand("~R"))
        assertEquals(Command.RESTART, Commands.resolveCommand("~RE"))
        assertEquals(Command.RESTART, Commands.resolveCommand("~RESTART"))
    }

    /** A command with an "i" typed in caps on a Turkish phone must still resolve. */
    @Test fun `every command spelling survives a Turkish uppercase round trip`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        for ((spelling, _) in Commands.ALL) {
            assertEquals(
                "\"$spelling\" does not survive being typed in caps on a Turkish device",
                spelling,
                Labels.foldLabel(spelling.uppercase(Locale.getDefault())),
            )
        }
    }

    @Test fun `an abbreviation matching nothing resolves to nothing`() {
        assertNull(Commands.resolveCommand("~zzz"))
        assertNull(Commands.resolveCommand("~restartt"))
    }

    @Test fun `a command needs the tilde in the raw prompt, not just after folding`() {
        assertNull(Commands.parseCommand("\u0307~restart"))
        assertNull(Commands.resolveCommand("\u0307~restart"))
        assertEquals(Command.RESTART, Commands.parseCommand("~restart"))
    }

    @Test fun `an abbreviated command needs the tilde in the raw prompt too`() {
        assertNull(Commands.resolveCommand("\u0307~r"))
        assertEquals(Command.RESTART, Commands.resolveCommand("~r"))
    }
}
