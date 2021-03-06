/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 * Copyright 2013 Near Infinity Corporation.
 */


package com.nearinfinity.honeycomb.mysql.generators;

import com.google.common.collect.ImmutableList;
import com.nearinfinity.honeycomb.mysql.schema.ColumnSchema;
import com.nearinfinity.honeycomb.mysql.schema.IndexSchema;
import com.nearinfinity.honeycomb.mysql.schema.TableSchema;
import net.java.quickcheck.Generator;
import net.java.quickcheck.generator.CombinedGenerators;
import net.java.quickcheck.generator.PrimitiveGenerators;
import net.java.quickcheck.generator.distribution.Distribution;

import java.util.List;

public class TableSchemaGenerator implements Generator<TableSchema> {
    /**
     * Maximum number of characters in an identifier
     * @see <a href="https://dev.mysql.com/doc/refman/5.5/en/identifiers.html">https://dev.mysql.com/doc/refman/5.5/en/identifiers.html</a>
     */
    public static final int MYSQL_MAX_NAME_LENGTH = 64;

    /**
     * Maximum number of columns per table
     * @see <a href="https://dev.mysql.com/doc/refman/5.5/en/column-count-limit.html">https://dev.mysql.com/doc/refman/5.5/en/column-count-limit.html</a>
     */
//    public static final int MYSQL_MAX_COLUMNS = 4096;
    public static final int MYSQL_MAX_COLUMNS = 32; // Set artificially low so tests are fast

    /**
     * Maximum number of indices per table
     * @see <a href="// https://dev.mysql.com/doc/refman/5.5/en/column-indexes.html">MySQL documentation</a>
     */
    public static final int MYSQL_MAX_INDICES = 16;

    public static final Generator<String> MYSQL_NAME_GEN =
            CombinedGenerators.uniqueValues(PrimitiveGenerators.strings(1, MYSQL_MAX_NAME_LENGTH));

    private static final Generator<List<ColumnSchema>> COLUMNS_GEN =
            CombinedGenerators.lists(new ColumnSchemaGenerator(),
                    PrimitiveGenerators.integers(1, MYSQL_MAX_COLUMNS,
                            Distribution.POSITIV_NORMAL));

    private final Generator<Integer> numIndicesGen;

    public TableSchemaGenerator() {
        super();
        this.numIndicesGen = PrimitiveGenerators.integers(0, MYSQL_MAX_INDICES);
    }

    public TableSchemaGenerator(int minNumIndices) {
        super();
        this.numIndicesGen = PrimitiveGenerators.integers(minNumIndices, MYSQL_MAX_INDICES);
    }

    @Override
    public TableSchema next() {
        final List<ColumnSchema> columnSchemas = COLUMNS_GEN.next();

        ImmutableList.Builder<String> columns = ImmutableList.builder();
        for (ColumnSchema columnSchema : columnSchemas) {
            columns.add(columnSchema.getColumnName());
        }

        final Generator<List<IndexSchema>> indexGen = CombinedGenerators.lists(
                new IndexSchemaGenerator(columns.build()), numIndicesGen);

        return new TableSchema(columnSchemas, indexGen.next());
    }
}