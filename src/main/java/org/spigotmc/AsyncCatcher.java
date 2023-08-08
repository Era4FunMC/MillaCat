package org.spigotmc;

import me.earthme.millacat.utils.TickThread;
import net.minecraft.server.MinecraftServer;

public class AsyncCatcher {
    public static boolean enabled = true;

    public static void catchOp(String reason) {
        if (enabled && Thread.currentThread() != MinecraftServer.getServer().serverThread && !TickThread.isTickThread()) {
            throw new IllegalStateException("Asynchronous " + reason + "!");
        }
    }
}
