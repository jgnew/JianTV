package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** plate_cut: how much of an icon pixel clear of its plate survives the cut. */
public class PlateCutTest {

    @Test
    public void aPixelOnThePlateIsCutAway() {
        assertEquals(0f, AppAdapter.keepShare(0), 0.001f);
        assertEquals(0f, AppAdapter.keepShare(10), 0.001f);
        assertEquals(0f, AppAdapter.keepShare(24), 0.001f);
    }

    @Test
    public void aPixelClearOfThePlateIsKeptWhole() {
        assertEquals(1f, AppAdapter.keepShare(96), 0.001f);
        assertEquals(1f, AppAdapter.keepShare(255), 0.001f);
    }

    @Test
    public void theEdgeFadesInsteadOfStepping() {
        assertEquals(0.25f, AppAdapter.keepShare(42), 0.001f);
        assertEquals(0.5f, AppAdapter.keepShare(60), 0.001f);
        assertEquals(0.75f, AppAdapter.keepShare(78), 0.001f);
    }
}
