package me.earthme.millacat.concurrent.thread;

import me.earthme.millacat.utils.TickThread;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

public class TickForkJoinWorker extends ForkJoinWorkerThread implements TickThread {
    protected TickForkJoinWorker(ForkJoinPool pool) {
        super(pool);
    }
}
