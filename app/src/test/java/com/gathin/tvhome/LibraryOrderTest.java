package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LibraryOrderTest {

    private static List<String> three() {
        return new ArrayList<>(Arrays.asList("com.b.example", "com.a.example", "org.z.example"));
    }

    @Test public void theOrderRoundTripsInTheOrderTheOwnerLeftIt() {
        List<String> keys = three();
        assertEquals(keys, LibraryOrder.decode(LibraryOrder.encode(keys)));
    }

    @Test public void anEmptyOrderIsStillAValidFile() {
        assertEquals(Collections.emptyList(), LibraryOrder.decode(LibraryOrder.encode(Collections.emptyList())));
    }

    @Test public void anythingThatIsNotAWholeOrderIsIgnoredRatherThanHalfApplied() {
        assertEquals(Collections.emptyList(), LibraryOrder.decode(null));
        assertEquals(Collections.emptyList(), LibraryOrder.decode(""));
        assertEquals(Collections.emptyList(), LibraryOrder.decode("com.a.example\n"));
        assertEquals(Collections.emptyList(),
                LibraryOrder.decode("tvhome-library 1\ncom.a.example\n\ncom.b.example\n"));
        assertEquals(Collections.emptyList(),
                LibraryOrder.decode("tvhome-library 1\ncom.a.example\ncom.a.example\n"));
        assertEquals(Collections.emptyList(),
                LibraryOrder.decode("tvhome-library 1\nnot a package\n"));
    }

    @Test public void invalidNamesAreDroppedFromWhatIsWrittenButTheGoodOnesStayInPlace() {
        List<String> keys = Arrays.asList("com.a.example", "", "com.b.example");
        assertEquals("tvhome-library 1\ncom.a.example\ncom.b.example\n", LibraryOrder.encode(keys));
    }

    @Test public void savedKeysComeFirstInSavedOrderAndTheRestFallThroughTogether() {
        List<String> keys = Arrays.asList("com.c.example", "com.a.example");
        Map<String, Integer> ranks = LibraryOrder.ranks(keys);
        assertEquals(2, ranks.size());   // the map holds the saved keys only
        assertTrue(LibraryOrder.compare(ranks, "com.c.example", "com.a.example") < 0);
        assertTrue(LibraryOrder.compare(ranks, "com.a.example", "com.c.example") > 0);
        assertTrue(LibraryOrder.compare(ranks, "com.c.example", "com.d.example") < 0);
        assertTrue(LibraryOrder.compare(ranks, "com.d.example", "com.c.example") > 0);
        assertEquals(0, LibraryOrder.compare(ranks, "com.d.example", "com.e.example"));
    }

    @Test public void anOrderThatIsTooLongIsCutToTheLimitInsteadOfFailing() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < LibraryOrder.MAX * 2; i++) keys.add("com.example.app" + i);
        List<String> saved = LibraryOrder.decode(LibraryOrder.encode(keys));
        assertEquals(LibraryOrder.MAX, saved.size());
        assertEquals("com.example.app0", saved.get(0));
    }
}
