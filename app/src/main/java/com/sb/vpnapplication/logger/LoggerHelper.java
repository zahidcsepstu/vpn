package com.sb.vpnapplication.logger;

public class LoggerHelper {
    private static final java.util.HashMap<java.util.logging.Level, java.lang.reflect.Method> methods = getAndroidLog();
    private static java.util.logging.Level globalLogLevelObject = java.util.logging.Level.INFO;
    private static int globalLogLevel = java.util.logging.Level.INFO.intValue();
    private static java.util.HashMap<String, java.util.logging.Level> levels = loadLevels();

    public static java.util.logging.Logger getLogger(String name) {
        if (methods == null) {
            return java.util.logging.Logger.getLogger(name);
        }
        AndroidLogger res = new AndroidLogger(name);
        res.setLevel(globalLogLevelObject);
        return res;
    }

    public static java.util.logging.Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    public static void setLevel(java.util.logging.Level level) {
        globalLogLevelObject = level;
        globalLogLevel = level.intValue();
        java.util.logging.LogManager lm = java.util.logging.LogManager.getLogManager();
        java.util.Enumeration<String> en = lm.getLoggerNames();
        while (en.hasMoreElements()) {
            java.util.logging.Logger l = java.util.logging.Logger.getLogger(en.nextElement());
            l.setLevel(level);
        }
        java.util.logging.Logger l = java.util.logging.Logger.getAnonymousLogger();
        if (l != null) {
            l.setLevel(level);
        }
        l = java.util.logging.Logger.getGlobal();
        if (l != null) {
            l.setLevel(level);
        }
    }

    // set level by name (possibly from remote)
    public static boolean setLevel(String value) {
        java.util.logging.Level level;
        if (value != null && (level = levels.get(value.toUpperCase().trim())) != null
                && level != globalLogLevelObject) {
            setLevel(level);
            return true;
        }
        return false;
    }

    private static java.util.HashMap<java.util.logging.Level, java.lang.reflect.Method> getAndroidLog() {
        java.util.HashMap<java.util.logging.Level, java.lang.reflect.Method> logMethods = null;
        try {
            Class<?> cls = Class.forName("android.util.Log");
            logMethods = new java.util.HashMap<>();
            logMethods.put(java.util.logging.Level.ALL, cls.getMethod("v", String.class, String.class));
            logMethods.put(java.util.logging.Level.FINEST, cls.getMethod("v", String.class, String.class));
            logMethods.put(java.util.logging.Level.FINER, cls.getMethod("d", String.class, String.class));
            logMethods.put(java.util.logging.Level.FINE, cls.getMethod("d", String.class, String.class));
            logMethods.put(java.util.logging.Level.INFO, cls.getMethod("i", String.class, String.class));
            logMethods.put(java.util.logging.Level.CONFIG, cls.getMethod("i", String.class, String.class));
            logMethods.put(java.util.logging.Level.WARNING, cls.getMethod("w", String.class, String.class));
            logMethods.put(java.util.logging.Level.SEVERE, cls.getMethod("e", String.class, String.class));
        } catch (Exception ignore) {
        }
        return logMethods;
    }

    private static class AndroidLogger extends java.util.logging.Logger {
        private final String tag;

        AndroidLogger(String name) {
            super(name, null);
            tag = name.substring(name.lastIndexOf('.') + 1);
        }

        @Override
        public boolean isLoggable(java.util.logging.Level l) {
            return l.intValue() >= globalLogLevel;
        }

        @Override
        public void log(java.util.logging.Level level, String msg) {
            if (!isLoggable(level)) {
                return;
            }
            java.lang.reflect.Method m = methods.get(level);
            if (m == null) {
                return;
            }
            try {
                m.invoke(null, tag, msg);
            } catch (Exception ignore) {
            }
        }

        @Override
        public void log(java.util.logging.Level level, String msg, Object param) {
            if (!isLoggable(level)) {
                return;
            }
            java.lang.reflect.Method m = methods.get(level);
            if (m == null) {
                return;
            }
            try {
                if ("{0}".equals(msg)) {
                    if (param == null) {
                        msg = "<null>";
                    } else {
                        msg = param.toString();
                    }
                } else if (param != null) {
                    msg = java.text.MessageFormat.format(msg, param);
                }
                m.invoke(null, tag, msg);
            } catch (Exception ignore) {
            }
        }
    }

    private static java.util.HashMap<String, java.util.logging.Level> loadLevels() {
        java.util.HashMap<String, java.util.logging.Level> res = new java.util.HashMap<>();
        for (java.lang.reflect.Field f : java.util.logging.Level.class.getFields()) {
            try {
                java.util.logging.Level level = (java.util.logging.Level) f.get(null);
                res.put(f.getName(), level);
            } catch (Exception ignore) {
            }
        }
        return res;
    }
}
