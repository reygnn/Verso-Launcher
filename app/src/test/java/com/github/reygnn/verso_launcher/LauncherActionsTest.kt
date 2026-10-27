package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fake is a bare-key [Ordered]: only [AppRow.entry] identity matters here. */
class LauncherActionsTest {

    private fun app(key: String): Row<OrderedFake> = AppRow(OrderedFake(key))
    private val tag: Row<OrderedFake> = TagRow("work")
    private val cmd: Row<OrderedFake> = CommandRow("~open", Command.OPEN)

    // ---- enterAction ---------------------------------------------------------

    /** Only a stale list can leave a command row nearest the prompt in a read mode. */
    @Test fun `a command row is no Enter target outside COMMAND mode`() {
        for (mode in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER)) {
            assertEquals(
                DrawerAction.None,
                LauncherLogic.enterAction(mode, command = null, promptBlank = false, lastRow = cmd),
            )
        }
    }

    /** "~o" both resolves to OPEN and is COMMAND mode: the command branch must come first. */
    @Test fun `a resolved command runs, even in COMMAND mode`() {
        assertEquals(
            DrawerAction.Run(Command.OPEN),
            LauncherLogic.enterAction(Mode.COMMAND, Command.OPEN, promptBlank = false, lastRow = cmd),
        )
    }

    @Test fun `a resolved command beats the empty-prompt branch`() {
        assertEquals(
            DrawerAction.Run(Command.OPEN),
            LauncherLogic.enterAction<OrderedFake>(Mode.COMMAND, Command.OPEN, promptBlank = true, lastRow = null),
        )
    }

    /** Clearing would throw away the typing; the overview already shows the candidates. */
    @Test fun `COMMAND mode with no resolved command is a no-op`() {
        assertEquals(
            DrawerAction.None,
            LauncherLogic.enterAction(Mode.COMMAND, command = null, promptBlank = false, lastRow = cmd),
        )
    }

    /** Must beat the launch branch, or an empty Enter would launch the last app in the alphabet. */
    @Test fun `an empty prompt only dismisses the keyboard, it never launches`() {
        assertEquals(
            DrawerAction.DismissKeyboard,
            LauncherLogic.enterAction(Mode.NORMAL, command = null, promptBlank = true, lastRow = app("com.z/Z")),
        )
        assertEquals(
            DrawerAction.DismissKeyboard,
            LauncherLogic.enterAction<OrderedFake>(Mode.NORMAL, command = null, promptBlank = true, lastRow = null),
        )
    }

    @Test fun `a read mode launches the app nearest the prompt`() {
        for (mode in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER)) {
            assertEquals(
                "launch failed for $mode",
                DrawerAction.Launch(OrderedFake("com.a/A")),
                LauncherLogic.enterAction(mode, command = null, promptBlank = false, lastRow = app("com.a/A")),
            )
        }
    }

    @Test fun `TAG_FILTER with a tag row drills into the single-hash filter`() {
        assertEquals(
            DrawerAction.SetPrompt("#work"),
            LauncherLogic.enterAction(Mode.TAG_FILTER, command = null, promptBlank = false, lastRow = tag),
        )
    }

    @Test fun `a read mode over an empty list does nothing`() {
        assertEquals(
            DrawerAction.None,
            LauncherLogic.enterAction<OrderedFake>(Mode.RECENTS, command = null, promptBlank = false, lastRow = null),
        )
    }

    // ---- enterEdge -----------------------------------------------------------

    private fun enterKey(down: Boolean, repeatCount: Int = 0, isEnterKey: Boolean = true) =
        EnterKey(down = down, repeatCount = repeatCount, isEnterKey = isEnterKey)

    @Test fun `a soft Enter acts`() {
        assertEquals(EnterEdge.ACT, LauncherLogic.enterEdge(imeAction = true, key = null))
    }

    @Test fun `another IME action without an event passes`() {
        assertEquals(EnterEdge.PASS, LauncherLogic.enterEdge(imeAction = false, key = null))
    }

    /** One UI (A17) sends GO on the down AND the up: acting on both launches twice; passing the
     *  up lets the TextView drop the keyboard. */
    @Test fun `a key Enter with the GO action acts on its down and consumes its up`() {
        assertEquals(EnterEdge.ACT, LauncherLogic.enterEdge(imeAction = true, key = enterKey(down = true)))
        assertEquals(EnterEdge.CONSUME, LauncherLogic.enterEdge(imeAction = true, key = enterKey(down = false)))
    }

    @Test fun `a key Enter without an IME action acts on its down and consumes its up`() {
        assertEquals(EnterEdge.ACT, LauncherLogic.enterEdge(imeAction = false, key = enterKey(down = true)))
        assertEquals(EnterEdge.CONSUME, LauncherLogic.enterEdge(imeAction = false, key = enterKey(down = false)))
    }

    /** Otherwise a held Enter launches the app over and over. */
    @Test fun `a held Enter acts once, its repeats are consumed`() {
        for (imeAction in listOf(true, false)) {
            assertEquals(EnterEdge.CONSUME, LauncherLogic.enterEdge(imeAction, enterKey(down = true, repeatCount = 1)))
            assertEquals(EnterEdge.CONSUME, LauncherLogic.enterEdge(imeAction, enterKey(down = true, repeatCount = 5)))
        }
    }

    /** No real input takes this path (TextView sends only ENTER/NUMPAD_ENTER); never pass it half-handled. */
    @Test fun `defensive - GO on another key acts on its down and consumes its up`() {
        assertEquals(EnterEdge.ACT, LauncherLogic.enterEdge(imeAction = true, key = enterKey(down = true, isEnterKey = false)))
        assertEquals(EnterEdge.CONSUME, LauncherLogic.enterEdge(imeAction = true, key = enterKey(down = false, isEnterKey = false)))
    }

    @Test fun `any other key passes`() {
        assertEquals(EnterEdge.PASS, LauncherLogic.enterEdge(imeAction = false, key = enterKey(down = true, isEnterKey = false)))
        assertEquals(EnterEdge.PASS, LauncherLogic.enterEdge(imeAction = false, key = enterKey(down = false, isEnterKey = false)))
    }

    // ---- tapAction -----------------------------------------------------------

    @Test fun `tapping nothing (a stale position) does nothing`() {
        assertEquals(DrawerAction.None, LauncherLogic.tapAction<OrderedFake>(Mode.NORMAL, null))
    }

    @Test fun `tapping a tag row drills into its filter`() {
        assertEquals(DrawerAction.SetPrompt("#work"), LauncherLogic.tapAction(Mode.TAG_FILTER, tag))
    }

    @Test fun `tapping a command row runs its command`() {
        assertEquals(DrawerAction.Run(Command.OPEN), LauncherLogic.tapAction(Mode.COMMAND, cmd))
    }

    @Test fun `tapping an app row launches under the read modes`() {
        for (mode in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER)) {
            assertEquals(
                "launch failed for $mode",
                DrawerAction.Launch(OrderedFake("com.a/A")),
                LauncherLogic.tapAction(mode, app("com.a/A")),
            )
        }
    }

    /** Never rendered (that overview holds only command rows), but the mapping stays total. */
    @Test fun `an app row in COMMAND mode resolves to nothing`() {
        assertEquals(DrawerAction.None, LauncherLogic.tapAction(Mode.COMMAND, app("com.a/A")))
    }

    // ---- sigil-named tags drill to a tag filter, not that sigil's mode --------

    /** canonicalTag keeps "!"/"-"/"~"/"?"; after the drill's "#" the sigil sits at position 1, where parseMode ignores it. */
    @Test fun `drilling a sigil-named tag builds a prompt that is still a tag filter`() {
        for (name in listOf("!work", "-work", "~work", "?work", "!!fun")) {
            assertEquals(DrawerAction.SetPrompt("#$name"), LauncherLogic.tapAction(Mode.TAG_FILTER, TagRow(name)))
            assertEquals(
                DrawerAction.SetPrompt("#$name"),
                LauncherLogic.enterAction(Mode.TAG_FILTER, command = null, promptBlank = false, lastRow = TagRow(name)),
            )
            assertEquals("\"#$name\" is no longer a tag filter", Mode.TAG_FILTER, LauncherLogic.parseMode("#$name"))
        }
    }

    @Test fun `Enter and a tap on the percent row toggle the order`() {
        val row = OrderRow(toUsage = true)
        assertEquals(DrawerAction.ToggleOrder, LauncherLogic.enterAction<OrderedFake>(Mode.ORDER, command = null, promptBlank = false, lastRow = row))
        assertEquals(DrawerAction.ToggleOrder, LauncherLogic.tapAction<OrderedFake>(Mode.ORDER, row))
        // "%abc" shows no row: Enter does nothing.
        assertEquals(DrawerAction.None, LauncherLogic.enterAction<OrderedFake>(Mode.ORDER, command = null, promptBlank = false, lastRow = null))
    }
}
