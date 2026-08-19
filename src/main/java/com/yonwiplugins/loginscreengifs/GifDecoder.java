package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GifDecoder
{
    private static final Logger log = LoggerFactory.getLogger(GifDecoder.class);
    static final int BUFFER_CAPACITY = 2;
    static final int LOGIN_WIDTH = 1536;
    static final int LOGIN_HEIGHT = 864;
    static final long FALLBACK_FRAME_DURATION_MILLIS = 50L;

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
            try (InputStream input = streamProvider.open(gif))
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

    private int decodeOnce(InputStream input, long activeGeneration) throws IOException
    {
        StreamingGifReader reader = new StreamingGifReader();
        return reader.read(input, (frame, durationMillis) ->
        {
            BufferedImage output = resize(frame, config.scaleMode(), outputWidth, outputHeight);
            try
            {
                boolean accepted = offer(new DecodedFrame(output, durationMillis), activeGeneration);
                if (!accepted)
                {
                    output.flush();
                }
                return accepted;
            }
            catch (RuntimeException ex)
            {
                output.flush();
                throw ex;
            }
        });
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
        InputStream open(File gif) throws IOException;
    }
}
