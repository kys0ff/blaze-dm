/*
 * Copyright (c) 2016—2021 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.torrent.messaging;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-connection adaptive limit on the number of outstanding (pipelined) block requests.
 *
 * <p>TOR-5: upstream 1.10 caps in-flight requests to a single fixed value for every peer
 * ({@code Config.getMaxOutstandingRequests()}, default 250) — see {@code RequestProducer.produce}
 * line 105. A fixed depth is wrong in both directions: on a fast, low-latency link it can under-fill
 * the pipe (idle bandwidth), while on a slow, high-latency link a deep queue holds more bytes in
 * flight than the path can carry, inflating the effective piece-receive time that drives assignment
 * churn. The right depth is the bandwidth-delay product — the number of block-sized bytes that fill
 * the pipe for exactly one round trip:</p>
 *
 * <pre>
 *   depth &approx; ceil(throughput [bytes/s] &times; RTT [s] / blockSize [bytes])
 * </pre>
 *
 * <p>This class measures throughput and RTT per connection — fed by {@link RequestProducer}
 * (request issue + the per-cycle sample) and {@link PieceConsumer} (block arrival) — and moves the
 * depth toward that target, always bounded by {@code [minDepth, maxDepth]}. It begins in slow start
 * (doubling the window while the window itself, not the source, is the bottleneck), then tracks the
 * BDP with additive increase / decrease toward the target so transient sample noise does not cause
 * oscillation.</p>
 *
 * <p>Instances are touched only from the owning connection's message-processing cycle — the same
 * single-threaded assumption {@link ConnectionState} already makes for its pending-request
 * bookkeeping — so the fields are plain, not atomic.</p>
 *
 * @since 1.10-blaze
 */
public final class RequestWindow {

    private static final double RTT_ALPHA = 0.2;
    private static final double THROUGHPUT_ALPHA = 0.2;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final int minDepth;
    private final int maxDepth;
    private final int blockBytes;

    private final Map<Object, Long> sentAtNanos = new HashMap<>();

    private boolean slowStart = true;
    private int currentDepth;

    private double rttSeconds;                 // EWMA of request -> response latency, 0 until first sample
    private double throughputBytesPerSec;      // EWMA of delivered bytes/s, 0 until first sample

    private long lastCycleNanos = -1L;
    private long lastDownloadedBytes;

    /**
     * @param minDepth  lower bound on outstanding requests, must be at least 1
     * @param maxDepth  upper bound (the fixed depth this replaces acts as the ceiling), at least {@code minDepth}
     * @param blockBytes representative block size used to convert a byte BDP into a request count, at least 1
     */
    public RequestWindow(int minDepth, int maxDepth, int blockBytes) {
        this.minDepth = Math.max(1, Math.min(minDepth, maxDepth));
        this.maxDepth = Math.max(this.minDepth, maxDepth);
        this.blockBytes = Math.max(1, blockBytes);
        this.currentDepth = this.minDepth;
    }

    /**
     * Records that a block request was just handed to the peer, so its round trip can be timed when
     * the matching block arrives.
     *
     * @param key       the request key (see {@link Mapper#buildKey(int, int, int)})
     * @param nowNanos  monotonic timestamp of issue
     */
    public void onRequestIssued(Object key, long nowNanos) {
        sentAtNanos.put(key, nowNanos);
    }

    /**
     * Records the arrival of a block that was outstanding, folding the measured latency into the RTT
     * estimate. Silent for an unknown key (e.g. an unsolicited block, already handled elsewhere).
     *
     * @param key       the request key that was issued earlier
     * @param nowNanos  monotonic timestamp of arrival
     */
    public void onBlockArrived(Object key, long nowNanos) {
        Long sent = sentAtNanos.remove(key);
        if (sent == null) {
            return;
        }
        long deltaNanos = nowNanos - sent;
        if (deltaNanos > 0) {
            double sample = deltaNanos / NANOS_PER_SECOND;
            rttSeconds = rttSeconds <= 0 ? sample : rttSeconds + RTT_ALPHA * (sample - rttSeconds);
        }
    }

    /**
     * Drops the timing of a request that will never be answered (its connection is being torn down),
     * so the pending-request map does not retain stale entries across a reset.
     */
    public void onRequestDropped(Object key) {
        sentAtNanos.remove(key);
    }

    /**
     * Advances the estimator once per connection processing cycle: samples throughput from the change
     * in delivered bytes, then moves the depth toward the bandwidth-delay product.
     *
     * @param nowNanos             monotonic timestamp of this cycle
     * @param totalDownloadedBytes cumulative bytes delivered on this connection (non-decreasing)
     * @param pendingCount         outstanding requests at this instant
     * @param moreQueued           whether more requests were ready to send but held back by the window
     */
    public void onCycle(long nowNanos, long totalDownloadedBytes, int pendingCount, boolean moreQueued) {
        if (lastCycleNanos >= 0) {
            double dtSeconds = (nowNanos - lastCycleNanos) / NANOS_PER_SECOND;
            if (dtSeconds > 0) {
                long deltaBytes = Math.max(0L, totalDownloadedBytes - lastDownloadedBytes);
                double instantaneous = deltaBytes / dtSeconds;
                throughputBytesPerSec = throughputBytesPerSec <= 0
                        ? instantaneous
                        : throughputBytesPerSec + THROUGHPUT_ALPHA * (instantaneous - throughputBytesPerSec);
            }
        }
        lastCycleNanos = nowNanos;
        lastDownloadedBytes = totalDownloadedBytes;

        if (slowStart) {
            // Grow aggressively only while the window (not the peer) is the limiting factor.
            if (pendingCount >= currentDepth && moreQueued) {
                currentDepth = Math.min(currentDepth * 2, maxDepth);
            } else {
                slowStart = false;
            }
        } else if (rttSeconds > 0 && throughputBytesPerSec > 0) {
            double bdpBytes = throughputBytesPerSec * rttSeconds;
            int target = clamp((int) Math.ceil(bdpBytes / blockBytes));
            if (target > currentDepth) {
                currentDepth = Math.min(currentDepth + 1, target);   // additive increase toward the BDP
            } else if (target < currentDepth) {
                currentDepth = target;                               // step down toward the BDP
            }
        } else if (pendingCount >= currentDepth && moreQueued) {
            // Not enough samples to trust a BDP yet, but the pipe looks full — probe upward gently.
            currentDepth = Math.min(currentDepth + 1, maxDepth);
        }
        currentDepth = clamp(currentDepth);
    }

    /**
     * Clears all accumulated measurements and restarts in slow start. Called when a connection is
     * reset so a fresh assignment is judged on its own throughput, not the previous burst's.
     */
    public void reset() {
        sentAtNanos.clear();
        slowStart = true;
        currentDepth = minDepth;
        rttSeconds = 0;
        throughputBytesPerSec = 0;
        lastCycleNanos = -1L;
        lastDownloadedBytes = 0;
    }

    /**
     * @return the current bound on outstanding requests for this connection
     * @since 1.10-blaze
     */
    public int depth() {
        return currentDepth;
    }

    /**
     * @return the smoothed round-trip estimate in milliseconds, or 0 until the first sample
     * @since 1.10-blaze
     */
    public double rttMillis() {
        return rttSeconds * 1000.0;
    }

    /**
     * @return the smoothed throughput estimate in bytes per second, or 0 until the first sample
     * @since 1.10-blaze
     */
    public double throughputBytesPerSec() {
        return throughputBytesPerSec;
    }

    /**
     * @return true while the window is still doubling each saturated cycle
     * @since 1.10-blaze
     */
    public boolean isSlowStart() {
        return slowStart;
    }

    private int clamp(int value) {
        return Math.max(minDepth, Math.min(value, maxDepth));
    }
}
