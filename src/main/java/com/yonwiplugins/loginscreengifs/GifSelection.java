package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

final class GifSelection
{
    private GifSelection()
    {
    }

    static int findByName(List<File> files, String name)
    {
        if (name == null)
        {
            return -1;
        }

        for (int index = 0; index < files.size(); index++)
        {
            if (name.equalsIgnoreCase(files.get(index).getName()))
            {
                return index;
            }
        }
        return -1;
    }

    static int next(int size, int currentIndex, int offset)
    {
        if (size <= 0)
        {
            return -1;
        }
        int safeCurrent = currentIndex < 0 || currentIndex >= size ? 0 : currentIndex;
        return Math.floorMod(safeCurrent + offset, size);
    }

    static int choose(int size, int currentIndex, boolean random)
    {
        return random
            ? randomOther(size, currentIndex, ThreadLocalRandom.current())
            : next(size, currentIndex, 1);
    }

    static int random(int size)
    {
        return size <= 0 ? -1 : ThreadLocalRandom.current().nextInt(size);
    }

    static int randomOther(int size, int currentIndex, Random random)
    {
        if (size <= 0)
        {
            return -1;
        }
        if (size == 1)
        {
            return 0;
        }
        if (currentIndex < 0 || currentIndex >= size)
        {
            return random.nextInt(size);
        }

        int candidate = random.nextInt(size - 1);
        return candidate >= currentIndex ? candidate + 1 : candidate;
    }
}
