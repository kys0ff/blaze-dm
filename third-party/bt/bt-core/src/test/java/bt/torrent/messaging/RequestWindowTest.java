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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TOR-5: the adaptive request window must grow only while it is itself the bottleneck, then track
 * the bandwidth-delay product — ceil(throughput x RTT / blockSize) — staying inside its bounds.
 * These cases drive {@link RequestWindow} with fully synthetic time/byte samples, so the response to
 * BDP (the thing a fixed depth can never express) is proven without depending on any real network.
 */
public class RequestWindowTest {

    private static final int BLOCK = 16 * 1024;         // 16 KiB, matches Config.transferBlockSize
    private static final long SEC = 1_000_000_000L;     // one second in nanos

    @Test
    public void startsAtMinDepthInSlowStart() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        assertEquals(8, w.depth());
        assertTrue(w.isSlowStart());
    }

    @Test
    public void slowStartDoublesOnlyWhileTheWindowIsTheBottleneck() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        // Saturated cycles (pending >= depth and more queued) double the window each time...
        w.onCycle(0L, 100L, 8, true);
        assertEquals(16, w.depth());
        w.onCycle(SEC, 200L, 16, true);
        assertEquals(32, w.depth());
        w.onCycle(2 * SEC, 300L, 32, true);
        assertEquals(64, w.depth());

        // ...until it reaches the ceiling and holds there.
        for (int i = 0; i < 8; i++) {
            w.onCycle((3 + i) * SEC, 400L + i, 250, true);
        }
        assertEquals(250, w.depth());

        // A cycle where the source (not the window) is the limit hands off to BDP tracking.
        w.onCycle(20 * SEC, 500L, 10, true);   // pending (10) < depth (250)
        assertFalse(w.isSlowStart());
        assertEquals(250, w.depth());
    }

    @Test
    public void nonSaturatedFirstCycleEndsSlowStartAtMinDepth() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        w.onCycle(0L, 0L, 0, false);           // nothing pending, nothing queued -> source-bound
        assertFalse(w.isSlowStart());
        assertEquals(8, w.depth());
    }

    @Test
    public void congestionAvoidanceTracksBdpWithAdditiveIncrease() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        Object key = new Object();

        // One clean round trip of 100 ms becomes the RTT estimate (first sample, no smoothing yet).
        w.onRequestIssued(key, 0L);
        w.onBlockArrived(key, 100_000_000L);
        assertEquals(100.0, w.rttMillis(), 0.001);

        w.onCycle(0L, 0L, 0, false);           // establish the time baseline and leave slow start
        assertFalse(w.isSlowStart());

        // 32 MiB/s with a 100 ms RTT -> BDP = 3.35 MB = 205 blocks; target is far above depth (8),
        // so the window should nudge up by exactly one per cycle (additive increase), not snap.
        w.onCycle(SEC, 32L * 1024 * 1024, 8, true);
        assertEquals(9, w.depth());
        assertEquals(32.0, w.throughputBytesPerSec() / (1024 * 1024), 0.5);

        w.onCycle(2 * SEC, 64L * 1024 * 1024, 9, true);
        assertEquals(10, w.depth());
    }

    @Test
    public void shrinkingBdpBringsDepthBackToLowerBound() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        Object key = new Object();
        w.onRequestIssued(key, 0L);
        w.onBlockArrived(key, 100_000_000L);   // rtt ~100ms
        w.onCycle(0L, 0L, 0, false);           // exit slow start
        w.onCycle(SEC, 32L * 1024 * 1024, 8, true);  // throughput ~32 MiB/s, big BDP

        // Now deliver no further bytes; the smoothed throughput decays, the BDP target collapses,
        // and depth must return to the lower bound rather than stay wedged high.
        for (int i = 0; i < 60; i++) {
            w.onCycle((2 + i) * SEC, 33_554_432L, 8, true);
            assertTrue("depth must stay bounded", w.depth() >= 8 && w.depth() <= 250);
        }
        assertEquals(8, w.depth());
    }

    @Test
    public void depthStaysWithinBoundsForExtremeBdp() {
        RequestWindow w = new RequestWindow(4, 16, 1000);
        Object key = new Object();
        w.onRequestIssued(key, 0L);
        w.onBlockArrived(key, 50_000_000L);    // 50 ms
        w.onCycle(0L, 0L, 0, false);

        // Absurdly high throughput would imply a target of thousands of blocks; the ceiling wins.
        for (int i = 0; i < 50; i++) {
            w.onCycle((i + 1) * SEC, (long) (i + 1) * 500_000_000L, 16, true);
        }
        assertEquals(16, w.depth());
    }

    @Test
    public void resetRestoresSlowStartAndClearsMeasurements() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        Object key = new Object();
        w.onRequestIssued(key, 0L);
        w.onBlockArrived(key, 100_000_000L);
        w.onCycle(0L, 0L, 8, true);
        w.onCycle(SEC, 32L * 1024 * 1024, 16, true);

        w.reset();
        assertEquals(8, w.depth());
        assertTrue(w.isSlowStart());
        assertEquals(0.0, w.rttMillis(), 0.0);
        assertEquals(0.0, w.throughputBytesPerSec(), 0.0);
    }

    @Test
    public void blockArrivalWithoutMatchingIssueDoesNotPolluteRtt() {
        RequestWindow w = new RequestWindow(8, 250, BLOCK);
        w.onBlockArrived(new Object(), 100_000_000L);  // unsolicited / unknown key
        assertEquals(0.0, w.rttMillis(), 0.0);
    }
}
