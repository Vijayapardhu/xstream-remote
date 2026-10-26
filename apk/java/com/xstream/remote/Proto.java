package com.xstream.remote;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tiny protobuf wire-format toolkit. We only need a handful of fields from
 * remotemessage.proto, so this avoids pulling in protobuf-lite.
 */
final class Proto {
    private Proto() {
    }

    static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
        return out.toByteArray();
    }

    static byte[] tag(int field, int wire) {
        return varint(((long) field << 3) | wire);
    }

    static byte[] uint(int field, long value) {
        return cat(tag(field, 0), varint(value));
    }

    static byte[] bool(int field, boolean value) {
        return uint(field, value ? 1 : 0);
    }

    static byte[] str(int field, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        return cat(tag(field, 2), varint(raw.length), raw);
    }

    static byte[] msg(int field, byte[] body) {
        return cat(tag(field, 2), varint(body.length), body);
    }

    static byte[] cat(byte[]... chunks) {
        int size = 0;
        for (byte[] chunk : chunks) {
            size += chunk.length;
        }
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, at, chunk.length);
            at += chunk.length;
        }
        return out;
    }

    static final class Field {
        final int number;
        final int wire;
        final long value;
        final byte[] bytes;

        Field(int number, int wire, long value, byte[] bytes) {
            this.number = number;
            this.wire = wire;
            this.value = value;
            this.bytes = bytes;
        }

        String text() {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static List<Field> parse(byte[] data) {
        if (data == null || data.length == 0) {
            return Collections.emptyList();
        }
        List<Field> fields = new ArrayList<>();
        int at = 0;
        while (at < data.length) {
            long[] key = new long[1];
            at = readVarint(data, at, key);
            if (at < 0) {
                break;
            }
            int wire = (int) (key[0] & 0x7);
            int number = (int) (key[0] >>> 3);
            if (number == 0) {
                break;
            }
            switch (wire) {
                case 0: {
                    long[] value = new long[1];
                    at = readVarint(data, at, value);
                    if (at < 0) {
                        return fields;
                    }
                    fields.add(new Field(number, wire, value[0], null));
                    break;
                }
                case 1: {
                    if (at + 8 > data.length) {
                        return fields;
                    }
                    at += 8;
                    fields.add(new Field(number, wire, 0, null));
                    break;
                }
                case 2: {
                    long[] length = new long[1];
                    at = readVarint(data, at, length);
                    if (at < 0) {
                        return fields;
                    }
                    int size = (int) length[0];
                    if (size < 0 || at + size > data.length) {
                        return fields;
                    }
                    byte[] slice = new byte[size];
                    System.arraycopy(data, at, slice, 0, size);
                    at += size;
                    fields.add(new Field(number, wire, 0, slice));
                    break;
                }
                case 5: {
                    if (at + 4 > data.length) {
                        return fields;
                    }
                    at += 4;
                    fields.add(new Field(number, wire, 0, null));
                    break;
                }
                default:
                    return fields;
            }
        }
        return fields;
    }

    private static int readVarint(byte[] data, int at, long[] out) {
        long value = 0;
        int shift = 0;
        while (at < data.length && shift < 64) {
            int b = data[at++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                out[0] = value;
                return at;
            }
            shift += 7;
        }
        return -1;
    }

    static Field find(List<Field> fields, int number) {
        for (Field field : fields) {
            if (field.number == number) {
                return field;
            }
        }
        return null;
    }

    static long number(List<Field> fields, int number, long fallback) {
        Field field = find(fields, number);
        return field == null ? fallback : field.value;
    }

    static String text(List<Field> fields, int number, String fallback) {
        Field field = find(fields, number);
        return field == null || field.wire != 2 ? fallback : field.text();
    }

    static List<Field> nested(List<Field> fields, int number) {
        Field field = find(fields, number);
        return field == null || field.wire != 2
                ? Collections.<Field>emptyList()
                : parse(field.bytes);
    }
}
