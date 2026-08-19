package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class GifThumbnailCacheTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reusesStaticThumbnailInMemoryAndOnDisk() throws Exception
    {
        File gif = temporaryFolder.newFile("example.gif");
        Files.write(gif.toPath(), new byte[] {1, 2, 3});
        File cacheDirectory = temporaryFolder.newFolder("cache");
        AtomicInteger loads = new AtomicInteger();

        GifThumbnailCache firstCache = new GifThumbnailCache(cacheDirectory, 72, 42, (file, width, height) ->
        {
            loads.incrementAndGet();
            return image(width, height, Color.RED);
        });
        BufferedImage first = firstCache.get(gif);
        assertSame(first, firstCache.get(gif));
        assertEquals(1, loads.get());
        firstCache.close();

        GifThumbnailCache secondCache = new GifThumbnailCache(cacheDirectory, 72, 42, (file, width, height) ->
        {
            loads.incrementAndGet();
            return image(width, height, Color.BLUE);
        });
        BufferedImage fromDisk = secondCache.get(gif);
        try
        {
            assertEquals(Color.RED.getRGB(), fromDisk.getRGB(0, 0));
            assertEquals(1, loads.get());
        }
        finally
        {
            secondCache.close();
        }
    }

    @Test
    public void invalidatesThumbnailWhenGifChanges() throws Exception
    {
        File gif = temporaryFolder.newFile("changing.gif");
        Files.write(gif.toPath(), new byte[] {1});
        AtomicInteger loads = new AtomicInteger();
        GifThumbnailCache cache = new GifThumbnailCache(
            temporaryFolder.newFolder("changing-cache"),
            72,
            42,
            (file, width, height) -> image(
                width,
                height,
                loads.getAndIncrement() == 0 ? Color.RED : Color.BLUE));
        try
        {
            BufferedImage before = cache.get(gif);
            Files.write(gif.toPath(), new byte[] {2}, StandardOpenOption.APPEND);
            BufferedImage after = cache.get(gif);

            assertNotSame(before, after);
            assertEquals(Color.RED.getRGB(), before.getRGB(0, 0));
            assertEquals(Color.BLUE.getRGB(), after.getRGB(0, 0));
            assertEquals(2, loads.get());
        }
        finally
        {
            cache.close();
        }
    }

    private static BufferedImage image(int width, int height, Color color)
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }
}
