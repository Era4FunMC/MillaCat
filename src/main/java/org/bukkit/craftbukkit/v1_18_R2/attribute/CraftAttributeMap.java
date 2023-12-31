package org.bukkit.craftbukkit.v1_18_R2.attribute;

import catserver.server.BukkitInjector;
import com.google.common.base.Preconditions;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraftforge.registries.ForgeRegistries;
import org.bukkit.Registry;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.craftbukkit.v1_18_R2.util.CraftNamespacedKey;

public class CraftAttributeMap implements Attributable {

    private final AttributeMap handle;

    public CraftAttributeMap(AttributeMap handle) {
        this.handle = handle;
    }

    @Override
    public AttributeInstance getAttribute(Attribute attribute) {
        Preconditions.checkArgument(attribute != null, "attribute");
        net.minecraft.world.entity.ai.attributes.AttributeInstance nms = handle.getInstance(toMinecraft(attribute));

        return (nms == null) ? null : new CraftAttributeInstance(nms, attribute);
    }

    public static net.minecraft.world.entity.ai.attributes.Attribute toMinecraft(Attribute attribute) {
        net.minecraft.resources.ResourceLocation resourceLocation = BukkitInjector.attributeToNameMap.get(attribute);
        if (resourceLocation == null) {
            return net.minecraft.core.Registry.ATTRIBUTE.get(CraftNamespacedKey.toMinecraft(attribute.getKey())); // Minecraft
        } else {
            return net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(resourceLocation); // Mod
        }
    }

    public static Attribute fromMinecraft(String nms) {
        Attribute attribute = Registry.ATTRIBUTE.get(CraftNamespacedKey.fromString(nms));
        if (attribute != null) {
            return attribute; // Minecraft
        } else {
            return BukkitInjector.nameToAttributeMap.get(net.minecraft.resources.ResourceLocation.tryParse(nms)); // Mod
        }
    }
}
