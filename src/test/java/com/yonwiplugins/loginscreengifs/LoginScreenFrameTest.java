package com.yonwiplugins.loginscreengifs;

import net.runelite.api.SpritePixels;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LoginScreenFrameTest
{
    @Test
    public void copiesAnimationFrameIntoInstalledSprite()
    {
        SpritePixels installed = mock(SpritePixels.class);
        SpritePixels next = mock(SpritePixels.class);
        int[] installedPixels = {1, 1, 1, 1};
        int[] nextPixels = {2, 3, 4, 5};
        when(installed.getPixels()).thenReturn(installedPixels);
        when(next.getPixels()).thenReturn(nextPixels);

        assertTrue(LoginScreenGifsPlugin.copyFramePixels(installed, next));
        assertArrayEquals(nextPixels, installedPixels);
    }

    @Test
    public void rejectsDifferentSizedSpriteBuffers()
    {
        SpritePixels installed = mock(SpritePixels.class);
        SpritePixels next = mock(SpritePixels.class);
        when(installed.getPixels()).thenReturn(new int[4]);
        when(next.getPixels()).thenReturn(new int[3]);

        assertFalse(LoginScreenGifsPlugin.copyFramePixels(installed, next));
    }
}
