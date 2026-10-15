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

/**
 * Classifies exceptions that escape per-connection read processing in {@link DataReceivingLoop},
 * so routine hostile/legacy peer behavior is not logged like an internal defect (fork patch, TOR-12).
 *
 * <p>Classification never changes connection handling: the socket has already been shut down by
 * {@code SocketChannelHandler.read} before the exception reaches the loop; only the log level differs.
 */
final class ReadLoopFailures {

    /**
     * Severity hint for logging a read-loop failure.
     */
    enum Kind {
        /** The peer sent something unparseable or out of spec; expected churn on real swarms. */
        PEER_MISBEHAVIOR,
        /** Not caused by the peer and not expected churn; a genuine defect worth a full trace. */
        UNEXPECTED
    }

    /** Bounds the cause-chain walk against (pathological) self-referencing causes. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private ReadLoopFailures() {
    }

    /**
     * @return {@link Kind#PEER_MISBEHAVIOR} if any throwable in the cause chain is an
     * {@link InvalidMessageException} (bt's own "unknown/invalid inbound message" signal, e.g.
     * thrown by {@code StandardBittorrentProtocol.readMessageType}), else {@link Kind#UNEXPECTED}.
     */
    static Kind kindOf(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof InvalidMessageException) {
                return Kind.PEER_MISBEHAVIOR;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return Kind.UNEXPECTED;
    }

    /**
     * @return a one-line description of the root cause ("SimpleClassName: message"), used instead
     * of a full stack trace for expected peer churn.
     */
    static String rootMessage(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
