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
package org.apache.pinot.query.runtime.operator;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.pinot.common.datablock.DataBlock;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.core.data.table.Key;
import org.apache.pinot.query.planner.logical.RexExpression;
import org.apache.pinot.query.planner.partitioning.KeySelector;
import org.apache.pinot.query.planner.plannode.JoinNode;
import org.apache.pinot.query.runtime.blocks.TransferableBlock;
import org.apache.pinot.query.runtime.blocks.TransferableBlockUtils;
import org.apache.pinot.query.runtime.operator.operands.TransformOperand;
import org.apache.pinot.query.runtime.operator.utils.FunctionInvokeUtils;
import org.apache.pinot.query.runtime.plan.OpChainExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This basic {@code BroadcastJoinOperator} implement a basic broadcast join algorithm.
 * This algorithm assumes that the broadcast table has to fit in memory since we are not supporting any spilling.
 *
 * For left join, inner join, right join and full join,
 * <p>It takes the right table as the broadcast side and materialize a hash table. Then for each of the left table row,
 * it looks up for the corresponding row(s) from the hash table and create a joint row.
 *
 * <p>For each of the data block received from the left table, it will generate a joint data block.
 * We currently support left join, inner join, right join and full join.
 * The output is in the format of [left_row, right_row]
 */
// TODO: Move inequi out of hashjoin. (https://github.com/apache/pinot/issues/9728)
public class HashJoinOperator extends MultiStageOperator {
  private static final String EXPLAIN_NAME = "HASH_JOIN";
  private static final Logger LOGGER = LoggerFactory.getLogger(AggregateOperator.class);

  private static final Set<JoinRelType> SUPPORTED_JOIN_TYPES = ImmutableSet.of(
      JoinRelType.INNER, JoinRelType.LEFT, JoinRelType.RIGHT, JoinRelType.FULL, JoinRelType.SEMI, JoinRelType.ANTI);

  private final HashMap<Key, List<Object[]>> _broadcastRightTable;

  // Used to track matched right rows.
  // Only used for right join and full join to output non-matched right rows.
  // TODO: Replace hashset with rolling bit map.
  private final HashMap<Key, HashSet<Integer>> _matchedRightRows;

  private final MultiStageOperator _leftTableOperator;
  private final MultiStageOperator _rightTableOperator;
  private final JoinRelType _joinType;
  private final DataSchema _resultSchema;
  private final int _leftColumnSize;
  private final int _resultColumnSize;
  private final List<TransformOperand> _joinClauseEvaluators;
  private boolean _isHashTableBuilt;

  // Used by non-inner join.
  // Needed to indicate we have finished processing all results after returning last block.
  // TODO: Remove this special handling by fixing data block EOS abstraction or operator's invariant.
  private boolean _isTerminated;
  private TransferableBlock _upstreamErrorBlock;
  private KeySelector<Object[], Object[]> _leftKeySelector;
  private KeySelector<Object[], Object[]> _rightKeySelector;

  public HashJoinOperator(OpChainExecutionContext context, MultiStageOperator leftTableOperator,
      MultiStageOperator rightTableOperator, DataSchema leftSchema, JoinNode node) {
    super(context);
    Preconditions.checkState(SUPPORTED_JOIN_TYPES.contains(node.getJoinRelType()),
        "Join type: " + node.getJoinRelType() + " is not supported!");
    _joinType = node.getJoinRelType();
    _leftKeySelector = node.getJoinKeys().getLeftJoinKeySelector();
    _rightKeySelector = node.getJoinKeys().getRightJoinKeySelector();
    Preconditions.checkState(_leftKeySelector != null, "LeftKeySelector for join cannot be null");
    Preconditions.checkState(_rightKeySelector != null, "RightKeySelector for join cannot be null");
    _leftColumnSize = leftSchema.size();
    Preconditions.checkState(_leftColumnSize > 0, "leftColumnSize has to be greater than zero:" + _leftColumnSize);
    _resultSchema = node.getDataSchema();
    _resultColumnSize = _resultSchema.size();
    Preconditions.checkState(_resultColumnSize >= _leftColumnSize,
        "Result column size" + _leftColumnSize + " has to be greater than or equal to left column size:"
            + _leftColumnSize);
    _leftTableOperator = leftTableOperator;
    _rightTableOperator = rightTableOperator;
    _joinClauseEvaluators = new ArrayList<>(node.getJoinClauses().size());
    for (RexExpression joinClause : node.getJoinClauses()) {
      _joinClauseEvaluators.add(TransformOperand.toTransformOperand(joinClause, _resultSchema));
    }
    _isHashTableBuilt = false;
    _broadcastRightTable = new HashMap<>();
    if (needUnmatchedRightRows()) {
      _matchedRightRows = new HashMap<>();
    } else {
      _matchedRightRows = null;
    }
    _upstreamErrorBlock = null;
  }

  // TODO: Separate left and right table operator.
  @Override
  public List<MultiStageOperator> getChildOperators() {
    return ImmutableList.of(_leftTableOperator, _rightTableOperator);
  }

  @Nullable
  @Override
  public String toExplainString() {
    return EXPLAIN_NAME;
  }

  @Override
  protected TransferableBlock getNextBlock() {
    try {
      if (_isTerminated) {
        return TransferableBlockUtils.getEndOfStreamTransferableBlock();
      }
      if (!_isHashTableBuilt) {
        // Build JOIN hash table
        buildBroadcastHashTable();
      }
      if (_upstreamErrorBlock != null) {
        return _upstreamErrorBlock;
      } else if (!_isHashTableBuilt) {
        return TransferableBlockUtils.getNoOpTransferableBlock();
      }
      TransferableBlock leftBlock = _leftTableOperator.nextBlock();
      // JOIN each left block with the right block.
      return buildJoinedDataBlock(leftBlock);
    } catch (Exception e) {
      return TransferableBlockUtils.getErrorTransferableBlock(e);
    }
  }

  private void buildBroadcastHashTable() {
    TransferableBlock rightBlock = _rightTableOperator.nextBlock();
    while (!rightBlock.isNoOpBlock()) {
      if (rightBlock.isErrorBlock()) {
        _upstreamErrorBlock = rightBlock;
        return;
      }
      if (TransferableBlockUtils.isEndOfStream(rightBlock)) {
        _isHashTableBuilt = true;
        return;
      }
      List<Object[]> container = rightBlock.getContainer();
      // put all the rows into corresponding hash collections keyed by the key selector function.
      for (Object[] row : container) {
        List<Object[]> hashCollection =
            _broadcastRightTable.computeIfAbsent(new Key(_rightKeySelector.getKey(row)), k -> new ArrayList<>());
        hashCollection.add(row);
      }
      rightBlock = _rightTableOperator.nextBlock();
    }
  }

  private TransferableBlock buildJoinedDataBlock(TransferableBlock leftBlock)
      throws Exception {
    if (leftBlock.isErrorBlock()) {
      _upstreamErrorBlock = leftBlock;
      return _upstreamErrorBlock;
    }
    if (leftBlock.isNoOpBlock() || (leftBlock.isSuccessfulEndOfStreamBlock() && !needUnmatchedRightRows())) {
      if (!leftBlock.getResultMetadata().isEmpty()) {
      }

      if (leftBlock.isSuccessfulEndOfStreamBlock()) {
        return TransferableBlockUtils.getEndOfStreamTransferableBlock();
      }

      return leftBlock;
    }
    // TODO: Moved to a different function.
    if (leftBlock.isSuccessfulEndOfStreamBlock() && needUnmatchedRightRows()) {
      // Return remaining non-matched rows for non-inner join.
      List<Object[]> returnRows = new ArrayList<>();
      for (Map.Entry<Key, List<Object[]>> entry : _broadcastRightTable.entrySet()) {
        Set<Integer> matchedIdx = _matchedRightRows.getOrDefault(entry.getKey(), new HashSet<>());
        List<Object[]> rightRows = entry.getValue();
        if (rightRows.size() == matchedIdx.size()) {
          continue;
        }
        for (int i = 0; i < rightRows.size(); i++) {
          if (!matchedIdx.contains(i)) {
            returnRows.add(joinRow(null, rightRows.get(i)));
          }
        }
      }
      _isTerminated = true;
      return new TransferableBlock(returnRows, _resultSchema, DataBlock.Type.ROW);
    }
    List<Object[]> rows = new ArrayList<>();
    List<Object[]> container = leftBlock.isEndOfStreamBlock() ? new ArrayList<>() : leftBlock.getContainer();
    for (Object[] leftRow : container) {
      Key key = new Key(_leftKeySelector.getKey(leftRow));
      switch (_joinType) {
        case SEMI:
          // SEMI-JOIN only checks existence of the key
          if (_broadcastRightTable.containsKey(key)) {
            rows.add(joinRow(leftRow, null));
          }
          break;
        case ANTI:
          // ANTI-JOIN only checks non-existence of the key
          if (!_broadcastRightTable.containsKey(key)) {
            rows.add(joinRow(leftRow, null));
          }
          break;
        default: // INNER, LEFT, RIGHT, FULL
          // NOTE: Empty key selector will always give same hash code.
          List<Object[]> matchedRightRows = _broadcastRightTable.getOrDefault(key, null);
          if (matchedRightRows == null) {
            if (needUnmatchedLeftRows()) {
              rows.add(joinRow(leftRow, null));
            }
            continue;
          }
          boolean hasMatchForLeftRow = false;
          for (int i = 0; i < matchedRightRows.size(); i++) {
            Object[] rightRow = matchedRightRows.get(i);
            // TODO: Optimize this to avoid unnecessary object copy.
            Object[] resultRow = joinRow(leftRow, rightRow);
            if (_joinClauseEvaluators.isEmpty() || _joinClauseEvaluators.stream().allMatch(
                evaluator -> (Boolean) FunctionInvokeUtils.convert(evaluator.apply(resultRow),
                    DataSchema.ColumnDataType.BOOLEAN))) {
              rows.add(resultRow);
              hasMatchForLeftRow = true;
              if (_matchedRightRows != null) {
                HashSet<Integer> matchedRows = _matchedRightRows.computeIfAbsent(key, k -> new HashSet<>());
                matchedRows.add(i);
              }
            }
          }
          if (!hasMatchForLeftRow && needUnmatchedLeftRows()) {
            rows.add(joinRow(leftRow, null));
          }
          break;
      }
    }
    return new TransferableBlock(rows, _resultSchema, DataBlock.Type.ROW);
  }

  private Object[] joinRow(@Nullable Object[] leftRow, @Nullable Object[] rightRow) {
    Object[] resultRow = new Object[_resultColumnSize];
    int idx = 0;
    if (leftRow != null) {
      for (Object obj : leftRow) {
        resultRow[idx++] = obj;
      }
    }
    // This is needed since left row can be null and we need to advance the idx to the beginning of right row.
    idx = _leftColumnSize;
    if (rightRow != null) {
      for (Object obj : rightRow) {
        resultRow[idx++] = obj;
      }
    }
    return resultRow;
  }

  private boolean needUnmatchedRightRows() {
    return _joinType == JoinRelType.RIGHT || _joinType == JoinRelType.FULL;
  }

  private boolean needUnmatchedLeftRows() {
    return _joinType == JoinRelType.LEFT || _joinType == JoinRelType.FULL;
  }
}
