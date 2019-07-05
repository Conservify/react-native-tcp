/**
 * Copyright (c) 2015-present, Peel Technologies, Inc.
 * All rights reserved.
 */

#include <Foundation/Foundation.h>
#import <netinet/in.h>
#import <arpa/inet.h>
#import "TcpSocketClient.h"

#import <React/RCTLog.h>

NSString *const RCTTCPErrorDomain = @"RCTTCPErrorDomain";

@interface TcpSocketClient()
{
@private
    GCDAsyncSocket *_tcpSocket;
    NSString *_destination;
    NSFileHandle *_fh;
    int _received;
    bool _gotHeader;
    NSMutableDictionary<NSNumber *, RCTResponseSenderBlock> *_pendingSends;
    NSLock *_lock;
    NSDate *_lastProgress;
    long _sendTag;
}

- (id)initWithClientId:(NSNumber *)clientID andConfig:(id<SocketClientDelegate>)aDelegate;
- (id)initWithClientId:(NSNumber *)clientID andConfig:(id<SocketClientDelegate>)aDelegate andSocket:(GCDAsyncSocket*)tcpSocket;

@end

@implementation TcpSocketClient

+ (id)socketClientWithId:(nonnull NSNumber *)clientID andConfig:(id<SocketClientDelegate>)delegate
{
    return [[[self class] alloc] initWithClientId:clientID andConfig:delegate andSocket:nil];
}

- (id)initWithClientId:(NSNumber *)clientID andConfig:(id<SocketClientDelegate>)aDelegate
{
    return [self initWithClientId:clientID andConfig:aDelegate andSocket:nil];
}

- (id)initWithClientId:(NSNumber *)clientID andConfig:(id<SocketClientDelegate>)aDelegate andSocket:(GCDAsyncSocket*)tcpSocket;
{
    self = [super init];
    if (self) {
        _id = clientID;
        _clientDelegate = aDelegate;
        _pendingSends = [NSMutableDictionary dictionary];
        _lock = [[NSLock alloc] init];
        _received = 0;
        _destination = nil;
        _fh = nil;
        _tcpSocket = tcpSocket;
        _lastProgress = nil;
        [_tcpSocket setUserData: clientID];
    }

    return self;
}

- (BOOL)connect:(NSString *)host port:(int)port withOptions:(NSDictionary *)options error:(NSError **)error
{
    if (_tcpSocket) {
        if (error) {
            *error = [self badInvocationError:@"this client's socket is already connected"];
        }

        return false;
    }

    _tcpSocket = [[GCDAsyncSocket alloc] initWithDelegate:self delegateQueue:[self methodQueue]];
    [_tcpSocket setUserData: _id];

    BOOL result = false;

    NSString *localAddress = (options ? options[@"localAddress"] : nil);
    NSNumber *localPort = (options ? options[@"localPort"] : nil);
    NSString *destination = (options ? options[@"write"] : nil);

    _destination = destination;
    _received = 0;

    RCTLogInfo(@"CONNECT");
    RCTLogInfo(@"%@", options);
    if (_destination != nil) {
        RCTLogInfo(@"Dest: %s", _destination);
    }

    if (!localAddress && !localPort) {
        result = [_tcpSocket connectToHost:host onPort:port error:error];
    } else {
        NSMutableArray *interface = [NSMutableArray arrayWithCapacity:2];
        [interface addObject: localAddress?localAddress:@""];
        if (localPort) {
            [interface addObject:[localPort stringValue]];
        }
        result = [_tcpSocket connectToHost:host
                                    onPort:port
                              viaInterface:[interface componentsJoinedByString:@":"]
                               withTimeout:-1
                                     error:error];
    }

    return result;
}

- (NSDictionary<NSString *, id> *)getAddress
{
    if (_tcpSocket)
    {
        if (_tcpSocket.isConnected) {
            return @{ @"port": @(_tcpSocket.connectedPort),
                      @"address": _tcpSocket.connectedHost ?: @"unknown",
                      @"family": _tcpSocket.isIPv6?@"IPv6":@"IPv4" };
        } else {
            return @{ @"port": @(_tcpSocket.localPort),
                      @"address": _tcpSocket.localHost ?: @"unknown",
                      @"family": _tcpSocket.isIPv6?@"IPv6":@"IPv4" };
        }
    }

    return @{ @"port": @(0),
              @"address": @"unknown",
              @"family": @"unkown" };
}

- (BOOL)listen:(NSString *)host port:(int)port error:(NSError **)error
{
    if (_tcpSocket) {
        if (error) {
            *error = [self badInvocationError:@"this client's socket is already connected"];
        }

        return false;
    }

    _tcpSocket = [[GCDAsyncSocket alloc] initWithDelegate:self delegateQueue:[self methodQueue]];
    [_tcpSocket setUserData: _id];

    // GCDAsyncSocket doesn't recognize 0.0.0.0
    if ([@"0.0.0.0" isEqualToString: host]) {
        host = @"localhost";
    }
    BOOL isListening = [_tcpSocket acceptOnInterface:host port:port error:error];
    if (isListening == YES) {
        [_clientDelegate onConnect: self];
        [_tcpSocket readDataWithTimeout:-1 tag:_id.longValue];
    }

    return isListening;
}

- (void)setPendingSend:(RCTResponseSenderBlock)callback forKey:(NSNumber *)key
{
    [_lock lock];
    @try {
        [_pendingSends setObject:callback forKey:key];
    }
    @finally {
        [_lock unlock];
    }
}

- (RCTResponseSenderBlock)getPendingSend:(NSNumber *)key
{
    [_lock lock];
    @try {
        return [_pendingSends objectForKey:key];
    }
    @finally {
        [_lock unlock];
    }
}

- (void)dropPendingSend:(NSNumber *)key
{
    [_lock lock];
    @try {
        [_pendingSends removeObjectForKey:key];
    }
    @finally {
        [_lock unlock];
    }
}

- (void)socket:(GCDAsyncSocket *)sock didWriteDataWithTag:(long)msgTag
{
    NSNumber* tagNum = [NSNumber numberWithLong:msgTag];
    RCTResponseSenderBlock callback = [self getPendingSend:tagNum];
    if (callback) {
        callback(@[]);
        [self dropPendingSend:tagNum];
    }
}

- (void) writeData:(NSData *)data
          callback:(RCTResponseSenderBlock)callback
{
    if (callback) {
        [self setPendingSend:callback forKey:@(_sendTag)];
    }
    [_tcpSocket writeData:data withTimeout:-1 tag:_sendTag];

    _sendTag++;

    [_tcpSocket readDataWithTimeout:-1 tag:_id.longValue];
}

- (void)end
{
    [_tcpSocket disconnectAfterWriting];
}

- (void)destroy
{
    [_tcpSocket disconnect];
}

- (UInt32)decode32FromBytes:(NSData *)data offset:(UInt32 *)offsetPointer {
    UInt8 *octets = (UInt8 *)[data bytes];
    UInt32 offset = *offsetPointer;
    UInt32 result = 0;
    int shift = 0;
    while(YES) {
        result += ((octets[offset] & 0x7F) << shift);
        if (octets[offset] < 128) {
            *offsetPointer = offset + 1;
            return result;
        }
        offset++;
        shift += 7;
    }
}

- (bool)updateProgress {
    NSDate *now = [NSDate date];
    if (_lastProgress == nil) {
        _lastProgress = now;
        return true;
    }
    NSTimeInterval since = [now timeIntervalSinceDate: _lastProgress];
    if (since > 1) {
        _lastProgress = now;
        return true;
    }
    return false;
}

- (void)socket:(GCDAsyncSocket *)sock didReadData:(NSData *)data withTag:(long)tag {
    if (!_clientDelegate) {
        RCTLogWarn(@"didReadData with nil clientDelegate for %@", [sock userData]);
        return;
    }
    
    if (_destination != nil) {
        NSFileManager *fm = [NSFileManager defaultManager];
        NSData *wrote = data;
        
        if (!_gotHeader) {
            UInt32 offset = 0;
            UInt32 headerLength = [self decode32FromBytes:data offset:&offset];

            NSRange headerRange = { 0, offset + headerLength };
            NSData *headerData = [data subdataWithRange:headerRange];

            [_clientDelegate onHeader:@(tag) data:headerData];
            
            NSRange remaining = { offset + headerLength, [data length] - offset - headerLength };
            wrote = [data subdataWithRange:remaining];

            // RCTLogInfo(@"OK %d %d %d %d", headerLength, offset, [data length], [wrote length]);
            
            _gotHeader = true;
        }
        
        if (_fh == nil)
        {
            BOOL success = [fm createFileAtPath:_destination contents:wrote attributes:nil];
            if (!success) {
                RCTLogWarn(@"Error creating file.");
            }
            _fh = [NSFileHandle fileHandleForUpdatingAtPath:_destination];
            [_fh seekToEndOfFile];
        }
        else
        {
            [_fh writeData:wrote];
        }
        
        _received += [wrote length];
        
        if ([self updateProgress]) {
            RCTLogInfo(@"Progress %d", _received);
            [_clientDelegate onProgress:@(tag) didReceive:_received];
        }
    }
    else {
        [_clientDelegate onData:@(tag) data:data];
    }
    
    [sock readDataWithTimeout:-1 tag:tag];
}

- (void)socket:(GCDAsyncSocket *)sock didAcceptNewSocket:(GCDAsyncSocket *)newSocket
{
    TcpSocketClient *inComing = [[TcpSocketClient alloc] initWithClientId:[_clientDelegate getNextId]
                                                                andConfig:_clientDelegate
                                                                andSocket:newSocket];
    [_clientDelegate onConnection: inComing
                         toClient: _id];
    [newSocket readDataWithTimeout:-1 tag:inComing.id.longValue];
}

- (void)socket:(GCDAsyncSocket *)sock didConnectToHost:(NSString *)host port:(uint16_t)port
{
    if (!_clientDelegate) {
        RCTLogWarn(@"didConnectToHost with nil clientDelegate for %@", [sock userData]);
        return;
    }

    [_clientDelegate onConnect:self];

    [sock readDataWithTimeout:-1 tag:_id.longValue];
}

- (void)socketDidCloseReadStream:(GCDAsyncSocket *)sock
{
    // TODO : investigate for half-closed sockets
    // for now close the stream completely
    [sock disconnect];
}

- (void)socketDidDisconnect:(GCDAsyncSocket *)sock withError:(NSError *)err
{
    if (!_clientDelegate) {
        RCTLogWarn(@"socketDidDisconnect with nil clientDelegate for %@", [sock userData]);
        return;
    }

    [_clientDelegate onClose:[sock userData] withError:(!err || err.code == GCDAsyncSocketClosedError ? nil : err)];
}

- (NSError *)badInvocationError:(NSString *)errMsg
{
    NSDictionary *userInfo = [NSDictionary dictionaryWithObject:errMsg forKey:NSLocalizedDescriptionKey];

    return [NSError errorWithDomain:RCTTCPErrorDomain
                               code:RCTTCPInvalidInvocationError
                           userInfo:userInfo];
}

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}

@end
