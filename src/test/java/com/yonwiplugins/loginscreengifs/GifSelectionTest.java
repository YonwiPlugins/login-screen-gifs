package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class GifSelectionTest
{
    @Test
    public void findsArbitraryFilenamesWithoutCaseSensitivity()
    {
        assertEquals(1, GifSelection.findByName(
            Arrays.asList(new File("alpha.gif"), new File("My Excellent Loop.GIF")),
            "my excellent loop.gif"));
    }

    @Test
    public void sequentialSelectionWrapsBothDirections()
    {
        assertEquals(0, GifSelection.next(3, 2, 1));
        assertEquals(2, GifSelection.next(3, 0, -1));
    }

    @Test
    public void randomSelectionDoesNotRepeatWhenAnotherGifExists()
    {
        Random random = new Random(42L);
        for (int i = 0; i < 100; i++)
        {
            assertNotEquals(2, GifSelection.randomOther(5, 2, random));
        }
    }
}
