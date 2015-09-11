package io.servicefabric.transport;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.transform;
import static io.servicefabric.transport.TransportChannel.Status.CLOSED;
import static io.servicefabric.transport.TransportChannel.Status.CONNECTED;
import static io.servicefabric.transport.TransportChannel.Status.CONNECT_IN_PROGRESS;
import static io.servicefabric.transport.utils.ChannelFutureUtils.setPromise;

import io.servicefabric.transport.protocol.Message;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.util.concurrent.SettableFuture;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Func1;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

final class TransportChannel implements ITransportChannel {
  static final Logger LOGGER = LoggerFactory.getLogger(TransportChannel.class);

  static final AttributeKey<TransportChannel> ATTR_TRANSPORT = AttributeKey.valueOf("transport");

  enum Status {
    CONNECT_IN_PROGRESS, CONNECTED, HANDSHAKE_IN_PROGRESS, HANDSHAKE_PASSED, READY, CLOSED
  }

  final Channel channel;
  final ITransportSpi transportSpi;
  private final AtomicReference<Status> status = new AtomicReference<>();
  private final AtomicReference<Throwable> cause = new AtomicReference<>();
  private Func1<TransportChannel, Void> whenClose;
  private volatile TransportData remoteHandshake;

  private TransportChannel(Channel channel, ITransportSpi transportSpi) {
    this.channel = channel;
    this.transportSpi = transportSpi;
  }

  /**
   * Setter for {@link #remoteHandshake}. Called when handshake passed successfully (RESOLVED_OK) on both sides.
   *
   * @param remoteHandshake remote handshake (non null)
   */
  void setRemoteHandshake(TransportData remoteHandshake) {
    checkArgument(remoteHandshake != null);
    this.remoteHandshake = remoteHandshake;
  }

  /**
   * Origin/Destination of this transport.
   *
   * @return TransportEndpoint object this transport is referencing to; or {@code null} if this transport isn't READY
   *         yet
   */
  @Nullable
  public TransportEndpoint getRemoteEndpoint() {
    return remoteHandshake != null ? (TransportEndpoint) remoteHandshake.get(TransportData.META_ORIGIN_ENDPOINT) : null;
  }

  /**
   * Identity of the Origin/Destination of this transport.
   *
   * @return TransportEndpoint object this transport is referencing to; or {@code null} if this transport isn't READY
   *         yet
   */
  @Nullable
  public String getRemoteEndpointId() {
    return remoteHandshake != null ? (String) remoteHandshake.get(TransportData.META_ORIGIN_ENDPOINT_ID) : null;
  }

  @Override
  public void send(@CheckForNull Message message) {
    send(message, null);
  }

  @Override
  public void send(@CheckForNull Message message, @Nullable SettableFuture<Void> promise) {
    checkArgument(message != null);
    if (promise != null && getCause() != null) {
      promise.setException(getCause());
      return;
    }
    setPromise(channel.writeAndFlush(message), promise);
  }

  @Override
  public void close(@Nullable SettableFuture<Void> promise) {
    close(null/* cause */, promise);
  }

  void close(Throwable cause) {
    close(cause, null/* promise */);
  }

  void close() {
    close(null/* cause */, null/* promise */);
  }

  void close(Throwable cause, SettableFuture<Void> promise) {
    this.cause.compareAndSet(null, cause != null ? cause : new TransportClosedException(this));
    status.set(CLOSED);
    whenClose.call(this);
    setPromise(channel.close(), promise);
    LOGGER.debug("Closed {}", this);
  }

  /**
   * Flips the {@link #status}.
   *
   * @throws TransportBrokenException in case {@code expect} not actual
   */
  void flip(Status expect, Status update) throws TransportBrokenException {
    if (!status.compareAndSet(expect, update)) {
      String err = "Can't set status " + update + " (expect=" + expect + ", actual=" + status + ")";
      throw new TransportBrokenException(this, err);
    }
  }

  Throwable getCause() {
    return cause.get();
  }

  @Override
  public String toString() {
    if (getCause() == null) {
      return "NettyTransport{" + "status=" + status + ", channel=" + channel + '}';
    }
    Class clazz = getCause().getClass();
    String packageName = clazz.getPackage().getName();
    String dottedPackageName =
        Joiner.on('.').join(transform(Splitter.on('.').split(packageName), new Function<String, Character>() {
          @Override
          public Character apply(String input) {
            return input.charAt(0);
          }
        }));
    return "NettyTransport{" + "status=" + status + ", cause=[" + dottedPackageName + "." + clazz.getSimpleName() + "]"
        + ", channel=" + channel + '}';
  }

  static final class Builder {
    private TransportChannel target;

    static Builder connector(Channel channel, ITransportSpi transportSpi) {
      Builder builder = new Builder();
      builder.target = new TransportChannel(channel, transportSpi);
      builder.target.status.set(CONNECT_IN_PROGRESS);
      return builder;
    }

    static Builder acceptor(Channel channel, ITransportSpi transportSpi) {
      Builder builder = new Builder();
      builder.target = new TransportChannel(channel, transportSpi);
      builder.target.status.set(CONNECTED);
      return builder;
    }

    Builder set(Func1<TransportChannel, Void> func) {
      target.whenClose = func;
      return this;
    }

    TransportChannel build() {
      return target;
    }
  }
}