/**
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
 */
package org.apache.pinot.core.query.distinct.raw;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.pinot.common.request.context.ExpressionContext;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.common.utils.DataSchema.ColumnDataType;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.data.table.Record;
import org.apache.pinot.core.operator.blocks.ValueBlock;
import org.apache.pinot.core.query.distinct.DistinctExecutor;
import org.apache.pinot.core.query.distinct.DistinctTable;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.roaringbitmap.RoaringBitmap;


/**
 * Base implementation of {@link DistinctExecutor} for single raw INT column.
 */
abstract class BaseRawIntSingleColumnDistinctExecutor implements DistinctExecutor {
  final ExpressionContext _expression;
  final DataType _dataType;
  final int _limit;
  final boolean _nullHandlingEnabled;

  final IntSet _valueSet;
  // Stored outside _valueSet to continue to use an IntSet instead of ObjectOpenHashSet (avoid boxing/unboxing).
  protected boolean _hasNull;

  BaseRawIntSingleColumnDistinctExecutor(ExpressionContext expression, DataType dataType, int limit,
      boolean nullHandlingEnabled) {
    _expression = expression;
    _dataType = dataType;
    _limit = limit;
    _nullHandlingEnabled = nullHandlingEnabled;

    _valueSet = new IntOpenHashSet(Math.min(limit, MAX_INITIAL_CAPACITY));
  }

  @Override
  public DistinctTable getResult() {
    DataSchema dataSchema = new DataSchema(new String[]{_expression.toString()},
        new ColumnDataType[]{ColumnDataType.fromDataTypeSV(_dataType)});
    List<Record> records = new ArrayList<>(_valueSet.size());
    IntIterator valueIterator = _valueSet.iterator();
    while (valueIterator.hasNext()) {
      records.add(new Record(new Object[]{valueIterator.nextInt()}));
    }
    if (_hasNull) {
      records.add(new Record(new Object[]{null}));
    }
    assert records.size() <= _limit;
    return new DistinctTable(dataSchema, records, _nullHandlingEnabled);
  }

  @Override
  public boolean process(ValueBlock valueBlock) {
    BlockValSet blockValueSet = valueBlock.getBlockValueSet(_expression);
    int numDocs = valueBlock.getNumDocs();
    if (blockValueSet.isSingleValue()) {
      int[] values = blockValueSet.getIntValuesSV();
      if (_nullHandlingEnabled) {
        RoaringBitmap nullBitmap = blockValueSet.getNullBitmap();
        for (int i = 0; i < numDocs; i++) {
          if (nullBitmap != null && nullBitmap.contains(i)) {
            _hasNull = true;
          } else if (add(values[i])) {
            return true;
          }
        }
      } else {
        for (int i = 0; i < numDocs; i++) {
          if (add(values[i])) {
            return true;
          }
        }
      }
    } else {
      int[][] values = blockValueSet.getIntValuesMV();
      for (int i = 0; i < numDocs; i++) {
        for (int value : values[i]) {
          if (add(value)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  protected abstract boolean add(int val);
}
