package com.yonwiplugins.loginscreengifs;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GifLibraryTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void discoversAnyGifFilenameInAlphabeticalOrder() throws Exception
    {
        File libraryDirectory = temporaryFolder.newFolder("library");
        writeGif(new File(libraryDirectory, "Zebra dance.gif"));
        writeGif(new File(libraryDirectory, "anything-at-all.GIF"));
        temporaryFolder.newFile("library/not-a-gif.txt");
        assertTrue(new File(libraryDirectory, "folder.gif").mkdir());

        List<File> files = new GifLibrary(libraryDirectory).listGifs();
        assertEquals(2, files.size());
        assertEquals("anything-at-all.GIF", files.get(0).getName());
        assertEquals("Zebra dance.gif", files.get(1).getName());
    }

    @Test
    public void importsManyFilesWithoutOverwritingMatchingNames() throws Exception
    {
        File libraryDirectory = temporaryFolder.newFolder("many-library");
        File sourceDirectory = temporaryFolder.newFolder("many-source");
        File first = new File(sourceDirectory, "Forest Loop.GIF");
        File second = new File(sourceDirectory, "Sea.gif");
        writeGif(first);
        writeGif(second);

        GifLibrary library = new GifLibrary(libraryDirectory);
        library.importEntries(Arrays.asList(first, second));
        library.importEntries(Collections.singletonList(first));

        List<File> files = library.listGifs();
        assertEquals(3, files.size());
        assertTrue(files.stream().anyMatch(file -> file.getName().equals("Forest Loop.GIF")));
        assertTrue(files.stream().anyMatch(file -> file.getName().equals("Forest Loop (2).gif")));
    }

    @Test
    public void importsFoldersRecursively() throws Exception
    {
        File libraryDirectory = temporaryFolder.newFolder("folder-library");
        File sourceDirectory = temporaryFolder.newFolder("folder-source");
        File nested = new File(sourceDirectory, "one/two");
        assertTrue(nested.mkdirs());
        writeGif(new File(sourceDirectory, "top.gif"));
        writeGif(new File(nested, "deep.gif"));
        File ignored = new File(nested, "ignored.png");
        assertTrue(ignored.createNewFile());

        GifLibrary.ImportSummary summary = new GifLibrary(libraryDirectory)
            .importEntries(Collections.singletonList(sourceDirectory));

        assertEquals(2, summary.getImported().size());
        assertEquals(0, summary.getErrors().size());
    }

    @Test
    public void rejectsFakeGifWithoutBlockingValidImports() throws Exception
    {
        File libraryDirectory = temporaryFolder.newFolder("validated-library");
        File sourceDirectory = temporaryFolder.newFolder("validated-source");
        File valid = new File(sourceDirectory, "valid.gif");
        File fake = new File(sourceDirectory, "fake.gif");
        writeGif(valid);
        assertTrue(fake.createNewFile());

        GifLibrary.ImportSummary summary = new GifLibrary(libraryDirectory)
            .importEntries(Arrays.asList(valid, fake));

        assertEquals(1, summary.getImported().size());
        assertEquals(1, summary.getErrors().size());
        assertEquals(1, new GifLibrary(libraryDirectory).listGifs().size());
    }

    private static void writeGif(File file) throws Exception
    {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try
        {
            assertTrue(ImageIO.write(image, "gif", file));
        }
        finally
        {
            image.flush();
        }
    }
}
