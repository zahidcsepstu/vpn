package com.sb.vpnapplication.logger;

import java.nio.charset.Charset;

public class RemoteLogger {
	private static final java.util.logging.Logger log = LoggerHelper.getLogger(RemoteLogger.class);
	private static final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss,SSS ");
	private static RemoteLogger instance;
	private final java.util.ArrayList<String> messages = new java.util.ArrayList<>();

	private RemoteLogger() {
	}

	private void add(String message) {
		messages.add(sdf.format(new java.util.Date()) + message);
	}

	public static String log(String message) {
		if (instance != null) {
			instance.add(message);
		}
		return message;
	}

	public static RemoteLogger getLastLog() {
		RemoteLogger res = instance;
		if (res != null) {
			if (res.messages.isEmpty()) {
				return null;
			}
			instance = new RemoteLogger();
		}
		return res;
	}

	public static Object log(Object message) {
		if (instance != null && message != null) {
			instance.add(LoggerFormatter.format(message).toString());
		}
		return message;
	}

	void pushLog(java.io.OutputStream out) {
		if (messages.isEmpty()) {
			return;
		}
		try {
			java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.OutputStreamWriter(out, Charset.defaultCharset()), false);
			for (String s : messages) {
				pw.println(s);
			}
			messages.clear(); // to help GC
			pw.close();
		} catch (Exception ex) {
			log.warning(LoggerFormatter.format("Write failed ", ex).toString());
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (java.io.IOException ignore) {
				}
			}
		}
	}

	public static void configure(String trueOrFalse) {
		if ("true".equalsIgnoreCase(trueOrFalse)) {
			if (instance == null) {
				instance = new RemoteLogger();
				instance.add("enabled");
			}
		} else {
			instance = null;
		}
	}
}
