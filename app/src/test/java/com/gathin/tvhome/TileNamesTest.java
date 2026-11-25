package com.gathin.tvhome;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TileNamesTest {
    @Test public void aNameTypedOnTheTvBeatsTheConfigLabel() {
        Map<String, String> config = new HashMap<>();
        config.put("com.dycomment.tv", "Config name");
        assertEquals("Typed name",
                Config.tileNames(config, Collections.singletonMap("com.dycomment.tv", "Typed name"))
                        .get("com.dycomment.tv"));
    }

    @Test public void aPackageWithoutATypedNameKeepsTheConfigLabel() {
        Map<String, String> config = new HashMap<>();
        config.put("a.tv", "Config name");
        assertEquals("Config name",
                Config.tileNames(config, Collections.singletonMap("b.tv", "Other")).get("a.tv"));
    }

    @Test public void nothingTypedReturnsTheConfigMapItself() {
        Map<String, String> config = new HashMap<>();
        config.put("a.tv", "Config name");
        assertSame(config, Config.tileNames(config, Collections.emptyMap()));
    }

    @Test public void aTypedNameForAPackageTheConfigNeverMentionsIsAdded() {
        assertEquals("Fresh", Config.tileNames(Collections.emptyMap(),
                Collections.singletonMap("c.tv", "Fresh")).get("c.tv"));
    }

    @Test public void theConfigMapIsNotLeftMarkedUpByATypedName() {
        Map<String, String> config = new HashMap<>();
        config.put("a.tv", "Config name");
        Config.tileNames(config, Collections.singletonMap("a.tv", "Typed name"));
        assertTrue(config.get("a.tv").equals("Config name"));
    }
}
