/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;

/**
 * An HTTP ready {@link ChannelOperations} with state management for status and headers
 * (first HTTP response packet).
 *
 * @author Stephane Maldini
 */
public abstract class HttpOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		extends ChannelOperations<INBOUND, OUTBOUND> implements HttpInfos {

	volatile int statusAndHeadersSent = 0;

	protected HttpOperations(Channel ioChannel,
			HttpOperations<INBOUND, OUTBOUND> replaced) {
		super(ioChannel, replaced);
		this.statusAndHeadersSent = replaced.statusAndHeadersSent;
	}

	protected HttpOperations(Channel ioChannel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		super(ioChannel, handler, context);
	}

	/**
	 * Has headers been sent
	 *
	 * @return true if headers have been sent
	 */
	public final boolean hasSentHeaders() {
		return statusAndHeadersSent == 1;
	}

	@Override
	public boolean isWebsocket() {
		return false;
	}

	//@Override
	//TODO document this
	public NettyOutbound sendHeaders() {
		if (markHeadersAsSent()) {
			if (HttpUtil.isContentLengthSet(outboundHttpMessage())) {
				outboundHttpMessage().headers()
				                     .remove(HttpHeaderNames.TRANSFER_ENCODING);
			}

			HttpMessage message;
			if (!HttpUtil.isTransferEncodingChunked(outboundHttpMessage())
					&& (!HttpUtil.isContentLengthSet(outboundHttpMessage()) ||
			HttpUtil.getContentLength(outboundHttpMessage(), 0) == 0)) {
				if(isKeepAlive()){
					message = newFullEmptyBodyMessage();
				}
				else {
					markPersistent(false);
					message = outboundHttpMessage();
				}
			}
			else {
				message = outboundHttpMessage();
			}
			return then(FutureMono.deferFuture(() -> {
				if(!channel().isActive()){
					throw new AbortedException();
				}
				return channel().writeAndFlush(message);
			}));
		}
		else {
			return this;
		}
	}

	@Override
	public Mono<Void> then() {
		if (markHeadersAsSent()) {
			if (HttpUtil.isContentLengthSet(outboundHttpMessage())) {
				outboundHttpMessage().headers()
				                     .remove(HttpHeaderNames.TRANSFER_ENCODING);
			}

			if (!HttpUtil.isTransferEncodingChunked(outboundHttpMessage())
					&& !HttpUtil.isContentLengthSet(outboundHttpMessage())) {
				markPersistent(false);
			}

			return FutureMono.deferFuture(() -> channel().writeAndFlush(outboundHttpMessage()));
		}
		else {
			return Mono.empty();
		}
	}

	protected abstract HttpMessage newFullEmptyBodyMessage();

	@Override
	public final NettyOutbound sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file);

		if (hasSentHeaders()) {
			return super.sendFile(file, position, count);
		}

		if (!HttpUtil.isTransferEncodingChunked(outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
				outboundHttpMessage()) && count < Integer.MAX_VALUE) {
			outboundHttpMessage().headers()
			                     .setInt(HttpHeaderNames.CONTENT_LENGTH, (int) count);
		}
		else if (!HttpUtil.isContentLengthSet(outboundHttpMessage())) {
			outboundHttpMessage().headers()
			                     .remove(HttpHeaderNames.CONTENT_LENGTH)
			                     .remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(outboundHttpMessage(), true);
		}

		return super.sendFile(file, position, count);
	}

	@Override
	protected void onInboundCancel() {
		if (!isInboundDone()) {
			channel().read();
		}
	}

	@Override
	public String toString() {
		if (isWebsocket()) {
			return "ws:" + uri();
		}

		return method().name() + ":" + uri();
	}

	@Override
	public HttpOperations<INBOUND, OUTBOUND> addHandler(String name, ChannelHandler handler) {
		super.addHandler(name, handler);

		if(channel().pipeline().context(handler) == null){
			return this;
		}

		autoAddHttpExtractor(this, name, handler);
		return this;
	}

	static void autoAddHttpExtractor(NettyContext c, String name, ChannelHandler
			handler){

		if (handler instanceof ByteToMessageDecoder
				|| handler instanceof ByteToMessageCodec
				|| handler instanceof CombinedChannelDuplexHandler) {
			String extractorName = name+"$extractor";

			if(c.channel().pipeline().context(extractorName) != null){
				return;
			}

			c.channel().pipeline().addBefore(name, extractorName, HTTP_EXTRACTOR);

			if(NettyContext.isPersistent(c.channel())){
				c.onClose(() -> c.removeHandler(extractorName));
			}

		}
	}

	/**
	 * Mark the headers sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markHeadersAsSent() {
		return HEADERS_SENT.compareAndSet(this, 0, 1);
	}

	/**
	 * Outbound Netty HttpMessage
	 *
	 * @return Outbound Netty HttpMessage
	 */
	protected abstract HttpMessage outboundHttpMessage();

	final static AtomicIntegerFieldUpdater<HttpOperations> HEADERS_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpOperations.class,
					"statusAndHeadersSent");

	final static ChannelInboundHandler HTTP_EXTRACTOR = NettyPipeline.inboundHandler(
			(ctx, msg) -> {
				if (msg instanceof ByteBufHolder) {
					ByteBuf bb = ((ByteBufHolder) msg).content();
					if(msg instanceof FullHttpMessage){
						// TODO convert into 2 messages if FullHttpMessage
						ctx.fireChannelRead(msg);
					}
					else {
						ctx.fireChannelRead(bb);
						if (msg instanceof LastHttpContent) {
							ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
						}
					}
				}
				else {
					ctx.fireChannelRead(msg);
				}
			}
	);
}
