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

import static io.protostuff.runtime.ArrayTestSupport.checkPipe;
import static io.protostuff.runtime.ArrayTestSupport.deepEq;
import static io.protostuff.runtime.ArrayTestSupport.hex;
import static io.protostuff.runtime.ArrayTestSupport.hitsEnumNullHandler;
import static io.protostuff.runtime.ArrayTestSupport.newArray;
import static io.protostuff.runtime.ArrayTestSupport.newHolder;
import static io.protostuff.runtime.ArrayTestSupport.newStrategy;
import static io.protostuff.runtime.ArrayTestSupport.rootCause;
import static io.protostuff.runtime.ArrayTestSupport.schema;

import java.io.IOException;
import java.util.Arrays;

import junit.framework.TestCase;
import io.protostuff.Pipe;
import io.protostuff.ProtostuffException;
import io.protostuff.Schema;
import io.protostuff.runtime.ArrayTestSupport.Format;
import io.protostuff.runtime.ArrayTestSupport.Kind;
import io.protostuff.runtime.ArrayTestSupport.Path;
import io.protostuff.runtime.ArrayTestSupport.Pattern;

/**
 * T4 (NP-1..NP-3): behavior of null array elements with and without {@link IdStrategy#PRESERVE_NULL_ELEMENTS}.
 */
public class ArraySchemasNullPolicyTest extends TestCase
{
    /**
     * NP-1, CHARACTERIZATION: with PRESERVE_NULL_ELEMENTS off (the default), a non-primitive array containing nulls
     * is written without error (the nulls are skipped but the full length is still written) and then fails to
     * deserialize with "Corrupt input.". Applies to every entry path and binary format.
     * <p>
     * If this is fixed on purpose, update this test in a separate commit with a CHANGELOG entry.
     */
    public void testNullsWithoutPreserveFlagFailOnRead()
    {
        for (Path path : Path.values())
        {
            Schema<Object> schema = schema(path.holder, newStrategy(path, false));
            for (Kind kind : Kind.values())
            {
                if (kind.isPrimitive())
                    continue;
                for (Format format : Format.values())
                {
                    Object holder = newHolder(path, kind, newArray(kind, Pattern.MIX));
                    byte[] data = format.write(holder, schema);
                    String label = path + "/" + kind + "/" + format;
                    try
                    {
                        format.read(data, schema.newMessage(), schema);
                        fail(label + ": expected Corrupt input");
                    }
                    catch (RuntimeException e)
                    {
                        Throwable root = rootCause(e);
                        assertTrue(label + ": " + root, root instanceof ProtostuffException);
                        assertEquals(label, "Corrupt input.", root.getMessage());
                    }
                }
            }
        }
    }

    /**
     * NP-2: without nulls, the flag must not change the bytes (except on path C, see below), and the data must
     * round-trip (except where the enum null-handler characterization applies).
     */
    public void testFlagDoesNotAffectNullFreeArrays()
    {
        java.util.Set<String> bytesDiffer = new java.util.TreeSet<String>();
        for (Path path : Path.values())
        {
            Schema<Object> keep = schema(path.holder, newStrategy(path, true));
            Schema<Object> drop = schema(path.holder, newStrategy(path, false));
            for (Kind kind : Kind.values())
            {
                for (Format format : Format.values())
                {
                    Object holder = newHolder(path, kind, newArray(kind, Pattern.NONE));
                    String label = path + "/" + kind + "/" + format;
                    byte[] withFlag = format.write(holder, keep);
                    byte[] withoutFlag = format.write(holder, drop);
                    if (!hex(withFlag).equals(hex(withoutFlag)))
                        bytesDiffer.add(path.toString());

                    Object copy = drop.newMessage();
                    try
                    {
                        format.read(withoutFlag, copy, drop);
                        assertFalse(label + ": enum null-handler case now succeeds; update hitsEnumNullHandler",
                                hitsEnumNullHandler(path, kind, false));
                        assertTrue(label, deepEq(holder, copy));
                    }
                    catch (NullPointerException e)
                    {
                        assertTrue(label + ": unexpected " + e, hitsEnumNullHandler(path, kind, false));
                    }
                }
            }
        }
        // CHARACTERIZATION: only the repeated-field path (List<X[]> without COLLECTION_SCHEMA_ON_REPEATED_FIELDS)
        // changes its encoding with the flag, because RuntimeRepeatedFieldFactory then routes the list through the
        // collection schema. The array encoding itself is unaffected by the flag when there are no nulls.
        assertEquals("[C]", bytesDiffer.toString());
    }

    /**
     * NP-3: delegate arrays with every null pattern on every path (the existing NullArrayElementTest has no delegate
     * coverage), including the pipe.
     */
    public void testDelegateArraysWithNulls() throws IOException
    {
        Kind kind = Kind.DELEGATE;
        for (Path path : Path.values())
        {
            Schema<Object> schema = schema(path.holder, newStrategy(path, true));
            Pipe.Schema<Object> pipeSchema = ((RuntimeSchema<Object>) schema).getPipeSchema();
            for (Pattern pattern : Pattern.values())
            {
                Object holder = newHolder(path, kind, newArray(kind, pattern));
                String label = path + "/" + pattern;
                for (Format format : Format.values())
                    assertTrue(label + "/" + format, deepEq(holder, format.roundTrip(holder, schema)));
                String pipe = checkPipe(holder, schema, pipeSchema);
                assertNull(label + ": " + pipe, pipe);
            }
        }
    }

    /** Sanity for the null patterns used across the suite. */
    public void testPatterns()
    {
        Integer[] mix = (Integer[]) newArray(Kind.INT_B, Pattern.MIX);
        assertEquals(6, mix.length);
        assertNull(mix[0]);
        assertNotNull(mix[1]);
        assertNull(mix[2]);
        assertNull(mix[3]);
        assertNotNull(mix[4]);
        assertNull(mix[5]);

        Object[] all = (Object[]) newArray(Kind.STRING, Pattern.NULL_ALL);
        assertTrue(Arrays.equals(new Object[3], all));

        Object[] longArray = (Object[]) newArray(Kind.POJO, Pattern.LONG);
        assertEquals(200, longArray.length);
        assertNotNull(longArray[19]);
        assertNull(longArray[20]);
        assertNull(longArray[169]);
        assertNotNull(longArray[170]);

        int[] longPrimitive = (int[]) newArray(Kind.INT_P, Pattern.LONG);
        assertEquals(200, longPrimitive.length);
    }
}
