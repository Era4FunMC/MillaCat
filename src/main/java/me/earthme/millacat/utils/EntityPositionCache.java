package me.earthme.millacat.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public class EntityPositionCache {
    private final double x;
    private final double y;
    private final double z;
    private final Entity currentEntity;

    public EntityPositionCache(@NotNull Entity entity){
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        this.currentEntity = entity;
    }

    public Entity getCurrentEntity() {
        return this.currentEntity;
    }

    public double getX() {
        return this.x;
    }

    public double getY() {
        return this.y;
    }

    public double getZ() {
        return this.z;
    }

    public double distanceToSqr(double x, double y, double z) {
        double d3 = this.x - x;
        double d4 = this.y - y;
        double d5 = this.z - z;

        return d3 * d3 + d4 * d4 + d5 * d5;
    }

    public double distanceToSqr(Entity entity) {
        return this.distanceToSqr(entity.position());
    }

    public double distanceToSqr(Vec3 vector) {
        double d0 = this.x - vector.x;
        double d1 = this.y - vector.y;
        double d2 = this.z - vector.z;

        return d0 * d0 + d1 * d1 + d2 * d2;
    }

    public double distanceToSqr(EntityPositionCache entityPositionCache) {
        return this.distanceToSqr(entityPositionCache.getX(),entityPositionCache.getY(),entityPositionCache.getZ());
    }
}
