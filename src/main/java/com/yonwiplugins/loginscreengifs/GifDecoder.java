package com.yonwiplugins.loginscreengifs;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

final class GifDecoder
{
    private static final Logger log = LoggerFactory.getLogger(GifDecoder.class);
    static final int BUFFER_CAPACITY = 2;
    static final int LOGIN_WIDTH = 1536;
    static final int LOGIN_HEIGHT = 864;
    static final long FALLBACK_FRAME_DURATION_MILLIS = 50L;
    private static final long MINIMUM_FRAME_DURATION_MILLIS = 20L;
    private static final long MAX_DECODED_SOURCE_BYTES = 32L * 1024L * 1024L;
    private static final String GIF_STREAM_METADATA = "javax_imageio_gif_stream_1.0";
    private static final String GIF_IMAGE_METADATA = "javax_imageio_gif_image_1.0";

    private final LoginScreenGifsConfig config;
    private final StreamProvider streamProvider;
    private final int outputWidth;
    private final int outputHeight;
    private final BlockingQueue<DecodedFrame> frames = new ArrayBlockingQueue<>(BUFFER_CAPACITY);
    private final AtomicLong generation = new AtomicLong();

    private ExecutorService executor;
    private Future<?> task;

    @Inject
    GifDecoder(LoginScreenGifsConfig config, GifLibrary library)
    {
        this(config, library::open, LOGIN_WIDTH, LOGIN_HEIGHT);
    }

    GifDecoder(LoginScreenGifsConfig config, StreamProvider streamProvider, int outputWidth, int outputHeight)
    {
        this.config = config;
        this.streamProvider = streamProvider;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    synchronized void start(File gif)
    {
        stop();
        long activeGeneration = generation.incrementAndGet();
        executor = Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "login-screen-gifs-decoder");
            thread.setDaemon(true);
            return thread;
        });
        task = executor.submit(() -> decodeForever(gif, activeGeneration));
    }

    synchronized void stop()
    {
        generation.incrementAndGet();
        if (task != null)
        {
            task.cancel(true);
            task = null;
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
        clearFrames();
    }

    DecodedFrame poll()
    {
        return frames.poll();
    }

    int bufferedFrameCount()
    {
        return frames.size();
    }

    private void decodeForever(File gif, long activeGeneration)
    {
        int completedLoops = 0;
        while (isActive(activeGeneration))
        {
            try (ImageInputStream input = streamProvider.open(gif))
            {
                if (input == null)
                {
                    throw new IOException("Unable to open " + gif.getName());
                }
                int decoded = decodeOnce(input, activeGeneration);
                if (decoded == 0)
                {
                    throw new IOException("GIF contains no readable frames");
                }
                if (completedLoops++ == 0)
                {
                    log.info("Playing {} with at most {} prefetched frames", gif, BUFFER_CAPACITY);
                }
                if (!offer(DecodedFrame.loopBoundary(), activeGeneration))
                {
                    return;
                }
            }
            catch (IOException | RuntimeException ex)
            {
                if (isActive(activeGeneration))
                {
                    log.warn("Unable to play {}", gif, ex);
                }
                return;
            }
        }
    }

    private int decodeOnce(ImageInputStream input, long activeGeneration) throws IOException
    {
        ImageReader reader = gifReader();
        if (reader == null)
        {
            throw new IOException("No GIF reader is available");
        }

        try
        {
            reader.setInput(input, false, false);
            int[] screen = logicalScreenSize(reader);
            validateImageSize(screen[0], screen[1], "logical screen");

            BufferedImage canvas = new BufferedImage(screen[0], screen[1], BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = canvas.createGraphics();
            try
            {
                graphics.setComposite(AlphaComposite.Src);
                graphics.setColor(Color.BLACK);
                graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

                int index = 0;
                while (isActive(activeGeneration))
                {
                    IIOImage image;
                    try
                    {
                        validateImageSize(reader.getWidth(index), reader.getHeight(index), "frame " + index);
                        image = reader.readAll(index, null);
                    }
                    catch (IndexOutOfBoundsException ex)
                    {
                        break;
                    }

                    BufferedImage raw = (BufferedImage) image.getRenderedImage();
                    FrameMetadata metadata = frameMetadata(image.getMetadata());
                    BufferedImage before = "restoreToPrevious".equals(metadata.disposalMethod)
                        ? copy(canvas)
                        : null;

                    graphics.setComposite(AlphaComposite.SrcOver);
                    graphics.drawImage(raw, metadata.left, metadata.top, null);
                    raw.flush();

                    BufferedImage output = resize(canvas, config.scaleMode(), outputWidth, outputHeight);
                    if (!offer(new DecodedFrame(output, metadata.durationMillis), activeGeneration))
                    {
                        output.flush();
                        if (before != null)
                        {
                            before.flush();
                        }
                        break;
                    }

                    applyDisposal(graphics, before, metadata);
                    if (before != null)
                    {
                        before.flush();
                    }
                    index++;
                }
                return index;
            }
            finally
            {
                graphics.dispose();
                canvas.flush();
            }
        }
        finally
        {
            reader.dispose();
        }
    }

    private boolean offer(DecodedFrame frame, long activeGeneration)
    {
        while (isActive(activeGeneration))
        {
            if (frames.offer(frame))
            {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        }
        return false;
    }

    private boolean isActive(long activeGeneration)
    {
        return generation.get() == activeGeneration && !Thread.currentThread().isInterrupted();
    }

    private void clearFrames()
    {
        DecodedFrame frame;
        while ((frame = frames.poll()) != null)
        {
            frame.release();
        }
    }

    private static ImageReader gifReader()
    {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        return readers.hasNext() ? readers.next() : null;
    }

    private static int[] logicalScreenSize(ImageReader reader) throws IOException
    {
        IIOMetadata metadata = reader.getStreamMetadata();
        if (metadata != null)
        {
            Node root = metadata.getAsTree(GIF_STREAM_METADATA);
            Node descriptor = child(root, "LogicalScreenDescriptor");
            if (descriptor != null)
            {
                return new int[] {
                    Math.max(1, intAttribute(descriptor, "logicalScreenWidth", LOGIN_WIDTH)),
                    Math.max(1, intAttribute(descriptor, "logicalScreenHeight", LOGIN_HEIGHT))
                };
            }
        }
        return new int[] {reader.getWidth(0), reader.getHeight(0)};
    }

    private static void validateImageSize(int width, int height, String label) throws IOException
    {
        long estimatedBytes = (long) width * (long) height * Integer.BYTES;
        if (width <= 0 || height <= 0 || estimatedBytes > MAX_DECODED_SOURCE_BYTES)
        {
            throw new IOException(label + " exceeds the 32 MiB decoded-image limit: " + width + "x" + height);
        }
    }

    private static FrameMetadata frameMetadata(IIOMetadata metadata)
    {
        if (metadata == null)
        {
            return new FrameMetadata(0, 0, 0, 0, "none", FALLBACK_FRAME_DURATION_MILLIS);
        }

        Node root = metadata.getAsTree(GIF_IMAGE_METADATA);
        Node descriptor = child(root, "ImageDescriptor");
        Node control = child(root, "GraphicControlExtension");
        int delayHundredths = control == null ? 0 : intAttribute(control, "delayTime", 0);
        long duration = delayHundredths > 0
            ? delayHundredths * 10L
            : FALLBACK_FRAME_DURATION_MILLIS;

        return new FrameMetadata(
            descriptor == null ? 0 : intAttribute(descriptor, "imageLeftPosition", 0),
            descriptor == null ? 0 : intAttribute(descriptor, "imageTopPosition", 0),
            descriptor == null ? 0 : intAttribute(descriptor, "imageWidth", 0),
            descriptor == null ? 0 : intAttribute(descriptor, "imageHeight", 0),
            control == null ? "none" : stringAttribute(control, "disposalMethod", "none"),
            Math.max(MINIMUM_FRAME_DURATION_MILLIS, duration));
    }

    private static void applyDisposal(Graphics2D graphics, BufferedImage before, FrameMetadata metadata)
    {
        if ("restoreToBackgroundColor".equals(metadata.disposalMethod))
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(metadata.left, metadata.top, metadata.width, metadata.height);
        }
        else if (before != null)
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(before, 0, 0, null);
        }
    }

    static BufferedImage resize(BufferedImage source, ScaleMode scaleMode, int width, int height)
    {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            if (scaleMode == ScaleMode.STRETCH)
            {
                graphics.drawImage(source, 0, 0, width, height, null);
            }
            else
            {
                double widthScale = (double) width / source.getWidth();
                double heightScale = (double) height / source.getHeight();
                double scale = scaleMode == ScaleMode.CONTAIN
                    ? Math.min(widthScale, heightScale)
                    : Math.max(widthScale, heightScale);
                int scaledWidth = (int) Math.round(source.getWidth() * scale);
                int scaledHeight = (int) Math.round(source.getHeight() * scale);
                graphics.drawImage(
                    source,
                    (width - scaledWidth) / 2,
                    (height - scaledHeight) / 2,
                    scaledWidth,
                    scaledHeight,
                    null);
            }
        }
        finally
        {
            graphics.dispose();
        }
        return resized;
    }

    private static BufferedImage copy(BufferedImage source)
    {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        }
        finally
        {
            graphics.dispose();
        }
        return copy;
    }

    private static Node child(Node parent, String name)
    {
        if (parent == null)
        {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (name.equals(node.getNodeName()))
            {
                return node;
            }
        }
        return null;
    }

    private static int intAttribute(Node node, String name, int fallback)
    {
        String value = stringAttribute(node, name, null);
        if (value == null)
        {
            return fallback;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex)
        {
            return fallback;
        }
    }

    private static String stringAttribute(Node node, String name, String fallback)
    {
        NamedNodeMap attributes = node.getAttributes();
        if (attributes == null)
        {
            return fallback;
        }
        Node attribute = attributes.getNamedItem(name);
        return attribute == null ? fallback : attribute.getNodeValue();
    }

    static final class DecodedFrame
    {
        private final BufferedImage image;
        private final long durationMillis;
        private final boolean loopBoundary;

        private DecodedFrame(BufferedImage image, long durationMillis)
        {
            this.image = image;
            this.durationMillis = durationMillis;
            this.loopBoundary = false;
        }

        private DecodedFrame()
        {
            this.image = null;
            this.durationMillis = 0L;
            this.loopBoundary = true;
        }

        static DecodedFrame loopBoundary()
        {
            return new DecodedFrame();
        }

        BufferedImage getImage()
        {
            return image;
        }

        long getDurationMillis()
        {
            return durationMillis;
        }

        boolean isLoopBoundary()
        {
            return loopBoundary;
        }

        void release()
        {
            if (image != null)
            {
                image.flush();
            }
        }
    }

    @FunctionalInterface
    interface StreamProvider
    {
        ImageInputStream open(File gif) throws IOException;
    }

    private static final class FrameMetadata
    {
        private final int left;
        private final int top;
        private final int width;
        private final int height;
        private final String disposalMethod;
        private final long durationMillis;

        private FrameMetadata(int left, int top, int width, int height, String disposalMethod, long durationMillis)
        {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.disposalMethod = disposalMethod;
            this.durationMillis = durationMillis;
        }
    }
}
