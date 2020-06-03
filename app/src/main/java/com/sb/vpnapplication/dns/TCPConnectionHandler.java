package com.sb.vpnapplication.dns;

import android.util.Log;

import com.sb.vpnapplication.logger.LoggerHelper;
import com.sb.vpnapplication.logger.RemoteLogger;

import static com.sb.vpnapplication.logger.LoggerFormatter.format;

public class TCPConnectionHandler {
	private static final java.util.logging.Logger log = LoggerHelper.getLogger(TCPConnectionHandler.class);
	private static SocketProtector socketProtector;
	private final boolean dataDebug;
	private static final int IN_BUFFER_RESERVED_SPACE = 40;
	static final int IN_BUFFER_SIZE = 1400;
	private static java.nio.ByteBuffer inBuffer = java.nio.ByteBuffer.allocate(IN_BUFFER_SIZE);
	private static java.nio.ByteBuffer connectBuffer = java.nio.ByteBuffer.allocate(1024);
	private static java.nio.channels.Selector selector;
	private boolean connected;
	private boolean closed;
	private final String realTarget;
	private final java.nio.channels.SocketChannel channel;
	private static final java.util.LinkedHashMap<TCPConnectionHandler, java.net.InetSocketAddress[]> pendingRegistrations = new java.util.LinkedHashMap<>();
	private TCPDataConsumer actionCallback;
	private static java.util.HashMap<java.net.InetAddress, ProxyConfig> fakeAddrRegistry = new java.util.HashMap<>();
	private final java.net.InetSocketAddress client;
	private java.net.InetSocketAddress remote;

	private static class ProxyConfig {
		final String domainName;
		final java.net.InetSocketAddress[] proxyAddress;

		ProxyConfig(String domainName, java.net.InetSocketAddress[] proxyAddress) {
			this.domainName = domainName;
			this.proxyAddress = proxyAddress;
		}
	};

	private TCPConnectionHandler(java.net.InetSocketAddress[] connTarget, java.net.InetSocketAddress client,
			String realTarget) {
		this.dataDebug = false; // Too much data... ll == null || ll.intValue() < java.util.logging.Level.FINE.intValue();
		this.client = client;
		this.realTarget = realTarget;
		java.nio.channels.SocketChannel sc;
		try {
			sc = java.nio.channels.SocketChannel.open();
			sc.configureBlocking(false);
			if (socketProtector != null) {
				if (!socketProtector.protect(sc.socket())) {
					log.warning("Protect socket failed");
					sc.close();
					close();
				}
			}
		} catch (Exception ex) {
			log.warning(format("Couldn't open channel ", ex).toString());
			close();
			sc = null;
		}
		this.channel = sc;
		if (sc != null) {
			pendingRegistrations.put(this, connTarget);
			selector.wakeup();
		}
	}

	public void setActionCallback(TCPDataConsumer actionCallback) {
		this.actionCallback = actionCallback;
	}

	public boolean isConnected() {
		return this.connected;
	}

	public static void setSocketProtector(SocketProtector socketProtector) {
		TCPConnectionHandler.socketProtector = socketProtector;
	}

	public boolean isClosed() {
		return this.closed;
	}

	public static void registerFakeAddress(java.net.InetAddress addr, String domainName,
			java.net.InetSocketAddress[] proxy) {
		log.fine("Register: " + domainName + " > " + addr.getHostAddress());
		fakeAddrRegistry.put(addr, new ProxyConfig(domainName, proxy));
	}

	static TCPConnectionHandler getHandler(java.net.InetSocketAddress client, java.net.InetAddress dest, int destPort) {
		if (selector == null) { // something's wrong with initialization?..
			log.warning("Can't work without selector!");
			return null;
		}

		ProxyConfig pc = fakeAddrRegistry.get(dest);
		java.net.InetSocketAddress[] proxy;
		if (pc != null) {
			String at = pc.domainName;
			if (at == null || "".equals(at)) {
				at = dest.getHostAddress();
			}
			if (at.indexOf(":") < 0) {
				at += ":" + destPort;
			}
			return new TCPConnectionHandler(pc.proxyAddress, client, at);
		}
		else {
				proxy = Configurator.getInstance().getProxyForIP(dest);
				Log.d("zahid","hereeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
				if(proxy!=null){
					Log.d("zahid",proxy.toString());
					return new TCPConnectionHandler(proxy, client, dest.getHostAddress() + ":" + destPort);
				}
				else
					return null;
			}
	}

	public int send(java.nio.ByteBuffer data) {
		try {
			int pos = data.position();
			int res = channel.write(data);
			log.fine("DATA >>> " + res + " " + this.channel + " " + client);
			if (dataDebug) {
				log.log(java.util.logging.Level.FINEST, "{0}", format(data.array(), pos, res));
			}
			return res;
		} catch (java.nio.channels.ClosedChannelException ex) {
			return (-1);
		} catch (java.io.IOException ex) {
			log.fine(format("write failed ", ex).toString());
			return (-1);
		}
	}

	private static boolean checkShutdownOutputMethod = true;
	private static java.lang.reflect.Method shutdownMethod;

	void shutdownOutput() {
		if (!channel.isOpen() || !channel.isConnected()) { // nothing to do anymore
			return;
		}
		log.fine("DATA >>> flush " + this.channel);
		try {
			if (checkShutdownOutputMethod) {
				try { // we need to do this crap because Android API 21 didn't implement it [properly]
					shutdownMethod = java.nio.channels.SocketChannel.class.getMethod("shutdownOutput");
				} catch (Throwable ignore) {
					log.fine(
							format("SocketChannel.shutdownOutput() not found (API level < 24) will try Socket.shutdownOutput() instead ",
									ignore).toString());
				}
				checkShutdownOutputMethod = false;
			}
			if (shutdownMethod != null) {
				shutdownMethod.invoke(channel);
			} else {
				channel.socket().shutdownOutput();
			}
		} catch (Throwable ex) {
			log.warning(format("shutdownOutput failed ", ex).toString());
		}
	}

	private static final java.nio.charset.Charset ASCII = java.nio.charset.Charset.forName("ASCII");

	private void selected(java.nio.channels.SelectionKey sk) {
		if (!sk.isValid()) { // closed already?
			close();
			return;
		}
		if (sk.isConnectable() && !channel.isConnected()) {
			try {
				if (!channel.finishConnect()) {
					close();
				} else {
					sk.interestOps(java.nio.channels.SelectionKey.OP_READ);
					if (realTarget == null) {
						RemoteLogger.log(format("Connected to ", remote));
						this.connected = true;
					} else {
						RemoteLogger.log(format("Connected to ", remote, " for ", realTarget));
						connectBuffer.clear();
						log.fine(realTarget + " for " + client);
						connectBuffer.put(("CONNECT " + realTarget + " HTTP/1.1\r\nHost: " + realTarget + "\r\n\r\n")
								.getBytes(ASCII)).flip();
						channel.write(connectBuffer);
						return;
					}
				}
			} catch (java.io.IOException ex) {
				RemoteLogger.log(format("Connection to ", this, " for ", realTarget));
				log.warning(format("connect to ", remote, " failed ", ex).toString());
				close();
			}
		}
		if (!channel.isOpen() || !channel.isConnected()) {
			actionCallback.consume(null);
			return;
		}
		inBuffer.position(IN_BUFFER_RESERVED_SPACE);
		if (sk.isReadable()) {
			inBuffer.limit(IN_BUFFER_SIZE);
			int size;
			try {
				size = channel.read(inBuffer);
				if (!this.connected && realTarget != null && size > 10) { // expecting 200 in the first line
					String s = new String(inBuffer.array(), IN_BUFFER_RESERVED_SPACE, size, ASCII);
					log.finer(s);
					int pos = s.indexOf("\n");
					if (pos > 0 && s.substring(0, pos).indexOf(" 200 ") > 0) {
						// we want to send notification ASAP, so connection will be accepted
						actionCallback.consume(connectBuffer.position(0).limit(0));
						this.connected = true;
						pos = s.indexOf("\r\n\r\n") + 4;
						if (size > pos) {
							size -= pos;
							inBuffer.position(IN_BUFFER_RESERVED_SPACE + pos);
						} else {
							size = 0;
						}
					} else {
						RemoteLogger.log(format("Server ", remote, " rejected connection to ", realTarget));
						size = (-1); // will force close
					}
				}
				while (size > 0) {
					log.fine("DATA <<< " + size + " " + this.channel);
					if (dataDebug) {
						log.log(java.util.logging.Level.FINEST, "{0}",
								format(inBuffer.array(), IN_BUFFER_RESERVED_SPACE, size));
					}
					inBuffer.position(IN_BUFFER_RESERVED_SPACE);
					inBuffer.limit(IN_BUFFER_RESERVED_SPACE + size);
					actionCallback.consume(inBuffer);
					inBuffer.position(IN_BUFFER_RESERVED_SPACE);
					inBuffer.limit(IN_BUFFER_SIZE);
					size = channel.read(inBuffer);
				}
			} catch (java.io.IOException ex) {
				log.finer(format("read exception ", ex).toString());
				size = (-1);
			}
			if (size < 0) {
				log.fine("DATA <<< closed " + this.channel);
				close();
				actionCallback.consume(null);
				return;
			} else {
				inBuffer.position(IN_BUFFER_RESERVED_SPACE);
				inBuffer.limit(IN_BUFFER_RESERVED_SPACE + size);
			}
		} else {
			inBuffer.limit(IN_BUFFER_RESERVED_SPACE);
		}
		actionCallback.consume(inBuffer);
		log.fine(sk.toString());
	}

	private static final int roundRobinCounter = new java.util.Random().nextInt(1000);

	private static void prepareSelector() {
		if (selector != null) { // should never happen
			return;
		}
		try {
			selector = java.nio.channels.Selector.open();
			final java.nio.channels.Selector sel = selector;
			Thread selThread = new Thread("tcp-connection-handler") {
				@Override
				public void run() {
					for (;;) {
						try {
							int count = sel.select(10000);
							if (!pendingRegistrations.isEmpty()) {
								java.util.Iterator<java.util.Map.Entry<TCPConnectionHandler, java.net.InetSocketAddress[]>> it = pendingRegistrations
										.entrySet().iterator();
								while (it.hasNext()) {
									java.util.Map.Entry<TCPConnectionHandler, java.net.InetSocketAddress[]> e = it
											.next();
									it.remove();
									TCPConnectionHandler tc = e.getKey();
									tc.channel.register(sel, java.nio.channels.SelectionKey.OP_CONNECT, tc);
									java.net.InetSocketAddress[] addrs = e.getValue();
									java.net.InetSocketAddress isa;
									if (addrs.length > 1) {
										isa = addrs[roundRobinCounter % addrs.length];
									} else {
										isa = addrs[0];
									}
									tc.remote = isa;
									boolean conn = tc.channel.connect(isa);
									log.fine(format("Registered: ", tc, " (", e.getValue(), "/", conn, ")").toString());
								}
							}
							if (count > 0) {
								log.finest(count + " selected keys");
							}
							java.util.Iterator<java.nio.channels.SelectionKey> it = sel.selectedKeys().iterator();
							while (it.hasNext()) {
								java.nio.channels.SelectionKey sk = it.next();
								Object att = sk.attachment();
								if (!(att instanceof TCPConnectionHandler)) {
									sk.cancel();
									sk.channel().close();
								} else {
									((TCPConnectionHandler) att).selected(sk);
								}
							}
						} catch (Exception ex) {
							log.warning(format("exception in selector ", ex).toString());
						}
					}
				}
			};
			selThread.setDaemon(true);
			selThread.start();
		} catch (Exception ex) {
			log.warning(format("Couldn't create selector ", ex).toString());
		}
	}

	void close() {
		if (!closed) {
			log.fine("DATA --- close " + this.channel);
			this.closed = true;
		}
		if (channel != null) {
			try {
				channel.close();
			} catch (java.io.IOException ignore) {
				log.fine(format("close failed ", ignore).toString());
			}
		}
	}

	static {
		prepareSelector();
	}
}
