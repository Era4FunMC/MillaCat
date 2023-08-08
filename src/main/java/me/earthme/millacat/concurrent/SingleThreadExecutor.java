package me.earthme.millacat.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class SingleThreadExecutor extends Thread implements Executor {
    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final AtomicBoolean shouldRun = new AtomicBoolean(true);

    @Override
    public void run(){
        while (this.shouldRun.get()){
            try {
                this.tasks.take().run();
            }catch (InterruptedException e) {
                //Do nothing
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    public void stopRun(){
        this.shouldRun.set(false);
        this.interrupt();
    }

    @Override
    public void execute(Runnable command){
        this.tasks.offer(command);
    }
}
