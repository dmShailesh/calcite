/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rel2sql;

import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalIntersect;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.RavenDistinctProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexFieldCollation;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexPatternFieldRef;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexWindow;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlAsOperator;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlFieldAccess;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlMatchRecognize;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOverOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.SqlSetOperator;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlCaseOperator;
import org.apache.calcite.sql.fun.SqlCountAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlSumEmptyIsZeroAggFunction;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Sarg;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * State for generating a SQL statement.
 */
public abstract class SqlImplementor {

  // Always use quoted position, the "isQuoted" info is only used when
  // unparsing a SqlIdentifier. For some rex nodes, saying RexInputRef, we have
  // no idea about whether it is quoted or not for the original sql statement.
  // So we just quote it.
  public static final SqlParserPos POS = SqlParserPos.QUOTED_ZERO;

  public final SqlDialect dialect;
  protected final Set<String> aliasSet = new LinkedHashSet<>();
  protected final Map<String, SqlNode> ordinalMap = new HashMap<>();

  protected final Map<CorrelationId, Context> correlTableMap = new HashMap<>();
  protected boolean isTableNameColumnNameIdentical = false;

  /** Private RexBuilder for short-lived expressions. It has its own
   * dedicated type factory, so don't trust the types to be canonized. */
  final RexBuilder rexBuilder =
      new RexBuilder(new SqlTypeFactoryImpl(RelDataTypeSystemImpl.DEFAULT));

  /** Maps a {@link SqlKind} to a {@link SqlOperator} that implements NOT
   * applied to that kind. */
  private static final Map<SqlKind, SqlOperator> NOT_KIND_OPERATORS =
      ImmutableMap.<SqlKind, SqlOperator>builder()
          .put(SqlKind.IN, SqlStdOperatorTable.NOT_IN)
          .put(SqlKind.NOT_IN, SqlStdOperatorTable.IN)
          .put(SqlKind.LIKE, SqlStdOperatorTable.NOT_LIKE)
          .put(SqlKind.SIMILAR, SqlStdOperatorTable.NOT_SIMILAR_TO)
          .build();

  protected SqlImplementor(SqlDialect dialect) {
    this.dialect = requireNonNull(dialect);
  }

  /** Visits a relational expression that has no parent. */
  public final Result visitRoot(RelNode e) {
    return visitInput(holder(e), 0);
  }

  /** Creates a relational expression that has {@code r} as its input. */
  private static RelNode holder(RelNode r) {
    return new SingleRel(r.getCluster(), r.getTraitSet(), r) {
    };
  }

  // CHECKSTYLE: IGNORE 1
  /** @deprecated Use either {@link #visitRoot(RelNode)} or
   * {@link #visitInput(RelNode, int)}. */
  @Deprecated // to be removed before 2.0
  public final Result visitChild(int i, RelNode e) {
    throw new UnsupportedOperationException();
  }

  /** Visits an input of the current relational expression,
   * deducing {@code anon} using {@link #isAnon()}. */
  public final Result visitInput(RelNode e, int i) {
    return visitInput(e, i, ImmutableSet.of());
  }

  /** Visits an input of the current relational expression,
   * with the given expected clauses. */
  public final Result visitInput(RelNode e, int i, Clause... clauses) {
    return visitInput(e, i, ImmutableSet.copyOf(clauses));
  }

  /** Visits an input of the current relational expression,
   * deducing {@code anon} using {@link #isAnon()}. */
  public final Result visitInput(RelNode e, int i, Set<Clause> clauses) {
    return visitInput(e, i, isAnon(), false, clauses);
  }

  /** Visits the {@code i}th input of {@code e}, the current relational
   * expression.
   *
   * @param e Current relational expression
   * @param i Ordinal of input within {@code e}
   * @param anon Whether to remove trivial aliases such as "EXPR$0"
   * @param ignoreClauses Whether to ignore the expected clauses when deciding
   *   whether a sub-query is required
   * @param expectedClauses Set of clauses that we expect the builder that
   *   consumes this result will create
   * @return Result
   *
   * @see #isAnon()
   */
  public abstract Result visitInput(RelNode e, int i, boolean anon,
      boolean ignoreClauses, Set<Clause> expectedClauses);

  public void addSelect(List<SqlNode> selectList, SqlNode node,
      RelDataType rowType) {
    String name = rowType.getFieldNames().get(selectList.size());
    String alias = SqlValidatorUtil.getAlias(node, -1);
    if (alias == null || !alias.equals(name)) {
      node = as(node, name);
    }
    selectList.add(node);
  }

  /** Convenience method for creating column and table aliases.
   *
   * <p>{@code AS(e, "c")} creates "e AS c";
   * {@code AS(e, "t", "c1", "c2"} creates "e AS t (c1, c2)". */
  protected SqlCall as(SqlNode e, String alias, String... fieldNames) {
    final List<SqlNode> operandList = new ArrayList<>();
    operandList.add(e);
    operandList.add(new SqlIdentifier(alias, POS));
    for (String fieldName : fieldNames) {
      operandList.add(new SqlIdentifier(fieldName, POS));
    }
    return SqlStdOperatorTable.AS.createCall(POS, operandList);
  }

  /** Returns whether a list of expressions projects all fields, in order,
   * from the input, with the same names. */
  public static boolean isStar(List<RexNode> exps, RelDataType inputRowType,
      RelDataType projectRowType) {
    assert exps.size() == projectRowType.getFieldCount();
    int i = 0;
    for (RexNode ref : exps) {
      if (!(ref instanceof RexInputRef)) {
        return false;
      } else if (((RexInputRef) ref).getIndex() != i++) {
        return false;
      }
    }
    return i == inputRowType.getFieldCount()
        && inputRowType.getFieldNames().equals(projectRowType.getFieldNames());
  }

  public static boolean isStar(RexProgram program) {
    int i = 0;
    for (RexLocalRef ref : program.getProjectList()) {
      if (ref.getIndex() != i++) {
        return false;
      }
    }
    return i == program.getInputRowType().getFieldCount();
  }

  public Result setOpToSql(SqlSetOperator operator, RelNode rel) {
    SqlNode node = null;
    for (Ord<RelNode> input : Ord.zip(rel.getInputs())) {
      final Result result = visitInput(rel, input.i);
      if (node == null) {
        node = result.asSelect();
      } else {
        node = operator.createCall(POS, node, result.asSelect());
      }
    }
    assert node != null : "set op must have at least one input, operator = " + operator
        + ", rel = " + rel;
    final List<Clause> clauses =
        Expressions.list(Clause.SET_OP);
    return result(node, clauses, rel, null);
  }

  /**
   * Converts a {@link RexNode} condition into a {@link SqlNode}.
   *
   * @param node            Join condition
   * @param leftContext     Left context
   * @param rightContext    Right context
   * @param leftFieldCount  Number of fields on left result
   * @return SqlNode that represents the condition
   */
  public static SqlNode convertConditionToSqlNode(RexNode node,
      Context leftContext,
      Context rightContext,
      int leftFieldCount,
      SqlDialect dialect) {
    if (node.isAlwaysTrue()) {
      return SqlLiteral.createBoolean(true, POS);
    }
    if (node.isAlwaysFalse()) {
      return SqlLiteral.createBoolean(false, POS);
    }
    final List<RexNode> operands;
    final SqlOperator op;
    final Context joinContext;
    switch (node.getKind()) {
    case AND:
    case OR:
      operands = ((RexCall) node).getOperands();
      op = ((RexCall) node).getOperator();
      final List<SqlNode> sqlOperands = new ArrayList<>();
      for (RexNode operand : operands) {
        sqlOperands.add(
            convertConditionToSqlNode(operand, leftContext,
                rightContext, leftFieldCount, dialect));
      }
      return SqlUtil.createCall(op, POS, sqlOperands);

    case EQUALS:
    case IN:
    case NOT:
    case IS_DISTINCT_FROM:
    case IS_NOT_DISTINCT_FROM:
    case NOT_EQUALS:
    case GREATER_THAN:
    case GREATER_THAN_OR_EQUAL:
    case LESS_THAN:
    case LESS_THAN_OR_EQUAL:
    case LIKE:
      node = stripCastFromString(node, dialect);
      operands = ((RexCall) node).getOperands();
      op = ((RexCall) node).getOperator();
      if (operands.size() == 2
          && operands.get(0) instanceof RexInputRef
          && operands.get(1) instanceof RexInputRef) {
        final RexInputRef op0 = (RexInputRef) operands.get(0);
        final RexInputRef op1 = (RexInputRef) operands.get(1);

        if (op0.getIndex() < leftFieldCount
            && op1.getIndex() >= leftFieldCount) {
          // Arguments were of form 'op0 = op1'
          return op.createCall(POS,
              leftContext.field(op0.getIndex()),
              rightContext.field(op1.getIndex() - leftFieldCount));
        }
        if (op1.getIndex() < leftFieldCount
            && op0.getIndex() >= leftFieldCount) {
          // Arguments were of form 'op1 = op0'
          return reverseOperatorDirection(op).createCall(POS,
              leftContext.field(op1.getIndex()),
              rightContext.field(op0.getIndex() - leftFieldCount));
        }
      }
      joinContext =
          leftContext.implementor().joinContext(leftContext, rightContext);
      return joinContext.toSql(null, node);
    default:
      joinContext = leftContext.implementor().joinContext(leftContext, rightContext);
      return joinContext.toSql(null, node);
    }
  }

  /** Removes cast from string.
   *
   * <p>For example, {@code x > CAST('2015-01-07' AS DATE)}
   * becomes {@code x > '2015-01-07'}.
   */
  private static RexNode stripCastFromString(RexNode node, SqlDialect dialect) {
    switch (node.getKind()) {
    case EQUALS:
    case IS_NOT_DISTINCT_FROM:
    case NOT_EQUALS:
    case GREATER_THAN:
    case GREATER_THAN_OR_EQUAL:
    case LESS_THAN:
    case LESS_THAN_OR_EQUAL:
      final RexCall call = (RexCall) node;
      final RexNode o0 = call.operands.get(0);
      final RexNode o1 = call.operands.get(1);
      if (o0.getKind() == SqlKind.CAST
          && o1.getKind() != SqlKind.CAST) {
        if (!dialect.supportsImplicitTypeCoercion((RexCall) o0)) {
          // If the dialect does not support implicit type coercion,
          // we definitely can not strip the cast.
          return node;
        }
        final RexNode o0b = ((RexCall) o0).getOperands().get(0);
        return call.clone(call.getType(), ImmutableList.of(o0b, o1));
      }
      if (o1.getKind() == SqlKind.CAST
          && o0.getKind() != SqlKind.CAST) {
        if (!dialect.supportsImplicitTypeCoercion((RexCall) o1)) {
          return node;
        }
        final RexNode o1b = ((RexCall) o1).getOperands().get(0);
        return call.clone(call.getType(), ImmutableList.of(o0, o1b));
      }
      break;
    default:
      break;
    }
    return node;
  }

  private static SqlOperator reverseOperatorDirection(SqlOperator op) {
    switch (op.kind) {
    case GREATER_THAN:
      return SqlStdOperatorTable.LESS_THAN;
    case GREATER_THAN_OR_EQUAL:
      return SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
    case LESS_THAN:
      return SqlStdOperatorTable.GREATER_THAN;
    case LESS_THAN_OR_EQUAL:
      return SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
    case EQUALS:
    case IS_NOT_DISTINCT_FROM:
    case NOT_EQUALS:
      return op;
    default:
      throw new AssertionError(op);
    }
  }

  public static JoinType joinType(JoinRelType joinType) {
    switch (joinType) {
    case LEFT:
      return JoinType.LEFT;
    case RIGHT:
      return JoinType.RIGHT;
    case INNER:
      return JoinType.INNER;
    case FULL:
      return JoinType.FULL;
    default:
      throw new AssertionError(joinType);
    }
  }

  /** Creates a result based on a single relational expression. */
  public Result result(SqlNode node, Collection<Clause> clauses,
      RelNode rel, @Nullable Map<String, RelDataType> aliases) {
    assert aliases == null
        || aliases.size() < 2
        || aliases instanceof LinkedHashMap
        || aliases instanceof ImmutableMap
        : "must use a Map implementation that preserves order";
    final String alias2 = SqlValidatorUtil.getAlias(node, -1);
    final String alias3 = alias2 != null ? alias2 : "t";
    final String alias4 =
        SqlValidatorUtil.uniquify(
            alias3, aliasSet, SqlValidatorUtil.EXPR_SUGGESTER);
    String tableName = getTableName(alias4, rel);
    final RelDataType rowType = adjustedRowType(rel, node);
    isTableNameColumnNameIdentical = isTableNameColumnNameIdentical(rowType, tableName);
    if (aliases != null
        && !aliases.isEmpty()
        && (!dialect.hasImplicitTableAlias()
        || (!dialect.supportsIdenticalTableAndColumnName() && isTableNameColumnNameIdentical)
          || aliases.size() > 1)) {
      return result(node, clauses, alias4, rowType, aliases);
    }
    final String alias5;
    if (alias2 == null
        || !alias2.equals(alias4)
        || !dialect.hasImplicitTableAlias()
        || (!dialect.supportsIdenticalTableAndColumnName() && isTableNameColumnNameIdentical)) {
      alias5 = alias4;
    } else {
      alias5 = null;
    }
    return result(node, clauses, alias5, rowType,
        ImmutableMap.of(alias4, rowType));
  }

  private boolean isTableNameColumnNameIdentical(RelDataType rowType, String tableName) {
    final List<RelDataTypeField> fields = rowType.getFieldList();
    return fields.stream().anyMatch(
        field -> field.getKey().equals(tableName)
    );
  }

  /** Factory method for {@link Result}.
   *
   * <p>Call this method rather than creating a {@code Result} directly,
   * because sub-classes may override. */
  protected Result result(SqlNode node, Collection<Clause> clauses,
      @Nullable String neededAlias, @Nullable RelDataType neededType,
      Map<String, RelDataType> aliases) {
    return new Result(node, clauses, neededAlias, neededType, aliases);
  }

  /** Returns the row type of {@code rel}, adjusting the field names if
   * {@code node} is "(query) as tableAlias (fieldAlias, ...)". */
  private static RelDataType adjustedRowType(RelNode rel, SqlNode node) {
    final RelDataType rowType = rel.getRowType();
    final RelDataTypeFactory.Builder builder;
    switch (node.getKind()) {
    case UNION:
    case INTERSECT:
    case EXCEPT:
      return adjustedRowType(rel, ((SqlCall) node).getOperandList().get(0));

    case SELECT:
      final SqlNodeList selectList = ((SqlSelect) node).getSelectList();
      if (selectList == null) {
        return rowType;
      }
      builder = rel.getCluster().getTypeFactory().builder();
      Pair.forEach(selectList,
          rowType.getFieldList(),
          (selectItem, field) ->
              builder.add(
                  Util.first(SqlValidatorUtil.getAlias(selectItem, -1),
                      field.getName()),
                  field.getType()));
      return builder.build();

    case AS:
      final List<SqlNode> operandList = ((SqlCall) node).getOperandList();
      if (operandList.size() <= 2) {
        return rowType;
      }
      builder = rel.getCluster().getTypeFactory().builder();
      Pair.forEach(Util.skip(operandList, 2),
          rowType.getFieldList(),
          (operand, field) ->
              builder.add(operand.toString(), field.getType()));
      return builder.build();

    default:
      return rowType;
    }
  }

  /** Creates a result based on a join. (Each join could contain one or more
   * relational expressions.) */
  public Result result(SqlNode join, Result leftResult, Result rightResult) {
    final Map<String, RelDataType> aliases;
    if (join.getKind() == SqlKind.JOIN) {
      final ImmutableMap.Builder<String, RelDataType> builder =
          ImmutableMap.builder();
      collectAliases(builder, join,
          Iterables.concat(leftResult.aliases.values(),
              rightResult.aliases.values()).iterator());
      aliases = builder.build();
    } else {
      aliases = leftResult.aliases;
    }
    return result(join, ImmutableList.of(Clause.FROM), null, null, aliases);
  }

  private static void collectAliases(ImmutableMap.Builder<String, RelDataType> builder,
      SqlNode node, Iterator<RelDataType> aliases) {
    if (node instanceof SqlJoin) {
      final SqlJoin join = (SqlJoin) node;
      collectAliases(builder, join.getLeft(),  aliases);
      collectAliases(builder, join.getRight(), aliases);
    } else {
      final String alias = SqlValidatorUtil.getAlias(node, -1);
      assert alias != null;
      builder.put(alias, aliases.next());
    }
  }

  /** Returns whether to remove trivial aliases such as "EXPR$0"
   * when converting the current relational expression into a SELECT.
   *
   * <p>For example, INSERT does not care about field names;
   * we would prefer to generate without the "EXPR$0" alias:
   *
   * <pre>{@code INSERT INTO t1 SELECT x, y + 1 FROM t2}</pre>
   *
   * rather than with it:
   *
   * <pre>{@code INSERT INTO t1 SELECT x, y + 1 AS EXPR$0 FROM t2}</pre>
   *
   * <p>But JOIN does care about field names; we have to generate the "EXPR$0"
   * alias:
   *
   * <pre>{@code SELECT *
   * FROM emp AS e
   * JOIN (SELECT x, y + 1 AS EXPR$0) AS d
   * ON e.deptno = d.EXPR$0}
   * </pre>
   *
   * <p>because if we omit "AS EXPR$0" we do not know the field we are joining
   * to, and the following is invalid:
   *
   * <pre>{@code SELECT *
   * FROM emp AS e
   * JOIN (SELECT x, y + 1) AS d
   * ON e.deptno = d.EXPR$0}
   * </pre>
   */
  protected boolean isAnon() {
    return false;
  }

  /** Wraps a node in a SELECT statement that has no clauses:
   *  "SELECT ... FROM (node)". */
  SqlSelect wrapSelect(SqlNode node) {
    assert node instanceof SqlJoin
        || node instanceof SqlIdentifier
        || node instanceof SqlMatchRecognize
        || node instanceof SqlCall
            && (((SqlCall) node).getOperator() instanceof SqlSetOperator
                || ((SqlCall) node).getOperator() == SqlStdOperatorTable.AS
                || ((SqlCall) node).getOperator() == SqlStdOperatorTable.VALUES)
        : node;
    if (requiresAlias(node)) {
      node = as(node, "t");
    }
    return new SqlSelect(POS, SqlNodeList.EMPTY, null, node, null, null, null,
        SqlNodeList.EMPTY, null, null, null, null);
  }

  /** Returns whether we need to add an alias if this node is to be the FROM
   * clause of a SELECT. */
  private boolean requiresAlias(SqlNode node) {
    if (!dialect.requiresAliasForFromItems()) {
      return false;
    }
    switch (node.getKind()) {
    case IDENTIFIER:
      return !dialect.hasImplicitTableAlias() || (!dialect.supportsIdenticalTableAndColumnName()
          && isTableNameColumnNameIdentical);
    case AS:
    case JOIN:
    case EXPLICIT_TABLE:
      return false;
    default:
      return true;
    }
  }

  /** Context for translating a {@link RexNode} expression (within a
   * {@link RelNode}) into a {@link SqlNode} expression (within a SQL parse
   * tree). */
  public abstract static class Context {
    final SqlDialect dialect;
    final int fieldCount;
    private final boolean ignoreCast;

    protected Context(SqlDialect dialect, int fieldCount) {
      this(dialect, fieldCount, false);
    }

    protected Context(SqlDialect dialect, int fieldCount, boolean ignoreCast) {
      this.dialect = dialect;
      this.fieldCount = fieldCount;
      this.ignoreCast = ignoreCast;
    }

    public abstract SqlNode field(int ordinal);

    public abstract SqlNode field(int ordinal, boolean useAlias);

    /** Creates a reference to a field to be used in an ORDER BY clause.
     *
     * <p>By default, it returns the same result as {@link #field}.
     *
     * <p>If the field has an alias, uses the alias.
     * If the field is an unqualified column reference which is the same an
     * alias, switches to a qualified column reference.
     */
    public SqlNode orderField(int ordinal) {
      return field(ordinal);
    }

    /** Converts an expression from {@link RexNode} to {@link SqlNode}
     * format.
     *
     * @param program Required only if {@code rex} contains {@link RexLocalRef}
     * @param rex Expression to convert
     */
    public SqlNode toSql(@Nullable RexProgram program, RexNode rex) {
      final RexSubQuery subQuery;
      final SqlNode sqlSubQuery;
      final RexLiteral literal;
      switch (rex.getKind()) {
      case LOCAL_REF:
        final int index = ((RexLocalRef) rex).getIndex();
        return toSql(program, requireNonNull(program, "program").getExprList().get(index));

      case INPUT_REF:
        return field(((RexInputRef) rex).getIndex());

      case FIELD_ACCESS:
        final Deque<RexFieldAccess> accesses = new ArrayDeque<>();
        RexNode referencedExpr = rex;
        while (referencedExpr.getKind() == SqlKind.FIELD_ACCESS) {
          accesses.offerLast((RexFieldAccess) referencedExpr);
          referencedExpr = ((RexFieldAccess) referencedExpr).getReferenceExpr();
        }
        SqlFieldAccess sqlFieldAccess = new SqlFieldAccess(POS);
        switch (referencedExpr.getKind()) {
        case CORREL_VARIABLE:
          final RexCorrelVariable variable = (RexCorrelVariable) referencedExpr;
          final Context correlAliasContext = getAliasContext(variable);
          final RexFieldAccess lastAccess = accesses.pollLast();
          assert lastAccess != null;
          sqlFieldAccess.add((SqlIdentifier) correlAliasContext
              .field(lastAccess.getField().getIndex()));
          break;
        case ROW:
          final SqlNode expr = toSql(program, referencedExpr);
          sqlFieldAccess.add(expr);
          break;
        default:
          sqlFieldAccess.add(toSql(program, referencedExpr));
        }

        RexFieldAccess access;
        while ((access = accesses.pollLast()) != null) {
          sqlFieldAccess.add(new SqlIdentifier(access.getField().getName(), POS));
        }
        return sqlFieldAccess;
      case PATTERN_INPUT_REF:
        final RexPatternFieldRef ref = (RexPatternFieldRef) rex;
        String pv = ref.getAlpha();
        SqlNode refNode = field(ref.getIndex());
        final SqlIdentifier id = (SqlIdentifier) refNode;
        if (id.names.size() > 1) {
          return id.setName(0, pv);
        } else {
          return new SqlIdentifier(ImmutableList.of(pv, id.names.get(0)), POS);
        }

      case LITERAL:
        return SqlImplementor.toSql(program, (RexLiteral) rex, dialect);

      case CASE:
        final RexCall caseCall = (RexCall) rex;
        final List<SqlNode> caseNodeList =
            toSql(program, caseCall.getOperands());
        final SqlNode valueNode;
        final List<SqlNode> whenList = Expressions.list();
        final List<SqlNode> thenList = Expressions.list();
        final SqlNode elseNode;
        if (caseNodeList.size() % 2 == 0) {
          // switched:
          //   "case x when v1 then t1 when v2 then t2 ... else e end"
          valueNode = caseNodeList.get(0);
          for (int i = 1; i < caseNodeList.size() - 1; i += 2) {
            whenList.add(caseNodeList.get(i));
            thenList.add(caseNodeList.get(i + 1));
          }
        } else {
          // other: "case when w1 then t1 when w2 then t2 ... else e end"
          valueNode = null;
          for (int i = 0; i < caseNodeList.size() - 1; i += 2) {
            whenList.add(caseNodeList.get(i));
            thenList.add(caseNodeList.get(i + 1));
          }
        }
        elseNode = caseNodeList.get(caseNodeList.size() - 1);
        return new SqlCase(POS, valueNode, new SqlNodeList(whenList, POS),
            new SqlNodeList(thenList, POS), elseNode);

      case DYNAMIC_PARAM:
        final RexDynamicParam caseParam = (RexDynamicParam) rex;
        return new SqlDynamicParam(caseParam.getIndex(), POS);

      case IN:
        if (rex instanceof RexSubQuery) {
          subQuery = (RexSubQuery) rex;
          sqlSubQuery = implementor().visitRoot(subQuery.rel).asQueryOrValues();
          final List<RexNode> operands = subQuery.operands;
          SqlNode op0;
          if (operands.size() == 1) {
            op0 = toSql(program, operands.get(0));
          } else {
            final List<SqlNode> cols = toSql(program, operands);
            op0 = new SqlNodeList(cols, POS);
          }
          return subQuery.getOperator().createCall(POS, op0, sqlSubQuery);
        } else {
          final RexCall call = (RexCall) rex;
          final List<SqlNode> cols = toSql(program, call.operands);
          return call.getOperator().createCall(POS, cols.get(0),
            new SqlNodeList(cols.subList(1, cols.size()), POS));
        }
      case SEARCH:
        final RexCall search = (RexCall) rex;
        literal = (RexLiteral) search.operands.get(1);
        final Sarg sarg = castNonNull(literal.getValueAs(Sarg.class));
        //noinspection unchecked
        return toSql(program, search.operands.get(0), literal.getType(), sarg);

      case EXISTS:
      case SCALAR_QUERY:
        subQuery = (RexSubQuery) rex;
        sqlSubQuery =
            implementor().visitRoot(subQuery.rel).asQueryOrValues();
        return subQuery.getOperator().createCall(POS, sqlSubQuery);

      case NOT:
        RexNode operand = ((RexCall) rex).operands.get(0);
        final SqlNode node = toSql(program, operand);
        final SqlOperator inverseOperator = NOT_KIND_OPERATORS.get(operand.getKind());
        if (inverseOperator != null) {
          switch (operand.getKind()) {
          case IN:
            return SqlStdOperatorTable.NOT_IN
                .createCall(POS, ((SqlCall) node).getOperandList());
          case LIKE:
            return SqlStdOperatorTable.NOT_LIKE
                .createCall(POS, ((SqlCall) node).getOperandList());
          case SIMILAR:
            return SqlStdOperatorTable.NOT_SIMILAR_TO
                .createCall(POS, ((SqlCall) node).getOperandList());
          default:
            break;
          }
          return inverseOperator.createCall(POS,
              ((SqlCall) node).getOperandList());
        } else {
          return SqlStdOperatorTable.NOT.createCall(POS, node);
        }
      case IS_NOT_TRUE:
      case IS_TRUE:
        if (!dialect.getConformance().allowIsTrue()) {
          SqlOperator op = dialect.getTargetFunc((RexCall) rex);
          if (op != ((RexCall) rex).op) {
            operand = ((RexCall) rex).operands.get(0);
            final SqlNode nodes = toSql(program, operand);
            return op.createCall(POS, ((SqlCall) nodes).getOperandList());
          }
        }
        List<SqlNode> nodes = toSql(program, ((RexCall) rex).getOperands());
        return ((RexCall) rex).getOperator().createCall(new SqlNodeList(nodes, POS));
      default:
        if (rex instanceof RexOver) {
          return toSql(program, (RexOver) rex);
        }

        return callToSql(program, (RexCall) rex, false);
      }
    }

    private SqlNode callToSql(@Nullable RexProgram program, RexCall rex, boolean not) {
      final RexCall call = (RexCall) stripCastFromString(rex, dialect);
      SqlOperator op = call.getOperator();
      switch (op.getKind()) {
      case SUM0:
        op = SqlStdOperatorTable.SUM;
        break;
      case NOT:
        RexNode operand = call.operands.get(0);
        if (NOT_KIND_OPERATORS.containsKey(operand.getKind())) {
          return callToSql(program, (RexCall) operand, !not);
        }
        break;
      default:
        break;
      }
      if (not) {
        SqlKind kind = op.getKind();
        op = requireNonNull(NOT_KIND_OPERATORS.get(kind),
            () -> "unable to negate " + kind);
      }
      final List<SqlNode> nodeList = toSql(program, call.getOperands());
      switch (call.getKind()) {
      case CAST:
        // CURSOR is used inside CAST, like 'CAST ($0): CURSOR NOT NULL',
        // convert it to sql call of {@link SqlStdOperatorTable#CURSOR}.
        RelDataType dataType = rex.getType();
        if (dataType.getSqlTypeName() == SqlTypeName.CURSOR) {
          RexNode operand0 = ((RexCall) rex).operands.get(0);
          assert operand0 instanceof RexInputRef;
          int ordinal = ((RexInputRef) operand0).getIndex();
          SqlNode fieldOperand = field(ordinal);
          return SqlStdOperatorTable.CURSOR.createCall(SqlParserPos.ZERO, fieldOperand);
        }
        assert nodeList.size() == 1;
        if (ignoreCast) {
          return nodeList.get(0);
        } else {
          RelDataType castFrom = call.operands.get(0).getType();
          RelDataType castTo = call.getType();
          return dialect.getCastCall(nodeList.get(0), castFrom, castTo);
        }
      case PLUS:
      case MINUS:
        op = dialect.getTargetFunc(call);
        break;
      case OTHER_FUNCTION:
        op = dialect.getOperatorForOtherFunc(call);
        break;
      default:
        break;
      }
      return SqlUtil.createCall(op, POS, nodeList);
    }

    /** Converts a Sarg to SQL, generating "operand IN (c1, c2, ...)" if the
     * ranges are all points. */
    @SuppressWarnings({"BetaApi", "UnstableApiUsage"})
    private <C extends Comparable<C>> SqlNode toSql(@Nullable RexProgram program,
        RexNode operand, RelDataType type, Sarg<C> sarg) {
      final List<SqlNode> orList = new ArrayList<>();
      final SqlNode operandSql = toSql(program, operand);
      if (sarg.containsNull) {
        orList.add(SqlStdOperatorTable.IS_NULL.createCall(POS, operandSql));
      }
      if (sarg.isPoints()) {
        final SqlNodeList list = sarg.rangeSet.asRanges().stream()
            .map(range ->
                toSql(program,
                    implementor().rexBuilder.makeLiteral(range.lowerEndpoint(),
                        type, true, true)))
            .collect(SqlNode.toList());
        switch (list.size()) {
        case 1:
          orList.add(
              SqlStdOperatorTable.EQUALS.createCall(POS, operandSql,
                  list.get(0)));
          break;
        default:
          orList.add(SqlStdOperatorTable.IN.createCall(POS, operandSql, list));
        }
      } else {
        final RangeSets.Consumer<C> consumer =
            new RangeToSql<>(operandSql, orList, v ->
                toSql(program,
                    implementor().rexBuilder.makeLiteral(v, type, false, true)));
        RangeSets.forEach(sarg.rangeSet, consumer);
      }
      return SqlUtil.createCall(SqlStdOperatorTable.OR, POS, orList);
    }

    /** Converts an expression from {@link RexWindowBound} to {@link SqlNode}
     * format.
     *
     * @param rexWindowBound Expression to convert
     */
    public SqlNode toSql(RexWindowBound rexWindowBound) {
      final SqlNode offsetLiteral =
          rexWindowBound.getOffset() == null
              ? null
              : SqlLiteral.createCharString(rexWindowBound.getOffset().toString(),
                  SqlParserPos.ZERO);
      if (rexWindowBound.isPreceding()) {
        return offsetLiteral == null
            ? SqlWindow.createUnboundedPreceding(POS)
            : SqlWindow.createPreceding(offsetLiteral, POS);
      } else if (rexWindowBound.isFollowing()) {
        return offsetLiteral == null
            ? SqlWindow.createUnboundedFollowing(POS)
            : SqlWindow.createFollowing(offsetLiteral, POS);
      } else {
        assert rexWindowBound.isCurrentRow();
        return SqlWindow.createCurrentRow(POS);
      }
    }

    public List<SqlNode> toSql(Window.Group group, ImmutableList<RexLiteral> constants,
        int inputFieldCount) {
      final List<SqlNode> rexOvers = new ArrayList<>();
      final List<SqlNode> partitionKeys = new ArrayList<>();
      final List<SqlNode> orderByKeys = new ArrayList<>();
      for (int partition: group.keys) {
        partitionKeys.add(this.field(partition));
      }
      for (RelFieldCollation collation: group.orderKeys.getFieldCollations()) {
        this.addOrderItem(orderByKeys, collation);
      }
      SqlLiteral isRows = SqlLiteral.createBoolean(group.isRows, POS);
      SqlNode lowerBound = null;
      SqlNode upperBound = null;

      final SqlLiteral allowPartial = null;

      for (Window.RexWinAggCall winAggCall: group.aggCalls) {
        SqlAggFunction aggFunction = (SqlAggFunction) winAggCall.getOperator();
        final SqlWindow sqlWindow = SqlWindow.create(null, null,
                new SqlNodeList(partitionKeys, POS), new SqlNodeList(orderByKeys, POS),
                isRows, lowerBound, upperBound, allowPartial, POS);
        if (aggFunction.allowsFraming()) {
          lowerBound = createSqlWindowBound(group.lowerBound);
          upperBound = createSqlWindowBound(group.upperBound);
          sqlWindow.setLowerBound(lowerBound);
          sqlWindow.setUpperBound(upperBound);
        }

        RexShuttle replaceConstants = new RexShuttle() {
          @Override public RexNode visitInputRef(RexInputRef inputRef) {
            int index = inputRef.getIndex();
            RexNode ref;
            if (index > inputFieldCount - 1) {
              ref = constants.get(index - inputFieldCount);
            } else {
              ref = inputRef;
            }
            return ref;
          }
        };
        RexCall aggCall = (RexCall) winAggCall.accept(replaceConstants);
        List<SqlNode> operands = toSql(null, aggCall.operands);
        rexOvers.add(createOverCall(aggFunction, operands, sqlWindow, winAggCall.distinct));
      }
      return rexOvers;
    }

    protected Context getAliasContext(RexCorrelVariable variable) {
      throw new UnsupportedOperationException();
    }

    private SqlCall toSql(@Nullable RexProgram program, RexOver rexOver) {
      final RexWindow rexWindow = rexOver.getWindow();
      final SqlNodeList partitionList = new SqlNodeList(
          toSql(program, rexWindow.partitionKeys), POS);

      List<SqlNode> orderNodes = Expressions.list();
      if (rexWindow.orderKeys != null) {
        for (RexFieldCollation rfc : rexWindow.orderKeys) {
          addOrderItem(orderNodes, program, rfc);
        }
      }
      final SqlNodeList orderList =
          new SqlNodeList(orderNodes, POS);

      final SqlLiteral isRows =
          SqlLiteral.createBoolean(rexWindow.isRows(), POS);

      // null defaults to true.
      // During parsing the allowPartial == false (e.g. disallow partial)
      // is expand into CASE expression and is handled as a such.
      // Not sure if we can collapse this CASE expression back into
      // "disallow partial" and set the allowPartial = false.
      final SqlLiteral allowPartial = null;

      SqlAggFunction sqlAggregateFunction = rexOver.getAggOperator();

      SqlNode lowerBound = null;
      SqlNode upperBound = null;

      if (sqlAggregateFunction.allowsFraming()) {
        lowerBound = createSqlWindowBound(rexWindow.getLowerBound());
        upperBound = createSqlWindowBound(rexWindow.getUpperBound());
      }

      final SqlWindow sqlWindow = SqlWindow.create(null, null, partitionList,
          orderList, isRows, lowerBound, upperBound, allowPartial, POS);

      final List<SqlNode> nodeList = toSql(program, rexOver.getOperands());
      return createOverCall(sqlAggregateFunction, nodeList, sqlWindow, rexOver.isDistinct());
    }

    private static SqlCall createOverCall(SqlAggFunction op, List<SqlNode> operands,
        SqlWindow window, boolean isDistinct) {
      if (op instanceof SqlSumEmptyIsZeroAggFunction) {
        // Rewrite "SUM0(x) OVER w" to "COALESCE(SUM(x) OVER w, 0)"
        final SqlCall node =
            createOverCall(SqlStdOperatorTable.SUM, operands, window, isDistinct);
        return SqlStdOperatorTable.COALESCE.createCall(POS, node,
            SqlLiteral.createExactNumeric("0", POS));
      }
      SqlCall aggFunctionCall;
      if (isDistinct) {
        aggFunctionCall = op.createCall(
            SqlSelectKeyword.DISTINCT.symbol(POS),
            POS,
            operands);
      } else {
        aggFunctionCall = op.createCall(POS, operands);
      }
      return SqlStdOperatorTable.OVER.createCall(POS, aggFunctionCall,
          window);
    }

    private SqlNode toSql(@Nullable RexProgram program, RexFieldCollation rfc) {
      SqlNode node = toSql(program, rfc.left);
      switch (rfc.getDirection()) {
      case DESCENDING:
      case STRICTLY_DESCENDING:
        node = SqlStdOperatorTable.DESC.createCall(POS, node);
        break;
      default:
        break;
      }
      if (rfc.getNullDirection()
              != dialect.defaultNullDirection(rfc.getDirection())) {
        switch (rfc.getNullDirection()) {
        case FIRST:
          node = SqlStdOperatorTable.NULLS_FIRST.createCall(POS, node);
          break;
        case LAST:
          node = SqlStdOperatorTable.NULLS_LAST.createCall(POS, node);
          break;
        default:
          break;
        }
      }
      return node;
    }

    private SqlNode createSqlWindowBound(RexWindowBound rexWindowBound) {
      if (rexWindowBound.isCurrentRow()) {
        return SqlWindow.createCurrentRow(POS);
      }
      if (rexWindowBound.isPreceding()) {
        if (rexWindowBound.isUnbounded()) {
          return SqlWindow.createUnboundedPreceding(POS);
        } else {
          SqlNode literal = toSql(null, rexWindowBound.getOffset());
          return SqlWindow.createPreceding(literal, POS);
        }
      }
      if (rexWindowBound.isFollowing()) {
        if (rexWindowBound.isUnbounded()) {
          return SqlWindow.createUnboundedFollowing(POS);
        } else {
          SqlNode literal = toSql(null, rexWindowBound.getOffset());
          return SqlWindow.createFollowing(literal, POS);
        }
      }

      throw new AssertionError("Unsupported Window bound: "
          + rexWindowBound);
    }

    private List<SqlNode> toSql(@Nullable RexProgram program, List<RexNode> operandList) {
      final List<SqlNode> list = new ArrayList<>();
      for (RexNode rex : operandList) {
        list.add(toSql(program, rex));
      }
      return list;
    }

    public List<SqlNode> fieldList() {
      return new AbstractList<SqlNode>() {
        @Override public SqlNode get(int index) {
          return field(index);
        }

        @Override public int size() {
          return fieldCount;
        }
      };
    }

    void addOrderItem(List<SqlNode> orderByList, RelFieldCollation field) {
      if (field.nullDirection != RelFieldCollation.NullDirection.UNSPECIFIED) {
        final boolean first =
            field.nullDirection == RelFieldCollation.NullDirection.FIRST;
        SqlNode nullDirectionNode =
            dialect.emulateNullDirection(field(field.getFieldIndex()),
                first, field.direction.isDescending());
        if (nullDirectionNode != null) {
          orderByList.add(nullDirectionNode);
          field = new RelFieldCollation(field.getFieldIndex(),
              field.getDirection(),
              RelFieldCollation.NullDirection.UNSPECIFIED);
        }
      }
      orderByList.add(toSql(field));
    }

    /** Converts a RexFieldCollation to an ORDER BY item. */
    private void addOrderItem(List<SqlNode> orderByList,
        @Nullable RexProgram program, RexFieldCollation field) {
      SqlNode node = toSql(program, field.left);
      SqlNode nullDirectionNode = null;
      if (field.getNullDirection() != RelFieldCollation.NullDirection.UNSPECIFIED) {
        final boolean first =
                  field.getNullDirection() == RelFieldCollation.NullDirection.FIRST;
        nullDirectionNode = dialect.emulateNullDirection(
                node, first, field.getDirection().isDescending());
      }
      if (nullDirectionNode != null) {
        orderByList.add(nullDirectionNode);
        switch (field.getDirection()) {
        case DESCENDING:
        case STRICTLY_DESCENDING:
          node = SqlStdOperatorTable.DESC.createCall(POS, node);
          break;
        default:
          break;
        }
        orderByList.add(node);
      } else {
        orderByList.add(toSql(program, field));
      }
    }

    /** Converts a call to an aggregate function to an expression. */
    public SqlNode toSql(AggregateCall aggCall) {
      return toSql(aggCall.getAggregation(), aggCall.isDistinct(),
          Util.transform(aggCall.getArgList(), this::field),
          aggCall.filterArg, aggCall.collation);
    }

    /** Converts a call to an aggregate function, with a given list of operands,
     * to an expression. */
    private SqlCall toSql(SqlOperator op, boolean distinct,
        List<SqlNode> operandList, int filterArg, RelCollation collation) {
      final SqlLiteral qualifier =
          distinct ? SqlSelectKeyword.DISTINCT.symbol(POS) : null;
      if (op instanceof SqlSumEmptyIsZeroAggFunction) {
        final SqlNode node = toSql(SqlStdOperatorTable.SUM, distinct,
            operandList, filterArg, collation);
        return SqlStdOperatorTable.COALESCE.createCall(POS, node,
            SqlLiteral.createExactNumeric("0", POS));
      }

      // Handle filter on dialects that do support FILTER by generating CASE.
      if (filterArg >= 0 && !dialect.supportsAggregateFunctionFilter()) {
        // SUM(x) FILTER(WHERE b)  ==>  SUM(CASE WHEN b THEN x END)
        // COUNT(*) FILTER(WHERE b)  ==>  COUNT(CASE WHEN b THEN 1 END)
        // COUNT(x) FILTER(WHERE b)  ==>  COUNT(CASE WHEN b THEN x END)
        // COUNT(x, y) FILTER(WHERE b)  ==>  COUNT(CASE WHEN b THEN x END, y)
        final SqlNodeList whenList = SqlNodeList.of(field(filterArg));
        final SqlNodeList thenList =
            SqlNodeList.of(operandList.isEmpty()
                ? SqlLiteral.createExactNumeric("1", POS)
                : operandList.get(0));
        final SqlNode elseList = SqlLiteral.createNull(POS);
        final SqlCall caseCall =
            SqlStdOperatorTable.CASE.createCall(null, POS, null, whenList,
                thenList, elseList);
        final List<SqlNode> newOperandList = new ArrayList<>();
        newOperandList.add(caseCall);
        if (operandList.size() > 1) {
          newOperandList.addAll(Util.skip(operandList));
        }
        return toSql(op, distinct, newOperandList, -1, collation);
      }

      if (op instanceof SqlCountAggFunction && operandList.isEmpty()) {
        // If there is no parameter in "count" function, add a star identifier
        // to it.
        operandList = ImmutableList.of(SqlIdentifier.STAR);
      }
      final SqlCall call =
          op.createCall(qualifier, POS, operandList);

      // Handle filter by generating FILTER (WHERE ...)
      final SqlCall call2;
      if (filterArg < 0) {
        call2 = call;
      } else {
        assert dialect.supportsAggregateFunctionFilter(); // we checked above
        call2 = SqlStdOperatorTable.FILTER.createCall(POS, call,
            field(filterArg));
      }

      // Handle collation
      return withOrder(call2, collation);
    }

    /** Wraps a call in a {@link SqlKind#WITHIN_GROUP} call, if
     * {@code collation} is non-empty. */
    private SqlCall withOrder(SqlCall call, RelCollation collation) {
      if (collation.getFieldCollations().isEmpty()) {
        return call;
      }
      final List<SqlNode> orderByList = new ArrayList<>();
      for (RelFieldCollation field : collation.getFieldCollations()) {
        addOrderItem(orderByList, field);
      }
      return SqlStdOperatorTable.WITHIN_GROUP.createCall(POS, call,
          new SqlNodeList(orderByList, POS));
    }

    /** Converts a collation to an ORDER BY item. */
    public SqlNode toSql(RelFieldCollation collation) {
      SqlNode node = orderField(collation.getFieldIndex());
      switch (collation.getDirection()) {
      case DESCENDING:
      case STRICTLY_DESCENDING:
        node = SqlStdOperatorTable.DESC.createCall(POS, node);
        break;
      default:
        break;
      }
      if (collation.nullDirection != dialect.defaultNullDirection(collation.direction)) {
        switch (collation.nullDirection) {
        case FIRST:
          node = SqlStdOperatorTable.NULLS_FIRST.createCall(POS, node);
          break;
        case LAST:
          node = SqlStdOperatorTable.NULLS_LAST.createCall(POS, node);
          break;
        default:
          break;
        }
      }
      return node;
    }

    public abstract SqlImplementor implementor();

    /** Converts a {@link Range} to a SQL expression.
     *
     * @param <C> Value type */
    private static class RangeToSql<C extends Comparable<C>>
        implements RangeSets.Consumer<C> {
      private final List<SqlNode> list;
      private final Function<C, SqlNode> literalFactory;
      private final SqlNode arg;

      RangeToSql(SqlNode arg, List<SqlNode> list,
          Function<C, SqlNode> literalFactory) {
        this.arg = arg;
        this.list = list;
        this.literalFactory = literalFactory;
      }

      private void addAnd(SqlNode... nodes) {
        list.add(
            SqlUtil.createCall(SqlStdOperatorTable.AND, POS,
                ImmutableList.copyOf(nodes)));
      }

      private SqlNode op(SqlOperator op, C value) {
        return op.createCall(POS, arg, literalFactory.apply(value));
      }

      @Override public void all() {
        list.add(SqlLiteral.createBoolean(true, POS));
      }

      @Override public void atLeast(C lower) {
        list.add(op(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, lower));
      }

      @Override public void atMost(C upper) {
        list.add(op(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, upper));
      }

      @Override public void greaterThan(C lower) {
        list.add(op(SqlStdOperatorTable.GREATER_THAN, lower));
      }

      @Override public void lessThan(C upper) {
        list.add(op(SqlStdOperatorTable.LESS_THAN, upper));
      }

      @Override public void singleton(C value) {
        list.add(op(SqlStdOperatorTable.EQUALS, value));
      }

      @Override public void closed(C lower, C upper) {
        addAnd(op(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, lower),
            op(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, upper));
      }

      @Override public void closedOpen(C lower, C upper) {
        addAnd(op(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, lower),
            op(SqlStdOperatorTable.LESS_THAN, upper));
      }

      @Override public void openClosed(C lower, C upper) {
        addAnd(op(SqlStdOperatorTable.GREATER_THAN, lower),
            op(SqlStdOperatorTable.LESS_THAN_OR_EQUAL, upper));
      }

      @Override public void open(C lower, C upper) {
        addAnd(op(SqlStdOperatorTable.GREATER_THAN, lower),
            op(SqlStdOperatorTable.LESS_THAN, upper));
      }
    }
  }

  /** Converts a {@link RexLiteral} in the context of a {@link RexProgram}
   * to a {@link SqlNode}. */
  public static SqlNode toSql(
      @Nullable RexProgram program, RexLiteral literal, SqlDialect dialect) {
    switch (literal.getTypeName()) {
    case SYMBOL:
      final Enum symbol = (Enum) literal.getValue();
      return SqlLiteral.createSymbol(symbol, POS);

    case ROW:
      //noinspection unchecked
      final List<RexLiteral> list = castNonNull(literal.getValueAs(List.class));
      return SqlStdOperatorTable.ROW.createCall(POS,
          list.stream().map(e -> toSql(program, e, dialect))
              .collect(Util.toImmutableList()));

    case SARG:
      final Sarg arg = literal.getValueAs(Sarg.class);
      throw new AssertionError("sargs [" + arg
          + "] should be handled as part of predicates, not as literals");

    default:
      return toSql(literal, dialect);
    }
  }

  /** Converts a {@link RexLiteral} to a {@link SqlLiteral}. */
  public static SqlNode toSql(RexLiteral literal, SqlDialect dialect) {
    SqlTypeName typeName = literal.getTypeName();
    switch (typeName) {
    case SYMBOL:
      final Enum symbol = (Enum) literal.getValue();
      return SqlLiteral.createSymbol(symbol, POS);

    case ROW:
      //noinspection unchecked
      final List<RexLiteral> list = castNonNull(literal.getValueAs(List.class));
      return SqlStdOperatorTable.ROW.createCall(POS,
          list.stream().map(e -> toSql(e, dialect))
              .collect(Util.toImmutableList()));

    case SARG:
      final Sarg arg = literal.getValueAs(Sarg.class);
      throw new AssertionError("sargs [" + arg
          + "] should be handled as part of predicates, not as literals");
    default:
      break;
    }
    SqlTypeFamily family = requireNonNull(typeName.getFamily(),
        () -> "literal " + literal + " has null SqlTypeFamily, and is SqlTypeName is " + typeName);
    switch (family) {
    case CHARACTER:
      return SqlLiteral.createCharString((String) castNonNull(literal.getValue2()), POS);
    case NUMERIC:
    case EXACT_NUMERIC:
      return SqlLiteral.createExactNumeric(
          castNonNull(literal.getValueAs(BigDecimal.class)).toPlainString(), POS);
    case APPROXIMATE_NUMERIC:
      return SqlLiteral.createApproxNumeric(
          castNonNull(literal.getValueAs(BigDecimal.class)).toPlainString(), POS);
    case BOOLEAN:
      return SqlLiteral.createBoolean(castNonNull(literal.getValueAs(Boolean.class)),
          POS);
    case INTERVAL_YEAR_MONTH:
    case INTERVAL_DAY_TIME:
      final boolean negative = castNonNull(literal.getValueAs(Boolean.class));
      return SqlLiteral.createInterval(negative ? -1 : 1,
          castNonNull(literal.getValueAs(String.class)),
          castNonNull(literal.getType().getIntervalQualifier()), POS);
    case DATE:
      return SqlLiteral.createDate(castNonNull(literal.getValueAs(DateString.class)),
          POS);
    case TIME:
      return dialect.getTimeLiteral(castNonNull(literal.getValueAs(TimeString.class)),
        literal.getType().getPrecision(), POS);
    case TIMESTAMP:
      TimestampString timestampString = literal.getValueAs(TimestampString.class);
      int precision = literal.getType().getPrecision();

      if (typeName == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE) {
        return SqlLiteral.createTimestamp(timestampString, precision, POS);
      }

      return dialect.getTimestampLiteral(castNonNull(timestampString), precision, POS);
    case ANY:
    case NULL:
      switch (typeName) {
      case NULL:
        return SqlLiteral.createNull(POS);
      default:
        break;
      }
      // fall through
    default:
      throw new AssertionError(literal + ": " + typeName);
    }
  }

  /** Simple implementation of {@link Context} that cannot handle sub-queries
   * or correlations. Because it is so simple, you do not need to create a
   * {@link SqlImplementor} or {@link org.apache.calcite.tools.RelBuilder}
   * to use it. It is a good way to convert a {@link RexNode} to SQL text. */
  public static class SimpleContext extends Context {
    private final IntFunction<SqlNode> field;

    public SimpleContext(SqlDialect dialect, IntFunction<SqlNode> field) {
      super(dialect, 0, false);
      this.field = field;
    }

    @Override public SqlImplementor implementor() {
      throw new UnsupportedOperationException();
    }

    public SqlNode field(int ordinal, boolean useAlias) {
      throw new IllegalStateException("SHouldn't be here");
    }

    @Override public SqlNode field(int ordinal) {
      return field.apply(ordinal);
    }
  }

  /** Implementation of {@link Context} that has an enclosing
   * {@link SqlImplementor} and can therefore do non-trivial expressions. */
  protected abstract class BaseContext extends Context {
    BaseContext(SqlDialect dialect, int fieldCount) {
      super(dialect, fieldCount);
    }

    @Override protected Context getAliasContext(RexCorrelVariable variable) {
      return requireNonNull(
          correlTableMap.get(variable.id),
          () -> "variable " + variable.id + " is not found");
    }

    @Override public SqlImplementor implementor() {
      return SqlImplementor.this;
    }
  }

  private static int computeFieldCount(
      Map<String, RelDataType> aliases) {
    int x = 0;
    for (RelDataType type : aliases.values()) {
      x += type.getFieldCount();
    }
    return x;
  }

  public Context aliasContext(Map<String, RelDataType> aliases,
      boolean qualified) {
    return new AliasContext(dialect, aliases, qualified);
  }

  public Context joinContext(Context leftContext, Context rightContext) {
    return new JoinContext(dialect, leftContext, rightContext);
  }

  public Context matchRecognizeContext(Context context) {
    return new MatchRecognizeContext(dialect, ((AliasContext) context).aliases);
  }

  public Context tableFunctionScanContext(List<SqlNode> inputSqlNodes) {
    return new TableFunctionScanContext(dialect, inputSqlNodes);
  }

  /** Context for translating MATCH_RECOGNIZE clause. */
  public class MatchRecognizeContext extends AliasContext {
    protected MatchRecognizeContext(SqlDialect dialect,
        Map<String, RelDataType> aliases) {
      super(dialect, aliases, false);
    }

    @Override public SqlNode toSql(@Nullable RexProgram program, RexNode rex) {
      if (rex.getKind() == SqlKind.LITERAL) {
        final RexLiteral literal = (RexLiteral) rex;
        if (literal.getTypeName().getFamily() == SqlTypeFamily.CHARACTER) {
          return new SqlIdentifier(castNonNull(RexLiteral.stringValue(literal)), POS);
        }
      }
      return super.toSql(program, rex);
    }
  }

  /** Implementation of Context that precedes field references with their
   * "table alias" based on the current sub-query's FROM clause. */
  public class AliasContext extends BaseContext {
    private final boolean qualified;
    private final Map<String, RelDataType> aliases;

    /** Creates an AliasContext; use {@link #aliasContext(Map, boolean)}. */
    protected AliasContext(SqlDialect dialect,
        Map<String, RelDataType> aliases, boolean qualified) {
      super(dialect, computeFieldCount(aliases));
      this.aliases = aliases;
      this.qualified = qualified;
    }

    public SqlNode field(int ordinal, boolean useAlias) {
      //Falling back to default behaviour & ignoring useAlias.
      // We can handle this as & when use cases arise.
      return field(ordinal);
    }

    @Override public SqlNode field(int ordinal) {
      for (Map.Entry<String, RelDataType> alias : aliases.entrySet()) {
        final List<RelDataTypeField> fields = alias.getValue().getFieldList();
        if (ordinal < fields.size()) {
          RelDataTypeField field = fields.get(ordinal);
          final SqlNode mappedSqlNode =
              ordinalMap.get(field.getName().toLowerCase(Locale.ROOT));
          if (mappedSqlNode != null) {
            return mappedSqlNode;
          }
          return new SqlIdentifier(!qualified
              ? ImmutableList.of(field.getName())
              : ImmutableList.of(alias.getKey(), field.getName()),
              POS);
        }
        ordinal -= fields.size();
      }
      throw new AssertionError(
          "field ordinal " + ordinal + " out of range " + aliases);
    }
  }

  /** Context for translating ON clause of a JOIN from {@link RexNode} to
   * {@link SqlNode}. */
  class JoinContext extends BaseContext {
    private final SqlImplementor.Context leftContext;
    private final SqlImplementor.Context rightContext;

    /** Creates a JoinContext; use {@link #joinContext(Context, Context)}. */
    private JoinContext(SqlDialect dialect, Context leftContext,
        Context rightContext) {
      super(dialect, leftContext.fieldCount + rightContext.fieldCount);
      this.leftContext = leftContext;
      this.rightContext = rightContext;
    }

    public SqlNode field(int ordinal, boolean useAlias) {
      throw new IllegalStateException("SHouldn't be here");
    }

    @Override public SqlNode field(int ordinal) {
      if (ordinal < leftContext.fieldCount) {
        return leftContext.field(ordinal);
      } else {
        return rightContext.field(ordinal - leftContext.fieldCount);
      }
    }
  }
  /** Context for translating call of a TableFunctionScan from {@link RexNode} to
   * {@link SqlNode}. */
  class TableFunctionScanContext extends BaseContext {
    private final List<SqlNode> inputSqlNodes;

    TableFunctionScanContext(SqlDialect dialect, List<SqlNode> inputSqlNodes) {
      super(dialect, inputSqlNodes.size());
      this.inputSqlNodes = inputSqlNodes;
    }

    @Override public SqlNode field(int ordinal) {
      return inputSqlNodes.get(ordinal);
    }

    @Override public SqlNode field(int ordinal, boolean useAlias) {
      throw new IllegalStateException("Shouldn't be here");
    }
  }

  /** Result of implementing a node. */
  public class Result {
    final SqlNode node;
    final @Nullable String neededAlias;
    private final @Nullable RelDataType neededType;
    private final Map<String, RelDataType> aliases;
    final List<Clause> clauses;
    private final boolean anon;
    /** Whether to treat {@link #expectedClauses} as empty for the
     * purposes of figuring out whether we need a new sub-query. */
    private final boolean ignoreClauses;
    /** Clauses that will be generated to implement current relational
     * expression. */
    private final ImmutableSet<Clause> expectedClauses;
    private final @Nullable RelNode expectedRel;
    private final boolean needNew;
    private RelToSqlUtils relToSqlUtils = new RelToSqlUtils();

    public Result(SqlNode node, Collection<Clause> clauses, @Nullable String neededAlias,
        @Nullable RelDataType neededType, Map<String, RelDataType> aliases) {
      this(node, clauses, neededAlias, neededType, aliases, false, false,
          ImmutableSet.of(), null);
    }

    private Result(SqlNode node, Collection<Clause> clauses, @Nullable String neededAlias,
        @Nullable RelDataType neededType, Map<String, RelDataType> aliases, boolean anon,
        boolean ignoreClauses, Set<Clause> expectedClauses,
        @Nullable RelNode expectedRel) {
      this.node = node;
      this.neededAlias = neededAlias;
      this.neededType = neededType;
      this.aliases = aliases;
      this.clauses = ImmutableList.copyOf(clauses);
      this.anon = anon;
      this.ignoreClauses = ignoreClauses;
      this.expectedClauses = ImmutableSet.copyOf(expectedClauses);
      this.expectedRel = expectedRel;
      final Set<Clause> clauses2 =
          ignoreClauses ? ImmutableSet.of() : expectedClauses;
      this.needNew = expectedRel != null
          && needNewSubQuery(expectedRel, this.clauses, clauses2);
    }

    /** Creates a builder for the SQL of the given relational expression,
     * using the clauses that you declared when you called
     * {@link #visitInput(RelNode, int, Set)}. */
    public Builder builder(RelNode rel) {
      return builder(rel, expectedClauses);
    }

    // CHECKSTYLE: IGNORE 3
    /** @deprecated Provide the expected clauses up-front, when you call
     * {@link #visitInput(RelNode, int, Set)}, then create a builder using
     * {@link #builder(RelNode)}. */
    @Deprecated // to be removed before 2.0
    public Builder builder(RelNode rel, Clause clause, Clause... clauses) {
      return builder(rel, ImmutableSet.copyOf(Lists.asList(clause, clauses)));
    }

    /** Once you have a Result of implementing a child relational expression,
     * call this method to create a Builder to implement the current relational
     * expression by adding additional clauses to the SQL query.
     *
     * <p>You need to declare which clauses you intend to add. If the clauses
     * are "later", you can add to the same query. For example, "GROUP BY" comes
     * after "WHERE". But if they are the same or earlier, this method will
     * start a new SELECT that wraps the previous result.
     *
     * <p>When you have called
     * {@link Builder#setSelect(SqlNodeList)},
     * {@link Builder#setWhere(SqlNode)} etc. call
     * {@link Builder#result(SqlNode, Collection, RelNode, Map)}
     * to fix the new query.
     *
     * @param rel Relational expression being implemented
     * @return A builder
     */
    private Builder builder(RelNode rel, Set<Clause> clauses) {
      assert expectedClauses.containsAll(clauses);
      assert rel.equals(expectedRel);
      final Set<Clause> clauses2 = ignoreClauses ? ImmutableSet.of() : clauses;
      boolean needNew = needNewSubQuery(rel, this.clauses, clauses2);
      assert needNew == this.needNew;
      boolean keepColumnAlias = false;

      if (rel instanceof LogicalSort
          && dialect.getConformance().isSortByAlias()) {
        keepColumnAlias = true;
      }

      SqlSelect select;
      Expressions.FluentList<Clause> clauseList = Expressions.list();
      if (needNew) {
        select = subSelect();
      } else {
        select = asSelect();
        clauseList.addAll(this.clauses);
      }
      clauseList.appendAll(clauses);
      final Context newContext;
      Map<String, RelDataType> newAliases = null;
      final SqlNodeList selectList = select.getSelectList();
      if (selectList != null) {
        final boolean aliasRef = expectedClauses.contains(Clause.HAVING)
            && dialect.getConformance().isHavingAlias() || keepColumnAlias;
        newContext = new Context(dialect, selectList.size()) {
          @Override public SqlImplementor implementor() {
            return SqlImplementor.this;
          }

          @Override public SqlNode field(int ordinal) {
            final SqlNode selectItem = selectList.get(ordinal);
            switch (selectItem.getKind()) {
            case AS:
              final SqlCall asCall = (SqlCall) selectItem;
              if (aliasRef) {
                // For BigQuery, given the query
                //   SELECT SUM(x) AS x FROM t HAVING(SUM(t.x) > 0)
                // we can generate
                //   SELECT SUM(x) AS x FROM t HAVING(x > 0)
                // because 'x' in HAVING resolves to the 'AS x' not 't.x'.
                return asCall.operand(1);
              }
              return asCall.operand(0);
            default:
              break;
            }
            return selectItem;
          }

          public SqlNode field(int ordinal, boolean useAlias) {
            final SqlNode selectItem = selectList.get(ordinal);
            switch (selectItem.getKind()) {
            case AS:
              if (useAlias) {
                return ((SqlCall) selectItem).operand(1);
              }
              return ((SqlCall) selectItem).operand(0);
            }
            return selectItem;
          }

          @Override public SqlNode orderField(int ordinal) {
            // If the field expression is an unqualified column identifier
            // and matches a different alias, use an ordinal.
            // For example, given
            //    SELECT deptno AS empno, empno AS x FROM emp ORDER BY emp.empno
            // we generate
            //    SELECT deptno AS empno, empno AS x FROM emp ORDER BY 2
            // "ORDER BY empno" would give incorrect result;
            // "ORDER BY x" is acceptable but is not preferred.
            final SqlNode node = field(ordinal);
            if (node instanceof SqlIdentifier
                && ((SqlIdentifier) node).isSimple()) {
              final String name = ((SqlIdentifier) node).getSimple();
              for (Ord<SqlNode> selectItem : Ord.zip(selectList)) {
                if (selectItem.i != ordinal) {
                  final String alias =
                      SqlValidatorUtil.getAlias(selectItem.e, -1);
                  if (name.equalsIgnoreCase(alias)) {
                    return SqlLiteral.createExactNumeric(
                        Integer.toString(ordinal + 1), SqlParserPos.ZERO);
                  }
                }
              }
            }
            return node;
          }
        };
      } else {
        boolean qualified =
            (
                !dialect.hasImplicitTableAlias() || (!dialect.supportsIdenticalTableAndColumnName()
                && isTableNameColumnNameIdentical)) || aliases.size() > 1;
        // basically, we did a subSelect() since needNew is set and neededAlias is not null
        // now, we need to make sure that we need to update the alias context.
        // if our aliases map has a single element:  <neededAlias, rowType>,
        // then we don't need to rewrite the alias but otherwise, it should be updated.
        if (needNew
            && neededAlias != null
            && (aliases.size() != 1 || !aliases.containsKey(neededAlias))) {
          newAliases =
              ImmutableMap.of(neededAlias, rel.getInput(0).getRowType());
          newContext = aliasContext(newAliases, qualified);
        } else {
          newContext = aliasContext(aliases, qualified);
        }
      }
      return new Builder(rel, clauseList, select, newContext, isAnon(),
          needNew && !aliases.containsKey(neededAlias) ? newAliases : aliases);
    }

    private boolean hasAnalyticalFunctionInAggregate(Aggregate rel) {
      boolean present = false;
      if (node instanceof SqlSelect) {
        final SqlNodeList selectList = ((SqlSelect) node).getSelectList();
        if (selectList != null) {
          final Set<Integer> aggregatesArgs = new HashSet<>();
          for (AggregateCall aggregateCall : rel.getAggCallList()) {
            aggregatesArgs.addAll(aggregateCall.getArgList());
          }
          for (int aggregatesArg : aggregatesArgs) {
            if (selectList.get(aggregatesArg) instanceof SqlBasicCall) {
              final SqlBasicCall call =
                  (SqlBasicCall) selectList.get(aggregatesArg);
              present = hasAnalyticalFunction(call);
              if (!present) {
                present = hasAnalyticalFunctionInWhenClauseOfCase(call);
              }
            }
          }
        }
      }
      return present;
    }

    private boolean hasAliasUsedInHavingClause() {
      SqlSelect sqlNode = (SqlSelect) this.node;
      if (!ifSqlBasicCallAliased(sqlNode)) {
        return false;
      }
      List<String> aliases = getAliases(sqlNode.getSelectList());
      return ifAliasUsedInHavingClause(aliases, (SqlBasicCall) sqlNode.getHaving());
    }

    private boolean ifSqlBasicCallAliased(SqlSelect sqlSelectNode) {
      for (SqlNode sqlNode: sqlSelectNode.getSelectList()) {
        if (sqlNode instanceof SqlBasicCall
            && ((SqlBasicCall) sqlNode).getOperator() != SqlStdOperatorTable.AS) {
          return false;
        }
      }
      return true;
    }


    private boolean ifAliasUsedInHavingClause(List<String> aliases, SqlBasicCall havingClauseCall) {
      List<SqlNode> sqlNodes = havingClauseCall.getOperandList();
      for (SqlNode node : sqlNodes) {
        if (node instanceof SqlBasicCall) {
          return ifAliasUsedInHavingClause(aliases, (SqlBasicCall) node);
        } else if (node instanceof SqlIdentifier) {
          boolean aliasUsed = aliases.contains(node.toString());
          if (aliasUsed) {
            return true;
          }
        }
      }
      return false;
    }


    private List<String> getAliases(SqlNodeList sqlNodes) {
      List<String> aliases = new ArrayList<>();
      for (SqlNode node : sqlNodes) {
        if (node instanceof SqlBasicCall && ((SqlBasicCall) node).getOperator()
            == SqlStdOperatorTable.AS) {
          aliases.add(((SqlBasicCall) node).getOperands()[1].toString());
        }
      }
      return aliases;
    }

    private boolean hasAnalyticalFunctionInWhenClauseOfCase(SqlBasicCall call) {
      SqlNode sqlNode = call.operand(0);
      if (sqlNode instanceof SqlCall) {
        if (((SqlCall) sqlNode).getOperator() instanceof SqlCaseOperator) {
          for (SqlNode whenOperand : ((SqlCase) sqlNode).getWhenOperands()) {
            boolean present = hasAnalyticalFunction((SqlBasicCall) whenOperand);
            if (present) {
              return true;
            }
          }
        }
      }
      return false;
    }

    private boolean hasAnalyticalFunction(SqlBasicCall call) {
      for (SqlNode operand : call.getOperands()) {
        if (operand instanceof SqlCall) {
          if (((SqlCall) operand).getOperator() instanceof SqlOverOperator) {
            return true;
          }
        }
      }
      return false;
    }

    private boolean hasAnalyticalFunctionUsedInGroupBy(Aggregate rel) {
      if (node instanceof SqlSelect) {
        Project projectRel = (Project) rel.getInput(0);
        for (int i = 0; i < projectRel.getRowType().getFieldNames().size(); i++) {
          if (relToSqlUtils.isAnalyticalRex(projectRel.getChildExps().get(i))) {
            return true;
          }
        }
      }
      return false;
    }

    private boolean hasAggFunctionUsedInGroupBy(Project project) {
      if (!(node instanceof SqlSelect && ((SqlSelect) node).getGroup() != null)) {
        return false;
      }
      List<RexNode> expressions = project.getChildExps();
      List<Pair<Integer, List<RexInputRef>>> identifiersPerSelectListItem = new ArrayList<>();
      int index = 0;
      for (RexNode expr : expressions) {
        identifiersPerSelectListItem.add(new Pair<>(index, getIdentifiers(expr)));
        index++;
      }
      List<String> columnNames = new ArrayList<>();
      List<SqlNode> selectList = ((SqlSelect) node).getSelectList().getList();
      for (Pair<Integer, List<RexInputRef>> identifiersWithIndex : identifiersPerSelectListItem) {
        boolean hasAggFunction = false;
        for (RexInputRef identifier: identifiersWithIndex.right) {
          SqlNode sqlNode = selectList.get(identifier.getIndex());
          if (sqlNode instanceof SqlCall) {
            if (hasSpecifiedFunction((SqlCall) sqlNode, SqlAggFunction.class)) {
              hasAggFunction = true;
            }
          }
        }
        if (hasAggFunction) {
          columnNames.
            add(project.getRowType().getFieldList().get(identifiersWithIndex.left).getName());
        }
      }
      List<SqlNode> groupByList = ((SqlSelect) node).getGroup().getList();
      for (SqlNode groupByItem : groupByList) {
        if (groupByItem instanceof SqlIdentifier
            && columnNames.contains(SqlIdentifier.getString(((SqlIdentifier) groupByItem).names))) {
          return true;
        }
      }
      return false;
    }

    List<RexInputRef> getIdentifiers(RexNode rexNode) {
      List<RexInputRef> identifiers = new ArrayList<>();
      if (rexNode instanceof RexInputRef) {
        identifiers.add((RexInputRef) rexNode);
      } else if (rexNode instanceof RexCall) {
        for (RexNode operand : ((RexCall) rexNode).getOperands()) {
          identifiers.addAll(getIdentifiers(operand));
        }
      }
      return identifiers;
    }

    private boolean hasSpecifiedFunction(SqlCall call, Class sqlOperator) {
      List<SqlNode> operands;
      if (sqlOperator.isInstance(call.getOperator())) {
        return true;
      } else if (call instanceof SqlCase) {
        operands = getListOfCaseOperands((SqlCase) call);
      } else {
        operands = call.getOperandList();
      }
      for (SqlNode sqlNode : operands) {
        if (sqlNode instanceof SqlCall) {
          if (hasSpecifiedFunction((SqlCall) sqlNode, sqlOperator)) {
            return true;
          }
        }
      }
      return false;
    }

    List<SqlNode> getListOfCaseOperands(SqlCase sqlCase) {
      List<SqlNode> operandList = new ArrayList<>();
      operandList.add(sqlCase.getValueOperand());
      operandList.addAll(sqlCase.getWhenOperands().getList());
      operandList.addAll(sqlCase.getThenOperands().getList());
      operandList.add(sqlCase.getElseOperand());
      return operandList;
    }

    /** Returns whether a new sub-query is required. */
    private boolean needNewSubQuery(
        @UnknownInitialization Result this,
        RelNode rel, List<Clause> clauses,
        Set<Clause> expectedClauses) {
      if (clauses.isEmpty()) {
        return false;
      }
      final Clause maxClause = Collections.max(clauses);

      // Previously, below query is getting translated with SubQuery logic (Queries like -
      // Analytical Function with WHERE clause). Now, it will remain as it is after translation.
      // select c1, ROW_NUMBER() OVER (PARTITION by c1 ORDER BY c2) as rnk from t1 where c3 = 'MA'
      // Here, if query contains any filter which does not have analytical function in it and
      // has any projection with Analytical function used then new SELECT wrap is not required.
      if (dialect.supportsQualifyClause() && rel instanceof Filter
          && rel.getInput(0) instanceof Project
          && relToSqlUtils.isAnalyticalFunctionPresentInProjection((Project) rel.getInput(0))
          && !relToSqlUtils.hasAnalyticalFunctionInFilter((Filter) rel)) {
        if (maxClause == Clause.SELECT) {
          return false;
        }
      }

      // If old and new clause are equal and belong to below set,
      // then new SELECT wrap is not required
      final Set<Clause> nonWrapSet = ImmutableSet.of(Clause.SELECT);
      for (Clause clause : expectedClauses) {
        if (maxClause.ordinal() > clause.ordinal()
            || (maxClause == clause
                && !nonWrapSet.contains(clause))) {
          return true;
        }
      }

      if (rel instanceof Project && rel.getInput(0) instanceof Aggregate
          && !dialect.supportAggInGroupByClause() && hasAggFunctionUsedInGroupBy((Project) rel)) {
        return true;
      }

      if (rel instanceof Project && rel.getInput(0) instanceof Project
          && !dialect.supportNestedAnalyticalFunctions()
          && hasNestedAnalyticalFunctions((Project) rel)) {
        return true;
      }

      if (rel instanceof Aggregate
          && !dialect.supportsNestedAggregations()
          && hasNestedAggregations((Aggregate) rel)) {
        return true;
      }

      if (rel instanceof Project && rel.getInput(0) instanceof Aggregate
          && dialect.getConformance().isGroupByAlias()
          && hasAliasUsedInGroupByWhichIsNotPresentInFinalProjection((Project) rel)) {
        return true;
      }

      if (rel instanceof Aggregate && rel.getInput(0) instanceof Project
          && dialect.getConformance().isGroupByAlias()
          && hasAnalyticalFunctionUsedInGroupBy((Aggregate) rel)) {
        return true;
      }

      if (rel instanceof Aggregate
          && !dialect.supportsAnalyticalFunctionInAggregate()
          && hasAnalyticalFunctionInAggregate((Aggregate) rel)) {
        return  true;
      }

      if (rel instanceof LogicalSort && rel.getInput(0) instanceof LogicalIntersect) {
        return true;
      }

      if (rel instanceof Project
          && clauses.contains(Clause.HAVING)
          && dialect.getConformance().isHavingAlias()
          && hasAliasUsedInHavingClause()) {
        return true;
      }

      if (rel instanceof Project
          && ((Project) rel).containsOver()
          && maxClause == Clause.SELECT) {
        // Cannot merge a Project that contains windowed functions onto an
        // underlying Project
        return true;
      }

      if (rel instanceof Aggregate) {
        final Aggregate agg = (Aggregate) rel;
        final boolean hasNestedAgg = hasNestedAggregations(agg);
        if (!dialect.supportsNestedAggregations()
            && hasNestedAgg) {
          return true;
        }

        if (clauses.contains(Clause.GROUP_BY)) {
          // Avoid losing the distinct attribute of inner aggregate.
          return !hasNestedAgg || Aggregate.isNotGrandTotal(agg);
        }
        if (rel instanceof Aggregate
            && rel.getInput(0) instanceof RavenDistinctProject
            && ((RavenDistinctProject) rel.getInput(0)).isDistinct()) {
          return true;
        }
      }

      if (rel instanceof Project
          && clauses.contains(Clause.HAVING)
          && !hasAliasUsedInHavingClause()
          && hasAliasUsedInGroupByWhichIsNotPresentInFinalProjection((Project) rel)) {
        stripHavingClauseIfAggregateFromProjection();
        return true;
      }

      return false;
    }


    private boolean hasNestedAggregations(
        @UnknownInitialization Result this,
        Aggregate rel) {
      if (node instanceof SqlSelect) {
        final SqlNodeList selectList = ((SqlSelect) node).getSelectList();
        if (selectList != null) {
          final Set<Integer> aggregatesArgs = new HashSet<>();
          for (AggregateCall aggregateCall : rel.getAggCallList()) {
            aggregatesArgs.addAll(aggregateCall.getArgList());
          }
          for (int aggregatesArg : aggregatesArgs) {
            if (selectList.get(aggregatesArg) instanceof SqlBasicCall) {
              final SqlBasicCall call =
                  (SqlBasicCall) selectList.get(aggregatesArg);
              for (SqlNode operand : call.getOperands()) {
                if (operand instanceof SqlCall
                    && ((SqlCall) operand).getOperator() instanceof SqlAggFunction) {
                  return true;
                }
              }
            }
          }
        }
      }
      return false;
    }

    private void stripHavingClauseIfAggregateFromProjection() {
      final SqlNodeList selectList = ((SqlSelect) node).getSelectList();
      final SqlNode havingSelectList = ((SqlBasicCall) ((SqlSelect) node).getHaving()).operands[0];
      if (selectList != null && havingSelectList != null
          && havingSelectList instanceof SqlCall
          && ((SqlCall) havingSelectList).getOperator().isAggregator()) {
        selectList.remove(havingSelectList);
        ((SqlSelect) node).setSelectList(selectList);
      }
    }
    boolean hasAliasUsedInGroupByWhichIsNotPresentInFinalProjection(Project rel) {
      final SqlNodeList selectList = ((SqlSelect) node).getSelectList();
      final SqlNodeList grpList = ((SqlSelect) node).getGroup();
      if (selectList != null && grpList != null) {
        for (SqlNode grpNode : grpList) {
          if (grpNode instanceof SqlIdentifier) {
            String grpCall = ((SqlIdentifier) grpNode).names.get(0);
            for (SqlNode selectNode : selectList.getList()) {
              if (selectNode instanceof SqlBasicCall) {
                if (grpCallIsAlias(grpCall, (SqlBasicCall) selectNode)
                    && !grpCallPresentInFinalProjection(grpCall, rel)) {
                  return true;
                }
              }
            }
          }
        }
      }
      return false;
    }

    boolean grpCallIsAlias(String grpCall, SqlBasicCall selectCall) {
      return selectCall.getOperator() instanceof SqlAsOperator
        && grpCall.equals(selectCall.operand(1).toString());
    }

    boolean grpCallPresentInFinalProjection(String grpCall, Project rel) {
      List<String> projFieldList = rel.getRowType().getFieldNames();
      for (String finalProj : projFieldList) {
        if (grpCall.equals(finalProj)) {
          return true;
        }
      }
      return false;
    }

    private boolean hasNestedAnalyticalFunctions(Project rel) {
      if (!(node instanceof SqlSelect)) {
        return false;
      }
      final SqlNodeList selectList = ((SqlSelect) node).getSelectList();
      if (selectList == null) {
        return false;
      }
      List<RexInputRef> rexInputRefsInAnalytical = new ArrayList<>();
      for (RexNode rexNode : rel.getChildExps()) {
        if (relToSqlUtils.isAnalyticalRex(rexNode)) {
          rexInputRefsInAnalytical.addAll(getIdentifiers(rexNode));
        }
      }
      if (rexInputRefsInAnalytical.isEmpty()) {
        return false;
      }
      for (RexInputRef rexInputRef : rexInputRefsInAnalytical) {
        SqlNode sqlNode = selectList.get(rexInputRef.getIndex());
        boolean hasAnalyticalFunction = false;
        if (sqlNode instanceof SqlCall) {
          hasAnalyticalFunction = hasSpecifiedFunction((SqlCall) sqlNode, SqlOverOperator.class);
        }
        if (hasAnalyticalFunction) {
          return true;
        }
      }
      return false;
    }

    /** Returns the highest clause that is in use. */
    @Deprecated
    public Clause maxClause() {
      return Collections.max(clauses);
    }

    /** Returns a node that can be included in the FROM clause or a JOIN. It has
     * an alias that is unique within the query. The alias is implicit if it
     * can be derived using the usual rules (For example, "SELECT * FROM emp" is
     * equivalent to "SELECT * FROM emp AS emp".) */
    public SqlNode asFrom() {
      if (neededAlias != null) {
        if (node.getKind() == SqlKind.AS) {
          // If we already have an AS node, we need to replace the alias
          // This is especially relevant for the VALUES clause rendering
          SqlCall sqlCall = (SqlCall) node;
          @SuppressWarnings("assignment.type.incompatible")
          SqlNode[] operands = sqlCall.getOperandList().toArray(new SqlNode[0]);
          operands[1] = new SqlIdentifier(neededAlias, POS);
          return SqlStdOperatorTable.AS.createCall(POS, operands);
        } else {
          return SqlStdOperatorTable.AS.createCall(POS, node,
              new SqlIdentifier(neededAlias, POS));
        }
      }
      return node;
    }

    public SqlSelect subSelect() {
      return wrapSelect(asFrom());
    }

    /** Converts a non-query node into a SELECT node. Set operators (UNION,
     * INTERSECT, EXCEPT) remain as is. */
    public SqlSelect asSelect() {
      if (node instanceof SqlSelect) {
        return (SqlSelect) node;
      }
      if (!dialect.hasImplicitTableAlias() || (!dialect.supportsIdenticalTableAndColumnName()
          && isTableNameColumnNameIdentical)) {
        return wrapSelect(asFrom());
      }
      return wrapSelect(node);
    }

    public void stripTrivialAliases(SqlNode node) {
      switch (node.getKind()) {
      case SELECT:
        final SqlSelect select = (SqlSelect) node;
        final SqlNodeList nodeList = select.getSelectList();
        if (nodeList != null) {
          for (int i = 0; i < nodeList.size(); i++) {
            final SqlNode n = nodeList.get(i);
            if (n.getKind() == SqlKind.AS) {
              final SqlCall call = (SqlCall) n;
              final SqlIdentifier identifier = call.operand(1);
              if (identifier.getSimple().toLowerCase(Locale.ROOT)
                  .startsWith("expr$")) {
                nodeList.set(i, call.operand(0));
              }
            }
          }
        }
        break;

      case UNION:
      case INTERSECT:
      case EXCEPT:
      case INSERT:
      case UPDATE:
      case DELETE:
      case MERGE:
        final SqlCall call = (SqlCall) node;
        for (SqlNode operand : call.getOperandList()) {
          if (operand != null) {
            stripTrivialAliases(operand);
          }
        }
        break;
      default:
        break;
      }
    }

    /** Strips trivial aliases if anon. */
    private SqlNode maybeStrip(SqlNode node) {
      if (anon) {
        stripTrivialAliases(node);
      }
      return node;
    }

    /** Converts a non-query node into a SELECT node. Set operators (UNION,
     * INTERSECT, EXCEPT) and DML operators (INSERT, UPDATE, DELETE, MERGE)
     * remain as is. */
    public SqlNode asStatement() {
      switch (node.getKind()) {
      case UNION:
      case INTERSECT:
      case EXCEPT:
      case INSERT:
      case UPDATE:
      case DELETE:
      case MERGE:
        return maybeStrip(node);
      default:
        return maybeStrip(asSelect());
      }
    }

    /** Converts a non-query node into a SELECT node. Set operators (UNION,
     * INTERSECT, EXCEPT) and VALUES remain as is. */
    public SqlNode asQueryOrValues() {
      switch (node.getKind()) {
      case UNION:
      case INTERSECT:
      case EXCEPT:
      case VALUES:
        return maybeStrip(node);
      default:
        return maybeStrip(asSelect());
      }
    }

    /** Returns a context that always qualifies identifiers. Useful if the
     * Context deals with just one arm of a join, yet we wish to generate
     * a join condition that qualifies column names to disambiguate them. */
    public Context qualifiedContext() {
      return aliasContext(aliases, true);
    }

    /**
     * In join, when the left and right nodes have been generated,
     * update their alias with 'neededAlias' if not null.
     */
    public Result resetAlias() {
      if (neededAlias == null) {
        return this;
      } else {
        return new Result(node, clauses, neededAlias, neededType,
            ImmutableMap.of(neededAlias, castNonNull(neededType)), anon, ignoreClauses,
            expectedClauses, expectedRel);
      }
    }

    /**
     * Sets the alias of the join or correlate just created.
     *
     * @param alias New alias
     * @param type type of the node associated with the alias
     */
    public Result resetAlias(String alias, RelDataType type) {
      return new Result(node, clauses, alias, neededType,
          ImmutableMap.of(alias, type), anon, ignoreClauses,
          expectedClauses, expectedRel);
    }

    /** Returns a copy of this Result, overriding the value of {@code anon}. */
    Result withAnon(boolean anon) {
      return anon == this.anon ? this
          : new Result(node, clauses, neededAlias, neededType, aliases, anon,
              ignoreClauses, expectedClauses, expectedRel);
    }

    /** Returns a copy of this Result, overriding the value of
     * {@code ignoreClauses} and {@code expectedClauses}. */
    Result withExpectedClauses(boolean ignoreClauses,
        Set<? extends Clause> expectedClauses, RelNode expectedRel) {
      return ignoreClauses == this.ignoreClauses
          && expectedClauses.equals(this.expectedClauses)
          && expectedRel == this.expectedRel
          ? this
          : new Result(node, clauses, neededAlias, neededType, aliases, anon,
              ignoreClauses, ImmutableSet.copyOf(expectedClauses), expectedRel);
    }
  }

  /** Builder. */
  public class Builder {
    private final RelNode rel;
    final List<Clause> clauses;
    final SqlSelect select;
    public final Context context;
    final boolean anon;
    private final @Nullable Map<String, RelDataType> aliases;

    public Builder(RelNode rel, List<Clause> clauses, SqlSelect select,
        Context context, boolean anon,
        @Nullable Map<String, RelDataType> aliases) {
      this.rel = requireNonNull(rel);
      this.clauses = ImmutableList.copyOf(clauses);
      this.select = requireNonNull(select);
      this.context = requireNonNull(context);
      this.anon = anon;
      this.aliases = aliases;
    }

    public void setSelect(SqlNodeList nodeList) {
      select.setSelectList(nodeList);
    }

    public void setWhere(SqlNode node) {
      assert clauses.contains(Clause.WHERE);
      select.setWhere(node);
    }

    public void setGroupBy(SqlNodeList nodeList) {
      assert clauses.contains(Clause.GROUP_BY);
      select.setGroupBy(nodeList);
    }

    public void setHaving(SqlNode node) {
      assert clauses.contains(Clause.HAVING);
      select.setHaving(node);
    }

    public void setQualify(SqlNode node) {
      assert clauses.contains(Clause.QUALIFY);
      select.setQualify(node);
    }

    public void setOrderBy(SqlNodeList nodeList) {
      assert clauses.contains(Clause.ORDER_BY);
      select.setOrderBy(nodeList);
    }

    public void setFetch(SqlNode fetch) {
      assert clauses.contains(Clause.FETCH);
      select.setFetch(fetch);
    }

    public void setOffset(SqlNode offset) {
      assert clauses.contains(Clause.OFFSET);
      select.setOffset(offset);
    }

    public void addOrderItem(List<SqlNode> orderByList,
        RelFieldCollation field) {
      context.addOrderItem(orderByList, field);
    }

    public Result result() {
      return SqlImplementor.this.result(select, clauses, rel, aliases)
          .withAnon(anon);
    }
  }

  /** Clauses in a SQL query. Ordered by evaluation order.
   * SELECT is set only when there is a NON-TRIVIAL SELECT clause. */
  public enum Clause {
    FROM, WHERE, GROUP_BY, HAVING, QUALIFY, SELECT, SET_OP, ORDER_BY, FETCH, OFFSET
  }

  /**
   * Method returns a tableName from relNode.
   * It covers below cases
   * <p>
   * Case 1:- LogicalProject OR LogicalFilter
   * * e.g. - SELECT employeeName FROM employeeTable;
   * * e.g. - SELECT * FROM employeeTable Where employeeLastName = 'ABC';
   * * e.g. - SELECT employeeName FROM employeeTable Where employeeLastName = 'ABC';
   * * Query contains Projection and Filter. Here the method will return 'employeeTable'.
   * <p>
   * Case 2:- LogicalTableScan (Table Scan)
   * * e.g. - SELECT * FROM employeeTable
   * * Query contains TableScan. Here the method will return 'employeeTable'.
   * <p>
   * Case 3 :- Default case
   * Currently this case is invoked for below query.
   * * e.g. - SELECT DISTINCT employeeName FROM employeeTable
   * * e.g. - SELECT 0 as ZERO
   * * Method will return alias.
   *
   * @param alias rel
   * @return tableName it returns tableName from relNode
   */
  private String getTableName(String alias, RelNode rel) {
    String tableName = null;
    if (rel instanceof LogicalFilter || rel instanceof LogicalProject) {
      if (rel.getInput(0).getTable() != null) {
        tableName =
            rel.getInput(0).getTable().getQualifiedName().
                get(rel.getInput(0).getTable().getQualifiedName().size() - 1);
      }
    } else if (rel instanceof LogicalTableScan) {
      tableName =
          rel.getTable().getQualifiedName().get(rel.getTable().getQualifiedName().size() - 1);

    } else {
      tableName = alias;
    }
    return tableName;
  }
}
