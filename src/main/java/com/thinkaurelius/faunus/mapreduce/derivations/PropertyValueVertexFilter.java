package com.thinkaurelius.faunus.mapreduce.derivations;

import com.thinkaurelius.faunus.FaunusVertex;
import com.thinkaurelius.faunus.Holder;
import com.thinkaurelius.faunus.Tokens;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Query;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static com.tinkerpop.blueprints.Direction.IN;
import static com.tinkerpop.blueprints.Direction.OUT;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class PropertyValueVertexFilter {

    public static final String KEY = Tokens.makeNamespace(PropertyValueVertexFilter.class) + ".key";
    public static final String VALUE = Tokens.makeNamespace(PropertyValueVertexFilter.class) + ".value";
    public static final String VALUE_CLASS = Tokens.makeNamespace(PropertyValueVertexFilter.class) + ".valueClass";
    public static final String COMPARE = Tokens.makeNamespace(PropertyValueVertexFilter.class) + ".compare";

    public enum Counters {
        VERTICES_KEPT,
        VERTICES_DROPPED
    }

    public static class Map extends Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>> {

        private String key;
        private Object value;
        private Query.Compare compare;

        private final Holder<FaunusVertex> holder = new Holder<FaunusVertex>();
        private final LongWritable longWritable = new LongWritable();

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.key = context.getConfiguration().get(KEY);
            final Class valueClass = context.getConfiguration().getClass(VALUE_CLASS, String.class);
            if (valueClass.equals(String.class)) {
                this.value = context.getConfiguration().get(VALUE);
            } else if (Number.class.isAssignableFrom((valueClass))) {
                this.value = context.getConfiguration().getFloat(VALUE, 0.0f);
            } else if (valueClass.equals(Boolean.class)) {
                this.value = context.getConfiguration().getBoolean(VALUE, false);
            } else {
                throw new IOException("Class " + valueClass + " is an unsupported value class");
            }

            this.compare = Query.Compare.valueOf(context.getConfiguration().get(COMPARE));
        }

        @Override
        public void map(final NullWritable key, final FaunusVertex value, final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws IOException, InterruptedException {

            if (this.isLegal(value)) {
                this.longWritable.set(value.getIdAsLong());
                context.write(this.longWritable, this.holder.set('v', value));
                context.getCounter(Counters.VERTICES_KEPT).increment(1l);
            } else {
                context.getCounter(Counters.VERTICES_DROPPED).increment(1l);
                this.holder.set('k', value.cloneId());
                for (final Edge edge : value.getEdges(OUT)) {
                    final Long id = (Long) edge.getVertex(IN).getId();
                    if (!id.equals(value.getId())) {
                        this.longWritable.set(id);
                        context.write(this.longWritable, this.holder);
                    }
                }
                for (final Edge edge : value.getEdges(IN)) {
                    final Long id = (Long) edge.getVertex(OUT).getId();
                    if (!id.equals(value.getId())) {
                        this.longWritable.set(id);
                        context.write(this.longWritable, this.holder);
                    }
                }
            }
        }

        private boolean isLegal(final Element element) {
            Object elementValue = element.getProperty(this.key);
            if (elementValue instanceof Number)
                elementValue = ((Number) elementValue).floatValue();

            switch (this.compare) {
                case EQUAL:
                    if (null == elementValue)
                        return this.value == null;
                    return elementValue.equals(this.value);
                case NOT_EQUAL:
                    if (null == elementValue)
                        return this.value != null;
                    return !elementValue.equals(this.value);
                case GREATER_THAN:
                    if (null == elementValue || this.value == null)
                        return false;
                    return ((Comparable) elementValue).compareTo(this.value) >= 1;
                case LESS_THAN:
                    if (null == elementValue || this.value == null)
                        return false;
                    return ((Comparable) elementValue).compareTo(this.value) <= -1;
                case GREATER_THAN_EQUAL:
                    if (null == elementValue || this.value == null)
                        return false;
                    return ((Comparable) elementValue).compareTo(this.value) >= 0;
                case LESS_THAN_EQUAL:
                    if (null == elementValue || this.value == null)
                        return false;
                    return ((Comparable) elementValue).compareTo(this.value) <= 0;
                default:
                    throw new IllegalArgumentException("Invalid state as no valid filter was provided");
            }
        }
    }

    public static class Reduce extends Reducer<LongWritable, Holder<FaunusVertex>, NullWritable, FaunusVertex> {
        @Override
        public void reduce(final LongWritable key, final Iterable<Holder<FaunusVertex>> values, final Reducer<LongWritable, Holder<FaunusVertex>, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            FaunusVertex vertex = null;
            final Set<Long> ids = new HashSet<Long>();
            for (final Holder<FaunusVertex> holder : values) {
                final char tag = holder.getTag();
                if (tag == 'k') {
                    ids.add(holder.get().getIdAsLong());
                    // todo: once vertex is found, do individual removes to save memory
                } else if (tag == 'v') {
                    vertex = holder.get();
                } else {
                    throw new IOException("A tag of " + tag + " is not a legal tag for this operation");
                }
            }
            if (null != vertex) {
                if (ids.size() > 0)
                    vertex.removeEdgesToFrom(ids);
                context.write(NullWritable.get(), vertex);
            }
        }
    }
}