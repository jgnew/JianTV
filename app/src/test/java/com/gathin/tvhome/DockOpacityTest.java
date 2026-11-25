package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DockOpacityTest {
    @Test public void aChoiceMadeOnTheTvWinsOverTheTheme() {
        // The row reads as transparency: 30% picked means a band at 70% opacity over an 80% theme.
        assertEquals(70, Config.chosenDockOpacity(30, 80));
        assertEquals(0, Config.chosenDockOpacity(100, 80));
        assertEquals(100, Config.chosenDockOpacity(0, 80));
    }

    @Test public void noChoiceKeepsTheThemeValue() {
        // -1 is what Config passes when nothing is saved (or a theme push cleared it).
        assertEquals(80, Config.chosenDockOpacity(-1, 80));
        assertEquals(100, Config.chosenDockOpacity(-1, 100));
    }

    @Test public void anOutOfRangeChoiceIsClamped() {
        // There is no "off" stop any more: the ends are a solid band and a fully invisible one.
        assertEquals(0, Config.chosenDockOpacity(150, 80));
        // Any negative value means "never picked", so the theme's opacity stands.
        assertEquals(80, Config.chosenDockOpacity(-40, 80));
    }
}
