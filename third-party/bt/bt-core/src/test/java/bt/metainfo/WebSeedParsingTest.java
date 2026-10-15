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

package bt.metainfo;

import bt.bencoding.model.BEObject;
import bt.bencoding.types.BEInteger;
import bt.bencoding.types.BEList;
import bt.bencoding.types.BEMap;
import bt.bencoding.types.BEString;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers BEP-19 ({@code url-list}) and BEP-17 ({@code httpseeds}) parsing into the
 * {@link Torrent} web-seed accessors added by this fork.
 */
public class WebSeedParsingTest {

    @Test
    public void rootUrlListIsParsedAsWebSeeds() throws IOException {
        Map<String, BEObject<?>> rootExtra = new LinkedHashMap<>();
        rootExtra.put("url-list", urlList("http://seed1.example/", "http://seed2.example/torrent"));

        Torrent torrent = parse(rootExtra, Collections.emptyMap());

        assertEquals(
                Arrays.asList("http://seed1.example/", "http://seed2.example/torrent"),
                torrent.getWebSeedUrls());
        assertTrue(torrent.getHttpSeedUrls().isEmpty());
    }

    @Test
    public void infoDictUrlListIsPreferredOverRoot() throws IOException {
        Map<String, BEObject<?>> rootExtra = new LinkedHashMap<>();
        rootExtra.put("url-list", urlList("http://unsigned.example/"));

        Map<String, BEObject<?>> infoExtra = new LinkedHashMap<>();
        infoExtra.put("url-list", urlList("http://signed.example/"));

        Torrent torrent = parse(rootExtra, infoExtra);

        assertEquals(Collections.singletonList("http://signed.example/"), torrent.getWebSeedUrls());
    }

    @Test
    public void httpseedsAreParsedSeparately() throws IOException {
        Map<String, BEObject<?>> rootExtra = new LinkedHashMap<>();
        rootExtra.put("httpseeds", urlList("http://ftp1.example/seed", "http://ftp2.example/seed"));

        Torrent torrent = parse(rootExtra, Collections.emptyMap());

        assertEquals(
                Arrays.asList("http://ftp1.example/seed", "http://ftp2.example/seed"),
                torrent.getHttpSeedUrls());
        assertTrue(torrent.getWebSeedUrls().isEmpty());
    }

    @Test
    public void bareStringUrlListIsTolerated() throws IOException {
        Map<String, BEObject<?>> rootExtra = new LinkedHashMap<>();
        rootExtra.put("url-list", new BEString("http://only.example/"));

        Torrent torrent = parse(rootExtra, Collections.emptyMap());

        assertEquals(Collections.singletonList("http://only.example/"), torrent.getWebSeedUrls());
    }

    @Test
    public void torrentWithoutSeedsReturnsEmptyLists() throws IOException {
        Torrent torrent = parse(Collections.emptyMap(), Collections.emptyMap());

        assertTrue(torrent.getWebSeedUrls().isEmpty());
        assertTrue(torrent.getHttpSeedUrls().isEmpty());
    }

    private static Torrent parse(Map<String, BEObject<?>> rootExtra, Map<String, BEObject<?>> infoExtra)
            throws IOException {
        Map<String, BEObject<?>> root = new LinkedHashMap<>();
        root.put("announce", new BEString("http://tracker.example/announce"));
        root.put("info", infoDict(infoExtra));
        root.putAll(rootExtra);
        return new MetadataService().fromByteArray(encode(new BEMap(root)));
    }

    private static BEMap infoDict(Map<String, BEObject<?>> extra) {
        Map<String, BEObject<?>> info = new LinkedHashMap<>();
        info.put("name", new BEString("sample.bin"));
        info.put("piece length", new BEInteger(32768L));
        info.put("pieces", new BEString(new byte[20]));
        info.put("length", new BEInteger(11L));
        info.putAll(extra);
        return new BEMap(info);
    }

    private static BEList urlList(String... urls) {
        List<BEObject<?>> items = new ArrayList<>(urls.length);
        for (String url : urls) {
            items.add(new BEString(url));
        }
        return new BEList(items);
    }

    private static byte[] encode(BEObject<?> object) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        object.writeTo(out);
        return out.toByteArray();
    }
}
