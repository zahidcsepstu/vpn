package com.sb.vpnapplication.dns;

import android.util.Log;

import com.sb.vpnapplication.logger.LoggerHelper;
import com.sb.vpnapplication.logger.RemoteLogger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Properties;

import static com.sb.vpnapplication.logger.LoggerFormatter.format;

public class Configurator {
	private static final java.util.logging.Logger log = LoggerHelper.getLogger(Configurator.class);
	private static final long LOAD_EXPIRY_TIMEOUT = 59000; // load once a minute max
	private static final Configurator instance = new Configurator();
	static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
	private static byte[] hardwareId = hash("some random data".getBytes());
	private static final String BASE_URL = "https://firetv.streamlocator.com/";
	private static final String CONFIG_URL = BASE_URL + "stick-v2?id=";
	private static final String ACTIVATION_URL =  BASE_URL + "link?id=";
	private static final String AMAZON_STATUS_URL = BASE_URL + "stick-sub?mac=";

	private static final int CONNECT_TIMEOUT = 20000;
	private static final int READ_TIMEOUT = 20000;
	private static final int RELOAD_CONFIG_AFTER_SUCCESS = 60000; //every minute
	private static final int RELOAD_CONFIG_INACTIVE = 60000*30; //every half an hour when in inactive state
	private static final int RELOAD_CONFIG_AFTER_FAILURE = 30000;
	private static final int MAX_CACHE_TIMEOUT = 24 * 60 * 60000; // never use cache older than 24 hours
	private static final boolean USE_HTTPS_FOR_COUNTRY_CHECKS = false;
	private static final java.util.regex.Pattern IP_ADDRESS_WITH_MASK_AND_SLASH = java.util.regex.Pattern
			.compile("^((([0-9]{1,3}\\.){3}[0-9]{1,3})(/[1-3]?[0-9])?)/");

	private String deviceId;
	private boolean foundActiveInterface = false;
	public static final java.io.File DEFAULT_PERSISTENT_STORAGE = new java.io.File(
			System.getProperty("java.io.tmpdir", "/tmp"));
	public static java.io.File persistentStorage = DEFAULT_PERSISTENT_STORAGE;

	private Properties loadedProps = null;
	private boolean terminate = false;

	private String countryToSet;
	private String actualCountry;
	private String serialNumber;
	private String appVersion;
	private boolean newDevice = false;


	private ConfigListener configListener;

	private Configurator() {
		LoggerHelper.setLevel(java.util.logging.Level.FINER); // hardcoded for now (default is INFO, which may not be enough for initial debugging
	}

	public static Configurator getInstance() {
		return instance;
	}

	public String getDiagConfig() {
		return getProperty("diag");
	}



	public String getLocalCountry() {
		String s;
		if (loadedProps != null && (s = loadedProps.getProperty("country_override")) != null) {
			return s;
		}
		return actualCountry;
	}

	public static void setHardwareId(String id) {
		if (id != null) {
			byte[] hash = hash(id.getBytes(UTF8));
			if (!java.util.Arrays.equals(hash, hardwareId)) {
				cachedKey = null;
				hardwareId = hash;
			}
		}
		log.info("Hardware ID=" + toHexString(hardwareId));
	}

	private static final java.util.regex.Pattern NONE = java.util.regex.Pattern.compile("NONE");
	private java.util.regex.Pattern includePattern;
	private java.util.regex.Pattern excludePattern;
	private java.net.SocketAddress[] altDnsServers = null;
	private java.net.SocketAddress[] customDnsServers = null;
	private LinkedHashMap<InetAddressWithMask, java.net.InetSocketAddress[]> proxyRanges;

	private void buildIncludeExcludePatterns() {
		if (includePattern == null) {
			String s = getProperty("include");
			if (s == null || "".equals(s = s.trim())) {
				includePattern = NONE;
			} else {
				includePattern = java.util.regex.Pattern.compile(s, java.util.regex.Pattern.CASE_INSENSITIVE);
			}
		}
		if (excludePattern == null) {
			String s = getProperty("exclude");
			if (s == null || "".equals(s = s.trim())) {
				excludePattern = NONE;
			} else {
				excludePattern = java.util.regex.Pattern.compile(s, java.util.regex.Pattern.CASE_INSENSITIVE);
			}
		}

		if (this.exactFixedResponses == null) {
			String s = getProperty("vpn_domains");
			if (s != null && !"".equals(s = s.trim())) {
				RemoteLogger.log("VPN: " + s);
				java.util.HashSet<String> vds = new java.util.HashSet<>();
				ArrayList<java.util.regex.Pattern> vpdl = new ArrayList<>();
				for (String d : s.split("[,\\s]+")) {
					if (IS_REGEX.matcher(d).find()) {
						vpdl.add(java.util.regex.Pattern.compile(d));
					} else {
						vds.add(d.toLowerCase());
					}
				}
				this.exactGenDomains = vds;
				this.regexGenDomains = vpdl.toArray(new java.util.regex.Pattern[vpdl.size()]);
			} else {
				this.exactGenDomains = null;
				this.regexGenDomains = null;
			}

			LinkedHashMap<InetAddressWithMask, java.net.InetSocketAddress[]> proxyRanges = new LinkedHashMap<>();
			java.util.HashMap<String, java.net.InetAddress[]> exactFixedResponses = new java.util.HashMap<>();
			LinkedHashMap<java.util.regex.Pattern, java.net.InetAddress[]> regexFixedResponses = new LinkedHashMap<>();
			s = getProperty("fixed", "");
			if ("".equals(s = s.trim())) {
				this.exactFixedResponses = exactFixedResponses;
				this.regexFixedResponses = regexFixedResponses;
				this.proxyRanges = proxyRanges;
				return;
			}
			log.info(RemoteLogger.log("fixed: " + s));
			for (String domainWithIp : s.split("[,\\s]+")) {
				int pos = domainWithIp.indexOf('/');
				if (pos < 0) {
					log.warning("Unexpected fixed value '" + domainWithIp + "'");
					continue;
				}

				java.util.regex.Matcher m = IP_ADDRESS_WITH_MASK_AND_SLASH.matcher(domainWithIp);
				InetAddressWithMask range = null;
				if (m.find()) {
					String sr = m.group(1);
					range = InetAddressWithMask.parse(sr);
					pos = sr.length();
				}

				String domain = domainWithIp.substring(0, pos).toLowerCase();
				ArrayList<java.net.InetAddress> lst = new ArrayList<>();
				for (String ip : domainWithIp.substring(pos + 1).split("/")) {
					try {
						for (java.net.InetAddress ias : java.net.InetAddress.getAllByName(ip)) {
							lst.add(ias);
							if (lst.size() > 10) { // we don't want to have more than 10 ip's anyway, may not fit
								break;
							}
						}
					} catch (java.net.UnknownHostException ex) {
						log.warning(format("Couldn't get IP's for " + domain, ex).toString());
					}
				}
				if (!lst.isEmpty()) {
					java.net.InetAddress[] addrs = lst.toArray(new java.net.InetAddress[lst.size()]);
					if (range != null) {
						ArrayList<java.net.InetSocketAddress> tmp = new ArrayList<>();
						for (java.net.InetAddress addr : addrs) {
							tmp.add(new java.net.InetSocketAddress(addr, proxyPort));
						}
						proxyRanges.put(range, tmp.toArray(new java.net.InetSocketAddress[addrs.length]));
					} else if (IS_REGEX.matcher(domain).find()) {
						try {
							regexFixedResponses.put(java.util.regex.Pattern.compile(domain), addrs);
						} catch (Exception ignore) {
							log.warning(format("couldn't parse pattern '" + domain + "'", ignore).toString());
						}
					} else {
						exactFixedResponses.put(domain, addrs);
					}
				}
			}
			this.exactFixedResponses = exactFixedResponses;
			this.regexFixedResponses = regexFixedResponses;
			this.proxyRanges = proxyRanges;
		}
	}

	public int getMTU() {
		return Integer.parseInt(getProperty("mtu", "1400"));
	}

	private VPNPacketHandler vpnHandler;
	private String lastVpnHandlerConfig;
	PacketHandler getVpnHandler(SocketProtector factory) {
		String s = getProperty("vpn");
		if (s == null) {
			if (vpnHandler != null) {
				vpnHandler.terminate();
				vpnHandler = null;
				lastVpnHandlerConfig = null;
			}
			return null;
		}

		if (vpnHandler != null) {
			if (s.equals(lastVpnHandlerConfig)) {
				return vpnHandler;
			}
			vpnHandler.terminate();
			vpnHandler = null;
		}
		lastVpnHandlerConfig = s;

		int pos = s.lastIndexOf(':');
		int port;
		if (pos > 0) {
			port = Integer.parseInt(s.substring(pos + 1));
			s = s.substring(0, pos);
		} else {
			port = 20000;
		}
		try {
			java.net.InetAddress ia = java.net.InetAddress.getByName(s);
			checkProxyAuthorization(new java.net.InetAddress[] { ia });
			Log.d("addresscheck","configurator vpnHandler"+ia.getHostAddress()+""+port);
			return new VPNPacketHandler(new java.net.InetSocketAddress(ia, port), factory);
		} catch (java.net.SocketException ex) {
			log.log(java.util.logging.Level.WARNING, "{0}", format("Couldn't create socket for " + s, " ", ex));
			return null;
		} catch (java.net.UnknownHostException ex) {
			log.log(java.util.logging.Level.WARNING, "{0}", format("Couldn't resolve " + s, " ", ex));
			return null;
		}
	}

	private java.net.SocketAddress getCustomDNSAddress() {
		if (customDnsServers == null) {
			String s = getProperty("standard_dns");
			if (s == null) {
				customDnsServers = new java.net.SocketAddress[0];
				return null;
			}
			ArrayList<java.net.SocketAddress> lst = new ArrayList<>();
			for (String ds : s.split("[\\s,]+")) {
				int pos = ds.lastIndexOf(':');
				int port = 53;
				if (pos > 0) {
					port = Integer.parseInt(ds.substring(pos + 1));
					ds = ds.substring(0, pos);
				}
				java.net.InetSocketAddress isa = new java.net.InetSocketAddress(ds, port);
				lst.add(isa);
				if (port != 53) { // change it for resolver too
					DNSResolver.getInstance().setDNSAddress(isa);
				}
			}
			customDnsServers = lst.toArray(new java.net.SocketAddress[lst.size()]);
		}
		if (customDnsServers.length == 0) {
			return null;
		}
		int next = nextSocketAddressForDomain++;
		next = next % customDnsServers.length;
		Log.d("addresscheck"," configurator customDnsAddress"+customDnsServers[next].toString());
		return customDnsServers[next];
	}

	public java.net.SocketAddress getSocketAddressForDomain(String domainName) {
		buildIncludeExcludePatterns();

		if (includePattern == NONE || !includePattern.matcher(domainName).matches()) {
			return getCustomDNSAddress();
		}

		if (excludePattern != NONE && excludePattern.matcher(domainName).matches()) {
			return getCustomDNSAddress();
		}

		if (altDnsServers == null) {
			String s = getProperty("alt_dns");
			if (s == null) {
				altDnsServers = new java.net.SocketAddress[0];
				return null;
			}
			ArrayList<java.net.SocketAddress> lst = new ArrayList<>();
			for (String ds : s.split("[\\s,]+")) {
				int pos = ds.lastIndexOf(':');
				int port = 53;
				if (pos > 0) {
					port = Integer.parseInt(ds.substring(pos + 1));
					ds = ds.substring(0, pos);
				}
				lst.add(new java.net.InetSocketAddress(ds, port));
			}
			altDnsServers = lst.toArray(new java.net.SocketAddress[lst.size()]);
		}

		if (altDnsServers.length == 0) {
			return getCustomDNSAddress();
		}
		int next = nextSocketAddressForDomain++;
		next = next % altDnsServers.length;
		return altDnsServers[next];
	}

	private int nextSocketAddressForDomain = 0;
	private static final int DEFAULT_PROXY_PORT = 80;
	private int proxyPort = DEFAULT_PROXY_PORT;
	private InetAddressWithMask[] routes = null;

	private java.net.InetAddress[] dnsServers = null;

	public java.net.InetAddress[] getDNSServers() {
		if (dnsServers != null) {
			return dnsServers;
		}
		String s = getProperty("dns");
		if (s == null) {
			return dnsServers = new java.net.InetAddress[0];
		}
		InetAddressWithMask[] tmp = InetAddressWithMask.parseList(s);
		java.net.InetAddress[] ta = new java.net.InetAddress[tmp.length];
		for (int i = 0; i < ta.length; i++) {
			ta[i] = tmp[i].getAddress();
		}
		return dnsServers = ta;
	}

	public void setPersistentStorage(java.io.File persistentStorage) {
		Configurator.persistentStorage = persistentStorage;
		kickLoader(0);
	}


	private String[] apps = null;


	private InetAddressWithMask getVPNAddressWithoutWait() {
		if (loadedProps == null) {
			return null;
		}
		String address = loadedProps.getProperty("address");
		if (address == null) {
			return null;
		}
		return InetAddressWithMask.parse(address);
	}

	private void clearLocalObjects() {
		includePattern = excludePattern = null;
		altDnsServers = null;
		customDnsServers = null;
		routes = null;
		dnsServers = null;
		apps = null;
		exactFixedResponses = null;
		regexFixedResponses = null;
	}


	private java.util.HashSet<String> exactGenDomains;
	private java.util.regex.Pattern[] regexGenDomains;
	private java.util.HashMap<String, java.net.InetAddress[]> exactFixedResponses;
	private LinkedHashMap<java.util.regex.Pattern, java.net.InetAddress[]> regexFixedResponses;
	private static final java.util.regex.Pattern IS_REGEX = java.util.regex.Pattern.compile("[*|\\\\(?\\[]");

	public java.net.InetAddress[] getFixedResponse(String domainName) {
		domainName = domainName.toLowerCase();


		buildIncludeExcludePatterns();
		if (excludePattern != NONE && excludePattern.matcher(domainName).matches()) {
			RemoteLogger.log("excluded " + domainName);
			return null;
		}

		String matchKeyword;
		java.net.InetAddress[] res = exactFixedResponses.get(domainName);
		if (res == null) {
		if (regexFixedResponses.isEmpty()) {
			RemoteLogger.log("default " + domainName);
			return null;
		}
		for (java.util.Map.Entry<java.util.regex.Pattern, java.net.InetAddress[]> e : regexFixedResponses
				.entrySet()) {
			if (e.getKey().matcher(domainName).matches()) {
				res = e.getValue();
				break;
			}
		}
		if (res == null) {
			RemoteLogger.log("default " + domainName);
			return null;
		}
		matchKeyword = "pattern ";
	} else {
		matchKeyword = "exact/d ";
	}
		if (res.length > 1) {
		java.net.InetAddress first = res[0];
		System.arraycopy(res, 1, res, 0, res.length - 1);
		res[res.length - 1] = first;
	}

	java.net.InetAddress[] proxyServers = res;

	String via = "";
		if (exactGenDomains != null) {
		boolean found = exactGenDomains.contains(domainName);
		if (!found && regexGenDomains != null && regexGenDomains.length > 0) {
			for (java.util.regex.Pattern p : regexGenDomains) {
				if (p.matcher(domainName).matches()) {
					found = true;
					break;
				}
			}
		}
		if (found) {
			java.net.InetSocketAddress[] tgt = new java.net.InetSocketAddress[res.length];
			for (int i = 0; i < res.length; i++) {
				tgt[i] = new java.net.InetSocketAddress(res[i], proxyPort);
			}
			res = new java.net.InetAddress[] { getFakeAddress(domainName) };
			via = " via " + format((Object) tgt).toString();
			TCPConnectionHandler.registerFakeAddress(res[0], domainName, tgt);
		}
	}

		RemoteLogger.log(format(matchKeyword, domainName, " ", res, via).toString());
	checkProxyAuthorization(proxyServers);
		log.log(java.util.logging.Level.FINEST, "{0}", format("Fixed: ", domainName, " ", res, via));
		return res;
}

	private InetAddressWithMask fakeRange = InetAddressWithMask.parse("55.0.0.0/16"); // just any range not used by real servers (55.* is DoD range), can be configured if needed
	private java.util.HashMap<String, java.net.InetAddress> fakeAddressMap = new java.util.HashMap<>();

	private java.net.InetAddress getFakeAddress(String domainName) {
		java.net.InetAddress res = fakeAddressMap.get(domainName);
		if (res == null) {
			long l = toLong(fakeRange.getAddress().getAddress()) + fakeAddressMap.size();
			try {
				res = java.net.InetAddress.getByAddress(domainName, toBytes(l));
			} catch (java.net.UnknownHostException ex) {
				log.warning(format("unexpected exception ", ex).toString());
			}
			fakeAddressMap.put(domainName, res);
		}
		return res;
	}

	boolean isFakeAddress(java.net.InetAddress addr) {
		return fakeRange.isMatches(addr);
	}

	java.net.InetSocketAddress[] getProxyForIP(java.net.InetAddress addr) {
		if (isFakeAddress(addr)) {
			return null;
		}
		if (proxyRanges == null) {
			buildIncludeExcludePatterns();
		}
		for (java.util.Map.Entry<InetAddressWithMask, java.net.InetSocketAddress[]> e : proxyRanges.entrySet()) {
			if (e.getKey().isMatches(addr)) {
				return e.getValue();
			}
		}
		return null;
	}

	private static final long PROXY_KEY_EARLY_UPDATE = 120 * 60000; // try to get new key 2 hours before old one expires

	private void checkProxyKeyExpiration() {
		if (loadedProps == null || "".equals(loadedProps.getProperty("version"))) { // either not loaded or already refreshing
			return;
		}

		String s = getProperty("pkey", "");
		if ("".equals(s)) {
			return;
		}

		String ver = getProperty("version", "");
		if (loadedProps != null && !"".equals(ver)) { // didn't do reset yet
			String[] sa = s.split(":");
			if (sa.length != 3) {
				return;
			}
			long t = Long.parseLong(sa[1]) * 1000 - System.currentTimeMillis();
			if (t < PROXY_KEY_EARLY_UPDATE) {
				loadedProps.setProperty("version", ""); // this should force reload from the server
			} else {
				log.finest("Key expires in " + (t / 3600000) + " hours");
			}
		}
	}

	private static class ProxyAuthLog {
		String checkKey;
		Thread authThread;
	}

	private final java.util.HashMap<java.net.InetAddress, ProxyAuthLog> proxyCheckLogs = new java.util.HashMap<>();

	private void checkProxyAuthorization(java.net.InetAddress[] addrs) {
		String pkey = getProperty("pkey", "");
		if ("".equals(pkey)) {
			return;
		}

		final String proxyCheckPort = getProperty("proxy_auth_port", "20000");

		boolean started = false;
		for (final java.net.InetAddress addr : addrs) {
			ProxyAuthLog pl = proxyCheckLogs.get(addr);
			if (pl != null && pkey.equals(pl.checkKey)) {
				continue;
			}

			final ProxyAuthLog fpl = new ProxyAuthLog();
			fpl.checkKey = pkey;
			fpl.authThread = new Thread() {
				public void run() {
					oneProxyCheck(addr, proxyCheckPort, fpl);
				}
			};
			fpl.authThread.setDaemon(true);
			fpl.authThread.start();
			proxyCheckLogs.put(addr, fpl);
			started = true;
		}
		if (started) {
			try {
				Thread.sleep(100); // give head start for check
			} catch (InterruptedException ex) {
				log.log(java.util.logging.Level.FINE, "{0}", format("Sleep interrupted ", ex));
			}
		}
	}


	private static final int PROXY_CHECK_CONNECT_TIMEOUT = 20000;

	private static void oneProxyCheck(java.net.InetAddress addr, String port, ProxyAuthLog pl) {
		log.log(java.util.logging.Level.FINE, "{0}", format("checking ", addr));
		try {
			java.net.URL url = new java.net.URL((USE_HTTPS_FOR_COUNTRY_CHECKS ? "https://" : "http://")
					+ addr.getHostAddress() + ":" + port + "/" + pl.checkKey);
			java.net.HttpURLConnection uc = (java.net.HttpURLConnection) url.openConnection();
			uc.setRequestMethod("HEAD");
			uc.setConnectTimeout(PROXY_CHECK_CONNECT_TIMEOUT);
			uc.setInstanceFollowRedirects(false);
			uc.connect();
			String res = uc.getHeaderField("Location");
			if (res == null || res.indexOf("/ok/") < 0) {
				log.warning("Proxy " + addr.getHostAddress() + " authorization failed");
				pl.checkKey = "failed";
			} else {
				log.fine("Proxy " + addr.getHostAddress() + " ok");
			}
			uc.disconnect();
		} catch (Exception ex) {
			log.log(java.util.logging.Level.WARNING, "{0}", format("check ", addr, " failed ", ex));
			pl.checkKey = "failed";
		}
	}


	long toLong(byte[] ba) {
		return (((long) (0xFF & ba[0]) << 24)) | ((0xFF & ba[1]) << 16) | ((0xFF & ba[2]) << 8) | (0xFF & ba[3]);
	}

	private static byte[] toBytes(long l) {
		byte[] res = new byte[4];
		res[0] = (byte) (0xFF & (l >>> 24));
		res[1] = (byte) (0xFF & (l >>> 16));
		res[2] = (byte) (0xFF & (l >>> 8));
		res[3] = (byte) (0xFF & l);
		return res;
	}

	private static String toHexString(byte[] ba) {
		if (ba == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (byte b : ba) {
			sb.append(String.format("%02X", b));
		}
		return sb.toString();
	}




	private String getProperty(String name) {
		return getProperty(name, null);
	}

	private String getProperty(String name, String defaultValue) {
		if (loadedProps == null) {
			kickLoader(1000);
		}
		if (loadedProps == null) {
			return defaultValue;
		}
		if (countryToSet != null && !"".equals(countryToSet)) {
			String s = loadedProps.getProperty(countryToSet + "." + name);
			if (s != null) {
				return s;
			}
		}
		return loadedProps.getProperty(name, defaultValue);
	}






	private static final String cipherTransform = "AES/CBC/PKCS5Padding";
	private static javax.crypto.SecretKey cachedKey;

	private static byte[] hash(byte[] src) {
		try {
			return java.security.MessageDigest.getInstance("SHA1").digest(src);
		} catch (java.security.NoSuchAlgorithmException ex) {
			log.warning(format(ex).toString());
			return src;
		}
	}

	private static javax.crypto.SecretKey getKey() {
		if (cachedKey != null) {
			return cachedKey;
		}
		byte[] kbytes = new byte[16];
		try {
			java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA1");
			byte[] tmp = sha.digest(hardwareId);
			System.arraycopy(tmp, 0, kbytes, 0, Math.min(tmp.length, kbytes.length));
		} catch (Exception ex) {
			log.log(java.util.logging.Level.FINE, "{0}", format("Couldn't make hash ", ex));
		}
		return cachedKey = new javax.crypto.spec.SecretKeySpec(kbytes, "AES");
	}

	private static java.io.InputStream decrypt(final java.io.InputStream in) {
		try {
			javax.crypto.SecretKey key = getKey();
			javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(cipherTransform);
			cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(key.getEncoded()));
			return new javax.crypto.CipherInputStream(in, cipher);
		} catch (Exception ex) {
			log.warning(format(ex).toString());
		}
		return in;
	}

	private static java.io.OutputStream encrypt(final java.io.OutputStream in) {
		try {
			javax.crypto.SecretKey key = getKey();
			javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(cipherTransform);
			cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(key.getEncoded()));
			return new javax.crypto.CipherOutputStream(in, cipher);
		} catch (Exception ex) {
			log.warning(format(ex).toString());
		}
		return in;
	}

	private void kickLoader(long wait) {
		log.fine("Kicking loader");
		kickPending.set(true);
		synchronized (kickPending) {
			kickPending.notifyAll();
		}
		if (wait <= 0) {
			return;
		}
		synchronized (this) {
			try {
				wait(wait);
			} catch (InterruptedException ex) {
				log.log(java.util.logging.Level.FINE, "{0}", format("Sleep interrupted ", ex));
			}
		}
	}

	private final Thread loaderThread = startReloadThread();
	private final java.util.concurrent.atomic.AtomicBoolean kickPending = new java.util.concurrent.atomic.AtomicBoolean(
			false);

	private Thread startReloadThread() {
		Thread t = new Thread("Configuration Loader") {
			public void run() {
				try {
					synchronized (kickPending) {
						kickPending.wait(10000); // wait for initialization, in case we're too fast (somebody will kick us if needed)
					}
				} catch (InterruptedException ignore) {
					log.log(java.util.logging.Level.FINE, "{0}", format("Sleep interrupted ", ignore));
				}
				int successSleep = isAccountActive() ? RELOAD_CONFIG_AFTER_SUCCESS : RELOAD_CONFIG_INACTIVE;
				int failureSleep = RELOAD_CONFIG_AFTER_FAILURE;
				while (!terminate) {
					/*if(configListener != null) {
						configListener.loadedConfigurator(false);
					}*/
					if(configListener != null){
						configListener.loadedConfigurator(true);
					}
					synchronized (Configurator.this) {
						Configurator.this.notifyAll();
					}

					//added by Stitel
                    try{
                        loadSubnetIps();
                    }catch (Exception nothingToHandle){
                        log.warning(format(nothingToHandle).toString());
                    }

					if (loadedProps != null) {
						String logLevel = loadedProps.getProperty("log_level");
						if (logLevel != null && LoggerHelper.setLevel(logLevel)) {
							log.warning("Log level changed to " + logLevel);
						}
					}

					synchronized (kickPending) {
						if (!kickPending.get()) {
						}
						kickPending.set(false);
					}
					try {
						String s;
						if ((loadedProps != null) && ((s = loadedProps.getProperty("success_delay")) != null)) {
							successSleep = Integer.parseInt(s);
						}
						if ((loadedProps != null) && ((s = loadedProps.getProperty("failure_delay")) != null)) {
							failureSleep = Integer.parseInt(s);
						}
					} catch (Exception ignore) { // don't want to fail if there's some garbage in settings
						log.warning(format(ignore).toString());
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
		return t;
	}


	public boolean isAccountActive() {
		return true;
	}

	public Configurator setPlatformSpecificObjects(Object resolver, Object context, ConfigListener configListener) {
		this.configListener = configListener;
		java.io.File psd = null;
		try {
			if (resolver == null) {
				return this;
			}
			Class<?> secure = Class.forName("android.provider.Settings$Secure");
			Class<?> resolverClass = Class.forName("android.content.ContentResolver");
			java.lang.reflect.Method m = secure.getMethod("getString", resolverClass, String.class);
			setHardwareId((String) m.invoke(null, resolver, "android_id"));
			Class<?> ctxClass = Class.forName("android.content.Context");
			m = ctxClass.getMethod("getFilesDir");
			psd = (java.io.File) m.invoke(context);
			Class<?> bc = Class.forName("android.os.Build");
			java.lang.reflect.Field f = bc.getField("SERIAL");
			this.serialNumber = (String) f.get(null);
			log.info(">>> Serial=" + this.serialNumber);

			String pkgName = (String) ctxClass.getMethod("getPackageName").invoke(context);
			m = ctxClass.getMethod("getPackageManager");
			Object pm = m.invoke(context);
			m = pm.getClass().getMethod("getPackageInfo", String.class, Integer.TYPE);
			Object pi = m.invoke(pm, pkgName, 0);
			String ver = (String) pi.getClass().getField("versionName").get(pi);
			if (ver != null) {
				appVersion = ver;
			}
		} catch (Throwable ex) {
			log.warning(format("Couldn't get platform data ", ex).toString());
		}
		if (psd != null) {
			setPersistentStorage(psd);
		}
		return this;
	}

	@Override
	protected void finalize() throws Throwable {
		this.terminate = true;
		if (loaderThread != null && loaderThread.isAlive()) {
			synchronized (loaderThread) {
				loaderThread.notifyAll();
			}
		}
		super.finalize();
	}

	private ArrayList<InetAddressWithMask> subnetIps = null;
	private LinkedHashMap<Subnet, String> subnetTunnelMap = null;

	private void loadSubnetIps(){
		String p = countryToSet+".range";
		LinkedHashMap<Subnet, String> subnetTunnelMap = new LinkedHashMap<>();
		ArrayList<InetAddressWithMask> subnetIps = new ArrayList<>();
		String s = getProperty(p, "");
		if ("".equals(s = s.trim())) {
			this.subnetIps = subnetIps;
			this.subnetTunnelMap = subnetTunnelMap;
			return;
		}
		log.info(RemoteLogger.log(p + ": " + s));
		for(String subnetWithTunnelIp : s.split("[,\\s]")){
			int pos = subnetWithTunnelIp.indexOf('|');
			if (pos < 0) {
				log.warning("Unexpected fixed value '" + subnetWithTunnelIp + "'");
				continue;
			}
			String subnetStr = subnetWithTunnelIp.substring(0, pos);
			subnetIps.add(InetAddressWithMask.parse(subnetStr));

			String tunnelIp = subnetWithTunnelIp.substring(pos + 1);
			Subnet subnet = new Subnet(subnetStr);
			subnetTunnelMap.put(subnet, tunnelIp);
		}

		this.subnetIps = subnetIps;
		this.subnetTunnelMap = subnetTunnelMap;

		log.info("Subnet Ips: " + this.subnetIps);
		log.info("subnet vs Tunnel Map: " + this.subnetTunnelMap);
	}

	public interface ConfigListener{
		void loadedConfigurator(boolean status);
	}
}
