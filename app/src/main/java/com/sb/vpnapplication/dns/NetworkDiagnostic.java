package com.sb.vpnapplication.dns;

import com.sb.vpnapplication.logger.LoggerHelper;

import static com.sb.vpnapplication.logger.LoggerFormatter.format;

public class NetworkDiagnostic implements Runnable {
	private static final java.util.logging.Logger log = LoggerHelper.getLogger(NetworkDiagnostic.class);
	private static long diagnosticInterval = 12 * 60 * 60000; // run every 12 hours
	private static Thread runningThread;
	private static final java.util.regex.Pattern cookieExtractPattern = java.util.regex.Pattern
			.compile(".*country(_code)?=([A-Za-z]{2,3})[^a-zA-Z]?.*");
	private static final java.util.regex.Pattern headerExtractIpPattern = java.util.regex.Pattern
			.compile("(?i)^(x-session-info):.*addr=(([0-9]{1,3}\\.){3}[0-9]{1,3})([^0-9].*)?$");
	private static final java.util.regex.Pattern jsonCountryExtractor = java.util.regex.Pattern
			.compile("(?ms).*(['\"]country['\"]\\s*:\\s*['\"]|<geolocation>\\s*)([a-zA-Z]{2,3})[<'\"].*");
	private static final java.util.regex.Pattern bodyIpExtractor = java.util.regex.Pattern.compile(
			"(?msi).*(ip\\(|['\"](lookup_address|ip)['\"]\\s*:\\s*)['\"](([0-9]{1,3}\\.){3}[0-9]{1,3})['\"].*");
	private static final java.util.HashMap<String, String> iso3to2map = new java.util.HashMap<>();
	private static final String AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/600.1.25 (KHTML, like Gecko) Version/8.0 Safari/600.1.25";
	//private static EventTracker eventTracker;

	public static void start() {
		if (runningThread != null && runningThread.isAlive()) {
			return;
		}
		runningThread = new Thread(new NetworkDiagnostic(), "network-diag");
		runningThread.setDaemon(true);
		runningThread.start();
	}

//	public static void setEventTracker(EventTracker tracker) {
//		eventTracker = tracker;
//	}

	public static void stop() {
		if (runningThread != null && runningThread.isAlive()) {
			runningThread.interrupt();
		}
	}

	public void runOnce() throws InterruptedException {
		String nxCountry = Configurator.getInstance().getLocalCountry();
		if (nxCountry == null) {
			Configurator.getInstance().findLocalInetAddress();
			synchronized (this) {
				wait(1000);
			}
			nxCountry = Configurator.getInstance().getLocalCountry();
		}
		String config = Configurator.getInstance().getDiagConfig();
		for (int i = 0; i < 20 && config == null; i++) {
			log.fine("Waiting for diag config");
			synchronized (this) {
				wait(1000);
			}
			config = Configurator.getInstance().getDiagConfig();
		}
		log.info(config);
		if (config == null) {
			return;
		}
		log.fine("NXC:" + nxCountry);
		String errs = null;
		if (nxCountry != null) {
			errs = nxCountry;
		} else {
			errs = "";
		}
		boolean mismatch = false;
		java.util.HashSet<String> ips = new java.util.HashSet<>();
		for (String ns : config.split("[,\\s]+")) {
			int pos = ns.indexOf(':');
			if (pos < 0 || pos > 2) {
				log.warning("Ignored: " + ns);
				continue;
			}
			int n = Integer.parseInt(ns.substring(0, pos));
			ns = ns.substring(pos + 1);
			String cc = null;
			switch (n) {
			case 1:
				cc = findCountryCodeFromCookie(ns);
				break;
			case 2:
				cc = findCountryCodeFromContent(ns, ips);
				break;
			case 3:
				cc = findCountryCodeFromContent(ns, ips);
				break;
			case 4:
				cc = findIPAddressFromHeader(ns);
				if (cc != null) {
					ips.add(cc);
				} else {
					ips.add("unknown");
				}
				continue;
			case 5:
				cc = findIPAddressFromContent(ns);
				if (cc != null) {
					ips.add(cc);
				} else {
					ips.add("unknown");
				}
				continue;
			default:
				log.warning("Ignred: " + ns);
				continue;
			}
			log.fine(cc);
			if (cc == null) {
				log.warning("Ignred: " + ns + " (connection failed?)");
				continue;
			}
			errs += "/" + cc;
			if (nxCountry == null) {
				nxCountry = cc;
				continue;
			}
			if (!nxCountry.equals(cc)) {
				mismatch = true;
			}
		}
//		if (eventTracker != null) {
//			if (mismatch) {
//				eventTracker.trackError("country_mismatch", errs);
//			}
//			if (ips.size() > 1) {
//				eventTracker.trackError("ip_mismatch", java.util.Arrays.toString(ips.toArray()));
//			}
//		}
		log.info("Diagnostic completed");
	}

	private static String convertCountryCodeIfNeeded(String s) {
		if (s == null || "".equals(s = s.trim())) {
			return s;
		}
		if (s.length() == 2) {
			return s.toUpperCase();
		}
		String cc = iso3to2map.get(s.toLowerCase());
		if (cc != null) {
			return cc;
		}
		return s;
	}

	public static String findIPAddressFromHeader(String url) {
		java.net.HttpURLConnection uc = null;
		try {
			uc = openConnection(url);
			uc.setRequestMethod("HEAD");
			uc.connect();
			for (java.util.Map.Entry<String, java.util.List<String>> e : uc.getHeaderFields().entrySet()) {
				java.util.List<String> v = e.getValue();
				for (String s : v) {
					String h = e.getKey() + ": " + s;
					java.util.regex.Matcher m = headerExtractIpPattern.matcher(h);
					if (m.matches()) {
						return m.group(2);
					}
				}
			}
		} catch (Exception ex) {
			log.warning(format("Couldn't get alt country code ", ex).toString());
		} finally {
			if (uc != null) {
				uc.disconnect();
			}
		}
		return null;
	}

	public static String findIPAddressFromContent(String url) {
		java.net.HttpURLConnection uc = null;
		try {
			uc = openConnection(url);
			uc.connect();
			java.io.InputStream is = uc.getInputStream();
			byte[] ba = new byte[8192];
			int off = 0;
			int i;
			while ((i = is.read(ba, off, ba.length - off)) > 0) {
				off += i;
			}
			is.close();
			String s = new String(ba, 0, off, java.nio.charset.Charset.forName("UTF8"));
			java.util.regex.Matcher m;
			m = bodyIpExtractor.matcher(s);
			if (m.matches()) {
				return m.group(3);
			}
		} catch (Exception ex) {
			log.warning(format("Couldn't get alt country code ", ex).toString());
		} finally {
			if (uc != null) {
				uc.disconnect();
			}
		}
		return null;
	}

	private String findCountryCodeFromCookie(String url) {
		java.net.HttpURLConnection uc = null;
		try {
			uc = openConnection(url);
			uc.setRequestMethod("HEAD");
			uc.connect();
			for (java.util.Map.Entry<String, java.util.List<String>> e : uc.getHeaderFields().entrySet()) {
				if (!"set-cookie".equalsIgnoreCase(e.getKey())) {
					continue;
				}
				java.util.List<String> v = e.getValue();
				for (String s : v) {
					java.util.regex.Matcher m = cookieExtractPattern.matcher(s);
					if (m.matches()) {
						return convertCountryCodeIfNeeded(m.group(2));
					}
				}
				log.finer(format(v).toString());
			}
		} catch (Exception ex) {
			log.warning(format("Couldn't get alt country code ", ex).toString());
		} finally {
			if (uc != null) {
				uc.disconnect();
			}
		}
		return null;
	}

	private static java.net.HttpURLConnection openConnection(String url) throws Exception {
		java.net.HttpURLConnection uc;
		java.net.URL u = new java.net.URL(url);
		String proto = u.getProtocol();
		if ("http".equalsIgnoreCase(proto)) {
			String host = u.getHost();
			u = new java.net.URL(proto, DNSResolver.getInstance().getHostByName(host).getHostAddress(), u.getFile());
			uc = (java.net.HttpURLConnection) u.openConnection();
			uc.setRequestProperty("Host", host);
		} else if ("https".equalsIgnoreCase(proto)) {
			final String host = u.getHost();
			final java.net.InetAddress a = DNSResolver.getInstance().getHostByName(host);
			u = new java.net.URL(proto, a.getHostAddress(), u.getFile());
			javax.net.ssl.HttpsURLConnection huc = (javax.net.ssl.HttpsURLConnection) u.openConnection();
			huc.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
				@Override
				public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
					return hostname.equalsIgnoreCase(host) || hostname.equals(a.getHostAddress());
				}
			});
			huc.setRequestProperty("Host", host);
			huc.setInstanceFollowRedirects(false);
			//huc.setSSLSocketFactory(getCustomFactory(a, host));
			uc = huc;
		} else {
			uc = (java.net.HttpURLConnection) u.openConnection();
		}
		uc.setRequestProperty("User-Agent", AGENT);
		uc.setConnectTimeout(10000);
		uc.setReadTimeout(10000);
		uc.setUseCaches(false);
		uc.setInstanceFollowRedirects(false);
		return uc;
	}

	public static String findCountryCodeFromContent(String url, java.util.Set<String> ips) {
		java.net.HttpURLConnection uc = null;
		try {
			uc = openConnection(url);
			uc.connect();
			java.io.InputStream is = uc.getInputStream();
			byte[] ba = new byte[8192];
			int off = 0;
			int i;
			while ((i = is.read(ba, off, ba.length - off)) > 0) {
				off += i;
			}
			is.close();
			String s = new String(ba, 0, off, java.nio.charset.Charset.forName("UTF8"));
			java.util.regex.Matcher m;
			if (ips != null) {
				m = bodyIpExtractor.matcher(s);
				if (m.matches()) {
					String ip = m.group(3);
					ips.add(ip);
					log.info(ip);
				}
			}
			m = jsonCountryExtractor.matcher(s);
			if (m.matches()) {
				return convertCountryCodeIfNeeded(m.group(2));
			}
			log.info(s);
		} catch (Exception ex) {
			log.warning(format("Couldn't get alt country code ", ex).toString());
		} finally {
			if (uc != null) {
				uc.disconnect();
			}
		}
		return null;
	}

	public void run() {
		try {
			for (;;) {
				runOnce();
				synchronized (this) {
					wait(diagnosticInterval);
				}
			}
		} catch (InterruptedException ignore) {
		}
	}

	private static final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

	private static SSLSocketFactoryWrapper getCustomFactory(java.net.InetAddress ip, String host) throws Exception {
		javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
		ctx.init(null, new CustomTrustManager[] { new CustomTrustManager() }, secureRandom);
		return new SSLSocketFactoryWrapper(ip, host, ctx.getSocketFactory());
	}

	private static class CustomTrustManager implements javax.net.ssl.X509TrustManager {
		CustomTrustManager() {
		}

		@Override
		public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
				throws java.security.cert.CertificateException {
			throw new java.security.cert.CertificateException("we never trust anyone");
		}

		@Override
		public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
				throws java.security.cert.CertificateException {
		}

		@Override
		public java.security.cert.X509Certificate[] getAcceptedIssuers() {
			return null;
		}
	}

	private static class SSLSocketFactoryWrapper extends javax.net.ssl.SSLSocketFactory {
		private final java.net.InetAddress hardTarget;
		private final String hardHostName;
		private final javax.net.ssl.SSLSocketFactory original;

		SSLSocketFactoryWrapper(java.net.InetAddress hardTarget, String hardHostName,
				javax.net.ssl.SSLSocketFactory original) {
			this.hardTarget = hardTarget;
			this.original = original;
			this.hardHostName = hardHostName;
		}

		@Override
		public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
				throws java.io.IOException {
			return original.createSocket(s, hardHostName, port, autoClose);
		}

		@Override
		public String[] getDefaultCipherSuites() {
			return original.getDefaultCipherSuites();
		}

		@Override
		public String[] getSupportedCipherSuites() {
			return original.getSupportedCipherSuites();
		}

		@Override
		public java.net.Socket createSocket(String host, int port)
				throws java.io.IOException, java.net.UnknownHostException {
			return original.createSocket(hardTarget, port);
		}

		@Override
		public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
			return original.createSocket(hardTarget, port);
		}

		@Override
		public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
				throws java.io.IOException, java.net.UnknownHostException {
			return original.createSocket(hardTarget, port, localHost, localPort);
		}

		@Override
		public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress localHost,
				int localPort) throws java.io.IOException {
			return original.createSocket(hardTarget, port, localHost, localPort);
		}
	}

	static {
		System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
		for (String c : java.util.Locale.getISOCountries()) {
			java.util.Locale locale = new java.util.Locale("", c);
			iso3to2map.put(locale.getISO3Country().toLowerCase(), c.toUpperCase());
		}
	}
}
