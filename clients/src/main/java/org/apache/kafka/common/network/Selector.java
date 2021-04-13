/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Count;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A nioSelector interface for doing non-blocking multi-connection network I/O.
 * <p>
 * This class works with {@link NetworkSend} and {@link NetworkReceive} to transmit size-delimited network requests and
 * responses.
 * <p>
 * A connection can be added to the nioSelector associated with an integer id by doing
 *
 * <pre>
 * nioSelector.connect(&quot;42&quot;, new InetSocketAddress(&quot;google.com&quot;, server.port), 64000, 64000);
 * </pre>
 *
 * The connect call does not block on the creation of the TCP connection, so the connect method only begins initiating
 * the connection. The successful invocation of this method does not mean a valid connection has been established.
 *
 * Sending requests, receiving responses, processing connection completions, and disconnections on the existing
 * connections are all done using the <code>poll()</code> call.
 *
 * <pre>
 * nioSelector.send(new NetworkSend(myDestination, myBytes));
 * nioSelector.send(new NetworkSend(myOtherDestination, myOtherBytes));
 * nioSelector.poll(TIMEOUT_MS);
 * </pre>
 *
 * The nioSelector maintains several lists that are reset by each call to <code>poll()</code> which are available via
 * various getters. These are reset by each call to <code>poll()</code>.
 *
 * This class is not thread safe!
 *
 * Selector内部的源码一定要带着大家深入到每个细节的研究，因为这是完全经历过全世界大量的、复杂的、大规模的场景考验的一套网络通信的框架，基于NIO封装的一套网络通信的框架
 *
 * 里面的涉及到的很多的细节和机制，都是代表了工业级、企业级的网络通信的设计
 */
public class Selector implements Selectable {

    private static final Logger log = LoggerFactory.getLogger(Selector.class);

    //封装了原生的Java NIO的Selector，多路复用组件，一个线程调用他直接监听多个网络连接的请求和响应
    private final java.nio.channels.Selector nioSelector;
    //broker id到Channel的映射关系。每个broker都有一个网络连接，每个连接在NIO的语义里，都有一个对应的SocketChannel，KafkaChannel封装了SocketChannel
    private final Map<String, KafkaChannel> channels;
    //已经成功发送出去的请求
    private final List<Send> completedSends;
    //已经接收回来的响应而且被处理完了
    private final List<NetworkReceive> completedReceives;
    //每个Broker的收到的但是还没有被处理的响应
    private final Map<KafkaChannel, Deque<NetworkReceive>> stagedReceives;
    private final Set<SelectionKey> immediatelyConnectedKeys;
    private final List<String> disconnected;
    private final List<String> connected;
    private final List<String> failedSends;
    private final Time time;
    private final SelectorMetrics sensors;
    private final String metricGrpPrefix;
    private final Map<String, String> metricTags;
    private final ChannelBuilder channelBuilder;
    private final Map<String, Long> lruConnections;
    //每个网络连接最多可以空闲的时间的大小，就要回收掉
    private final long connectionsMaxIdleNanos;
    //最大可以接收的数据量的大小
    private final int maxReceiveSize;
    private final boolean metricsPerConnection;
    private long currentTimeNanos;
    private long nextIdleCloseCheckTime;


    /**
     * Create a new nioSelector
     */
    public Selector(int maxReceiveSize, long connectionMaxIdleMs, Metrics metrics, Time time, String metricGrpPrefix, Map<String, String> metricTags, boolean metricsPerConnection, ChannelBuilder channelBuilder) {
        try {
            this.nioSelector = java.nio.channels.Selector.open();
        } catch (IOException e) {
            throw new KafkaException(e);
        }
        this.maxReceiveSize = maxReceiveSize;
        this.connectionsMaxIdleNanos = connectionMaxIdleMs * 1000 * 1000;
        this.time = time;
        this.metricGrpPrefix = metricGrpPrefix;
        this.metricTags = metricTags;
        this.channels = new HashMap<>();
        this.completedSends = new ArrayList<>();
        this.completedReceives = new ArrayList<>();
        this.stagedReceives = new HashMap<>();
        this.immediatelyConnectedKeys = new HashSet<>();
        this.connected = new ArrayList<>();
        this.disconnected = new ArrayList<>();
        this.failedSends = new ArrayList<>();
        this.sensors = new SelectorMetrics(metrics);
        this.channelBuilder = channelBuilder;
        // initial capacity and load factor are default, we set them explicitly because we want to set accessOrder = true
        this.lruConnections = new LinkedHashMap<>(16, .75F, true);
        currentTimeNanos = time.nanoseconds();
        nextIdleCloseCheckTime = currentTimeNanos + connectionsMaxIdleNanos;
        this.metricsPerConnection = metricsPerConnection;
    }

    public Selector(long connectionMaxIdleMS, Metrics metrics, Time time, String metricGrpPrefix, ChannelBuilder channelBuilder) {
        this(NetworkReceive.UNLIMITED, connectionMaxIdleMS, metrics, time, metricGrpPrefix, new HashMap<String, String>(), true, channelBuilder);
    }

    /**
     * Begin connecting to the given address and add the connection to this nioSelector associated with the given id
     * number.
     * <p>
     * Note that this call only initiates the connection, which will be completed on a future {@link #poll(long)}
     * call. Check {@link #connected()} to see which (if any) connections have completed after a given poll call.
     * @param id The id for the new connection
     * @param address The address to connect to
     * @param sendBufferSize The send buffer for the new connection
     * @param receiveBufferSize The receive buffer for the new connection
     * @throws IllegalStateException if there is already a connection for that id
     * @throws IOException if DNS resolution fails on the hostname or if the broker is down
     * 使用 nio 发起网络连接的工业级使用方法
     *
     * TCP/IP协议中,无论发送多少数据,总是要在数据前面加上协议头,同时,对方接收到数据,也需要发送ACK表示确认.
     * 为了尽可能的利用网络带宽,TCP总是希望尽可能的发送足够大的数据.(在一个连接中会设置MSS参数,
     * 因此,TCP/IP希望每次都能够以MSS尺寸的数据块来发送数据).Nagle算法就是为了尽可能发送大块数据,避免网络中充斥着许多小数据块.
     *
     * Nagle算法的基本定义是任意时刻,最多只能有一个未被确认的小段. 所谓“小段”,指的是小于MSS尺寸的数据块,所谓“未被确认”,
     * 是指一个数据块发送出去后,没有收到对方发送的ACK确认该数据已收到.
     *
     * keepalive的意思，主要是避免客户端和服务端任何一方如果断开连接之后，别人不知道，一直保持着网络连接的资源；所以设置这个之后，
     * 2小时内如果双方没有任何通信，那么发送一个探测包，根据探测包的结果保持连接、重新连接或者断开连接
     *
     * NetworkClient、Selector、KafkaChannel、ConnectStates，这些东西是极为值得我们来研究的，
     * 对我们的技术底层的功底的夯实极为有好处，假设我们真的要去开发一个网络通信的程序，打算基于NIO来做
     *
     * 对于客户端而言，他的SocketChannel到底应该如何来设置呢？你就可以参考人家做法：KeepAlive、TcpNoDelay、SocketBuffer
     */
    @Override
    public void connect(String id, InetSocketAddress address, int sendBufferSize, int receiveBufferSize) throws IOException {
        //判断连接是否已经建立
        if (this.channels.containsKey(id))
            throw new IllegalStateException("There is already a connection for id " + id);

        SocketChannel socketChannel = SocketChannel.open();
        //配置为非阻塞
        socketChannel.configureBlocking(false);
        Socket socket = socketChannel.socket();
        //tcp 长连接开启心跳检测探测连接是否正常
        socket.setKeepAlive(true);
        if (sendBufferSize != Selectable.USE_DEFAULT_BUFFER_SIZE)
            //设置 tcp 发送数据缓冲区
            socket.setSendBufferSize(sendBufferSize);
        if (receiveBufferSize != Selectable.USE_DEFAULT_BUFFER_SIZE)
            //设置 tcp 接收数据缓冲区
            socket.setReceiveBufferSize(receiveBufferSize);
        //关闭Nagle's算法，让发送出去的数据包立即通过网络传输过去
        socket.setTcpNoDelay(true);
        boolean connected;
        try {
            //创建网络连接
            //一个channel在非阻塞模式下执行connect后，如果连接能马上建立好则返回true(连接本地可能会很快成功)，否则完成false
            //如果返回false，那么只能通过之后调用finishConnect来判断连接是否完成。
            connected = socketChannel.connect(address);
        } catch (UnresolvedAddressException e) {
            socketChannel.close();
            throw new IOException("Can't resolve address: " + address, e);
        } catch (IOException e) {
            socketChannel.close();
            throw e;
        }
        //将创建的连接注册到 Selector 上，并且关注OP_CONNECT事件
        SelectionKey key = socketChannel.register(nioSelector, SelectionKey.OP_CONNECT);
        //可以通过SelectionKey.key获取到连接channel，最终封装为KafkaChannel
        KafkaChannel channel = channelBuilder.buildChannel(id, key, maxReceiveSize);
        //后续可以通过attachment()方法获取到 KafkaChannel
        //后续在通过SelectionKey进行网络请求和相应的处理的时候，就可以从SelectionKey里获取出来SocketChannel
        key.attach(channel);
        //将连接缓存
        this.channels.put(id, channel);

        if (connected) {
            // OP_CONNECT won't trigger for immediately connected channels
            log.debug("Immediately connected to node {}", channel.id());
            immediatelyConnectedKeys.add(key);
            key.interestOps(0);
        }
    }

    /**
     * Register the nioSelector with an existing channel
     * Use this on server-side, when a connection is accepted by a different thread but processed by the Selector
     * Note that we are not checking if the connection id is valid - since the connection already exists
     */
    public void register(String id, SocketChannel socketChannel) throws ClosedChannelException {
        SelectionKey key = socketChannel.register(nioSelector, SelectionKey.OP_READ);
        KafkaChannel channel = channelBuilder.buildChannel(id, key, maxReceiveSize);
        key.attach(channel);
        this.channels.put(id, channel);
    }

    /**
     * Interrupt the nioSelector if it is blocked waiting to do I/O.
     */
    @Override
    public void wakeup() {
        this.nioSelector.wakeup();
    }

    /**
     * Close this selector and all associated connections
     */
    @Override
    public void close() {
        List<String> connections = new ArrayList<>(channels.keySet());
        for (String id : connections)
            close(id);
        try {
            this.nioSelector.close();
        } catch (IOException | SecurityException e) {
            log.error("Exception closing nioSelector:", e);
        }
        sensors.close();
        channelBuilder.close();
    }

    /**
     * Queue the given request for sending in the subsequent {@link #poll(long)} calls
     * @param send The request to send
     */
    public void send(Send send) {
        //获取 broker 连接对应的 channel
        KafkaChannel channel = channelOrFail(send.destination());
        try {
            //将需要发送的数据跟 channel 所绑定
            channel.setSend(send);
        } catch (CancelledKeyException e) {
            this.failedSends.add(send.destination());
            close(channel);
        }
    }

    /**
     * Do whatever I/O can be done on each connection without blocking. This includes completing connections, completing
     * disconnections, initiating new sends, or making progress on in-progress sends or receives.
     *
     * When this call is completed the user can check for completed sends, receives, connections or disconnects using
     * {@link #completedSends()}, {@link #completedReceives()}, {@link #connected()}, {@link #disconnected()}. These
     * lists will be cleared at the beginning of each `poll` call and repopulated by the call if there is
     * any completed I/O.
     *
     * In the "Plaintext" setting, we are using socketChannel to read & write to the network. But for the "SSL" setting,
     * we encrypt the data before we use socketChannel to write data to the network, and decrypt before we return the responses.
     * This requires additional buffers to be maintained as we are reading from network, since the data on the wire is encrypted
     * we won't be able to read exact no.of bytes as kafka protocol requires. We read as many bytes as we can, up to SSLEngine's
     * application buffer size. This means we might be reading additional bytes than the requested size.
     * If there is no further data to read from socketChannel selector won't invoke that channel and we've have additional bytes
     * in the buffer. To overcome this issue we added "stagedReceives" map which contains per-channel deque. When we are
     * reading a channel we read as many responses as we can and store them into "stagedReceives" and pop one response during
     * the poll to add the completedReceives. If there are any active channels in the "stagedReceives" we set "timeout" to 0
     * and pop response and add to the completedReceives.
     *
     * @param timeout The amount of time to wait, in milliseconds, which must be non-negative
     * @throws IllegalArgumentException If `timeout` is negative
     * @throws IllegalStateException If a send is given for which we have no existing connection or for which there is
     *         already an in-progress send
     */
    @Override
    public void poll(long timeout) throws IOException {
        if (timeout < 0)
            throw new IllegalArgumentException("timeout should be >= 0");

        //清空队列
        clear();

        if (hasStagedReceives() || !immediatelyConnectedKeys.isEmpty())
            timeout = 0;

        /* check ready keys */
        long startSelect = time.nanoseconds();
        //调用底层nio 组件Selector.select，如果所有 socketChannel 都没有 IO 读写事件发生，最多阻塞指定时间
        int readyKeys = select(timeout);
        long endSelect = time.nanoseconds();
        currentTimeNanos = endSelect;
        this.sensors.selectTime.record(endSelect - startSelect, time.milliseconds());

        //如果有任一个socketChannel有 IO 读写时间发生
        if (readyKeys > 0 || !immediatelyConnectedKeys.isEmpty()) {
            //处理IO 读写事件具体逻辑
            pollSelectionKeys(this.nioSelector.selectedKeys(), false);
            pollSelectionKeys(immediatelyConnectedKeys, true);
        }

        //将stagedReceives队列中的部分数据放入到completedReceives队列中
        addToCompletedReceives();

        long endIo = time.nanoseconds();
        this.sensors.ioTime.record(endIo - endSelect, time.milliseconds());
        maybeCloseOldestConnection();
    }

    /**
     * 原生 NIO 读写处理逻辑
     * 梳理和总结一下，通过Kafka客户端源码的研究，对NIO的编程可以有非常好的认识和进步，就是完全掌握利用底层的NIO进行开发的技术，
     * 对不同事件的监听和取消监听，是通过二进制位运算的方式来实现的
     *
     * 但是其实人家NIO是支持同时监听一个连接上的多种事件的，就是通过位运算的
     *
     * key.interestOps() & ~ SelectionKey.OP_READ | SelectionKey.OP_WRITE，底层的NIO网络编程里是非常有实践意义的
     *
     * 拆包、粘包的工业级解决方案：
     * send 数据出现拆包，继续关注OP_WRITE事件，继续发送数据
     *
     *
     * @param selectionKeys
     * @param isImmediatelyConnected
     */
    private void pollSelectionKeys(Iterable<SelectionKey> selectionKeys, boolean isImmediatelyConnected) {
        Iterator<SelectionKey> iterator = selectionKeys.iterator();
        //遍历所有有事件发生的SelectionKey
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            //从集合中移除当前SelectionKey
            iterator.remove();
            //在创建连接时，注册到Selector 后执行了attach操作。这里对应可以通过attachment来获取
            KafkaChannel channel = channel(key);

            // register all per-connection metrics at once
            sensors.maybeRegisterConnectionMetrics(channel.id());
            //保存连接-->发生事件时的时间
            //lruConnections，因为一般来说一个客户端不能放太多的Socket连接资源，否则会导致这个客户端的复杂过重，
            //所以他需要采用lru的方式来不断的淘汰掉最近最少使用的一些连接，很多连接最近没怎么发送消息
            lruConnections.put(channel.id(), currentTimeNanos);

            try {

                /* complete any connections that have finished their handshake (either normally or immediately) */
                //如果发现SelectionKey当前处于的状态是可以建立连接，isConnectable方法是true
                if (isImmediatelyConnected || key.isConnectable()) {
                    //调用到KafkaChannel最底层的SocketChannel的finishConnect方法，等待这个连接必须执行完毕
                    //连接创建成功，取消关注OP_CONNECT事件，并且关注OP_READ事件
                    if (channel.finishConnect()) {
                        this.connected.add(channel.id());
                        this.sensors.connectionCreated.record();
                    } else
                        continue;
                }

                /* if channel is not ready finish prepare */
                if (channel.isConnected() && !channel.ready())
                    channel.prepare();

                /* if channel is ready read from any connections that have readable data */
                //处理网络读事件（接收请求）
                if (channel.ready() && key.isReadable() && !hasStagedReceive(channel)) {
                    NetworkReceive networkReceive;
                    //一个 broker 可以通过一个连接连续发送出去多个请求，这些请求可能都没有收到响应消息，此时 broker 端可能会连续处理完
                    //多个请求然后连续返回多个响应，所以这里通过 while 循环连续不断的读响应数据
                    while ((networkReceive = channel.read()) != null)
                        //将发送出去的消息放入到stagedReceives队列
                        addToStagedReceives(channel, networkReceive);
                }

                /* if channel is ready write to any sockets that have space in their buffer and for which we have data */
                //处理网络写事件（发送请求）
                //拆包现象：
                //如果Kafka一个请求一次write操作没有把全部的数据都写到broker去，相当于出现了类似于拆包的问题，一个请求一次没法发送完毕，此时如何处理的呢？
                //如果说一个请求对应的ByteBuffer中的二进制字节数据一次write没有全部发送完毕,底层ByteBufferSend的remaining是大于0，此时就不会取消对OP_WRITE事件的监听
                //如果出现拆包，这里的 send 返回的是 null
                //针对某个Broker，再次发送一个Request时，必须得先判断一下，这个Broker上一次发送的Request请求是否发送完毕了，并且还得限制为最多只发送5个request是没有收到响应的
                //如果说上一次 request出现了类似拆包的问题，一次请求没有发送完毕，此时下次就不会继续往这个broker发送请求了，但是此时针对这个broker还是保持着OP_WRITE的监听，下次调用poll，会发现对这个broker可以再次执行WRITABLE事件
                if (channel.ready() && key.isWritable()) {
                    Send send = channel.write();
                    if (send != null) {
                        //send数据全部发送成功才会放入到completedSends队列
                        this.completedSends.add(send);
                        this.sensors.recordBytesSent(channel.id(), send.size());
                    }
                }

                /* cancel any defunct sockets */
                if (!key.isValid()) {
                    close(channel);
                    this.disconnected.add(channel.id());
                }

            } catch (Exception e) {
                String desc = channel.socketDescription();
                if (e instanceof IOException)
                    log.debug("Connection with {} disconnected", desc, e);
                else
                    log.warn("Unexpected error from {}; closing connection", desc, e);
                //读写任何异常（包括超时），都会关闭连接，释放各种资源。后续会更新拉取元数据标识
                close(channel);
                this.disconnected.add(channel.id());
            }
        }
    }

    @Override
    public List<Send> completedSends() {
        return this.completedSends;
    }

    @Override
    public List<NetworkReceive> completedReceives() {
        return this.completedReceives;
    }

    @Override
    public List<String> disconnected() {
        return this.disconnected;
    }

    @Override
    public List<String> connected() {
        return this.connected;
    }

    @Override
    public void mute(String id) {
        KafkaChannel channel = channelOrFail(id);
        mute(channel);
    }

    private void mute(KafkaChannel channel) {
        channel.mute();
    }

    @Override
    public void unmute(String id) {
        KafkaChannel channel = channelOrFail(id);
        unmute(channel);
    }

    private void unmute(KafkaChannel channel) {
        channel.unmute();
    }

    @Override
    public void muteAll() {
        for (KafkaChannel channel : this.channels.values())
            mute(channel);
    }

    @Override
    public void unmuteAll() {
        for (KafkaChannel channel : this.channels.values())
            unmute(channel);
    }

    private void maybeCloseOldestConnection() {
        if (currentTimeNanos > nextIdleCloseCheckTime) {
            if (lruConnections.isEmpty()) {
                nextIdleCloseCheckTime = currentTimeNanos + connectionsMaxIdleNanos;
            } else {
                Map.Entry<String, Long> oldestConnectionEntry = lruConnections.entrySet().iterator().next();
                Long connectionLastActiveTime = oldestConnectionEntry.getValue();
                nextIdleCloseCheckTime = connectionLastActiveTime + connectionsMaxIdleNanos;
                if (currentTimeNanos > nextIdleCloseCheckTime) {
                    String connectionId = oldestConnectionEntry.getKey();
                    if (log.isTraceEnabled())
                        log.trace("About to close the idle connection from " + connectionId
                                + " due to being idle for " + (currentTimeNanos - connectionLastActiveTime) / 1000 / 1000 + " millis");

                    disconnected.add(connectionId);
                    close(connectionId);
                }
            }
        }
    }

    /**
     * Clear the results from the prior poll
     */
    private void clear() {
        this.completedSends.clear();
        this.completedReceives.clear();
        this.connected.clear();
        this.disconnected.clear();
        this.disconnected.addAll(this.failedSends);
        this.failedSends.clear();
    }

    /**
     * Check for data, waiting up to the given timeout.
     *
     * @param ms Length of time to wait, in milliseconds, which must be non-negative
     * @return The number of keys ready
     * @throws IllegalArgumentException
     * @throws IOException
     */
    private int select(long ms) throws IOException {
        if (ms < 0L)
            throw new IllegalArgumentException("timeout should be >= 0");

        if (ms == 0L)
            return this.nioSelector.selectNow();
        else
            return this.nioSelector.select(ms);
    }

    /**
     * Close the connection identified by the given id
     */
    public void close(String id) {
        KafkaChannel channel = this.channels.get(id);
        if (channel != null)
            close(channel);
    }

    /**
     * Begin closing this connection
     */
    private void close(KafkaChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            log.error("Exception closing connection to node {}:", channel.id(), e);
        }
        this.stagedReceives.remove(channel);
        this.channels.remove(channel.id());
        this.lruConnections.remove(channel.id());
        this.sensors.connectionClosed.record();
    }


    /**
     * check if channel is ready
     */
    @Override
    public boolean isChannelReady(String id) {
        KafkaChannel channel = this.channels.get(id);
        return channel != null && channel.ready();
    }

    private KafkaChannel channelOrFail(String id) {
        KafkaChannel channel = this.channels.get(id);
        if (channel == null)
            throw new IllegalStateException("Attempt to retrieve channel for which there is no open connection. Connection id " + id + " existing connections " + channels.keySet());
        return channel;
    }

    /**
     * Return the selector channels.
     */
    public List<KafkaChannel> channels() {
        return new ArrayList<>(channels.values());
    }

    /**
     * Return the channel associated with this connection or `null` if there is no channel associated with the
     * connection.
     */
    public KafkaChannel channel(String id) {
        return this.channels.get(id);
    }

    /**
     * Get the channel associated with selectionKey
     */
    private KafkaChannel channel(SelectionKey key) {
        return (KafkaChannel) key.attachment();
    }

    /**
     * Check if given channel has a staged receive
     */
    private boolean hasStagedReceive(KafkaChannel channel) {
        return stagedReceives.containsKey(channel);
    }

    /**
     * check if stagedReceives have unmuted channel
     */
    private boolean hasStagedReceives() {
        for (KafkaChannel channel : this.stagedReceives.keySet()) {
            if (!channel.isMute())
                return true;
        }
        return false;
    }


    /**
     * adds a receive to staged receives
     */
    private void addToStagedReceives(KafkaChannel channel, NetworkReceive receive) {
        if (!stagedReceives.containsKey(channel))
            stagedReceives.put(channel, new ArrayDeque<NetworkReceive>());

        Deque<NetworkReceive> deque = stagedReceives.get(channel);
        deque.add(receive);
    }

    /**
     * checks if there are any staged receives and adds to completedReceives
     * 如果一个连接一次OP_READ读取出来多个响应消息的话，在这里仅仅只会把每个连接对应的第一个响应消息会放到completedReceives里面去，
     * 供后续处理，此时有可能某个连接的stagedReceives是不为空的
     *
     * stagedReceives不为空，则下一次poll不会执行read操作，但是仍然会将队头第一个响应消息放入到completedReceives中，
     * 供后续处理响应并从inFlightRequests中移除
     */
    private void addToCompletedReceives() {
        if (!this.stagedReceives.isEmpty()) {
            Iterator<Map.Entry<KafkaChannel, Deque<NetworkReceive>>> iter = this.stagedReceives.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<KafkaChannel, Deque<NetworkReceive>> entry = iter.next();
                KafkaChannel channel = entry.getKey();
                //只有关注 OP_READ 事件，才会进行处理
                //RequestChannel的requestQueue队列在保存一个请求数据后，移除OP_READ 事件，这里就不会将数据继续放入到completedReceives中
                //只有待requestQueue队列处理完一个请求后，才能继续从stagedReceives获取数据放入到completedReceives中
                if (!channel.isMute()) {
                    Deque<NetworkReceive> deque = entry.getValue();
                    NetworkReceive networkReceive = deque.poll();
                    this.completedReceives.add(networkReceive);
                    this.sensors.recordBytesReceived(channel.id(), networkReceive.payload().limit());
                    if (deque.isEmpty())
                        iter.remove();
                }
            }
        }
    }


    private class SelectorMetrics {
        private final Metrics metrics;
        public final Sensor connectionClosed;
        public final Sensor connectionCreated;
        public final Sensor bytesTransferred;
        public final Sensor bytesSent;
        public final Sensor bytesReceived;
        public final Sensor selectTime;
        public final Sensor ioTime;

        /* Names of metrics that are not registered through sensors */
        private final List<MetricName> topLevelMetricNames = new ArrayList<>();
        private final List<Sensor> sensors = new ArrayList<>();

        public SelectorMetrics(Metrics metrics) {
            this.metrics = metrics;
            String metricGrpName = metricGrpPrefix + "-metrics";
            StringBuilder tagsSuffix = new StringBuilder();

            for (Map.Entry<String, String> tag: metricTags.entrySet()) {
                tagsSuffix.append(tag.getKey());
                tagsSuffix.append("-");
                tagsSuffix.append(tag.getValue());
            }

            this.connectionClosed = sensor("connections-closed:" + tagsSuffix.toString());
            MetricName metricName = metrics.metricName("connection-close-rate", metricGrpName, "Connections closed per second in the window.", metricTags);
            this.connectionClosed.add(metricName, new Rate());

            this.connectionCreated = sensor("connections-created:" + tagsSuffix.toString());
            metricName = metrics.metricName("connection-creation-rate", metricGrpName, "New connections established per second in the window.", metricTags);
            this.connectionCreated.add(metricName, new Rate());

            this.bytesTransferred = sensor("bytes-sent-received:" + tagsSuffix.toString());
            metricName = metrics.metricName("network-io-rate", metricGrpName, "The average number of network operations (reads or writes) on all connections per second.", metricTags);
            bytesTransferred.add(metricName, new Rate(new Count()));

            this.bytesSent = sensor("bytes-sent:" + tagsSuffix.toString(), bytesTransferred);
            metricName = metrics.metricName("outgoing-byte-rate", metricGrpName, "The average number of outgoing bytes sent per second to all servers.", metricTags);
            this.bytesSent.add(metricName, new Rate());
            metricName = metrics.metricName("request-rate", metricGrpName, "The average number of requests sent per second.", metricTags);
            this.bytesSent.add(metricName, new Rate(new Count()));
            metricName = metrics.metricName("request-size-avg", metricGrpName, "The average size of all requests in the window..", metricTags);
            this.bytesSent.add(metricName, new Avg());
            metricName = metrics.metricName("request-size-max", metricGrpName, "The maximum size of any request sent in the window.", metricTags);
            this.bytesSent.add(metricName, new Max());

            this.bytesReceived = sensor("bytes-received:" + tagsSuffix.toString(), bytesTransferred);
            metricName = metrics.metricName("incoming-byte-rate", metricGrpName, "Bytes/second read off all sockets", metricTags);
            this.bytesReceived.add(metricName, new Rate());
            metricName = metrics.metricName("response-rate", metricGrpName, "Responses received sent per second.", metricTags);
            this.bytesReceived.add(metricName, new Rate(new Count()));

            this.selectTime = sensor("select-time:" + tagsSuffix.toString());
            metricName = metrics.metricName("select-rate", metricGrpName, "Number of times the I/O layer checked for new I/O to perform per second", metricTags);
            this.selectTime.add(metricName, new Rate(new Count()));
            metricName = metrics.metricName("io-wait-time-ns-avg", metricGrpName, "The average length of time the I/O thread spent waiting for a socket ready for reads or writes in nanoseconds.", metricTags);
            this.selectTime.add(metricName, new Avg());
            metricName = metrics.metricName("io-wait-ratio", metricGrpName, "The fraction of time the I/O thread spent waiting.", metricTags);
            this.selectTime.add(metricName, new Rate(TimeUnit.NANOSECONDS));

            this.ioTime = sensor("io-time:" + tagsSuffix.toString());
            metricName = metrics.metricName("io-time-ns-avg", metricGrpName, "The average length of time for I/O per select call in nanoseconds.", metricTags);
            this.ioTime.add(metricName, new Avg());
            metricName = metrics.metricName("io-ratio", metricGrpName, "The fraction of time the I/O thread spent doing I/O", metricTags);
            this.ioTime.add(metricName, new Rate(TimeUnit.NANOSECONDS));

            metricName = metrics.metricName("connection-count", metricGrpName, "The current number of active connections.", metricTags);
            topLevelMetricNames.add(metricName);
            this.metrics.addMetric(metricName, new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return channels.size();
                }
            });
        }

        private Sensor sensor(String name, Sensor... parents) {
            Sensor sensor = metrics.sensor(name, parents);
            sensors.add(sensor);
            return sensor;
        }

        public void maybeRegisterConnectionMetrics(String connectionId) {
            if (!connectionId.isEmpty() && metricsPerConnection) {
                // if one sensor of the metrics has been registered for the connection,
                // then all other sensors should have been registered; and vice versa
                String nodeRequestName = "node-" + connectionId + ".bytes-sent";
                Sensor nodeRequest = this.metrics.getSensor(nodeRequestName);
                if (nodeRequest == null) {
                    String metricGrpName = metricGrpPrefix + "-node-metrics";

                    Map<String, String> tags = new LinkedHashMap<>(metricTags);
                    tags.put("node-id", "node-" + connectionId);

                    nodeRequest = sensor(nodeRequestName);
                    MetricName metricName = metrics.metricName("outgoing-byte-rate", metricGrpName, tags);
                    nodeRequest.add(metricName, new Rate());
                    metricName = metrics.metricName("request-rate", metricGrpName, "The average number of requests sent per second.", tags);
                    nodeRequest.add(metricName, new Rate(new Count()));
                    metricName = metrics.metricName("request-size-avg", metricGrpName, "The average size of all requests in the window..", tags);
                    nodeRequest.add(metricName, new Avg());
                    metricName = metrics.metricName("request-size-max", metricGrpName, "The maximum size of any request sent in the window.", tags);
                    nodeRequest.add(metricName, new Max());

                    String nodeResponseName = "node-" + connectionId + ".bytes-received";
                    Sensor nodeResponse = sensor(nodeResponseName);
                    metricName = metrics.metricName("incoming-byte-rate", metricGrpName, tags);
                    nodeResponse.add(metricName, new Rate());
                    metricName = metrics.metricName("response-rate", metricGrpName, "The average number of responses received per second.", tags);
                    nodeResponse.add(metricName, new Rate(new Count()));

                    String nodeTimeName = "node-" + connectionId + ".latency";
                    Sensor nodeRequestTime = sensor(nodeTimeName);
                    metricName = metrics.metricName("request-latency-avg", metricGrpName, tags);
                    nodeRequestTime.add(metricName, new Avg());
                    metricName = metrics.metricName("request-latency-max", metricGrpName, tags);
                    nodeRequestTime.add(metricName, new Max());
                }
            }
        }

        public void recordBytesSent(String connectionId, long bytes) {
            long now = time.milliseconds();
            this.bytesSent.record(bytes, now);
            if (!connectionId.isEmpty()) {
                String nodeRequestName = "node-" + connectionId + ".bytes-sent";
                Sensor nodeRequest = this.metrics.getSensor(nodeRequestName);
                if (nodeRequest != null)
                    nodeRequest.record(bytes, now);
            }
        }

        public void recordBytesReceived(String connection, int bytes) {
            long now = time.milliseconds();
            this.bytesReceived.record(bytes, now);
            if (!connection.isEmpty()) {
                String nodeRequestName = "node-" + connection + ".bytes-received";
                Sensor nodeRequest = this.metrics.getSensor(nodeRequestName);
                if (nodeRequest != null)
                    nodeRequest.record(bytes, now);
            }
        }

        public void close() {
            for (MetricName metricName : topLevelMetricNames)
                metrics.removeMetric(metricName);
            for (Sensor sensor : sensors)
                metrics.removeSensor(sensor.name());
        }
    }

}
