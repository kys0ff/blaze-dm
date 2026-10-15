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

package bt.processor.torrent;

import bt.event.EventSink;
import bt.metainfo.Torrent;
import bt.processor.ProcessingStage;
import bt.processor.TerminateOnErrorProcessingStage;
import bt.processor.listener.ProcessingEvent;
import bt.torrent.TorrentRegistry;

public class FetchTorrentStage extends TerminateOnErrorProcessingStage<TorrentContext> {

    private EventSink eventSink;
    private final TorrentRegistry torrentRegistry;

    public FetchTorrentStage(ProcessingStage<TorrentContext> next, EventSink eventSink,
                             TorrentRegistry torrentRegistry) {
        super(next);
        this.eventSink = eventSink;
        this.torrentRegistry = torrentRegistry;
    }

    @Override
    protected void doExecute(TorrentContext context) {
        Torrent torrent = context.getTorrentSupplier().get();
        // This is the first point a file/URL session learns its torrent ID - and the earliest hook that
        // only a genuinely new session reaches (a session being paused has already run past here before
        // its metadata event could reach the caller). Lift any finalizing guard from a previous session
        // so this session's registration below is accepted (fork patch #9, TOR-7).
        torrentRegistry.beginSession(torrent.getTorrentId());
        context.setTorrentId(torrent.getTorrentId());
        context.setTorrent(torrent);
        eventSink.fireMetadataAvailable(torrent.getTorrentId(), torrent);
    }

    @Override
    public ProcessingEvent after() {
        return ProcessingEvent.TORRENT_FETCHED;
    }
}
