package com.yonwiplugins.loginscreengifs;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Small streaming GIF89a reader. The LZW routine follows the public-domain
 * decoder published by Kevin Weiner, with bounded frame delivery and stricter
 * input validation added for this plugin.
 */
final class StreamingGifReader
{
    private static final int MAX_STACK_SIZE = 4096;
    private static final long MAX_DECODED_SOURCE_BYTES = 32L * 1024L * 1024L;
    private static final long FALLBACK_FRAME_DURATION_MILLIS = 50L;
    private static final long MINIMUM_FRAME_DURATION_MILLIS = 20L;

    private final byte[] block = new byte[256];
    private final short[] prefix = new short[MAX_STACK_SIZE];
    private final byte[] suffix = new byte[MAX_STACK_SIZE];
    private final byte[] pixelStack = new byte[MAX_STACK_SIZE + 1];

    private BufferedInputStream input;
    private int blockSize;
    private int width;
    private int height;
    private int[] globalColorTable;
    private BufferedImage canvas;
    private GraphicControl control = new GraphicControl();
    private int previousDisposal;
    private Rectangle previousRectangle;
    private BufferedImage previousRestore;

    int read(InputStream source, FrameConsumer consumer) throws IOException
    {
        if (source == null)
        {
            throw new IOException("GIF input is missing");
        }
        input = source instanceof BufferedInputStream
            ? (BufferedInputStream) source
            : new BufferedInputStream(source);
        try
        {
            readHeader();

            int frameCount = 0;
            boolean finished = false;
            while (!finished)
            {
                int marker = readByte();
                switch (marker)
                {
                    case 0x2C:
                        frameCount++;
                        if (!readImage(consumer))
                        {
                            finished = true;
                        }
                        break;
                    case 0x21:
                        readExtension();
                        break;
                    case 0x3B:
                        finished = true;
                        break;
                    case 0x00:
                        break;
                    default:
                        throw new IOException("Unexpected GIF block 0x" + Integer.toHexString(marker));
                }
            }
            return frameCount;
        }
        finally
        {
            release();
        }
    }

    private void readHeader() throws IOException
    {
        byte[] signature = new byte[6];
        readFully(signature, 0, signature.length);
        String header = new String(signature, StandardCharsets.US_ASCII);
        if (!"GIF87a".equals(header) && !"GIF89a".equals(header))
        {
            throw new IOException("Not a GIF87a or GIF89a image");
        }

        width = readShort();
        height = readShort();
        validateImageSize(width, height, "logical screen");
        int packed = readByte();
        boolean hasGlobalColorTable = (packed & 0x80) != 0;
        int globalColorTableSize = 2 << (packed & 7);
        readByte(); // background color index
        readByte(); // pixel aspect ratio
        if (hasGlobalColorTable)
        {
            globalColorTable = readColorTable(globalColorTableSize);
        }

        canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fill(canvas, new Rectangle(0, 0, width, height), Color.BLACK.getRGB());
    }

    private void readExtension() throws IOException
    {
        int label = readByte();
        if (label == 0xF9)
        {
            readGraphicControlExtension();
        }
        else
        {
            skipSubBlocks();
        }
    }

    private void readGraphicControlExtension() throws IOException
    {
        int size = readByte();
        if (size < 4 || size > block.length)
        {
            throw new IOException("Invalid GIF graphic-control block size: " + size);
        }
        readFully(block, 0, size);
        if (readByte() != 0)
        {
            throw new IOException("GIF graphic-control block is not terminated");
        }

        int packed = block[0] & 0xFF;
        int disposal = (packed & 0x1C) >> 2;
        int delayHundredths = (block[1] & 0xFF) | ((block[2] & 0xFF) << 8);
        long duration = delayHundredths > 0
            ? delayHundredths * 10L
            : FALLBACK_FRAME_DURATION_MILLIS;
        control = new GraphicControl(
            disposal,
            (packed & 1) != 0,
            block[3] & 0xFF,
            Math.max(MINIMUM_FRAME_DURATION_MILLIS, duration));
    }

    private boolean readImage(FrameConsumer consumer) throws IOException
    {
        int left = readShort();
        int top = readShort();
        int frameWidth = readShort();
        int frameHeight = readShort();
        validateImageSize(frameWidth, frameHeight, "frame");

        int packed = readByte();
        boolean hasLocalColorTable = (packed & 0x80) != 0;
        boolean interlaced = (packed & 0x40) != 0;
        int localColorTableSize = 2 << (packed & 7);
        int[] colors = hasLocalColorTable ? readColorTable(localColorTableSize) : globalColorTable;
        if (colors == null)
        {
            throw new IOException("GIF frame has no color table");
        }

        applyPreviousDisposal();
        BufferedImage restore = control.disposal == 3 ? copy(canvas) : null;
        byte[] pixels = decodeImageData(frameWidth * frameHeight);
        skipSubBlocks();
        drawPixels(pixels, colors, left, top, frameWidth, frameHeight, interlaced, control);

        BufferedImage frame = copy(canvas);
        boolean keepReading;
        try
        {
            keepReading = consumer.accept(frame, control.durationMillis);
        }
        finally
        {
            frame.flush();
        }

        if (previousRestore != null)
        {
            previousRestore.flush();
        }
        previousDisposal = control.disposal;
        previousRectangle = new Rectangle(left, top, frameWidth, frameHeight);
        previousRestore = restore;
        control = new GraphicControl();
        return keepReading;
    }

    private byte[] decodeImageData(int pixelCount) throws IOException
    {
        byte[] pixels = new byte[pixelCount];
        int dataSize = readByte();
        if (dataSize < 1 || dataSize > 8)
        {
            throw new IOException("Invalid GIF LZW code size: " + dataSize);
        }

        int clear = 1 << dataSize;
        int endOfInformation = clear + 1;
        int available = clear + 2;
        int oldCode = -1;
        int codeSize = dataSize + 1;
        int codeMask = (1 << codeSize) - 1;
        for (int code = 0; code < clear; code++)
        {
            prefix[code] = 0;
            suffix[code] = (byte) code;
        }

        int datum = 0;
        int bits = 0;
        int count = 0;
        int first = 0;
        int top = 0;
        int blockIndex = 0;
        int pixelIndex = 0;

        while (pixelIndex < pixelCount)
        {
            if (top == 0)
            {
                if (bits < codeSize)
                {
                    if (count == 0)
                    {
                        count = readBlock();
                        if (count <= 0)
                        {
                            break;
                        }
                        blockIndex = 0;
                    }
                    datum |= (block[blockIndex] & 0xFF) << bits;
                    bits += 8;
                    blockIndex++;
                    count--;
                    continue;
                }

                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;
                if (code > available || code == endOfInformation)
                {
                    break;
                }
                if (code == clear)
                {
                    codeSize = dataSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clear + 2;
                    oldCode = -1;
                    continue;
                }
                if (oldCode == -1)
                {
                    pixelStack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }

                int inputCode = code;
                if (code == available)
                {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }
                while (code > clear)
                {
                    if (code >= MAX_STACK_SIZE || top >= pixelStack.length)
                    {
                        throw new IOException("Invalid GIF LZW dictionary reference");
                    }
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = suffix[code] & 0xFF;

                pixelStack[top++] = (byte) first;
                if (available < MAX_STACK_SIZE)
                {
                    prefix[available] = (short) oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < MAX_STACK_SIZE)
                    {
                        codeSize++;
                        codeMask += available;
                    }
                }
                oldCode = inputCode;
            }

            top--;
            pixels[pixelIndex++] = pixelStack[top];
        }

        if (pixelIndex < pixelCount)
        {
            Arrays.fill(pixels, pixelIndex, pixelCount, (byte) 0);
        }
        return pixels;
    }

    private void drawPixels(
        byte[] pixels,
        int[] colors,
        int left,
        int top,
        int frameWidth,
        int frameHeight,
        boolean interlaced,
        GraphicControl frameControl)
    {
        int[] destination = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        int pass = 1;
        int increment = 8;
        int interlaceLine = 0;
        for (int sourceLine = 0; sourceLine < frameHeight; sourceLine++)
        {
            int line = sourceLine;
            if (interlaced)
            {
                if (interlaceLine >= frameHeight)
                {
                    pass++;
                    if (pass == 2)
                    {
                        interlaceLine = 4;
                    }
                    else if (pass == 3)
                    {
                        interlaceLine = 2;
                        increment = 4;
                    }
                    else if (pass == 4)
                    {
                        interlaceLine = 1;
                        increment = 2;
                    }
                }
                line = interlaceLine;
                interlaceLine += increment;
            }

            int destinationY = top + line;
            if (destinationY < 0 || destinationY >= height)
            {
                continue;
            }
            int sourceOffset = sourceLine * frameWidth;
            for (int sourceX = 0; sourceX < frameWidth; sourceX++)
            {
                int destinationX = left + sourceX;
                if (destinationX < 0 || destinationX >= width)
                {
                    continue;
                }
                int colorIndex = pixels[sourceOffset + sourceX] & 0xFF;
                if (frameControl.transparent && colorIndex == frameControl.transparentIndex)
                {
                    continue;
                }
                destination[destinationY * width + destinationX] = colors[colorIndex];
            }
        }
    }

    private void applyPreviousDisposal()
    {
        if (previousDisposal == 2 && previousRectangle != null)
        {
            fill(canvas, previousRectangle, Color.BLACK.getRGB());
        }
        else if (previousDisposal == 3 && previousRestore != null)
        {
            Graphics2D graphics = canvas.createGraphics();
            try
            {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(previousRestore, 0, 0, null);
            }
            finally
            {
                graphics.dispose();
            }
        }
    }

    private int[] readColorTable(int colorCount) throws IOException
    {
        byte[] bytes = new byte[colorCount * 3];
        readFully(bytes, 0, bytes.length);
        int[] colors = new int[256];
        int offset = 0;
        for (int index = 0; index < colorCount; index++)
        {
            int red = bytes[offset++] & 0xFF;
            int green = bytes[offset++] & 0xFF;
            int blue = bytes[offset++] & 0xFF;
            colors[index] = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
        return colors;
    }

    private int readBlock() throws IOException
    {
        blockSize = readByte();
        if (blockSize > 0)
        {
            readFully(block, 0, blockSize);
        }
        return blockSize;
    }

    private void skipSubBlocks() throws IOException
    {
        while (readBlock() > 0)
        {
            // readBlock consumes the block
        }
    }

    private int readByte() throws IOException
    {
        int value = input.read();
        if (value < 0)
        {
            throw new EOFException("Unexpected end of GIF data");
        }
        return value;
    }

    private int readShort() throws IOException
    {
        return readByte() | (readByte() << 8);
    }

    private void readFully(byte[] destination, int offset, int length) throws IOException
    {
        int read = 0;
        while (read < length)
        {
            int count = input.read(destination, offset + read, length - read);
            if (count < 0)
            {
                throw new EOFException("Unexpected end of GIF data");
            }
            read += count;
        }
    }

    private void release()
    {
        if (previousRestore != null)
        {
            previousRestore.flush();
            previousRestore = null;
        }
        if (canvas != null)
        {
            canvas.flush();
            canvas = null;
        }
    }

    private static void validateImageSize(int imageWidth, int imageHeight, String label) throws IOException
    {
        long decodedBytes = (long) imageWidth * (long) imageHeight * Integer.BYTES;
        if (imageWidth <= 0 || imageHeight <= 0 || decodedBytes > MAX_DECODED_SOURCE_BYTES)
        {
            throw new IOException(label + " exceeds the 32 MiB decoded-image limit: "
                + imageWidth + "x" + imageHeight);
        }
    }

    private static void fill(BufferedImage image, Rectangle rectangle, int color)
    {
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(new Color(color, true));
            graphics.fill(rectangle);
        }
        finally
        {
            graphics.dispose();
        }
    }

    private static BufferedImage copy(BufferedImage source)
    {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        }
        finally
        {
            graphics.dispose();
        }
        return result;
    }

    @FunctionalInterface
    interface FrameConsumer
    {
        boolean accept(BufferedImage frame, long durationMillis) throws IOException;
    }

    private static final class GraphicControl
    {
        private final int disposal;
        private final boolean transparent;
        private final int transparentIndex;
        private final long durationMillis;

        private GraphicControl()
        {
            this(0, false, 0, FALLBACK_FRAME_DURATION_MILLIS);
        }

        private GraphicControl(int disposal, boolean transparent, int transparentIndex, long durationMillis)
        {
            this.disposal = disposal;
            this.transparent = transparent;
            this.transparentIndex = transparentIndex;
            this.durationMillis = durationMillis;
        }
    }
}
