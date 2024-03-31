package me.earthme.millacat.hook;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.longs.Long2BooleanLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import me.earthme.millacat.concurrent.SplittingTraverseTask;
import me.earthme.millacat.concurrent.thread.TickForkJoinWorker;
import me.earthme.millacat.concurrent.thread.TickThreadImpl;
import me.earthme.millacat.utils.Locatable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.v1_18_R2.SpigotTimings;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ServerThreadingHooks {
    private static final Logger logger = LogManager.getLogger();
    private static final AtomicInteger idGenerator = new AtomicInteger();
    private static final ForkJoinPool tileEntityThreadPool;
    private static final ExecutorService entityThreadPool;
    private static final ExecutorService worldThreadPool;
    private static final ExecutorService miscThreadPool;
    private static final Map<ServerLevel,AtomicInteger> worldTaskCount = Maps.newConcurrentMap();
    private static final Map<ServerLevel, Queue<Runnable>> worldCallbackTasks = Maps.newConcurrentMap();
    private static final AtomicInteger worldTickTaskCount = new AtomicInteger();

    public static void awaitAllTasks(){
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

        final AtomicInteger taskCounter = worldTaskCount.get(levelIn);
        for (List<Locatable> split : mergePointsWithinDistance(groupByChunk(levelIn.blockEntityTickers)).values()){
            taskCounter.getAndIncrement();
            tileEntityThreadPool.execute(()->{
                try {
                    for (Locatable l : split){
                        final TickingBlockEntity tickingblockentity = ((TickingBlockEntity) l);

                        if (tickingblockentity.isRemoved()) {
                            levelIn.blockEntityTickers.remove(tickingblockentity);
                        } else if (levelIn.shouldTickBlocksAt(ChunkPos.asLong(tickingblockentity.getPos()))) {
                            tickingblockentity.tick();
                        }
                    }
                }finally {
                    taskCounter.getAndDecrement();
                }
            });
        }

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

    public static Map<ChunkPos, List<Locatable>> groupByChunk(Collection<? extends Locatable> dataList) {
        return dataList.stream().collect(Collectors.groupingBy(Locatable::getChunkKey, LinkedHashMap::new, Collectors.toList()));
    }

    public static List<ChunkPos> getNeighbors(ChunkPos pos) {
        List<ChunkPos> neighbors = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    neighbors.add(new ChunkPos(pos.x + dx, pos.z + dz));
                }
            }
        }
        return neighbors;
    }

    public static Map<ChunkPos, List<Locatable>> mergePointsWithinDistance(Map<ChunkPos, List<Locatable>> map) {
        Map<ChunkPos, List<Locatable>> result = new HashMap<>();
        Set<Set<ChunkPos>> mergedPointSets = new HashSet<>();

        for (ChunkPos startPoint : map.keySet()) {
            if (!isMerged(startPoint, mergedPointSets)) {
                Set<ChunkPos> mergedPoints = bfs(startPoint, map);
                mergedPointSets.add(mergedPoints);

                List<Locatable> mergedData = new ArrayList<>();
                for (ChunkPos p : mergedPoints) {
                    mergedData.addAll(map.get(p));
                }

                ChunkPos centroid = calculateCentroid(mergedPoints);
                result.put(centroid, mergedData);
            }
        }

        return result;
    }

    private static boolean isMerged(ChunkPos point, Set<Set<ChunkPos>> mergedPointSets) {
        for (Set<ChunkPos> mergedPoints : mergedPointSets) {
            if (mergedPoints.contains(point)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ChunkPos> bfs(ChunkPos startPoint, Map<ChunkPos, List<Locatable>> map) {
        Set<ChunkPos> visited = new HashSet<>();
        Queue<ChunkPos> queue = new LinkedList<>();
        Set<ChunkPos> mergedPoints = new HashSet<>();
        double threshold = 1.5;

        queue.offer(startPoint);
        visited.add(startPoint);

        while (!queue.isEmpty()) {
            ChunkPos currentPoint = queue.poll();
            mergedPoints.add(currentPoint);

            for (ChunkPos neighbor : map.keySet()) {
                if (!visited.contains(neighbor) && calculateDistance(currentPoint, neighbor) < threshold) {
                    visited.add(neighbor);
                    queue.offer(neighbor);
                }
            }
        }

        return mergedPoints;
    }

    private static double calculateDistance(ChunkPos p1, ChunkPos p2) {
        // Calculate distance between two points
        return Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.z - p2.z, 2));
    }


    private static ChunkPos calculateCentroid(Set<ChunkPos> points) {
        int totalX = 0;
        int totalZ = 0;

        for (ChunkPos p : points) {
            totalX += p.x;
            totalZ += p.z;
        }

        int centroidX = totalX / points.size();
        int centroidY = totalZ / points.size();

        return new ChunkPos(centroidX, centroidY);
    }



    public static Collection<List<Locatable>> remerge(Map<Long, List<Locatable>> map) {
        Map<Long, List<Locatable>> aggregatedMap = new HashMap<>();
        Set<Long> visited = new HashSet<>();

        for (Long chunkPos : map.keySet()) {
            if (visited.contains(chunkPos)) {
                continue; // 如果已访问过该 Chunk，则跳过
            }

            List<Locatable> locatables = new ArrayList<>();
            Queue<ChunkPos> queue = new LinkedList<>();
            queue.offer(new ChunkPos((int) (chunkPos >> 32), (int) (chunkPos & 0xffffffffL)));
            visited.add(chunkPos);

            while (!queue.isEmpty()) {
                ChunkPos currentPos = queue.poll();
                locatables.addAll(map.get(chunkPos));

                for (ChunkPos neighbor : getNeighbors(currentPos)) {
                    Long neighborKey =  (((long) neighbor.x << 32) | neighbor.z);
                    if (!visited.contains(neighborKey) && map.containsKey(neighborKey) &&
                            distance(currentPos,neighbor) <= 2) {
                        queue.offer(neighbor);
                        visited.add(neighborKey);
                    }
                }
            }

            long newKey = (chunkPos >> 32) << 32 | (chunkPos & 0xffffffffL); // 更新 key 为最左上角的 ChunkPos
            aggregatedMap.put(newKey, locatables);
        }

        return aggregatedMap.values();
    }


    private static double distance(ChunkPos point1, ChunkPos point2) {
        int deltaX = point2.x - point1.x;
        int deltaZ = point2.z - point1.z;

        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

    public static <T> void postRunScheduledTicks(LevelTicks<T> ticks, BiConsumer<BlockPos, T> action){
        final List<ScheduledTick<T>> scheduledTicks = new ArrayList<>();
        final AtomicInteger taskCounter = worldTaskCount.get(ticks.level);

        ticks.accessLock.writeLock().lock();
        try {
            while(!ticks.toRunThisTick.isEmpty()) {
                ScheduledTick<T> scheduledTick = ticks.toRunThisTick.poll();
                if (!ticks.toRunThisTickSet.isEmpty()) {
                    ticks.toRunThisTickSet.remove(scheduledTick);
                }

                scheduledTicks.add(scheduledTick);
                ticks.alreadyRunThisTick.add(scheduledTick);
            }
        }finally {
            ticks.accessLock.writeLock().unlock();
        }

        final List<Runnable> toRun = new ArrayList<>();

        for (List<Locatable> split : mergePointsWithinDistance(groupByChunk(scheduledTicks)).values()){
            taskCounter.getAndIncrement();
            toRun.add(() -> {
                try {
                    for (Locatable l : split){
                        final ScheduledTick<T> scheduledTick = ((ScheduledTick<T>) l);

                        action.accept(scheduledTick.pos(), scheduledTick.type());
                    }
                }finally {
                    taskCounter.getAndDecrement();
                }
            });
        }

        for (Runnable task : toRun){
            miscThreadPool.execute(task);
        }

        worldCallbackTasks.get(ticks.level).offer(ticks::cleanupAfterTick);
    }

    public static void postEntityTicKTask(ServerLevel level){
        final AtomicInteger taskCounter = worldTaskCount.get(level);
        final List<Runnable> toRun = new ArrayList<>();

        for (List<Locatable> split : mergePointsWithinDistance(groupByChunk(level.entityTickList.entities)).values()){
            taskCounter.getAndIncrement();
            toRun.add(() -> {
                try {
                    for (Locatable entityInO : split){
                        Entity entityIn = ((Entity) entityInO);
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
                    }
                }finally {
                    taskCounter.getAndDecrement();
                }
            });
        }

        for (Runnable task : toRun){
            entityThreadPool.execute(task);
        }
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
