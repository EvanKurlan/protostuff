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

import static io.protostuff.runtime.ArraySchemas.ID_ARRAY_DATA;
import static io.protostuff.runtime.ArraySchemas.ID_ARRAY_LEN;
import static io.protostuff.runtime.ArraySchemas.ID_ARRAY_NULLCOUNT;
import static io.protostuff.runtime.ArrayTestSupport.arraySchema;
import static io.protostuff.runtime.ArrayTestSupport.buf;
import static io.protostuff.runtime.ArrayTestSupport.deepEq;
import static io.protostuff.runtime.ArrayTestSupport.describe;
import static io.protostuff.runtime.ArrayTestSupport.newArray;
import static io.protostuff.runtime.ArrayTestSupport.newStrategy;
import static io.protostuff.runtime.ArrayTestSupport.readRaw;
import static io.protostuff.runtime.ArrayTestSupport.rootCause;
import static io.protostuff.runtime.ArrayTestSupport.writeRaw;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;

import junit.framework.TestCase;
import io.protostuff.ProtobufIOUtil;
import io.protostuff.ProtostuffException;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.ProtostuffOutput;
import io.protostuff.runtime.ArrayTestSupport.Kind;
import io.protostuff.runtime.ArrayTestSupport.Pattern;

/**
 * T2 (CI-1..CI-9): malformed array payloads. The array schemas must reject them with
 * {@code ProtostuffException("Corrupt input.")}. Where the current code does something else, the test pins that
 * behavior and is marked {@code CHARACTERIZATION}.
 * <p>
 * Payloads are built directly against each kind's array schema (the one entry path A uses), so every array class is
 * exercised: the 7 primitive-capable scalars as primitive and boxed, the 6 reference scalars, enum, pojo and delegate.
 */
public class ArraySchemasCorruptInputTest extends TestCase
{
    static final String CORRUPT = "Corrupt input.";

    final IdStrategy strategy = newStrategy(ArrayTestSupport.BASE_FLAGS | IdStrategy.PRESERVE_NULL_ELEMENTS);

    // ------------------------------------------------------------------
    // payload builder
    // ------------------------------------------------------------------

    static final class Raw
    {
        final ProtostuffOutput output = new ProtostuffOutput(buf());
        byte[] tail = new byte[0];

        Raw len(int len) throws IOException
        {
            output.writeInt32(ID_ARRAY_LEN, len, false);
            return this;
        }

        Raw nullCount(int count) throws IOException
        {
            output.writeUInt32(ID_ARRAY_NULLCOUNT, count, false);
            return this;
        }

        Raw field(int number, int value) throws IOException
        {
            output.writeInt32(number, value, false);
            return this;
        }

        /** Appends raw bytes after everything written through the builder methods. */
        Raw then(byte[] bytes)
        {
            tail = bytes;
            return this;
        }

        byte[] build()
        {
            byte[] head = output.toByteArray();
            byte[] all = Arrays.copyOf(head, head.length + tail.length);
            System.arraycopy(tail, 0, all, head.length, tail.length);
            return all;
        }
    }

    /**
     * The serialized elements of a valid array, i.e. its bytes without the leading length field.
     */
    byte[] body(ArraySchemas.Base schema, Kind kind, Pattern pattern) throws IOException
    {
        Object array = newArray(kind, pattern);
        byte[] valid = writeRaw(schema, array);
        int len = Array.getLength(array);
        assertTrue("test assumes a 1-byte length", len < 128);
        assertEquals("length tag", (ID_ARRAY_LEN << 3), valid[0]);
        assertEquals("length value", len, valid[1]);
        return Arrays.copyOfRange(valid, 2, valid.length);
    }

    /**
     * Reads the payload and returns either "OK:<value>" or "ERR:<root exception simple name>:<message>".
     */
    String outcome(ArraySchemas.Base schema, byte[] payload)
    {
        try
        {
            return "OK:" + describe(readRaw(schema, payload));
        }
        catch (Throwable t)
        {
            Throwable root = rootCause(t);
            return "ERR:" + root.getClass().getSimpleName() + ":" + root.getMessage();
        }
    }

    void assertCorrupt(Kind kind, String scenario, ArraySchemas.Base schema, byte[] payload)
    {
        try
        {
            Object result = readRaw(schema, payload);
            fail(kind + " " + scenario + ": expected Corrupt input, got " + describe(result));
        }
        catch (ProtostuffException e)
        {
            assertEquals(kind + " " + scenario, CORRUPT, e.getMessage());
        }
        catch (IOException e)
        {
            fail(kind + " " + scenario + ": unexpected " + e);
        }
        catch (RuntimeException e)
        {
            fail(kind + " " + scenario + ": expected ProtostuffException, got " + e);
        }
    }

    // ------------------------------------------------------------------
    // tests
    // ------------------------------------------------------------------

    /** Sanity: the raw helpers round-trip valid data, so the failures below are caused by the corruption only. */
    public void testValidPayloadsRoundTrip() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            for (Pattern pattern : Pattern.values())
            {
                if (!pattern.appliesTo(kind))
                    continue;
                Object array = newArray(kind, pattern);
                Object copy = readRaw(schema, writeRaw(schema, array));
                assertTrue(kind + "/" + pattern + ": " + describe(copy), deepEq(array, copy));
            }
        }
    }

    /** CI-1: the first field must be the length. */
    public void testFirstFieldNotLength() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            byte[] valid = writeRaw(schema, newArray(kind, Pattern.NONE));
            assertCorrupt(kind, "null-count before length",
                    schema, new Raw().nullCount(1).then(valid).build());
            assertCorrupt(kind, "data before length",
                    schema, body(schema, kind, Pattern.NONE));
        }
    }

    /** CI-2: a null-count marker is only legal in non-primitive arrays. */
    public void testNullCountMarker() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            byte[] payload = new Raw().len(1).nullCount(1).build();
            if (kind.isPrimitive())
            {
                assertCorrupt(kind, "null-count in primitive array", schema, payload);
            }
            else
            {
                Object result = readRaw(schema, payload);
                assertEquals(kind.toString(), 1, Array.getLength(result));
                assertNull(kind.toString(), Array.get(result, 0));
                assertSame(kind.componentType, result.getClass().getComponentType());
            }
        }
    }

    /** CI-3: an unknown field number inside the element loop. */
    public void testUnknownFieldInElements() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            assertCorrupt(kind, "unknown field 4", schema, new Raw().len(1).field(4, 0).build());
            assertCorrupt(kind, "length repeated", schema, new Raw().len(1).len(1).build());
        }
    }

    /** CI-4: fewer elements than the declared length. */
    public void testTruncated() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            assertCorrupt(kind, "declared 6, has 5", schema,
                    new Raw().len(6).then(body(schema, kind, Pattern.NONE)).build());
            assertCorrupt(kind, "declared 1, has 0", schema, new Raw().len(1).build());
        }
    }

    /** CI-5: more elements than the declared length (no end-of-array where it is expected). */
    public void testTrailingElements() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            assertCorrupt(kind, "declared 4, has 5", schema,
                    new Raw().len(4).then(body(schema, kind, Pattern.NONE)).build());
            assertCorrupt(kind, "declared 0, has 5", schema,
                    new Raw().len(0).then(body(schema, kind, Pattern.NONE)).build());
        }
    }

    /**
     * CI-6, CHARACTERIZATION: a null count that overshoots the declared length is accepted silently and yields an
     * all-null array of the declared length (no validation that the runs add up).
     */
    public void testNullCountOvershootIsAccepted() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            if (kind.isPrimitive())
                continue;
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            Object result = readRaw(schema, new Raw().len(2).nullCount(5).build());
            assertEquals(kind.toString(), 2, Array.getLength(result));
            assertNull(kind.toString(), Array.get(result, 0));
            assertNull(kind.toString(), Array.get(result, 1));
        }
    }

    /**
     * CI-6b, CHARACTERIZATION: a "negative" (>= 2^31) null count moves the write index backwards, so a following
     * element is stored at index -1 and the read fails with an ArrayIndexOutOfBoundsException rather than
     * "Corrupt input.".
     */
    public void testNegativeNullCount() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            if (kind.isPrimitive())
                continue;
            ArraySchemas.Base schema = arraySchema(kind, strategy);

            // no element follows: the loop keeps running and hits the end of the input
            assertCorrupt(kind, "null-count -1 then end", schema, new Raw().len(2).nullCount(-1).build());

            String out = outcome(schema,
                    new Raw().len(2).nullCount(-1).then(body(schema, kind, Pattern.SINGLE)).build());
            assertTrue(kind + ": " + out, out.startsWith("ERR:ArrayIndexOutOfBoundsException:"));
        }
    }

    /**
     * CI-9, CHARACTERIZATION: a negative declared length is passed straight to the array allocation.
     */
    public void testNegativeLength() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            String out = outcome(schema, new Raw().len(-1).build());
            assertTrue(kind + ": " + out, out.startsWith("ERR:NegativeArraySizeException:"));
        }
    }

    /** CI-7: the derived (collection/map element) schemas validate the length tag too. */
    public void testDerivedSchemaFirstFieldNotLength() throws IOException
    {
        int[] ids = { RuntimeFieldFactory.ID_BOOL, RuntimeFieldFactory.ID_CHAR, RuntimeFieldFactory.ID_SHORT,
                RuntimeFieldFactory.ID_INT32, RuntimeFieldFactory.ID_INT64, RuntimeFieldFactory.ID_FLOAT,
                RuntimeFieldFactory.ID_DOUBLE, RuntimeFieldFactory.ID_STRING, RuntimeFieldFactory.ID_BYTES,
                RuntimeFieldFactory.ID_BYTE_ARRAY, RuntimeFieldFactory.ID_BIGDECIMAL,
                RuntimeFieldFactory.ID_BIGINTEGER, RuntimeFieldFactory.ID_DATE };
        for (int id : ids)
        {
            ArraySchemas.Base derived = ArraySchemas.getGenericElementSchema(id, strategy);
            try
            {
                readRaw(derived, new Raw().nullCount(1).len(1).build());
                fail("derived schema for id " + id + " accepted a payload without a leading length");
            }
            catch (ProtostuffException e)
            {
                assertEquals(CORRUPT, e.getMessage());
            }
        }
    }

    /** CI-8: the pipe (transfer) path rejects the same malformed payloads. */
    public void testPipeRejectsCorruptPayloads() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            byte[][] payloads = {
                    new Raw().nullCount(1).then(writeRaw(schema, newArray(kind, Pattern.NONE))).build(),
                    new Raw().len(1).field(4, 0).build(),
                    new Raw().len(6).then(body(schema, kind, Pattern.NONE)).build(),
                    new Raw().len(4).then(body(schema, kind, Pattern.NONE)).build() };
            for (int i = 0; i < payloads.length; i++)
            {
                byte[] p = payloads[i];
                try
                {
                    ProtobufIOUtil.toByteArray(ProtostuffIOUtil.newPipe(p, 0, p.length),
                            schema.getPipeSchema(), buf());
                    fail(kind + " payload " + i + " was transferred without error");
                }
                catch (RuntimeException e)
                {
                    Throwable root = rootCause(e);
                    assertTrue(kind + " payload " + i + ": " + root, root instanceof ProtostuffException);
                    assertEquals(kind + " payload " + i, CORRUPT, root.getMessage());
                }
            }
        }
    }

    /**
     * G9, CHARACTERIZATION: a negative length reaching the pipe. The shared transferObject (scalars, delegate) maps
     * len -> -len-1 because derived boxed arrays use that encoding; the enum and pojo arrays have their own transfer
     * code without that mapping. Both currently accept len = -1 as an empty array.
     */
    public void testPipeNegativeLength() throws IOException
    {
        for (Kind kind : Kind.values())
        {
            ArraySchemas.Base schema = arraySchema(kind, strategy);
            byte[] p = new Raw().len(-1).build();
            byte[] out = ProtobufIOUtil.toByteArray(ProtostuffIOUtil.newPipe(p, 0, p.length),
                    schema.getPipeSchema(), buf());
            // the length is written back unchanged
            assertTrue(kind.toString(), Arrays.equals(new Raw().len(-1).build(), out));
        }
    }
}
