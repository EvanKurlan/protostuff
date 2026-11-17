//========================================================================
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

import static io.protostuff.runtime.ArraySchemas.ID_ARRAY_LEN;
import static io.protostuff.runtime.ArrayTestSupport.checkPipe;
import static io.protostuff.runtime.ArrayTestSupport.deepEq;
import static io.protostuff.runtime.ArrayTestSupport.describe;
import static io.protostuff.runtime.ArrayTestSupport.hitsEnumNullHandler;
import static io.protostuff.runtime.ArrayTestSupport.newArray;
import static io.protostuff.runtime.ArrayTestSupport.newHolder;
import static io.protostuff.runtime.ArrayTestSupport.newStrategy;
import static io.protostuff.runtime.ArrayTestSupport.readRaw;
import static io.protostuff.runtime.ArrayTestSupport.rootCause;
import static io.protostuff.runtime.ArrayTestSupport.schema;
import static io.protostuff.runtime.ArrayTestSupport.writeRaw;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BIGDECIMAL;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BIGINTEGER;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BOOL;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BYTE;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BYTES;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_BYTE_ARRAY;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_CHAR;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_DATE;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_DOUBLE;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_FLOAT;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_INT32;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_INT64;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_SHORT;
import static io.protostuff.runtime.RuntimeFieldFactory.ID_STRING;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;
import io.protostuff.ByteArrayInput;
import io.protostuff.Pipe;
import io.protostuff.Schema;
import io.protostuff.runtime.ArrayTestSupport.Format;
import io.protostuff.runtime.ArrayTestSupport.Kind;
import io.protostuff.runtime.ArrayTestSupport.Path;
import io.protostuff.runtime.ArrayTestSupport.Pattern;

/**
 * T5 (EP-1..EP-10, TF-4): the schema selection, id mapping, derived length encoding and naming of
 * {@link ArraySchemas}, plus a full functional matrix of element kind x entry path x shape x binary format,
 * including the pipe.
 */
public class ArraySchemasEntryPathTest extends TestCase
{
    /** The scalar ids that have array schemas, in RuntimeFieldFactory order (ID_BYTE has none: see EP-4). */
    static final int[] PRIMITIVE_CAPABLE = { ID_BOOL, ID_CHAR, ID_SHORT, ID_INT32, ID_INT64, ID_FLOAT, ID_DOUBLE };
    static final int[] REFERENCE_SCALARS = { ID_STRING, ID_BYTES, ID_BYTE_ARRAY, ID_BIGDECIMAL, ID_BIGINTEGER,
            ID_DATE };

    final IdStrategy strategy = newStrategy(ArrayTestSupport.BASE_FLAGS | IdStrategy.PRESERVE_NULL_ELEMENTS);

    // ------------------------------------------------------------------
    // EP-1 / EP-2: which shared schema instance is chosen for an id
    // ------------------------------------------------------------------

    public void testGetSchemaMapping()
    {
        IdStrategy s = strategy;
        Object[][] expected = {
                // id, primitive schema, boxed schema
                { ID_BOOL, s.ARRAY_BOOL_PRIMITIVE_SCHEMA, s.ARRAY_BOOL_BOXED_SCHEMA },
                { ID_CHAR, s.ARRAY_CHAR_PRIMITIVE_SCHEMA, s.ARRAY_CHAR_BOXED_SCHEMA },
                { ID_SHORT, s.ARRAY_SHORT_PRIMITIVE_SCHEMA, s.ARRAY_SHORT_BOXED_SCHEMA },
                { ID_INT32, s.ARRAY_INT32_PRIMITIVE_SCHEMA, s.ARRAY_INT32_BOXED_SCHEMA },
                { ID_INT64, s.ARRAY_INT64_PRIMITIVE_SCHEMA, s.ARRAY_INT64_BOXED_SCHEMA },
                { ID_FLOAT, s.ARRAY_FLOAT_PRIMITIVE_SCHEMA, s.ARRAY_FLOAT_BOXED_SCHEMA },
                { ID_DOUBLE, s.ARRAY_DOUBLE_PRIMITIVE_SCHEMA, s.ARRAY_DOUBLE_BOXED_SCHEMA },
                { ID_STRING, s.ARRAY_STRING_SCHEMA, s.ARRAY_STRING_SCHEMA },
                { ID_BYTES, s.ARRAY_BYTESTRING_SCHEMA, s.ARRAY_BYTESTRING_SCHEMA },
                { ID_BYTE_ARRAY, s.ARRAY_BYTEARRAY_SCHEMA, s.ARRAY_BYTEARRAY_SCHEMA },
                { ID_BIGDECIMAL, s.ARRAY_BIGDECIMAL_SCHEMA, s.ARRAY_BIGDECIMAL_SCHEMA },
                { ID_BIGINTEGER, s.ARRAY_BIGINTEGER_SCHEMA, s.ARRAY_BIGINTEGER_SCHEMA },
                { ID_DATE, s.ARRAY_DATE_SCHEMA, s.ARRAY_DATE_SCHEMA } };
        for (Object[] row : expected)
        {
            int id = (Integer) row[0];
            assertNotNull("id " + id, row[1]);
            assertSame("id " + id + " primitive", row[1], ArraySchemas.getSchema(id, true, s));
            assertSame("id " + id + " boxed", row[2], ArraySchemas.getSchema(id, false, s));
        }
        // primitive and boxed are distinct instances for the primitive-capable ids
        for (int id : PRIMITIVE_CAPABLE)
            assertNotSame(ArraySchemas.getSchema(id, true, s), ArraySchemas.getSchema(id, false, s));
    }

    public void testGenericElementSchemaMapping()
    {
        IdStrategy s = strategy;
        Object[][] expected = {
                { ID_BOOL, s.ARRAY_BOOL_DERIVED_SCHEMA },
                { ID_CHAR, s.ARRAY_CHAR_DERIVED_SCHEMA },
                { ID_SHORT, s.ARRAY_SHORT_DERIVED_SCHEMA },
                { ID_INT32, s.ARRAY_INT32_DERIVED_SCHEMA },
                { ID_INT64, s.ARRAY_INT64_DERIVED_SCHEMA },
                { ID_FLOAT, s.ARRAY_FLOAT_DERIVED_SCHEMA },
                { ID_DOUBLE, s.ARRAY_DOUBLE_DERIVED_SCHEMA },
                // the reference scalars share the inline schema
                { ID_STRING, s.ARRAY_STRING_SCHEMA },
                { ID_BYTES, s.ARRAY_BYTESTRING_SCHEMA },
                { ID_BYTE_ARRAY, s.ARRAY_BYTEARRAY_SCHEMA },
                { ID_BIGDECIMAL, s.ARRAY_BIGDECIMAL_SCHEMA },
                { ID_BIGINTEGER, s.ARRAY_BIGINTEGER_SCHEMA },
                { ID_DATE, s.ARRAY_DATE_SCHEMA } };
        for (Object[] row : expected)
        {
            int id = (Integer) row[0];
            assertNotNull("id " + id, row[1]);
            assertSame("id " + id, row[1], ArraySchemas.getGenericElementSchema(id, s));
        }
        for (int id : PRIMITIVE_CAPABLE)
        {
            assertNotSame("derived differs from boxed for id " + id,
                    ArraySchemas.getSchema(id, false, s), ArraySchemas.getGenericElementSchema(id, s));
        }
    }

    // ------------------------------------------------------------------
    // EP-3: wire ids of inline arrays (written by ObjectSchema)
    // ------------------------------------------------------------------

    public void testArrayIdMapping()
    {
        // id -> {primitive array id, boxed array id}; -1 = not applicable
        int[][] table = {
                { ID_BOOL, 0, 8 },
                { ID_BYTE, 1, 9 },
                { ID_CHAR, 2, 10 },
                { ID_SHORT, 3, 11 },
                { ID_INT32, 4, 12 },
                { ID_INT64, 5, 13 },
                { ID_FLOAT, 6, 14 },
                { ID_DOUBLE, 7, 15 },
                { ID_STRING, -1, 16 },
                { ID_BYTES, -1, 17 },
                { ID_BYTE_ARRAY, -1, 18 },
                { ID_BIGDECIMAL, -1, 19 },
                { ID_BIGINTEGER, -1, 20 },
                { ID_DATE, -1, 21 } };
        for (int[] row : table)
        {
            int id = row[0];
            if (row[1] != -1)
            {
                assertEquals("primitive id " + id, row[1], ArraySchemas.toArrayId(id, true));
                assertTrue(ArraySchemas.isPrimitive(row[1]));
                assertEquals(id, ArraySchemas.toInlineId(row[1]));
            }
            assertEquals("boxed id " + id, row[2], ArraySchemas.toArrayId(id, false));
            assertFalse(ArraySchemas.isPrimitive(row[2]));
            assertEquals(id, ArraySchemas.toInlineId(row[2]));
        }
    }

    // ------------------------------------------------------------------
    // EP-4: unknown ids and the unsupported Byte[] case
    // ------------------------------------------------------------------

    public void testUnknownIds()
    {
        int[] unknown = { 0, ID_BYTE, 15, 99 };
        for (int id : unknown)
        {
            assertShouldNotHappen("getSchema(" + id + ", true)", id, 0);
            assertShouldNotHappen("getSchema(" + id + ", false)", id, 1);
            assertShouldNotHappen("getGenericElementSchema(" + id + ")", id, 2);
            assertShouldNotHappen("newSchema(" + id + ")", id, 3);
        }
    }

    void assertShouldNotHappen(String call, int id, int which)
    {
        try
        {
            switch (which)
            {
                case 0:
                    ArraySchemas.getSchema(id, true, strategy);
                    break;
                case 1:
                    ArraySchemas.getSchema(id, false, strategy);
                    break;
                case 2:
                    ArraySchemas.getGenericElementSchema(id, strategy);
                    break;
                default:
                    ArraySchemas.newSchema(id, Object.class, Object[].class, strategy, null);
            }
            fail(call + " should throw");
        }
        catch (RuntimeException e)
        {
            assertEquals(call, "Should not happen.", e.getMessage());
        }
    }

    public static final class TypedByteArray
    {
        Byte[] value;
    }

    public static final class UntypedByteArray
    {
        Object value;
    }

    /**
     * CHARACTERIZATION: {@code Byte[]} has no array schema (ArraySchemas has no ID_BYTE case). A class with a
     * {@code Byte[]} field cannot even get a schema, and a {@code Byte[]} inside an Object field fails when written.
     * {@code byte[]} is unaffected because it is a scalar (ID_BYTE_ARRAY).
     */
    public void testBoxedByteArrayIsUnsupported()
    {
        try
        {
            schema(TypedByteArray.class, strategy);
            fail("schema created for a Byte[] field; update this characterization");
        }
        catch (RuntimeException e)
        {
            assertEquals("Should not happen.", rootCause(e).getMessage());
        }

        Schema<UntypedByteArray> schema = schema(UntypedByteArray.class, strategy);
        UntypedByteArray m = new UntypedByteArray();
        m.value = new Byte[] { 1, 2 };
        try
        {
            Format.PROTOSTUFF.write(m, schema);
            fail("Byte[] in an Object field serialized; update this characterization");
        }
        catch (RuntimeException e)
        {
            assertEquals("Should not happen.", rootCause(e).getMessage());
        }
    }

    // ------------------------------------------------------------------
    // EP-5..EP-9: functional matrix
    // ------------------------------------------------------------------

    /**
     * Every kind x path x shape x binary format round-trips (lists and maps hold two different arrays and every
     * element is compared). Enum arrays inside collections/maps currently fail on read: see
     * {@link ArrayTestSupport#hitsEnumNullHandler}.
     */
    public void testRoundTripMatrix()
    {
        List<String> failures = new ArrayList<String>();
        int checked = 0;
        for (Path path : Path.values())
        {
            Schema<Object> schema = schema(path.holder, newStrategy(path, true));
            for (Kind kind : Kind.values())
            {
                boolean npeExpected = hitsEnumNullHandler(path, kind, true);
                for (Pattern pattern : Pattern.values())
                {
                    if (!pattern.appliesTo(kind))
                        continue;
                    for (Format format : Format.values())
                    {
                        Object holder = newHolder(path, kind, newArray(kind, pattern));
                        String label = path + "/" + kind + "/" + pattern + "/" + format;
                        checked++;
                        try
                        {
                            Object copy = format.roundTrip(holder, schema);
                            if (npeExpected)
                                failures.add(label + ": enum null-handler case now succeeds; update the characterization");
                            else if (!deepEq(holder, copy))
                                failures.add(label + ": got " + describe(copy));
                        }
                        catch (NullPointerException e)
                        {
                            if (!npeExpected)
                                failures.add(label + ": " + e);
                        }
                        catch (RuntimeException e)
                        {
                            failures.add(label + ": " + rootCause(e));
                        }
                    }
                }
            }
        }
        assertTrue("no cases ran", checked > 1000);
        assertTrue(failures.size() + " failures, e.g. " + failures.subList(0, Math.min(10, failures.size())),
                failures.isEmpty());
    }

    /** EP-8: protobuf <-> protostuff pipe is byte-exact for every kind x path x shape (incl. delegate transfer). */
    public void testPipeMatrix() throws IOException
    {
        List<String> failures = new ArrayList<String>();
        for (Path path : Path.values())
        {
            Schema<Object> schema = schema(path.holder, newStrategy(path, true));
            Pipe.Schema<Object> pipeSchema = ((RuntimeSchema<Object>) schema).getPipeSchema();
            for (Kind kind : Kind.values())
            {
                for (Pattern pattern : Pattern.values())
                {
                    if (!pattern.appliesTo(kind))
                        continue;
                    Object holder = newHolder(path, kind, newArray(kind, pattern));
                    String problem = checkPipe(holder, schema, pipeSchema);
                    if (problem != null)
                        failures.add(path + "/" + kind + "/" + pattern + ": " + problem);
                }
            }
        }
        assertTrue(failures.size() + " failures, e.g. " + failures.subList(0, Math.min(5, failures.size())),
                failures.isEmpty());
    }

    // ------------------------------------------------------------------
    // EP-10: derived length encoding
    // ------------------------------------------------------------------

    static int firstLength(byte[] data) throws IOException
    {
        ByteArrayInput input = new ByteArrayInput(data, 0, data.length, true);
        assertEquals(ID_ARRAY_LEN, input.readFieldNumber(null));
        return input.readInt32();
    }

    /**
     * The derived (collection/map element) schemas write a boxed array's length as -(len + 1) and a primitive
     * array's length as len, and restore the matching component type on read. The non-derived schemas always write
     * len. Reference scalars share one schema, so they always write len.
     */
    public void testDerivedLengthEncoding() throws IOException
    {
        Kind[][] pairs = { { Kind.BOOL_P, Kind.BOOL_B }, { Kind.CHAR_P, Kind.CHAR_B },
                { Kind.SHORT_P, Kind.SHORT_B }, { Kind.INT_P, Kind.INT_B }, { Kind.LONG_P, Kind.LONG_B },
                { Kind.FLOAT_P, Kind.FLOAT_B }, { Kind.DOUBLE_P, Kind.DOUBLE_B } };
        for (int i = 0; i < pairs.length; i++)
        {
            ArraySchemas.Base derived = ArraySchemas.getGenericElementSchema(PRIMITIVE_CAPABLE[i], strategy);
            for (Pattern pattern : new Pattern[] { Pattern.EMPTY, Pattern.NONE })
            {
                Object primitive = newArray(pairs[i][0], pattern);
                Object boxed = newArray(pairs[i][1], pattern);
                int len = Array.getLength(primitive);

                byte[] p = writeRaw(derived, primitive);
                byte[] b = writeRaw(derived, boxed);
                assertEquals(pairs[i][0] + " " + pattern, len, firstLength(p));
                assertEquals(pairs[i][1] + " " + pattern, -(len + 1), firstLength(b));
                assertTrue(deepEq(primitive, readRaw(derived, p)));
                assertTrue(deepEq(boxed, readRaw(derived, b)));

                // non-derived schemas
                assertEquals(len, firstLength(writeRaw(ArraySchemas.getSchema(PRIMITIVE_CAPABLE[i], true,
                        strategy), primitive)));
                assertEquals(len, firstLength(writeRaw(ArraySchemas.getSchema(PRIMITIVE_CAPABLE[i], false,
                        strategy), boxed)));
            }
        }
        for (int id : REFERENCE_SCALARS)
        {
            ArraySchemas.Base derived = ArraySchemas.getGenericElementSchema(id, strategy);
            Kind kind = kindFor(id);
            Object array = newArray(kind, Pattern.MIX);
            byte[] data = writeRaw(derived, array);
            assertEquals(kind.toString(), Array.getLength(array), firstLength(data));
            assertTrue(kind.toString(), deepEq(array, readRaw(derived, data)));
        }
    }

    static Kind kindFor(int referenceScalarId)
    {
        switch (referenceScalarId)
        {
            case ID_STRING:
                return Kind.STRING;
            case ID_BYTES:
                return Kind.BYTESTRING;
            case ID_BYTE_ARRAY:
                return Kind.BYTEARRAY;
            case ID_BIGDECIMAL:
                return Kind.BIGDECIMAL;
            case ID_BIGINTEGER:
                return Kind.BIGINTEGER;
            case ID_DATE:
                return Kind.DATE;
            default:
                throw new IllegalArgumentException(String.valueOf(referenceScalarId));
        }
    }

    // ------------------------------------------------------------------
    // TF-4: field naming (used by the text formats)
    // ------------------------------------------------------------------

    public void testFieldNames()
    {
        assertEquals("a", ArraySchemas.name(ArraySchemas.ID_ARRAY_LEN));
        assertEquals("b", ArraySchemas.name(ArraySchemas.ID_ARRAY_DATA));
        assertEquals("c", ArraySchemas.name(ArraySchemas.ID_ARRAY_NULLCOUNT));
        assertNull(ArraySchemas.name(0));
        assertNull(ArraySchemas.name(4));

        assertEquals(ArraySchemas.ID_ARRAY_LEN, ArraySchemas.number("a"));
        assertEquals(ArraySchemas.ID_ARRAY_DATA, ArraySchemas.number("b"));
        assertEquals(ArraySchemas.ID_ARRAY_NULLCOUNT, ArraySchemas.number("c"));
        assertEquals(0, ArraySchemas.number("d"));
        assertEquals(0, ArraySchemas.number("ab"));
        assertEquals(0, ArraySchemas.number(""));

        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = ArrayTestSupport.arraySchema(kind, strategy);
            assertEquals(kind.toString(), "a", schema.getFieldName(1));
            assertEquals(kind.toString(), 3, schema.getFieldNumber("c"));
            assertEquals(kind.toString(), "Array", schema.messageName());
            assertEquals(kind.toString(), "java.lang.reflect.Array", schema.messageFullName());
        }
    }
}
