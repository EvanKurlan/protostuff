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

import static io.protostuff.runtime.ArrayTestSupport.deepEq;
import static io.protostuff.runtime.ArrayTestSupport.hex;
import static io.protostuff.runtime.ArrayTestSupport.newArray;
import static io.protostuff.runtime.ArrayTestSupport.newHolder;
import static io.protostuff.runtime.ArrayTestSupport.newStrategy;
import static io.protostuff.runtime.ArrayTestSupport.schema;
import static io.protostuff.runtime.ArrayTestSupport.unhex;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.TestCase;
import io.protostuff.Schema;
import io.protostuff.runtime.ArrayTestSupport.Format;
import io.protostuff.runtime.ArrayTestSupport.Kind;
import io.protostuff.runtime.ArrayTestSupport.Path;
import io.protostuff.runtime.ArrayTestSupport.Pattern;

/**
 * T1 (GB-1..GB-7): pins the exact bytes written for every array element kind, entry path, shape, null-preservation
 * flag and binary format, together with the result of decoding those bytes. Round-trip tests cannot detect a change
 * made symmetrically to reader and writer; this test can.
 * <p>
 * Each line of {@code array-golden/path-<path>.txt} is {@code <kind>.<pattern>.<keep|drop>.<format>=<hex>|<outcome>}
 * where outcome is {@code OK} (decodes to an equal value), {@code MISMATCH} (decodes to a different value) or
 * {@code ERR:<root exception class>:<message>}.
 * <p>
 * Regenerate (only from a commit whose behavior is known-good!) with:
 * {@code mvn -pl protostuff-runtime test -Dtest=ArraySchemasGoldenBytesTest -Dprotostuff.golden.regen=true}
 */
public class ArraySchemasGoldenBytesTest extends TestCase
{
    static final String REGEN_PROPERTY = "protostuff.golden.regen";

    static final String RESOURCE_DIR = "src/test/resources/io/protostuff/runtime/array-golden";

    public void testPathA() throws Exception
    {
        check(Path.A);
    }

    public void testPathB() throws Exception
    {
        check(Path.B);
    }

    public void testPathC() throws Exception
    {
        check(Path.C);
    }

    public void testPathCCollection() throws Exception
    {
        check(Path.C_COLLECTION);
    }

    public void testPathD() throws Exception
    {
        check(Path.D);
    }

    public void testPathDKey() throws Exception
    {
        check(Path.D_KEY);
    }

    static String fileName(Path path)
    {
        return "path-" + path.name().toLowerCase() + ".txt";
    }

    void check(Path path) throws Exception
    {
        TreeMap<String, String> actual = compute(path);

        if (Boolean.getBoolean(REGEN_PROPERTY))
        {
            write(path, actual);
            return;
        }

        Map<String, String> expected = load(path);

        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, String> e : expected.entrySet())
        {
            String got = actual.get(e.getKey());
            if (got == null)
                problems.add("missing case " + e.getKey());
            else if (!got.equals(e.getValue()))
                problems.add(e.getKey() + "\n    expected " + e.getValue() + "\n    actual   " + got);
        }
        for (String key : actual.keySet())
        {
            if (!expected.containsKey(key))
                problems.add("unexpected new case " + key + " (regenerate the golden files if intended)");
        }

        if (!problems.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            sb.append(problems.size()).append(" golden mismatch(es) for path ").append(path).append(":\n");
            for (int i = 0; i < problems.size() && i < 25; i++)
                sb.append("  ").append(problems.get(i)).append('\n');
            fail(sb.toString());
        }
    }

    /**
     * Computes every case for the path from the current code.
     */
    static TreeMap<String, String> compute(Path path)
    {
        TreeMap<String, String> out = new TreeMap<String, String>();
        for (boolean keep : new boolean[] { true, false })
        {
            IdStrategy strategy = newStrategy(path, keep);
            Schema<Object> schema = schema(path.holder, strategy);

            for (Kind kind : Kind.values())
            {
                for (Pattern pattern : Pattern.values())
                {
                    if (!pattern.appliesTo(kind))
                        continue;
                    // the 200-element case is large; pin it on one normal and one derived path
                    if (pattern == Pattern.LONG && path != Path.A && path != Path.D)
                        continue;
                    // the "drop nulls" flag only matters with and without nulls once each
                    if (!keep && pattern != Pattern.NONE && pattern != Pattern.MIX)
                        continue;

                    for (Format format : Format.values())
                    {
                        if (format == Format.GRAPH && pattern != Pattern.NONE && pattern != Pattern.MIX)
                            continue;

                        Object holder = newHolder(path, kind, newArray(kind, pattern));
                        String key = kind + "." + pattern + "." + (keep ? "keep" : "drop") + "." + format;
                        byte[] data = format.write(holder, schema);
                        out.put(key, hex(data) + "|" + decodeOutcome(format, data, holder, schema));
                    }
                }
            }
        }
        return out;
    }

    static String decodeOutcome(Format format, byte[] data, Object expected, Schema<Object> schema)
    {
        try
        {
            Object copy = schema.newMessage();
            format.read(data, copy, schema);
            return deepEq(expected, copy) ? "OK" : "MISMATCH";
        }
        catch (RuntimeException e)
        {
            Throwable root = e;
            while (root.getCause() != null)
                root = root.getCause();
            return "ERR:" + root.getClass().getName() + ":" + root.getMessage();
        }
    }

    static Map<String, String> load(Path path) throws IOException
    {
        InputStream in = ArraySchemasGoldenBytesTest.class.getResourceAsStream(
                "array-golden/" + fileName(path));
        if (in == null)
        {
            // protostuff-runtime-md compiles a copy of these test sources but not the test resources;
            // read the files from the runtime module so the reflection-only build is held to the same bytes
            File sibling = new File(System.getProperty("basedir", "."),
                    "../protostuff-runtime/" + RESOURCE_DIR + "/" + fileName(path));
            if (sibling.isFile())
                in = new java.io.FileInputStream(sibling);
        }
        assertNotNull("golden file " + fileName(path) + " missing; generate it from known-good code with -D"
                + REGEN_PROPERTY + "=true", in);
        Map<String, String> map = new TreeMap<String, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.length() == 0 || line.charAt(0) == '#')
                    continue;
                int eq = line.indexOf('=');
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        finally
        {
            reader.close();
        }
        return map;
    }

    static void write(Path path, TreeMap<String, String> cases) throws IOException
    {
        File dir = new File(System.getProperty("basedir", "."), RESOURCE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Could not create " + dir);
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, fileName(path))), "UTF-8");
        try
        {
            w.write("# Generated by ArraySchemasGoldenBytesTest from the pre-refactor ArraySchemas.\n");
            w.write("# <kind>.<pattern>.<keep|drop nulls>.<format>=<hex bytes>|<decode outcome>\n");
            for (Map.Entry<String, String> e : cases.entrySet())
                w.write(e.getKey() + "=" + e.getValue() + "\n");
        }
        finally
        {
            w.close();
        }
    }

    /**
     * Guards the golden data itself: the hex helpers must be inverses, otherwise every comparison is meaningless.
     */
    public void testHexHelpers()
    {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++)
            all[i] = (byte) i;
        assertTrue(java.util.Arrays.equals(all, unhex(hex(all))));
    }
}
