/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.volmit.iris.util.parallel;

import com.volmit.iris.Iris;
import com.volmit.iris.util.collection.KList;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@SuppressWarnings("ALL")
public class BurstExecutor {
    private final ExecutorService executor;
    @Getter
    private final KList<Future<?>> futures;
    @Setter
    private boolean multicore = true;

    public BurstExecutor(ExecutorService executor, int burstSizeEstimate) {
        this.executor = executor;
        futures = new KList<Future<?>>(burstSizeEstimate);
    }

    @SuppressWarnings("UnusedReturnValue")
    public Future<?> queue(Runnable r) {
        if (!multicore) {
            r.run();
            return CompletableFuture.completedFuture(null);
        }

        synchronized (futures) {

            Future<?> c = executor.submit(r);
            futures.add(c);
            return c;
        }
    }

    public BurstExecutor queue(List<Runnable> r) {
        if (!multicore) {
            for (Runnable i : r) {
                i.run();
            }

            return this;
        }

        synchronized (futures) {
            for (Runnable i : r) {
                Future<?> c = executor.submit(i);
                futures.add(c);
            }
        }

        return this;
    }

    public BurstExecutor queue(Runnable[] r) {
        if (!multicore) {
            for (Runnable i : r) {
                i.run();
            }

            return this;
        }

        synchronized (futures) {
            for (Runnable i : r) {
                Future<?> c = executor.submit(i);
                futures.add(c);
            }
        }

        return this;
    }

    public void complete() {
        if (!multicore) {
            return;
        }

        Throwable failure = null;
        synchronized (futures) {
            if (futures.isEmpty()) {
                return;
            }

            // Join EVERY task before deciding anything: complete() is a stage
            // barrier (R12) — the shared hunk may only be handed on once all
            // parallel work finished, including the failing tasks.
            for (Future<?> i : futures) {
                try {
                    i.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (failure == null) failure = e;
                } catch (ExecutionException e) {
                    if (failure == null) failure = e.getCause();
                } catch (Throwable t) {
                    // ForkJoinTask.get() rethrows unchecked task exceptions
                    // DIRECTLY (reconstructed via reflection, not wrapped in
                    // ExecutionException like FutureTask does) — they must not
                    // escape this loop or the barrier and the futures cleanup
                    // below are skipped.
                    if (failure == null) failure = t;
                }
            }

            // Always clear: a retried complete() (see Mantle.unloadTectonicPlate's
            // catch path) must be a no-op, never a re-throw of a stale failure,
            // and futures from a failed burst must not be re-awaited forever.
            futures.clear();
        }

        if (failure != null) {
            // Report first (callers that catch upstream keep their Sentry
            // telemetry), then propagate unwrapped — the single-core path has
            // always thrown from queue(), and swallowing here made multicore
            // hide generation/unload failures (R26: silent plate-unload stuck
            // residency).
            Iris.reportError(failure);
            if (failure instanceof RuntimeException r) throw r;
            if (failure instanceof Error er) throw er;
            throw new RuntimeException(failure);
        }
    }
}
