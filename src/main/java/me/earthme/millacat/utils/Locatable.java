package me.earthme.millacat.utils;

import net.minecraft.world.level.ChunkPos;

public interface Locatable {
    int chunkX();

    int chunkZ();

    default ChunkPos getChunkKey(){
        return new ChunkPos(this.chunkX(),this.chunkZ());
    }
}
