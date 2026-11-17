//========================================================================
//Copyright 2026
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package io.protostuff.runtime;

import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.protostuff.ByteArrayInput;
import io.protostuff.ByteString;
import io.protostuff.GraphIOUtil;
import io.protostuff.LinkedBuffer;
import io.protostuff.Pipe;
import io.protostuff.ProtobufIOUtil;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.ProtostuffOutput;
import io.protostuff.Schema;
import io.protostuff.runtime.NullArrayElementTest.Size;
import io.protostuff.runtime.NullArrayElementTest.SomePojo;
import io.protostuff.runtime.SampleDelegates.Singleton;

/**
 * Shared fixtures for the {@code ArraySchemas*Test} characterization suite.
 * <p>
 * The suite pins the current behavior of {@link ArraySchemas} (and the derived array schemas declared in
 * {@link IdStrategy}) so that the planned refactor of those classes can be verified to be behavior- and
 * wire-compatible. See {@code ARRAYSCHEMAS_TEST_PLAN.md} at the repository root.
 * <p>
 * Kept Java 6 source compatible (no diamond, lambdas or try-with-resources) to match the project.
 */
public final class ArrayTestSupport
{
    private ArrayTestSupport()
    {
    }

    // ------------------------------------------------------------------
    // Element kinds
    // ------------------------------------------------------------------

    /**
     * Every array element kind handled by {@link ArraySchemas}: the 7 primitive-capable scalars (in primitive and
     * boxed form), the 6 reference scalars, enum, pojo and delegate. The {@link #field} name matches a field on each
     * holder class below.
     */
    public enum Kind
    {
        BOOL_P("boolP", boolean.class, new Object[] { true, false, true }),
        BOOL_B("boolB", Boolean.class, new Object[] { true, false, true }),
        CHAR_P("charP", char.class, new Object[] { 'a', '\uD83D', Character.MAX_VALUE, (char) 0 }),
        CHAR_B("charB", Character.class, new Object[] { 'a', '\uD83D', Character.MAX_VALUE, (char) 0 }),
        SHORT_P("shortP", short.class, new Object[] { Short.MIN_VALUE, (short) -1, (short) 0, Short.MAX_VALUE }),
        SHORT_B("shortB", Short.class, new Object[] { Short.MIN_VALUE, (short) -1, (short) 0, Short.MAX_VALUE }),
        INT_P("intP", int.class, new Object[] { Integer.MIN_VALUE, -1, 0, 300, Integer.MAX_VALUE }),
        INT_B("intB", Integer.class, new Object[] { Integer.MIN_VALUE, -1, 0, 300, Integer.MAX_VALUE }),
        LONG_P("longP", long.class, new Object[] { Long.MIN_VALUE, -1L, 0L, 1L << 40, Long.MAX_VALUE }),
        LONG_B("longB", Long.class, new Object[] { Long.MIN_VALUE, -1L, 0L, 1L << 40, Long.MAX_VALUE }),
        FLOAT_P("floatP", float.class, new Object[] { Float.NaN, -0.0f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, 1.5f, Float.MIN_VALUE }),
        FLOAT_B("floatB", Float.class, new Object[] { Float.NaN, -0.0f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, 1.5f, Float.MIN_VALUE }),
        DOUBLE_P("doubleP", double.class, new Object[] { Double.NaN, -0.0d, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 1.5d, Double.MIN_VALUE }),
        DOUBLE_B("doubleB", Double.class, new Object[] { Double.NaN, -0.0d, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 1.5d, Double.MIN_VALUE }),
        STRING("string", String.class, new Object[] { "", "a", "héllo", "日本" }),
        BYTESTRING("byteString", ByteString.class, new Object[] { ByteString.EMPTY,
                ByteString.copyFrom(new byte[] { 1, 2, -1 }) }),
        BYTEARRAY("byteArray", byte[].class, new Object[] { new byte[0], new byte[] { 1, 2, -1 } }),
        BIGDECIMAL("bigDecimal", BigDecimal.class, new Object[] { new BigDecimal("-1.50"),
                new BigDecimal("1E+3"), BigDecimal.ZERO }),
        BIGINTEGER("bigInteger", BigInteger.class, new Object[] { BigInteger.ONE.negate(),
                new BigInteger("123456789012345678901234567890") }),
        DATE("date", Date.class, new Object[] { new Date(0L), new Date(-1000L), new Date(1600000000000L) }),
        ENUM("enumValue", Size.class, new Object[] { Size.SMALL, Size.MEDIUM, Size.LARGE }),
        POJO("pojo", SomePojo.class, new Object[] { new SomePojo("a"), new SomePojo("b") }),
        DELEGATE("delegate", Singleton.class, new Object[] { Singleton.INSTANCE });

        public final String field;
        public final Class<?> componentType;
        final Object[] samples;

        Kind(String field, Class<?> componentType, Object[] samples)
        {
            this.field = field;
            this.componentType = componentType;
            this.samples = samples;
        }

        public boolean isPrimitive()
        {
            return componentType.isPrimitive();
        }

        public Class<?> arrayType()
        {
            return Array.newInstance(componentType, 0).getClass();
        }

        public Object sample(int i)
        {
            return samples[i % samples.length];
        }
    }

    // ------------------------------------------------------------------
    // Shapes / null patterns
    // ------------------------------------------------------------------

    /**
     * Array shapes. Patterns with {@link #hasNulls} are skipped for primitive kinds.
     */
    public enum Pattern
    {
        EMPTY(false),
        SINGLE(false),
        /** several values, no nulls */
        NONE(false),
        /** {null, x, null, null, x, null}: leading, middle and trailing null runs */
        MIX(true),
        NULL_ALL(true),
        /** 200 elements; for reference kinds indexes 20..169 are null (multi-byte varint length and null count) */
        LONG(true);

        public final boolean hasNulls;

        Pattern(boolean hasNulls)
        {
            this.hasNulls = hasNulls;
        }

        public boolean appliesTo(Kind kind)
        {
            // LONG is meaningful for primitives too (200 non-null values)
            return !kind.isPrimitive() || !hasNulls || this == LONG;
        }

        public boolean producesNulls(Kind kind)
        {
            return hasNulls && !kind.isPrimitive();
        }
    }

    public static Object newArray(Kind kind, Pattern pattern)
    {
        switch (pattern)
        {
            case EMPTY:
                return Array.newInstance(kind.componentType, 0);
            case SINGLE:
                return fill(kind, 1, null);
            case NONE:
                return fill(kind, 5, null);
            case MIX:
                return fill(kind, 6, new boolean[] { true, false, true, true, false, true });
            case NULL_ALL:
                return fill(kind, 3, new boolean[] { true, true, true });
            case LONG:
            {
                boolean[] nulls = new boolean[200];
                if (!kind.isPrimitive())
                    Arrays.fill(nulls, 20, 170, true);
                return fill(kind, 200, nulls);
            }
            default:
                throw new IllegalArgumentException(pattern.toString());
        }
    }

    private static Object fill(Kind kind, int len, boolean[] nulls)
    {
        Object array = Array.newInstance(kind.componentType, len);
        for (int i = 0; i < len; i++)
        {
            if (nulls != null && nulls[i] && !kind.isPrimitive())
                continue;
            Array.set(array, i, kind.sample(i));
        }
        return array;
    }

    // ------------------------------------------------------------------
    // Holders: one per entry path into ArraySchemas
    // ------------------------------------------------------------------

    /** Entry path A: array-typed fields. */
    public static final class PathA
    {
        boolean[] boolP; Boolean[] boolB;
        char[] charP; Character[] charB;
        short[] shortP; Short[] shortB;
        int[] intP; Integer[] intB;
        long[] longP; Long[] longB;
        float[] floatP; Float[] floatB;
        double[] doubleP; Double[] doubleB;
        String[] string; ByteString[] byteString; byte[][] byteArray;
        BigDecimal[] bigDecimal; BigInteger[] bigInteger; Date[] date;
        Size[] enumValue; SomePojo[] pojo; Singleton[] delegate;
    }

    /** Entry path B: an array stored in an {@code Object} field (ObjectSchema inline arrays). */
    public static final class PathB
    {
        Object value;
    }

    /** Entry path C (repeated element) and D (with COLLECTION_SCHEMA_ON_REPEATED_FIELDS): {@code List<X[]>}. */
    public static final class PathC
    {
        List<boolean[]> boolP; List<Boolean[]> boolB;
        List<char[]> charP; List<Character[]> charB;
        List<short[]> shortP; List<Short[]> shortB;
        List<int[]> intP; List<Integer[]> intB;
        List<long[]> longP; List<Long[]> longB;
        List<float[]> floatP; List<Float[]> floatB;
        List<double[]> doubleP; List<Double[]> doubleB;
        List<String[]> string; List<ByteString[]> byteString; List<byte[][]> byteArray;
        List<BigDecimal[]> bigDecimal; List<BigInteger[]> bigInteger; List<Date[]> date;
        List<Size[]> enumValue; List<SomePojo[]> pojo; List<Singleton[]> delegate;
    }

    /** Entry path D (derived schema): arrays as map values. */
    public static final class PathD
    {
        Map<String, boolean[]> boolP; Map<String, Boolean[]> boolB;
        Map<String, char[]> charP; Map<String, Character[]> charB;
        Map<String, short[]> shortP; Map<String, Short[]> shortB;
        Map<String, int[]> intP; Map<String, Integer[]> intB;
        Map<String, long[]> longP; Map<String, Long[]> longB;
        Map<String, float[]> floatP; Map<String, Float[]> floatB;
        Map<String, double[]> doubleP; Map<String, Double[]> doubleB;
        Map<String, String[]> string; Map<String, ByteString[]> byteString; Map<String, byte[][]> byteArray;
        Map<String, BigDecimal[]> bigDecimal; Map<String, BigInteger[]> bigInteger; Map<String, Date[]> date;
        Map<String, Size[]> enumValue; Map<String, SomePojo[]> pojo; Map<String, Singleton[]> delegate;
    }

    /** Entry path D (derived schema): arrays as map keys. */
    public static final class MapKey
    {
        Map<boolean[], String> boolP; Map<Boolean[], String> boolB;
        Map<char[], String> charP; Map<Character[], String> charB;
        Map<short[], String> shortP; Map<Short[], String> shortB;
        Map<int[], String> intP; Map<Integer[], String> intB;
        Map<long[], String> longP; Map<Long[], String> longB;
        Map<float[], String> floatP; Map<Float[], String> floatB;
        Map<double[], String> doubleP; Map<Double[], String> doubleB;
        Map<String[], String> string; Map<ByteString[], String> byteString; Map<byte[][], String> byteArray;
        Map<BigDecimal[], String> bigDecimal; Map<BigInteger[], String> bigInteger; Map<Date[], String> date;
        Map<Size[], String> enumValue; Map<SomePojo[], String> pojo; Map<Singleton[], String> delegate;
    }

    /**
     * The entry paths. {@link #holder} is the pojo type; {@link #extraFlags} are OR-ed into the strategy flags.
     */
    public enum Path
    {
        A(PathA.class, 0),
        B(PathB.class, 0),
        C(PathC.class, 0),
        /** List element through the collection schema, i.e. the derived array schemas */
        C_COLLECTION(PathC.class, IdStrategy.COLLECTION_SCHEMA_ON_REPEATED_FIELDS),
        D(PathD.class, 0),
        D_KEY(MapKey.class, 0);

        public final Class<?> holder;
        public final int extraFlags;

        Path(Class<?> holder, int extraFlags)
        {
            this.holder = holder;
            this.extraFlags = extraFlags;
        }
    }

    /**
     * Builds a holder for the given path with only the kind's slot populated by {@code array}. Collection/map paths
     * also add a second, different array ({@link Pattern#SINGLE}) so that every element is compared.
     */
    public static Object newHolder(Path path, Kind kind, Object array)
    {
        try
        {
            Object holder = path.holder.newInstance();
            Object value;
            switch (path)
            {
                case A:
                    value = array;
                    break;
                case B:
                    ((PathB) holder).value = array;
                    return holder;
                case C:
                case C_COLLECTION:
                {
                    List<Object> list = new ArrayList<Object>();
                    list.add(array);
                    list.add(newArray(kind, Pattern.SINGLE));
                    value = list;
                    break;
                }
                case D:
                {
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    map.put("k1", array);
                    map.put("k2", newArray(kind, Pattern.SINGLE));
                    value = map;
                    break;
                }
                case D_KEY:
                {
                    Map<Object, String> map = new LinkedHashMap<Object, String>();
                    map.put(array, "v1");
                    value = map;
                    break;
                }
                default:
                    throw new IllegalArgumentException(path.toString());
            }
            java.lang.reflect.Field f = path.holder.getDeclaredField(kind.field);
            f.setAccessible(true);
            f.set(holder, value);
            return holder;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * CHARACTERIZATION: possible bug. {@code EnumIO} creates its {@code genericElementSchema} as
     * {@code new ArraySchemas.EnumArray(strategy, null, this)} (EnumIO.java:212) whereas {@code HasSchema} and
     * {@code HasDelegate} pass {@code ArraySchemas.GENERIC_HANDLER}. As a result an enum array used as a collection
     * element or map key/value serializes fine but deserializing it throws a {@link NullPointerException} on the null
     * handler. Returns true for the cases where that currently happens.
     */
    public static boolean hitsEnumNullHandler(Path path, Kind kind, boolean keepNulls)
    {
        if (kind != Kind.ENUM)
            return false;
        switch (path)
        {
            case C:
                // the repeated-field factory only reaches the generic element schema when nulls are preserved
                return keepNulls;
            case C_COLLECTION:
            case D:
            case D_KEY:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------
    // Strategies
    // ------------------------------------------------------------------

    /**
     * Base flags used by the suite. Explicit (not {@link IdStrategy#DEFAULT_FLAGS}) so the pinned bytes do not depend
     * on {@code protostuff.runtime.*} system properties.
     */
    public static final int BASE_FLAGS = IdStrategy.AUTO_LOAD_POLYMORPHIC_CLASSES;

    public static DefaultIdStrategy newStrategy(int flags)
    {
        DefaultIdStrategy strategy = new DefaultIdStrategy(flags);
        strategy.registerDelegate(SampleDelegates.SINGLETON_DELEGATE);
        return strategy;
    }

    public static DefaultIdStrategy newStrategy(Path path, boolean preserveNulls)
    {
        return newStrategy(BASE_FLAGS | path.extraFlags | (preserveNulls ? IdStrategy.PRESERVE_NULL_ELEMENTS : 0));
    }

    @SuppressWarnings("unchecked")
    public static <T> Schema<T> schema(Class<?> holder, IdStrategy strategy)
    {
        return (Schema<T>) RuntimeSchema.getSchema(holder, strategy);
    }

    // ------------------------------------------------------------------
    // Formats
    // ------------------------------------------------------------------

    public enum Format
    {
        PROTOSTUFF
        {
            @Override
            public <T> byte[] write(T message, Schema<T> schema)
            {
                return ProtostuffIOUtil.toByteArray(message, schema, buf());
            }

            @Override
            public <T> void read(byte[] data, T message, Schema<T> schema)
            {
                ProtostuffIOUtil.mergeFrom(data, message, schema);
            }
        },
        PROTOBUF
        {
            @Override
            public <T> byte[] write(T message, Schema<T> schema)
            {
                return ProtobufIOUtil.toByteArray(message, schema, buf());
            }

            @Override
            public <T> void read(byte[] data, T message, Schema<T> schema)
            {
                ProtobufIOUtil.mergeFrom(data, message, schema);
            }
        },
        GRAPH
        {
            @Override
            public <T> byte[] write(T message, Schema<T> schema)
            {
                return GraphIOUtil.toByteArray(message, schema, buf());
            }

            @Override
            public <T> void read(byte[] data, T message, Schema<T> schema)
            {
                GraphIOUtil.mergeFrom(data, message, schema);
            }
        };

        public abstract <T> byte[] write(T message, Schema<T> schema);

        public abstract <T> void read(byte[] data, T message, Schema<T> schema);

        public <T> T roundTrip(T message, Schema<T> schema)
        {
            byte[] data = write(message, schema);
            T copy = schema.newMessage();
            read(data, copy, schema);
            return copy;
        }
    }

    public static LinkedBuffer buf()
    {
        return LinkedBuffer.allocate(512);
    }

    /**
     * Pipes protobuf bytes to protostuff and back, asserting that each piped result is byte-identical to the
     * directly serialized form (the pipe must neither lose nor re-encode anything).
     *
     * @return a failure description, or null on success
     */
    public static <T> String checkPipe(T message, Schema<T> schema, Pipe.Schema<T> pipeSchema)
            throws IOException
    {
        byte[] protobuf = ProtobufIOUtil.toByteArray(message, schema, buf());
        byte[] protostuff = ProtostuffIOUtil.toByteArray(message, schema, buf());

        byte[] protostuffFromPipe = ProtostuffIOUtil.toByteArray(
                ProtobufIOUtil.newPipe(protobuf, 0, protobuf.length), pipeSchema, buf());
        if (!Arrays.equals(protostuff, protostuffFromPipe))
            return "protobuf->protostuff pipe differs: expected " + hex(protostuff) + " got " + hex(protostuffFromPipe);

        byte[] protobufFromPipe = ProtobufIOUtil.toByteArray(
                ProtostuffIOUtil.newPipe(protostuff, 0, protostuff.length), pipeSchema, buf());
        if (!Arrays.equals(protobuf, protobufFromPipe))
            return "protostuff->protobuf pipe differs: expected " + hex(protobuf) + " got " + hex(protobufFromPipe);

        return null;
    }

    // ------------------------------------------------------------------
    // Raw array-schema I/O (bypasses the holder; used by corrupt-input tests)
    // ------------------------------------------------------------------

    /**
     * The array schema that entry path A would use for the kind (no handler; only readFrom/writeTo are used).
     */
    public static ArraySchemas.Base arraySchema(Kind kind, IdStrategy strategy)
    {
        return (ArraySchemas.Base) PolymorphicSchemaFactories.ARRAY.newSchema(kind.arrayType(), strategy, null);
    }

    public static byte[] writeRaw(ArraySchemas.Base schema, Object array) throws IOException
    {
        ProtostuffOutput output = new ProtostuffOutput(buf());
        schema.writeTo(output, array);
        return output.toByteArray();
    }

    public static Object readRaw(ArraySchemas.Base schema, byte[] data) throws IOException
    {
        return schema.readFrom(new ByteArrayInput(data, 0, data.length, true), null);
    }

    // ------------------------------------------------------------------
    // Deep equality
    // ------------------------------------------------------------------

    /**
     * Deep equality over arrays (including primitive and nested arrays, same runtime component type required), lists,
     * maps (array keys compared by content) and the holder classes above (field by field).
     */
    public static boolean deepEq(Object a, Object b)
    {
        if (a == b)
            return true;
        if (a == null || b == null)
            return false;

        if (a.getClass().isArray())
        {
            if (a.getClass() != b.getClass())
                return false;
            if (a.getClass().getComponentType().isPrimitive())
                return Arrays.deepEquals(new Object[] { a }, new Object[] { b });
            // recurse so that lists/maps nested inside Object[] are compared deeply too
            Object[] oa = (Object[]) a, ob = (Object[]) b;
            if (oa.length != ob.length)
                return false;
            for (int i = 0; i < oa.length; i++)
            {
                if (!deepEq(oa[i], ob[i]))
                    return false;
            }
            return true;
        }

        if (a instanceof List)
        {
            if (!(b instanceof List))
                return false;
            List<?> la = (List<?>) a, lb = (List<?>) b;
            if (la.size() != lb.size())
                return false;
            for (int i = 0; i < la.size(); i++)
            {
                if (!deepEq(la.get(i), lb.get(i)))
                    return false;
            }
            return true;
        }

        if (a instanceof Map)
        {
            if (!(b instanceof Map))
                return false;
            Map<?, ?> ma = (Map<?, ?>) a, mb = (Map<?, ?>) b;
            if (ma.size() != mb.size())
                return false;
            outer: for (Map.Entry<?, ?> ea : ma.entrySet())
            {
                for (Map.Entry<?, ?> eb : mb.entrySet())
                {
                    if (deepEq(ea.getKey(), eb.getKey()))
                    {
                        if (!deepEq(ea.getValue(), eb.getValue()))
                            return false;
                        continue outer;
                    }
                }
                return false;
            }
            return true;
        }

        if (!overridesEquals(a.getClass()))
        {
            // holder pojos: compare field by field (must not be cyclic)
            if (a.getClass() != b.getClass())
                return false;
            try
            {
                for (java.lang.reflect.Field f : a.getClass().getDeclaredFields())
                {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                        continue;
                    f.setAccessible(true);
                    if (!deepEq(f.get(a), f.get(b)))
                        return false;
                }
            }
            catch (IllegalAccessException e)
            {
                throw new RuntimeException(e);
            }
            return true;
        }

        return a.equals(b);
    }

    private static boolean overridesEquals(Class<?> clazz)
    {
        try
        {
            return clazz.getMethod("equals", Object.class).getDeclaringClass() != Object.class;
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static Throwable rootCause(Throwable t)
    {
        while (t.getCause() != null && t.getCause() != t)
            t = t.getCause();
        return t;
    }

    public static String describe(Object value)
    {
        if (value != null && value.getClass().isArray())
            return Arrays.deepToString(new Object[] { value });
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Hex
    // ------------------------------------------------------------------

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] data)
    {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++)
        {
            out[i * 2] = HEX[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[data[i] & 0xF];
        }
        return new String(out);
    }

    public static byte[] unhex(String s)
    {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
