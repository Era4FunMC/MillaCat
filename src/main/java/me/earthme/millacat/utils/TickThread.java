package me.earthme.millacat.utils;

public interface TickThread {
    static boolean isTickThread(){
        return Thread.currentThread() instanceof TickThread;
    }
}
