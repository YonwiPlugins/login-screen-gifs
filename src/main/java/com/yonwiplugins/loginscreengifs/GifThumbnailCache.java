package com.yonwiplugins.loginscreengifs;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GifThumbnailCache implements AutoCloseable
{
    static final int WIDTH = 72;
    static final int HEIGHT = 42;

    private static final Logger log = LoggerFactory.getLogger(GifThumbnailCache.class);
    private static final String CACHE_DIRECTORY = "login-screen-gifs";

    private final File directory;
    private final int width;
    private final int height;
    private final ThumbnailLoader loader;
    private final Map<Path, CacheEntry> memory = new HashMap<>();
    private final List<BufferedImage> retiredImages = new ArrayList<>();

    @Inject
    GifThumbnailCache()
    {
        this(
            new File(RuneLite.CACHE_DIR, CACHE_DIRECTORY),
            WIDTH,
            HEIGHT,
            GifThumbnailLoader::load);
    }

    GifThumbnailCache(File directory, int width, int height, ThumbnailLoader loader)
    {
        this.directory = directory;
        this.width = width;
        this.height = height;
        this.loader = loader;
    }

    synchronized BufferedImage get(File gif) throws IOException
    {
        Path source = gif.toPath().toRealPath();
        String fingerprint = fingerprint(source);
        CacheEntry cached = memory.get(source);
        if (cached != null && cached.fingerprint.equals(fingerprint))
        {
            return cached.image;
        }

        BufferedImage image = readDiskCache(fingerprint);
        if (image == null)
        {
            image = loader.load(source.toFile(), width, height);
            writeDiskCache(fingerprint, image);
        }

        if (cached != null)
        {
            // Swing may still be painting the previous ImageIcon. Keep its bitmap
            // alive until shutdown instead of making a visible row flash blank.
            retiredImages.add(cached.image);
        }
        memory.put(source, new CacheEntry(fingerprint, image));
        return image;
    }

    private BufferedImage readDiskCache(String fingerprint)
    {
        Path cachedFile = directory.toPath().resolve(fingerprint + ".png");
        if (!Files.isRegularFile(cachedFile))
        {
            return null;
        }

        try
        {
            BufferedImage image;
            synchronized (ImageIO.class)
            {
                image = ImageIO.read(cachedFile.toFile());
            }
            if (image != null && image.getWidth() == width && image.getHeight() == height)
            {
                return image;
            }
            if (image != null)
            {
                image.flush();
            }
        }
        catch (IOException | RuntimeException ex)
        {
            log.debug("Unable to read cached GIF thumbnail {}", cachedFile, ex);
        }
        return null;
    }

    private void writeDiskCache(String fingerprint, BufferedImage image)
    {
        Path temporary = null;
        try
        {
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), "thumbnail-", ".png");
            boolean written;
            synchronized (ImageIO.class)
            {
                written = ImageIO.write(image, "png", temporary.toFile());
            }
            if (!written)
            {
                throw new IOException("No PNG writer is available");
            }

            Path destination = directory.toPath().resolve(fingerprint + ".png");
            try
            {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ex)
            {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        }
        catch (IOException | RuntimeException ex)
        {
            log.debug("Unable to cache GIF thumbnail", ex);
        }
        finally
        {
            if (temporary != null)
            {
                try
                {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ex)
                {
                    log.debug("Unable to remove temporary GIF thumbnail {}", temporary, ex);
                }
            }
        }
    }

    private String fingerprint(Path source) throws IOException
    {
        String identity = source.toString()
            + '\n' + Files.size(source)
            + '\n' + Files.getLastModifiedTime(source).toMillis()
            + '\n' + width + 'x' + height;
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest)
            {
                hex.append(String.format("%02x", value & 0xFF));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Override
    public synchronized void close()
    {
        for (CacheEntry entry : memory.values())
        {
            entry.image.flush();
        }
        memory.clear();
        for (BufferedImage image : retiredImages)
        {
            image.flush();
        }
        retiredImages.clear();
    }

    @FunctionalInterface
    interface ThumbnailLoader
    {
        BufferedImage load(File file, int width, int height) throws IOException;
    }

    private static final class CacheEntry
    {
        private final String fingerprint;
        private final BufferedImage image;

        private CacheEntry(String fingerprint, BufferedImage image)
        {
            this.fingerprint = fingerprint;
            this.image = image;
        }
    }
}
