package me.earthme.millacat.hook;

import com.google.common.collect.Maps;
import me.earthme.millacat.concurrent.SplittingTraverseTask;
import me.earthme.millacat.concurrent.thread.TickForkJoinWorker;
import me.earthme.millacat.concurrent.thread.TickThreadImpl;
import net.minecraft.Util;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.v1_18_R2.SpigotTimings;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public class ServerThreadingHooks {
    private static final Logger logger = LogManager.getLogger();
    private static final AtomicInteger idGenerator = new AtomicInteger();
    private static final ForkJoinPool tileEntityThreadPool;
    private static final ExecutorService entityThreadPool;
    private static final ExecutorService worldThreadPool;
    private static final ExecutorService miscThreadPool;
    private static final Map<ServerLevel,AtomicInteger> worldTaskCount = Maps.newConcurrentMap();
    private static final Map<ServerLevel, ForkJoinTask<?>> worldTileTickTasks = Maps.newConcurrentMap();
    private static final Map<ServerLevel, Queue<Runnable>> worldCallbackTasks = Maps.newConcurrentMap();
    private static final AtomicInteger worldTickTaskCount = new AtomicInteger();

    public static void awaitAllTasks(){
        for (ForkJoinTask<?> task : worldTileTickTasks.values()){
            try {
                task.join();
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        for (AtomicInteger taskCount : worldTaskCount.values()){
            while (taskCount.get() > 0){
                LockSupport.parkNanos(1_000_000);
            }
        }

        for (Queue<Runnable> callBackQueue : worldCallbackTasks.values()){
            Runnable task;
            while ((task = callBackQueue.poll()) != null){
                try {
                    task.run();
                }catch (Exception e){
                    logger.error("Failed to run call back task!",e);
                    e.printStackTrace();
                }
            }
        }

        while (worldTickTaskCount.get() > 0){
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static void checkAndInit(ServerLevel level){
        if (!worldTaskCount.containsKey(level)){
            worldTaskCount.put(level,new AtomicInteger());
            logger.info("Inited tick counter for level {}",level.getWorld().getName());
        }

        if (!worldCallbackTasks.containsKey(level)){
            logger.info("Inited callback task queue for level {}",level.getWorld().getName());
            worldCallbackTasks.put(level,new ConcurrentLinkedQueue<>());
        }
    }

    public static void postWorldTickTask(ServerLevel levelIn, BooleanSupplier shouldKeepTicking){
        checkAndInit(levelIn);
        worldTickTaskCount.getAndIncrement();
        worldThreadPool.execute(()->{
            try {
                final MinecraftServer server = MinecraftServer.getServer();
                long tickStart = Util.getNanos();
                SpigotTimings.timeUpdateTimer.startTiming(); // Spigot
                if (server.getTickCount() % 20 == 0) {
                    server.getPlayerList().broadcastAll(new ClientboundSetTimePacket(levelIn.getGameTime(),levelIn.getDayTime(),levelIn.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)),levelIn.dimension());
                }
                SpigotTimings.timeUpdateTimer.stopTiming(); // Spigot
                net.minecraftforge.event.ForgeEventFactory.onPreWorldTick(levelIn, shouldKeepTicking);

                try {
                    levelIn.timings.doTick.startTiming(); // Spigot
                    levelIn.tick(shouldKeepTicking);
                    levelIn.timings.doTick.stopTiming(); // Spigot
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
                net.minecraftforge.event.ForgeEventFactory.onPostWorldTick(levelIn, shouldKeepTicking);

                server.perWorldTickTimes.computeIfAbsent(levelIn.dimension(), k -> new long[100])[server.getTickCount() % 100] = Util.getNanos() - tickStart;
            }finally {
                worldTickTaskCount.getAndDecrement();
            }
        });
    }

    public static void callGlobalTileEntityTickTask(ServerLevel levelIn){
        final ForkJoinTask<?> lastTask = worldTileTickTasks.get(levelIn);
        if (lastTask != null && !lastTask.isDone()){
            throw new IllegalStateException("Tile entity tick task already running!");
        }

        if (!levelIn.pendingFreshBlockEntities.isEmpty()) {
            levelIn.freshBlockEntities.addAll(levelIn.pendingFreshBlockEntities);
            levelIn.pendingFreshBlockEntities.clear();
        }

        levelIn.tickingBlockEntities = true;
        if (!levelIn.freshBlockEntities.isEmpty()) {
            levelIn.freshBlockEntities.forEach(BlockEntity::onLoad);
            levelIn.freshBlockEntities.clear();
        }

        if (!levelIn.pendingBlockEntityTickers.isEmpty()) {
            levelIn.blockEntityTickers.addAll(levelIn.pendingBlockEntityTickers);
            levelIn.pendingBlockEntityTickers.clear();
        }

        worldTileTickTasks.replace(levelIn, SplittingTraverseTask.createNewSubmitted(tickingblockentity -> {
            if (tickingblockentity.isRemoved()) {
                levelIn.blockEntityTickers.remove(tickingblockentity);
            } else if (levelIn.shouldTickBlocksAt(ChunkPos.asLong(tickingblockentity.getPos()))) {
                tickingblockentity.tick();
            }
        },levelIn.blockEntityTickers,tileEntityThreadPool));

        worldCallbackTasks.get(levelIn).offer(()-> {
            levelIn.tickingBlockEntities = false;
            levelIn.spigotConfig.currentPrimedTnt = 0; // Spigot
        });
    }

    public static void postChunkTickTask(@NotNull LevelChunk chunkIn, int randomTickFreq){
        final ServerLevel level = (ServerLevel) chunkIn.level;
        final AtomicInteger taskCounter = worldTaskCount.get(level);
        taskCounter.getAndIncrement();
        miscThreadPool.execute(()->{
            try {
                level.tickChunk(chunkIn,randomTickFreq);
            }finally {
                taskCounter.getAndDecrement();
            }
        });
    }

    public static void postEntityTicKTask(@NotNull Entity entityIn){
        final ServerLevel level = (ServerLevel) entityIn.level;
        final AtomicInteger taskCounter = worldTaskCount.get(level);
        taskCounter.getAndIncrement();

        final Runnable task = ()->{
            try {
                if (!entityIn.isRemoved()) {
                    if (level.shouldDiscardEntity(entityIn)) {
                        entityIn.discard();
                    } else {
                        entityIn.checkDespawn();
                        if (level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(entityIn.chunkPosition().toLong())) {
                            Entity entity = entityIn.getVehicle();
                            if (entity != null) {
                                if (!entity.isRemoved() && entity.hasPassenger(entityIn)) {
                                    return;
                                }
                                entityIn.stopRiding();
                            }

                            if (!entityIn.isRemoved() && !(entityIn instanceof net.minecraftforge.entity.PartEntity)) {
                                level.guardEntityTick(level::tickNonPassenger, entityIn);
                            }
                        }
                    }
                }
            }finally {
                taskCounter.getAndDecrement();
            }
        };

        if (entityIn instanceof Player){
            task.run();
            return;
        }

        entityThreadPool.execute(task);
    }

    public static void shutdownAllExecutors(){
        worldThreadPool.shutdownNow();
        entityThreadPool.shutdownNow();
        tileEntityThreadPool.shutdownNow();
        miscThreadPool.shutdownNow();
    }

    static {
        worldThreadPool = Executors.newCachedThreadPool(task -> {
            final TickThreadImpl workerThread = new TickThreadImpl(task,"MillaCat World Worker Thread - "+idGenerator.getAndIncrement());
            //workerThread.setContextClassLoader(MinecraftServer.class.getClassLoader());
            workerThread.setPriority(7);
            return workerThread;
        });

        entityThreadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),task -> {
            final TickThreadImpl workerThread = new TickThreadImpl(task,"MillaCat Entity Worker Thread - "+idGenerator.getAndIncrement());
            //workerThread.setContextClassLoader(MinecraftServer.class.getClassLoader());
            workerThread.setPriority(3);
            return workerThread;
        });

        miscThreadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),task -> {
           final TickThreadImpl workerThread = new TickThreadImpl(task,"MillaCat Misc Worker Thread - "+idGenerator.getAndIncrement());
           //workerThread.setContextClassLoader(MinecraftServer.class.getClassLoader());
           workerThread.setPriority(3);
           return workerThread;
        });

        tileEntityThreadPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors(),pool -> {
            final ForkJoinWorkerThread workerThread = new TickForkJoinWorker(pool){};
            workerThread.setName("MillaCat Tile Worker Thread - "+idGenerator.getAndIncrement());
            //workerThread.setContextClassLoader(MinecraftServer.class.getClassLoader());
            workerThread.setPriority(3);
            return workerThread;
        },null,true);
    }
}
