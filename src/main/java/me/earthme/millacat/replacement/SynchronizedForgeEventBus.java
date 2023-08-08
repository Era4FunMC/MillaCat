package me.earthme.millacat.replacement;

import net.minecraftforge.eventbus.api.*;

import java.util.function.Consumer;

public class SynchronizedForgeEventBus implements IEventBus {
    private final IEventBus parent;
    private final Object lock = new Object();

    public SynchronizedForgeEventBus(IEventBus parent) {
        this.parent = parent;
    }

    @Override
    public void register(Object target) {
        synchronized (this.lock){
            this.parent.register(target);
        }
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addListener(consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addListener(priority,consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addListener(priority,receiveCancelled,consumer);
        }
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addListener(priority,receiveCancelled,eventType,consumer);
        }
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addGenericListener(genericClassFilter,consumer);
        }
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addGenericListener(genericClassFilter,priority,consumer);
        }
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addGenericListener(genericClassFilter,priority,receiveCancelled,consumer);
        }
    }

    @Override
    public <T extends GenericEvent<? extends F>, F> void addGenericListener(Class<F> genericClassFilter, EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        synchronized (this.lock){
            this.parent.addGenericListener(genericClassFilter,priority,receiveCancelled,eventType,consumer);
        }
    }

    @Override
    public void unregister(Object object) {
        synchronized (this.lock){
            this.parent.unregister(object);
        }
    }

    @Override
    public boolean post(Event event) {
        synchronized (this.lock){
            return this.parent.post(event);
        }
    }

    @Override
    public boolean post(Event event, IEventBusInvokeDispatcher wrapper) {
        synchronized (this.lock){
            return this.parent.post(event,wrapper);
        }
    }

    @Override
    public void shutdown() {
        synchronized (this.lock){
            this.parent.shutdown();
        }
    }

    @Override
    public void start() {
        synchronized (this.lock){
            this.parent.start();
        }
    }
}
