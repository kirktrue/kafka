/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals.events;

import org.apache.kafka.clients.consumer.internals.AsyncKafkaConsumer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.utils.LogContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The {@code CompletableEventReaper} is responsible for tracking any {@link CompletableEvent}s that were processed,
 * making sure to reap them if they complete normally or pass their deadline. This is done so that we enforce an upper
 * bound on the amount of time the event logic will execute.
 */
public class CompletableEventReaper<T extends CompletableEvent<?>> {

    private final Logger log;

    /**
     * List of tracked events that we are candidates to expire or cancel when reviewed.
     */
    private final List<T> tracked;

    /**
     * {@link Predicate} to check if a {@link Future} is {@link Future#isDone() done}.
     */
    private final Predicate<T> doneFilter = e -> e.future().isDone();

    public CompletableEventReaper(LogContext logContext) {
        this.log = logContext.logger(CompletableEventReaper.class);
        this.tracked = new ArrayList<>();
    }

    /**
     * Adds a new {@link CompletableEvent event} to track for later completion/expiration.
     *
     * @param event Event to track
     */
    public void add(T event) {
        tracked.add(Objects.requireNonNull(event, "Event to track must be non-null"));
    }

    /**
     * This method "completes" any {@link CompletableEvent}s that have either expired or completed normally. So this
     * is a two-step process:
     *
     * <ol>
     *     <li>
     *         For each tracked event which has exceeded its {@link CompletableEvent#deadlineMs() deadline}, an
     *         instance of {@link TimeoutException} is created and passed to
     *         {@link CompletableFuture#completeExceptionally(Throwable)}.
     *     </li>
     *     <li>
     *         For each tracked event of which its {@link CompletableEvent#future() future} is already in the
     *         {@link CompletableFuture#isDone() done} state, it will be removed from the list of tracked events.
     *     </li>
     * </ol>
     *
     * <p/>
     *
     * This method should be called at regular intervals, based upon the needs of the resource that owns the reaper.
     *
     * @param currentTimeMs <em>Current</em> time with which to compare against the
     *                      <em>{@link CompletableEvent#deadlineMs() expiration time}</em>
     */
    public void reapExpiredAndCompleted(long currentTimeMs) {
        log.trace("Reaping expired events");

        Consumer<CompletableEvent<?>> completeEvent = e -> {
            TimeoutException error = new TimeoutException(String.format("%s could not be completed within its timeout", e.getClass().getSimpleName()));
            long pastDueMs = currentTimeMs - e.deadlineMs();
            log.debug("Completing event {} exceptionally since it expired {} ms ago", e, pastDueMs);
            CompletableFuture<?> f = e.future();
            f.completeExceptionally(error);
        };

        // First, complete (exceptionally) any events that have passed their deadline AND aren't already complete.
        tracked
                .stream()
                .filter(not(doneFilter))
                .filter(e -> currentTimeMs > e.deadlineMs())
                .forEach(completeEvent);

        // Second, remove any events that are already complete, just to make sure we don't hold references. This will
        // include any events that finished successfully as well as any events we just completed exceptionally above.
        tracked.removeIf(doneFilter);

        log.trace("Finished reaping expired events");
    }

    /**
     * It is possible for the {@link AsyncKafkaConsumer#close() consumer to close} before completing the processing of
     * all the events in the queue. In this case, we need to {@link Future#cancel(boolean) cancel} any remaining events.
     *
     * <p/>
     *
     * Check each of the {@link #add(CompletableEvent) previously-added} {@link CompletableEvent completable events},
     * and for any that are incomplete, {@link Future#cancel(boolean) cancel} them. Also check the core event queue
     * for any incomplete events and likewise cancel them.
     *
     * <p/>
     *
     * <em>Note</em>: because this is called in the context of {@link AsyncKafkaConsumer#close() closing consumer},
     * don't take the deadline into consideration, just close it regardless.
     *
     * @param events Events from a queue that have not yet been tracked that also need to be reviewed
     */
    public void reapIncomplete(Collection<T> events) {
        log.trace("Reaping incomplete events");

        Objects.requireNonNull(events, "Event queue to reap must be non-null");

        Consumer<CompletableEvent<?>> completeEvent = e -> {
            log.debug("Canceling event {} since the consumer is closing", e);
            CompletableFuture<?> f = e.future();
            f.cancel(true);
        };

        tracked
                .stream()
                .filter(not(doneFilter))
                .forEach(completeEvent);
        tracked.clear();

        events
                .stream()
                .filter(not(doneFilter))
                .forEach(completeEvent);

        log.trace("Finished reaping incomplete events");
    }

    int size() {
        return tracked.size();
    }

    private Predicate<T> not(Predicate<T> p) {
        return p.negate();
    }
}