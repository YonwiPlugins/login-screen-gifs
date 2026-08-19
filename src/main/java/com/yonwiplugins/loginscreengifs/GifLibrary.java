package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import net.runelite.client.RuneLite;

final class GifLibrary
{
    static final String DIRECTORY_NAME = "login-screen-gifs";
    private static final String GIF_EXTENSION = ".gif";

    private final File directory;

    @Inject
    GifLibrary()
    {
        this(new File(RuneLite.RUNELITE_DIR, DIRECTORY_NAME));
    }

    GifLibrary(File directory)
    {
        this.directory = directory;
    }

    File getDirectory()
    {
        return directory;
    }

    List<File> listGifs() throws IOException
    {
        ensureDirectory();
        File[] children = directory.listFiles();
        if (children == null)
        {
            throw new IOException("Unable to list GIF library: " + directory);
        }

        List<File> files = new ArrayList<>();
        for (File child : children)
        {
            if (isManagedGif(child))
            {
                files.add(child.getCanonicalFile());
            }
        }
        files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    ImageInputStream open(File gif) throws IOException
    {
        ensureDirectory();
        if (gif == null || !isManagedGif(gif))
        {
            throw new IOException("GIF is not a readable file in " + directory);
        }

        ImageInputStream input = ImageIO.createImageInputStream(gif);
        if (input == null)
        {
            throw new IOException("Unable to open " + gif.getName());
        }
        return input;
    }

    ImportSummary importEntries(List<File> entries) throws IOException
    {
        ensureDirectory();
        ImportSummary summary = new ImportSummary();
        if (entries == null || entries.isEmpty())
        {
            return summary;
        }

        Map<String, File> sources = new LinkedHashMap<>();
        for (File entry : entries)
        {
            collectSources(entry, sources, summary);
        }

        for (File source : sources.values())
        {
            try
            {
                validateGif(source);
                File canonicalSource = source.getCanonicalFile();
                if (directory.getCanonicalFile().equals(canonicalSource.getParentFile()))
                {
                    summary.imported.add(canonicalSource);
                    continue;
                }

                File destination = availableDestination(source.getName());
                Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                summary.imported.add(destination.getCanonicalFile());
            }
            catch (IOException ex)
            {
                summary.errors.add(source.getName() + ": " + ex.getMessage());
            }
        }
        return summary;
    }

    private void collectSources(File entry, Map<String, File> sources, ImportSummary summary)
    {
        if (entry == null)
        {
            summary.errors.add("One selected item could not be read");
            return;
        }

        Path path = entry.toPath();
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        {
            int before = sources.size();
            try (Stream<Path> stream = Files.walk(path))
            {
                stream.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> hasGifExtension(candidate.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                    .forEach(candidate -> addCanonical(candidate.toFile(), sources, summary));
            }
            catch (IOException | SecurityException ex)
            {
                summary.errors.add(entry.getName() + ": " + ex.getMessage());
            }
            if (sources.size() == before)
            {
                summary.emptyFolders++;
            }
            return;
        }

        if (!hasGifExtension(entry.getName()))
        {
            summary.errors.add(entry.getName() + ": select a .gif file");
            return;
        }
        addCanonical(entry, sources, summary);
    }

    private static void addCanonical(File file, Map<String, File> sources, ImportSummary summary)
    {
        try
        {
            File canonical = file.getCanonicalFile();
            sources.putIfAbsent(canonical.getPath().toLowerCase(Locale.ROOT), canonical);
        }
        catch (IOException ex)
        {
            summary.errors.add(file.getName() + ": " + ex.getMessage());
        }
    }

    private void ensureDirectory() throws IOException
    {
        if (!directory.exists() && !directory.mkdirs())
        {
            throw new IOException("Unable to create GIF library: " + directory);
        }
        if (!directory.isDirectory())
        {
            throw new IOException("GIF library path is not a directory: " + directory);
        }
    }

    private boolean isManagedGif(File file) throws IOException
    {
        if (!hasGifExtension(file.getName())
            || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        {
            return false;
        }
        return directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile());
    }

    private static void validateGif(File source) throws IOException
    {
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("not a regular file");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(source))
        {
            if (input == null)
            {
                throw new IOException("unreadable file");
            }
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
            {
                throw new IOException("not a readable GIF");
            }

            ImageReader reader = readers.next();
            try
            {
                if (!"gif".equalsIgnoreCase(reader.getFormatName()))
                {
                    throw new IOException("not a GIF");
                }
                reader.setInput(input, true, true);
                if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0)
                {
                    throw new IOException("GIF has no readable image");
                }
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    private File availableDestination(String sourceName) throws IOException
    {
        String safeName = new File(sourceName).getName();
        int extensionAt = safeName.toLowerCase(Locale.ROOT).lastIndexOf(GIF_EXTENSION);
        String baseName = safeName.substring(0, extensionAt);
        File candidate = directDestination(safeName);
        int suffix = 2;
        while (candidate.exists())
        {
            candidate = directDestination(baseName + " (" + suffix++ + ")" + GIF_EXTENSION);
        }
        return candidate;
    }

    private File directDestination(String name) throws IOException
    {
        File canonicalDirectory = directory.getCanonicalFile();
        File destination = new File(canonicalDirectory, name).getCanonicalFile();
        if (!canonicalDirectory.equals(destination.getParentFile()))
        {
            throw new IOException("invalid filename");
        }
        return destination;
    }

    private static boolean hasGifExtension(String name)
    {
        return name.toLowerCase(Locale.ROOT).endsWith(GIF_EXTENSION);
    }

    static final class ImportSummary
    {
        private final List<File> imported = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private int emptyFolders;

        List<File> getImported()
        {
            return imported;
        }

        List<String> getErrors()
        {
            return errors;
        }

        int getEmptyFolders()
        {
            return emptyFolders;
        }
    }
}
