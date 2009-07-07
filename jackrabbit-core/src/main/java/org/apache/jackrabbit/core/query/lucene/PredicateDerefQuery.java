/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.query.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.spi.Name;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.HitCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.Weight;

/**
 * Implements a Lucene <code>Query</code> which returns the nodes which have a 
 * reference property which matches the nodes of the subquery.
 */
public class PredicateDerefQuery extends Query {

    /**
     * The context query
     */
    private final Query subQuery;

    /**
     * The name of the reference property.
     */
    private final String refProperty;

    /**
     * The nameTest to apply on target node, or <code>null</code> if all
     * target nodes should be selected.
     */
    private final Name nameTest;

    /**
     * The index format version.
     */
    private final IndexFormatVersion version;

    /**
     * The internal namespace mappings.
     */
    private final NamespaceMappings nsMappings;

    /**
     * The scorer of the context query
     */
    private Scorer subQueryScorer;

    /**
     * The scorer of the name test query
     */
    private Scorer nameTestScorer;
    /**
     * Creates a new <code>DerefQuery</code> based on a <code>context</code>
     * query.
     *
     * @param context the context for this query.
     * @param subQuery TODO
     * @param refProperty the name of the reference property.
     * @param nameTest a name test or <code>null</code> if any node is
     *  selected.
     * @param version the index format version.
     * @param nsMappings the namespace mappings.
     */
    PredicateDerefQuery(Query subQuery, String refProperty,
               Name nameTest, IndexFormatVersion version, NamespaceMappings nsMappings) {
        this.subQuery = subQuery;
        this.refProperty = refProperty;
        this.nameTest = nameTest;
        this.version = version;
        this.nsMappings = nsMappings;
    }

    /**
     * Creates a <code>Weight</code> instance for this query.
     *
     * @param searcher the <code>Searcher</code> instance to use.
     * @return a <code>DerefWeight</code>.
     */
    protected Weight createWeight(Searcher searcher) {
        return new DerefWeight(searcher);
    }

    /**
     * Returns PredicateDerefQuery(subQuery, referenceNodeProperty, nameTest)
     *
     * @param field the name of a field.
     * @return 'DerefQuery'.
     */
    public String toString(String field) {
        StringBuffer sb = new StringBuffer();
        sb.append("PredicateDerefQuery(");
        sb.append(subQuery);
        sb.append(", ");
        sb.append(nameTest);
        sb.append(", ");
        sb.append(refProperty);
        sb.append(")");
        return sb.toString();
    }


    /**
     * {@inheritDoc}
     */
    public void extractTerms(Set terms) {
        // no terms to extract
    }

    /**
     * {@inheritDoc}
     */
    public Query rewrite(IndexReader reader) throws IOException {
        Query cQuery = subQuery.rewrite(reader);
        if (cQuery == subQuery) {
            return this;
        } else {
            return new PredicateDerefQuery(subQuery, refProperty, nameTest, version, nsMappings);
        }
    }

    //-------------------< DerefWeight >------------------------------------

    /**
     * The <code>Weight</code> implementation for this <code>DerefQuery</code>.
     */
    private class DerefWeight implements Weight {

        /**
         * The searcher in use
         */
        private final Searcher searcher;

        /**
         * Creates a new <code>DerefWeight</code> instance using
         * <code>searcher</code>.
         *
         * @param searcher a <code>Searcher</code> instance.
         */
        private DerefWeight(Searcher searcher) {
            this.searcher = searcher;
        }

        /**
         * Returns this <code>DerefQuery</code>.
         *
         * @return this <code>DerefQuery</code>.
         */
        public Query getQuery() {
            return PredicateDerefQuery.this;
        }

        /**
         * {@inheritDoc}
         */
        public float getValue() {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public float sumOfSquaredWeights() throws IOException {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public void normalize(float norm) {
        }

        /**
         * Creates a scorer for this <code>DerefQuery</code>.
         *
         * @param reader a reader for accessing the index.
         * @return a <code>DerefScorer</code>.
         * @throws IOException if an error occurs while reading from the index.
         */
        public Scorer scorer(IndexReader reader) throws IOException {
            subQueryScorer = subQuery.weight(searcher).scorer(reader);
            if (nameTest != null) {
                nameTestScorer = new NameQuery(nameTest, version, nsMappings).weight(searcher).scorer(reader);
            }
            return new DerefScorer(searcher.getSimilarity(), reader);
        }

        /**
         * {@inheritDoc}
         */
        public Explanation explain(IndexReader reader, int doc) throws IOException {
            return new Explanation();
        }
    }

    //----------------------< DerefScorer >---------------------------------

    /**
     * Implements a <code>Scorer</code> for this <code>DerefQuery</code>.
     */
    private class DerefScorer extends Scorer {

        /**
         * An <code>IndexReader</code> to access the index.
         */
        private final IndexReader reader;

        /**
         * BitSet storing the id's of selected documents
         */
        private final BitSet subQueryHits;
        
        /**
         * BitSet storing the id's of selected documents
         */
        private final BitSet hits;

        /**
         * List of UUIDs of selected nodes
         */
        private List uuids = null;

        
        /**
         * The next document id to return
         */
        private int nextDoc = -1;

        /**
         * Creates a new <code>DerefScorer</code>.
         *
         * @param similarity the <code>Similarity</code> instance to use.
         * @param reader     for index access.
         */
        protected DerefScorer(Similarity similarity, IndexReader reader) {
            super(similarity);
            this.reader = reader;
            this.hits = new BitSet(reader.maxDoc());
            this.subQueryHits = new BitSet(reader.maxDoc());
        }

        /**
         * {@inheritDoc}
         */
        public boolean next() throws IOException {
            calculateChildren();
            nextDoc = hits.nextSetBit(nextDoc + 1);
            return nextDoc > -1;
        }

        /**
         * {@inheritDoc}
         */
        public int doc() {
            return nextDoc;
        }

        /**
         * {@inheritDoc}
         */
        public float score() throws IOException {
            return 1.0f;
        }

        /**
         * {@inheritDoc}
         */
        public boolean skipTo(int target) throws IOException {
            calculateChildren();
            nextDoc = hits.nextSetBit(target);
            return nextDoc > -1;
        }

        /**
         * {@inheritDoc}
         *
         * @throws UnsupportedOperationException this implementation always
         *                                       throws an <code>UnsupportedOperationException</code>.
         */
        public Explanation explain(int doc) throws IOException {
            throw new UnsupportedOperationException();
        }


        /**
         * Perform the sub query
         * For each reference property UUID
         *      - find document number
         *      - if document # is in subquery bitset add to bit set
         * Use the name test to filter the documents
         * @throws IOException
         */
        private void calculateChildren() throws IOException {
            if (uuids == null) {
                uuids = new ArrayList();
//                subQueryHits.clear();
//                hits.clear();
                subQueryScorer.score(new HitCollector() {
                    public void collect(int doc, float score) {
                        subQueryHits.set(doc);
                    }
                });

                TermDocs termDocs = reader.termDocs(new Term(FieldNames.PROPERTIES_SET, refProperty));
                String prefix = FieldNames.createNamedValue(refProperty, "");
                while (termDocs.next()) {
                    int doc = termDocs.doc();
                     
                    String[] values = reader.document(doc).getValues(FieldNames.PROPERTIES);
                    if (values == null) {
                        // no reference properties at all on this node
                        continue;
                    }
                    for (int v = 0; v < values.length; v++) {
                        if (values[v].startsWith(prefix)) {
                            String uuid = values[v].substring(prefix.length());
                            
                            TermDocs node = reader.termDocs(new Term(FieldNames.UUID, uuid));
                            try {
                                while (node.next()) {
                                    if (subQueryHits.get(node.doc())) {
                                        hits.set(doc);
                                    }
                                }
                            } finally {
                                node.close();
                            }
                        }
                    }
                }
                
                // collect nameTest hits
                final BitSet nameTestHits = new BitSet();
                if (nameTestScorer != null) {
                    nameTestScorer.score(new HitCollector() {
                        public void collect(int doc, float score) {
                            nameTestHits.set(doc);
                        }
                    });
                }

                // filter out the target nodes that do not match the name test
                // if there is any name test at all.
                if (nameTestScorer != null) {
                    hits.and(nameTestHits);
                }
            }
        }
    }
}
