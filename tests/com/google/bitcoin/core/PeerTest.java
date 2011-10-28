/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static org.easymock.EasyMock.*;

import org.junit.Before;
import org.junit.Test;

public class PeerTest extends TestWithNetworkConnections {
    private Peer peer;
    private MockNetworkConnection conn;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        conn = createMockNetworkConnection();
        peer = new Peer(unitTestParams, blockChain, conn);
    }

    @Test
    public void testAddEventListener() {
        PeerEventListener listener = new AbstractPeerEventListener();
        peer.addEventListener(listener);
        assertTrue(peer.removeEventListener(listener));
        assertFalse(peer.removeEventListener(listener));
    }
    
    // Check that the connection is shut down if there's a read error and that the exception is propagated.
    @Test
    public void testRun_exception() throws Exception {
        conn.exceptionOnRead(new IOException("done"));
        try {
            peer.run();
            fail("did not throw");
        } catch (PeerException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
        conn.exceptionOnRead(new ProtocolException("proto"));
        try {
            peer.run();
            fail("did not throw");
        } catch (PeerException e) {
            // expected
            assertTrue(e.toString(), e.getCause() instanceof ProtocolException);
        }
    }

    // Check that it runs through the event loop and shut down correctly
    @Test
    public void shutdown() throws Exception {
        runPeer(peer, conn);
    }

    // Check that when we receive a block that does not connect to our chain, we send a getblocks to fetch
    // the intermediates.
    @Test
    public void unconnectedBlock() throws Exception {
        Block b1 = TestUtils.createFakeBlock(unitTestParams, blockStore).block;
        blockChain.add(b1);
        Block b2 = TestUtils.makeSolvedTestBlock(unitTestParams, b1);
        Block b3 = TestUtils.makeSolvedTestBlock(unitTestParams, b2);
        conn.inbound(b3);
        runPeer(peer, conn);
        GetBlocksMessage getblocks = (GetBlocksMessage) conn.popOutbound();
        List<Sha256Hash> expectedLocator = new ArrayList<Sha256Hash>();
        expectedLocator.add(b1.getHash());
        expectedLocator.add(b1.getPrevBlockHash());
        expectedLocator.add(unitTestParams.genesisBlock.getHash());
        assertEquals(getblocks.getLocator(), expectedLocator);
        assertEquals(getblocks.getStopHash(), b3.getHash());
    }

    // Check that an inventory tickle is processed correctly when downloading missing blocks is active.
    @Test
    public void invTickle() throws Exception {
        Block b1 = TestUtils.createFakeBlock(unitTestParams, blockStore).block;
        blockChain.add(b1);
        // Make a missing block.
        Block b2 = TestUtils.makeSolvedTestBlock(unitTestParams, b1);
        Block b3 = TestUtils.makeSolvedTestBlock(unitTestParams, b2);
        conn.inbound(b3);
        InventoryMessage inv = new InventoryMessage(unitTestParams);
        InventoryItem item = new InventoryItem(InventoryItem.Type.Block, b3.getHash());
        inv.addItem(item);
        conn.inbound(inv);
        runPeer(peer, conn);
        GetBlocksMessage getblocks = (GetBlocksMessage) conn.popOutbound();
        List<Sha256Hash> expectedLocator = new ArrayList<Sha256Hash>();
        expectedLocator.add(b1.getHash());
        expectedLocator.add(b1.getPrevBlockHash());
        expectedLocator.add(unitTestParams.genesisBlock.getHash());
        
        assertEquals(getblocks.getLocator(), expectedLocator);
        assertEquals(getblocks.getStopHash(), b3.getHash());
    }

    // Check that an inv to a peer that is not set to download missing blocks does nothing.
    @Test
    public void invNoDownload() throws Exception {
        // Don't download missing blocks.
        peer.setDownloadData(false);

        // Make a missing block that we receive.
        Block b1 = TestUtils.createFakeBlock(unitTestParams, blockStore).block;
        blockChain.add(b1);
        Block b2 = TestUtils.makeSolvedTestBlock(unitTestParams, b1);

        // Receive an inv.
        InventoryMessage inv = new InventoryMessage(unitTestParams);
        InventoryItem item = new InventoryItem(InventoryItem.Type.Block, b2.getHash());
        inv.addItem(item);
        conn.inbound(inv);
        // Peer does nothing with it.
        runPeer(peer, conn);
        Message message = conn.popOutbound();
        assertNull(message != null ? message.toString() : "", message);
    }

    // Check that inventory message containing blocks we want is processed correctly.
    @Test
    public void newBlock() throws Exception {
        PeerEventListener listener = control.createMock(PeerEventListener.class);
        peer.addEventListener(listener);

        Block b1 = TestUtils.createFakeBlock(unitTestParams, blockStore).block;
        blockChain.add(b1);
        Block b2 = TestUtils.makeSolvedTestBlock(unitTestParams, b1);
        Block b3 = TestUtils.makeSolvedTestBlock(unitTestParams, b2);
        conn.setVersionMessageForHeight(unitTestParams, 100);
        // Receive notification of a new block.
        InventoryMessage inv = new InventoryMessage(unitTestParams);
        InventoryItem item = new InventoryItem(InventoryItem.Type.Block, b2.getHash());
        inv.addItem(item);
        conn.inbound(inv);
        // Response to the getdata message.
        conn.inbound(b2);

        listener.onBlocksDownloaded(eq(peer), anyObject(Block.class), eq(98));
        expectLastCall();

        control.replay();
        runPeer(peer, conn);
        control.verify();
        
        GetDataMessage getdata = (GetDataMessage) conn.popOutbound();
        List<InventoryItem> items = getdata.getItems();
        assertEquals(1, items.size());
        assertEquals(b2.getHash(), items.get(0).hash);
        assertEquals(InventoryItem.Type.Block, items.get(0).type);
    }

    // Check that it starts downloading the block chain correctly on request.
    @Test
    public void startBlockChainDownload() throws Exception {
        PeerEventListener listener = control.createMock(PeerEventListener.class);
        peer.addEventListener(listener);

        Block b1 = TestUtils.createFakeBlock(unitTestParams, blockStore).block;
        blockChain.add(b1);
        Block b2 = TestUtils.makeSolvedTestBlock(unitTestParams, b1);
        blockChain.add(b2);
        conn.setVersionMessageForHeight(unitTestParams, 100);

        listener.onChainDownloadStarted(peer, 98);
        expectLastCall();

        control.replay();
        peer.startBlockChainDownload();
        runPeer(peer, conn);
        control.verify();
        
        List<Sha256Hash> expectedLocator = new ArrayList<Sha256Hash>();
        expectedLocator.add(b2.getHash());
        expectedLocator.add(b1.getHash());
        expectedLocator.add(unitTestParams.genesisBlock.getHash());

        GetBlocksMessage message = (GetBlocksMessage) conn.popOutbound();
        assertEquals(message.getLocator(), expectedLocator);
        assertEquals(message.getStopHash(), Sha256Hash.ZERO_HASH);
    }

    @Test
    public void getBlock() throws Exception {
        Block b1 = TestUtils.createFakeBlock(unitTestParams, blockStore).block;
        blockChain.add(b1);
        Block b2 = TestUtils.makeSolvedTestBlock(unitTestParams, b1);
        Block b3 = TestUtils.makeSolvedTestBlock(unitTestParams, b2);
        conn.setVersionMessageForHeight(unitTestParams, 100);
        runPeerAsync(peer, conn);
        // Request the block.
        Future<Block> resultFuture = peer.getBlock(b3.getHash());
        assertFalse(resultFuture.isDone());
        // Peer asks for it.
        GetDataMessage message = (GetDataMessage) conn.outbound();
        assertEquals(message.getItems().get(0).hash, b3.getHash());
        assertFalse(resultFuture.isDone());
        // Peer receives it.
        conn.inbound(b3);
        Block b = resultFuture.get();
        assertEquals(b, b3);
        conn.disconnect();
    }
}