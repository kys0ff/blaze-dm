/*
 * This file is part of the Blaze download manager's fork of `bt` (Apache-2.0).
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

package bt.net;

import bt.protocol.InvalidMessageException;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Tests fork patch #1 (TOR-12): read-loop failures caused by the peer's own protocol violation must
 * be classified as expected churn so {@link DataReceivingLoop} can log a one-liner, not an ERROR trace.
 */
public class ReadLoopFailuresTest {

    // Mirrors what InboundMessageProcessor.processInboundData does: wraps any exception in RuntimeException.
    @Test
    public void unknownMessageTypeWrappedByPipelineIsPeerMisbehavior() {
        Throwable inLoop = new RuntimeException(
                new InvalidMessageException("Unknown message type ID: 70"));
        assertEquals(ReadLoopFailures.Kind.PEER_MISBEHAVIOR, ReadLoopFailures.kindOf(inLoop));
    }

    @Test
    public void invalidMessageThrownDirectlyIsPeerMisbehavior() {
        assertEquals(ReadLoopFailures.Kind.PEER_MISBEHAVIOR,
                ReadLoopFailures.kindOf(new InvalidMessageException("Unknown message type ID: -59")));
    }

    @Test
    public void deeplyNestedInvalidMessageIsStillPeerMisbehavior() {
        Throwable deep = new IllegalStateException(
                new IOException("read failed",
                        new InvalidMessageException("Unknown message type ID: 29")));
        assertEquals(ReadLoopFailures.Kind.PEER_MISBEHAVIOR, ReadLoopFailures.kindOf(deep));
    }

    @Test
    public void unrelatedFailureIsUnexpected() {
        assertEquals(ReadLoopFailures.Kind.UNEXPECTED,
                ReadLoopFailures.kindOf(new IllegalStateException("bug elsewhere")));
    }

    @Test
    public void causeCycleDoesNotHang() {
        RuntimeException cyclic = new RuntimeException("cycle") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(ReadLoopFailures.Kind.UNEXPECTED, ReadLoopFailures.kindOf(cyclic));
    }

    @Test
    public void rootMessageNamesTheRootCauseOnOneLine() {
        Throwable inLoop = new RuntimeException(
                new InvalidMessageException("Unknown message type ID: 70"));
        assertEquals("InvalidMessageException: Unknown message type ID: 70",
                ReadLoopFailures.rootMessage(inLoop));
    }

    @Test
    public void rootMessageWithoutTextOmitsTheSeparator() {
        assertEquals("InvalidMessageException",
                ReadLoopFailures.rootMessage(new InvalidMessageException(null)));
    }
}
