package me.earthme.millacat.concurrent.thread;

import me.earthme.millacat.utils.TickThread;

public class TickThreadImpl extends Thread implements TickThread {
    public TickThreadImpl(Runnable task){
        super(task);
    }

    public TickThreadImpl(Runnable task,String name){
        super(task,name);
    }
}
