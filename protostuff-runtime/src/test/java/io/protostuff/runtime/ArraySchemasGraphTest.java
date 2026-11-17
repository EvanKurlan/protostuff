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
import static io.protostuff.runtime.ArrayTestSupport.describe;
import static io.protostuff.runtime.ArrayTestSupport.newArray;
import static io.protostuff.runtime.ArrayTestSupport.newStrategy;
import static io.protostuff.runtime.ArrayTestSupport.schema;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;
import io.protostuff.Schema;
import io.protostuff.runtime.ArrayTestSupport.Format;
import io.protostuff.runtime.ArrayTestSupport.Kind;
import io.protostuff.runtime.ArrayTestSupport.Pattern;
import io.protostuff.runtime.NullArrayElementTest.Size;
import io.protostuff.runtime.NullArrayElementTest.SomePojo;
import io.protostuff.runtime.SampleDelegates.Singleton;

/**
 * T3 (GR-1..GR-5): the graph format must preserve the identity of arrays that are referenced more than once, and
 * cycles that pass through arrays. Every array class registers the freshly allocated array with
 * {@code GraphInput.updateLast(...)} before reading its elements; before this suite no test reached that code.
 */
public class ArraySchemasGraphTest extends TestCase
{
    static final Format GRAPH = Format.GRAPH;

    static IdStrategy strategy(int extraFlags)
    {
        return newStrategy(ArrayTestSupport.BASE_FLAGS | IdStrategy.PRESERVE_NULL_ELEMENTS | extraFlags);
    }

    // ------------------------------------------------------------------
    // GR-1 / GR-5: the same array in two Object fields (entry path B), every kind
    // ------------------------------------------------------------------

    public static final class SharedObject
    {
        Object a;
        Object b;
    }

    public void testSharedArrayInObjectFields()
    {
        Schema<SharedObject> schema = schema(SharedObject.class, strategy(0));
        for (Kind kind : Kind.values())
        {
            for (Pattern pattern : new Pattern[] { Pattern.EMPTY, Pattern.NONE, Pattern.MIX })
            {
                if (!pattern.appliesTo(kind))
                    continue;
                SharedObject m = new SharedObject();
                m.a = m.b = newArray(kind, pattern);

                SharedObject copy = GRAPH.roundTrip(m, schema);
                String label = kind + "/" + pattern;
                assertSame(label + ": identity lost", copy.a, copy.b);
                assertTrue(label + ": " + describe(copy.a), deepEq(m.a, copy.a));
            }
        }
    }

    // ------------------------------------------------------------------
    // GR-5: the same array in two typed fields (entry path A)
    // ------------------------------------------------------------------

    public static final class SharedTyped
    {
        int[] p1, p2;
        Integer[] b1, b2;
        double[] dp1, dp2;
        String[] s1, s2;
        Date[] d1, d2;
        Size[] e1, e2;
        SomePojo[] o1, o2;
        Singleton[] g1, g2;
    }

    public void testSharedArrayInTypedFields()
    {
        Schema<SharedTyped> schema = schema(SharedTyped.class, strategy(0));
        SharedTyped m = new SharedTyped();
        m.p1 = m.p2 = new int[] { 1, 2 };
        m.b1 = m.b2 = new Integer[] { 1, null, 3 };
        m.dp1 = m.dp2 = new double[] { 1.5d };
        m.s1 = m.s2 = new String[] { "x", null };
        m.d1 = m.d2 = new Date[] { null };
        m.e1 = m.e2 = new Size[] { Size.SMALL, null };
        m.o1 = m.o2 = new SomePojo[] { new SomePojo("p"), null };
        m.g1 = m.g2 = new Singleton[] { Singleton.INSTANCE, null };

        SharedTyped copy = GRAPH.roundTrip(m, schema);
        assertSame(copy.p1, copy.p2);
        assertSame(copy.b1, copy.b2);
        assertSame(copy.dp1, copy.dp2);
        assertSame(copy.s1, copy.s2);
        assertSame(copy.d1, copy.d2);
        assertSame(copy.e1, copy.e2);
        assertSame(copy.o1, copy.o2);
        assertSame(copy.g1, copy.g2);
        assertTrue(deepEq(m, copy));
    }

    // ------------------------------------------------------------------
    // GR-2 / GR-5: the same array twice in a list (path C and C_COLLECTION) and a map (path D)
    // ------------------------------------------------------------------

    public static final class SharedInCollections
    {
        List<int[]> lp;
        List<Integer[]> lb;
        List<String[]> ls;
        List<SomePojo[]> lo;
        List<Singleton[]> lg;
        Map<String, int[]> mp;
        Map<String, Integer[]> mb;
        Map<String, String[]> ms;
        Map<String, SomePojo[]> mo;
        Map<String, Singleton[]> mg;
    }

    static <T> List<T> twice(T value)
    {
        List<T> list = new ArrayList<T>();
        list.add(value);
        list.add(value);
        return list;
    }

    static <T> Map<String, T> twiceMap(T value)
    {
        Map<String, T> map = new LinkedHashMap<String, T>();
        map.put("k1", value);
        map.put("k2", value);
        return map;
    }

    void checkSharedInCollections(int extraFlags)
    {
        Schema<SharedInCollections> schema = schema(SharedInCollections.class, strategy(extraFlags));
        SharedInCollections m = new SharedInCollections();
        m.lp = twice(new int[] { 1, 2 });
        m.lb = twice(new Integer[] { 1, null });
        m.ls = twice(new String[] { "s", null });
        m.lo = twice(new SomePojo[] { new SomePojo("p") });
        m.lg = twice(new Singleton[] { Singleton.INSTANCE });
        m.mp = twiceMap(new int[] { 3 });
        m.mb = twiceMap(new Integer[] { null, 4 });
        m.ms = twiceMap(new String[] { "t" });
        m.mo = twiceMap(new SomePojo[] { null, new SomePojo("q") });
        m.mg = twiceMap(new Singleton[] { null });

        SharedInCollections copy = GRAPH.roundTrip(m, schema);
        assertTrue(deepEq(m, copy));
        assertSame(copy.lp.get(0), copy.lp.get(1));
        assertSame(copy.lb.get(0), copy.lb.get(1));
        assertSame(copy.ls.get(0), copy.ls.get(1));
        assertSame(copy.lo.get(0), copy.lo.get(1));
        assertSame(copy.lg.get(0), copy.lg.get(1));
        assertSame(copy.mp.get("k1"), copy.mp.get("k2"));
        assertSame(copy.mb.get("k1"), copy.mb.get("k2"));
        assertSame(copy.ms.get("k1"), copy.ms.get("k2"));
        assertSame(copy.mo.get("k1"), copy.mo.get("k2"));
        assertSame(copy.mg.get("k1"), copy.mg.get("k2"));
    }

    public void testSharedArrayInCollections()
    {
        checkSharedInCollections(0);
    }

    public void testSharedArrayInCollectionsWithCollectionSchema()
    {
        checkSharedInCollections(IdStrategy.COLLECTION_SCHEMA_ON_REPEATED_FIELDS);
    }

    // ------------------------------------------------------------------
    // GR-3: back-references from pojo array elements to the owner
    // ------------------------------------------------------------------

    public static final class Node
    {
        String name;
        Node parent;
        Node[] children;
    }

    /**
     * The tree is nested in a holder on purpose: GraphIOUtil does not resolve back-references to the top-level
     * message passed to mergeFrom (with or without arrays), which is outside the scope of ArraySchemas.
     */
    public static final class Tree
    {
        Node root;
    }

    public void testPojoArrayBackReference()
    {
        Schema<Tree> schema = schema(Tree.class, strategy(0));
        Node root = new Node();
        root.name = "root";
        Node c1 = new Node();
        c1.name = "c1";
        c1.parent = root;
        Node c2 = new Node();
        c2.name = "c2";
        c2.parent = root;
        c2.children = new Node[] { root, null, c1 };
        root.children = new Node[] { c1, c2 };
        Tree tree = new Tree();
        tree.root = root;

        Node copy = GRAPH.roundTrip(tree, schema).root;
        assertEquals("root", copy.name);
        assertNull(copy.parent);
        assertEquals(2, copy.children.length);
        Node k1 = copy.children[0], k2 = copy.children[1];
        assertEquals("c1", k1.name);
        assertEquals("c2", k2.name);
        assertSame(copy, k1.parent);
        assertSame(copy, k2.parent);
        assertEquals(3, k2.children.length);
        assertSame(copy, k2.children[0]);
        assertNull(k2.children[1]);
        assertSame(k1, k2.children[2]);
    }

    // ------------------------------------------------------------------
    // GR-4: an Object[] that contains itself
    // ------------------------------------------------------------------

    public static final class SelfRef
    {
        Object value;
    }

    public void testObjectArrayContainingItself()
    {
        Schema<SelfRef> schema = schema(SelfRef.class, strategy(0));
        Object[] array = new Object[3];
        array[0] = array;
        array[1] = "x";
        array[2] = new int[] { 7 };
        SelfRef m = new SelfRef();
        m.value = array;

        SelfRef copy = GRAPH.roundTrip(m, schema);
        Object[] read = (Object[]) copy.value;
        assertEquals(3, read.length);
        assertSame(read, read[0]);
        assertEquals("x", read[1]);
        assertTrue(java.util.Arrays.equals(new int[] { 7 }, (int[]) read[2]));
    }
}
