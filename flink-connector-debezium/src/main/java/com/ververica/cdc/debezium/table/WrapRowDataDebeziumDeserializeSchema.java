/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.debezium.table;

import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import com.ververica.cdc.debezium.JsonDebeziumDeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.Collector;
import org.apache.kafka.connect.source.SourceRecord;

import java.io.Serializable;

/**
 * Deserialization schema from Debezium object to Flink Table/SQL internal data structure {@link RowData}.
 */
public final class WrapRowDataDebeziumDeserializeSchema implements DebeziumDeserializationSchema<RowData> {
    private static final long serialVersionUID = -4852684966051743777L;

    /**
     * Custom validator to validate the row value.
     */
    public interface ValueValidator extends Serializable {
        void validate(RowData rowData, RowKind rowKind) throws Exception;
    }

    /**
     * TypeInformation of the produced {@link RowData}.
     **/
    private final TypeInformation<RowData> resultTypeInfo;

    /**
     * Runtime converter that converts {@link JsonNode}s into
     * objects of Flink SQL internal data structures.
     **/
    private final DeserializationRuntimeConverter runtimeConverter;

    private final JsonDebeziumDeserializationSchema debeziumDeserializationSchema;


    public WrapRowDataDebeziumDeserializeSchema(RowType rowType, TypeInformation<RowData> resultTypeInfo, JsonDebeziumDeserializationSchema deserializationSchema) {
        this.runtimeConverter = createConverter(rowType);
        this.resultTypeInfo = resultTypeInfo;
        this.debeziumDeserializationSchema = deserializationSchema;
    }


    @Override
    public void deserialize(SourceRecord record, Collector<RowData> out) throws Exception {
        GenericRowData row  = extractRow(debeziumDeserializationSchema.deserialize(record));
        row.setRowKind(RowKind.INSERT);
        out.collect(row);
    }

    private GenericRowData extractRow(String row) throws Exception {
        return (GenericRowData) runtimeConverter.convert(row);
    }


    @Override
    public TypeInformation<RowData> getProducedType() {
        return resultTypeInfo;
    }

    // -------------------------------------------------------------------------------------
    // Runtime Converters
    // -------------------------------------------------------------------------------------

    /**
     * Runtime converter that converts objects of Debezium into objects of Flink Table & SQL internal data structures.
     */
    @FunctionalInterface
    private interface DeserializationRuntimeConverter extends Serializable {
        Object convert(Object row) throws Exception;
    }


    /**
     * Creates a runtime converter which is null safe.
     */
    private DeserializationRuntimeConverter createConverter(LogicalType type) {
        return wrapIntoNullableConverter(createNotNullConverter(type));
    }

    /**
     * Creates a runtime converter which assuming input object is not null.
     */
    private DeserializationRuntimeConverter createNotNullConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case NULL:
                return (row) -> null;
            case VARCHAR:
                return this::convertToString;
            case ROW:
                return createRowConverter((RowType) type);
            case ARRAY:
            case MAP:
            case MULTISET:
            case RAW:
            default:
                throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }


    private DeserializationRuntimeConverter createRowConverter(RowType rowType) {
//        final DeserializationRuntimeConverter[] fieldConverters = rowType.getFields().stream()
//                .map(RowType.RowField::getType)
//                .map(this::createConverter)
//                .toArray(DeserializationRuntimeConverter[]::new);
        final String[] fieldNames = rowType.getFieldNames().toArray(new String[0]);

        return (obj) -> {
            int arity = fieldNames.length;
            GenericRowData row = new GenericRowData(arity);
            if (arity == 1) {
                Object convertedField = StringData.fromString((String) obj);
                row.setField(0, convertedField);
                return row;
            }
            throw new Exception("this DeserializeSchema only support one column and string type");
        };

    }

    private StringData convertToString(Object obj) {
        return StringData.fromString(obj.toString());
    }

//    private Object convertField(
//            StringRowDataDebeziumDeserializeSchema.DeserializationRuntimeConverter fieldConverter,
//            Object fieldValue) throws Exception {
//        if (fieldValue == null) {
//            return null;
//        } else {
//            return fieldConverter.convert(fieldValue);
//        }
//    }


    private DeserializationRuntimeConverter wrapIntoNullableConverter(
            DeserializationRuntimeConverter converter) {
        return (row) -> {
            if (row == null) {
                return null;
            }
            return converter.convert(row);
        };
    }
}
