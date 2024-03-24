package me.earthme.millacat.utils;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

@Deprecated(forRemoval = true)
public class SplittedHashSet<V extends Locatable> {
    private final Map<Long, V> dataMap = new LinkedHashMap<>();
    private final Map<Long, Set<V>> splitMap = new LinkedHashMap<>();

    public synchronized void put(V data) {
        final long key = getChunkKey(data);
        dataMap.put(key, data);
        updateSplitMap(key, data);
    }

    public synchronized boolean contains(V data){
        return this.dataMap.containsKey(data);
    }

    public synchronized V remove(V value){
        final long key = getChunkKey(value);

        return this.remove(key);
    }

    private V remove(long key) {
        V removedData = dataMap.remove(key);
        if (removedData != null) {
            Set<V> splitSet = splitMap.get(key);
            if (splitSet != null) {
                splitSet.remove(removedData);
            }
        }
        return removedData;
    }

    public synchronized void refresh() {
        splitMap.clear();
        for (Map.Entry<Long, V> entry : dataMap.entrySet()) {
            updateSplitMap(entry.getKey(), entry.getValue());
        }
    }

    public synchronized void updatePointLocation(V newData) {
        final long key = getChunkKey(newData);
        remove(key);
        put(newData); // Re-insert with updated location
    }

    public synchronized Collection<Pair<Long, Collection<V>>> getAllSplit() {
        Collection<Pair<Long, Collection<V>>> result = new ObjectArrayList<>();

        for (Map.Entry<Long, Set<V>> entry : splitMap.entrySet()) {
            result.add(Pair.of(entry.getKey(), new HashSet<>(entry.getValue())));
        }

        return result;
    }

    private void updateSplitMap(Long key, V data) {
        if (data instanceof Locatable) {
            Locatable locatableData = data;
            int chunkX = locatableData.chunkX();
            int chunkZ = locatableData.chunkZ();

            Long chunkKey = getChunkKey(data);

            if (!splitMap.containsKey(chunkKey)) {
                splitMap.put(chunkKey, new HashSet<>());
            }

            splitMap.get(chunkKey).add(data);
        }
    }

    private long getChunkKey(V data) {
        // You can customize how the chunk key is generated based on chunk coordinates
        return ChunkPos.asLong(data.chunkX(),data.chunkZ());
    }
}
