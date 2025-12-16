package no2.worldthreader.common;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.storage.PrimaryLevelData;
import no2.worldthreader.common.mixin_support.interfaces.ServerWorldExtended;
import no2.worldthreader.common.thread.ThreadHelper;
import no2.worldthreader.common.thread.ThreadLocals;
import no2.worldthreader.common.thread.ThreadOwnedObject;
import no2.worldthreader.common.thread.WorldThreadingManager;

import java.util.function.BooleanSupplier;

public class ServerWorldTicking {

    public static boolean isMainWorld(ServerLevel world) {
        return world.getLevelData() instanceof PrimaryLevelData;
    }

    public static void runWorldThread(MinecraftServer server, WorldThreadingManager worldThreadingManager, ServerLevel serverWorld, ThreadOwnedObject[] threadOwnedObjects) {
        runWorldThread(server, worldThreadingManager, serverWorld, threadOwnedObjects, 0, 1);
    }

    public static void runWorldThread(MinecraftServer server, WorldThreadingManager worldThreadingManager, ServerLevel serverWorld, ThreadOwnedObject[] threadOwnedObjects, int threadIndex, int totalThreads) {
        Thread currentThread = Thread.currentThread();
        ThreadLocals.WORLD_THREAD_MINECRAFT_SERVER_ACCESS.set(server);
        boolean isPrimaryThread = threadIndex == 0;
        boolean continueMultithreading = true;
        while (continueMultithreading) {
            //Start of tick barrier
            if (worldThreadingManager.tickBarrier() < 0) {
                continueMultithreading = false;
            } else {
                if (isPrimaryThread) {
                    // Only the primary thread (index 0) does the ownership swap and ticking
                    Thread mainThread = ((ThreadOwnedObject) serverWorld).worldthreader$getOwningThread();
                    ThreadHelper.swapOnMultithreadTickStart(mainThread, currentThread, threadOwnedObjects);
                    tickThreaded(server, worldThreadingManager, serverWorld, threadIndex, totalThreads);
                    ThreadHelper.swapOnMultithreadTickEnd(mainThread, currentThread, threadOwnedObjects);
                } else {
                    // Worker threads participate in barriers but don't do main ticking yet
                    // They can be extended to participate in parallel chunk/entity ticking
                    tickWorkerThread(server, worldThreadingManager, serverWorld, threadIndex, totalThreads);
                }
                //End of tick barrier
                if (worldThreadingManager.tickBarrier() < 0) {
                    continueMultithreading = false;
                }
            }
        }
        ThreadLocals.WORLD_THREAD_MINECRAFT_SERVER_ACCESS.remove();
    }

    public static void tickThreaded(MinecraftServer server, WorldThreadingManager worldThreadingManager, ServerLevel serverLevel) {
        tickThreaded(server, worldThreadingManager, serverLevel, 0, 1);
    }

    public static void tickThreaded(MinecraftServer server, WorldThreadingManager worldThreadingManager, ServerLevel serverLevel, int threadIndex, int totalThreads) {
        //TODO Issues mostly with Command Blocks: Level Properties, Level Info is not threadsafe.

        final BooleanSupplier shouldKeepTicking = worldThreadingManager::shouldKeepTickingThreaded;

        String crashReason = "Exception in server world thread";
        try {
            // [VanillaCopy] MinecraftServer#tickChildren
            ProfilerFiller profilerFiller = Profiler.get();
            profilerFiller.push(() -> serverLevel + " " + serverLevel.dimension().location());
            if (server.getTickCount() % 20 == 0) {
                profilerFiller.push("timeSync");
                server.synchronizeTime(serverLevel);
                profilerFiller.pop();
            }
            profilerFiller.push("tick");

            crashReason = "Exception ticking world";
            worldThreadingManager.withinTickBarrier();
            ((ServerWorldExtended) serverLevel).worldthreader$setTickPhase(WorldThreaderTickPhase.WORLD_TICK);
            serverLevel.tick(shouldKeepTicking);

            crashReason = "Exception receiving entities from other worlds";
            worldThreadingManager.withinTickBarrier();
            ((ServerWorldExtended) serverLevel).worldthreader$setTickPhase(WorldThreaderTickPhase.RECEIVE_TELEPORTS);
            finishTeleportsToWorld(serverLevel);

            crashReason = "Exception restoring entities that could not be teleported to another world";
            worldThreadingManager.withinTickBarrier();
            ((ServerWorldExtended) serverLevel).worldthreader$setTickPhase(WorldThreaderTickPhase.RECOVER_FAILED_TELEPORTS);
            recoverFailedTeleports(serverLevel);

            crashReason = "Exception in server world thread";
            //Additional barrier here fixes the issue where one thread taking exclusive ownership during recoverFailedTeleports causes other threads crash due to ownership not being handed back before trying to give ownership to the main thread
            worldThreadingManager.withinTickBarrier();
            ((ServerWorldExtended) serverLevel).worldthreader$setTickPhase(WorldThreaderTickPhase.NONE);

            profilerFiller.pop();
            profilerFiller.pop();
        } catch (Throwable throwable) {
            delegateCrash(throwable, crashReason, serverLevel, worldThreadingManager);
        }
    }

    private static void delegateCrash(Throwable throwable, String title, ServerLevel serverLevel, WorldThreadingManager worldThreadingManager) {
        String serverLevelOwner = ((ThreadOwnedObject) serverLevel).worldthreader$getOwningThread().getName();
        String chunkCacheOwner = ((ThreadOwnedObject) serverLevel.getChunkSource()).worldthreader$getOwningThread().getName();
        worldThreadingManager.tryGiveAwayExclusiveWorldAccess(); //If the exception was thrown while this thread held exclusive access, it must be returned.

        CrashReport crashReport = CrashReport.forThrowable(throwable, title);
        serverLevel.fillReportDetails(crashReport);
        CrashReportCategory worldthreaderCrashInfo = crashReport.addCategory("WorldThreader");
        worldthreaderCrashInfo.setDetail("Crashing thread", Thread.currentThread().getName());
        worldthreaderCrashInfo.setDetail("Level owner", serverLevelOwner);
        worldthreaderCrashInfo.setDetail("ChunkCache owner", chunkCacheOwner);
        worldThreadingManager.handleCrash(crashReport);
    }

    public static void finishTeleportsToWorld(ServerLevel world) {
        ((ServerWorldExtended) world).worldthreader$finishReceivingTeleportedEntities();
    }

    public static void recoverFailedTeleports(ServerLevel world) {
        ((ServerWorldExtended) world).worldthreader$recoverFailedTeleports();
    }

    /**
     * Worker thread tick method for additional threads per world.
     * These threads participate in barriers to stay synchronized with the primary thread.
     * Currently, they wait at barriers but can be extended to participate in parallel work.
     */
    public static void tickWorkerThread(MinecraftServer server, WorldThreadingManager worldThreadingManager, ServerLevel serverLevel, int threadIndex, int totalThreads) {
        String crashReason = "Exception in server world worker thread";
        try {
            // Worker threads participate in barriers to stay synchronized
            // Barrier 1: Before world tick
            worldThreadingManager.withinTickBarrier();
            // Worker threads could perform parallel chunk/entity ticking here in the future

            // Barrier 2: Before receive teleports
            worldThreadingManager.withinTickBarrier();

            // Barrier 3: Before recover failed teleports
            worldThreadingManager.withinTickBarrier();

            // Barrier 4: End of tick phase
            worldThreadingManager.withinTickBarrier();
        } catch (Throwable throwable) {
            delegateCrash(throwable, crashReason, serverLevel, worldThreadingManager);
        }
    }
}
