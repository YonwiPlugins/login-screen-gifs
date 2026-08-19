package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GifDecoderTest
{
    private static final String GIF_METADATA = "javax_imageio_gif_image_1.0";
    private static final File TEST_GIF = new File("anything.gif");

    @Test
    public void streamsLongGifWithBoundedMemory() throws Exception
    {
        byte[] gif = createGif(120, 5);
        GifDecoder decoder = decoderFor(gif);
        int decoded = 0;
        long duration = 0L;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        decoder.start(TEST_GIF);
        try
        {
            while (decoded < 120 && System.nanoTime() < deadline)
            {
                assertTrue(decoder.bufferedFrameCount() <= GifDecoder.BUFFER_CAPACITY);
                GifDecoder.DecodedFrame frame = decoder.poll();
                if (frame == null)
                {
                    Thread.sleep(1L);
                    continue;
                }
                duration += frame.getDurationMillis();
                decoded++;
                frame.release();
            }
        }
        finally
        {
            decoder.stop();
        }
        assertEquals(120, decoded);
        assertEquals(6000L, duration);
    }

    @Test
    public void automaticallyUsesSafetyTimingForZeroDelayFrame() throws Exception
    {
        GifDecoder decoder = decoderFor(createGif(1, 0));
        GifDecoder.DecodedFrame frame = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        decoder.start(TEST_GIF);
        try
        {
            while (frame == null && System.nanoTime() < deadline)
            {
                frame = decoder.poll();
                if (frame == null)
                {
                    Thread.sleep(1L);
                }
            }
            assertNotNull(frame);
            assertEquals(GifDecoder.FALLBACK_FRAME_DURATION_MILLIS, frame.getDurationMillis());
        }
        finally
        {
            if (frame != null)
            {
                frame.release();
            }
            decoder.stop();
        }
    }

    @Test
    public void stretchAndCoverProduceDifferentEdges()
    {
        BufferedImage source = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++)
        {
            for (int x = 0; x < source.getWidth(); x++)
            {
                source.setRGB(x, y, x < 5 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }

        BufferedImage stretched = GifDecoder.resize(source, ScaleMode.STRETCH, 160, 90);
        BufferedImage covered = GifDecoder.resize(source, ScaleMode.COVER, 160, 90);
        try
        {
            assertEquals(Color.RED.getRGB(), stretched.getRGB(0, 45));
            assertEquals(Color.BLUE.getRGB(), covered.getRGB(0, 45));
        }
        finally
        {
            stretched.flush();
            covered.flush();
            source.flush();
        }
    }

    private static GifDecoder decoderFor(byte[] gif)
    {
        return new GifDecoder(
            new LoginScreenGifsConfig() { },
            ignored -> ImageIO.createImageInputStream(new ByteArrayInputStream(gif)),
            16,
            9);
    }

    private static byte[] createGif(int frameCount, int delayHundredths) throws IOException
    {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        assertTrue(writers.hasNext());
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes))
        {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frameCount; i++)
            {
                BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, (i & 1) == 0 ? Color.RED.getRGB() : Color.BLUE.getRGB());
                IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_METADATA);
                IIOMetadataNode control = findNode(root, "GraphicControlExtension");
                assertNotNull(control);
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", Integer.toString(delayHundredths));
                control.setAttribute("transparentColorIndex", "0");
                metadata.setFromTree(GIF_METADATA, root);
                writer.writeToSequence(new IIOImage(image, null, metadata), null);
                image.flush();
            }
            writer.endWriteSequence();
        }
        finally
        {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static IIOMetadataNode findNode(IIOMetadataNode root, String name)
    {
        for (int i = 0; i < root.getLength(); i++)
        {
            if (name.equals(root.item(i).getNodeName()))
            {
                return (IIOMetadataNode) root.item(i);
            }
        }
        return null;
    }
}
