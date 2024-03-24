package me.earthme.millacat.utils;

import net.minecraft.world.level.ChunkPos;

public interface Locatable {
    int chunkX();

    int chunkZ();

    default long getChunkKey(){
        return ChunkPos.asLong(this.chunkX(),this.chunkZ());
    }
}
