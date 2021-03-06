package io.scalecube.services.transport.rsocket;

import io.rsocket.core.RSocketServer;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.netty.server.CloseableChannel;
import io.rsocket.transport.netty.server.TcpServerTransport;
import io.scalecube.net.Address;
import io.scalecube.services.methods.ServiceMethodRegistry;
import io.scalecube.services.transport.api.ServerTransport;
import io.scalecube.services.transport.api.ServiceMessageCodec;
import java.net.InetSocketAddress;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpServer;

/** RSocket server transport implementation. */
public class RSocketServerTransport implements ServerTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(RSocketServerTransport.class);

  private final ServiceMessageCodec messageCodec;
  private final TcpServer tcpServer;

  private CloseableChannel serverChannel; // calculated

  /**
   * Constructor for this server transport.
   *
   * @param messageCodec message codec
   * @param tcpServer tcp server
   */
  public RSocketServerTransport(ServiceMessageCodec messageCodec, TcpServer tcpServer) {
    this.messageCodec = messageCodec;
    this.tcpServer = tcpServer;
  }

  @Override
  public Address address() {
    InetSocketAddress address = serverChannel.address();
    return Address.create(address.getHostString(), address.getPort());
  }

  @Override
  public Mono<ServerTransport> bind(ServiceMethodRegistry methodRegistry) {
    return Mono.defer(
        () -> {
          TcpServer tcpServer =
              this.tcpServer.doOnConnection(
                  connection -> {
                    LOGGER.debug(
                        "[rsocket][server] Accepted connection on {}", connection.channel());
                    connection.onDispose(
                        () ->
                            LOGGER.debug(
                                "[rsocket][server] Connection closed on {}", connection.channel()));
                  });
          return RSocketServer.create()
              .acceptor(new RSocketServiceAcceptor(messageCodec, methodRegistry))
              .payloadDecoder(PayloadDecoder.DEFAULT)
              .bind(TcpServerTransport.create(tcpServer))
              .doOnSuccess(channel -> serverChannel = channel)
              .thenReturn(this);
        });
  }

  @Override
  public Mono<Void> stop() {
    return Mono.defer(
        () -> {
          if (serverChannel == null || serverChannel.isDisposed()) {
            return Mono.empty();
          }
          return Mono.fromRunnable(() -> serverChannel.dispose())
              .then(
                  serverChannel
                      .onClose()
                      .doOnError(
                          e ->
                              LOGGER.warn(
                                  "[rsocket][server][onClose] Exception occurred: {}",
                                  e.toString())));
        });
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", RSocketServerTransport.class.getSimpleName() + "[", "]")
        .add("messageCodec=" + messageCodec)
        .add("tcpServer=" + tcpServer)
        .add("serverChannel=" + serverChannel)
        .toString();
  }
}
