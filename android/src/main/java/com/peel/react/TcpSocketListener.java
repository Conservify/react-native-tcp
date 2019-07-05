package com.peel.react;

import java.net.InetSocketAddress;

/**
 * Created by aprock on 12/28/15.
 */
public interface TcpSocketListener {
    // server
    void onConnection(Integer serverId, Integer clientId, InetSocketAddress socketAddress, SocketSettings settings);

    // client and server
    void onConnect(Integer id, InetSocketAddress socketAddress, SocketSettings settings);
    void onData(Integer id, byte[] data, SocketSettings settings);
    void onClose(Integer id, String error, SocketSettings settings);
    void onError(Integer id, String error, SocketSettings settings);
}
