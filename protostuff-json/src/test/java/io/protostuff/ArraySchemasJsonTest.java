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

package io.protostuff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;
import io.protostuff.runtime.ArrayTestSupport;
import io.protostuff.runtime.ArrayTestSupport.Kind;
import io.protostuff.runtime.ArrayTestSupport.Path;
import io.protostuff.runtime.ArrayTestSupport.Pattern;

/**
 * T6 (TF-1..TF-3): runtime arrays through JSON, which uses the array schemas' field names ("a" = length, "b" = data,
 * "c" = null count) or, in numeric mode, their field numbers.
 */
public class ArraySchemasJsonTest extends TestCase
{
    static String toJson(Object message, Schema<Object> schema, boolean numeric)
    {
        try
        {
            return new String(JsonIOUtil.toByteArray(message, schema, numeric, LinkedBuffer.allocate(512)),
                    "UTF-8");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    static Object fromJson(String json, Schema<Object> schema, boolean numeric) throws IOException
    {
        Object message = schema.newMessage();
        JsonIOUtil.mergeFrom(json.getBytes("UTF-8"), message, schema, numeric);
        return message;
    }

    /**
     * TF-1: the exact named-mode output for a small fixture.
     * <p>
     * CHARACTERIZATION: a boxed array with a null run in the middle is written as separate "b" (data) members around
     * the "c" (null count) member, i.e. a JSON object with a duplicate key. protostuff reads it back correctly, but
     * generic JSON parsers may reject the object or keep only the last "b".
     */
    public void testNamedOutput()
    {
        Schema<Object> schema = ArrayTestSupport.schema(Path.A.holder, ArrayTestSupport.newStrategy(Path.A, true));
        assertEquals("{\"intP\":{\"a\":2,\"b\":[5,6]},"
                + "\"intB\":{\"a\":4,\"b\":[1],\"c\":2,\"b\":[4]},"
                + "\"string\":{\"a\":2,\"c\":1,\"b\":[\"s\"]},"
                + "\"enumValue\":{\"a\":1,\"b\":[0]}}",
                toJson(fixture(), schema, false));
    }

    /** TF-2: numeric mode uses the field numbers (1 = length, 2 = data, 3 = null count). */
    public void testNumericOutput()
    {
        Schema<Object> schema = ArrayTestSupport.schema(Path.A.holder, ArrayTestSupport.newStrategy(Path.A, true));
        assertEquals("{\"7\":{\"1\":2,\"2\":[5,6]},"
                + "\"8\":{\"1\":4,\"2\":[1],\"3\":2,\"2\":[4]},"
                + "\"15\":{\"1\":2,\"3\":1,\"2\":[\"s\"]},"
                + "\"21\":{\"1\":1,\"2\":[0]}}",
                toJson(fixture(), schema, true));
    }

    /** TF-1/TF-2: the pinned strings read back to the fixture. */
    public void testPinnedOutputReadsBack() throws IOException
    {
        Schema<Object> schema = ArrayTestSupport.schema(Path.A.holder, ArrayTestSupport.newStrategy(Path.A, true));
        Object fixture = fixture();
        assertTrue(ArrayTestSupport.deepEq(fixture, fromJson(toJson(fixture, schema, false), schema, false)));
        assertTrue(ArrayTestSupport.deepEq(fixture, fromJson(toJson(fixture, schema, true), schema, true)));
    }

    /**
     * TF-3: every kind x path x shape round-trips through JSON in both named and numeric mode, except enum arrays in
     * collections/maps (see {@link ArrayTestSupport#hitsEnumNullHandler}), which fail on read here too.
     */
    public void testRoundTripMatrix() throws IOException
    {
        List<String> failures = new ArrayList<String>();
        int checked = 0;
        for (Path path : Path.values())
        {
            Schema<Object> schema = ArrayTestSupport.schema(path.holder, ArrayTestSupport.newStrategy(path, true));
            for (Kind kind : Kind.values())
            {
                for (Pattern pattern : Pattern.values())
                {
                    if (!pattern.appliesTo(kind))
                        continue;
                    for (boolean numeric : new boolean[] { false, true })
                    {
                        Object holder = ArrayTestSupport.newHolder(path, kind,
                                ArrayTestSupport.newArray(kind, pattern));
                        String label = path + "/" + kind + "/" + pattern + (numeric ? "/numeric" : "/named");
                        boolean npeExpected = ArrayTestSupport.hitsEnumNullHandler(path, kind, true);
                        checked++;
                        try
                        {
                            Object copy = fromJson(toJson(holder, schema, numeric), schema, numeric);
                            if (npeExpected)
                                failures.add(label + ": enum null-handler case now succeeds; update the characterization");
                            else if (!ArrayTestSupport.deepEq(holder, copy))
                                failures.add(label + ": MISMATCH " + ArrayTestSupport.describe(copy));
                        }
                        catch (Exception e)
                        {
                            Throwable root = ArrayTestSupport.rootCause(e);
                            if (!(npeExpected && root instanceof NullPointerException))
                                failures.add(label + ": ERR " + root);
                        }
                    }
                }
            }
        }
        assertTrue("no cases ran", checked > 500);
        assertTrue(failures.size() + " failures, e.g. " + failures.subList(0, Math.min(10, failures.size())),
                failures.isEmpty());
    }

    static Object fixture()
    {
        Object holder = ArrayTestSupport.newHolder(Path.A, Kind.INT_B, new Integer[] { 1, null, null, 4 });
        try
        {
            set(holder, "intP", new int[] { 5, 6 });
            set(holder, "string", new String[] { null, "s" });
            set(holder, "enumValue", ArrayTestSupport.newArray(Kind.ENUM, Pattern.SINGLE));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        return holder;
    }

    static void set(Object holder, String field, Object value) throws Exception
    {
        java.lang.reflect.Field f = holder.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(holder, value);
    }
}
