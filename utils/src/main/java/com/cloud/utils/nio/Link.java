//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.utils.nio;

import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.db.DbProperties;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.log4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 */
public class Link {
    private static final Logger s_logger = Logger.getLogger(Link.class);

    private final InetSocketAddress _addr;
    private final NioConnection _connection;
    private SelectionKey _key;
    private final ConcurrentLinkedQueue<ByteBuffer[]> _writeQueue;
    private ByteBuffer _readBuffer;
    private ByteBuffer _plaintextBuffer;
    private Object _attach;
    private boolean _readHeader;
    private boolean _gotFollowingPacket;

    private SSLEngine _sslEngine;
    public static final String keystoreFile = "/cloudmanagementserver.keystore";

    public Link(InetSocketAddress addr, NioConnection connection) {
        _addr = addr;
        _connection = connection;
        _readBuffer = ByteBuffer.allocate(2048);
        _attach = null;
        _key = null;
        _writeQueue = new ConcurrentLinkedQueue<ByteBuffer[]>();
        _readHeader = true;
        _gotFollowingPacket = false;
    }

    public Link(Link link) {
        this(link._addr, link._connection);
    }

    public Object attachment() {
        return _attach;
    }

    public void attach(Object attach) {
        _attach = attach;
    }

    public void setKey(SelectionKey key) {
        synchronized (this) {
            _key = key;
        }
    }

    public void setSSLEngine(SSLEngine sslEngine) {
        _sslEngine = sslEngine;
    }

    /**
     * No user, so comment it out.
     *
     * Static methods for reading from a channel in case
     * you need to add a client that doesn't require nio.
     * @param ch channel to read from.
     * @param bytebuffer to use.
     * @return bytes read
     * @throws IOException if not read to completion.
    public static byte[] read(SocketChannel ch, ByteBuffer buff) throws IOException {
        synchronized(buff) {
            buff.clear();
            buff.limit(4);

            while (buff.hasRemaining()) {
                if (ch.read(buff) == -1) {
                    throw new IOException("Connection closed with -1 on reading size.");
                }
            }

            buff.flip();

            int length = buff.getInt();
            ByteArrayOutputStream output = new ByteArrayOutputStream(length);
            WritableByteChannel outCh = Channels.newChannel(output);

            int count = 0;
            while (count < length) {
                buff.clear();
                int read = ch.read(buff);
                if (read < 0) {
                    throw new IOException("Connection closed with -1 on reading data.");
                }
                count += read;
                buff.flip();
                outCh.write(buff);
            }

            return output.toByteArray();
        }
    }
     */

    private static void doWrite(SocketChannel ch, ByteBuffer[] buffers, SSLEngine sslEngine) throws IOException {
        SSLSession sslSession = sslEngine.getSession();
        ByteBuffer pkgBuf = ByteBuffer.allocate(sslSession.getPacketBufferSize() + 40);
        SSLEngineResult engResult;

        ByteBuffer headBuf = ByteBuffer.allocate(4);

        int totalLen = 0;
        for (ByteBuffer buffer : buffers) {
            totalLen += buffer.limit();
        }

        int processedLen = 0;
        while (processedLen < totalLen) {
            headBuf.clear();
            pkgBuf.clear();
            engResult = sslEngine.wrap(buffers, pkgBuf);
            if (engResult.getHandshakeStatus() != HandshakeStatus.FINISHED && engResult.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING &&
                    engResult.getStatus() != SSLEngineResult.Status.OK) {
                throw new IOException("SSL: SSLEngine return bad result! " + engResult);
            }

            processedLen = 0;
            for (ByteBuffer buffer : buffers) {
                processedLen += buffer.position();
            }

            int dataRemaining = pkgBuf.position();
            int header = dataRemaining;
            int headRemaining = 4;
            pkgBuf.flip();
            if (processedLen < totalLen) {
                header = header | HEADER_FLAG_FOLLOWING;
            }
            headBuf.putInt(header);
            headBuf.flip();

            while (headRemaining > 0) {
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("Writing Header " + headRemaining);
                }
                long count = ch.write(headBuf);
                headRemaining -= count;
            }
            while (dataRemaining > 0) {
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("Writing Data " + dataRemaining);
                }
                long count = ch.write(pkgBuf);
                dataRemaining -= count;
            }
        }
    }

    /**
     * write method to write to a socket.  This method writes to completion so
     * it doesn't follow the nio standard.  We use this to make sure we write
     * our own protocol.
     *
     * @param ch channel to write to.
     * @param buffers buffers to write.
     * @throws IOException if unable to write to completion.
     */
    public static void write(SocketChannel ch, ByteBuffer[] buffers, SSLEngine sslEngine) throws IOException {
        synchronized (ch) {
            doWrite(ch, buffers, sslEngine);
        }
    }

    /* SSL has limitation of 16k, we may need to split packets. 18000 is 16k + some extra SSL informations */
    protected static final int MAX_SIZE_PER_PACKET = 18000;
    protected static final int HEADER_FLAG_FOLLOWING = 0x10000;

    public byte[] read(SocketChannel ch) throws IOException {
        if (_readHeader) {   // Start of a packet
            if (_readBuffer.position() == 0) {
                _readBuffer.limit(4);
            }

            if (ch.read(_readBuffer) == -1) {
                throw new IOException("Connection closed with -1 on reading size.");
            }

            if (_readBuffer.hasRemaining()) {
                s_logger.trace("Need to read the rest of the packet length");
                return null;
            }
            _readBuffer.flip();
            int header = _readBuffer.getInt();
            int readSize = (short)header;
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Packet length is " + readSize);
            }

            if (readSize > MAX_SIZE_PER_PACKET) {
                throw new IOException("Wrong packet size: " + readSize);
            }

            if (!_gotFollowingPacket) {
                _plaintextBuffer = ByteBuffer.allocate(2000);
            }

            if ((header & HEADER_FLAG_FOLLOWING) != 0) {
                _gotFollowingPacket = true;
            } else {
                _gotFollowingPacket = false;
            }

            _readBuffer.clear();
            _readHeader = false;

            if (_readBuffer.capacity() < readSize) {
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("Resizing the byte buffer from " + _readBuffer.capacity());
                }
                _readBuffer = ByteBuffer.allocate(readSize);
            }
            _readBuffer.limit(readSize);
        }

        if (ch.read(_readBuffer) == -1) {
            throw new IOException("Connection closed with -1 on read.");
        }

        if (_readBuffer.hasRemaining()) {   // We're not done yet.
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Still has " + _readBuffer.remaining());
            }
            return null;
        }

        _readBuffer.flip();

        ByteBuffer appBuf;

        SSLSession sslSession = _sslEngine.getSession();
        SSLEngineResult engResult;
        int remaining = 0;

        while (_readBuffer.hasRemaining()) {
            remaining = _readBuffer.remaining();
            appBuf = ByteBuffer.allocate(sslSession.getApplicationBufferSize() + 40);
            engResult = _sslEngine.unwrap(_readBuffer, appBuf);
            if (engResult.getHandshakeStatus() != HandshakeStatus.FINISHED && engResult.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING &&
                    engResult.getStatus() != SSLEngineResult.Status.OK) {
                throw new IOException("SSL: SSLEngine return bad result! " + engResult);
            }
            if (remaining == _readBuffer.remaining()) {
                throw new IOException("SSL: Unable to unwrap received data! still remaining " + remaining + "bytes!");
            }

            appBuf.flip();
            if (_plaintextBuffer.remaining() < appBuf.limit()) {
                // We need to expand _plaintextBuffer for more data
                ByteBuffer newBuffer = ByteBuffer.allocate(_plaintextBuffer.capacity() + appBuf.limit() * 5);
                _plaintextBuffer.flip();
                newBuffer.put(_plaintextBuffer);
                _plaintextBuffer = newBuffer;
            }
            _plaintextBuffer.put(appBuf);
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Done with packet: " + appBuf.limit());
            }
        }

        _readBuffer.clear();
        _readHeader = true;

        if (!_gotFollowingPacket) {
            _plaintextBuffer.flip();
            byte[] result = new byte[_plaintextBuffer.limit()];
            _plaintextBuffer.get(result);
            return result;
        } else {
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Waiting for more packets");
            }
            return null;
        }
    }

    public void send(byte[] data) throws ClosedChannelException {
        send(data, false);
    }

    public void send(byte[] data, boolean close) throws ClosedChannelException {
        send(new ByteBuffer[] {ByteBuffer.wrap(data)}, close);
    }

    public void send(ByteBuffer[] data, boolean close) throws ClosedChannelException {
        ByteBuffer[] item = new ByteBuffer[data.length + 1];
        int remaining = 0;
        for (int i = 0; i < data.length; i++) {
            remaining += data[i].remaining();
            item[i + 1] = data[i];
        }

        item[0] = ByteBuffer.allocate(4);
        item[0].putInt(remaining);
        item[0].flip();

        if (s_logger.isTraceEnabled()) {
            s_logger.trace("Sending packet of length " + remaining);
        }

        _writeQueue.add(item);
        if (close) {
            _writeQueue.add(new ByteBuffer[0]);
        }
        synchronized (this) {
            if (_key == null) {
                throw new ClosedChannelException();
            }
            _connection.change(SelectionKey.OP_WRITE, _key, null);
        }
    }

    public void send(ByteBuffer[] data) throws ClosedChannelException {
        send(data, false);
    }

    public synchronized void close() {
        if (_key != null) {
            _connection.close(_key);
        }
    }

    public boolean write(SocketChannel ch) throws IOException {
        ByteBuffer[] data = null;
        while ((data = _writeQueue.poll()) != null) {
            if (data.length == 0) {
                if (s_logger.isTraceEnabled()) {
                    s_logger.trace("Closing connection requested");
                }
                return true;
            }

            ByteBuffer[] raw_data = new ByteBuffer[data.length - 1];
            System.arraycopy(data, 1, raw_data, 0, data.length - 1);

            doWrite(ch, raw_data, _sslEngine);
        }
        return false;
    }

    public InetSocketAddress getSocketAddress() {
        return _addr;
    }

    public String getIpAddress() {
        return _addr.getAddress().toString();
    }

    public synchronized void terminated() {
        _key = null;
    }

    public synchronized void schedule(Task task) throws ClosedChannelException {
        if (_key == null) {
            throw new ClosedChannelException();
        }
        _connection.scheduleTask(task);
    }

    public static SSLContext initSSLContext(boolean isClient) throws GeneralSecurityException, IOException {
        InputStream stream;
        SSLContext sslContext = null;
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        KeyStore ks = KeyStore.getInstance("JKS");
        TrustManager[] tms;

        File confFile = PropertiesUtil.findConfigFile("db.properties");
        if (null != confFile && !isClient) {
            final String pass = DbProperties.getDbProperties().getProperty("db.cloud.keyStorePassphrase");
            char[] passphrase = "vmops.com".toCharArray();
            if (pass != null) {
                passphrase = pass.toCharArray();
            }
            String confPath = confFile.getParent();
            String keystorePath = confPath + keystoreFile;
            if (new File(keystorePath).exists()) {
                stream = new FileInputStream(keystorePath);
            } else {
                s_logger.warn("SSL: Fail to find the generated keystore. Loading fail-safe one to continue.");
                stream = NioConnection.class.getResourceAsStream("/cloud.keystore");
                passphrase = "vmops.com".toCharArray();
            }
            ks.load(stream, passphrase);
            stream.close();
            kmf.init(ks, passphrase);
            tmf.init(ks);
            tms = tmf.getTrustManagers();
        } else {
            ks.load(null, null);
            kmf.init(ks, null);
            tms = new TrustManager[1];
            tms[0] = new TrustAllManager();
        }

        sslContext = SSLUtils.getSSLContext();
        sslContext.init(kmf.getKeyManagers(), tms, null);
        if (s_logger.isTraceEnabled()) {
            s_logger.trace("SSL: SSLcontext has been initialized");
        }

        return sslContext;
    }

    public static ByteBuffer enlargeBuffer(ByteBuffer buffer, final int sessionProposedCapacity) {
        if (buffer == null || sessionProposedCapacity < 0) {
            return buffer;
        }
        if (sessionProposedCapacity > buffer.capacity()) {
            buffer = ByteBuffer.allocate(sessionProposedCapacity);
        } else {
            buffer = ByteBuffer.allocate(buffer.capacity() * 2);
        }
        return buffer;
    }

    public static ByteBuffer handleBufferUnderflow(final SSLEngine engine, ByteBuffer buffer) {
        if (engine == null || buffer == null) {
            return buffer;
        }
        if (buffer.position() < buffer.limit()) {
            return buffer;
        }
        ByteBuffer replaceBuffer = enlargeBuffer(buffer, engine.getSession().getPacketBufferSize());
        buffer.flip();
        replaceBuffer.put(buffer);
        return replaceBuffer;
    }

    private static boolean doHandshakeUnwrap(final SocketChannel socketChannel, final SSLEngine sslEngine,
                                             ByteBuffer peerAppData, ByteBuffer peerNetData, final int appBufferSize) throws IOException {
        if (socketChannel == null || sslEngine == null || peerAppData == null || peerNetData == null || appBufferSize < 0) {
            return false;
        }
        if (socketChannel.read(peerNetData) < 0) {
            if (sslEngine.isInboundDone() && sslEngine.isOutboundDone()) {
                return false;
            }
            try {
                sslEngine.closeInbound();
            } catch (SSLException e) {
                s_logger.warn("This SSL engine was forced to close inbound due to end of stream.");
            }
            sslEngine.closeOutbound();
            // After closeOutbound the engine will be set to WRAP state,
            // in order to try to send a close message to the client.
            return true;
        }
        peerNetData.flip();
        SSLEngineResult result = null;
        try {
            result = sslEngine.unwrap(peerNetData, peerAppData);
            peerNetData.compact();
        } catch (SSLException sslException) {
            s_logger.error("SSL error occurred while processing unwrap data: " + sslException.getMessage());
            sslEngine.closeOutbound();
            return true;
        }
        switch (result.getStatus()) {
            case OK:
                break;
            case BUFFER_OVERFLOW:
                // Will occur when peerAppData's capacity is smaller than the data derived from peerNetData's unwrap.
                peerAppData = enlargeBuffer(peerAppData, appBufferSize);
                break;
            case BUFFER_UNDERFLOW:
                // Will occur either when no data was read from the peer or when the peerNetData buffer
                // was too small to hold all peer's data.
                peerNetData = handleBufferUnderflow(sslEngine, peerNetData);
                break;
            case CLOSED:
                if (sslEngine.isOutboundDone()) {
                    return false;
                } else {
                    sslEngine.closeOutbound();
                    break;
                }
            default:
                throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return true;
    }

    private static boolean doHandshakeWrap(final SocketChannel socketChannel, final SSLEngine sslEngine,
                                           ByteBuffer myAppData, ByteBuffer myNetData, ByteBuffer peerNetData,
                                           final int netBufferSize) throws IOException {
        if (socketChannel == null || sslEngine == null || myNetData == null || peerNetData == null
                || myAppData == null || netBufferSize < 0) {
            return false;
        }
        myNetData.clear();
        SSLEngineResult result = null;
        try {
            result = sslEngine.wrap(myAppData, myNetData);
        } catch (SSLException sslException) {
            s_logger.error("SSL error occurred while processing wrap data: " + sslException.getMessage());
            sslEngine.closeOutbound();
            return true;
        }
        switch (result.getStatus()) {
            case OK :
                myNetData.flip();
                while (myNetData.hasRemaining()) {
                    socketChannel.write(myNetData);
                }
                break;
            case BUFFER_OVERFLOW:
                // Will occur if there is not enough space in myNetData buffer to write all the data
                // that would be generated by the method wrap. Since myNetData is set to session's packet
                // size we should not get to this point because SSLEngine is supposed to produce messages
                // smaller or equal to that, but a general handling would be the following:
                myNetData = enlargeBuffer(myNetData, netBufferSize);
                break;
            case BUFFER_UNDERFLOW:
                throw new SSLException("Buffer underflow occurred after a wrap. We should not reach here.");
            case CLOSED:
                try {
                    myNetData.flip();
                    while (myNetData.hasRemaining()) {
                        socketChannel.write(myNetData);
                    }
                    // At this point the handshake status will probably be NEED_UNWRAP
                    // so we make sure that peerNetData is clear to read.
                    peerNetData.clear();
                } catch (Exception e) {
                    s_logger.error("Failed to send server's CLOSE message due to socket channel's failure.");
                }
                break;
            default:
                throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return true;
    }

    public static boolean doHandshake(final SocketChannel socketChannel, final SSLEngine sslEngine, final boolean isClient) throws IOException {
        if (socketChannel == null || sslEngine == null) {
            return false;
        }
        final int appBufferSize = sslEngine.getSession().getApplicationBufferSize();
        final int netBufferSize = sslEngine.getSession().getPacketBufferSize();
        ByteBuffer myAppData = ByteBuffer.allocate(appBufferSize);
        ByteBuffer peerAppData = ByteBuffer.allocate(appBufferSize);
        ByteBuffer myNetData = ByteBuffer.allocate(netBufferSize);
        ByteBuffer peerNetData = ByteBuffer.allocate(netBufferSize);

        final long startTimeMills = System.currentTimeMillis();

        HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED
                && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            final long timeTaken = System.currentTimeMillis() - startTimeMills;
            if (timeTaken > 15000L) {
                s_logger.warn("SSL Handshake has taken more than 15s to connect to: " + socketChannel.getRemoteAddress() +
                        ". Please investigate this connection.");
                return false;
            }
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    if (!doHandshakeUnwrap(socketChannel, sslEngine, peerAppData, peerNetData, appBufferSize)) {
                        return false;
                    }
                    break;
                case NEED_WRAP:
                    if (!doHandshakeWrap(socketChannel, sslEngine,  myAppData, myNetData, peerNetData, netBufferSize)) {
                        return false;
                    }
                    break;
                case NEED_TASK:
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        if (s_logger.isTraceEnabled()) {
                            s_logger.trace("SSL: Running delegated task!");
                        }
                        task.run();
                    }
                    break;
                case FINISHED:
                    break;
                case NOT_HANDSHAKING:
                    break;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + handshakeStatus);
            }
            handshakeStatus = sslEngine.getHandshakeStatus();
        }
        return true;
    }

}
