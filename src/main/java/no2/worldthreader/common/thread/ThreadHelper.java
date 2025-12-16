package no2.worldthreader.common.thread;

import no2.worldthreader.WorldThreaderMod;
import net.minecraft.server.level.ServerLevel;

public class ThreadHelper {

    public static void swapOnMultithreadTickStart(Thread mainThread, Thread worldThread, ThreadOwnedObject... threadExclusiveObjects) {
        for (ThreadOwnedObject object : threadExclusiveObjects) {
            if (mainThread == object.worldthreader$getOwningThread()) {
                object.worldthreader$setOwningThread(worldThread);
            } else {
                throw new IllegalStateException("Failed to swap thread exclusive access!");
            }
        }
    }

    public static void swapOnMultithreadTickEnd(Thread mainThread, Thread worldThread, ThreadOwnedObject... threadExclusiveObjects) {
        for (ThreadOwnedObject object : threadExclusiveObjects) {
            if (worldThread == object.worldthreader$getOwningThread()) {
                object.worldthreader$setOwningThread(mainThread);
            } else {
                throw new IllegalStateException("Failed to swap thread exclusive access!");
            }
        }
    }

    public static void setWorldThreadName(Thread thread, ServerLevel world) {
        setWorldThreadName(thread, world, 0, 1);
    }

    public static void setWorldThreadName(Thread thread, ServerLevel world, int threadIndex, int totalThreads) {
        if (totalThreads > 1) {
            thread.setName(WorldThreaderMod.MOD_ID + "_" + world.dimension().location() + "_" + threadIndex);
        } else {
            thread.setName(WorldThreaderMod.MOD_ID + "_" + world.dimension().location());
        }
    }
}
