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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import io.protostuff.GraphIOUtil;
import io.protostuff.Pipe;
import io.protostuff.Schema;

/**
 * Graph (protostuff with references) impl for {@link NullArrayElementInObjectArrayTest}: runs the whole null-pattern matrix through
 * {@link GraphIOUtil}, i.e. through the {@code GraphInput.updateLast} branch of every array schema.
 */
public class GraphNullArrayElementInObjectArrayTest extends NullArrayElementInObjectArrayTest
{

    @Override
    protected <T> void mergeFrom(byte[] data, int offset, int length,
            T message, Schema<T> schema) throws IOException
    {
        GraphIOUtil.mergeFrom(data, offset, length, message, schema);
    }

    @Override
    protected <T> void mergeFrom(InputStream in, T message, Schema<T> schema)
            throws IOException
    {
        GraphIOUtil.mergeFrom(in, message, schema);
    }

    @Override
    protected <T> byte[] toByteArray(T message, Schema<T> schema)
    {
        return GraphIOUtil.toByteArray(message, schema, buf());
    }

    @Override
    protected <T> void writeTo(OutputStream out, T message, Schema<T> schema)
            throws IOException
    {
        GraphIOUtil.writeTo(out, message, schema, buf());
    }

    /**
     * The graph format has no pipe; instead check that the streaming writer produces the same bytes as the buffered
     * one and that those bytes read back equal.
     */
    @Override
    protected <T> void roundTrip(T message, Schema<T> schema,
            Pipe.Schema<T> pipeSchema) throws IOException
    {
        byte[] buffered = toByteArray(message, schema);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTo(out, message, schema);
        assertTrue(Arrays.equals(buffered, out.toByteArray()));

        T parsed = schema.newMessage();
        mergeFrom(new ByteArrayInputStream(out.toByteArray()), parsed, schema);
        // some fixtures have no equals(); compare structurally
        assertTrue(ArrayTestSupport.deepEq(message, parsed));
    }

}
