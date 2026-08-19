package com.yonwiplugins.loginscreengifs;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

final class GifThumbnailLoader
{
    private GifThumbnailLoader()
    {
    }

    static BufferedImage load(File gif, int width, int height) throws IOException
    {
        try (InputStream input = new BufferedInputStream(new FileInputStream(gif)))
        {
            return load(input, width, height);
        }
    }

    static BufferedImage load(InputStream input, int width, int height) throws IOException
    {
        BufferedImage[] thumbnail = new BufferedImage[1];
        StreamingGifReader reader = new StreamingGifReader();
        int frames = reader.read(input, (frame, ignoredDuration) ->
        {
            thumbnail[0] = GifDecoder.resize(frame, ScaleMode.CONTAIN, width, height);
            return false;
        });
        if (frames == 0 || thumbnail[0] == null)
        {
            throw new IOException("GIF contains no readable preview frame");
        }
        return thumbnail[0];
    }
}
