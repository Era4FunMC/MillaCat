package me.earthme.millacat.concurrent;

import org.jetbrains.annotations.NotNull;

import java.util.Spliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.Consumer;

public class SplittingTraverseTask<E> extends RecursiveAction {
    private final Spliterator<E> spliterator;
    private final Consumer<E> action;
    private final long threshold;

    public SplittingTraverseTask(@NotNull Iterable<E> iterable, int threads, Consumer<E> action){
        this.spliterator = iterable.spliterator();
        this.action = action;
        this.threshold = Math.max((int)iterable.spliterator().estimateSize() / threads,10);
    }

    private SplittingTraverseTask(Spliterator<E> spliterator, Consumer<E> action, long t) {
        this.spliterator = spliterator;
        this.action = action;
        this.threshold = t;
    }

    @Override
    protected void compute() {
        if (this.spliterator.getExactSizeIfKnown() <= this.threshold) {
            this.spliterator.forEachRemaining(this.action);
        } else {
            final Spliterator<E> split = this.spliterator.trySplit();
            if (split != null){
                new SplittingTraverseTask<>(split, this.action, this.threshold).fork();
                new SplittingTraverseTask<>(this.spliterator, this.action, this.threshold).compute();
                return;
            }
            new SplittingTraverseTask<>(this.spliterator, this.action, this.threshold).compute();
        }
    }

    public static <T> SplittingTraverseTask<T> createNewSubmitted(Consumer<T> task, Iterable<T> input, @NotNull ForkJoinPool worker){
        return (SplittingTraverseTask<T>) worker.submit(new SplittingTraverseTask<>(input, worker.getParallelism(), task));
    }
}

