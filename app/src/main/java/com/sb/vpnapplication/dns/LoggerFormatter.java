package com.sb.vpnapplication.dns;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.text.DecimalFormat;

public class LoggerFormatter {
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,###.#####");
    private static final String NULL = "<null>";
    private static final String EMPTY = "";

    private static final LoggerFormatter NULL_FORMATTER = new LoggerFormatter(new Object[] { NULL });
    private static final LoggerFormatter EMPTY_FORMATTER = new LoggerFormatter(new Object[] { EMPTY });

    private final Object[] data;

    private LoggerFormatter(Object[] data) {
        this.data = data;
    }

    private static void serialize(Object o, StringWriter writer) {
        if (o == null) {
            writer.write(NULL);
            return;
        }

        Class<?> oc = o.getClass();
        if (oc == String.class) {
            writer.write(o.toString());
            return;
        }

        if (oc.isPrimitive()) {
            if ((oc == Long.TYPE) || (oc == Integer.TYPE) || (oc == Double.TYPE) || (oc == Float.TYPE)) {
                writer.write(NUMBER_FORMAT.format(o));
            } else {
                writer.write(o.toString());
            }
            return;
        } else if (Number.class.isAssignableFrom(oc)) {
            writer.write(NUMBER_FORMAT.format(o));
            return;
        }

        if (oc.isArray()) {
            writer.write('[');
            int size = Array.getLength(o);
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    writer.write(", ");
                }
                serialize(Array.get(o, i), writer);
            }
            writer.write(']');
            return;
        }

        writer.write(o.toString());
    }

    public String toString() {
        StringWriter sw = new StringWriter();
        Throwable throwable = null;
        for (Object o : data) {
            if (o instanceof Throwable) {
                throwable = (Throwable) o;
                sw.write(o.toString());
                continue;
            }
            serialize(o, sw);
        }

        if (throwable != null) {
            PrintWriter pw = new PrintWriter(sw);
            pw.println();
            throwable.printStackTrace(pw);
            pw.close();
        }
        return sw.toString();
    }

    public static LoggerFormatter format(final Object... data) {
        if (data == null || (data.length == 1 && data[0] == null)) {
            return NULL_FORMATTER;
        }
        if (data.length == 0) {
            return EMPTY_FORMATTER;
        }
        return new LoggerFormatter(data);
    }

    public static LoggerFormatter format(byte[] data, int offset, int length) {
        return new ByteArrayWrapper(data, offset, length);
    }

    public static LoggerFormatter format(byte[] data, int length) {
        if (data == null) {
            return NULL_FORMATTER;
        }
        return format(data, 0, length);
    }

    public static LoggerFormatter format(byte[] data) {
        if (data == null) {
            return NULL_FORMATTER;
        }
        return format(data, 0, data.length);
    }

    private static class ByteArrayWrapper extends LoggerFormatter {
        private final byte[] data;
        private final int offset;
        private final int length;

        ByteArrayWrapper(byte[] data, int offset, int length) {
            super(null);
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        private static final char[] hex = "0123456789ABCDEF".toCharArray();

        public String toString() {
            int offset = this.offset;
            StringBuilder sb = new StringBuilder();
            sb.append("Data(" + length + " bytes)\n");
            int line_len = 32;
            int line_split = line_len / 2;
            int end = offset + length;
            int ll = 0;
            StringBuilder tmp = new StringBuilder();
            while (offset < end) {
                if (ll == line_split) {
                    sb.append("   ");
                    tmp.append(" ");
                }
                sb.append(hex[0xF & (data[offset] >> 4)]);
                sb.append(hex[0xF & data[offset]]);
                sb.append(' ');
                char c = (char) data[offset];
                if (c < ' ' || c >= 127) {
                    tmp.append('.');
                } else {
                    tmp.append(c);
                }

                offset++;
                ll++;
                if (ll == line_len) {
                    sb.append('|').append(tmp.toString()).append("| ").append(offset);
                    if (offset < end) {
                        sb.append("\n");
                        tmp.delete(0, Integer.MAX_VALUE);
                        ll = 0;
                    }
                }
            }
            if (ll < line_len) {
                if (ll <= line_split) {
                    sb.append("   ");
                    tmp.append(" ");
                }
                while (ll < line_len) {
                    sb.append("   ");
                    tmp.append(" ");
                    ll++;
                }
                sb.append('|').append(tmp.toString()).append("|");
            }
            return sb.toString();
        }
    }
}
